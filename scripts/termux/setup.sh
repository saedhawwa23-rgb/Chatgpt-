#!/data/data/com.termux/files/usr/bin/bash
# USMAN AND UMAR — zero-cost Termux development environment
# Run from Termux: bash setup.sh
set -Eeuo pipefail
trap 'rc=$?; printf "\n[setup] failed at line %s (exit %s)\n" "$LINENO" "$rc" >&2; exit "$rc"' ERR

if ! command -v pkg >/dev/null 2>&1; then
  echo "[setup] This script must run inside the official Termux environment." >&2
  exit 2
fi

echo "[setup] Refreshing Termux package indexes…"
pkg update -y

echo "[setup] Installing free/open-source developer tools…"
pkg install -y \
  bash coreutils curl wget git openssh \
  python nodejs clang make cmake pkg-config \
  tar unzip zip proot findutils grep sed \
  procps termux-tools

mkdir -p "$HOME/.cache/usman-and-umar" "$HOME/workspace"
python --version
node --version
npm --version
git --version

cat <<'NOTICE'
[setup] Environment ready.
[setup] Workspace: ~/workspace
[setup] No AI provider, paid API key, or payment card is required for these local tools.
[setup] To allow the Android app to invoke Termux commands, review Termux's
        allow-external-apps setting yourself and grant Android's RUN_COMMAND
        permission only to apps you trust. This script does not silently weaken
        that security setting.
NOTICE
