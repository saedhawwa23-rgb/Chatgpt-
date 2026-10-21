package com.opendroid.ai.ui.screens

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.opendroid.ai.core.linux.RuntimeStatus
import com.opendroid.ai.core.storage.StorageWorkspaceProvider
import com.opendroid.ai.core.termux.TermuxGitHubClone
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.LinuxTerminalViewModel

@Composable
fun IntegratedTerminalScreen(viewModel: LinuxTerminalViewModel, onBack: () -> Unit) {
    val output by viewModel.output.collectAsState()
    val message by viewModel.message.collectAsState()
    val runtimeStatus by viewModel.status.collectAsState()
    val sessionStarted by viewModel.sessionStarted.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val termuxAvailable by viewModel.termuxAvailable.collectAsState()
    val termuxStatus by viewModel.termuxStatus.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var command by remember { mutableStateOf("echo USMAN_AND_UMAR_TERMINAL_OK") }
    var repositoryUrl by remember { mutableStateOf("") }
    var agentInstallStatus by remember { mutableStateOf<String?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var copyAfterRefresh by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.diagnostics()
    }
    LaunchedEffect(diagnostics, copyAfterRefresh) {
        if (copyAfterRefresh && diagnostics.isNotBlank()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("USMAN AND UMAR diagnostics", diagnostics))
            copyAfterRefresh = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("INTEGRATED LINUX WORKSPACE", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        },
        containerColor = AppTheme.colors.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("APP-OWNED LINUX + OPTIONAL TERMUX", fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
                Text(
                    "Local terminal and public GitHub cloning do not require AI or paid APIs. Install and verify the app-owned ARM32 Alpine/PRoot runtime first; Termux remains an optional fallback.",
                    color = AppTheme.colors.textSecondary
                )
                Text("App-owned runtime status: ${runtimeStatus.name}", color = AppTheme.colors.textSecondary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.install() }, modifier = Modifier.weight(1f)) {
                        Text("INSTALL / VERIFY LINUX")
                    }
                    OutlinedButton(
                        onClick = { viewModel.start() },
                        modifier = Modifier.weight(1f),
                        enabled = runtimeStatus == RuntimeStatus.READY && !sessionStarted
                    ) { Text(if (sessionStarted) "INTERNAL SHELL READY" else "START INTERNAL SHELL") }
                }
            }
            item {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Linux shell command") },
                    singleLine = true
                )
                Button(
                    onClick = { viewModel.send(command) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = sessionStarted
                ) { Text("RUN IN APP-OWNED LINUX") }
                OutlinedButton(
                    onClick = { viewModel.runViaTermux(command) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = termuxAvailable
                ) { Text("RUN VIA TERMUX (OPTIONAL FALLBACK)") }
                Text(termuxStatus, color = AppTheme.colors.textSecondary)
            }
            item {
                Text("OPENMANUS + OPENSWARM — APP-OWNED ALPINE", fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
                Text(
                    "This downloads both public repositories and attempts dependency installation inside the patched app-owned Linux workspace. ARM32 package compatibility is reported in the log; it is not assumed.",
                    color = AppTheme.colors.textSecondary
                )
                Button(
                    onClick = {
                        runCatching {
                            val workspace = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
                            val script = File(workspace, "install-agents-alpine.sh")
                            context.assets.open("install-agents-alpine.sh").use { input ->
                                script.outputStream().use { output -> input.copyTo(output) }
                            }
                            script.setExecutable(true, false)
                            viewModel.send("sh /workspace/install-agents-alpine.sh; rc=\$?; echo AGENT_INSTALL_EXIT:\$rc")
                            agentInstallStatus = "Agent setup started. Use REFRESH OUTPUT to collect its result, then export the log to Drive."
                        }.onFailure {
                            agentInstallStatus = "Could not prepare agent installer: ${it.message ?: "asset copy failed"}"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = sessionStarted
                ) { Text("INSTALL OPENMANUS + OPENSWARM") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.read() },
                        modifier = Modifier.weight(1f),
                        enabled = sessionStarted
                    ) { Text("REFRESH OUTPUT") }
                    OutlinedButton(
                        onClick = {
                            agentInstallStatus = "Exporting agent log to the selected workspace…"
                            coroutineScope.launch(Dispatchers.IO) {
                                val result = StorageWorkspaceProvider.copyFile(
                                    context,
                                    "agent-install-results.txt",
                                    "agent-results/agent-install-results.txt"
                                )
                                withContext(Dispatchers.Main) {
                                    agentInstallStatus = if (result.success) {
                                        result.data ?: "Agent log exported to selected workspace."
                                    } else {
                                        result.error ?: "Agent log export failed."
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("EXPORT LOG TO DRIVE") }
                }
                agentInstallStatus?.let { Text(it, color = AppTheme.colors.textSecondary) }
            }
            item {
                Text("PUBLIC GITHUB CLONE — NO AI REQUIRED", fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
                OutlinedTextField(
                    value = repositoryUrl,
                    onValueChange = { repositoryUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("https://github.com/owner/repository") },
                    singleLine = true
                )
                OutlinedButton(
                    onClick = {
                        runCatching { TermuxGitHubClone.command(repositoryUrl) }
                            .onSuccess { clone ->
                                val safeArgs = (listOf(clone.executable) + clone.arguments).joinToString(" ")
                                viewModel.send("if ! command -v git >/dev/null 2>&1; then apk add --no-cache git; fi && cd /workspace && $safeArgs")
                                command = safeArgs
                            }
                            .onFailure { failure ->
                                // Report validation errors in the local terminal without invoking AI.
                                val messageText = failure.message?.replace("'", "").orEmpty().ifBlank { "validation failed" }
                                command = "echo 'Invalid GitHub repository URL: $messageText'"
                                viewModel.send(command)
                            }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = repositoryUrl.isNotBlank() && sessionStarted
                ) { Text("CLONE PUBLIC REPOSITORY") }
                Text("Clone runs inside the app-owned Alpine workspace after installing Git if needed.", color = AppTheme.colors.textSecondary)
            }
            item {
                Text(
                    "The patched PRoot path is enabled only when the source-built ARM32 executable and loader pass verification. If Android rejects execution, diagnostics report the actual failure and Termux remains available as a fallback.",
                    color = AppTheme.colors.textSecondary
                )
                OutlinedButton(onClick = viewModel::clearOutput, modifier = Modifier.fillMaxWidth()) { Text("CLEAR OUTPUT") }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDiagnostics = !showDiagnostics; viewModel.diagnostics() }, modifier = Modifier.weight(1f)) {
                        Text(if (showDiagnostics) "HIDE DIAGNOSTICS" else "DIAGNOSTICS")
                    }
                    OutlinedButton(onClick = { copyAfterRefresh = true; viewModel.diagnostics() }, modifier = Modifier.weight(1f)) {
                        Text("COPY DIAGNOSTICS")
                    }
                }
            }
            if (!message.isNullOrBlank()) item { Text(message!!, color = AppTheme.colors.textSecondary) }
            if (showDiagnostics && diagnostics.isNotBlank()) {
                item {
                    Text("On-device diagnostics", fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
                    Text(diagnostics, color = AppTheme.colors.textSecondary)
                }
            }
            item { Text(output.ifBlank { "No command output yet." }, color = AppTheme.colors.textPrimary) }
        }
    }
}
