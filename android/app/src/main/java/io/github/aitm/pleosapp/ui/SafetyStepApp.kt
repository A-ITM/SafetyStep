package io.github.aitm.pleosapp.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aitm.pleosapp.BuildConfig
import io.github.aitm.pleosapp.accident.*
import io.github.aitm.pleosapp.prototype.*

/** This composable owns one application window. Navigation belongs to Pleos. */
@Composable
fun SafetyStepApp(model: PrototypeViewModel, diagnostics: @Composable () -> Unit) {
    val s by model.state.collectAsStateWithLifecycle()
    var diagnostic by rememberSaveable { mutableStateOf(false) }
    var tools by rememberSaveable { mutableStateOf(false) }
    var lastIncidentId by remember { mutableStateOf<String?>(null) }
    val cabinQuiet = s.incident != null && s.cabinClearConfirmed
    LaunchedEffect(s.drivingRestricted, cabinQuiet) {
        if (s.drivingRestricted || cabinQuiet) { tools = false; diagnostic = false }
    }
    LaunchedEffect(s.incident?.id, s.incident?.help) {
        val id = s.incident?.id
        if (id != null && (id != lastIncidentId || s.incident?.help == HelpState.NEEDS_HELP)) {
            tools = false; diagnostic = false
        }
        lastIncidentId = id
    }
    SafetyStepTheme(s.theme) {
        val c = LocalSafetyColors.current
        Surface(Modifier.fillMaxSize(), color = c.panel) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val padding = if (maxWidth < 600.dp) SafetyStepTokens.compactGutter else SafetyStepTokens.gutter
                Column(Modifier.fillMaxSize().padding(horizontal = padding, vertical = 16.dp)) {
                    AppHeader({ tools = true }, diagnostic, !s.drivingRestricted && !cabinQuiet) { diagnostic = false }
                    Spacer(Modifier.height(SafetyStepTokens.gap))
                    when {
                        s.drivingRestricted -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { DrivingContent(s) }
                        diagnostic && !cabinQuiet -> Box(Modifier.weight(1f).fillMaxWidth()) { diagnostics() }
                        !BuildConfig.DEBUG -> Box(Modifier.weight(1f).fillMaxWidth()) { Text("디버그 프로토타입에서 실행해주세요.") }
                        else -> {
                            val receiver = s.receiverView && s.incident == null
                            val screen = when {
                                receiver -> "RECEIVER"
                                s.incident == null -> "DRIVE"
                                cabinQuiet -> "MONITORING"
                                s.incident?.help == HelpState.NEEDS_HELP -> "HELP"
                                else -> "DASHBOARD"
                            }
                            // The dashboard owns its bounded body and pinned support actions.
                            Box(Modifier.weight(1f).fillMaxWidth().testTag("screen-$screen")) {
                                if (s.incident != null) IncidentDashboard(s, model)
                                else if (receiver) ReceiverContent(s, model)
                                else HomeContent(s, model)
                            }
                        }
                    }
                    if (!s.drivingRestricted && !cabinQuiet && s.incident?.eventType == EventType.STOPPED_VEHICLE && s.stopReportStatus != StopReportStatus.NONE) {
                        Spacer(Modifier.height(16.dp))
                        StopReportCard(s)
                    }
                }
            }
        }
        if (tools && !s.drivingRestricted && !cabinQuiet) ToolsDialog(s, model, { tools = false }) { tools = false; diagnostic = true }
    }
}

