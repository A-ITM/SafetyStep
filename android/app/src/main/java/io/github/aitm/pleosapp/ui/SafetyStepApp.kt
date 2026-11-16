package io.github.aitm.pleosapp.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aitm.pleosapp.BuildConfig
import io.github.aitm.pleosapp.prototype.*

/** This composable owns one application window. Navigation belongs to Pleos. */
@Composable
fun SafetyStepApp(model: PrototypeViewModel, diagnostics: @Composable () -> Unit) {
    val s by model.state.collectAsStateWithLifecycle()
    var diagnostic by rememberSaveable { mutableStateOf(false) }
    var tools by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.drivingRestricted) {
        if (s.drivingRestricted) { tools = false; diagnostic = false }
    }
    SafetyStepTheme(s.theme) {
        val c = LocalSafetyColors.current
        Surface(Modifier.fillMaxSize(), color = c.panel) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val padding = if (maxWidth < 600.dp) SafetyStepTokens.compactGutter else SafetyStepTokens.gutter
                Column(Modifier.fillMaxSize().padding(horizontal = padding, vertical = 24.dp)) {
                    AppHeader({ tools = true }, diagnostic, !s.drivingRestricted) { diagnostic = false }
                    Spacer(Modifier.height(24.dp))
                    if (s.drivingRestricted) {
                        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) { DrivingContent(s) }
                    } else if (diagnostic) Box(Modifier.weight(1f)) { diagnostics() }
                    else if (!BuildConfig.DEBUG) Text("디버그 프로토타입에서 실행해주세요.", Modifier.weight(1f))
                    else {
                        val receiver = s.receiverView && s.incident == null
                        val screen = when {
                            receiver -> "RECEIVER"
                            s.incident == null -> "DRIVE"
                            s.incident?.help == HelpState.NEEDS_HELP -> "HELP"
                            else -> "DASHBOARD"
                        }
                        Column(
                            Modifier.weight(1f).fillMaxWidth().testTag("screen-$screen")
                                .verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            if (receiver) ReceiverContent(s, model)
                            else if (s.incident != null) IncidentDashboard(s, model)
                            else HomeContent(s, model)
                        }
                    }
                    StatusBand(s)
                }
            }
        }
        if (tools && !s.drivingRestricted) ToolsDialog(s, model, { tools = false }) { tools = false; diagnostic = true }
    }
}

@Composable private fun AppHeader(openTools: () -> Unit, diagnostic: Boolean, editable: Boolean, closeDiagnostic: () -> Unit) {
    val c = LocalSafetyColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = SafetyStepTokens.touch), verticalAlignment = Alignment.CenterVertically) {
        SafetyMark(Modifier.size(36.dp), c.blue)
        Text("세이프티스탭", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.padding(start = 16.dp))
        Spacer(Modifier.weight(1f))
        if (diagnostic && editable) TextButton(onClick = closeDiagnostic) { Text("지원 화면") }
        if (editable) TextButton(onClick = openTools, modifier = Modifier.testTag("tools").size(SafetyStepTokens.touch), shape = RoundedCornerShape(SafetyStepTokens.radius),
            colors = ButtonDefaults.textButtonColors(containerColor = Color.Transparent)) {
            Text("•••", fontSize = 26.sp, color = c.secondary)
        }
    }
}

@Composable private fun SafetyMark(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val shield = Path().apply {
            moveTo(w * .5f, h * .04f); lineTo(w * .86f, h * .2f); lineTo(w * .86f, h * .52f)
            cubicTo(w * .86f, h * .73f, w * .67f, h * .88f, w * .5f, h * .97f)
            cubicTo(w * .33f, h * .88f, w * .14f, h * .73f, w * .14f, h * .52f)
            lineTo(w * .14f, h * .2f); close()
        }
        drawPath(shield, color, style = Stroke((w * .045f).coerceAtLeast(2f), cap = StrokeCap.Round))
    }
}

