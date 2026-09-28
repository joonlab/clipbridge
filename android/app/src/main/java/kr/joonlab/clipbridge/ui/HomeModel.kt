package kr.joonlab.clipbridge.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.joonlab.clipbridge.ClipServerService
import kr.joonlab.clipbridge.NotifyMirror
import kr.joonlab.clipbridge.OpenLink
import kr.joonlab.clipbridge.SmsReader
import kr.joonlab.clipbridge.SmsSender
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 홈 화면 상태. 기능 로직은 그대로 두고(서비스·권한 확인 함수) 화면에 필요한 값만 읽어 모은다.
 * 액티비티가 자세 변화에도 다시 만들어지지 않으므로(configChanges) 액티비티 필드로 들고 있으면 된다.
 */
class HomeModel(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    companion object {
        // 맥(clipd) 주소 — 빌드 때 넣은 값(Config)
        val MAC_BASE: String get() = kr.joonlab.clipbridge.Config.MAC_BASE
    }

    // ── 맥 연결 ──────────────────────────────────────────────────────────
    sealed interface Mac {
        data object Checking : Mac
        data class Online(val host: String, val ms: Long, val at: Long) : Mac
        data class Offline(val err: String) : Mac
    }

    var mac by mutableStateOf<Mac>(Mac.Checking); private set
    var macHost by mutableStateOf(prefs.getString("macHost", null)); private set
    var macLastOk by mutableLongStateOf(prefs.getLong("macLastOk", 0L)); private set
    var checking by mutableStateOf(false); private set

    /** 맥 /watch — 맥→폰 자동 보내기가 켜져 있는지, 맥이 이 폰에 닿는지(읽기만). null = 모름 */
    data class Watch(val on: Boolean, val reachable: Boolean)
    var watch by mutableStateOf<Watch?>(null); private set

    suspend fun checkMac() {
        if (checking) return
        checking = true
        if (mac !is Mac.Online) mac = Mac.Checking
        val r = withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            try {
                val c = (URL("$MAC_BASE/health").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 5000
                }
                val code = c.responseCode
                val body = if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else ""
                c.disconnect()
                if (code == 200) {
                    val host = try { JSONObject(body).optString("host") } catch (_: Exception) { "" }
                    Mac.Online(host.removeSuffix(".local"), System.currentTimeMillis() - t0, System.currentTimeMillis())
                } else Mac.Offline("HTTP $code")
            } catch (e: Exception) {
                Mac.Offline(e.javaClass.simpleName + (e.message?.let { " — $it" } ?: ""))
            }
        }
        mac = r
        if (r is Mac.Online) {
            if (r.host.isNotEmpty()) macHost = r.host
            macLastOk = r.at
            prefs.edit().putString("macHost", macHost).putLong("macLastOk", r.at).apply()
            watch = withContext(Dispatchers.IO) {
                try {
                    val c = (URL("$MAC_BASE/watch").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000; readTimeout = 3000
                        setRequestProperty(kr.joonlab.clipbridge.Config.TOKEN_HEADER, kr.joonlab.clipbridge.Config.TOKEN)
                    }
                    val j = if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().use { it.readText() }) else null
                    c.disconnect()
                    j?.let { Watch(it.optBoolean("watch", true), it.optBoolean("phone_reachable", true)) }
                } catch (_: Exception) { null }
            }
        } else watch = null
        checking = false
    }

    // ── 수신 서버(맥 → 폰) ───────────────────────────────────────────────
    var serverOn by mutableStateOf(ClipServerService.running); private set
    var boundTo by mutableStateOf(ClipServerService.boundTo); private set
    var last by mutableStateOf(ClipServerService.lastReceived); private set
    /** 화면이 열린 동안 마지막 수신이 바뀐 시각(서비스는 시각을 안 남긴다). */
    var lastAt by mutableLongStateOf(0L); private set
    /** 켜기/끄기 요청 후 실제 상태가 따라올 때까지의 목표값 */
    var serverTarget by mutableStateOf<Boolean?>(null); private set
    private var targetSince = 0L

    fun setServer(on: Boolean, ctx: Context) {
        serverTarget = on; targetSince = System.currentTimeMillis()
        if (on) ClipServerService.start(ctx) else ClipServerService.stop(ctx)
    }

    /** 1초마다(화면이 보일 때만) — 서비스의 휘발 상태를 옮겨 담는다. */
    fun pollServer() {
        serverOn = ClipServerService.running
        boundTo = ClipServerService.boundTo
        val l = ClipServerService.lastReceived
        if (l != last) { last = l; lastAt = System.currentTimeMillis() }
        val t = serverTarget
        if (t != null && (t == serverOn || System.currentTimeMillis() - targetSince > 8000)) serverTarget = null
    }

    // ── 권한 ─────────────────────────────────────────────────────────────
    data class Perms(
        val notify: Boolean = true, val battery: Boolean = true, val overlay: Boolean = true,
        val listener: Boolean = true, val smsSend: Boolean = true, val smsRead: Boolean = true,
    ) {
        /** 앱 핵심(필수·권장)에서 빠진 개수 */
        val missingCore get() = listOf(notify, battery).count { !it }
        val missingAll get() = listOf(notify, battery, overlay, listener, smsSend, smsRead).count { !it }
    }

    var perms by mutableStateOf(Perms()); private set

    fun refreshPerms() {
        val nm = app.getSystemService(NotificationManager::class.java)
        val pm = app.getSystemService(PowerManager::class.java)
        fun has(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        perms = Perms(
            notify = (Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)) && nm.areNotificationsEnabled(),
            battery = pm.isIgnoringBatteryOptimizations(app.packageName),
            overlay = OpenLink.canLaunchDirectly(app),
            listener = nm.isNotificationListenerAccessGranted(ComponentName(app, NotifyMirror::class.java)),
            smsSend = SmsSender.canSend(app),
            smsRead = SmsReader.canRead(app),
        )
    }

    // ── 타일 추가 여부(시스템이 알려주지 않아 요청 결과를 기억한다) ──────────
    var tileAdded by mutableStateOf(prefs.getBoolean("tileAdded", false)); private set
    fun markTileAdded() { tileAdded = true; prefs.edit().putBoolean("tileAdded", true).apply() }

    fun refreshAll() { pollServer(); refreshPerms() }
}
