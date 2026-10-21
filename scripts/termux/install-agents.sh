#!/data/data/com.termux/files/usr/bin/bash
# Best-effort, auditable install of open-source agent repositories in Termux.
# ARM32 devices may not have wheels for every optional ML dependency.
set -Eeuo pipefail
ROOT="$HOME/workspace/agents"
LOG="$HOME/workspace/agent-install-results.txt"
mkdir -p "$ROOT" "$(dirname "$LOG")"
exec > >(tee "$LOG") 2>&1
trap 'rc=$?; printf "\n[agents] command failed at line %s (exit %s)\n" "$LINENO" "$rc" | tee -a "$LOG" >&2; exit "$rc"' ERR

command -v git >/dev/null || { echo "[agents] Run setup.sh first: Git is missing."; exit 2; }
command -v python >/dev/null || { echo "[agents] Run setup.sh first: Python is missing."; exit 2; }
python --version
git --version

clone_repo() {
  local url="$1" dir="$2"
  if [ -d "$dir/.git" ]; then
    echo "[agents] Existing checkout: $dir (preserving local edits)"
  else
    git clone --depth 1 "$url" "$dir"
  fi
  git -C "$dir" rev-parse HEAD
}

install_python_project() {
  local dir="$1" label="$2"
  echo ""
  echo "===== $label ====="
  clone_repo "$3" "$dir"
  python -m venv "$dir/.venv" || {
    echo "[agents] Could not create venv for $label on this device."
    return 1
  }
  # shellcheck disable=SC1091
  source "$dir/.venv/bin/activate"
  python -m pip install --upgrade pip || echo "[agents] pip upgrade failed; continuing with current pip."
  if [ -f "$dir/requirements.txt" ]; then
    python -m pip install -r "$dir/requirements.txt"
  elif [ -f "$dir/pyproject.toml" ]; then
    python -m pip install -e "$dir"
  else
    echo "[agents] No requirements.txt or pyproject.toml found; source was cloned only."
    deactivate
    return 0
  fi
  deactivate
}

status=0
install_python_project "$ROOT/OpenManus" "OpenManus" "https://github.com/FoundationAgents/OpenManus.git" || status=1
install_python_project "$ROOT/OpenSwarm" "OpenSwarm (openswarm-os)" "https://github.com/openswarm-os/openswarm.git" || status=1

{
  echo ""
  echo "===== Summary ====="
  echo "OpenManus: $([ -d "$ROOT/OpenManus/.git" ] && echo cloned || echo missing)"
  echo "OpenSwarm: $([ -d "$ROOT/OpenSwarm/.git" ] && echo cloned || echo missing)"
  echo "Agent workspace: $ROOT"
  echo "Install log: $LOG"
  if [ "$status" -eq 0 ]; then
    echo "Dependency install commands completed. This does not mean an LLM provider/model is configured."
  else
    echo "One or more dependency installs failed. Review this log; ARM32 may lack compatible wheels."
  fi
} | tee -a "$LOG"

exit "$status"
