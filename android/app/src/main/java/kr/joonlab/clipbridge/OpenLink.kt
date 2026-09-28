package kr.joonlab.clipbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 맥에서 보낸 URL 을 폰에서 연다.
 *
 * 함정: Android 10+ 는 **백그라운드 앱의 액티비티 실행(BAL)** 을 막는다. 클립보드 쓰기와 달리
 * 이건 실제로 막히고, 막혀도 **예외가 안 난다** — 조용히 아무 일도 일어나지 않는다
 * (logcat 에 "Background activity launch blocked" 만 남는다). 그래서 성공을 가정하지 않는다.
 *
 * 두 경로를 겹쳐 둔다:
 *   1) 「다른 앱 위에 표시」(SYSTEM_ALERT_WINDOW) 가 허용돼 있으면 BAL 이 풀려 바로 열린다
 *   2) 아니면 고우선 알림을 띄운다 — 탭 한 번으로 열린다 (확실히 동작)
 */
object OpenLink {

    private const val TAG = "ClipBridge"
    private const val CHANNEL = "clipbridge-open"

    fun canLaunchDirectly(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(ctx)

    /** (직접 열었는지, 메시지) */
    fun open(ctx: Context, url: String): Pair<Boolean, String> {
        val uri = try {
            Uri.parse(url.trim())
        } catch (e: Exception) {
            return false to "URL 파싱 실패"
        }
        if (uri.scheme == null) return false to "scheme 없음 (http:// 를 붙여 보낼 것)"

        val view = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (canLaunchDirectly(ctx)) {
            return try {
                ctx.startActivity(view)
                Log.i(TAG, "링크 직접 열기: $url")
                true to "열었다"
            } catch (e: Exception) {
                Log.w(TAG, "직접 열기 실패 → 알림으로: ${e.message}")
                notifyLink(ctx, uri, view)
                false to "알림으로 보냄 (${e.javaClass.simpleName})"
            }
        }

        notifyLink(ctx, uri, view)
        return false to "알림으로 보냄 (「다른 앱 위에 표시」를 켜면 바로 열린다)"
    }

    private fun notifyLink(ctx: Context, uri: Uri, view: Intent) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "맥에서 보낸 링크", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(ctx, uri.hashCode(), view,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(uri.hashCode(), Notification.Builder(ctx, CHANNEL)
                .setContentTitle(uri.host?.removePrefix("www.")?.let { "맥에서 보낸 링크 · $it" } ?: "맥에서 보낸 링크")
                .setContentText(uri.toString())
                .setStyle(Notification.BigTextStyle().bigText(uri.toString()))
                .setSmallIcon(R.drawable.ic_stat_link)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) {
            Log.w(TAG, "링크 알림 실패: ${e.message}")
        }
    }
}
