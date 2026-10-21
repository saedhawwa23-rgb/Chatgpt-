#!/bin/sh
# Run inside the app-owned Alpine/PRoot workspace. Logs remain exportable via /workspace.
set -u
ROOT="/workspace/agents"
LOG="/workspace/agent-install-results.txt"
mkdir -p "$ROOT" /workspace
exec >"$LOG" 2>&1
status=0

echo "[agents] Alpine ARM32 setup started: $(date -u)"
echo "[agents] Installing free repository tools…"
apk add --no-cache git python3 py3-pip py3-virtualenv nodejs npm curl bash build-base linux-headers libffi-dev openssl-dev || {
  echo "[agents] ERROR: Alpine tool installation failed."
  status=1
}

clone_repo() {
  url="$1"
  dir="$2"
  if [ -d "$dir/.git" ]; then
    echo "[agents] Existing checkout preserved: $dir"
  else
    git clone --depth 1 "$url" "$dir" || return 1
  fi
  git -C "$dir" rev-parse HEAD
}

install_project() {
  label="$1"
  url="$2"
  dir="$3"
  echo ""
  echo "===== $label ====="
  clone_repo "$url" "$dir" || {
    echo "[agents] ERROR: clone failed for $label."
    return 1
  }
  python3 -m venv "$dir/.venv" || {
    echo "[agents] ERROR: virtual environment failed for $label."
    return 1
  }
  if [ -f "$dir/requirements.txt" ]; then
    "$dir/.venv/bin/python" -m pip install -r "$dir/requirements.txt"
  elif [ -f "$dir/pyproject.toml" ]; then
    "$dir/.venv/bin/python" -m pip install -e "$dir"
  else
    echo "[agents] No requirements.txt or pyproject.toml; source cloned only."
    return 0
  fi
}

install_project "OpenManus" "https://github.com/FoundationAgents/OpenManus.git" "$ROOT/OpenManus" || status=1
install_project "OpenSwarm (openswarm-os)" "https://github.com/openswarm-os/openswarm.git" "$ROOT/OpenSwarm" || status=1

echo ""
echo "===== Summary ====="
echo "OpenManus checkout: $([ -d "$ROOT/OpenManus/.git" ] && echo present || echo missing)"
echo "OpenSwarm checkout: $([ -d "$ROOT/OpenSwarm/.git" ] && echo present || echo missing)"
echo "Workspace: $ROOT"
echo "Log: $LOG"
if [ "$status" -eq 0 ]; then
  echo "Dependency installation commands completed. Configure a model separately if agent inference requires one."
else
  echo "One or more dependency steps failed. Review this log; ARM32 may lack compatible wheels."
fi
exit "$status"
