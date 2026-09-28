package kr.joonlab.clipbridge

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 폰의 문자를 읽는다. **읽기 전용** — 발송은 SmsSender 가 따로 한다.
 *
 * adb shell 로도 같은 조회가 되지만(shell uid 가 READ_SMS 를 갖는다), adb 는 절전에
 * 자주 끊긴다. 수신 서버는 상시 살아 있으므로 여기로 붙인다.
 */
object SmsReader {

    private const val TAG = "ClipBridge"

    fun canRead(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * @param limit  최대 건수
     * @param q      본문 검색어(없으면 전체)
     * @param with   특정 상대 번호(끝 8자리로 느슨하게 맞춘다 — 010-/+8210- 표기가 섞인다)
     */
    fun query(ctx: Context, limit: Int, q: String?, with: String?): JSONArray {
        val out = JSONArray()
        if (!canRead(ctx)) return out

        val cols = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ)

        val where = StringBuilder()
        val args = mutableListOf<String>()
        if (!q.isNullOrBlank()) {
            where.append("${Telephony.Sms.BODY} LIKE ?")
            args.add("%$q%")
        }
        if (!with.isNullOrBlank()) {
            // 번호 표기가 제각각이라(010-…, +8210-…, 하이픈 유무) 끝자리로 맞춘다
            val tail = with.filter { it.isDigit() }.takeLast(8)
            if (tail.isNotEmpty()) {
                if (where.isNotEmpty()) where.append(" AND ")
                where.append("REPLACE(REPLACE(${Telephony.Sms.ADDRESS},'-',''),' ','') LIKE ?")
                args.add("%$tail")
            }
        }

        return try {
            ctx.contentResolver.query(
                Uri.parse("content://sms"),
                cols,
                if (where.isEmpty()) null else where.toString(),
                if (args.isEmpty()) null else args.toTypedArray(),
                "${Telephony.Sms.DATE} DESC LIMIT $limit"
            )?.use { c ->
                val iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndex(Telephony.Sms.BODY)
                val iDate = c.getColumnIndex(Telephony.Sms.DATE)
                val iType = c.getColumnIndex(Telephony.Sms.TYPE)
                val iRead = c.getColumnIndex(Telephony.Sms.READ)
                while (c.moveToNext()) {
                    out.put(JSONObject().apply {
                        put("address", c.getString(iAddr) ?: "")
                        put("body", c.getString(iBody) ?: "")
                        put("date", c.getLong(iDate))
                        // 1=받음 2=보냄 — 대화를 재구성하려면 이게 필요하다
                        put("incoming", c.getInt(iType) == Telephony.Sms.MESSAGE_TYPE_INBOX)
                        put("read", c.getInt(iRead) == 1)
                    })
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "SMS 조회 실패", e)
            out
        }
    }
}
