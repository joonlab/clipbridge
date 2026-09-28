package kr.joonlab.clipbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.InputStream

/**
 * 맥이 보낸 파일을 폰의 「다운로드」에 저장한다.
 *
 * MediaStore 로 쓰는 이유: Android 11+ 의 scoped storage 에서 공용 Download 폴더에
 * 직접 쓰려면 권한이 필요하지만, MediaStore.Downloads 로 넣으면 **권한 없이** 된다.
 * 다른 앱(파일·갤러리)에서도 바로 보인다.
 */
object PhoneInbox {

    private const val TAG = "ClipBridge"
    private const val CHANNEL = "clipbridge-inbox"

    /** (성공여부, 저장된 이름 또는 오류) */
    fun save(ctx: Context, name: String, mime: String, input: InputStream, expected: Long): Pair<Boolean, String> {
        val safe = name.substringAfterLast('/').ifEmpty { "mac-file" }
        // ⚠️ MIME 이 이름의 확장자와 어긋나면 MediaStore 가 **파일명 뒤에 확장자를 덧붙인다**
        //    (app-debug.apk + application/zip → "app-debug.apk.zip" → 설치 불가).
        //    맥의 `file --mime-type` 은 컨테이너를 보므로 APK·docx 등을 zip 이라 한다.
        //    그래서 확장자에서 유추한 것을 우선한다.
        val ext = safe.substringAfterLast('.', "").lowercase()
        val byExt = when (ext) {
            "apk" -> "application/vnd.android.package-archive"
            "hwp" -> "application/x-hwp"
            "hwpx" -> "application/hwp+zip"
            else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }
        val useMime = byExt ?: mime.ifEmpty { "application/octet-stream" }
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.MIME_TYPE, useMime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false to "MediaStore insert 실패"

            var written = 0L
            resolver.openOutputStream(uri)?.use { out ->
                val buf = ByteArray(65536)
                var remaining = expected
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    remaining -= n
                }
                out.flush()
            } ?: return false to "openOutputStream 실패"

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            if (written != expected) {
                Log.w(TAG, "불완전 수신 $safe: $written/$expected")
                return false to "불완전 ($written/$expected)"
            }

            Log.i(TAG, "파일 저장: $safe (${written / 1024}KB)")
            notifySaved(ctx, safe, written, uri, useMime)
            true to safe
        } catch (e: Exception) {
            Log.e(TAG, "파일 저장 실패", e)
            false to "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** 조용히 쌓이면 온 줄도 모른다 — 탭하면 바로 열리는 알림을 띄운다. */
    private fun notifySaved(ctx: Context, name: String, bytes: Long, uri: Uri, mime: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "맥에서 받은 파일", NotificationManager.IMPORTANCE_DEFAULT))

            val open = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime.ifEmpty { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pi = PendingIntent.getActivity(ctx, name.hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            nm.notify(name.hashCode(), Notification.Builder(ctx, CHANNEL)
                .setContentTitle(name)
                .setContentText("맥에서 받음 · ${kr.joonlab.clipbridge.ui.Words.size(bytes)} · 누르면 열려요")
                .setSmallIcon(R.drawable.ic_stat_file)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) {
            Log.w(TAG, "알림 실패: ${e.message}")
        }
    }
}
