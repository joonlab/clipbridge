# 증상별 진단

## "폰이 안 잡힌다" / adb 끊김

```bash
adb devices                    # offline 항목이 있으면 먼저 치운다
adb disconnect <그 항목>
. "$CLIPBRIDGE_REPO/cli/lib-device.sh" && resolve_device
```
- **화면을 한 번 켜면 대개 살아난다** — adbd 가 절전에 TCP 리슨을 멈춘다(하루에도 몇 번)
- 무선 디버깅 포트는 **재부팅마다 바뀐다**. 페어링은 유지되므로 mDNS 로 찾으면 된다
- 「사용할 수 없음(허용되지 않음)」 = 그 네트워크에서 무선 디버깅을 허용한 적이 없다는 뜻.
  무선 디버깅을 껐다 켜고 **「이 네트워크에서 항상 허용」 체크 후 「허용」**
  (체크박스와 버튼이 별개다 — 체크만 하면 안 닫힌다)

## "맥에서 복사했는데 폰에 안 간다"

```bash
clip2phone status              # watch:true 인가 · phone_reachable 인가
. ~/.config/clipbridge/clipbridge.env && curl -s "http://$CLIPBRIDGE_PHONE_HOST:8788/health"
tail -5 ~/.local/state/clipbridge/clipd.log
```
| 로그 | 뜻 |
|---|---|
| `🔒 concealed 클립보드` | **정상 동작이다** — 비밀번호 관리자에서 복사한 것은 일부러 안 보낸다 |
| `⚠️ 폰에 못 보냄` | 폰 수신 서버가 죽었거나 Tailscale 이 끊겼다 |
| 아무것도 없음 | 맥 쪽 watcher 가 안 돈다 → `launchctl kickstart -k gui/$(id -u)/local.clipbridge.clipd` |

## "앱을 설치했는데 수신 서버가 안 뜬다"

Android 12+ 는 백그라운드 FGS 시작을 막고 **`MY_PACKAGE_REPLACED` 는 면제가 아니다.**
폰이 Doze 중이면 조용히 실패한다(로그도 크래시도 없다).
→ **앱을 한 번 열면 뜬다.** `clipbridge-deploy` 가 설치 직후 앱을 열어 이 구멍을 메운다.

## "파일을 보냈는데 폰에서 안 열린다"

MediaStore 는 `DISPLAY_NAME` 의 확장자가 `MIME_TYPE` 과 어긋나면 **확장자를 덧붙인다**
(`app-debug.apk` → `app-debug.apk.zip` → 설치 불가).
맥의 `file --mime-type` 은 컨테이너를 보므로 APK·docx·hwpx 를 전부 zip 이라 답한다.
→ 양쪽 다 **확장자 기준으로 MIME 을 정하게** 고쳐져 있다. 새 확장자를 추가할 때 이 규칙을 깨지 말 것.

## "링크가 폰에서 안 열린다"

`GET /health` 의 `canOpenDirectly` 를 본다.
- `false` → 고우선 알림으로 가고 **탭 한 번**이면 열린다(정상)
- 바로 열리게 하려면 `adb shell appops set kr.joonlab.clipbridge SYSTEM_ALERT_WINDOW allow`

⚠️ Android 10+ 의 BAL(백그라운드 액티비티 실행) 제한은 **막혀도 예외를 안 던진다.**
logcat 에 `Background activity launch blocked` 만 남는다. 성공을 가정하지 말 것.

## "폰 카메라 창이 OBS 목록에 없다"

1. **카메라 창과 OBS 가 다른 데스크톱(Space)에 있다** ← 가장 흔하다.
   권한과 무관하고 `Show windows with empty names` 로도 못 넘는다. **같은 Space 로 모은다.**
2. OBS 속성 창은 **열리는 시점의 목록**을 쓴다 → 창을 띄운 뒤 Cancel → 소스 더블클릭

진단(추측 말고):
```python
import Quartz
allw  = Quartz.CGWindowListCopyWindowInfo(Quartz.kCGWindowListOptionAll |
        Quartz.kCGWindowListExcludeDesktopElements, Quartz.kCGNullWindowID)
onscr = Quartz.CGWindowListCopyWindowInfo(Quartz.kCGWindowListOptionOnScreenOnly |
        Quartz.kCGWindowListExcludeDesktopElements, Quartz.kCGNullWindowID)
# All 엔 있는데 OnScreenOnly 에 없으면 → 다른 Space
```

## "가상 카메라가 Zoom 목록에 없다"

macOS 13+ 의 OBS 가상 카메라는 **시스템 확장**이다.
```bash
systemextensionsctl list | grep -i "obs.*camera"
# [activated waiting for user]  → 승인 안 됨 (Zoom 목록에 안 뜬다)
# [activated enabled]           → 정상
```
⚠️ `system_profiler SPCameraDataType` 로는 이 상태가 **안 보인다.**
승인: OBS `Start Virtual Camera` → 시스템 설정 → 일반 → 로그인 항목 및 확장 → 카메라 확장 → OBS 허용

## "폰 카메라 앱을 켜면 스트림이 죽는다"

```
CameraAccessException: The system-wide limit for number of open cameras has been reached
```
**동시에 열 수 있는 카메라 «개수» 한도**다. 카메라 ID 를 바꿔도 안 된다(전면 id=1 로 실측 실패).
- 카메라 앱을 닫으면 `phone-cam` 이 **스스로 다시 붙는다**(실측 20초)
- 카메라 앱 기능(줌·야간모드)을 쓰면서 맥에 보내려면 → **`phone` 화면 미러링** + 카메라 앱 전체화면

## `pgrep`/`pkill` 이 `illegal byte sequence`

BSD 계열은 대상 **명령줄에 한글**이 섞이면 실패한다(창 제목이 「폴드8 카메라」).
→ **`LC_ALL=C pgrep -f …`**
