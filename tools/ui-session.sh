#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${KGBOARD_UI_WM:-}" ]]; then
  task_window_manager="$KGBOARD_UI_WM"
elif command -v openbox >/dev/null; then
  task_window_manager=openbox
elif command -v twm >/dev/null; then
  task_window_manager=twm
else
  echo 'UI tests require openbox or twm on the test machine.' >&2
  exit 2
fi
mkdir -p build/reports/kgboard-ui
if [[ "${task_window_manager##*/}" == twm ]]; then
  "$task_window_manager" -f tools/ui-twm.conf > build/reports/kgboard-ui/window-manager.log 2>&1 &
else
  "$task_window_manager" --sm-disable > build/reports/kgboard-ui/window-manager.log 2>&1 &
fi
task_window_manager_pid=$!
trap 'kill "$task_window_manager_pid" 2>/dev/null || true' EXIT
./gradlew "$@"