@Composable private fun AppHeader(openTools: () -> Unit, diagnostic: Boolean, editable: Boolean, closeDiagnostic: () -> Unit) {
    val c = LocalSafetyColors.current
    Row(Modifier.fillMaxWidth().height(SafetyStepTokens.touch), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SafetyStepTokens.smallGap)) {
        SafetyShield(Modifier.size(SafetyStepTokens.brandSymbol), c.blue)
        Text("세이프티스탭", Modifier.weight(1f), fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium,
            color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Surface(color = c.inset, shape = RoundedCornerShape(SafetyStepTokens.badgeRadius), modifier = Modifier.testTag("test-badge")) {
            Text("TEST", fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                color = c.secondary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        if (editable) TextButton(onClick = if (diagnostic) closeDiagnostic else openTools,
            modifier = Modifier.testTag(if (diagnostic) "close-diagnostics" else "tools").size(SafetyStepTokens.touch),
            shape = RoundedCornerShape(SafetyStepTokens.radius),
            colors = ButtonDefaults.textButtonColors(containerColor = Color.Transparent)) {
            Text(if (diagnostic) "지원" else "설정", style = SafetyStepTokens.label, color = c.secondary)
        }
    }
}

@Composable private fun HomeContent(s: PrototypeUi, model: PrototypeViewModel) {
    val warning = preferredNearbyAccident(s.accidentNetwork.nearby)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(SafetyStepTokens.gap)) {
        if (warning != null) NearbyAccidentWarning(warning) else Row(Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SafetyStepTokens.gap)) {
            SafetyShield(Modifier.size(SafetyStepTokens.homeSymbol), LocalSafetyColors.current.blue.copy(alpha = .8f), strokeWidth = 2.5.dp)
            Text("안전운전 하세요", Modifier.weight(1f), style = SafetyStepTokens.hero)
        }
        ReceiverSettings(s, model)
        Spacer(Modifier.weight(1f))
        Action("사고 도움받기", "start-support") { model.begin(eventType = EventType.ACCIDENT) }
    }
}

@Composable internal fun RoadNotice(s: PrototypeUi) = StopReportCard(s)

@Composable internal fun StopReportCard(s: PrototypeUi) {
    if (s.incident?.eventType == EventType.ACCIDENT || s.stopReportStatus == StopReportStatus.NONE) return
    val c = LocalSafetyColors.current
    val message = when (s.stopReportStatus) {
        StopReportStatus.NONE -> ""
        StopReportStatus.CANDIDATE -> "정차 상태 확인 중"
        StopReportStatus.HELD -> "위험 위치 공유 보류"
        StopReportStatus.QUEUED -> "위험 위치 공유 대기"
        StopReportStatus.SHARED -> "위험 위치 공유 확인"
        StopReportStatus.RETRACTED -> "재출발 · 정차 알림 철회됨"
        StopReportStatus.REJECTED -> "위험 위치 공유 실패"
    }
    Text(message, Modifier.fillMaxWidth().testTag("stop-report-status"), style = SafetyStepTokens.label,
        color = if (s.stopReportStatus == StopReportStatus.SHARED) c.blue else c.secondary)
}

@Composable internal fun Hero(title: String, body: String = "") {
    val c = LocalSafetyColors.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = SafetyStepTokens.hero)
        if (body.isNotEmpty()) Text(body, color = c.secondary, style = SafetyStepTokens.body)
    }
}

@Composable internal fun Action(label: String, tag: String = label, enabled: Boolean = true, danger: Boolean = false, action: () -> Unit) {
    val c = LocalSafetyColors.current
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = SafetyStepTokens.primaryHeight).testTag(tag),
        shape = RoundedCornerShape(SafetyStepTokens.radius), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) Color(0xFFC92A30) else MaterialTheme.colorScheme.primary,
            contentColor = if (danger) Color.White else MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = c.inset, disabledContentColor = c.secondary)) {
        Text(label, style = SafetyStepTokens.action)
    }
}

@Composable internal fun SecondaryAction(label: String, tag: String = label, action: () -> Unit) {
    val c = LocalSafetyColors.current
    OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = SafetyStepTokens.secondaryHeight).testTag(tag),
        shape = RoundedCornerShape(SafetyStepTokens.radius), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text, containerColor = c.inset)) {
        Text(label, style = SafetyStepTokens.action)
    }
}

