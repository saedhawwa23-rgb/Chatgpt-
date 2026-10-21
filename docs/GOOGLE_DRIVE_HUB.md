# Google Drive Hub

USMAN AND UMAR uses Android's Storage Access Framework (SAF) for user-authorized file access. In **Settings → Google Drive Hub**, choose **Connect Drive / Choose Workspace**, then select Google Drive in the Android folder picker and authorize the folder. Android's Google Drive provider must be available on the device and the user must be signed in; Android does not permit silent account access.

The selected tree URI is persisted by `StorageWorkspaceProvider` and can be used by supported workspace file operations. Settings also exposes a direct **Copy workspace output to Drive / folder** control and a zero-cost **URL download** control. The downloader streams public HTTP(S) files (up to a 250 MB safety limit) directly to the selected document provider when Drive is selected, or to the app workspace otherwise. The Termux bridge writes OpenManus/OpenSwarm task logs through the same provider when a folder is selected.

Local file generation, public GitHub cloning, URL opening, and Android share intents must not require an AI provider or paid API. Google Drive access through SAF does not require a separate paid API key.

## Boundaries

- A user action is required for Drive authorization; the app cannot silently access a Drive account on first install.
- SAF supports provider-backed files and folders but does not make every external process (including Termux or arbitrary build tools) transparently write to Drive. Outputs produced outside the app workspace must be copied/exported through the app's authorized document provider path.
- Never store OAuth client secrets, GitHub tokens, or AI API keys in source code or the APK.
- Do not report Drive upload success unless the document provider confirms the write completed.
