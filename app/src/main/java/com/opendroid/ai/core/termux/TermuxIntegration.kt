package com.opendroid.ai.core.termux

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.opendroid.ai.core.storage.StorageWorkspaceProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Explicit, bounded local tasks. No AI-generated or free-form shell input is accepted. */
enum class TermuxTask(val label: String, val executable: String, val arguments: Array<String>) {
    CHECK_HOME("Check Termux home", "sh", arrayOf("-lc", "pwd")),
    LIST_FILES("List Termux files", "ls", arrayOf("-la")),
    SYSTEM_INFO("Read Termux system info", "uname", arrayOf("-a")),
    UPDATE_PACKAGES("Refresh package index", "pkg", arrayOf("update", "-y")),
    INSTALL_DEV_TOOLS(
        "Run zero-cost Termux setup.sh (Python, Node.js, Git, build tools)",
        "sh",
        arrayOf(
            "-lc",
            "curl --fail --silent --show-error --location 'https://raw.githubusercontent.com/saedhawwa23-rgb/Chatgpt-/a3e027a22970e1ed07a0c9e1178d4258516c1b3b/scripts/termux/setup.sh' -o \"\$HOME/setup.sh\" && bash \"\$HOME/setup.sh\""
        )
    ),
    INSTALL_OPEN_SOURCE_AGENTS(
        "Clone OpenManus and OpenSwarm; attempt Python dependencies",
        "sh",
        arrayOf(
            "-lc",
            "curl --fail --silent --show-error --location 'https://raw.githubusercontent.com/saedhawwa23-rgb/Chatgpt-/adb1fb99b7a564191cdcf4a3ecd537fe1d37ba10/scripts/termux/install-agents.sh' -o \"\$HOME/install-agents.sh\" && bash \"\$HOME/install-agents.sh\""
        )
    ),
    STOP_AGENT_JOBS(
        "Send SIGINT to OpenManus/OpenSwarm processes",
        "sh",
        arrayOf(
            "-lc",
            "curl --fail --silent --show-error --location 'https://raw.githubusercontent.com/saedhawwa23-rgb/Chatgpt-/14a0aa8509f01da09d21bfdffc1b16cbbbcf7e56/scripts/termux/stop-agents.sh' -o \"\$HOME/stop-agents.sh\" && bash \"\$HOME/stop-agents.sh\""
        )
    )
}

data class TermuxCommand(val label: String, val executable: String, val arguments: List<String>)

object TermuxVersion {
    fun atLeast(value: String?, major: Int, minor: Int): Boolean {
        val parts = value?.split('.')?.mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() } ?: return false
        val currentMajor = parts.getOrNull(0) ?: return false
        val currentMinor = parts.getOrNull(1) ?: 0
        return currentMajor > major || currentMajor == major && currentMinor >= minor
    }
}

/** Only canonical public GitHub repository URLs are accepted; argv is never shell-interpolated. */
object TermuxGitHubClone {
    private val repoPart = Regex("[A-Za-z0-9_.-]{1,100}")
    private val ownerPart = Regex("[A-Za-z0-9-]{1,39}")

    fun command(url: String): TermuxCommand {
        val uri = java.net.URI(url.trim())
        require(uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("github.com", ignoreCase = true)) {
            "Use an https://github.com/owner/repository URL."
        }
        require(uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null) {
            "GitHub clone links cannot include credentials, custom ports, queries, or fragments."
        }
        val segments = uri.path.trim('/').split('/')
        require(segments.size == 2) { "Enter a repository URL in the form https://github.com/owner/repository." }
        val owner = segments[0]
        val repository = segments[1].removeSuffix(".git")
        require(ownerPart.matches(owner) && repoPart.matches(repository) && repository != "." && repository != "..") {
            "The GitHub owner or repository name is invalid."
        }
        val destination = "${owner}-${repository}"
        return TermuxCommand("Clone $owner/$repository", "git", listOf("clone", "--depth=1", "https://github.com/$owner/$repository.git", destination))
    }
}

data class TermuxResult(
    val executionId: Int,
    val taskLabel: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val error: String? = null,
    val completedAtMs: Long = System.currentTimeMillis()
) {
    val succeeded: Boolean get() = error == null && exitCode == 0
}

