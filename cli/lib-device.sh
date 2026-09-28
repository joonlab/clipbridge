# lib-device.sh — 폴드8 무선 adb 연결·기기 선택 공통 로직.
# phone · phone-cam 이 함께 쓴다. 고칠 일이 생기면 여기만 고친다.
#
# ⚠️ macOS BSD grep 은 \s 를 모른다 — "\sdevice$" 는 항상 실패한다(조용한 오작동).
# ⚠️ set -e 아래에서 grep 미매치는 할당 실패로 잡혀 스크립트가 조용히 죽는다 → || true

has_device() { adb devices | awk '$2=="device"{f=1} END{exit !f}'; }

# 무선 디버깅(TLS)은 재부팅해도 페어링이 유지되지만 포트가 매번 바뀐다 → mDNS 로 찾는다.
# LTE만으로 핫스팟을 쓸 때는 무선 디버깅이 IP를 못 잡으므로 레거시 tcpip 5555 를 열어둔다.
ensure_tcpip() {
  local dev="$1" ip
  ip=$(adb -s "$dev" shell "ip -4 addr show wlan0 2>/dev/null" 2>/dev/null \
        | grep -oE 'inet [0-9.]+' | awk '{print $2}' | head -1 || true)
  [ -z "$ip" ] && return 0
  if ! nc -z -w 2 "$ip" 5555 2>/dev/null; then
    echo "  tcpip 5555 를 열어둡니다 (핫스팟 대비)" >&2
    adb -s "$dev" tcpip 5555 >/dev/null 2>&1
    sleep 2
  fi
}

auto_connect() {
  # 죽은 항목부터 치운다 — offline 을 붙들고 있으면 새 연결을 방해한다
  adb devices | awk '$2=="offline"{print $1}' | while read -r d; do adb disconnect "$d" >/dev/null 2>&1; done
  has_device && return 0

  local cands=() addr gw ts
  # 1) mDNS — 폰이 Wi-Fi 클라이언트일 때만 광고된다
  addr=$(adb mdns services 2>/dev/null | awk '/_adb-tls-connect/{print $NF}' | head -1 || true)
  [ -n "$addr" ] && cands+=("$addr")
  addr=$(adb mdns services 2>/dev/null | awk '/_adb\._tcp/{print $NF}' | head -1 || true)
  [ -n "$addr" ] && cands+=("$addr")
  # 2) 기본 게이트웨이 — 맥이 폰 핫스팟에 붙어 있으면 폰이 곧 게이트웨이다
  gw=$(route -n get default 2>/dev/null | awk '/gateway/{print $2}' || true)
  [ -n "$gw" ] && cands+=("$gw:5555")
  # 3) Tailscale — 망이 서로 달라도 닿는 마지막 보루
  ts=$(/Applications/Tailscale.app/Contents/MacOS/Tailscale status 2>/dev/null | awk '/android/{print $1}' | head -1 || true)
  [ -n "$ts" ] && cands+=("$ts:5555")

  for c in "${cands[@]}"; do
    local h="${c%:*}" pt="${c##*:}"
    nc -z -w 2 "$h" "$pt" 2>/dev/null || continue
    echo "  연결 시도: $c" >&2
    if adb connect "$c" 2>&1 | grep -q "connected"; then
      sleep 1
      has_device && return 0
    fi
  done
  return 1
}

# 기기 자동 탐색: 로컬 Wi-Fi 가 있으면 그쪽이 빠르다(암호화 오버헤드 없음)
pick_device() {
  local devs; devs=$(adb devices | awk '$2=="device"{print $1}' || true)
  local mine; mine=$(ipconfig getifaddr en0 2>/dev/null | cut -d. -f1-3 || true)
  for d in $devs; do
    case "$d" in "$mine".*) echo "$d"; return;; esac
  done
  for d in $devs; do
    case "$d" in 100.*) echo "$d"; return;; esac
  done
  echo "$devs" | head -1
}

# 연결까지 끝내고 기기 ID 를 돌려준다. 실패하면 안내 후 1 을 반환.
resolve_device() {
  auto_connect || true
  local dev; dev=$(pick_device)
  if [ -z "$dev" ]; then
    echo "❌ 연결된 기기 없음" >&2
    echo "   adb connect ${CLIPBRIDGE_PHONE_HOST:-<폰 Tailscale 주소>}:5555   # Tailscale" >&2
    echo "   폰을 재부팅했다면 무선 adb 가 풀렸을 수 있습니다" >&2
    return 1
  fi
  ensure_tcpip "$dev" || true
  echo "$dev"
}
