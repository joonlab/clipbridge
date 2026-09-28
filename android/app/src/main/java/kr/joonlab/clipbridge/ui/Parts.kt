package kr.joonlab.clipbridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joonlab.clipbridge.R

// ── 면 ──────────────────────────────────────────────────────────────────

/** 카드 = panel 면 + 1dp 테두리 + 모서리 14. 강조는 면을 물들인다(그림자 없음). */
fun Modifier.card(tint: Color = Color.Transparent, outline: Color? = null, r: Dp = 14.dp): Modifier {
    val s = RoundedCornerShape(r)
    return this.clip(s).background(P.panel).background(tint).border(1.dp, outline ?: P.border, s)
}

@Composable
fun Divider(color: Color = P.border) = Box(Modifier.fillMaxWidth().height(1.dp).background(color))

// ── 표식·머리줄 ──────────────────────────────────────────────────────────

/**
 * 앱 표식 = 실제 런처 아이콘(적응형 아이콘의 배경+전경 두 겹). 108dp 캔버스 중 가운데 72dp 만 보이는 규칙을 그대로 따라
 * 1.5배로 그린 뒤 둥근 사각으로 자른다 — 홈 화면 아이콘과 같은 그림이 머리줄·카드에도 나온다.
 */
@Composable
fun AppIcon(bg: Int, fg: Int, size: Dp = 24.dp, label: String? = null) {
    val shape = RoundedCornerShape(size * 0.28f)
    // 다크 바탕과 타일(#0B0E12)이 거의 같아 모서리가 사라진다 → 다크에서만 1dp 테두리.
    Box(Modifier.size(size).clip(shape)
            .then(if (ThemeMode.dark) Modifier.border(1.dp, P.border, shape) else Modifier)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
        contentAlignment = Alignment.Center) {
        Image(painterResource(bg), null, Modifier.requiredSize(size * 1.5f))
        Image(painterResource(fg), null, Modifier.requiredSize(size * 1.5f))
    }
}

/** ClipBridge 표식(런처 아이콘). */
@Composable
fun Mark(size: Dp = 24.dp) = AppIcon(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground, size)

@Composable
fun Header(title: String, status: (@Composable RowScope.() -> Unit)? = null, compact: Boolean = false) {
    Column {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Mark(24.dp)
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, color = P.text,
                letterSpacing = (-0.2).sp, maxLines = 1, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.width(12.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (status != null) status()
            }
            ThemeButton()
        }
        Divider()
    }
}

/** 우상단 테마 버튼 — 지금 보이는 테마(해/달), 시스템 모드면 「자동」을 덧붙인다. */
@Composable
fun ThemeButton() {
    val ctx = LocalContext.current
    val label = when (ThemeMode.mode) { "light" -> "밝게"; "dark" -> "어둡게"; else -> "자동" }
    Row(Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
            .border(1.dp, P.border, RoundedCornerShape(10.dp))
            .clickable(onClickLabel = "테마 바꾸기") { ThemeMode.cycle(ctx) }
            .semantics { contentDescription = "테마: $label" }
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
        Ic(if (ThemeMode.dark) "moon" else "sun", P.text, 18.dp)
        if (ThemeMode.mode == "system") Text("자동", color = P.dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── 점·알약 ──────────────────────────────────────────────────────────────

/** 상태 점. breathe = 동작 중(숨쉬기). */
@Composable
fun Dot(color: Color, size: Dp = 8.dp, breathe: Boolean = false) {
    val a = if (breathe) {
        val v by rememberInfiniteTransition(label = "breath").animateFloat(1f, .35f,
            infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "breath")
        v
    } else 1f
    Box(Modifier.size(size).alpha(a).background(color, CircleShape))
}

@Composable
fun Pill(text: String, color: Color, icon: String? = null) {
    Row(Modifier.clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = .14f)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (icon != null) Ic(icon, color, 12.dp)
        Text(text, color = color, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun MonoTag(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = P.dim,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(P.bg).border(1.dp, P.border, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp))
}

// ── 버튼 ─────────────────────────────────────────────────────────────────

enum class BtnKind { Primary, Secondary, Quiet, Danger }

@Composable
fun Btn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: BtnKind = BtnKind.Secondary,
        icon: String? = null, enabled: Boolean = true, busy: Boolean = false) {
    val s = RoundedCornerShape(24.dp)
    val fg = when (kind) {
        BtnKind.Primary -> P.onAccent
        BtnKind.Danger -> P.danger
        BtnKind.Quiet -> P.accent
        else -> P.text
    }
    var m = modifier.heightIn(min = 48.dp).clip(s)
    m = when (kind) {
        BtnKind.Primary -> m.background(P.accent)
        BtnKind.Secondary -> m.border(1.dp, P.border, s)
        else -> m
    }
    Row(m.clickable(enabled = enabled && !busy, role = Role.Button, onClickLabel = text) { onClick() }
            .alpha(if (enabled) 1f else .45f).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        if (busy) Ic("loader", fg, 16.dp, spin = true)
        else if (icon != null) Ic(icon, fg, 17.dp)
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 44~48dp 정사각 아이콘 버튼. 이름(label)은 TalkBack 용으로 필수. */
@Composable
fun IconBtn(icon: String, label: String, onClick: () -> Unit, tint: Color = P.dim) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = label, role = Role.Button) { onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center) { Ic(icon, tint, 20.dp) }
}

// ── 스위치 줄 ───────────────────────────────────────────────────────────

/** 줄 전체가 눌리는 스위치. busy = 요청을 보냈고 결과를 기다리는 중(트랙을 흐리게). */
@Composable
fun ToggleRow(label: String, sub: String = "", on: Boolean, enabled: Boolean = true, busy: Boolean = false,
              icon: String? = null, onChange: (Boolean) -> Unit) {
    val x by animateDpAsState(if (on) 20.dp else 0.dp, tween(140), label = "knob")
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(on, enabled = enabled && !busy, role = Role.Switch, onValueChange = onChange)
            .semantics { if (busy) stateDescription = "바꾸는 중" }
            .padding(vertical = 8.dp).alpha(if (enabled) 1f else .45f),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { IconTile(icon, if (on) P.accent else P.dim); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = P.text, fontSize = 15.sp, lineHeight = 20.sp)
            if (sub.isNotEmpty()) Text(sub, color = P.dim, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        Box(Modifier.size(48.dp, 28.dp).alpha(if (busy) .55f else 1f)
                .background(if (on) P.accent else P.panel2, RoundedCornerShape(14.dp))
                .border(1.dp, if (on) P.accent else P.border, RoundedCornerShape(14.dp)).padding(3.dp)) {
            Box(Modifier.offset { IntOffset(x.roundToPx(), 0) }.size(22.dp).background(if (on) P.onAccent else P.dim, CircleShape),
                contentAlignment = Alignment.Center) {
                if (busy) Ic("loader", if (on) P.accent else P.panel2, 12.dp, spin = true)
            }
        }
    }
}

/** 36dp 둥근 사각 안 아이콘 — 카드·줄의 앞머리. */
@Composable
fun IconTile(icon: String, tint: Color, size: Dp = 36.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = .12f)),
        contentAlignment = Alignment.Center) { Ic(icon, tint, size * 0.52f) }
}

