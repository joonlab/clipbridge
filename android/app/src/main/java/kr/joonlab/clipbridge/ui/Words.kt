package kr.joonlab.clipbridge.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 화면 문구 도우미 — 기술어·예외 이름을 사람 말로 바꾼다. 원문은 「자세히」에만 둔다. */
object Words {

    /** 네트워크 실패 → (짧은 제목, 원인 추정 한 줄). err 는 예외 클래스 이름이나 "HTTP 500" 같은 원문. */
    fun netCause(err: String): Pair<String, String> = when {
        err.startsWith("HTTP") -> "맥이 요청을 거절했어요" to "맥의 ClipBridge 서버가 오류를 냈어요($err). 맥에서 서버를 다시 켜 보세요."
        err.contains("Timeout", true) -> "맥이 응답하지 않아요" to "맥이 잠들었거나 노트북이 덮였을 수 있어요. Tailscale 이 양쪽 모두 켜져 있는지 확인해 주세요."
        err.contains("ConnectException", true) || err.contains("refused", true) ->
            "맥의 수신 서버가 꺼져 있어요" to "맥은 닿지만 ClipBridge 서버(clipd)가 동작하지 않아요. 맥에서 서버를 켜 주세요."
        err.contains("NoRouteToHost", true) || err.contains("UnknownHost", true) || err.contains("Unreachable", true) ->
            "맥을 찾지 못했어요" to "이 폰의 Tailscale 이 꺼져 있거나 인터넷이 끊겼을 수 있어요."
        err.contains("FileNotFound", true) || err.contains("스트림", true) || err.contains("Security", true) ->
            "파일을 읽지 못했어요" to "보낸 앱이 파일 접근을 허락하지 않았어요. 그 앱에서 다시 공유해 보세요."
        else -> "보내지 못했어요" to "잠시 뒤 다시 시도해 주세요."
    }

    /** "방금 · 12초 전 · 3분 전 · 2시간 전 · 9월 27일" */
    fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            s < 5 -> "방금"
            s < 60 -> "${s}초 전"
            s < 3600 -> "${s / 60}분 전"
            s < 86_400 -> "${s / 3600}시간 전"
            else -> SimpleDateFormat("M월 d일", Locale.KOREA).format(Date(at))
        }
    }

    /** 9월 27일 오후 2:03 */
    fun stamp(at: Long): String = SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA).format(Date(at))

    /** 1023 B · 12 KB · 3.4 MB · 1.2 GB */
    fun size(bytes: Long): String = when {
        bytes < 0 -> "크기 모름"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }
}