@Composable internal fun LinkAction(label: String, tag: String = label, color: Color = LocalSafetyColors.current.blue, action: () -> Unit) {
    TextButton(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = SafetyStepTokens.touch).testTag(tag),
        shape = RoundedCornerShape(SafetyStepTokens.radius), colors = ButtonDefaults.textButtonColors(containerColor = Color.Transparent)) {
        Text(label, color = color, style = SafetyStepTokens.label)
    }
}

@Composable private fun CheckLine(label: String, checked: Boolean, change: (Boolean) -> Unit, tag: String, enabled: Boolean = true,
    status: String? = null,
) {
    val c = LocalSafetyColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = SafetyStepTokens.touch)
        .clip(RoundedCornerShape(SafetyStepTokens.radius)).background(if (checked) c.blue.copy(alpha = .07f) else c.inset)
        .border(1.dp, if (checked) c.blue.copy(alpha = .35f) else c.line, RoundedCornerShape(SafetyStepTokens.radius))
        .testTag(tag).toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = change)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = SafetyStepTokens.label, color = if (enabled) c.text else c.secondary)
            if (status != null) Text(status, Modifier.testTag("receiver-status"), style = SafetyStepTokens.label,
                color = if (checked && enabled) c.blue else c.secondary)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable internal fun ContactActions() {
    val context = LocalContext.current
    var failure by remember { mutableStateOf(false) }
    Action("119 전화 열기", "contact-phone", danger = true) {
        failure = runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:119"))) }.isFailure
    }
    if (failure) Text("차량의 전화 메뉴에서 긴급 전화를 이용해주세요.", style = SafetyStepTokens.label)
}

@Composable internal fun ReceiverSettings(s: PrototypeUi, model: PrototypeViewModel) {
    CheckLine("주변 위험 알림", s.receiverEnabled, model::setReceiverEnabled, "receiver-enabled", !s.drivingRestricted,
        status = receiverLabel(if (s.receiverView) s.receiverStatus else s.accidentNetwork.receiverStatus))
}

internal fun receiverLabel(status: ReceiverStatus): String = when (status) {
    ReceiverStatus.OFF -> "꺼짐"
    ReceiverStatus.ACTIVE -> "수신 중"
    ReceiverStatus.PAUSED -> "일시 중지 · 앱을 열어 재개"
    ReceiverStatus.UNAVAILABLE -> "연결·위치 확인 필요"
}

/** One selection policy for both parked and driving views, independent from server order. */
internal fun preferredWarning(cards: List<WarningCard>): WarningCard? = cards.minWithOrNull(
    compareBy<WarningCard> { if (it.freshness == EventFreshness.FRESH) 0 else 1 }
        .thenBy { it.distanceMeters?.takeIf { distance -> distance.isFinite() && distance > 0 } ?: Double.MAX_VALUE }
        .thenBy { it.freshness.ordinal }.thenBy { it.incidentId },
)

/** Coordinates establish proximity, not a shared road or the direction of travel. */
internal fun preferredNearbyAccident(events: List<NearbyAccident>): NearbyAccident? = events
    .filter { it.event.status == "ACTIVE" }
    .minWithOrNull(compareBy<NearbyAccident> { if (it.freshness == EventFreshness.FRESH) 0 else 1 }
        .thenBy { it.distanceMeters?.takeIf { distance -> distance.isFinite() && distance >= 0 } ?: Double.MAX_VALUE }
        .thenBy { it.freshness.ordinal }.thenBy { it.event.id })

