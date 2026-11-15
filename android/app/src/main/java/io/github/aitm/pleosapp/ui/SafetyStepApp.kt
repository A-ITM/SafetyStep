package io.github.aitm.pleosapp.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import io.github.aitm.pleosapp.speech.VoicePhase
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aitm.pleosapp.BuildConfig
import io.github.aitm.pleosapp.prototype.*
import io.github.aitm.pleosapp.vehicle.CabinPolicy
import io.github.aitm.pleosapp.vehicle.CabinPresence

/** This composable owns one application window. Driving view and navigation belong to Pleos. */
@Composable
fun SafetyStepApp(model: PrototypeViewModel, diagnostics: @Composable () -> Unit) {
    val s by model.state.collectAsStateWithLifecycle()
    var diagnostic by rememberSaveable { mutableStateOf(false) }
    var tools by rememberSaveable { mutableStateOf(false) }
    var trunkConfirmation by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.drivingRestricted, s.cabinClearConfirmed) {
        if (s.drivingRestricted || s.cabinClearConfirmed) { tools = false; trunkConfirmation = false; diagnostic = false }
    }
    SafetyStepTheme(s.theme) {
        val c = LocalSafetyColors.current
        Surface(Modifier.fillMaxSize(), color = c.panel) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val padding = if (maxWidth < 600.dp) SafetyStepTokens.compactGutter else SafetyStepTokens.gutter
                Column(Modifier.fillMaxSize().padding(horizontal = padding, vertical = 24.dp)) {
                    AppHeader({ tools = true }, diagnostic, !s.drivingRestricted && !s.cabinClearConfirmed) { diagnostic = false }
                    Spacer(Modifier.height(24.dp))
                    if (s.drivingRestricted) {
                        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) { DrivingContent(s) }
                        StatusBand(s)
                    }
                    else if (diagnostic) Box(Modifier.weight(1f)) { diagnostics() }
                    else if (!BuildConfig.DEBUG) Text("디버그 프로토타입에서 실행해주세요.")
                    else {
                        val screen = SupportPolicy.screen(s.incident, s.journey, s.cabinClearConfirmed)
                        Column(
                            Modifier.weight(1f).fillMaxWidth().testTag("screen-${if (s.receiverView) "RECEIVER" else screen.name}")
                                .verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            if (s.receiverView) ReceiverContent(s, model)
                            else SupportContent(s, model) {
                                val reason = SupportPolicy.trunkBlock(s.journey, s.vehicle, System.currentTimeMillis(), true)
                                if (reason == null) trunkConfirmation = true else model.openTrunk()
                            }
                        }
                        StatusBand(s)

                    }
                }
            }
        }
        if (tools && !s.drivingRestricted) ToolsDialog(s, model, { tools = false }) { tools = false; diagnostic = true }
        if (trunkConfirmation && !s.drivingRestricted) SafetyDialog("트렁크를 열까요?", { trunkConfirmation = false }) {
            Text("사람이나 장애물이 없고 개방 공간이 확보됐을 때 요청하세요. 확인하려고 차 뒤로 이동하지 마세요.", style = SafetyStepTokens.body)
            Action("조건 확인 · 열기 요청", "trunk-confirm") { trunkConfirmation = false; model.openTrunk(true) }
            SecondaryAction("보류하기") { trunkConfirmation = false }
        }
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

@Composable private fun SupportContent(s: PrototypeUi, model: PrototypeViewModel, requestTrunk: () -> Unit) {
    val c = LocalSafetyColors.current
    when (SupportPolicy.screen(s.incident, s.journey, s.cabinClearConfirmed)) {
        SupportScreen.DRIVE -> {
            Spacer(Modifier.height(36.dp))
            SafetyMark(Modifier.size(142.dp), c.mint)
            Hero("안전운전 하세요", "오늘도 안전한 이동을 함께할게요.")
            Spacer(Modifier.height(12.dp))
            Action("사고·고장 도움받기", "start-support") { model.begin() }

        }
        SupportScreen.INTAKE -> {
            StageLabel("01  상황 확인", "안전 지원")
            Hero("어떤 상황인가요?", "확실하지 않으면 ‘아직 몰라요’를 선택하세요.")
            CollisionContext.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { item ->
                        OutlinedButton(onClick = { model.chooseSituation(item) },
                            modifier = Modifier.weight(1f).heightIn(min = 92.dp).testTag("collision-${item.name}"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (s.journey.collision == item) c.inset else Color.Transparent,
                                contentColor = if (s.journey.collision == item) c.blue else c.text,
                            )) { Text(item.label, fontSize = 26.sp) }
                    }
                }
            }
            if (s.journey.collision == CollisionContext.REAR) InlineNote("후방 충돌 · 트렁크 열기 보류", c.amber)

        }
        else -> IncidentDashboard(s, model, requestTrunk)

    }
}

