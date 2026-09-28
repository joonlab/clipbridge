package kr.joonlab.clipbridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kr.joonlab.clipbridge.BuildConfig

/** 화면이 부르는 동작 — 권한 요청·설정 열기 등. 구현은 MainActivity 가 기존 로직을 그대로 부른다. */
interface HomeActions {
    fun toggleServer(on: Boolean)
    fun checkMac()
    fun askNotify()
    fun openBattery()
    fun openOverlay()
    fun openListener()
    fun askSmsSend()
    fun askSmsRead()
    fun addTile()
    val canAddTile: Boolean
}

@Composable
fun HomeScreen(m: HomeModel, a: HomeActions, snack: SnackState, resumed: Boolean) {
    // 화면이 보일 때만 폴링: 수신 서버 1초, 권한 2초, 맥 30초.
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        m.refreshAll()
        var n = 0
        while (true) {
            m.pollServer()
            if (n % 2 == 0) m.refreshPerms()
            n++
            delay(1000)
        }
    }
    LaunchedEffect(resumed) {
        while (resumed) { m.checkMac(); delay(30_000) }
    }
    // "3초 전" 같은 상대 시각을 흘러가게
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(resumed) { while (resumed) { now = System.currentTimeMillis(); delay(1000) } }

    val cfg = LocalConfiguration.current
    // 2열은 840dp 부터(태블릿·펼친 폴드 가로). 그 아래는 1열 가운데 정렬(최대 560dp) — 두 칸이 좁아 줄바꿈이 늘어난다.
    val wide = cfg.screenWidthDp >= 840
    val short = cfg.screenHeightDp < 480

    Box(Modifier.fillMaxSize().background(P.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Column(Modifier.fillMaxSize()) {
            Header("ClipBridge", status = { HeaderStatus(m, showText = cfg.screenWidthDp >= 360) })
            val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp).padding(top = if (short) 8.dp else 14.dp, bottom = nav + 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (wide) {
                    Row(Modifier.widthIn(max = 1040.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        // 왼쪽 = 연결 + 주고받는 방법(주 동작, 길다), 오른쪽 = 권한 + 더 보기 + 정보(설정·안내).
                        Column(Modifier.weight(1f)) { LeftColumn(m, a, now, label = true) }
                        Column(Modifier.weight(1f)) { RightColumn(m, a) }
                    }
                } else {
                    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                        LeftColumn(m, a, now)
                        RightColumn(m, a)
                    }
                }
            }
        }
        Snack(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun HeaderStatus(m: HomeModel, showText: Boolean) {
    val (c, t) = when (m.mac) {
        is HomeModel.Mac.Online -> P.ok to "맥 연결됨"
        is HomeModel.Mac.Checking -> P.dim to "확인 중…"
        is HomeModel.Mac.Offline -> P.danger to "연결 안 됨"
    }
    Row(Modifier.semantics { contentDescription = t }, verticalAlignment = Alignment.CenterVertically) {
        Dot(c, 7.dp)
        if (showText) {
            Spacer(Modifier.width(6.dp))
            Text(t, fontSize = 12.5.sp, color = P.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ColumnScope.LeftColumn(m: HomeModel, a: HomeActions, now: Long, label: Boolean = false) {
    // 두 칸일 때 오른쪽 「권한」 머리와 윗선을 맞춘다
    if (label) SectionLabel("연결", trailing = if (m.serverOn) "받기 켜짐" else "받기 꺼짐")
    ConnectionCard(m, a, now)
    SectionLabel("주고받는 방법")
    DirectionCards(m, a)
}

@Composable
private fun ColumnScope.RightColumn(m: HomeModel, a: HomeActions) {
    SectionLabel("권한", trailing = m.perms.missingAll.let { if (it == 0) null else "${it}개 남음" })
    PermissionCard(m.perms, a)
    SectionLabel("더 보기")
    HowItWorks()
    Footer(m)
}

// ── 1. 연결 카드 ────────────────────────────────────────────────────────

@Composable
private fun ConnectionCard(m: HomeModel, a: HomeActions, now: Long) {
    val mac = m.mac
    val online = mac is HomeModel.Mac.Online
    val live = online && m.serverOn
    Column(Modifier.fillMaxWidth().card(tint = if (live) P.accentTint else Color.Transparent,
            outline = if (live) P.accent.copy(alpha = .35f) else null).padding(16.dp)) {

        // 제목 줄 + 작은 다리 그림을 한 줄에 — 커버 첫 화면에 「주고받는 방법」 머리까지 보이게.
        val title = when (mac) {
            is HomeModel.Mac.Online -> "맥과 연결돼 있어요"
            is HomeModel.Mac.Checking -> "맥을 찾는 중…"
            is HomeModel.Mac.Offline -> Words.netCause(mac.err).first
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = P.text)
                when (mac) {
                    is HomeModel.Mac.Online -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(buildString {
                            m.macHost?.let { append(it); append(" · ") }
                            append("응답 ${mac.ms}ms · ${Words.ago(mac.at, now)} 확인")
                        }, fontSize = 12.5.sp, lineHeight = 18.sp, color = P.dim, style = tnum,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        // 1초를 넘으면 글자만으로는 놓친다 → warn 알약
                        if (mac.ms > 1000) { Spacer(Modifier.width(6.dp)); Pill("느림", P.warn, "clock") }
                    }
                    is HomeModel.Mac.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Ic("loader", P.dim, 13.dp, spin = true); Spacer(Modifier.width(6.dp))
                        Text("최대 5초 걸려요", fontSize = 12.5.sp, color = P.dim)
                    }
                    is HomeModel.Mac.Offline -> Text("이 폰 → 맥 보내기가 멈춰 있어요", fontSize = 12.5.sp, lineHeight = 18.sp, color = P.dim)
                }
            }
            Spacer(Modifier.width(12.dp))
            MiniBridge(mac, m.serverOn)
        }
        if (mac is HomeModel.Mac.Offline) {
            Spacer(Modifier.height(10.dp))
            val (_, hint) = Words.netCause(mac.err)
            val lastOk = if (m.macLastOk > 0) "마지막 연결 ${Words.ago(m.macLastOk, now)}. " else ""
            NoticeBox(P.danger, "circle-x", "폰 → 맥 보내기가 지금은 안 돼요", lastOk + hint, raw = mac.err) {
                Btn("다시 확인", { a.checkMac() }, icon = "refresh", busy = m.checking)
            }
        }
        if (online && m.watch?.on == false) {
            Spacer(Modifier.height(8.dp))
            NoticeBox(P.warn, "alert", "맥에서 자동 보내기가 꺼져 있어요",
                "맥에서 복사해도 이 폰으로 오지 않아요. 맥의 ClipBridge 에서 자동 보내기를 켜 주세요.")
        }

        // 물든 카드 안에서는 border 색이 면에 묻혀 안 보인다(다크) → accent 로 살짝 물든 선.
        val line = if (live) P.accent.copy(alpha = if (ThemeMode.dark) .22f else .25f) else P.border
        Spacer(Modifier.height(6.dp))
        Divider(line)
        val busy = m.serverTarget != null
        ToggleRow(
            label = "맥에서 받기",
            sub = when {
                busy -> if (m.serverTarget == true) "켜는 중…" else "끄는 중…"
                m.serverOn -> "맥에서 복사한 글·링크·파일이 1~2초 안에 들어와요"
                else -> "꺼 두면 맥에서 복사해도 이 폰으로 들어오지 않아요"
            },
            on = m.serverTarget ?: m.serverOn, busy = busy, icon = "power",
        ) { a.toggleServer(it) }
        if (m.serverOn && m.watch?.reachable == false && online) {
            NoticeBox(P.warn, "alert", "맥이 이 폰에 닿지 않는다고 해요",
                "이 폰의 Tailscale 이 켜져 있는지 확인해 주세요. 방금 켰다면 잠시 뒤 저절로 풀려요.")
            Spacer(Modifier.height(4.dp))
        }
        Divider(line)
        LastReceived(m, now)
    }
}

/** 폰 ─ 맥 작은 다리 그림(약 104dp). 연결 상태 글은 왼쪽 제목이 말하고, 이건 한눈 표식이다. */
@Composable
private fun MiniBridge(mac: HomeModel.Mac, serverOn: Boolean) {
    val online = mac is HomeModel.Mac.Online
    val (c, icon) = when (mac) {
        // 카드가 accent 로 물들어 있어 ok(초록)를 쓰면 틸·초록 두 색이 한 그림에 섞인다 → 다리는 accent.
        is HomeModel.Mac.Online -> P.accent to "arrow-left-right"
        is HomeModel.Mac.Checking -> P.dim to "loader"
        is HomeModel.Mac.Offline -> P.danger to "x"
    }
    Row(Modifier.semantics { contentDescription = if (online) "이 폰과 맥이 연결됨" else "이 폰과 맥이 끊김" },
        verticalAlignment = Alignment.CenterVertically) {
        MiniNode("phone", serverOn)
        Box(Modifier.width(40.dp).height(32.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(c.copy(alpha = if (online) .7f else .35f), CircleShape))
            Box(Modifier.size(22.dp).background(P.panel, CircleShape).background(c.copy(alpha = .14f), CircleShape),
                contentAlignment = Alignment.Center) { Ic(icon, c, 12.dp, spin = mac is HomeModel.Mac.Checking) }
        }
        MiniNode("laptop", online)
    }
}

@Composable
private fun MiniNode(icon: String, on: Boolean) {
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(if (on) P.accent.copy(alpha = .14f) else P.panel2),
        contentAlignment = Alignment.Center) { Ic(icon, if (on) P.accent else P.dim, 17.dp) }
}

@Composable
private fun LastReceived(m: HomeModel, now: Long) {
    val raw = m.last
    val none = raw == "아직 없음" || raw.isBlank()
    // 서비스가 남기는 접두 기호(📎 🔗 ✉️)를 아이콘으로 바꾼다
    val (icon, kind, body) = when {
        none -> Triple("clipboard", "", "")
        raw.startsWith("📎") -> Triple("file", "파일", raw.removePrefix("📎").trim())
        raw.startsWith("🔗") -> Triple("link", "링크", raw.removePrefix("🔗").trim())
        raw.startsWith("✉️") -> Triple("message", "문자 보냄", raw.removePrefix("✉️").trim())
        else -> Triple("clipboard", "클립보드", raw.replace("⏎", " "))
    }
    if (none) {
        // 빈 상태: 라벨(본문색) + 한 줄 dim. 작은 회색 라벨 아래 큰 회색 문장이 오던 뒤집힌 위계를 바로잡는다.
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, P.dim, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("최근 받은 것", fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = P.text)
                Text(if (m.serverOn) "아직 없어요 · 맥에서 복사하면 여기에 보여요" else "받기를 켜면 여기에 보여요",
                    fontSize = 12.5.sp, lineHeight = 17.sp, color = P.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
        IconTile(icon, P.accent, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("최근 받은 것", fontSize = 12.sp, color = P.dim, fontWeight = FontWeight.SemiBold)
                if (kind.isNotEmpty()) { Spacer(Modifier.width(6.dp)); Pill(kind, P.accent) }
                Spacer(Modifier.weight(1f))
                if (m.lastAt > 0) Text(Words.ago(m.lastAt, now), fontSize = 12.sp, color = P.dim, style = tnum)
            }
            Spacer(Modifier.height(2.dp))
            SelectionContainer {
                Text(body, fontSize = 14.5.sp, lineHeight = 21.sp, color = P.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ── 2. 방향별 카드 ──────────────────────────────────────────────────────

@Composable
private fun DirectionCards(m: HomeModel, a: HomeActions) {
    Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
        DirHead("맥 → 폰", "자동", P.accent)
        Step("clipboard", "복사하면 바로 들어와요", "맥에서 복사한 글이 이 폰 클립보드로 옮겨져요.")
        Step("link", "링크는 바로 열려요", if (m.perms.overlay) "맥에서 보낸 링크가 폰에서 곧장 열려요."
            else "지금은 알림으로 와요. 아래 「링크 바로 열기」를 허용하면 곧장 열려요.")
        Step("file", "파일은 다운로드 폴더로", "받으면 알림이 뜨고, 누르면 바로 열려요.")
    }
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
        DirHead("폰 → 맥", "두 번 누르기", P.dim)
        Step("tile", "빠른 설정 「맥으로 복사」",
            "복사한 뒤 화면 위에서 쓸어내리고 타일을 누르세요.") {
            if (m.tileAdded) Pill("추가됨", P.ok, "check")
            else if (a.canAddTile) Btn("타일 추가", { a.addTile() }, kind = BtnKind.Primary, icon = "tile")
            else Text("빠른 설정 편집(연필)에서 꺼내 두세요.", fontSize = 12.5.sp, color = P.dim, lineHeight = 17.sp)
        }
        Step("share", "공유 「맥으로 보내기」", "사진·동영상·문서·글 무엇이든. 여러 개도 한 번에 보내요.")
        Step("type", "글자 선택 메뉴", "글자를 길게 눌러 선택하고 「맥으로 보내기」를 고르세요.")
    }
}

@Composable
private fun DirHead(title: String, badge: String, c: Color) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = P.text)
        Spacer(Modifier.width(8.dp))
        Pill(badge, c)
    }
}

@Composable
private fun Step(icon: String, title: String, sub: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Top) {
        Ic(icon, P.accent, 18.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = P.text)
            Text(sub, fontSize = 13.sp, lineHeight = 19.sp, color = P.dim)
            if (trailing != null) { Spacer(Modifier.height(8.dp)); trailing() }
        }
    }
}