@Singleton
class TermuxIntegration @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
        private const val RESULT_ACTION = "com.usmanandumar.app.TERMUX_RESULT"
        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

        // Official Termux RUN_COMMAND/TermuxConstants wire keys.
        private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        private const val EXTRA_RESULT_BUNDLE = "result"
        private const val EXTRA_STDOUT = "stdout"
        private const val EXTRA_STDERR = "stderr"
        private const val EXTRA_EXIT_CODE = "exitCode"
        private const val EXTRA_ERROR = "err"
        private const val EXTRA_ERROR_MESSAGE = "errmsg"
        private const val EXTRA_EXECUTION_ID = "execution_id"
        private const val MAX_RESULTS = 30
        private const val MAX_COMMAND_LENGTH = 64 * 1024
        private const val COMMAND_TIMEOUT_MS = 30_000L

        private val nextExecutionId = AtomicInteger(SystemClock.elapsedRealtime().toInt().and(Int.MAX_VALUE).coerceAtLeast(1))
    }

    private val pendingTasks = ConcurrentHashMap<Int, TermuxCommand>()
    private val waiters = ConcurrentHashMap<Int, CompletableDeferred<TermuxResult>>()
    private val _results = MutableStateFlow<List<TermuxResult>>(emptyList())
    val results: StateFlow<List<TermuxResult>> = _results.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
            val task = pendingTasks.remove(id) ?: return
            val bundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
            val internalErrorCode = bundle?.getInt(EXTRA_ERROR, Activity.RESULT_OK) ?: Activity.RESULT_CANCELED
            val error = when {
                bundle == null -> "Termux returned no result bundle. Update Termux to version 0.109 or later."
                internalErrorCode != Activity.RESULT_OK -> bundle.getString(EXTRA_ERROR_MESSAGE).orEmpty()
                    .ifBlank { "Termux could not start or finish the command (error $internalErrorCode)." }
                else -> null
            }
            val commandResult = TermuxResult(
                executionId = id,
                taskLabel = task.label,
                stdout = bundle?.getString(EXTRA_STDOUT).orEmpty(),
                stderr = bundle?.getString(EXTRA_STDERR).orEmpty(),
                exitCode = bundle?.takeIf { error == null }?.getInt(EXTRA_EXIT_CODE),
                error = error
            )
            // Preserve agent setup/stop logs in the selected SAF workspace. If the
            // user connected Google Drive in Settings, this writes through Drive's
            // authorized document provider; otherwise it writes to app workspace.
            val result = if (
                task.label.contains("OpenManus", ignoreCase = true) ||
                task.label.contains("SIGINT", ignoreCase = true)
            ) {
                val safeLabel = task.label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                val logText = buildString {
                    appendLine("Task: ${task.label}")
                    appendLine("Execution ID: $id")
                    appendLine("Exit code: ${commandResult.exitCode ?: "unknown"}")
                    commandResult.error?.let { appendLine("Error: $it") }
                    appendLine()
                    appendLine("--- stdout ---")
                    appendLine(commandResult.stdout)
                    appendLine()
                    appendLine("--- stderr ---")
                    appendLine(commandResult.stderr)
                }
                val saved = runCatching {
                    StorageWorkspaceProvider.writeFile(context, "agent-results/$safeLabel-$id.txt", logText)
                }.getOrNull()
                if (saved?.success == true) {
                    commandResult.copy(stdout = commandResult.stdout + "\\n[Agent log saved to workspace: agent-results/$safeLabel-$id.txt]")
                } else {
                    commandResult.copy(stderr = commandResult.stderr + "\\n[Agent log export failed: ${saved?.error ?: "storage provider error"}]")
                }
            } else {
                commandResult
            }
            waiters.remove(id)?.complete(result)
            _results.value = (_results.value + result).takeLast(MAX_RESULTS)
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(RESULT_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun versionName(): String? = runCatching {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0).versionName
    }.getOrNull()

    fun supportsResultCallbacks(): Boolean = TermuxVersion.atLeast(versionName(), 0, 109)

    fun isCommandExecutionAvailable(): Boolean =
        isInstalled() && supportsResultCallbacks() && hasRunCommandPermission()

    fun availabilityMessage(): String = when {
        !isInstalled() -> "Termux is not installed."
        !supportsResultCallbacks() -> "Termux ${versionName() ?: "version"} is too old for captured command results; install Termux 0.109 or later."
        !hasRunCommandPermission() -> "Grant ‘Run commands in Termux environment’ to USMAN AND UMAR in Android app permissions."
        else -> "Termux command execution is available. Termux must also have allow-external-apps=true in ~/.termux/termux.properties."
    }

    fun hasRunCommandPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        RUN_COMMAND_PERMISSION
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Sends one allowlisted executable and argv array to Termux; no shell concatenation is used.
     * User must separately enable Termux's `allow-external-apps` setting and grant RUN_COMMAND.
     */
    fun run(task: TermuxTask): Result<Int> = run(TermuxCommand(task.label, task.executable, task.arguments.toList()))

    /**
     * Runs user-entered shell text through Termux's background RUN_COMMAND API.
     * This returns a bounded command result and is intentionally not presented as a PTY.
     */
    suspend fun runShellCommand(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): TermuxResult {
        require(command.isNotBlank()) { "Enter a command first." }
        require(command.length <= MAX_COMMAND_LENGTH) { "Command is too long." }
        check(isCommandExecutionAvailable()) { availabilityMessage() }
        val task = TermuxCommand("Linux command via Termux", "sh", listOf("-lc", command))
        val (executionId, waiter) = submit(task)
        return withTimeoutOrNull(timeoutMs) { waiter.await() }
            ?: TermuxResult(
                executionId = executionId,
                taskLabel = task.label,
                stdout = "",
                stderr = "",
                exitCode = null,
                error = "Timed out after ${timeoutMs / 1000}s. Check Termux is running, allow-external-apps=true is set, and battery optimization is disabled."
            ).also { waiters.remove(executionId) }
    }

    fun cloneGitHubRepository(url: String): Result<Int> = runCatching { TermuxGitHubClone.command(url) }
        .fold(onSuccess = ::run, onFailure = { Result.failure(it) })

    private fun termuxDataDir(): File = File(context.packageManager.getApplicationInfo(TERMUX_PACKAGE, 0).dataDir)

    private fun run(task: TermuxCommand): Result<Int> = runCatching { submit(task).first }

    private fun submit(task: TermuxCommand): Pair<Int, CompletableDeferred<TermuxResult>> {
        check(isInstalled()) { "Termux is not installed." }
        check(hasRunCommandPermission()) {
            "Grant ‘Run commands in Termux environment’ to USMAN AND UMAR in Android app permissions."
        }
        check(supportsResultCallbacks()) {
            "Termux ${versionName() ?: "version"} is too old for captured results; install Termux 0.109 or later."
        }

        val executionId = nextExecutionId.getAndUpdate { if (it >= Int.MAX_VALUE - 1) 1 else it + 1 }
        val waiter = CompletableDeferred<TermuxResult>()
        val callback = Intent(RESULT_ACTION).setPackage(context.packageName)
            .putExtra(EXTRA_EXECUTION_ID, executionId)
        val flags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(context, executionId, callback, flags)
        val termuxDir = termuxDataDir()
        val executable = File(termuxDir, "files/usr/bin/${task.executable}").absolutePath
        val workdir = File(termuxDir, "files/home").absolutePath
        val command = Intent(RUN_COMMAND_ACTION).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_PATH, executable)
            putExtra(EXTRA_ARGUMENTS, task.arguments.toTypedArray())
            putExtra(EXTRA_WORKDIR, workdir)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            putExtra("com.termux.RUN_COMMAND_LABEL", task.label)
            putExtra("com.termux.RUN_COMMAND_DESCRIPTION", "Started explicitly by the user from USMAN AND UMAR.")
        }
        pendingTasks[executionId] = task
        waiters[executionId] = waiter
        try {
            context.startService(command)
        } catch (failure: Throwable) {
            pendingTasks.remove(executionId)
            waiters.remove(executionId)
            pendingIntent.cancel()
            throw IllegalStateException(
                "Termux rejected RUN_COMMAND. Confirm the Android RUN_COMMAND permission and " +
                    "allow-external-apps=true in ~/.termux/termux.properties.",
                failure
            )
        }
        return executionId to waiter
    }

    fun close() {
        runCatching { context.unregisterReceiver(receiver) }
    }

}
