package kr.joonlab.clipbridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 공유·타일 전송 창의 상태. */
sealed interface SendState {
    data object Idle : SendState
    /** progress = (끝난 개수, 전체) — 파일일 때만 */
    data class Sending(val title: String, val detail: String, val progress: Pair<Int, Int>?) : SendState
    data class Done(val title: String, val detail: String) : SendState
    enum class Tone { Danger, Warn }
    data class Failed(val title: String, val hint: String, val raw: String?, val retry: (() -> Unit)?,
                      val items: List<String> = emptyList(), val tone: Tone = Tone.Danger) : SendState
}

/**
 * 투명 창 아래쪽의 작은 카드. 보내는 동안 무엇을 하는지 보이고, 실패하면 이유와 다시 시도가 남는다.
 * 바깥을 누르면 닫힌다(보내는 중에는 닫아도 전송은 계속되고 결과는 토스트로 온다).
 */
@Composable
fun SendSheet(s: SendState, onClose: () -> Unit) {
    val dimA by animateFloatAsState(if (s is SendState.Idle) 0f else .32f, tween(160), label = "scrim")
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimA))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "닫기") { onClose() }
            .safeDrawingPadding(),
        contentAlignment = Alignment.BottomCenter) {
        if (s !is SendState.Idle) {
            Column(Modifier.padding(12.dp).widthIn(max = 520.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp)).background(P.raised).border(1.dp, P.border, RoundedCornerShape(20.dp))
                    // 카드 안을 눌러도 닫히지 않게 클릭을 먹는다
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 18.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mark(20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("ClipBridge", fontSize = 12.sp, color = P.dim, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(12.dp))
                AnimatedContent(s, transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) },
                    contentKey = { it::class }, label = "send") { st -> Body(st, onClose) }
            }
        }
    }
}

@Composable
private fun Body(s: SendState, onClose: () -> Unit) {
    when (s) {
        is SendState.Idle -> {}
        is SendState.Sending -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ic("loader", P.accent, 22.dp, spin = true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P.text)
                    if (s.detail.isNotEmpty()) Text(s.detail, fontSize = 13.sp, color = P.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (s.progress != null && s.progress.second > 1)
                    Text("${s.progress.first + 1}/${s.progress.second}", fontSize = 13.sp, color = P.dim, style = tnum)
            }
            if (s.progress != null && s.progress.second > 1) {
                Spacer(Modifier.height(12.dp))
                val f by animateFloatAsState(s.progress.first.toFloat() / s.progress.second, tween(220), label = "prog")
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(P.panel2)) {
                    Box(Modifier.fillMaxWidth(f).height(4.dp).background(P.accent))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        is SendState.Done -> Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile("check", P.ok, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P.text)
                Text(s.detail, fontSize = 13.sp, color = P.dim, style = tnum, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        is SendState.Failed -> {
            val tone = if (s.tone == SendState.Tone.Warn) P.warn else P.danger
            Column {
                Row(verticalAlignment = Alignment.Top) {
                    IconTile(if (s.tone == SendState.Tone.Warn) "clipboard" else "alert", tone, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = P.text)
                        Text(s.hint, fontSize = 13.5.sp, lineHeight = 20.sp, color = P.dim)
                    }
                }
                if (s.items.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.fillMaxWidth().heightIn(max = 140.dp).clip(RoundedCornerShape(10.dp)).background(P.panel2)
                            .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        s.items.forEach {
                            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Ic("file", P.dim, 14.dp); Spacer(Modifier.width(8.dp))
                                Text(it, fontSize = 13.sp, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (s.raw != null) {
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(s.raw, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.sp, color = P.dim,
                            maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    // 실패 창의 「닫기」는 채우지 않는다 — 오류 톤 위에 밝은 accent 가 가장 큰 면이 되면 성공처럼 보인다.
                    Btn("닫기", onClose, kind = BtnKind.Secondary)
                    if (s.retry != null) Btn(if (s.items.size > 1) "실패한 것만 다시" else "다시 시도", s.retry,
                        kind = BtnKind.Primary, icon = "refresh")
                }
            }
        }
    }
}
