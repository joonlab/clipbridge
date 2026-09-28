package kr.joonlab.clipbridge

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 폰 알림을 맥으로 미러링한다.
 *
 * 이건 클립보드와 달리 **사용자가 설정에서 직접 허용**해야 한다(알림 접근 권한).
 * adb 로도 줄 수 있지만(`cmd notification allow_listener`), 정식 경로는 설정 화면이다.
 *
 * 1차 필터는 여기서 한다 — 폰에는 상주·진행률 알림이 끊임없이 흐르고, 그걸 다 보내면
 * 맥이 알림 폭탄을 맞는다. 앱별 허용/차단 같은 2차 필터는 맥(clipd)에서 한다.
 */
class NotifyMirror : NotificationListenerService() {

    companion object {
        private const val TAG = "ClipBridge"

        /** 최근 보낸 것 — 같은 알림이 갱신될 때마다 다시 보내지 않게 */
        private val recent = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 200
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName == packageName) return              // 자기 알림
            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return   // 상주(음악·다운로드)
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return   // 묶음 머리글

            val e = n.extras
            val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return

            // 같은 내용이 30초 안에 또 오면 버린다(진행률·재게시)
            val sig = "${sbn.packageName}|$title|$text"
            val now = System.currentTimeMillis()
            synchronized(recent) {
                val last = recent[sig]
                if (last != null && now - last < 30_000) return
                recent[sig] = now
            }

            val appName = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (ex: Exception) { sbn.packageName }

            val json = JSONObject().apply {
                put("pkg", sbn.packageName)
                put("app", appName)
                put("title", title)
                put("text", text)
                put("category", n.category ?: "")
                put("when", n.`when`)
                put("key", sbn.key)
            }
            post(json.toString())
        } catch (ex: Exception) {
            Log.w(TAG, "알림 처리 실패: ${ex.message}")
        }
    }

    private fun post(body: String) {
        thread {
            try {
                val c = (URL("${Config.MAC_BASE}/notify").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty(Config.TOKEN_HEADER, Config.TOKEN)
                }
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                c.responseCode
                c.disconnect()
            } catch (e: Exception) {
                // 맥이 꺼져 있는 게 정상 상태일 수 있다 — 조용히 넘어간다
                Log.d(TAG, "알림 전달 실패(무시): ${e.javaClass.simpleName}")
            }
        }
    }
}
