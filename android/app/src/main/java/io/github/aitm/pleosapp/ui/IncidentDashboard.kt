package io.github.aitm.pleosapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aitm.pleosapp.accident.*
import io.github.aitm.pleosapp.location.ReportLocation
import io.github.aitm.pleosapp.location.ReportLocationStatus
import io.github.aitm.pleosapp.prototype.*
import io.github.aitm.pleosapp.speech.VoicePhase
import io.github.aitm.pleosapp.vehicle.CabinPolicy
import java.util.Locale

@Composable
internal fun IncidentDashboard(s: PrototypeUi, model: PrototypeViewModel) = IncidentDashboardContent(
    s,
    onHelp = { model.respond(s.incident?.responder ?: Responder.UNKNOWN, HelpState.NEEDS_HELP) },
    onVoice = {
        if (s.voicePhase in listOf(VoicePhase.PREPARING, VoicePhase.SPEAKING, VoicePhase.LISTENING)) model.pauseVoice()
        else model.requestVoice()
    },
    onRefreshLocation = model::refreshReportLocation,
    onRefreshAccidentLocation = model::requestAccidentLocationRefresh,
)

internal enum class SupportGuidanceStage { QUIET, HELP, STOP, PASSENGER, EXIT }
internal data class SupportGuidance(val stage: SupportGuidanceStage, val heading: String, val body: String)

/** The same vehicle and cabin evidence chooses guidance in both interactive and restricted views. */
internal fun supportGuidance(s: PrototypeUi): SupportGuidance = when {
    s.cabinClearConfirmed -> SupportGuidance(SupportGuidanceStage.QUIET, "좌석 비움 관측",
        if (s.cabinEmptyReadingComplete) "" else "현재 좌석 상태 미확인")
    s.incident?.help == HelpState.NEEDS_HELP -> SupportGuidance(SupportGuidanceStage.HELP,
        "무리하게 움직이지 마세요", "가능한 방법으로 도움을 요청하세요.")
    !s.supportQualified || s.vehicle.speedMps.current(s.now)?.let { it.isFinite() && it > .5f } == true ->
        SupportGuidance(SupportGuidanceStage.STOP, "안전하게 정차해주세요", "차량이 멈춘 뒤 주변 상황을 살펴주세요.")
    CabinPolicy.passengerRemains(s.vehicle.seats, s.now, s.occupiedSeatAreas) ->
        SupportGuidance(SupportGuidanceStage.PASSENGER, "동승 좌석 감지됨", CabinGuidance.PASSENGER)
    else -> SupportGuidance(SupportGuidanceStage.EXIT, "안전한 곳으로 이동하세요", CabinGuidance.EXIT)
}