@Composable private fun HomeContent(s: PrototypeUi, model: PrototypeViewModel) {
    val c = LocalSafetyColors.current
    SafetyMark(Modifier.size(112.dp), c.mint)
    Hero("안전운전 하세요", "정차 중 주변 위험 알림을 켤 수 있어요.")
    ReceiverSettings(s, model)
    if (s.warningCards.isNotEmpty()) WarningCards(s.warningCards)
    Action("고장·정차 도움받기", "start-support") { model.begin() }
    SecondaryAction("사고 도움받기", "start-accident") { model.begin(eventType = EventType.ACCIDENT) }
    Text("TEST 프로토타입 · 공유와 접근 알림은 시험 위치를 사용해요.", style = SafetyStepTokens.caption, color = c.secondary)
    StopReportCard(s)
}

@Composable internal fun RoadNotice(s: PrototypeUi) = StopReportCard(s)

@Composable internal fun StopReportCard(s: PrototypeUi) {
    val c = LocalSafetyColors.current
    val message = when (s.stopReportStatus) {
        StopReportStatus.NONE -> if (s.consent) "위험 위치 공유 켜짐" else "위험 위치 공유 꺼짐"
        StopReportStatus.CANDIDATE -> "연속 정차를 확인하고 있어요"
        StopReportStatus.HELD -> "정차 위치 공유를 보류했어요"
        StopReportStatus.QUEUED -> "위험 위치 공유를 기다리고 있어요"
        StopReportStatus.SHARED -> "TEST 위험 위치를 공유했어요"
        StopReportStatus.RETRACTED -> "재출발을 확인해 정차 알림을 철회했어요"
        StopReportStatus.REJECTED -> "위험 위치를 공유하지 못했어요"
    }
    Column(Modifier.fillMaxWidth().testTag("stop-report-status"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InlineNote(message, if (s.stopReportStatus == StopReportStatus.SHARED) c.blue else c.secondary)
        if (s.stopReportStatus != StopReportStatus.NONE && s.stopReportDetail.isNotBlank()) Text(s.stopReportDetail, style = SafetyStepTokens.caption, color = c.secondary)
    }
}

@Composable internal fun Hero(title: String, body: String = "") {
    val c = LocalSafetyColors.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = SafetyStepTokens.hero)
        if (body.isNotEmpty()) Text(body, color = c.secondary, style = SafetyStepTokens.body)
    }
}

@Composable internal fun Action(label: String, tag: String = label, enabled: Boolean = true, danger: Boolean = false, action: () -> Unit) {
    val c = LocalSafetyColors.current
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = SafetyStepTokens.primaryHeight).testTag(tag),
        shape = RoundedCornerShape(SafetyStepTokens.radius), contentPadding = PaddingValues(22.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) Color(0xFFC92A30) else MaterialTheme.colorScheme.primary,
            contentColor = if (danger) Color.White else MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = c.inset, disabledContentColor = c.secondary)) {
        Text(label, style = SafetyStepTokens.action)
    }
}

@Composable internal fun SecondaryAction(label: String, tag: String = label, action: () -> Unit) {
    val c = LocalSafetyColors.current
    OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = SafetyStepTokens.secondaryHeight).testTag(tag),
        shape = RoundedCornerShape(SafetyStepTokens.radius), contentPadding = PaddingValues(22.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text, containerColor = c.inset)) {
        Text(label, style = SafetyStepTokens.action)
    }
}

@Composable internal fun LinkAction(label: String, tag: String = label, color: Color = LocalSafetyColors.current.blue, action: () -> Unit) {
    TextButton(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = SafetyStepTokens.touch).testTag(tag),
        shape = RoundedCornerShape(SafetyStepTokens.radius), colors = ButtonDefaults.textButtonColors(containerColor = Color.Transparent)) {
        Text(label, color = color, fontSize = 24.sp)
    }
}

@Composable private fun InlineNote(text: String, color: Color) {
    val c = LocalSafetyColors.current
    Text(text, fontSize = 24.sp, lineHeight = 36.sp, color = color,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.inset).padding(22.dp))
}

