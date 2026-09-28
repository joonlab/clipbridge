#!/usr/bin/env python3
"""
clipd — 폰에서 보낸 텍스트를 맥 클립보드에 넣는 수신 서버.

Tailscale 인터페이스에만 바인딩한다. tailnet 밖에서는 포트 자체가 안 보인다.
폰 앱(ClipBridge)이 공유 시트로 받은 텍스트를 여기로 POST 한다.

설정은 ~/.config/clipbridge/clipbridge.env (config.example.env 참고) 또는 환경변수로 준다.
GET /health 를 뺀 모든 엔드포인트는 X-ClipBridge-Token 헤더(또는 ?token=)를 요구한다.
"""
import http.server
import hmac
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import datetime
import socket
import urllib.parse
import urllib.request
import urllib.error

PORT = 8787
HERE = os.path.dirname(os.path.realpath(__file__))
REPO = os.path.dirname(HERE)


def load_config():
    """~/.config/clipbridge/clipbridge.env 의 KEY=VALUE 를 읽는다. 환경변수가 있으면 그쪽이 이긴다."""
    path = os.environ.get("CLIPBRIDGE_CONFIG",
                          os.path.expanduser("~/.config/clipbridge/clipbridge.env"))
    cfg = {}
    try:
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k = k.replace("export ", "").strip()
                v = v.strip().strip('"').strip("'").replace("$HOME", os.path.expanduser("~"))
                cfg[k] = v
    except FileNotFoundError:
        pass
    cfg.update({k: v for k, v in os.environ.items() if k.startswith("CLIPBRIDGE_")})
    return cfg


CFG = load_config()

# ── 맥 → 폰 (2026-09-21 추가) ────────────────────────────────────────────────
# Android 의 "포커스 있는 앱만" 제약은 읽기에만 걸린다. 쓰기(setPrimaryClip)는
# 포커스를 요구하지 않아서, 이 방향은 완전 자동이 된다(실측). 폰의 ClipBridge
# 수신 서버로 밀어 넣는다 — adb 는 절전에 끊기므로 쓰지 않는다.
PHONE_HOST = CFG.get("CLIPBRIDGE_PHONE_HOST", "")   # 폰의 Tailscale 주소
PHONE_PORT = 8788
# 맥과 폰이 나눠 갖는 토큰. 폰 앱 빌드 때 넣은 값(BuildConfig.CLIPBRIDGE_TOKEN)과 같아야 한다.
TOKEN = CFG.get("CLIPBRIDGE_TOKEN", "")
MAX_PUSH_CHARS = 100_000
CLIPWATCH = os.path.join(HERE, "clipwatch")

# 실행 중 생기는 상태·로그는 저장소 밖에 둔다 — 로그에는 사적인 흔적이 남는다.
STATE_DIR = CFG.get("CLIPBRIDGE_STATE_DIR") or os.path.expanduser("~/.local/state/clipbridge")
WATCH_STATE = os.path.join(STATE_DIR, "watch.state")
NOTIFY_FILTER = os.path.join(STATE_DIR, "notify-filter.json")
NOTIFY_STATS = os.path.join(STATE_DIR, "notify-stats.json")
LOG = os.path.join(STATE_DIR, "clipd.log")

# 폰에서 보낸 파일이 떨어지는 곳.
INBOX = CFG.get("CLIPBRIDGE_INBOX") or os.path.expanduser("~/ClipBridge-inbox")

# adb 가 끊겨도 앱을 폰에 넣을 수 있게, APK 를 HTTP 로 제공한다.
# 폰 브라우저에서 http://<맥 주소>:8787/?token=<토큰> 을 열어 받는다.
APKS = {
    "clipbridge": os.path.join(REPO, "android/app/build/outputs/apk/debug/app-debug.apk"),
}


def tailscale_ip():
    """utun 중 100.64/10 (CGNAT, Tailscale 대역) 주소를 찾는다."""
    out = subprocess.run(["ifconfig"], capture_output=True, text=True).stdout
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("inet 100."):
            ip = line.split()[1]
            second = int(ip.split(".")[1])
            if 64 <= second <= 127:      # CGNAT 대역만
                return ip
    return None


def log(msg):
    stamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{stamp}] {msg}"
    print(line, flush=True)
    with open(LOG, "a") as f:
        f.write(line + "\n")