/** Critical action stays visible; only the observation area scrolls on a short window. */
@Composable
internal fun IncidentDashboardContent(
    s: PrototypeUi,
    onHelp: () -> Unit,
    onVoice: () -> Unit,
    onRefreshLocation: () -> Unit,
    onRefreshAccidentLocation: () -> Unit = onRefreshLocation,
) {
    val guidance = supportGuidance(s)
    if (guidance.stage == SupportGuidanceStage.QUIET) {
        Box(Modifier.fillMaxSize().testTag("incident-dashboard")) { QuietCabinContent(s) }
        return
    }
    val c = LocalSafetyColors.current
    val accident = s.incident?.eventType == EventType.ACCIDENT
    val needsHelp = guidance.stage == SupportGuidanceStage.HELP
    val bodyScroll = remember(s.incident?.id, s.incident?.help, guidance.stage) { ScrollState(0) }
    var voiceDetails by remember(s.incident?.id, s.incident?.help, guidance.stage) { mutableStateOf(false) }
    LaunchedEffect(s.drivingRestricted) { if (s.drivingRestricted) voiceDetails = false }

    @Composable fun LocationSummary() = key(guidance.stage) {
        ReportLocationCard(
            s.reportLocation, s.now, onRefreshLocation,
            sessionId = s.incident?.id, help = s.incident?.help, drivingRestricted = s.drivingRestricted,
            locationOrigin = if (accident) (s.accidentNetwork.sharedEvent?.location ?: s.accidentNetwork.currentLocation)?.origin else null,
        )
    }

    Column(Modifier.fillMaxSize().testTag("incident-dashboard")) {
        if (accident) {
            AccidentContext(s)
            Spacer(Modifier.height(10.dp))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(bodyScroll).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(guidance.heading, style = SafetyStepTokens.hero, modifier = Modifier.testTag("support-heading"))
                Text(guidance.body, style = SafetyStepTokens.label, color = c.secondary, modifier = Modifier.testTag("support-guidance"))
                if (guidance.stage == SupportGuidanceStage.PASSENGER &&
                    s.voicePhase in listOf(VoicePhase.UNAVAILABLE, VoicePhase.PAUSED)) Text(
                    "도움이 필요하면 아래 ‘움직이기 어려워요’를 눌러주세요.",
                    style = SafetyStepTokens.caption, color = c.blue, modifier = Modifier.testTag("passenger-help-cue"))
            }
            if (needsHelp) LocationSummary()
            if (accident) {
                VehicleStatusRow(s)
                key(guidance.stage) {
                    AccidentShareCard(s.accidentNetwork, s.now, onRefreshAccidentLocation,
                        sessionId = s.incident?.id, help = s.incident?.help, drivingRestricted = s.drivingRestricted)
                }
            }
            SupportVoiceStatus(s, onDetails = { voiceDetails = true })
            CabinMap(s, preparing = true)
            HazardReadStatus(s)
            if (!needsHelp) LocationSummary()
        }
        if (needsHelp) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp).testTag("support-actions")) { ContactActions() }
        } else {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).testTag("support-actions")) {
                Action("움직이기 어려워요", "urgent-help") { onHelp() }
            }
        }
    }
    if (voiceDetails && !s.drivingRestricted) {
        SafetyDialog("음성 안내", { voiceDetails = false }) {
            Text(voiceLabel(s.voicePhase), style = SafetyStepTokens.action)
            if (s.voiceDetail.isNotBlank()) Text(s.voiceDetail, style = SafetyStepTokens.label, color = c.secondary)
            val active = s.voicePhase in listOf(VoicePhase.PREPARING, VoicePhase.SPEAKING, VoicePhase.LISTENING)
            Action(if (active) "음성 잠시 멈추기" else "음성 응답 다시 시도", "voice-action") {
                voiceDetails = false
                onVoice()
            }
        }
    }
}

/** The departure latch silences cabin prompts; it does not erase help or resolve an accident. */
@Composable internal fun QuietCabinContent(s: PrototypeUi) {
    val c = LocalSafetyColors.current
    val guidance = supportGuidance(s)
    Column(Modifier.fillMaxWidth().testTag("cabin-quiet"),
        verticalArrangement = Arrangement.spacedBy(SafetyStepTokens.gap)) {
        AccidentContext(s)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(guidance.heading, style = SafetyStepTokens.hero, modifier = Modifier.testTag("cabin-passive-status"))
            if (guidance.body.isNotEmpty()) Text(guidance.body,
                style = SafetyStepTokens.label, color = c.secondary)
            if (s.vehicle.sample) Text("샘플 좌석 관측", style = SafetyStepTokens.caption, color = c.secondary)
        }
        if (s.incident?.help == HelpState.NEEDS_HELP) Text("도움 요청 기록 있음",
            style = SafetyStepTokens.label, color = c.secondary, modifier = Modifier.testTag("help-record"))
        if (s.incident?.eventType == EventType.ACCIDENT) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(SafetyStepTokens.radius)).background(c.inset)
                .padding(14.dp).testTag("accident-share"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("사고 위치 공유", style = SafetyStepTokens.caption, color = c.secondary)
                Text(accidentShareLabel(s.accidentNetwork.shareStatus), style = SafetyStepTokens.label,
                    color = if (s.accidentNetwork.shareStatus == AccidentShareStatus.REGISTERED) c.blue else c.secondary,
                    modifier = Modifier.testTag("accident-share-status"))
                val location = s.accidentNetwork.sharedEvent?.location
                if (location?.mock == true || location?.origin == LocationOrigin.TEST) {
                    Text("시험 위치", style = SafetyStepTokens.caption, color = c.secondary)
                }
            }
        } else StopReportCard(s)
    }
}