@Composable private fun CheckLine(label: String, checked: Boolean, change: (Boolean) -> Unit, tag: String, enabled: Boolean = true) {
    val c = LocalSafetyColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = SafetyStepTokens.touch)
        .clip(RoundedCornerShape(SafetyStepTokens.radius)).background(if (checked) c.blue.copy(alpha = .09f) else Color.Transparent)
        .border(1.dp, if (checked) c.blue.copy(alpha = .45f) else c.line, RoundedCornerShape(SafetyStepTokens.radius))
        .testTag(tag).toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = change)
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.size(48.dp))
        Text(label, style = SafetyStepTokens.label, color = if (enabled) c.text else c.secondary,
            modifier = Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, bottom = 12.dp))
    }
}

@Composable internal fun ContactActions() {
    val context = LocalContext.current
    var failure by remember { mutableStateOf(false) }
    Action("긴급 전화 열기", "contact-phone", danger = true) {
        failure = runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:119"))) }.isFailure
    }
    if (failure) Text("차량의 전화 메뉴에서 긴급 전화를 이용해주세요.", style = SafetyStepTokens.label)
}

@Composable internal fun ReceiverSettings(s: PrototypeUi, model: PrototypeViewModel) {
    CheckLine("주변 위험 알림 받기", s.receiverEnabled, model::setReceiverEnabled, "receiver-enabled", !s.drivingRestricted)
    Text("수신 ${receiverLabel(s.receiverStatus)}", Modifier.testTag("receiver-status"), style = SafetyStepTokens.label, color = LocalSafetyColors.current.blue)
    if (s.receiverDetail.isNotBlank()) Text(s.receiverDetail, style = SafetyStepTokens.caption, color = LocalSafetyColors.current.secondary)
}

internal fun receiverLabel(status: ReceiverStatus): String = when (status) {
    ReceiverStatus.OFF -> "꺼짐"
    ReceiverStatus.ACTIVE -> "켜짐 · 확인 중"
    ReceiverStatus.PAUSED -> "일시 중지"
    ReceiverStatus.UNAVAILABLE -> "연결·위치 확인 필요"
}

@Composable private fun DrivingContent(s: PrototypeUi) {
    val c = LocalSafetyColors.current
    val ownSupport = s.incident != null
    val warning = !ownSupport && s.warningCards.isNotEmpty()
    val freshWarning = s.warningCards.any { it.freshness == EventFreshness.FRESH }
    SafetyMark(Modifier.size(100.dp), if (warning) c.amber else c.blue)
    Spacer(Modifier.height(28.dp))
    Hero(if (warning && !freshWarning) "전방 알림\n현재 상태 확인 필요" else if (warning) "전방 위험\n속도를 줄이세요" else if (ownSupport) "운전에 집중하세요" else "안전운전 하세요",
        if (warning) "앞차와의 거리를 확보하세요." else if (ownSupport) "안전하게 정차한 후 안내를 이어갈 수 있어요." else "주변 위험 수신 ${receiverLabel(s.receiverStatus)}")
    if (warning) WarningCards(s.warningCards.take(1), compact = true)
}

@Composable private fun SafetyDialog(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.92f).heightIn(max = 1100.dp), shape = RoundedCornerShape(28.dp), color = LocalSafetyColors.current.panel) {
            Column(Modifier.padding(32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(title, style = SafetyStepTokens.action)
                content()
            }
        }
    }
}

@Composable private fun StatusBand(s: PrototypeUi) {
    val c = LocalSafetyColors.current
    val occupant = when {
        s.incident?.help == HelpState.NEEDS_HELP -> "도움 필요"
        s.incident == null -> if (s.drivingRestricted) "주행 중" else "정차 중"
        s.cabinClearConfirmed -> "관측 좌석 비움"
        s.vehicle.seats.any { it.occupied.current(s.now) == true } -> "좌석 잔류 관측"
        else -> "좌석 확인 필요"
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HorizontalDivider(color = c.line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StatusCell("내 차량", occupant, if (s.incident?.help == HelpState.NEEDS_HELP) c.red else c.text, Modifier.weight(1f))
            StatusCell("주변 위험 수신", receiverLabel(s.receiverStatus), if (s.warningCards.isNotEmpty()) c.amber else c.secondary, Modifier.weight(1f))
        }
    }
}

