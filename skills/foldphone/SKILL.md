---
name: foldphone
description: >-
  갤럭시 Z 폴드 8(안드로이드 폰)을 맥에서 CLI로 다루는 단일 진입점 — 클립보드·파일·링크를
  폰으로 보내고, 폰 알림·문자를 맥에서 보고, 폰 화면을 미러링하거나 보조 모니터로 쓰고,
  폰 카메라를 맥 웹캠으로 쓰고, 홈 화면을 정리한다. 도구 모음
  (ClipBridge 앱 + clipd 서버 + CLI)이 이미 깔려 있다는 전제이므로 **새로 만들지 말고 이것을 쓴다.**
  반드시 이 스킬을 사용하라 — 사용자가 "폰", "폴드", "폴드8", "갤럭시", "안드로이드"를 언급하며
  (1) 무언가를 폰으로 보내거나("이거 폰으로 보내줘", "파일 폰에 넣어줘", "이 링크 폰에서 열어줘",
  "폰에 복사해줘") (2) 폰의 것을 맥으로 가져오거나("폰 알림 맥에서 보게", "문자 확인해줘",
  "폰 문자 검색", "폰에서 온 파일") (3) 폰 화면·카메라를 쓰거나("폰 화면 띄워줘", "폰 미러링",
  "폰을 보조 모니터로", "폰 카메라 웹캠으로", "줌에 폰 카메라") (4) 폰에 앱을 설치하거나 권한을 주거나
  ("앱 폰에 깔아줘", "adb로 권한", "폰에 APK") (5) 폰이 안 잡힐 때("adb 끊겼어", "폰 연결 안 돼") (6) 폰 홈 화면을 정리할 때("아이콘 폴더로 정리", "홈 화면 정리",
  "폴더 만들어", "빈 페이지 지워") 요청하는 모든 경우. "foldphone"이라고 부르지 않아도 발동한다.
  (안드로이드 **앱을 새로 만드는** 일은 이 스킬 범위가 아니다.)
---

# foldphone — 폴드8을 맥에서 다루기

> 확인한 기기 Galaxy Z Fold 8 (Android 17/API 37) · 맥 ↔ 폰은 Tailscale 로 잇는다.
> 주소·토큰은 `~/.config/clipbridge/clipbridge.env` (`CLIPBRIDGE_MAC_HOST` · `CLIPBRIDGE_PHONE_HOST` · `CLIPBRIDGE_TOKEN`).
> 코드는 이 저장소(`$CLIPBRIDGE_REPO` 로 적는다), CLI 는 `cli/install.sh` 로 `~/.local/bin` 에 링크돼 있다는 전제다. 만들기 전에 아래 표부터 볼 것.

## 1. 먼저 이 표에서 찾는다 — 대개 이미 있다

| 하고 싶은 것 | 명령 |
|---|---|
| 텍스트를 폰으로 | **자동** (맥에서 복사하면 1~2초 안에 폰 클립보드에). 수동은 `clip2phone [텍스트]` |
| 자동 동기화 끄기/켜기 | `clip2phone off` / `on` / `status` |
| 파일을 폰으로 | `file2phone <파일…>` → 폰 「다운로드」 + 알림 |
| 링크를 폰에서 열기 | `link2phone [url]` (인자 없으면 클립보드의 URL) |
| 폰 알림을 맥에서 | `phone-notify status\|apps\|deny <pkg>\|allow <pkg>\|only\|setup` |
| 문자 읽기·검색 | `phone-sms` · `phone-sms read <번호>` · `phone-sms search <키워드>` |
| 문자 보내기 | `phone-sms send [-y] <번호> <내용>` ⚠️ **사용자 승인 없이 보내지 말 것** |
| 폰 화면 미러링 | `phone` · `phone off`(폰 화면 끈 채) · `phone light` · `phone stop` |
| 폰을 보조 모니터로 | `screen2 [해상도]` (기본 1440x900) · `screen2 stop` |
| 폰 카메라를 맥 웹캠으로 | `phone-cam` · `phone-cam vcam`(OBS 가상카메라까지) · `stop`/`off`/`status` |
| 홈 화면 폴더 정리·배치 | `phone-home ls --folders` · `folder <이름> <앱…>` · `add` · `rename` · `pull` · `move` · `drop-empty-pages` → **`references/home-screen.md` 먼저** (팝업 옆에 「설치 삭제」가 있다) |
| ClipBridge 앱 빌드·설치 | `clipbridge-deploy` |