/** Explicit accident context stays distinct from stop observations and action guidance. */
@Composable internal fun AccidentContext(s: PrototypeUi) {
    if (s.incident?.eventType != EventType.ACCIDENT) return
    val c = LocalSafetyColors.current
    Row(Modifier.fillMaxWidth().testTag("accident-context"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        WarningTriangle(Modifier.size(28.dp), c.amber)
        Text("사고 후 안전 지원", style = SafetyStepTokens.label, color = c.amber, fontWeight = FontWeight.SemiBold)
        if (s.journey.collision in listOf(CollisionContext.FRONT, CollisionContext.REAR, CollisionContext.OTHER)) {
            Text(s.journey.collision.label, style = SafetyStepTokens.caption, color = c.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

/** Only the current vehicle speed reading establishes the displayed vehicle state. */
@Composable internal fun VehicleStatusRow(s: PrototypeUi) {
    val c = LocalSafetyColors.current
    val speed = s.vehicle.speedMps.current(s.now)?.takeIf { it.isFinite() && it >= 0f }
    val stopped = speed != null && speed <= .5f
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("vehicle-motion-status"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (s.vehicle.sample || s.vehicle.speedMps.sample) "차량 상태 · 샘플" else "차량 상태",
            style = SafetyStepTokens.caption, color = c.secondary)
        Spacer(Modifier.weight(1f))
        Text(when { speed == null -> "정지 여부 확인 필요"; stopped -> "정지 확인"; else -> "이동 중" },
            style = SafetyStepTokens.label, color = if (stopped) c.mint else c.amber)
    }
}

internal fun accidentShareLabel(status: AccidentShareStatus): String = when (status) {
    AccidentShareStatus.IDLE -> "공유 대기"
    AccidentShareStatus.CONSENT_REQUIRED -> "공유 동의 필요"
    AccidentShareStatus.LOCATING -> "위치 확인 중 · 공유 보류"
    AccidentShareStatus.QUEUED -> "서버 등록 대기"
    AccidentShareStatus.REGISTERED -> "서버 등록 완료"
    AccidentShareStatus.FAILED -> "사고 위치 공유 실패"
    AccidentShareStatus.EXPIRED -> "공유 유효 시간 지남"
}

@Composable internal fun AccidentShareCard(
    network: AccidentNetworkUi,
    now: Long,
    refresh: () -> Unit,
    sessionId: String? = null,
    help: HelpState? = null,
    drivingRestricted: Boolean = false,
) {
    val c = LocalSafetyColors.current
    var details by remember(sessionId, help) { mutableStateOf(false) }
    LaunchedEffect(drivingRestricted) { if (drivingRestricted) details = false }
    val location = network.sharedEvent?.location ?: network.currentLocation
    val source = when (location?.origin) {
        LocationOrigin.GPS -> if (location.mock) "GPS · 모의 위치" else "GPS"
        LocationOrigin.NAVI -> if (location.mock) "내비게이션 · 모의 위치" else "내비게이션"
        LocationOrigin.TEST -> "시험 입력 위치"
        null -> null
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(SafetyStepTokens.radius)).background(c.inset)
        .padding(start = 14.dp, end = if (drivingRestricted) 14.dp else 4.dp, top = 8.dp, bottom = 8.dp)
        .testTag("accident-share"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("사고 위치 공유", style = SafetyStepTokens.caption, color = c.secondary)
                Text(accidentShareLabel(network.shareStatus), style = SafetyStepTokens.label,
                    color = if (network.shareStatus == AccidentShareStatus.REGISTERED) c.blue else c.amber,
                    modifier = Modifier.testTag("accident-share-status"))
            }
            if (!drivingRestricted) TextButton(onClick = { details = true },
                modifier = Modifier.width(80.dp).heightIn(min = SafetyStepTokens.touch).testTag("accident-share-details")) {
                Text("상세", style = SafetyStepTokens.caption)
            }
        }
        if (network.locationPermissionNeeded) Text(if (drivingRestricted) "정차 후 설정에서 위치 권한을 허용해주세요" else "설정에서 위치 권한을 허용해주세요",
            style = SafetyStepTokens.caption, color = c.secondary)
        if (location?.mock == true || location?.origin == LocationOrigin.TEST) Text(source.orEmpty(),
            style = SafetyStepTokens.caption, color = c.amber)
    }
    if (details && !drivingRestricted) SafetyDialog("사고 위치 공유", { details = false }) {
        Text(accidentShareLabel(network.shareStatus), style = SafetyStepTokens.action)
        Text("서버 등록과 다른 차량의 수신은 별개예요.", style = SafetyStepTokens.label, color = c.secondary)
        if (network.locationPermissionNeeded) Text("설정에서 위치 권한을 허용해주세요.", style = SafetyStepTokens.label, color = c.amber)
        if (location != null) {
            Text("위치 출처 · $source", style = SafetyStepTokens.label)
            location.address?.takeIf { it.isNotBlank() }?.let { Text(it, style = SafetyStepTokens.label) }
            Text(String.format(Locale.KOREA, "위도 %.6f · 경도 %.6f", location.latitude, location.longitude),
                style = SafetyStepTokens.label, modifier = Modifier.testTag("accident-share-coordinates"))
            Text("${(now - location.receivedAt).coerceAtLeast(0) / 1000}초 전 받은 위치", style = SafetyStepTokens.caption, color = c.secondary)
            Text(location.accuracyMeters?.let { "위치 정확도 약 ${kotlin.math.ceil(it).toInt()} m" } ?: "위치 정확도 알 수 없음",
                style = SafetyStepTokens.label)
            if (location.fixObservedAt == null) Text("측위 시각 알 수 없음", style = SafetyStepTokens.caption, color = c.secondary)
            if (network.sharedEvent != null) Text("사고 시점에 받은 위치로 고정해 공유해요.", style = SafetyStepTokens.label, color = c.secondary)
        } else Text("유효한 위치가 확인되기 전에는 전송하지 않아요.", style = SafetyStepTokens.label, color = c.secondary)
        Action("위치 다시 확인", "accident-location-refresh", action = refresh)
    }
}

@Composable private fun SupportVoiceStatus(s: PrototypeUi, onDetails: () -> Unit) {
    val c = LocalSafetyColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = SafetyStepTokens.touch)
            .clip(RoundedCornerShape(SafetyStepTokens.radius)).background(c.inset)
            .padding(start = 14.dp, end = 4.dp).testTag("support-voice"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VoiceGlyph(s.voicePhase)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(voiceLabel(s.voicePhase), style = SafetyStepTokens.label, color = c.blue)
            if (s.voicePhase == VoicePhase.LISTENING) Text("마이크가 켜져 있어요", style = SafetyStepTokens.caption, color = c.secondary)
        }
        TextButton(onClick = onDetails, modifier = Modifier.width(80.dp).heightIn(min = SafetyStepTokens.touch).testTag("voice-details")) {
            Text("음성", style = SafetyStepTokens.caption)
        }
    }
}

