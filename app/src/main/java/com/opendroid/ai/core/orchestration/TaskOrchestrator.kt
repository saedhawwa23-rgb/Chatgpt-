package com.opendroid.ai.core.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * A capability shared by the task runner and future Tools/Macros adapters.
 * Handlers must validate inputs themselves; the registry validates required keys
 * and gates sensitive capabilities before invoking a handler.
 */
data class Capability(
    val id: String,
    val description: String,
    val requiredInputs: Set<String> = emptySet(),
    val sensitive: Boolean = false,
    val enabled: Boolean = true,
    val handler: suspend (Map<String, String>) -> ToolResult
)

data class ToolResult(
    val success: Boolean,
    val summary: String,
    val output: String = "",
    val exitCode: Int? = null,
    val artifactUris: List<String> = emptyList()
)

enum class TaskState {
    PENDING, PLANNING, READY, RUNNING, VERIFYING, WAITING_FOR_USER,
    PAUSED, COMPLETED, FAILED, CANCELLED
}

enum class RoutingMode { AUTOMATIC, MANUAL, TOOL_ONLY }

data class PlannedAction(
    val capabilityId: String,
    val input: Map<String, String>,
    val requiresApproval: Boolean = false,
    val description: String
)

data class TaskEvent(
    val atMillis: Long = System.currentTimeMillis(),
    val state: TaskState,
    val message: String,
    val actionIndex: Int? = null
)

