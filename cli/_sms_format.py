#!/usr/bin/env python3
"""phone-sms 의 출력 포맷터. stdin 으로 /sms 응답 JSON 을 받는다."""
import datetime
import json
import sys

try:
    d = json.load(sys.stdin)
except json.JSONDecodeError:
    print("응답을 읽지 못했다 — 폰 수신 서버 상태를 확인할 것", file=sys.stderr)
    sys.exit(1)

if not d.get("canRead"):
    print("폰에 READ_SMS 권한이 없다 — clipbridge-deploy 를 돌리거나 폰에서 허용할 것",
          file=sys.stderr)
    sys.exit(1)

msgs = d.get("messages", [])
if not msgs:
    print("해당하는 문자가 없다")
    sys.exit(0)

for m in reversed(msgs):          # 오래된 것부터 = 대화 흐름 순서
    t = datetime.datetime.fromtimestamp(m["date"] / 1000).strftime("%m-%d %H:%M")
    arrow = "←" if m["incoming"] else "→"
    unread = "●" if (m["incoming"] and not m["read"]) else " "
    body = m["body"].replace("\n", " ⏎ ")
    print(f'{unread}{t}  {arrow} {m["address"]:<16} {body}')
print(f"\n({len(msgs)}통)")