private fun voiceLabel(phase: VoicePhase): String = when (phase) {
    VoicePhase.PREPARING -> "음성을 연결하고 있어요"
    VoicePhase.SPEAKING -> "안내 음성 재생 중"
    VoicePhase.LISTENING -> "도움이 필요하면 말씀해주세요"
    VoicePhase.UNAVAILABLE -> "음성을 사용할 수 없어요"
    VoicePhase.PAUSED -> "음성 일시 중지"
    else -> "화면 안내를 확인해주세요"
}

@Composable private fun VoiceGlyph(phase: VoicePhase) {
    val c = LocalSafetyColors.current
    val listening = phase == VoicePhase.LISTENING
    Canvas(Modifier.size(28.dp).semantics { contentDescription = if (listening) "마이크 켜짐" else "음성 상태" }) {
        val color = if (listening) c.mint else c.blue
        if (listening) {
            val stroke = 2.dp.toPx()
            drawRoundRect(color, Offset(size.width * .35f, size.height * .05f), Size(size.width * .3f, size.height * .53f),
                CornerRadius(size.width * .16f), style = Stroke(stroke))
            drawArc(color, 0f, 180f, false, Offset(size.width * .18f, size.height * .2f),
                Size(size.width * .64f, size.height * .57f), style = Stroke(stroke, cap = StrokeCap.Round))
            drawLine(color, Offset(size.width * .5f, size.height * .77f), Offset(size.width * .5f, size.height * .96f), stroke, StrokeCap.Round)
            drawLine(color, Offset(size.width * .32f, size.height * .96f), Offset(size.width * .68f, size.height * .96f), stroke, StrokeCap.Round)
        } else {
            listOf(.25f to .35f, .5f to .7f, .75f to .48f).forEach { (x, height) ->
                drawLine(color, Offset(size.width * x, size.height * (1 - height) / 2),
                    Offset(size.width * x, size.height * (1 + height) / 2), 3.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@Composable private fun HazardReadStatus(s: PrototypeUi) {
    val c = LocalSafetyColors.current
    val hazard = s.vehicle.hazardLights.current(s.now)
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("bitbox-hazard"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (s.vehicle.sample) "비상등 · 샘플" else "비상등", style = SafetyStepTokens.label, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(when (hazard) { true -> "켜짐 확인"; false -> "꺼짐 · 가능하면 켜주세요"; null -> "상태 확인 필요" },
            style = SafetyStepTokens.caption, color = if (hazard == true) c.mint else c.amber,
            modifier = Modifier.weight(2f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
internal fun ReportLocationCard(
    location: ReportLocation,
    now: Long,
    refresh: () -> Unit,
    sessionId: String? = null,
    help: HelpState? = null,
    drivingRestricted: Boolean = false,
    locationOrigin: LocationOrigin? = null,
) {
    val c = LocalSafetyColors.current
    var details by remember(sessionId, help) { mutableStateOf(false) }
    LaunchedEffect(drivingRestricted) { if (drivingRestricted) details = false }
    val summary = when (location.status) {
        ReportLocationStatus.NOT_REQUESTED -> "위치 확인이 필요해요"
        ReportLocationStatus.LOADING -> "위치를 확인하고 있어요"
        ReportLocationStatus.UNAVAILABLE -> "위치를 확인하지 못했어요"
        ReportLocationStatus.AVAILABLE -> location.address?.takeIf { it.isNotBlank() }
            ?: if (location.hasCoordinates) String.format(Locale.KOREA, "%.6f, %.6f", location.latitude, location.longitude)
            else "위치 확인이 필요해요"
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(SafetyStepTokens.radius)).background(c.inset)
        .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 10.dp).testTag("report-location"),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("신고할 때 참고할 위치", style = SafetyStepTokens.caption, color = c.secondary)
                if (location.sample) Text("샘플 위치", style = SafetyStepTokens.caption, color = c.amber, fontWeight = FontWeight.SemiBold)
                Text(summary, style = SafetyStepTokens.label, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (location.status == ReportLocationStatus.UNAVAILABLE) c.amber else c.text)
            }
            TextButton(onClick = { details = true }, enabled = !drivingRestricted,
                modifier = Modifier.width(136.dp).heightIn(min = SafetyStepTokens.touch).testTag("report-location-details")) {
                Text("위치 자세히", style = SafetyStepTokens.caption)
            }
        }
        Text("정확한 위치는 주변 표지로 확인하세요", style = SafetyStepTokens.caption, color = c.secondary,
            modifier = Modifier.padding(end = 10.dp))
    }
    if (details && !drivingRestricted) {
        SafetyDialog("신고 위치", { details = false }) {
            if (location.sample) Text("샘플 위치 · 실제 신고에 사용하지 마세요", style = SafetyStepTokens.label, color = c.amber)
            Text(summary, style = SafetyStepTokens.action)
            if (location.hasCoordinates) Text(String.format(Locale.KOREA, "위도 %.6f · 경도 %.6f", location.latitude, location.longitude),
                style = SafetyStepTokens.label, modifier = Modifier.testTag("report-coordinates"))
            location.receivedAt?.let { received ->
                val provider = when (locationOrigin) { LocationOrigin.GPS -> "GPS"; LocationOrigin.TEST -> "시험 입력"; else -> "내비게이션" }
                Text("${(now - received).coerceAtLeast(0) / 1000}초 전 ${provider}에서 받은 위치", style = SafetyStepTokens.caption, color = c.secondary)
            }
            Text(when {
                location.fixObservedAt == null && location.accuracyMeters == null -> "측위 시각·정확도 알 수 없음"
                location.fixObservedAt == null -> "측위 시각 알 수 없음"
                location.accuracyMeters == null -> "위치 정확도 알 수 없음"
                else -> "위치 정확도 약 ${kotlin.math.ceil(location.accuracyMeters).toInt()} m"
            }, style = SafetyStepTokens.label)
            Text("도로 진행 방향은 주변 표지로 확인해주세요.", style = SafetyStepTokens.label)
            if (location.detail.isNotBlank()) Text(location.detail, style = SafetyStepTokens.caption, color = c.secondary)
            Action("위치 다시 확인", "report-location-refresh", enabled = location.status != ReportLocationStatus.LOADING, action = refresh)
        }
    }
}
