package kr.joonlab.clipbridge

import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log

/**
 * 맥이 시킨 문자를 폰이 대신 보낸다. 아이폰의 「문자 이어쓰기」에 해당한다.
 *
 * 권한(SEND_SMS)은 런타임 승인이라 앱을 한 번 열어 허용해야 한다.
 * 70자가 넘으면 분할 전송해야 한다 — 한글은 EMS 라 더 짧게 끊긴다.
 */
object SmsSender {

    private const val TAG = "ClipBridge"

    fun canSend(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /** (성공여부, 메시지) */
    fun send(ctx: Context, to: String, text: String): Pair<Boolean, String> {
        if (!canSend(ctx)) return false to "SEND_SMS 권한 없음 — 폰에서 ClipBridge 를 열어 허용할 것"
        val number = to.filter { it.isDigit() || it == '+' }
        if (number.length < 3) return false to "번호가 이상하다: $to"
        if (text.isBlank()) return false to "본문이 비었다"

        return try {
            val sm = ctx.getSystemService(SmsManager::class.java)
            val parts = sm.divideMessage(text)
            if (parts.size == 1) {
                sm.sendTextMessage(number, null, text, null, null)
            } else {
                sm.sendMultipartTextMessage(number, null, parts, null, null)
            }
            Log.i(TAG, "SMS 전송: $number (${text.length}자, ${parts.size}통)")
            true to "보냄 (${parts.size}통)"
        } catch (e: Exception) {
            Log.e(TAG, "SMS 실패", e)
            false to "${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
