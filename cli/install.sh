#!/bin/bash
# install.sh — cli/ 의 명령을 ~/.local/bin 에 심링크로 건다. (다시 돌려도 안전하다)
#
#   cli/install.sh             ~/.local/bin 에
#   cli/install.sh <디렉터리>   다른 곳에
set -euo pipefail
CB_DIR="$(cd "$(dirname "$(readlink -f "$0" 2>/dev/null || echo "$0")")" && pwd)"
DEST="${1:-$HOME/.local/bin}"
mkdir -p "$DEST"
for f in clip2phone file2phone link2phone phone-notify phone-sms clipbridge-deploy \
         phone phone-cam screen2 phone-home; do
  chmod +x "$CB_DIR/$f"
  ln -sfn "$CB_DIR/$f" "$DEST/$f"
  echo "  $DEST/$f → cli/$f"
done
case ":$PATH:" in *":$DEST:"*) ;; *) echo "⚠️ $DEST 가 PATH 에 없다 — 셸 설정에 추가할 것";; esac
[ -f "${CLIPBRIDGE_CONFIG:-$HOME/.config/clipbridge/clipbridge.env}" ] \
  || echo "⚠️ 설정 파일이 없다 — mkdir -p ~/.config/clipbridge && cp config.example.env ~/.config/clipbridge/clipbridge.env"