@Composable private fun StatusCell(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = SafetyStepTokens.caption, color = LocalSafetyColors.current.secondary)
        Text(value, style = SafetyStepTokens.label, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable private fun ReceiverContent(s: PrototypeUi, model: PrototypeViewModel) {
    Hero("주변 위험 알림")
    ReceiverSettings(s, model)
    if (s.warningCards.isNotEmpty()) WarningCards(s.warningCards)
    else Text(if (s.receiverStatus == ReceiverStatus.ACTIVE) "현재 표시할 위험 알림이 없어요. 도로 상태를 계속 살펴주세요." else "알림을 받으려면 수신 상태를 확인해주세요.", style = SafetyStepTokens.body, color = LocalSafetyColors.current.secondary)
    Text("TEST 위치 기반 알림", style = SafetyStepTokens.caption, color = LocalSafetyColors.current.secondary)
    LinkAction("내 차량 화면으로") { model.receiverView(false) }
}

@Composable internal fun WarningCards(cards: List<WarningCard>, compact: Boolean = false) {
    val c = LocalSafetyColors.current
    cards.forEach { card ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(SafetyStepTokens.radius)).background(c.inset).padding(20.dp).testTag("warning-${card.incidentId}"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (card.eventType == EventType.ACCIDENT) "전방 사고 알림" else "전방 정차 차량", style = SafetyStepTokens.action, color = c.amber)
            Text(card.distanceMeters?.takeIf { it.isFinite() && it > 0 }?.let { "${kotlin.math.ceil(it).toInt()} m 앞" } ?: "거리 확인 필요",
                style = if (compact) SafetyStepTokens.action else SafetyStepTokens.hero, modifier = Modifier.testTag("warning-distance-${card.incidentId}"))
            Text(when (card.freshness) {
                EventFreshness.FRESH -> "최근 받은 TEST 알림"
                EventFreshness.STALE -> "오래된 알림 · 현재 상태 확인 필요"
                EventFreshness.UNCONFIRMED -> "최신 상태 확인 안 됨"
            }, style = SafetyStepTokens.caption, color = c.secondary)
            if (!compact) Text(when (card.source) {
                "STOP_OBSERVATION" -> "TEST · 정차 관측"
                "SDK_AIRBAG" -> "TEST · 차량 사고 입력"
                else -> "TEST · 시험 입력"
            }, style = SafetyStepTokens.caption, color = c.secondary)
            if (card.voiceHeld) Text("저속 정차 중 · 음성 안내 보류", style = SafetyStepTokens.caption, color = c.secondary)
            if (!compact) Text("속도를 줄이고 앞차와 거리를 확보하세요.", style = SafetyStepTokens.label)
        }
    }
}

@Composable private fun ToolsDialog(s: PrototypeUi, model: PrototypeViewModel, close: () -> Unit, diagnostics: () -> Unit) {
    SafetyDialog("설정", close) {
        Text("화면 테마", style = SafetyStepTokens.label)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemePreference.entries.forEach { mode ->
                OutlinedButton(onClick = { model.setTheme(mode); close() }, modifier = Modifier.weight(1f).heightIn(min = SafetyStepTokens.touch).testTag("theme-${mode.name.lowercase()}")) {
                    Text(mode.label, style = SafetyStepTokens.label)
                }
            }
        }
        ReceiverSettings(s, model)
        HorizontalDivider()
        Text("개발 도구 · TEST", style = SafetyStepTokens.label)
        CheckLine("TEST 위험 위치 공유", s.consent, model::setConsent, "share")
        CheckLine("시험 서버 연결", s.online, model::setOnline, "network")
        SecondaryAction("접근 차량 보기", "receiver-view") { model.receiverView(true); close() }
        SecondaryAction("상태 초기화", "reset-demo") { model.newLocalDemo(); close() }
        SecondaryAction("차량·음성 진단") { diagnostics() }
        SecondaryAction("닫기") { close() }
    }
}
