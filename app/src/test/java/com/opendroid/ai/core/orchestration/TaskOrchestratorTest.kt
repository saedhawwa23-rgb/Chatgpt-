package com.opendroid.ai.core.orchestration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskOrchestratorTest {
    @Test fun successfulToolResultIsRequiredForCompletion() = runBlocking {
        val orchestrator = TaskOrchestrator(
            planner = TaskPlanner { _, _, _, _ -> listOf(PlannedAction("test.echo", mapOf("text" to "hello"), description = "echo text")) },
            approvalGate = ApprovalGate { _, _, _ -> true }
        )
        orchestrator.register(Capability("test.echo", "Returns supplied text", setOf("text"), handler = {
            ToolResult(true, "echoed", it.getValue("text"), exitCode = 0)
        }))
        val task = orchestrator.enqueue("Echo hello")
        val result = orchestrator.run(task.id)
        assertEquals(TaskState.COMPLETED, result.state)
        assertEquals("hello", result.results.single().output)
    }

    @Test fun failedToolCannotCompleteTask() = runBlocking {
        val orchestrator = TaskOrchestrator(
            planner = TaskPlanner { _, _, _, _ -> listOf(PlannedAction("test.fail", emptyMap(), description = "failing action")) },
            approvalGate = ApprovalGate { _, _, _ -> true }
        )
        orchestrator.register(Capability("test.fail", "Fails deliberately", handler = {
            ToolResult(false, "exit 2", exitCode = 2)
        }))
        val task = orchestrator.enqueue("Run failing action")
        val result = orchestrator.run(task.id)
        assertEquals(TaskState.FAILED, result.state)
        assertFalse(result.results.single().success)
    }

    @Test fun deniedSensitiveApprovalPreventsHandlerExecution() = runBlocking {
        var called = false
        val orchestrator = TaskOrchestrator(
            planner = TaskPlanner { _, _, _, _ -> listOf(PlannedAction("test.sensitive", emptyMap(), description = "sensitive operation")) },
            approvalGate = ApprovalGate { _, _, _ -> false }
        )
        orchestrator.register(Capability("test.sensitive", "Sensitive", sensitive = true, handler = {
            called = true
            ToolResult(true, "should not run")
        }))
        val task = orchestrator.enqueue("Sensitive task")
        val result = orchestrator.run(task.id)
        assertEquals(TaskState.FAILED, result.state)
        assertFalse(called)
        assertTrue(result.error.orEmpty().contains("approval was denied"))
    }
}