// ── 3. 권한 ─────────────────────────────────────────────────────────────

@Composable
private fun PermissionCard(p: HomeModel.Perms, a: HomeActions) {
    Column(Modifier.fillMaxWidth().card()) {
        PermGroup(p.missingAll == 0, "모든 권한 허용됨", "6개 · 설정을 바꾸려면 펼치세요") { PermList(p, a) }
    }
}

@Composable
private fun PermList(p: HomeModel.Perms, a: HomeActions) {
    Column(Modifier.fillMaxWidth()) {
        PermRow("bell", "알림 표시", "링크·파일 알림을 띄우려면 필요해요.", p.notify, "필수") { a.askNotify() }
        Divider()
        PermRow("battery", "배터리 최적화 제외", "화면을 오래 꺼 둬도 받기가 잠들지 않아요.", p.battery, "권장") { a.openBattery() }
        Divider()
        PermRow("layers", "링크 바로 열기", "「다른 앱 위에 표시」를 켜면 맥에서 보낸 링크가 알림 없이 열려요.", p.overlay) { a.openOverlay() }
        Divider()
        PermRow("laptop", "알림을 맥으로", "폰 알림을 맥에 띄워요. 설정 목록에서 「맥으로 알림 미러링」을 켜세요.", p.listener) { a.openListener() }
        Divider()
        PermRow("send", "문자 보내기", "맥에서 쓴 문자를 이 폰이 대신 보내요.", p.smsSend) { a.askSmsSend() }
        Divider()
        PermRow("inbox", "문자 읽기", "맥에서 받은 문자를 찾아볼 수 있어요.", p.smsRead) { a.askSmsRead() }
    }
}

