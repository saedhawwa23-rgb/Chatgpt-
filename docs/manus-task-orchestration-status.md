# Manus-style task orchestration implementation status

This feature branch adds a first executable orchestration core and a Task Manager workspace screen with two real, deterministic Android actions.

## Implemented in this slice
- Shared capability model with IDs, descriptions, required input keys, enable/disable state, sensitive-operation approval gate, and typed result.
- Explicit task lifecycle: pending, planning, ready, running, verifying, waiting for user, paused, completed, failed, cancelled.
- Priority ordering, structured plan validation, action-by-action execution, event timestamps, result/exit-code/artifact fields, and fail-closed completion.
- Real tool-only Android actions: open validated HTTP(S) URL and invoke the Android text-sharing chooser.
- Task event/result history is retained in current screen process memory; it is not yet persisted to Room.
- Unit tests cover successful result verification, failure handling, and denied approval.

## Not yet implemented / explicitly not claimed
- AI app routing and automatic result capture. Consumer AI apps are isolated; only supported handoff/manual return can be relied on.
- Persistent Room task/workflow schema and interruption recovery across process death.
- Full terminal-backed sandbox integration from the orchestrator, command approval, resource/time limits, and isolation audit.
- Google Drive OAuth/API operations. No configured OAuth client is assumed; Drive is not presented as connected.
- Versioned external plugin package installation, resumable uploads, and remote worker execution.
- Device verification on Samsung Galaxy M10 / Android 9.

## Safety behavior
- Tool-only mode is the only executable mode in this initial UI. Automatic/manual modes fail with a clear message rather than pretending an AI provider was invoked.
- URL launching only accepts valid HTTP(S) URLs.
- Share success means Android's chooser opened; it does not mean a recipient received the content.
- A task only reaches COMPLETED if each planned handler returned a successful ToolResult and verification matched the plan.
- Sensitive capabilities require the injected approval gate to approve; the initial screen's gate denies by default.

## Build and review
Run ./gradlew :app:testDebugUnitTest assembleDebug in the configured Android/JDK environment. Keep this feature unmerged until CI and device acceptance tests pass.