def _as_str(s):
    """AppleScript 문자열 리터럴로 안전하게 — 파일명에 따옴표가 있어도 스크립트가 깨지지 않게."""
    return '"' + str(s).replace("\\", "\\\\").replace('"', '\\"') + '"'


def notify(title, body):
    """맥 알림센터로 알린다 — 파일이 조용히 쌓이면 온 줄도 모른다."""
    try:
        subprocess.run([
            "osascript", "-e",
            f'display notification {_as_str(body)} with title "ClipBridge" subtitle {_as_str(title)}'
        ], capture_output=True, timeout=5)
    except Exception:
        pass


# ── 맥 → 폰 전송 ────────────────────────────────────────────────────────────

# 폰에서 받아 pbcopy 한 텍스트. 그대로 되돌려 보내지 않기 위한 표시.
_suppress = None
# 마지막 전송 성공 여부. 상태가 바뀔 때만 로그한다(끊겨 있으면 로그가 폭주한다).
_phone_ok = None


def watch_enabled():
    """기본은 켜짐. 파일이 있으면 그 값을 따른다."""
    try:
        with open(WATCH_STATE) as f:
            return f.read().strip() != "off"
    except FileNotFoundError:
        return True


def set_watch(on):
    with open(WATCH_STATE, "w") as f:
        f.write("on" if on else "off")