/**
 * 권한이 모두 허용되면 「허용됨」 줄을 줄줄이 보여 주지 않고 요약 한 줄로 접는다. 하나라도 빠지면 늘 펼친다.
 * 요약 줄을 누르면 펼쳐진다(허용된 권한도 설정을 다시 열 수 있게).
 */
@Composable
private fun PermGroup(allOk: Boolean, title: String, sub: String, rows: @Composable () -> Unit) {
    if (!allOk) { rows(); return }
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val rot by animateFloatAsState(if (open) 180f else 0f, tween(160), label = "chev")
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .clickable(role = Role.Button, onClickLabel = if (open) "권한 목록 접기" else "권한 목록 펼치기") { open = !open }
            .semantics { stateDescription = if (open) "펼쳐짐" else "접힘" }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconTile("circle-check", P.ok, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, lineHeight = 20.sp, color = P.text, fontWeight = FontWeight.Medium)
            Text(sub, fontSize = 12.5.sp, lineHeight = 17.sp, color = P.dim)
        }
        Spacer(Modifier.width(8.dp))
        Text(if (open) "접기" else "펼치기", fontSize = 13.sp, color = P.dim, fontWeight = FontWeight.Medium)
        Ic("chevron-down", P.dim, 16.dp, modifier = Modifier.rotate(rot))
    }
    AnimatedVisibility(open, enter = expandVertically(tween(180)) + fadeIn(), exit = shrinkVertically(tween(160)) + fadeOut()) {
        Column(Modifier.fillMaxWidth()) { Divider(); rows() }
    }
}

