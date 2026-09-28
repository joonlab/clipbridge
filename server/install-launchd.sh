#!/bin/bash
# install-launchd.sh — clipd 를 로그인할 때 자동으로 뜨는 launchd 서비스로 등록한다.
#
#   server/install-launchd.sh            등록(이미 있으면 다시 읽는다)
#   server/install-launchd.sh uninstall  해제
#
# 먼저 할 것: ~/.config/clipbridge/clipbridge.env 채우기 · swiftc -O -o server/clipwatch server/clipwatch.swift
set -euo pipefail
HERE="$(cd "$(dirname "$(readlink -f "$0" 2>/dev/null || echo "$0")")" && pwd)"
LABEL=local.clipbridge.clipd
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
STATE="${CLIPBRIDGE_STATE_DIR:-$HOME/.local/state/clipbridge}"
PY="$(command -v python3)"

if [ "${1-}" = "uninstall" ]; then
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
  [ -f "$PLIST" ] && mv "$PLIST" "$PLIST.disabled" && echo "해제: $PLIST → .disabled"
  exit 0
fi

mkdir -p "$STATE" "$(dirname "$PLIST")"
cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>$PY</string><string>$HERE/clipd.py</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$STATE/clipd.out.log</string>
  <key>StandardErrorPath</key><string>$STATE/clipd.err.log</string>
</dict>
</plist>
PL
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "등록: $PLIST"
echo "상태: launchctl print gui/$(id -u)/$LABEL | head · 로그: $STATE/clipd.log"
