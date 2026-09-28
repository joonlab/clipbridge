package kr.joonlab.clipbridge

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import kr.joonlab.clipbridge.ui.JlTheme
import kr.joonlab.clipbridge.ui.SendSheet
import kr.joonlab.clipbridge.ui.SendState
import kr.joonlab.clipbridge.ui.Words
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 공유 시트로 받은 텍스트를 맥(clipd)으로 보낸다.
 *
 * 클립보드를 직접 읽지 않는 이유: Android 10+ 는 포커스 없는 앱의 클립보드 접근을 막는다.
 * 공유 인텐트로 넘어온 데이터는 그 제한을 받지 않으므로, 정책과 싸우지 않고 우회한다.
 *
 * 화면: 투명 창 아래쪽에 작은 카드 하나 — 보내는 중 → 보냄(잠깐 보이고 닫힘) / 실패(이유 + 다시 시도).
 * 전송 로직(엔드포인트·타임아웃·파일 스트리밍)은 이전과 같다.
 */
class ShareActivity : ComponentActivity() {

    companion object {
        /** 타일이 누를 때 쓰는 내부 액션 — 클립보드에서 직접 읽어 보낸다. */
        const val ACTION_SEND_CLIPBOARD = "kr.joonlab.clipbridge.SEND_CLIPBOARD"

        private const val TIMEOUT_MS = 8000
    }

