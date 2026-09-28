package kr.joonlab.clipbridge.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import kr.joonlab.clipbridge.R

/**
 * 색 역할 — 내가 만든 다른 폴드 앱들과 같은 팔레트 구조를 파일로 복사해 쓴다(모듈 공유 대신).
 * 앱마다 바뀌는 것은 accent 하나. ClipBridge = 민트→틸.
 */
interface Pal {
    val bg: Color
    val panel: Color
    val panel2: Color
    val raised: Color
    val border: Color
    val text: Color
    val dim: Color
    val accent: Color
    val accentTint: Color
    val onAccent: Color
}

/** 테마는 셋을 돈다: system(폰 설정 추종) → light → dark. SharedPreferences("ui").theme */
object ThemeMode {
    var mode by mutableStateOf("system")
    var sysDark by mutableStateOf(false)
    val dark get() = mode == "dark" || (mode == "system" && sysDark)

    fun cycle(ctx: Context, prefsName: String = "ui") {
        mode = when (mode) { "system" -> "light"; "light" -> "dark"; else -> "system" }
        ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString("theme", mode).apply()
    }
}

/** ClipBridge 팔레트 — 디자인 가이드 값 그대로(대비 계산 AA 통과). getter 라 테마가 바뀌면 읽은 자리만 다시 그려진다. */
object P : Pal {
    private fun t(l: Long, d: Long) = Color(if (ThemeMode.dark) d else l)
    override val bg get() = t(0xFFF8FBFA, 0xFF0A0F0E)
    override val panel get() = t(0xFFEFF5F3, 0xFF101816)
    override val panel2 get() = t(0xFFE3ECE9, 0xFF17211E)
    override val raised get() = t(0xFFFFFFFF, 0xFF1B2623)
    override val border get() = t(0xFFCFDDD8, 0xFF263430)
    override val text get() = t(0xFF0F1F1B, 0xFFE4EFEB)
    override val dim get() = t(0xFF4F605B, 0xFF93A5A0)
    override val accent get() = t(0xFF0F766E, 0xFF5EEAD4)
    override val accentTint get() = accent.copy(alpha = if (ThemeMode.dark) .14f else .10f)
    override val onAccent get() = t(0xFFFFFFFF, 0xFF042F2A)

    // 의미색. 틸(accent)과 초록(ok)이 가까우므로 「연결됨」은 ok, 「켜짐·주 버튼」은 accent 로 나눈다.
    val ok get() = t(0xFF1A7F3C, 0xFF5ED18A)
    val warn get() = t(0xFF8A6100, 0xFFD9AE45)
    val warnText get() = t(0xFF6B4C00, 0xFFE6C97E)
    val danger get() = t(0xFFC4322C, 0xFFEF6157)
}

val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

/**
 * 뿌리에서 한 번: 저장된 테마 복원(첫 프레임 전에 읽어 깜빡임 없음) + 상태·내비바 글자색 + 기본 글꼴·글자색.
 */
@Composable
fun JlTheme(prefsName: String = "ui", content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    ThemeMode.sysDark = isSystemInDarkTheme()
    remember {
        ThemeMode.mode = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString("theme", "system") ?: "system"
        true
    }
    val view = LocalView.current
    LaunchedEffect(ThemeMode.dark) {
        (ctx as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !ThemeMode.dark
                isAppearanceLightNavigationBars = !ThemeMode.dark
            }
        }
    }
    CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = Pretendard, color = P.text)) {
        content()
    }
}

/** 숫자 폭을 고정(시각·크기·지연이 흔들리지 않게). */
val tnum: TextStyle
    @Composable get() = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
