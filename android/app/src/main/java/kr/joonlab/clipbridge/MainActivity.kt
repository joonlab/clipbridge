package kr.joonlab.clipbridge

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kr.joonlab.clipbridge.ui.HomeActions
import kr.joonlab.clipbridge.ui.HomeModel
import kr.joonlab.clipbridge.ui.HomeScreen
import kr.joonlab.clipbridge.ui.JlTheme
import kr.joonlab.clipbridge.ui.SnackState

/**
 * 앱 홈 — 맥 연결 상태, 주고받는 방법, 권한.
 * 실제 전송은 여전히 타일·공유 시트·수신 서버가 한다. 이 화면은 상태를 보여주고 설정으로 안내할 뿐이다.
 */
class MainActivity : ComponentActivity() {

    private lateinit var model: HomeModel
    private val snack = SnackState()
    private val resumed = mutableStateOf(false)
    private val prefs by lazy { getSharedPreferences("ui", Context.MODE_PRIVATE) }

    /** 런타임 권한 요청 결과 — 거절되면 이유와 다음 행동을 한 줄로. */
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        model.refreshPerms()
        if (res.isNotEmpty() && res.values.none { it }) snack.show("허용하지 않았어요. 필요하면 다시 눌러 주세요.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        model = HomeModel(this)
        ClipServerService.start(this)   // 앱을 열면 항상 켠다(기존 동작 그대로)

        setContent {
            JlTheme {
                HomeScreen(model, actions, snack, resumed.value)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed.value = true      // 설정에서 돌아오면 권한·상태를 다시 읽는다(HomeScreen 폴링 재시작)
    }

    override fun onPause() {
        resumed.value = false
        super.onPause()
    }

    // ── 권한 요청 도우미 ───────────────────────────────────────────────

    /**
     * 요청 → 이미 두 번 거절돼 시스템이 더 묻지 않는 상태면 앱 정보 화면으로 보낸다.
     * (거절 이력을 기억하지 않으면 버튼을 눌러도 아무 일도 안 일어나는 것처럼 보인다)
     */
    private fun ask(vararg perms: String) {
        val need = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) { openAppDetails(); return }
        val blocked = need.all { prefs.getBoolean("asked:$it", false) && !shouldShowRequestPermissionRationale(it) }
        if (blocked) {
            snack.show("설정 › 권한에서 직접 허용해 주세요")
            openAppDetails(); return
        }
        prefs.edit().apply { need.forEach { putBoolean("asked:$it", true) } }.apply()
        permLauncher.launch(need.toTypedArray())
    }

    private fun openAppDetails() = go(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun go(i: Intent, fallback: Intent? = null) {
        try { startActivity(i) } catch (e: Exception) {
            if (fallback != null) try { startActivity(fallback) } catch (_: Exception) { snack.show("설정 화면을 열지 못했어요") }
            else snack.show("설정 화면을 열지 못했어요")
        }
    }

    private val actions = object : HomeActions {
        override fun toggleServer(on: Boolean) = model.setServer(on, this@MainActivity)
        override fun checkMac() { lifecycleScope.launch { model.checkMac() } }

        override fun askNotify() {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                ask(Manifest.permission.POST_NOTIFICATIONS)
            else go(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }

        override fun openBattery() {
            // 제외해 두지 않으면 화면을 오래 끈 뒤 수신 서버가 조용히 잠든다
            if (model.perms.battery) go(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            else go(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        override fun openOverlay() =
            go(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))

        override fun openListener() {
            // 이 권한만은 설정 화면에서 «직접 허용»이 필요하다. 가능하면 이 앱 항목으로 바로 보낸다.
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(this@MainActivity, NotifyMirror::class.java).flattenToString())
            go(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        override fun askSmsSend() = ask(Manifest.permission.SEND_SMS)
        override fun askSmsRead() = ask(Manifest.permission.READ_SMS)

        override val canAddTile get() = Build.VERSION.SDK_INT >= 33

        override fun addTile() {
            if (Build.VERSION.SDK_INT < 33) return
            val sbm = getSystemService(StatusBarManager::class.java)
            try {
                sbm.requestAddTileService(ComponentName(this@MainActivity, ClipTileService::class.java), "맥으로 복사",
                    Icon.createWithResource(this@MainActivity, android.R.drawable.ic_menu_save), mainExecutor) { r ->
                    when (r) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                            model.markTileAdded(); snack.show("빠른 설정에 「맥으로 복사」가 있어요")
                        }
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> snack.show("추가하지 않았어요")
                        else -> snack.show("지금은 추가할 수 없어요. 빠른 설정 편집에서 꺼내 주세요.")
                    }
                }
            } catch (e: Exception) {
                snack.show("지금은 추가할 수 없어요. 빠른 설정 편집에서 꺼내 주세요.")
            }
        }
    }
}
