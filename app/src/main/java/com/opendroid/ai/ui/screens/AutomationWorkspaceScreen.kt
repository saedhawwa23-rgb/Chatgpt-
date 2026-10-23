package com.opendroid.ai.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opendroid.ai.core.orchestration.ApprovalGate
import com.opendroid.ai.core.orchestration.Capability
import com.opendroid.ai.core.orchestration.PlannedAction
import com.opendroid.ai.core.orchestration.RoutingMode
import com.opendroid.ai.core.orchestration.TaskOrchestrator
import com.opendroid.ai.core.orchestration.TaskPlanner
import com.opendroid.ai.core.orchestration.TaskState
import com.opendroid.ai.core.orchestration.ToolResult
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationWorkspaceScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var goal by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(RoutingMode.TOOL_ONLY) }
    var modeExpanded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready · native tools require no AI API key") }

    val orchestrator = remember(context) {
        TaskOrchestrator(
            planner = TaskPlanner { taskGoal, _, routingMode, _ ->
                if (routingMode != RoutingMode.TOOL_ONLY) {
                    throw IllegalStateException("AI/manual routing is not connected yet. Choose Tool only; no AI result will be fabricated.")
                }
                val lower = taskGoal.lowercase()
                when {
                    lower.contains("open url") || lower.contains("open website") || lower.startsWith("https://") || lower.startsWith("http://") -> {
                        val url = Regex("https?://[^\\s]+").find(taskGoal)?.value
                            ?: throw IllegalArgumentException("Include a complete https:// or http:// URL.")
                        listOf(PlannedAction("phone.open_url", mapOf("url" to url), description = "Open URL in Android"))
                    }
                    lower.startsWith("share:") -> listOf(PlannedAction("phone.share_text", mapOf("text" to taskGoal.substringAfter(":").trim()), description = "Share text using Android chooser"))
                    else -> throw IllegalArgumentException("Available tool-only tasks: 'open URL https://…' or 'share: your text'.")
                }
            },
            approvalGate = ApprovalGate { _, _, _ -> false }
        ).apply {
            register(Capability("phone.open_url", "Open an HTTP(S) URL with Android", setOf("url"), handler = { input ->
                val uri = Uri.parse(input.getValue("url"))
                require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "Only valid HTTP(S) URLs are allowed." }
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolResult(true, "Android accepted URL launch", uri.toString())
            }))
            register(Capability("phone.share_text", "Share text through Android chooser", setOf("text"), handler = { input ->
                val text = input.getValue("text")
                require(text.isNotBlank()) { "Share text cannot be blank." }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(send, "Share from USMAN AND UMAR").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolResult(true, "Android share chooser opened; delivery to a recipient is not assumed.")
            }))
        }
    }
    val tasks by orchestrator.tasks.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("TASK MANAGER · AUTOMATION WORKSPACE", style = MaterialTheme.typography.titleLarge)
        Text("Free-first execution. Tool-only actions run locally. AI planning, app handoffs, sandbox commands, and Google Drive are not shown as connected until integrated and verified.")
        OutlinedTextField(value = goal, onValueChange = { goal = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Goal") }, placeholder = { Text("Open URL https://example.com  or  share: Hello") })
        OutlinedTextField(value = instructions, onValueChange = { instructions = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Optional execution instructions") })
        ExposedDropdownMenuBox(expanded = modeExpanded, onExpandedChange = { modeExpanded = it }) {
            OutlinedTextField(
                value = mode.name.replace('_', ' '), onValueChange = {}, readOnly = true,
                label = { Text("Execution mode") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                RoutingMode.values().forEach { option ->
                    DropdownMenuItem(text = { Text(option.name.replace('_', ' ')) }, onClick = {
                        mode = option
                        modeExpanded = false
                    })
                }
            }
        }
        Button(onClick = {
            scope.launch {
                runCatching {
                    val task = orchestrator.enqueue(goal, instructions, mode)
                    status = "Queued task " + task.id.take(8)
                    val result = orchestrator.run(task.id)
                    status = result.state.name + ": " + (result.error ?: result.results.lastOrNull()?.summary ?: "No result")
                }.onFailure { status = "FAILED: " + (it.message ?: it.javaClass.simpleName) }
            }
        }, enabled = goal.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("QUEUE AND EXECUTE")
        }
        Text(status)
        Text("TASK HISTORY (${tasks.size})", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks, key = { it.id }) { task ->
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(task.goal, style = MaterialTheme.typography.titleSmall)
                        Text("State: ${task.state} · Priority ${task.priority}")
                        task.events.takeLast(3).forEach { Text("${it.state}: ${it.message}", style = MaterialTheme.typography.bodySmall) }
                        task.results.forEach { result ->
                            Text((if (result.success) "SUCCESS" else "FAILED") + " · " + result.summary)
                            if (result.output.isNotBlank()) Text(result.output, style = MaterialTheme.typography.bodySmall)
                            result.exitCode?.let { Text("Exit code: $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (task.state == TaskState.FAILED || task.state == TaskState.PAUSED) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { scope.launch { runCatching { orchestrator.retry(task.id) }.onFailure { status = it.message.orEmpty() } } }) { Text("Retry") }
                            }
                        }
                    }
                }
            }
        }
    }
}
