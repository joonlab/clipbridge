package kr.joonlab.clipbridge

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 공유받은 파일을 맥(clipd)으로 올린다.
 *
 * 텍스트와 달리 파일 공유에는 클립보드 같은 OS 제한이 없다 — 공유 인텐트로 넘어온
 * URI 를 ContentResolver 로 읽으면 그만이다. 대용량이 메모리에 통째로 올라가지 않게
 * setFixedLengthStreamingMode 로 흘려보낸다.
 */
object FileSender {

    data class Meta(val name: String, val size: Long)

    /**
     * 표시용 파일명과 크기를 얻는다.
     *
     * DISPLAY_NAME 이 없을 때 URI 마지막 조각을 쓰면 안 된다 — 앱에 따라 그 자리가
     * base64 데이터 꼬리인 경우가 있어 "j0lHxAl4AAAAASUVORK5CYII=.png" 같은 이름이
     * 나온다(실측). 쓸 만한 이름이 아니면 타임스탬프로 짓는다.
     */
    fun meta(cr: ContentResolver, uri: Uri): Meta {
        var name: String? = null
        var size = -1L
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                     null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) { }

        val candidate = name ?: uri.lastPathSegment
        return Meta(if (looksUsable(candidate)) candidate!! else fallbackName(cr, uri), size)
    }

    /** base64 꼬리·과도하게 긴 이름·빈 이름을 걸러낸다. */
    private fun looksUsable(n: String?): Boolean {
        if (n.isNullOrBlank()) return false
        val stem = n.substringBeforeLast('.')
        if (stem.isEmpty() || stem.length > 60) return false
        // base64 는 영숫자+/+= 만으로 길게 이어진다. 사람이 지은 이름은 보통 이렇지 않다.
        if (stem.length >= 16 && stem.matches(Regex("[A-Za-z0-9+/=]+"))) return false
        return true
    }

    /** 폰-20260920-150706.png 형태로 짓는다. 확장자는 MIME 에서 끌어온다. */
    private fun fallbackName(cr: ContentResolver, uri: Uri): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.KOREA)
            .format(java.util.Date())
        val mime = cr.getType(uri)
        val ext = mime?.let {
            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
        } ?: "bin"
        return "폰-$stamp.$ext"
    }

    /** 성공하면 null, 실패하면 사람이 읽을 에러 문구를 돌려준다. */
    fun send(cr: ContentResolver, uri: Uri): String? = try {
        val m = meta(cr, uri)
        val conn = (URL("${Config.MAC_BASE}/file").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 300_000          // 대용량도 버티게 넉넉히
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-Filename", URLEncoder.encode(m.name, "UTF-8"))
            setRequestProperty(Config.TOKEN_HEADER, Config.TOKEN)
            if (m.size >= 0) setFixedLengthStreamingMode(m.size)
            else setChunkedStreamingMode(0)
        }

        cr.openInputStream(uri).use { input ->
            if (input == null) throw IllegalStateException("스트림 열기 실패")
            conn.outputStream.use { out -> input.copyTo(out, 64 * 1024) }
        }

        val code = conn.responseCode
        conn.disconnect()
        if (code == 200) null else "HTTP $code"
    } catch (e: Exception) {
        "${e.javaClass.simpleName}"
    }
}