data class AutomationTask(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val instructions: String = "",
    val routingMode: RoutingMode = RoutingMode.TOOL_ONLY,
    val priority: Int = 3,
    val state: TaskState = TaskState.PENDING,
    val plan: List<PlannedAction> = emptyList(),
    val currentActionIndex: Int = -1,
    val results: List<ToolResult> = emptyList(),
    val events: List<TaskEvent> = emptyList(),
    val error: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

fun interface TaskPlanner {
    suspend fun plan(goal: String, instructions: String, mode: RoutingMode, capabilities: List<Capability>): List<PlannedAction>
}

fun interface ApprovalGate {
    suspend fun approve(task: AutomationTask, action: PlannedAction, capability: Capability): Boolean
}

/**
 * Execution coordinator. It deliberately does not treat an LLM's prose as proof
 * of completion: every action must return a successful ToolResult. A task with
 * no executable actions fails closed. Persistence can be attached through the
 * event callback without coupling this core to Room or UI.
 */
class TaskOrchestrator(
    private val planner: TaskPlanner,
    private val approvalGate: ApprovalGate,
    private val onTaskChanged: suspend (AutomationTask) -> Unit = {}
) {
    private val mutex = Mutex()
    private val registry = linkedMapOf<String, Capability>()
    private val _tasks = MutableStateFlow<List<AutomationTask>>(emptyList())
    val tasks: StateFlow<List<AutomationTask>> = _tasks.asStateFlow()

    fun register(capability: Capability) {
        require(capability.id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{1,95}"))) { "Invalid capability id" }
        require(capability.description.isNotBlank()) { "Capability description is required" }
        registry[capability.id] = capability
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val existing = requireNotNull(registry[id]) { "Unknown capability: $id" }
        registry[id] = existing.copy(enabled = enabled)
    }

    fun capabilities(): List<Capability> = registry.values.toList()

    suspend fun enqueue(goal: String, instructions: String = "", mode: RoutingMode = RoutingMode.TOOL_ONLY, priority: Int = 3): AutomationTask {
        require(goal.isNotBlank()) { "Task goal cannot be blank" }
        require(priority in 1..5) { "Priority must be between 1 and 5" }
        val task = AutomationTask(
            goal = goal.trim(),
            instructions = instructions.trim(),
            routingMode = mode,
            priority = priority
        ).withEvent(TaskState.PENDING, "Task queued")
        update(task)
        return task
    }

    suspend fun run(taskId: String): AutomationTask = mutex.withLock {
        var task = find(taskId)
        if (task.state in setOf(TaskState.COMPLETED, TaskState.CANCELLED)) return@withLock task
        task = task.transition(TaskState.PLANNING, "Creating an executable plan")
        update(task)
        try {
            val available = registry.values.filter { it.enabled }
            val plan = planner.plan(task.goal, task.instructions, task.routingMode, available)
            if (plan.isEmpty()) {
                return@withLock fail(task, "Planner returned no executable actions; no completion was assumed.")
            }
            plan.forEach { action ->
                val cap = registry[action.capabilityId]
                    ?: return@withLock fail(task, "Plan references an unknown capability: ${action.capabilityId}")
                if (!cap.enabled) return@withLock fail(task, "Capability is disabled: ${cap.id}")
                val missing = cap.requiredInputs - action.input.keys
                if (missing.isNotEmpty()) return@withLock fail(task, "Missing required inputs for ${cap.id}: ${missing.sorted().joinToString()}")
            }
            task = task.copy(plan = plan, currentActionIndex = -1, error = null).transition(TaskState.READY, "Plan validated: ${plan.size} action(s)")
            update(task)

            for ((index, action) in plan.withIndex()) {
                task = find(taskId)
                if (task.state == TaskState.CANCELLED || task.state == TaskState.PAUSED) return@withLock task
                val capability = registry.getValue(action.capabilityId)
                if (capability.sensitive || action.requiresApproval) {
                    task = task.copy(currentActionIndex = index).transition(TaskState.WAITING_FOR_USER, "Approval required for ${action.description}", index)
                    update(task)
                    if (!approvalGate.approve(task, action, capability)) {
                        return@withLock fail(task, "User approval was denied for ${action.description}")
                    }
                }
                task = task.copy(currentActionIndex = index).transition(TaskState.RUNNING, "Running ${action.description}", index)
                update(task)
                val result = capability.handler(action.input)
                task = task.copy(results = task.results + result, currentActionIndex = index)
                update(task)
                if (!result.success) {
                    return@withLock fail(task, "Action ${index + 1} (${capability.id}) failed: ${result.summary}")
                }
            }
            task = task.transition(TaskState.VERIFYING, "Verifying action outcomes")
            update(task)
            if (task.results.size != plan.size || task.results.any { !it.success }) {
                return@withLock fail(task, "Verification failed: not every planned action has a successful result.")
            }
            task.transition(TaskState.COMPLETED, "Verified: all ${plan.size} action(s) returned success").also { update(it); it }
        } catch (cancelled: CancellationException) {
            val current = find(taskId).transition(TaskState.CANCELLED, "Execution cancelled")
            update(current)
            throw cancelled
        } catch (error: Exception) {
            fail(find(taskId), error.message ?: error.javaClass.simpleName)
        }
    }

    suspend fun pause(taskId: String): AutomationTask = updateAndReturn(taskId, TaskState.PAUSED, "Paused by user")
    suspend fun cancel(taskId: String): AutomationTask = updateAndReturn(taskId, TaskState.CANCELLED, "Cancelled by user")

    suspend fun resume(taskId: String): AutomationTask {
        val task = find(taskId)
        require(task.state == TaskState.PAUSED) { "Only paused tasks can resume" }
        val resumed = task.transition(TaskState.PENDING, "Resume requested; safe rerun will re-plan the task")
        update(resumed)
        return resumed
    }

    suspend fun retry(taskId: String): AutomationTask {
        val task = find(taskId)
        require(task.state == TaskState.FAILED || task.state == TaskState.PAUSED) { "Only failed or paused tasks can retry" }
        val reset = task.copy(plan = emptyList(), currentActionIndex = -1, results = emptyList(), error = null)
            .transition(TaskState.PENDING, "Retry queued; previous results retained in event history")
        update(reset)
        return reset
    }

    private suspend fun updateAndReturn(id: String, state: TaskState, message: String): AutomationTask {
        val task = find(id).transition(state, message)
        update(task)
        return task
    }

    private fun find(id: String): AutomationTask = _tasks.value.firstOrNull { it.id == id }
        ?: error("Task not found: $id")

    private suspend fun fail(task: AutomationTask, message: String): AutomationTask {
        val failed = task.copy(error = message).transition(TaskState.FAILED, message)
        update(failed)
        return failed
    }

    private suspend fun update(task: AutomationTask) {
        val normalized = task.copy(updatedAtMillis = System.currentTimeMillis())
        _tasks.value = if (_tasks.value.any { it.id == task.id }) {
            _tasks.value.map { if (it.id == task.id) normalized else it }
        } else {
            (_tasks.value + normalized).sortedWith(compareByDescending<AutomationTask> { it.priority }.thenBy { it.createdAtMillis })
        }
        onTaskChanged(normalized)
    }

    private fun AutomationTask.transition(next: TaskState, message: String, actionIndex: Int? = null): AutomationTask =
        copy(state = next, events = events + TaskEvent(state = next, message = message, actionIndex = actionIndex))

    private fun AutomationTask.withEvent(state: TaskState, message: String): AutomationTask =
        copy(state = state, events = events + TaskEvent(state = state, message = message))
}