def push_to_phone(text):
    """폰 클립보드에 넣는다. (성공여부, 메시지)"""
    global _phone_ok
    if not text:
        return False, "빈 텍스트"
    if len(text) > MAX_PUSH_CHARS:
        return False, f"너무 김 ({len(text)}자 > {MAX_PUSH_CHARS})"
    req = urllib.request.Request(
        f"http://{PHONE_HOST}:{PHONE_PORT}/clip",
        data=text.encode("utf-8"),
        headers={
            "X-ClipBridge-Token": TOKEN,
            "Content-Type": "text/plain; charset=utf-8",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=4) as r:
            r.read()
        if _phone_ok is False:
            log("폰 연결 회복 — 맥→폰 전송 재개")
        _phone_ok = True
        return True, "ok"
    except Exception as e:
        if _phone_ok is not False:
            log(f"⚠️ 폰에 못 보냄 ({type(e).__name__}) — ClipBridge 수신 서버·Tailscale 확인")
        _phone_ok = False
        return False, f"{type(e).__name__}: {e}"


# ── 폰 알림 → 맥 ────────────────────────────────────────────────────────────
# 기본은 «다 보여주되 소음만 뺀다». 어느 앱이 시끄러운지는 써 보기 전에는 모르므로,
# 앱별 차단은 쓰면서 늘린다(phone-notify deny <pkg>). 통계를 남기는 이유가 그것이다.
NOTIFY_DEFAULT_DENY = [
    "com.android.systemui",
    "com.samsung.android.lool",          # 디바이스 케어
    "com.sec.android.daemonapp",         # 날씨 위젯
    "com.google.android.gms",            # Play 서비스
]


def notify_filter():
    try:
        with open(NOTIFY_FILTER) as f:
            cfg = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        cfg = {}
    cfg.setdefault("enabled", True)
    cfg.setdefault("mode", "deny")          # deny: 목록만 막는다 / allow: 목록만 통과
    cfg.setdefault("apps", list(NOTIFY_DEFAULT_DENY))
    return cfg


def save_notify_filter(cfg):
    with open(NOTIFY_FILTER, "w") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


def bump_notify_stat(pkg, app, blocked):
    """어느 앱이 얼마나 오는지 세어 둔다 — 차단 목록을 짤 근거가 된다."""
    try:
        with open(NOTIFY_STATS) as f:
            st = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        st = {}
    e = st.setdefault(pkg, {"app": app, "count": 0, "blocked": 0, "last": ""})
    e["app"] = app or e.get("app", pkg)
    e["count"] += 1
    if blocked:
        e["blocked"] += 1
    e["last"] = datetime.datetime.now().strftime("%m-%d %H:%M")
    with open(NOTIFY_STATS, "w") as f:
        json.dump(st, f, ensure_ascii=False, indent=2)


def show_mac_notification(app, title, text, pkg):
    """terminal-notifier 가 있으면 그걸로(앱별 묶음 지원), 없으면 osascript."""
    body = text or title
    sub = title if text else ""
    tn = shutil.which("terminal-notifier") or "/opt/homebrew/bin/terminal-notifier"
    try:
        subprocess.run([
            tn,
            "-title", f"📱 {app}",
            "-subtitle", sub,
            "-message", body[:400],
            "-group", f"clipbridge-{pkg}",    # 같은 앱은 갈아끼운다 — 쌓이지 않게
        ], capture_output=True, timeout=5)
        return
    except Exception:
        pass
    notify(app, (f"{title} — " if title else "") + body[:150])



def clipboard_watcher():
    """clipwatch(Swift) 가 흘리는 변경 이벤트를 받아 폰으로 밀어 넣는다."""
    global _suppress
    while True:
        try:
            proc = subprocess.Popen(
                [CLIPWATCH], stdout=subprocess.PIPE, text=True, bufsize=1)
        except Exception as e:
            log(f"clipwatch 기동 실패: {e} — 30초 후 재시도")
            time.sleep(30)
            continue

        for line in proc.stdout:
            try:
                ev = json.loads(line)
            except json.JSONDecodeError:
                continue
            text = ev.get("text", "")
            if not text:
                continue
            # 폰에서 방금 받은 것을 되쏘지 않는다
            if text == _suppress:
                _suppress = None
                continue
            if not watch_enabled():
                continue
            # 비밀번호 관리자가 붙인 표시 — 자동 동기화에서 제외한다
            if ev.get("concealed"):
                log("🔒 concealed 클립보드 — 폰으로 보내지 않음")
                continue
            ok, msg = push_to_phone(text)
            if ok:
                log(f"← 맥 → 폰 {len(text)}자")   # 내용은 남기지 않는다

        proc.wait()
        log("clipwatch 가 종료됐다 — 2초 후 재시작")
        time.sleep(2)


class Handler(http.server.BaseHTTPRequestHandler):
    def _authorized(self):
        """토큰 확인. 헤더가 기본이고, 헤더를 못 붙이는 폰 브라우저(APK 받기)는 ?token= 으로."""
        got = self.headers.get("X-ClipBridge-Token", "")
        if not got:
            qs = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            got = (qs.get("token") or [""])[0]
        return bool(TOKEN) and hmac.compare_digest(got.encode(), TOKEN.encode())

    def _route(self):
        return urllib.parse.urlsplit(self.path).path

    def _reply(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _serve_apk(self, key):
        path = APKS.get(key)
        if not path or not os.path.exists(path):
            self._reply(404, {"ok": False, "error": f"apk not found: {key}"})
            return
        data = open(path, "rb").read()
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Disposition", f'attachment; filename="{key}.apk"')
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        log(f"{self.client_address[0]} ← apk/{key} ({len(data)//1024}KB)")

    def _serve_index(self):
        import datetime as dt
        q = "?token=" + urllib.parse.quote(TOKEN)
        rows = []
        for k, p in APKS.items():
            if os.path.exists(p):
                st = os.stat(p)
                built = dt.datetime.fromtimestamp(st.st_mtime).strftime("%m-%d %H:%M")
                rows.append(f'<li><a href="/apk/{k}{q}">{k}</a> — {st.st_size//1024}KB · {built} 빌드</li>')
            else:
                rows.append(f"<li>{k} — 빌드 안 됨</li>")
        html = ("<!doctype html><meta charset=utf-8>"
                "<meta name=viewport content='width=device-width,initial-scale=1'>"
                "<title>clipd</title>"
                "<style>body{font-family:-apple-system,system-ui,sans-serif;padding:24px;"
                "background:#111;color:#eee}a{color:#7ab7ff;font-size:18px}"
                "li{margin:14px 0}</style>"
                "<h2>clipd — APK 배포</h2><ul>" + "".join(rows) + "</ul>")
        body = html.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        route = self._route()
        if route == "/health":
            self._reply(200, {"ok": True, "host": socket.gethostname()})
            return
        if not self._authorized():
            self._reply(403, {"ok": False, "error": "forbidden"})
            return
        if route.startswith("/apk/"):
            self._serve_apk(route[len("/apk/"):])
        elif route in ("/", "/apk"):
            self._serve_index()
        elif route == "/clip":
            # 폰이 당겨 가는 경로: 현재 맥 클립보드를 돌려준다
            text = subprocess.run(["pbpaste"], capture_output=True, text=True).stdout
            self._reply(200, {"ok": True, "text": text})
        elif route == "/notify-filter":
            self._reply(200, {"ok": True, "filter": notify_filter()})
        elif route == "/notify-stats":
            try:
                with open(NOTIFY_STATS) as f:
                    self._reply(200, {"ok": True, "stats": json.load(f)})
            except (FileNotFoundError, json.JSONDecodeError):
                self._reply(200, {"ok": True, "stats": {}})
        elif route == "/watch":
            self._reply(200, {
                "ok": True,
                "watch": watch_enabled(),
                "phone": f"{PHONE_HOST}:{PHONE_PORT}",
                "phone_reachable": _phone_ok,
            })
        else:
            self._reply(404, {"ok": False, "error": "not found"})

    def _recv_file(self):
        """파일 수신. 큰 파일도 메모리에 다 올리지 않고 청크로 흘려 쓴다."""
        raw_name = self.headers.get("X-Filename", "")
        # 앱이 URLEncoder.encode() 로 보내면 공백이 "+" 가 된다 → unquote 가 아니라 unquote_plus
        name = urllib.parse.unquote_plus(raw_name) or "phone-file"
        name = os.path.basename(name).replace("/", "_") or "phone-file"

        os.makedirs(INBOX, exist_ok=True)

        # 같은 이름이 있으면 덮어쓰지 않고 번호를 붙인다
        dest = os.path.join(INBOX, name)
        if os.path.exists(dest):
            stem, ext = os.path.splitext(name)
            n = 2
            while os.path.exists(os.path.join(INBOX, f"{stem} ({n}){ext}")):
                n += 1
            dest = os.path.join(INBOX, f"{stem} ({n}){ext}")

        total = int(self.headers.get("Content-Length", 0))
        written = 0
        try:
            with open(dest, "wb") as f:
                remaining = total
                while remaining > 0:
                    chunk = self.rfile.read(min(65536, remaining))
                    if not chunk:
                        break
                    f.write(chunk)
                    remaining -= len(chunk)
                    written += len(chunk)
        except Exception as e:
            log(f"파일 수신 실패 {name}: {e}")
            self._reply(500, {"ok": False, "error": str(e)})
            return

        if written != total:
            log(f"⚠️ 불완전 수신 {os.path.basename(dest)}: {written}/{total} bytes")
            self._reply(500, {"ok": False, "error": f"incomplete {written}/{total}"})
            return

        mb = written / 1024 / 1024
        log(f"{self.client_address[0]} → 파일 {os.path.basename(dest)} ({mb:.1f}MB)")
        notify(f"{os.path.basename(dest)}", f"{mb:.1f}MB 받음")
        self._reply(200, {"ok": True, "saved": os.path.basename(dest), "bytes": written})

    def do_POST(self):
        global _suppress
        # 인증 없이 받던 /clip·/file·/watch 도 이제 토큰을 요구한다(폰 앱·CLI 는 헤더를 붙인다).
        if not self._authorized():
            self._reply(403, {"ok": False, "error": "forbidden"})
            return
        if self.path == "/file":
            self._recv_file()
            return

        if self.path.startswith("/watch"):
            n = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(n).decode("utf-8").strip() if n else ""
            qs = [x for x in self.path.partition("?")[2].split("&") if x and not x.startswith("token=")]
            arg = body or (qs[0] if qs else "")
            if arg in ("on", "off"):
                set_watch(arg == "on")
                log(f"맥→폰 자동 동기화 {'켬' if arg == 'on' else '끔'}")
            self._reply(200, {"ok": True, "watch": watch_enabled()})
            return

        if self.path == "/notify":
            n = int(self.headers.get("Content-Length", 0))
            try:
                ev = json.loads(self.rfile.read(n).decode("utf-8"))
            except (json.JSONDecodeError, UnicodeDecodeError):
                self._reply(400, {"ok": False, "error": "bad json"})
                return

            pkg = ev.get("pkg", "")
            app = ev.get("app", pkg)
            title = ev.get("title", "")
            text = ev.get("text", "")

            cfg = notify_filter()
            listed = pkg in cfg["apps"]
            blocked = (not cfg["enabled"]) or \
                      (listed if cfg["mode"] == "deny" else not listed)
            bump_notify_stat(pkg, app, blocked)

            if not blocked:
                show_mac_notification(app, title, text, pkg)
                # 본문은 로그에 남기지 않는다 — 카톡·문자 내용이 평문으로 쌓인다.
                # 알림 자체는 맥 알림센터에 뜨므로 로그에는 «왔다»만 있으면 된다.
                log(f"🔔 {app} ({len(title) + len(text)}자)")
            self._reply(200, {"ok": True, "shown": not blocked})
            return

        if self.path.startswith("/notify-filter"):
            n = int(self.headers.get("Content-Length", 0))
            try:
                cmd = json.loads(self.rfile.read(n).decode("utf-8")) if n else {}
            except json.JSONDecodeError:
                cmd = {}
            cfg = notify_filter()
            action, pkg = cmd.get("action"), cmd.get("pkg")
            if action == "deny" and pkg:
                if cfg["mode"] == "deny":
                    if pkg not in cfg["apps"]:
                        cfg["apps"].append(pkg)
                else:
                    cfg["apps"] = [a for a in cfg["apps"] if a != pkg]
            elif action == "allow" and pkg:
                if cfg["mode"] == "deny":
                    cfg["apps"] = [a for a in cfg["apps"] if a != pkg]
                elif pkg not in cfg["apps"]:
                    cfg["apps"].append(pkg)
            elif action in ("on", "off"):
                cfg["enabled"] = action == "on"
            elif action == "mode" and cmd.get("mode") in ("allow", "deny"):
                cfg["mode"] = cmd["mode"]
                cfg["apps"] = []
            save_notify_filter(cfg)
            log(f"알림 필터 변경: {action} {pkg or ''}".strip())
            self._reply(200, {"ok": True, "filter": cfg})
            return

        if self.path == "/push":
            # 수동 전송: 지금 맥 클립보드를 폰으로. 자동 감시와 무관하게 동작한다.
            n = int(self.headers.get("Content-Length", 0))
            text = self.rfile.read(n).decode("utf-8") if n else \
                subprocess.run(["pbpaste"], capture_output=True, text=True).stdout
            ok, msg = push_to_phone(text)
            if ok:
                log(f"← 맥 → 폰 (수동) {len(text)}자")
            self._reply(200 if ok else 502,
                        {"ok": ok, "chars": len(text), "error": None if ok else msg})
            return

        if self.path != "/clip":
            self._reply(404, {"ok": False, "error": "not found"})
            return
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n)
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            self._reply(400, {"ok": False, "error": "utf-8 decode failed"})
            return

        # Content-Type 이 json 이면 {"text": ...} 로도 받는다
        if self.headers.get("Content-Type", "").startswith("application/json"):
            try:
                text = json.loads(text).get("text", "")
            except json.JSONDecodeError:
                pass

        _suppress = text          # 방금 받은 것을 watcher 가 되쏘지 않게
        subprocess.run(["pbcopy"], input=text, text=True)
        log(f"폰 → 맥 {len(text)}자")   # 내용은 남기지 않는다
        self._reply(200, {"ok": True, "chars": len(text)})

    def log_message(self, *args):
        pass  # 기본 접근로그 끔 — 우리 log() 만 쓴다


if __name__ == "__main__":
    if not TOKEN:
        print("❌ CLIPBRIDGE_TOKEN 이 비어 있다 — ~/.config/clipbridge/clipbridge.env 를 채울 것"
              " (config.example.env 참고)", file=sys.stderr)
        sys.exit(1)
    if not PHONE_HOST:
        print("⚠️ CLIPBRIDGE_PHONE_HOST 가 비어 있다 — 맥→폰 방향은 동작하지 않는다", file=sys.stderr)
    os.makedirs(STATE_DIR, exist_ok=True)
    ip = tailscale_ip()
    if not ip:
        print("❌ Tailscale IP 를 못 찾았다. Tailscale 이 떠 있는지 확인할 것.", file=sys.stderr)
        sys.exit(1)
    log(f"clipd 시작 — http://{ip}:{PORT}  (Tailscale 전용 바인딩)")

    # 맥 클립보드 변경 → 폰으로. clipwatch 가 없으면 이 방향만 조용히 꺼진다.
    if os.path.exists(CLIPWATCH):
        threading.Thread(target=clipboard_watcher, daemon=True).start()
        log(f"맥→폰 자동 동기화 {'켜짐' if watch_enabled() else '꺼짐'} "
            f"→ {PHONE_HOST}:{PHONE_PORT}")
    else:
        log("⚠️ clipwatch 가 없다(server/clipwatch) — 맥→폰 자동 동기화 꺼짐."
            " 빌드: swiftc -O -o server/clipwatch server/clipwatch.swift")

    http.server.HTTPServer((ip, PORT), Handler).serve_forever()
