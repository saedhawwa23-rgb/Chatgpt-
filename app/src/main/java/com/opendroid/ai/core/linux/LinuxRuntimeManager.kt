package com.opendroid.ai.core.linux

import android.content.Context
import android.os.Build
import android.util.Log
import com.opendroid.ai.core.pty.PtySession
import com.opendroid.ai.core.security.GitHubTokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the app-private Alpine userspace and launches the pinned PRoot executable
 * from Android's extracted native-library directory.
 *
 * PRoot is packaged as lib/armeabi-v7a/libproot.so, but it is deliberately
 * executed as a file and is never loaded with System.loadLibrary(). Android's
 * native-library extraction gives the file an executable, noexec-safe location
 * on devices where app-private files storage is mounted non-executable.
 */
@Singleton
class LinuxRuntimeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val githubTokenStore: GitHubTokenStore
) {
    val rootDir: File get() = File(context.filesDir, RUNTIME_DIR)
    val rootfsDir: File get() = File(rootDir, ROOTFS_DIR)
    /** The packaged executable resolved through Android's native-library directory. */
    val prootFile: File get() = File(context.applicationInfo.nativeLibraryDir, PROOT_NAME)
    private val stateFile: File get() = File(rootDir, STATE_FILE)
    private val prootVerificationFile: File get() = File(rootDir, PROOT_VERIFIED_STATE)

    @Volatile
    private var lastInstallStage: String = "not-started"

    @Volatile
    private var lastInstallError: String? = null

    @Volatile
    private var lastProotCheck: ProotExecutionDiagnostics? = null

    suspend fun install(progress: (Long, Long) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        rootDir.mkdirs()
        val temporaryArchive = File(rootDir, "$ROOTFS_ARCHIVE.part")
        try {
            lastInstallError = null
            if (rootfsDir.resolve("bin/sh").isFile) {
                // A repair triggered by a missing native-library executable must not
                // replace the user's Alpine home or installed packages. The safe
                // extractor is reserved for first installation; an explicit reset
                // workflow would be required to replace an existing rootfs.
                lastInstallStage = "existing-rootfs-preserved"
                configureRepositories()
                lastInstallStage = "proot-verification"
                ensureProotReady()
                stateFile.writeText("$ROOTFS_VERSION\n$ROOTFS_SHA256\n", Charsets.UTF_8)
                lastInstallStage = "ready"
                return@withContext
            }
            lastInstallStage = "download"
            downloadVerified(ROOTFS_URL, ROOTFS_SHA256, temporaryArchive, progress)
            lastInstallStage = "archive-extraction"
            safeExtract(temporaryArchive, rootfsDir)
            temporaryArchive.delete()
            lastInstallStage = "repository-configuration"
            configureRepositories()
            lastInstallStage = "proot-verification"
            ensureProotReady()
            stateFile.writeText("$ROOTFS_VERSION\n$ROOTFS_SHA256\n", Charsets.UTF_8)
            lastInstallStage = "ready"
        } catch (failure: Throwable) {
            lastInstallError = failure.stackTraceText()
            Log.e(TAG, "Alpine installation failed at $lastInstallStage", failure)
            throw failure
        }
    }

    fun status(): RuntimeStatus = when {
        !rootfsDir.resolve("bin/sh").isFile -> RuntimeStatus.NOT_INSTALLED
        !hasVerifiedProot() -> RuntimeStatus.PROOT_MISSING
        else -> RuntimeStatus.READY
    }

    fun startShell(): PtySession {
        lastInstallStage = "shell-startup"
        ensureProotReady()
        check(status() == RuntimeStatus.READY) {
            "Integrated Linux is not ready. Install the verified ARM32 PRoot runtime and Alpine rootfs first."
        }
        val home = rootfsDir.resolve("home/usman").apply { mkdirs() }
        val gitAskPass = prepareGitAskPass()
        val githubToken = githubTokenStore.read()
        return try {
            PtySession.start(
                executable = prootFile.absolutePath,
                arguments = arrayOf(
                    PROOT_REQUIRED_OPTION,
                    "-0",
                    "-r", rootfsDir.absolutePath,
                    "-b", "/dev:/dev",
                    "-b", "/proc:/proc",
                    "-b", "${context.filesDir.absolutePath}:/android-app",
                    "/bin/sh", "-l"
                ),
                workingDirectory = home.absolutePath,
                environment = mapOf(
                    "HOME" to "/home/usman",
                    "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "TERM" to "xterm-256color",
                    "GIT_ASKPASS" to gitAskPass,
                    "GIT_USERNAME" to "x-access-token",
                    "GIT_TERMINAL_PROMPT" to "0"
                ) + githubToken?.let { mapOf("GITHUB_TOKEN" to it) }.orEmpty()
            )
        } catch (failure: Throwable) {
            lastInstallStage = "shell-process-launch"
            lastInstallError = failure.stackTraceText()
            throw IllegalStateException(
                "PRoot shell launch failed at shell-process-launch: ${failure.message}",
                failure
            )
        }
    }

    private fun prepareGitAskPass(): String {
        val helper = File(context.filesDir, GIT_ASKPASS_FILE)
        if (!helper.exists()) {
            helper.writeText("#!/bin/sh\nprintf '%s' \"\$GITHUB_TOKEN\"\n", Charsets.UTF_8)
            helper.setExecutable(true, false)
        }
        return "/android-app/$GIT_ASKPASS_FILE"
    }

    /**
     * Runs a bounded harmless PRoot check and returns a redacted diagnostic report.
     * This is also used by the UI's Copy Diagnostics action; it does not inspect
     * or include secrets, environment values, or user file contents.
     */
    fun diagnosticsReport(): String {
        val check = runCatching { verifyProotExecutable() }
            .getOrElse { failure ->
                ProotExecutionDiagnostics(
                    stage = "proot-verification",
                    path = prootFile.absolutePath,
                    fileSize = prootFile.takeIf { it.isFile }?.length() ?: 0L,
                    sha256 = sha256OrNull(prootFile),
                    fileType = elfDescription(prootFile),
                    readable = prootFile.canRead(),
                    writable = prootFile.canWrite(),
                    executable = prootFile.canExecute(),
                    stdout = "",
                    stderr = "",
                    exitCode = null,
                    timedOut = false,
                    exception = failure.stackTraceText()
                )
            }
        return buildString {
            appendLine("USMAN AND UMAR integrated Linux diagnostics")
            appendLine("androidRelease=${Build.VERSION.RELEASE}")
            appendLine("apiLevel=${Build.VERSION.SDK_INT}")
            appendLine("targetSdk=${context.applicationInfo.targetSdkVersion}")
            appendLine("supportedAbis=${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("nativeLibraryDir=${context.applicationInfo.nativeLibraryDir}")
            appendLine("resolvedProotPath=${prootFile.absolutePath}")
            appendLine("prootPathPolicy=PACKAGED_NATIVE_LIBRARY_ONLY")
            appendLine("legacyWritableProotPathRejected=${rootDir.resolve("proot-armv7").absolutePath}")
            appendLine("prootExpectedSha256=$PROOT_SHA256")
            appendLine("prootCommit=$PROOT_COMMIT")
            appendLine("prootRequiredOption=$PROOT_REQUIRED_OPTION")
            appendLine("prootFileSize=${check.fileSize}")
            appendLine("prootSha256=${check.sha256 ?: "unavailable"}")
            appendLine("prootFileType=${check.fileType}")
            appendLine("prootReadable=${check.readable}")
            appendLine("prootWritable=${check.writable}")
            appendLine("prootExecutable=${check.executable}")
            appendLine("prootLaunchStage=${check.stage}")
            appendLine("prootStdout=${check.stdout.trim().redactDiagnostics()}")
            appendLine("prootStderr=${check.stderr.trim().redactDiagnostics()}")
            appendLine("prootExitCode=${check.exitCode ?: "none"}")
            appendLine("prootTimedOut=${check.timedOut}")
            appendLine("prootException=${check.exception?.redactDiagnostics() ?: "none"}")
            appendLine("alpineStage=$lastInstallStage")
            appendLine("alpineRootfs=${rootfsDir.absolutePath}")
            appendLine("alpineRootfsPresent=${rootfsDir.resolve("bin/sh").isFile}")
            appendLine("alpineStatePresent=${stateFile.isFile}")
            appendLine("alpineLastError=${lastInstallError?.redactDiagnostics() ?: "none"}")
            appendLine("runtimeStatus=${status()}")
            appendLine("physicalDeviceVerification=NOT_VERIFIED")
        }
    }

    private fun hasVerifiedProot(): Boolean =
        prootFile.isFile &&
            prootFile.length() > 0L &&
            prootFile.canExecute() &&
            sha256OrNull(prootFile).equals(PROOT_SHA256, ignoreCase = true) &&
            prootVerificationFile.readTextOrNull() == PROOT_COMMIT

    private fun ensureProotReady() {
        check(prootFile.parentFile?.canonicalFile != rootDir.canonicalFile) {
            "Refusing to execute PRoot from writable linux-runtime storage"
        }
        val result = verifyProotExecutable()
        if (!result.success) {
            throw IllegalStateException(
                "PRoot verification failed at ${result.stage}: " +
                    (result.exception ?: result.stderr.ifBlank { "exit=${result.exitCode}" })
            )
        }
    }

    private fun verifyProotExecutable(): ProotExecutionDiagnostics {
        val path = prootFile
        val base = {
            ProotExecutionDiagnostics(
                stage = "proot-file-validation",
                path = path.absolutePath,
                fileSize = path.takeIf { it.isFile }?.length() ?: 0L,
                sha256 = sha256OrNull(path),
                fileType = elfDescription(path),
                readable = path.canRead(),
                writable = path.canWrite(),
                executable = path.canExecute(),
                stdout = "",
                stderr = "",
                exitCode = null,
                timedOut = false,
                exception = null
            )
        }
        val initial = base()
        if (!path.isFile || path.length() == 0L) {
            return initial.copy(exception = "native-library file is missing or empty").also { recordProotCheck(it) }
        }
        if (!initial.sha256.equals(PROOT_SHA256, ignoreCase = true)) {
            return initial.copy(exception = "native-library SHA-256 does not match pinned PRoot").also { recordProotCheck(it) }
        }
        if (!path.canExecute()) {
            return initial.copy(exception = "native-library file is not executable").also { recordProotCheck(it) }
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        return try {
            val process = ProcessBuilder(path.absolutePath, PROOT_REQUIRED_OPTION, "--version")
                .redirectErrorStream(false)
                .start()
            val stdoutReader = thread(start = true, name = "proot-diagnostics-stdout") {
                process.inputStream.bufferedReader().use { stdout.append(it.readText()) }
            }
            val stderrReader = thread(start = true, name = "proot-diagnostics-stderr") {
                process.errorStream.bufferedReader().use { stderr.append(it.readText()) }
            }
            val finished = process.waitFor(PROOT_EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            stdoutReader.join(1_000)
            stderrReader.join(1_000)
            val exitCode = if (finished) process.exitValue() else null
            ProotExecutionDiagnostics(
                stage = "proot-version-check",
                path = path.absolutePath,
                fileSize = initial.fileSize,
                sha256 = initial.sha256,
                fileType = initial.fileType,
                readable = initial.readable,
                writable = initial.writable,
                executable = initial.executable,
                stdout = stdout.toString(),
                stderr = stderr.toString(),
                exitCode = exitCode,
                timedOut = !finished,
                exception = null
            ).also { result ->
                if (result.success) prootVerificationFile.writeText(PROOT_COMMIT, Charsets.UTF_8)
                else prootVerificationFile.delete()
                recordProotCheck(result)
            }
        } catch (failure: Throwable) {
            initial.copy(
                stage = "proot-process-launch",
                stdout = stdout.toString(),
                stderr = stderr.toString(),
                exception = failure.stackTraceText()
            ).also { recordProotCheck(it) }
        }
    }

    private fun recordProotCheck(result: ProotExecutionDiagnostics) {
        lastProotCheck = result
    }

    private fun sha256OrNull(file: File): String? = runCatching {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun elfDescription(file: File): String = runCatching {
        if (!file.isFile) return "missing"
        FileInputStream(file).use { input ->
            val header = ByteArray(20)
            if (input.read(header) != header.size) return "unreadable"
            if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()) return "not-elf"
            val elfClass = when (header[4].toInt() and 0xff) { 1 -> "32-bit"; 2 -> "64-bit"; else -> "unknown-class" }
            val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
            "$elfClass ELF machine=$machine"
        }
    }.getOrElse { "unavailable: ${it.message}" }

    private fun configureRepositories() {
        val etc = rootfsDir.resolve("etc").apply { mkdirs() }
        etc.resolve("repositories").writeText(
            "https://dl-cdn.alpinelinux.org/alpine/$ROOTFS_VERSION/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/$ROOTFS_VERSION/community\n",
            Charsets.UTF_8
        )
    }

    private fun downloadVerified(
        url: String,
        expectedSha256: String,
        destination: File,
        progress: (Long, Long) -> Unit
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
        }
        try {
            check(connection.responseCode in 200..299) { "Rootfs download failed: HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var count = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        count += read
                        progress(count, total)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(expectedSha256, ignoreCase = true)) {
                "Rootfs checksum mismatch: expected $expectedSha256, got $actual"
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun safeExtract(archive: File, destination: File) {
        SafeArchiveExtractor.extract(
            archive = archive,
            destination = destination,
            allowTrustedGuestAbsoluteLinks = true
        )
    }

    companion object {
        const val ROOTFS_VERSION = "latest-stable"
        const val ROOTFS_URL = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/armv7/alpine-minirootfs-3.24.2-armv7.tar.gz"
        const val ROOTFS_SHA256 = "5552f1ef2398cee46ef2cf733f8f1258f39a77ad065dfd47282fc0df9fe1976c"
        const val PROOT_SOURCE = "https://github.com/termux/proot"
        const val PROOT_COMMIT = "b8c1f5c5c6730dc9d521d5bd5ce1cfeedecde087"
        const val PROOT_REQUIRED_OPTION = "--link2symlink"
        const val PROOT_SHA256 = "6ef736d86c8b9a6de61e8b417876594f9d8ad294a7d10b56110e4632cd2e6be7"
        private const val PROOT_EXECUTION_TIMEOUT_SECONDS = 5L
        private const val RUNTIME_DIR = "linux-runtime"
        private const val ROOTFS_DIR = "alpine-armv7"
        private const val ROOTFS_ARCHIVE = "alpine-minirootfs-3.24.2-armv7.tar.gz"
        private const val PROOT_NAME = "libproot.so"
        private const val STATE_FILE = "install.state"
        private const val PROOT_VERIFIED_STATE = "proot.verified"
        private const val GIT_ASKPASS_FILE = ".github-askpass"
        private const val TAG = "LinuxRuntimeManager"
    }
}

data class ProotExecutionDiagnostics(
    val stage: String,
    val path: String,
    val fileSize: Long,
    val sha256: String?,
    val fileType: String,
    val readable: Boolean,
    val writable: Boolean,
    val executable: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val exception: String?
) {
    val success: Boolean
        get() = exception == null && !timedOut && exitCode == 0
}

enum class RuntimeStatus { NOT_INSTALLED, PROOT_MISSING, READY }

private fun File.readTextOrNull(): String? =
    if (isFile) runCatching { readText(Charsets.UTF_8) }.getOrNull() else null

private fun Throwable.stackTraceText(): String =
    StringWriter().also { writer -> printStackTrace(PrintWriter(writer)) }.toString()

private fun String.redactDiagnostics(): String =
    replace(Regex("(?i)(token|password|secret|authorization)\\s*[=:]\\s*\\S+"), "\$1=[REDACTED]")