@Composable internal fun RoadNotice(s: PrototypeUi) {
    val message = when {
        !s.consent -> "주변 차량에 위험을 알리려면 위치 공유를 켜주세요"
        !s.online || !s.connected || s.pending > 0 -> "연결되면 주변 차량에 위험을 알릴게요"
        else -> "뒤따르는 차량에 위험을 알리고 있어요"
    }
    InlineNote(message, LocalSafetyColors.current.amber)
}

@Composable private fun StageLabel(stage: String, detail: String) {
    val c = LocalSafetyColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stage, style = SafetyStepTokens.label, fontWeight = FontWeight.SemiBold, color = c.blue)
        Spacer(Modifier.weight(1f))
        Text(detail, style = SafetyStepTokens.caption, color = c.secondary)
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

@Composable internal fun HelpAction(s: PrototypeUi, model: PrototypeViewModel) {
    LinkAction("도움이 필요해요", "urgent-help", LocalSafetyColors.current.red) {
        model.respond(s.incident?.responder ?: Responder.UNKNOWN, HelpState.NEEDS_HELP)
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

@Composable private fun VoiceCard(s: PrototypeUi, model: PrototypeViewModel) {
    val c = LocalSafetyColors.current
    val active = s.voicePhase in listOf(VoicePhase.PREPARING, VoicePhase.SPEAKING, VoicePhase.LISTENING)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(SafetyStepTokens.radius)).background(c.inset).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(when (s.voicePhase) {
            VoicePhase.PREPARING -> "음성을 연결하고 있어요"
            VoicePhase.SPEAKING -> "제 말을 들어주세요"
            VoicePhase.LISTENING -> "답변을 듣고 있어요"
            VoicePhase.UNAVAILABLE -> "화면으로 답해주세요"
            else -> "음성으로 답할 수 있어요"
        }, style = SafetyStepTokens.label, fontWeight = FontWeight.SemiBold, color = c.blue)
        if (s.voiceText.isNotBlank()) Text("“${s.voiceText}”", style = SafetyStepTokens.label)
        if (s.voiceDetail.isNotBlank()) Text(if (s.voicePhase == VoicePhase.UNAVAILABLE) "음성 연결이 원활하지 않아요. 화면으로 답해주세요." else s.voiceDetail,
            style = SafetyStepTokens.caption, color = c.secondary)
        LinkAction(if (active) "화면으로 답하기" else "음성으로 답하기", "voice-action") {
            if (active) model.pauseVoice() else model.requestVoice()
        }
    }
}

@Composable private fun TrunkCard(s: PrototypeUi, request: () -> Unit) {
    val c = LocalSafetyColors.current
    val reason = SupportPolicy.trunkBlock(s.journey, s.vehicle, s.now, true)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(SafetyStepTokens.radius)).border(1.dp, c.line, RoundedCornerShape(SafetyStepTokens.radius)).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("트렁크", style = SafetyStepTokens.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (s.journey.collision == CollisionContext.REAR) {
                Text("개방 보류", style = SafetyStepTokens.label, color = c.amber,
                    modifier = Modifier.heightIn(min = SafetyStepTokens.touch).wrapContentHeight())
            } else TextButton(onClick = request, enabled = !s.trunkBusy, shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.textButtonColors(containerColor = Color.Transparent),
                modifier = Modifier.testTag("trunk-request").heightIn(min = SafetyStepTokens.touch).widthIn(min = 160.dp)) {
                Text(if (s.trunkBusy) "열림 확인 중" else "열기 요청", style = SafetyStepTokens.label)
            }
        }
        Text(when {
            s.journey.trunk == ControlState.IDLE && reason != null -> reason
            s.journey.trunk == ControlState.IDLE -> "개방 공간이 확보됐을 때 요청하세요"
            else -> s.journey.trunkNote.replace("샘플 열림 확인 · 실제 차량 제어 없음", "트렁크가 열렸어요")
        }, style = SafetyStepTokens.caption, color = if (s.journey.trunk == ControlState.CONFIRMED) c.mint else if (s.journey.collision == CollisionContext.REAR) c.amber else c.secondary)
        Spacer(Modifier.height(8.dp))
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