@Composable internal fun DrivingContent(s: PrototypeUi) {
    val guidance = supportGuidance(s)
    if (s.incident != null && guidance.stage == SupportGuidanceStage.QUIET) {
        Box(Modifier.fillMaxWidth().testTag("screen-DRIVING")) { QuietCabinContent(s) }
        return
    }
    val warning = preferredNearbyAccident(s.accidentNetwork.nearby)
    val legacyWarning = if (s.receiverView) preferredWarning(s.warningCards) else null
    Column(Modifier.fillMaxWidth().testTag("screen-DRIVING"), verticalArrangement = Arrangement.spacedBy(SafetyStepTokens.gap)) {
        AccidentContext(s)
        when {
            s.incident != null -> {
                Hero(guidance.heading, guidance.body)
                if (s.incident.eventType == EventType.ACCIDENT) {
                    VehicleStatusRow(s)
                    AccidentShareCard(s.accidentNetwork, s.now, {}, drivingRestricted = true)
                }
            }
            legacyWarning != null -> WarningCards(listOf(legacyWarning), compact = true)
            warning != null -> NearbyAccidentWarning(warning, compact = true)
            else -> {
                Hero("운전에 집중하세요")
                Text("주변 위험 알림 ${receiverLabel(if (s.receiverView) s.receiverStatus else s.accidentNetwork.receiverStatus)}",
                    style = SafetyStepTokens.label, color = LocalSafetyColors.current.secondary)
            }
        }
    }
}

@Composable internal fun NearbyAccidentWarning(accident: NearbyAccident, compact: Boolean = false) {
    val c = LocalSafetyColors.current
    val distance = accident.distanceMeters?.takeIf { it.isFinite() && it >= 0 }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(SafetyStepTokens.radius)).background(c.inset)
        .padding(if (compact) 20.dp else SafetyStepTokens.cardPadding).testTag("nearby-accident-${accident.event.id}"),
        verticalArrangement = Arrangement.spacedBy(SafetyStepTokens.smallGap)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WarningTriangle(Modifier.size(SafetyStepTokens.warningSymbol), c.amber)
            Text("주변 사고", style = SafetyStepTokens.action, color = c.amber)
        }
        Text(distance?.let { "약 ${kotlin.math.ceil(it).toInt()} m" } ?: "거리 확인 필요",
            style = if (distance != null) SafetyStepTokens.distance else SafetyStepTokens.body,
            modifier = Modifier.testTag("nearby-accident-distance-${accident.event.id}"))
        if (accident.freshness != EventFreshness.FRESH) Text(
            if (accident.freshness == EventFreshness.STALE) "오래된 사고 알림 · 최신 상태 미확인" else "최신 상태 미확인",
            style = SafetyStepTokens.label, color = c.amber)
        Text(if (accident.freshness == EventFreshness.FRESH) "직선 거리 · 도로 방향 미확인" else "이전 확인 위치 기준 · 도로 방향 미확인",
            style = SafetyStepTokens.caption, color = c.secondary)
        Text("속도를 줄이고 주변을 살피세요.", style = SafetyStepTokens.body)
        if (accident.event.location.mock || accident.event.location.origin == LocationOrigin.TEST) {
            Text("시험 위치 기반 알림", style = SafetyStepTokens.caption, color = c.secondary)
        }
    }
}

@Composable internal fun SafetyDialog(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.92f).heightIn(max = 1100.dp),
            shape = RoundedCornerShape(SafetyStepTokens.dialogRadius), color = LocalSafetyColors.current.panel) {
            Column(Modifier.padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SafetyStepTokens.gap)) {
                    Text(title, Modifier.weight(1f), style = SafetyStepTokens.action)
                    TextButton(onClick = close, modifier = Modifier.size(SafetyStepTokens.touch).testTag("dialog-close")) {
                        Text("닫기", style = SafetyStepTokens.label)
                    }
                }
                Spacer(Modifier.height(SafetyStepTokens.gap))
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(SafetyStepTokens.gap), content = content)
            }
        }
    }
}

@Composable private fun ReceiverContent(s: PrototypeUi, model: PrototypeViewModel) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(SafetyStepTokens.gap)) {
        preferredWarning(s.warningCards)?.let { WarningCards(listOf(it)) }
            ?: Hero("주변 위험 알림")
        ReceiverSettings(s, model)
        Spacer(Modifier.weight(1f))
        SecondaryAction("내 차량 화면으로") { model.receiverView(false) }
    }
}