@Composable
private fun PermRow(icon: String, title: String, why: String, granted: Boolean, tag: String? = null,
                    enabled: Boolean = true, grantLabel: String = "허용", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = if (granted) "설정 열기" else "$title 허용") { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // 끝난 줄은 조용히(무채), 할 일이 남은 줄에 색을 준다 — 눈이 「허용」 쪽으로 가게. 허용 여부는 오른쪽 알약이 말한다.
        IconTile(icon, if (granted) P.dim else if (tag == "필수") P.warn else P.accent, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 15.sp, lineHeight = 20.sp, color = P.text, fontWeight = FontWeight.Medium)
                if (tag != null && !granted) { Spacer(Modifier.width(6.dp)); Pill(tag, if (tag == "필수") P.warn else P.dim) }
            }
            Text(why, fontSize = 12.5.sp, lineHeight = 17.sp, color = P.dim)
        }
        Spacer(Modifier.width(10.dp))
        if (granted) Pill("허용됨", P.ok, "check")
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(grantLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) P.accent else P.dim)
            Ic("chevron-right", if (enabled) P.accent else P.dim, 16.dp)
        }
    }
}

// ── 4. 도움말·정보 ───────────────────────────────────────────────────

@Composable
private fun HowItWorks() {
    Collapsible("어떻게 동작하나요?", "help", "왜 한쪽만 자동인지") {
        Para("안드로이드는 화면에 떠 있지 않은 앱이 클립보드를 «읽는» 것을 막아요. 하지만 «쓰는» 것은 막지 않아요.")
        Para("그래서 맥 → 폰은 자동으로 되고, 폰 → 맥은 타일이나 공유처럼 한 번 눌러 주는 동작이 필요해요. 그 순간만큼은 ClipBridge 가 앞에 뜨기 때문에 클립보드를 읽을 수 있어요.")
        Para("맥과 폰은 Tailscale 사설망으로만 이야기해요. 같은 폰의 다른 앱은 토큰이 없어 끼어들 수 없어요.")
    }
}

@Composable
private fun Para(s: String) = Text(s, fontSize = 14.sp, lineHeight = 22.sp, color = P.text, modifier = Modifier.padding(bottom = 8.dp))

@Composable
private fun Footer(m: HomeModel) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, start = 4.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("이 폰 주소", fontSize = 12.sp, color = P.dim)
            Spacer(Modifier.width(8.dp))
            SelectionContainer { MonoTag(if (m.serverOn) m.boundTo else "받기 꺼짐") }
        }
        Text("ClipBridge ${BuildConfig.VERSION_NAME} · 빌드 ${BuildConfig.BUILD_TIME}", fontSize = 12.sp, color = P.dim)
    }
}