> 폰 온디바이스 STT(`phone-stt`·`stt-queue`)는 이 저장소에 없다 — 별도 저장소로 나눴다.

**폰 → 맥 방향(클립보드·파일)은 폰에서 사람이 조작한다** — 퀵설정 타일 「맥으로 복사」, 공유 시트 「맥으로 보내기」.
맥에서 끌어올 수는 없다(Android 가 백그라운드 클립보드 **읽기**를 막는다).

## 2. 폰이 안 잡힐 때

```bash
adb devices                                   # 비어 있거나 offline 이면
. "$CLIPBRIDGE_REPO/cli/lib-device.sh" && resolve_device
```
`resolve_device` 가 mDNS → 기본 게이트웨이 → Tailscale 순으로 알아서 붙는다(`phone` 계열 CLI 에 내장).
**화면을 한 번 켜면 대개 살아난다** — adbd 가 절전에 TCP 리슨을 멈춘다.

> ⛔ **상시 워크플로를 adb 위에 올리지 말 것.** 절전에 끊긴다.
> 상주가 필요하면 앱(ClipBridge 포그라운드 서비스)으로, adb 는 **요청할 때만** 쓰는 것에만.

## 3. 권한은 adb 로 준다 (민감하므로 사용자에게 알릴 것)

```bash
adb shell appops set kr.joonlab.clipbridge SYSTEM_ALERT_WINDOW allow        # 링크 바로 열기
adb shell cmd notification allow_listener kr.joonlab.clipbridge/kr.joonlab.clipbridge.NotifyMirror
adb shell pm grant kr.joonlab.clipbridge android.permission.READ_SMS        # 문자 읽기
adb shell pm grant kr.joonlab.clipbridge android.permission.SEND_SMS        # 문자 발송
```
지금 무엇이 켜져 있는지는 **폰이 직접 알려준다**:
```bash
. ~/.config/clipbridge/clipbridge.env
curl -s "http://$CLIPBRIDGE_PHONE_HOST:8788/health"
# {"ok":true,"device":"<모델명>","canOpenDirectly":…,"canSendSms":…,"canReadSms":…}
```

## 4. 반드시 지킬 것 — 전부 실측으로 데인 것들

1. **조용히 실패하는 API 가 많다.** `setPrimaryClip`·`startActivity`·`MediaStore.insert` 는 실패해도
   **예외가 없다.** 「보냈다」를 성공으로 읽지 말고 **라운드트립으로 확인**한다
   (맥→폰으로 넣고 → 폰이 들고 있는 값을 되읽어 대조).
2. **`pgrep`/`pkill` 은 `LC_ALL=C` 로.** 대상 명령줄에 한글이 섞이면 `illegal byte sequence` 로 죽는다
   (창 제목이 「폴드8 카메라」다).
3. **문자 발송·파괴적 동작은 사용자 승인 뒤에.** `phone-sms send` 는 기본적으로 묻는다. `-y` 를 함부로 붙이지 말 것.
4. **자동 클립보드 동기화는 concealed 타입을 걸러낸다**(1Password 등). 이 방어를 깨지 말 것.
5. 알림 본문과 클립보드 내용은 **로그에 남기지 않는다** — 카톡 대화·은행 OTP 가 흐른다.

## 5. 더 깊이 필요할 때

| 파일 | 언제 |
|---|---|
| `references/architecture.md` | 서버·엔드포인트·앱 구조를 고쳐야 할 때 |
| `references/ui-automation.md` | 삼성 앱 UI 를 adb 로 몰아야 할 때(uiautomator 레시피·함정) |
| `references/home-screen.md` | 홈 화면 폴더·배치를 정리할 때(선택모드·⊕는 복사·팝업 OCR·드래그 함정) |
| `references/troubleshooting.md` | 증상별 진단 — "안 간다" "안 보인다" "끊긴다" |

함정의 근거와 경위는 저장소 README 의 「만든 과정」 절에 정리돼 있다.