@Composable internal fun WarningCards(cards: List<WarningCard>, compact: Boolean = false) {
    val c = LocalSafetyColors.current
    cards.forEach { card ->
        val distance = card.distanceMeters?.takeIf { it.isFinite() && it > 0 }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(SafetyStepTokens.radius)).background(c.inset)
            .padding(if (compact) 20.dp else SafetyStepTokens.cardPadding).testTag("warning-${card.incidentId}"),
            verticalArrangement = Arrangement.spacedBy(SafetyStepTokens.smallGap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                WarningTriangle(Modifier.size(SafetyStepTokens.warningSymbol), c.amber)
                Text(if (card.eventType == EventType.ACCIDENT) "전방 사고 알림" else "전방 정차 차량", style = SafetyStepTokens.action, color = c.amber)
            }
            Text(distance?.let { "${kotlin.math.ceil(it).toInt()} m 앞" } ?: "거리 확인 필요",
                style = if (distance != null) SafetyStepTokens.distance else SafetyStepTokens.body,
                modifier = Modifier.testTag("warning-distance-${card.incidentId}"))
            if (card.freshness != EventFreshness.FRESH) Text(
                if (card.freshness == EventFreshness.STALE) "오래된 알림 · 확인 필요" else "현재 상태 확인 필요",
                style = SafetyStepTokens.label, color = c.secondary)
            Text("속도를 줄이고 거리를 확보하세요.", style = SafetyStepTokens.body)
            if (card.voiceHeld) Text("저속 정차 중 · 음성 보류", style = SafetyStepTokens.label, color = c.secondary)
        }
    }
}

@Composable private fun ToolsDialog(s: PrototypeUi, model: PrototypeViewModel, close: () -> Unit, diagnostics: () -> Unit) {
    var developer by rememberSaveable { mutableStateOf(false) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.requestAccidentLocationRefresh()
    }
    val c = LocalSafetyColors.current
    SafetyDialog("설정", close) {
        ReceiverSettings(s, model)
        CheckLine("위험 위치 공유 동의", s.consent, model::setConsent, "share")
        Text("공유 동의는 주변 알림 수신과 별개예요.", style = SafetyStepTokens.caption, color = LocalSafetyColors.current.secondary)
        if (s.accidentNetwork.locationPermissionNeeded && !s.drivingRestricted) {
            SecondaryAction("위치 권한 설정", "location-permission") {
                locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }
            Text("사고 위치 공유와 주변 사고 확인에 현재 위치를 사용해요.", style = SafetyStepTokens.caption, color = c.secondary)
        }
        Text("화면 테마", style = SafetyStepTokens.label)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 312.dp) Row(horizontalArrangement = Arrangement.spacedBy(SafetyStepTokens.gap)) {
                ThemePreference.entries.forEach { mode ->
                    OutlinedButton(onClick = { model.setTheme(mode); close() }, modifier = Modifier.weight(1f).height(SafetyStepTokens.touch)
                        .testTag("theme-${mode.name.lowercase()}"), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (s.theme == mode) c.blue.copy(alpha = .10f) else Color.Transparent,
                            contentColor = if (s.theme == mode) c.blue else c.text)) {
                        Text(mode.label, style = SafetyStepTokens.label, maxLines = 1)
                    }
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(SafetyStepTokens.gap)) {
                ThemePreference.entries.forEach { mode ->
                    SecondaryAction(mode.label, "theme-${mode.name.lowercase()}") { model.setTheme(mode); close() }
                }
            }
        }
        HorizontalDivider()
        SecondaryAction(if (developer) "개발 도구 접기" else "개발 도구", "developer-toggle") { developer = !developer }
        if (developer) {
            CheckLine("시험 서버 연결", s.online, model::setOnline, "network")
            SecondaryAction("접근 차량 화면", "receiver-view") { model.receiverView(true); close() }
            SecondaryAction("시험 상태 초기화", "reset-demo") { model.newLocalDemo(); close() }
            SecondaryAction("차량·음성 진단", "diagnostics") { diagnostics() }
        }
    }
}
