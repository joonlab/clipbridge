# 구조 — 서버·엔드포인트·앱

```
맥                                          폰 (Galaxy Z Fold 8)
┌─────────────────────────────┐            ┌──────────────────────────────┐
│ clipwatch (Swift)           │            │ ClipBridge 앱                │
│  NSPasteboard.changeCount   │            │  ├ 수신 서버 :8788 (FGS)     │
│         ↓ JSON 한 줄        │            │  ├ 퀵설정 타일 「맥으로 복사」│
│ clipd (Python, launchd)     │──Tailscale→│  ├ 공유 시트 「맥으로 보내기」│
│  :8787                      │←───────────│  └ 알림 리스너 → 맥          │
└─────────────────────────────┘            └──────────────────────────────┘
```

| 구성 | 경로 |
|---|---|
| 맥 수신 서버 + 감시자 | `server/clipd.py` (launchd `local.clipbridge.clipd`, `server/install-launchd.sh`) |
| 맥 클립보드 감시 | `server/clipwatch.swift` (빌드된 바이너리 `server/clipwatch`) |
| 폰 앱 | `android/` (Kotlin + Compose) |
| CLI | `cli/` → `~/.local/bin/` 심링크 (`cli/install.sh`) |
| 설정 | `~/.config/clipbridge/clipbridge.env` (`config.example.env`) — CLI·clipd 공통, `cli/clipbridge-env.sh` 가 읽는다 |
| 기기 연결 공통 로직 | `cli/lib-device.sh` — **고칠 일이 생기면 여기만** |

## 엔드포인트

**맥 `clipd` (:8787, Tailscale 인터페이스에만 바인딩, `GET /health` 외에는 `X-ClipBridge-Token` 필요 — 폰 브라우저용 `?token=` 도 받는다)**

| | 용도 |
|---|---|
| `POST /clip` | 텍스트 → 맥 클립보드(`pbcopy`) |
| `POST /file` | 파일 → `$CLIPBRIDGE_INBOX` (기본 `~/ClipBridge-inbox`, 청크 스트리밍, 중복은 `(2)`) |
| `POST /notify` | 폰 알림 수신 → 필터 → `terminal-notifier` |
| `GET·POST /watch` | 맥→폰 자동 동기화 상태/토글 |
| `POST /push` | 지금 맥 클립보드를 폰으로 즉시 |
| `GET /clip` | 맥 클립보드 읽기 |
| `GET·POST /notify-filter`, `GET /notify-stats` | 앱별 알림 필터 |
| `GET /apk/<키>?token=` | **adb 없이 앱 설치** — 폰 브라우저로 받는다 |
| `GET /health` | 상태 |

**폰 `ClipBridge` (:8788, Tailscale 주소에만 바인딩, `X-ClipBridge-Token` 필요)**

| | 용도 |
|---|---|
| `POST /clip` | → 폰 클립보드 (`setPrimaryClip`) |
| `POST /file` | → 폰 「다운로드」 (MediaStore, 권한 불필요) + 알림 |
| `POST /open` | 링크 열기 (BAL 막히면 고우선 알림으로 폴백) |
| `POST /sms` · `GET /sms?limit=&q=&with=` | 문자 발송 / 읽기 |
| `GET /health` | 기기명 · 바인딩 · **권한 3종 상태** |

토큰: `CLIPBRIDGE_TOKEN` — 폰 앱은 빌드 때 `BuildConfig.CLIPBRIDGE_TOKEN` 으로 넣고, 맥 clipd·CLI 는 설정 파일에서 읽는다. 둘이 같아야 한다.

## 앱을 고쳤을 때

```bash
clipbridge-deploy      # 빌드 → 설치 → 앱 실행 → 수신서버·권한 확인까지
```
⚠️ **설치 직후 앱을 한 번 열어야 한다.** Android 12+ 는 백그라운드 FGS 시작을 막는데
`MY_PACKAGE_REPLACED` 는 면제가 아니라서, 폰이 Doze 중이면 서버가 조용히 안 뜬다.
`clipbridge-deploy` 가 이 구멍을 메운다.

빌드에는 JDK 21 이 필요하다. `clipbridge-deploy` 는 `JAVA_HOME` 이 비어 있으면 Homebrew `openjdk@21` 을 잡는다
(gradlew 가 `org.gradle.java.home` 을 읽기 **전에** java 를 찾는다)

## 왜 이렇게 만들었나 (설계 이유)

- **Tailscale 바인딩**: tailnet 밖에서는 포트 자체가 안 보인다. AP 클라이언트 격리도 우회된다
- **폰 쪽은 포그라운드 서비스 + WifiLock**: adb 는 절전에 끊기지만 이건 **화면 끈 Doze 15분 내내 5/5 성공**(실측)
- **clipwatch 를 `pbpaste` 가 아니라 `NSPasteboard` 직접**: 타입 목록을 봐야
  `org.nspasteboard.ConcealedType`(1Password 등)을 걸러낼 수 있다. `pbpaste` 로는 이 구분이 불가능하다