    private var handled = false
    private val state = mutableStateOf<SendState>(SendState.Idle)
    @Volatile private var gone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 여기서 바로 읽지 않는다 — onCreate 시점에는 윈도우가 아직 포커스를 못 받아서
        // ClipboardService 가 "not in focus" 로 거부한다(2026-09-20 실측).
        setContent {
            JlTheme { SendSheet(state.value, onClose = { finish() }) }
        }
    }

    override fun onDestroy() { gone = true; super.onDestroy() }

    /** 윈도우가 실제로 포커스를 얻은 뒤 처리한다. 이때라야 클립보드를 읽을 수 있다. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true

        val uris = extractUris(intent)
        if (uris.isNotEmpty()) {
            sendFiles(uris)
            return
        }

        when (val t = extractText(intent)) {
            is Clip.Denied -> state.value = SendState.Failed("클립보드를 읽지 못했어요",
                "안드로이드가 이번 읽기를 막았어요. 글자를 선택해 「맥으로 보내기」로 보내 보세요.", null, null)
            is Clip.Empty -> state.value = SendState.Failed(
                if (intent.action == ACTION_SEND_CLIPBOARD) "클립보드가 비어 있어요" else "보낼 글이 없어요",
                if (intent.action == ACTION_SEND_CLIPBOARD) "먼저 보낼 글을 복사한 뒤 타일을 눌러 주세요." else "공유한 앱이 글을 넘겨주지 않았어요.",
                null, null, tone = SendState.Tone.Warn)
            is Clip.Text -> sendText(t.s)
        }
    }

    // ── 텍스트 ──────────────────────────────────────────────────────────

    private fun sendText(text: String) {
        state.value = SendState.Sending("맥으로 보내는 중", text.take(80).replace('\n', ' '), null)
        thread {
            val err = send(text)
            runOnUiThread {
                if (err == null) done("맥으로 보냈어요", "${text.length}자 · " + text.replace('\n', ' ').take(40))
                else {
                    val (title, hint) = Words.netCause(err)
                    fail(SendState.Failed(title, hint, err, retry = { sendText(text) }))
                }
            }
        }
    }

    // ── 파일 ────────────────────────────────────────────────────────────

    /** 단일·다중 공유 양쪽에서 파일 URI 를 모은다. */
    @Suppress("DEPRECATION")
    private fun extractUris(intent: Intent): List<android.net.Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri)
        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        else -> emptyList()
    }

    private fun sendFiles(uris: List<android.net.Uri>) {
        val total = uris.size
        state.value = SendState.Sending(if (total == 1) "파일을 보내는 중" else "파일 ${total}개를 보내는 중", "", 0 to total)
        thread {
            var ok = 0
            var bytes = 0L
            val failed = mutableListOf<Pair<android.net.Uri, String>>()
            val names = mutableListOf<String>()
            uris.forEachIndexed { i, uri ->
                val meta = FileSender.meta(contentResolver, uri)
                runOnUiThread {
                    state.value = SendState.Sending(
                        if (total == 1) "파일을 보내는 중" else "파일 ${total}개를 보내는 중",
                        "${meta.name} · ${Words.size(meta.size)}", i to total)
                }
                val err = FileSender.send(contentResolver, uri)
                if (err == null) { ok++; if (meta.size > 0) bytes += meta.size }
                else { failed.add(uri to err); names.add(meta.name) }
            }
            runOnUiThread {
                when {
                    failed.isEmpty() -> done("맥으로 보냈어요",
                        (if (ok == 1) "파일 1개" else "파일 ${ok}개") + (if (bytes > 0) " · ${Words.size(bytes)}" else ""))
                    else -> {
                        val (title, hint) = Words.netCause(failed.first().second)
                        val head = if (ok == 0) title else "${ok}개 보냄, ${failed.size}개 실패"
                        fail(SendState.Failed(head, hint,
                            failed.mapIndexed { i, f -> "${names[i]} — ${f.second}" }.joinToString("\n"),
                            retry = { sendFiles(failed.map { it.first }) },
                            items = names))
                    }
                }
            }
        }
    }

    // ── 결과 ────────────────────────────────────────────────────────────

    /** 성공은 잠깐 보여주고 스스로 닫는다. 이미 다른 앱으로 넘어가 창이 없으면 토스트로 알린다. */
    private fun done(title: String, detail: String) {
        if (gone || isFinishing) { Toast.makeText(applicationContext, "$title · $detail", Toast.LENGTH_SHORT).show(); return }
        state.value = SendState.Done(title, detail)
        window.decorView.postDelayed({ if (!isFinishing) finish() }, 900)
    }

    private fun fail(f: SendState.Failed) {
        if (gone || isFinishing) { Toast.makeText(applicationContext, f.title, Toast.LENGTH_LONG).show(); return }
        state.value = f
    }

    // ── 텍스트 추출 ─────────────────────────────────────────────────────

    private sealed interface Clip {
        data class Text(val s: String) : Clip
        data object Empty : Clip
        data object Denied : Clip
    }

    /** 공유 시트·텍스트 선택 메뉴·타일 세 경로를 모두 받는다. */
    private fun extractText(intent: Intent): Clip {
        val s = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ACTION_SEND_CLIPBOARD -> return readClipboard()
            else -> null
        }
        return if (s.isNullOrEmpty()) Clip.Empty else Clip.Text(s)
    }

    /**
     * 이 액티비티는 포커스를 가지므로 클립보드를 읽을 수 있다.
     * (같은 호출을 백그라운드 서비스에서 하면 ClipboardService 가 거부한다)
     * 비어 있음과 읽기 거부를 나눠 돌려준다 — 화면 문구가 달라야 해서.
     */
    private fun readClipboard(): Clip = try {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val clip = cm.primaryClip
        val s = if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(this).toString()
        if (s.isNullOrEmpty()) Clip.Empty else Clip.Text(s)
    } catch (e: SecurityException) {
        Clip.Denied
    }

    /** 성공하면 null, 실패하면 원문(HTTP 코드 또는 예외 이름). 요청 자체는 이전과 같다. */
    private fun send(text: String): String? = try {
        val conn = (URL("${Config.MAC_BASE}/clip").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            setRequestProperty(Config.TOKEN_HEADER, Config.TOKEN)
        }
        conn.outputStream.use { os: OutputStream -> os.write(text.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        conn.disconnect()

        if (code == 200) null else "HTTP $code"
    } catch (e: Exception) {
        // 맥이 꺼져 있거나 clipd 가 안 떠 있으면 여기로 온다.
        e.javaClass.simpleName
    }
}
