#!/data/data/com.termux/files/usr/bin/bash
# Send SIGINT to OpenManus/OpenSwarm processes only; never kill unrelated Termux jobs.
set -Eeuo pipefail
count=0
for proc in /proc/[0-9]*; do
  pid="${proc##*/}"
  [ "$pid" != "$$" ] || continue
  [ -r "$proc/cmdline" ] || continue
  cmd="$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null || true)"
  case "$cmd" in
    *openmanus*|*OpenManus*|*openswarm*|*OpenSwarm*)
      if kill -0 "$pid" 2>/dev/null; then
        if kill -INT "$pid" 2>/dev/null; then
          printf 'Sent SIGINT to PID %s: %s\n' "$pid" "${cmd:0:180}"
          count=$((count + 1))
        fi
      fi
      ;;
  esac
done
if [ "$count" -eq 0 ]; then
  echo "No OpenManus/OpenSwarm process was found. No other process was signalled."
else
  echo "SIGINT sent to $count agent process(es). Check their logs for graceful shutdown."
fi
