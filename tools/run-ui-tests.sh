#!/usr/bin/env bash
set -euo pipefail
# The IDE and test Robot must share an isolated X11 display, including a window manager.
exec env -u WAYLAND_DISPLAY GDK_BACKEND=x11 XDG_SESSION_TYPE=x11 \
  xvfb-run -a -s '-screen 0 2560x2048x24' tools/ui-session.sh "$@"
