# clipbridge-env.sh — cli/ 의 모든 명령이 source 하는 공통 설정.
#
# 값은 ~/.config/clipbridge/clipbridge.env (또는 $CLIPBRIDGE_CONFIG) 에서 읽는다.
# 이미 환경변수로 넘어온 값이 있으면 그쪽이 이긴다.

CLIPBRIDGE_CONFIG="${CLIPBRIDGE_CONFIG:-$HOME/.config/clipbridge/clipbridge.env}"
if [ -f "$CLIPBRIDGE_CONFIG" ]; then
  # 이미 설정된 환경변수를 덮지 않도록, 비어 있는 것만 채운다
  while IFS='=' read -r _k _v; do
    case "$_k" in ''|\#*) continue ;; esac
    _k="${_k#export }"; _k="${_k//[[:space:]]/}"
    [ -n "${!_k-}" ] && continue
    _v="${_v%\"}"; _v="${_v#\"}"; _v="${_v%\'}"; _v="${_v#\'}"
    _v="${_v//\$HOME/$HOME}"
    export "$_k=$_v"
  done < "$CLIPBRIDGE_CONFIG"
  unset _k _v
fi

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
export PATH="/opt/homebrew/bin:/usr/local/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

MAC="${CLIPBRIDGE_MAC_HOST-}"
PHONE="${CLIPBRIDGE_PHONE_HOST-}"
TOKEN="${CLIPBRIDGE_TOKEN-}"

# 설정이 빠졌을 때 조용히 엉뚱한 곳으로 보내지 않게, 쓰기 전에 확인한다.
cb_need() {
  local miss=()
  for v in "$@"; do
    case "$v" in
      mac)   [ -n "$MAC" ]   || miss+=(CLIPBRIDGE_MAC_HOST) ;;
      phone) [ -n "$PHONE" ] || miss+=(CLIPBRIDGE_PHONE_HOST) ;;
      token) [ -n "$TOKEN" ] || miss+=(CLIPBRIDGE_TOKEN) ;;
    esac
  done
  if [ ${#miss[@]} -gt 0 ]; then
    printf '설정이 비어 있다: %s\n  → %s 를 채울 것 (config.example.env 참고)\n' \
      "${miss[*]}" "$CLIPBRIDGE_CONFIG" >&2
    exit 1
  fi
}
