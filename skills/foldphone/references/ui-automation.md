# 폰 앱 UI 를 adb 로 몰기 (uiautomator)

삼성 앱처럼 **공개 API 가 없는** 기능을 쓸 때. 이 저장소의 `phone-home` 과,
별도 저장소로 나눈 온디바이스 전사 도구(삼성 음성녹음 앱의 전사 기능에는 인텐트가 없다)가 이 방식이다.

## 기본 레시피

```bash
A() { adb -s "$DEV" "$@"; }
dump() { A shell uiautomator dump /sdcard/_ui.xml >/dev/null 2>&1; A shell "cat /sdcard/_ui.xml"; }
```

**좌표를 하드코딩하지 말고 라벨로 찾는다** — 레이아웃이 조금 바뀌어도 버틴다.

```python
# dump 한 XML 에서 text/content-desc 로 탭 좌표 구하기
import re, html, sys
x = sys.stdin.read(); want = sys.argv[1]
for m in re.finditer(r'<node[^>]*>', x):
    n = m.group(0)
    t = re.search(r'text="([^"]*)"', n); d = re.search(r'content-desc="([^"]*)"', n)
    b = re.search(r'bounds="(\[\d+,\d+\]\[\d+,\d+\])"', n)
    lab = html.unescape((t.group(1) if t else '') or (d.group(1) if d else ''))
    if b and lab == want:
        c = [int(v) for v in re.findall(r'\d+', b.group(1))]
        print((c[0]+c[2])//2, (c[1]+c[3])//2); break
```

**라벨이 겹치면 `resource-id` 로 집는다.** (같은 이름의 버튼이 화면에 둘 이상 있는 경우가 흔하다)

## 밟은 함정

| 증상 | 원인 · 해결 |
|---|---|
| 목록에서 파일을 못 찾음 | **파일명이 길면 목록 UI 가 잘라서** 매칭 실패(31자 ✗ / 9자 ✓) → 폰에 올릴 이름은 짧게 |
| 짧은 이름인데도 못 찾음 | 목록은 날짜순이라 옛 항목이 화면 밖에 있고 **`uiautomator` 는 보이는 노드만 덤프**한다 → 스크롤 말고 **검색** |
| 「옵션 더보기」를 눌렀는데 엉뚱한 메뉴 | 폴더블은 **2패널**이라 같은 이름 버튼이 둘 → **x 좌표 하한**으로 오른쪽 패널 것을 고른다 |
| 버튼을 눌렀는데 아무 반응 없이 대기만 | **중간에 다이얼로그가 하나 더 뜬다**(예: 삼성 STT 의 「언어 선택」). 모르면 타임아웃까지 통째로 날린다 |
| 확인 버튼 라벨이 앞 메뉴와 똑같음 | text 매칭 불가 → **`resource-id`** 로 (예: `select_language_trans_text`) |
| 전사문이 44자로 잘림 | 화면 긁기는 **보이는 만큼만** 온다 → 공유 시트로 **파일을 꺼내야** 전문을 얻는다 |

## 백그라운드로 돌릴 수 있나

가상 디스플레이(`settings put global overlay_display_devices '1080x2400/320'` + `am start --display N`)로
**앱 분리와 덤프까지는 성공**했으나, 삼성이 이를 **DeX 외부 모니터로 인식해 팝업**을 띄운다.
터치 주입(`input -d N`)은 미검증. 해제는 `settings put global overlay_display_devices ''`.

→ 현실적으로 **UI 자동화는 폰 화면을 점유한다.** 그래서 긴 자동화는
「화면 꺼짐 + 충전 중」일 때만 돌도록 짜는 편이 낫다.
