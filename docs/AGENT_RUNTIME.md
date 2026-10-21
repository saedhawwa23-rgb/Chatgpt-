# Termux agent setup and ARM32 constraints

The Termux screen exposes explicit, user-triggered tasks for:

- `scripts/termux/setup.sh`: installs Python, Node.js, Git, curl, compiler/build utilities, and basic Linux tools using Termux's free package repositories.
- `scripts/termux/install-agents.sh`: clones [FoundationAgents/OpenManus](https://github.com/FoundationAgents/OpenManus) and [openswarm-os/openswarm](https://github.com/openswarm-os/openswarm), attempts each project's Python dependency installation in a separate virtual environment, and records the exact checkout SHAs and output.
- `app/src/main/assets/install-agents-alpine.sh`: the integrated-terminal button runs the equivalent installation inside the app-owned Alpine/PRoot environment, storing repositories under the shared `/workspace/agents` directory and logs at `/workspace/agent-install-results.txt`.
- `scripts/termux/stop-agents.sh`: sends SIGINT only to processes whose command lines identify OpenManus/OpenSwarm. It does not signal unrelated Termux processes.

The installer is best-effort. The Samsung Galaxy M10 target is 32-bit ARM; upstream ML libraries and optional dependencies may not publish compatible ARM32 wheels. An installation log with an error is a real failure, not evidence that the agent is ready. Neither repository is bundled into the APK and neither starts automatically at install time.

Agent task stdout/stderr is written to `agent-results/<task>-<execution-id>.txt` through `StorageWorkspaceProvider`. If Google Drive is selected as the workspace, the write goes through Android's authorized document provider; if the provider rejects the write, the task result includes an export-failure notice instead of claiming it was saved to Drive.

## PRoot source

The CI build checks out [oonid/pr](https://github.com/oonid/pr) at source revision `1b2ea7966b5105e7d58051a394c25da97bf85a7d`, initializes its Samba/talloc submodule, and runs its own `scripts/build.sh --arch=arm` with Android NDK r27c / API 28. The build must produce 32-bit ARM `proot` and `loader` binaries. The resulting files are packaged as `lib/armeabi-v7a/libproot.so` and `lib/armeabi-v7a/libproot-loader.so` and verified in the APK. CI does not substitute the upstream repository's ARM64 prebuilt binaries for these outputs.