@Composable private fun DrivingContent(s: PrototypeUi) {
    val c = LocalSafetyColors.current
    val warning = s.receiverView && s.online && (s.previewHazard || s.connected) && s.warnings.isNotEmpty()
    SafetyMark(Modifier.size(100.dp), if (warning) c.amber else c.blue)
    Spacer(Modifier.height(32.dp))
    Hero(if (warning) "전방 위험\n속도를 줄이세요" else if (s.incident == null) "안전운전 하세요" else "운전에 집중하세요",
        if (warning) "앞차와의 거리를 확보하세요." else if (s.incident == null) "오늘도 안전한 이동을 함께할게요." else "안전하게 정차한 후\n안내를 이어갈 수 있어요.")
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
        s.incident == null -> if (s.drivingRestricted) "주행 중" else "정차 중"
        s.cabinClearConfirmed -> "모든 좌석 비움"
        s.journey.departureDeclared && s.vehicle.seats.any { it.occupied.current(s.now) == true } -> "탑승 ${s.vehicle.seats.count { it.occupied.current(s.now) == true }}석"
        s.incident.help == HelpState.NEEDS_HELP -> "도움 필요"
        s.incident.evacuated -> "안전한 곳으로 이동"
        s.journey.departureDeclared -> "이동 중"
        s.incident.response == ResponseState.RESPONDED -> "응답 확인"
        s.incident.response == ResponseState.UNAVAILABLE -> "음성 오류"
        else -> "응답 확인 중"
    }
    val road = when {
        !s.online -> "연결 확인 필요"
        s.receiverView && (s.receiverScenario == "stale" || (!s.previewHazard && !s.connected)) -> "연결 확인 필요"
        s.receiverView && s.warnings.isNotEmpty() -> if (s.previewHazard) "전방 위험" else "전방 위험"
        s.receiverView -> "새 알림 없음"
        s.incident?.hazard == HazardState.ACTIVE && !s.consent -> "위치 공유 꺼짐"
        s.incident?.hazard == HazardState.ACTIVE && (!s.connected || s.pending > 0) -> "연결 확인 중"
        s.incident?.hazard == HazardState.ACTIVE -> "위험 알림 유지"
        else -> "새 알림 없음"
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HorizontalDivider(color = c.line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StatusCell(if (s.receiverView) "화면" else if (s.incident == null) "내 차량" else "탑승자", if (s.receiverView) "접근 차량" else occupant, c.text, Modifier.weight(1f))
            StatusCell("도로 위험", road, if (s.incident?.hazard == HazardState.ACTIVE || !s.online) c.amber else c.secondary, Modifier.weight(1f))
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
    val c = LocalSafetyColors.current
    val current = s.online && (s.previewHazard || s.connected) && s.receiverScenario != "stale"
    StageLabel("주변 위험 알림", "주변 위험")
    if (!current) Hero("연결을 확인하고 있어요", "연결과 위치가 돌아오면 다시 확인해요.")
    else if (s.warnings.isNotEmpty()) {
        Canvas(Modifier.size(130.dp)) {
            val p = Path().apply { moveTo(size.width / 2, 6f); lineTo(size.width - 4f, size.height - 8f); lineTo(4f, size.height - 8f); close() }
            drawPath(p, c.amber, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
            drawLine(c.amber, Offset(size.width / 2, size.height * .38f), Offset(size.width / 2, size.height * .64f), 8.dp.toPx(), StrokeCap.Round)
            drawCircle(c.amber, 5.dp.toPx(), Offset(size.width / 2, size.height * .79f))
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("400", fontSize = 112.sp, lineHeight = 126.sp, fontWeight = FontWeight.Bold)
            Text("m", fontSize = 42.sp, color = c.secondary, modifier = Modifier.padding(bottom = 16.dp))
        }
        Hero("전방 정차 차량", "속도를 줄이고\n앞차와 거리를 확보하세요.")
        InlineNote("같은 도로 · 같은 진행 방향", c.amber)
        Text("같은 방향의 전방 도로", style = SafetyStepTokens.caption, color = c.secondary)
    } else Hero("현재 표시할\n위험 알림이 없어요", "안전운전 하세요.")
    LinkAction("내 차량 화면으로") { model.receiverView(false) }
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
        HorizontalDivider()
        Text("개발 도구", style = SafetyStepTokens.label)
        SecondaryAction("상태 초기화", "reset-demo") { model.newLocalDemo(); close() }
        SecondaryAction("접근 차량 보기") { model.previewApproachingHazard(); close() }
        SecondaryAction("차량·음성 진단") { diagnostics() }
        CheckLine("로컬 사건 공유", s.consent, model::setConsent, "share")
        CheckLine("서버 연결", s.online, model::setOnline, "network")
        SecondaryAction("닫기") { close() }
    }
}