// ── 섹션·접기 ───────────────────────────────────────────────────────────

@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = P.dim, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics { heading() })
        if (trailing != null) Text(trailing, color = P.dim, fontSize = 12.sp)
    }
}

/** 누르면 펼쳐지는 카드(도움말·개발자 도구). */
@Composable
fun Collapsible(title: String, icon: String, sub: String = "", startOpen: Boolean = false,
                content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    val rot by animateFloatAsState(if (open) 180f else 0f, tween(160), label = "chev")
    Column(Modifier.fillMaxWidth().card()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(onClickLabel = if (open) "접기" else "펼치기") { open = !open }
                .semantics { stateDescription = if (open) "펼쳐짐" else "접힘" }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Ic(icon, P.dim, 18.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P.text)
                if (sub.isNotEmpty()) Text(sub, fontSize = 12.5.sp, color = P.dim, lineHeight = 17.sp)
            }
            Ic("chevron-down", P.dim, 18.dp, modifier = Modifier.rotate(rot))
        }
        AnimatedVisibility(open, enter = expandVertically(tween(180)) + fadeIn(), exit = shrinkVertically(tween(160)) + fadeOut()) {
            Column(Modifier.fillMaxWidth()) {
                Divider()
                Column(Modifier.padding(16.dp), content = content)
            }
        }
    }
}

// ── 오류·안내 상자 ──────────────────────────────────────────────────────

/** 물든 상자: 제목 + 설명 + (선택) 원문 + 동작. tone = danger / warn. */
@Composable
fun NoticeBox(tone: Color, icon: String, title: String, body: String, raw: String? = null,
              action: (@Composable RowScope.() -> Unit)? = null) {
    var showRaw by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(tone.copy(alpha = .10f))
            .border(1.dp, tone.copy(alpha = .35f), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Ic(icon, tone, 16.dp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = P.text)
                Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = P.dim)
            }
        }
        if (raw != null) {
            if (showRaw) SelectionContainer {
                Text(raw, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = P.dim,
                    modifier = Modifier.padding(start = 24.dp))
            }
            Text(if (showRaw) "자세히 접기" else "자세히 보기", fontSize = 12.5.sp, color = P.dim, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { showRaw = !showRaw }.padding(horizontal = 8.dp, vertical = 12.dp))
        }
        if (action != null) Row(Modifier.fillMaxWidth().padding(start = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically, content = action)
    }
}

// ── 스낵바 ──────────────────────────────────────────────────────────────

class SnackState {
    var msg by mutableStateOf<String?>(null)
    var seq by mutableIntStateOf(0)
    fun show(m: String) { msg = m; seq++ }
}

@Composable
fun Snack(state: SnackState, modifier: Modifier = Modifier) {
    val m = state.msg
    androidx.compose.runtime.LaunchedEffect(state.seq) {
        if (m != null) { kotlinx.coroutines.delay(3200); state.msg = null }
    }
    AnimatedVisibility(m != null, modifier = modifier, enter = fadeIn(tween(140)), exit = fadeOut(tween(200))) {
        Box(Modifier.padding(16.dp).widthIn(max = 520.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                .background(P.raised).border(1.dp, P.border, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 13.dp),
            contentAlignment = Alignment.CenterStart) {
            Text(state.msg ?: "", fontSize = 14.sp, lineHeight = 20.sp, color = P.text)
        }
    }
}
