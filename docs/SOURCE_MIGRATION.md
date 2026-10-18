# Source migration plan

## Source of truth
The existing Android source is in `saedhawwa23-rgb/Usman-and-UMAR-automation-app-work-space-`, branch `Chatgpt`.

## Inventory verified
- 471 tracked file entries were found in the source branch tree.
- The source README documents Android min SDK 28, target SDK 34, cloud AI, app-private Alpine ARMv7/PRoot terminal, GitHub discovery, and optional Termux compatibility.
- Source migration must preserve the app's existing code, tests, Gradle wrapper, license notices, workflows, and project documentation.

## Transfer status
The new repository is initialized. A direct `git clone` from the current execution environment failed because DNS/network access to `github.com` is unavailable. Therefore, the full source has **not** been copied yet and no build/test result is claimed for this repository.

## Required next operation
Transfer the complete source branch into this repository (preferably via a repository import or an uploaded source ZIP), then verify file counts and Git history/content. Work on `feature/source-migration`; do not merge into `main` without explicit approval.

## Validation gates after transfer
1. Confirm Gradle wrapper and Android project files are present.
2. Review `applicationId`, min/target SDK, and ARM32 native libraries.
3. Run unit tests and Android debug build in a network-enabled CI environment.
4. Review workflow results and inspect APK ABI contents before claiming device compatibility.
