package kr.joonlab.clipbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 맥(clipd)이 보내는 텍스트를 받아 폰 클립보드에 넣는 상주 서버.
 *
 * 왜 서비스인가: adb 는 절전에 자주 끊긴다(연동랩 README). 상시 동작은 앱으로 둔다.
 * 왜 포그라운드인가: 백그라운드 서비스는 Android 가 금방 죽인다.
 *
 * 왜 이게 되는가: Android 10+ 의 "포커스 있는 앱만" 제약은 읽기(OP_READ_CLIPBOARD)에만
 * 걸린다. 쓰기는 포커스를 요구하지 않는다 — 2026-09-21 실측(콜드 백그라운드에서 성공).
 */
class ClipServerService : Service() {

    companion object {
        const val PORT = 8788
        private const val TAG = "ClipBridge"
        private const val CHANNEL = "clipbridge-server"
        private const val NOTI_ID = 1001
        private const val MAX_BYTES = 256 * 1024
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024   // 2GB

        /** 맥과 나눠 갖는 토큰(빌드 때 주입). tailnet 안이라도 같은 폰의 다른 앱은 막는다. */
        val TOKEN: String get() = Config.TOKEN

        @Volatile var running = false
            private set
        @Volatile var boundTo: String = "-"
            private set
        @Volatile var lastReceived: String = "아직 없음"
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, ClipServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, ClipServerService::class.java))
    }

    private var server: ServerSocket? = null
    private var lock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        // 예전 버전이 쓰던 알림 채널을 치운다(없으면 아무 일 없음)
        try { getSystemService(NotificationManager::class.java).deleteNotificationChannel("clipbridge-parking") } catch (_: Exception) {}
        // Wi-Fi 를 절전으로 재우지 않게 — 안 그러면 화면 끄고 몇 분 뒤 안 닿는다
        try {
            val wm = getSystemService(WifiManager::class.java)
            lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "clipbridge")
                .apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "wifi lock 실패: ${e.message}")
        }
        thread(name = "clip-server") { serve() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        try { lock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── 서버 ────────────────────────────────────────────────────────────────

    /** Tailscale(100.64/10) 주소에만 바인딩한다. 없으면 전체 — 토큰이 막는다. */
    private fun tailscaleAddress(): InetAddress? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { a ->
                val h = a.hostAddress ?: ""
                h.count { it == '.' } == 3 && h.startsWith("100.") &&
                    (h.split(".")[1].toIntOrNull() ?: 0) in 64..127
            }
    } catch (e: Exception) { null }

    private fun serve() {
        while (true) {
            val addr = tailscaleAddress()
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(if (addr != null) InetSocketAddress(addr, PORT) else InetSocketAddress(PORT))
                server = s
                running = true
                boundTo = "${addr?.hostAddress ?: "0.0.0.0"}:$PORT"
                Log.i(TAG, "수신 서버 시작 — $boundTo")
                notifyState()
                while (true) handle(s.accept())
            } catch (e: Exception) {
                running = false
                Log.w(TAG, "서버 중단: ${e.message}")
                if (server?.isClosed == true && !isRunningWanted) return
                // Tailscale 이 아직 안 올라왔거나 망이 바뀐 경우 — 잠깐 쉬고 다시
                try { Thread.sleep(5000) } catch (_: InterruptedException) { return }
            }
        }
    }

    private val isRunningWanted get() = true

    private fun handle(sock: Socket) {
        thread {
            sock.use { s ->
                try {
                    s.soTimeout = 10000
                    val input = BufferedInputStream(s.getInputStream())
                    val head = readHeaders(input) ?: return@use
                    val (line, headers) = head
                    val parts = line.split(" ")
                    val method = parts.getOrNull(0) ?: ""
                    val path = parts.getOrNull(1) ?: ""

                    val out = s.getOutputStream()

                    if (method == "GET" && path == "/health") {
                        reply(out, 200,
                            """{"ok":true,"device":"${Build.MODEL}","bound":"$boundTo",""" +
                            """"canOpenDirectly":${OpenLink.canLaunchDirectly(applicationContext)},""" +
                            """"canSendSms":${SmsSender.canSend(applicationContext)},""" +
                            """"canReadSms":${SmsReader.canRead(applicationContext)}}""")
                        return@use
                    }
                    val given = headers["x-clipbridge-token"] ?: ""
                    if (TOKEN.isEmpty() || !java.security.MessageDigest.isEqual(
                            given.toByteArray(), TOKEN.toByteArray())) {
                        Log.w(TAG, "토큰 불일치 — ${s.inetAddress?.hostAddress}")
                        reply(out, 403, """{"ok":false,"error":"forbidden"}""")
                        return@use
                    }
                    if (method == "GET" && path.startsWith("/sms")) {
                        val qs = path.substringAfter('?', "")
                        val params = qs.split("&").mapNotNull {
                            val i = it.indexOf('='); if (i <= 0) null else
                                it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
                        }.toMap()
                        val rows = SmsReader.query(
                            applicationContext,
                            params["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 30,
                            params["q"], params["with"])
                        reply(out, 200,
                            """{"ok":true,"canRead":${SmsReader.canRead(applicationContext)},"messages":$rows}""")
                        return@use
                    }
                    if (method != "POST") {
                        reply(out, 404, """{"ok":false,"error":"not found"}""")
                        return@use
                    }

                    val len = headers["content-length"]?.toLongOrNull() ?: 0L

                    when (path) {
                        // 파일은 통째로 메모리에 올리지 않는다 — 소켓에서 바로 흘려 쓴다
                        "/file" -> {
                            if (len <= 0 || len > MAX_FILE_BYTES) {
                                reply(out, 413, """{"ok":false,"error":"bad length"}""")
                                return@use
                            }
                            val raw = headers["x-filename"] ?: "mac-file"
                            val name = try {
                                java.net.URLDecoder.decode(raw, "UTF-8")
                            } catch (e: Exception) { raw }
                            val mime = headers["x-mime"] ?: "application/octet-stream"
                            val (ok, msg) = PhoneInbox.save(
                                applicationContext, name, mime, input, len)
                            if (ok) {
                                lastReceived = "📎 $msg"
                                notifyState()
                            }
                            reply(out, if (ok) 200 else 500,
                                """{"ok":$ok,"saved":"${msg.replace("\"", "")}"}""")
                        }

                        "/open", "/clip" -> {
                            if (len <= 0 || len > MAX_BYTES) {
                                reply(out, 413, """{"ok":false,"error":"bad length"}""")
                                return@use
                            }
                            val buf = ByteArray(len.toInt())
                            var read = 0
                            while (read < len) {
                                val n = input.read(buf, read, len.toInt() - read)
                                if (n < 0) break
                                read += n
                            }
                            val text = String(buf, 0, read, Charsets.UTF_8)

                            if (path == "/open") {
                                val (direct, msg) = OpenLink.open(applicationContext, text)
                                lastReceived = "🔗 ${text.take(34)}"
                                notifyState()
                                reply(out, 200,
                                    """{"ok":true,"opened":$direct,"note":"$msg"}""")
                            } else {
                                val ok = ClipReceiver.setClipboard(applicationContext, text)
                                if (ok) {
                                    lastReceived = text.take(40).replace("\n", "⏎")
                                    notifyState()
                                }
                                reply(out, if (ok) 200 else 500,
                                    """{"ok":$ok,"chars":${text.length}}""")
                            }
                        }

                        "/sms" -> {
                            if (len <= 0 || len > MAX_BYTES) {
                                reply(out, 413, """{"ok":false,"error":"bad length"}""")
                                return@use
                            }
                            val buf = ByteArray(len.toInt())
                            var read = 0
                            while (read < len) {
                                val n = input.read(buf, read, len.toInt() - read)
                                if (n < 0) break
                                read += n
                            }
                            val body = org.json.JSONObject(String(buf, 0, read, Charsets.UTF_8))
                            val (ok, msg) = SmsSender.send(
                                applicationContext, body.optString("to"), body.optString("text"))
                            if (ok) {
                                lastReceived = "✉️ ${body.optString("to")}"
                                notifyState()
                            }
                            reply(out, if (ok) 200 else 500,
                                """{"ok":$ok,"note":"${msg.replace("\"", "")}"}""")
                        }

                        else -> reply(out, 404, """{"ok":false,"error":"not found"}""")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "요청 처리 실패: ${e.message}")
                }
            }
        }
    }

    /** 요청줄 + 헤더만 읽는다(바디는 남겨둔다). 헤더 키는 소문자로 정규화. */
    private fun readHeaders(input: BufferedInputStream): Pair<String, Map<String, String>>? {
        val sb = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 and 0xFFFFFFFF.toInt() == 0x0D0A0D0A) break
            if (sb.length > 8192) return null
        }
        val lines = sb.toString().split("\r\n")
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
        }.toMap()
        return lines[0] to headers
    }

    private fun reply(out: OutputStream, code: Int, json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    // ── 알림 ────────────────────────────────────────────────────────────────

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "맥 클립보드 수신", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) })
        }
        val noti = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, noti)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(if (running) "맥에서 받는 중" else "맥 연결을 기다리는 중")
            .setContentText(if (running) "마지막: $lastReceived" else "Tailscale 이 켜지면 저절로 이어져요")
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun notifyState() {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTI_ID, buildNotification())
        } catch (_: Exception) {}
    }
}
