package io.github.aitm.pleosapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.aitm.pleosapp.location.ReportLocation
import io.github.aitm.pleosapp.location.ReportLocationStatus
import io.github.aitm.pleosapp.prototype.*
import io.github.aitm.pleosapp.speech.VoicePhase
import java.util.Locale

/** The first action, help, observed seats and reporting location stay on one surface. */
@Composable
internal fun IncidentDashboard(s: PrototypeUi, model: PrototypeViewModel) = IncidentDashboardContent(
    s,
    onHelp = { model.respond(s.incident?.responder ?: Responder.UNKNOWN, HelpState.NEEDS_HELP) },
    onVoice = {
        if (s.voicePhase in listOf(VoicePhase.PREPARING, VoicePhase.SPEAKING, VoicePhase.LISTENING)) model.pauseVoice()
        else model.requestVoice()
    },
    onRefreshLocation = model::refreshReportLocation,
)

@Composable internal fun IncidentDashboardContent(s: PrototypeUi, onHelp: () -> Unit, onVoice: () -> Unit, onRefreshLocation: () -> Unit) {
    val c = LocalSafetyColors.current
    val needsHelp = s.incident?.help == HelpState.NEEDS_HELP
    val remaining = s.vehicle.seats.count { it.occupied.current(s.now) == true }
    val unknownSeats = s.vehicle.seats.isEmpty() || s.vehicle.seats.any { it.occupied.current(s.now) == null }
    Row(Modifier.fillMaxWidth().testTag("incident-dashboard"), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("내 차량 안전 지원", style = SafetyStepTokens.label, color = c.amber, fontWeight = FontWeight.SemiBold)
        Text(if (s.vehicle.sample) "샘플 차량 상태" else if (s.supportQualified) "정차 확인" else "차량 상태 확인 중", style = SafetyStepTokens.caption, color = c.secondary)
    }
    Hero(when {
        needsHelp -> "무리하게 움직이지 마세요"
        !s.supportQualified -> "안전하게 정차해주세요"
        s.cabinClearConfirmed -> "안전한 곳에 머무르세요"
        else -> "안전한 곳으로 이동하세요"
    }, when {
        needsHelp -> "가능한 방법으로 도움을 요청하세요."
        !s.supportQualified -> "차량이 멈춘 뒤 주변 상황을 살펴주세요."
        s.cabinClearConfirmed -> "관측된 좌석은 비어 있어요. 차량으로 돌아가지 마세요."
        else -> "주변을 살피고, 안전하게 내릴 수 있으면 가드레일 밖 등 안전한 곳으로 이동하세요."
    })
    if (needsHelp) {
        ReportLocationCard(s.reportLocation, s.now, onRefreshLocation)
        ContactActions()
    }
    SupportVoiceStatus(s, onVoice)
    // Acknowledgement is optional; it never declares that the occupant can move.
    if (!s.cabinClearConfirmed || needsHelp) LinkAction("도움이 필요해요", "urgent-help", c.red, onHelp)
    CabinMap(s, preparing = true)
    Column(Modifier.fillMaxWidth().testTag("bitbox-status"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val hazard = s.vehicle.hazardLights.current(s.now)
        StatusRow("비상등", when (hazard) {
            true -> "켜짐 확인"
            false -> "꺼짐 · 가능하면 켜주세요"
            null -> "상태 확인 필요"
        }, if (hazard == true) c.mint else c.amber, "bitbox-hazard")
        StatusRow("좌석 관측", when {
            remaining > 0 -> "잔류 감지 ${remaining}석 · 확인 필요"
            unknownSeats -> "확인 안 된 좌석이 있어요"
            else -> "관측 좌석 비움"
        }, if (remaining > 0 || unknownSeats) c.amber else c.mint, "bitbox-exit")
        Text("좌석 감지만으로 사람이나 물체를 구분할 수 없어요.", style = SafetyStepTokens.caption, color = c.secondary)
    }
    if (!needsHelp) ReportLocationCard(s.reportLocation, s.now, onRefreshLocation)
    if (!needsHelp && !s.cabinClearConfirmed) ContactActions()
    if (s.cabinClearConfirmed && !needsHelp) Text("안전한 곳에서 휴대전화로 상황과 위치를 알려주세요.", style = SafetyStepTokens.label, color = c.secondary)
    RoadNotice(s)
}

@Composable private fun SupportVoiceStatus(s: PrototypeUi, onVoice: () -> Unit) {
    val c = LocalSafetyColors.current
    val active = s.voicePhase in listOf(VoicePhase.PREPARING, VoicePhase.SPEAKING, VoicePhase.LISTENING)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(SafetyStepTokens.radius)).background(c.inset).padding(16.dp).testTag("support-voice"),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(Modifier.size(10.dp)) { drawCircle(if (s.voicePhase == VoicePhase.LISTENING) c.mint else c.blue) }
            Text(when (s.voicePhase) {
                VoicePhase.PREPARING -> "음성 연결 중"
                VoicePhase.SPEAKING -> "안내를 듣고 주변을 살펴주세요"
                VoicePhase.LISTENING -> "도움이 필요하면 말씀해주세요"
                VoicePhase.UNAVAILABLE -> "음성 안내를 사용할 수 없어요"
                VoicePhase.PAUSED -> "음성 안내가 중단됐어요"
                else -> "안내 내용이 화면에 남아 있어요"
            }, style = SafetyStepTokens.label, color = c.blue)
        }
        if (s.voiceDetail.isNotBlank()) Text(s.voiceDetail, style = SafetyStepTokens.caption, color = c.secondary)
        TextButton(onClick = onVoice,
            modifier = Modifier.fillMaxWidth().heightIn(min = SafetyStepTokens.touch).testTag("voice-action")) {
            Text(if (active) "음성 잠시 멈추기" else "음성 응답 다시 시도", style = SafetyStepTokens.label)
        }
    }
}

@Composable private fun StatusRow(title: String, status: String, color: androidx.compose.ui.graphics.Color, tag: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LocalSafetyColors.current.inset)
        .padding(horizontal = 16.dp, vertical = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = SafetyStepTokens.label, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(status, style = SafetyStepTokens.caption, color = color)
    }
}

@Composable internal fun ReportLocationCard(location: ReportLocation, now: Long, refresh: () -> Unit) {
    val c = LocalSafetyColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(SafetyStepTokens.radius)).background(c.inset).padding(20.dp).testTag("report-location"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("신고할 때 알려줄 위치", style = SafetyStepTokens.action)
        when (location.status) {
            ReportLocationStatus.NOT_REQUESTED -> Text("위치를 확인해주세요.", style = SafetyStepTokens.label)
            ReportLocationStatus.LOADING -> Text("신고 위치를 확인하고 있어요.", style = SafetyStepTokens.label)
            ReportLocationStatus.UNAVAILABLE -> Text("위치를 확인하지 못했어요.", style = SafetyStepTokens.label, color = c.amber)
            ReportLocationStatus.AVAILABLE -> {
                if (location.sample) Text("샘플 위치 · 실제 신고에 사용하지 마세요", style = SafetyStepTokens.caption, color = c.amber)
                location.address?.takeIf { it.isNotBlank() }?.let { Text(it, style = SafetyStepTokens.label) }
                if (location.hasCoordinates) Text(String.format(Locale.KOREA, "위도 %.6f · 경도 %.6f", location.latitude, location.longitude),
                    style = SafetyStepTokens.caption, modifier = Modifier.testTag("report-coordinates"))
                location.receivedAt?.let { received ->
                    val age = ((now - received).coerceAtLeast(0) / 1000)
                    Text("${age}초 전 내비게이션에서 받은 위치", style = SafetyStepTokens.caption, color = c.secondary)
                }
                Text(when {
                    location.fixObservedAt == null && location.accuracyMeters == null -> "측위 시각·정확도 알 수 없음"
                    location.fixObservedAt == null -> "측위 시각 알 수 없음"
                    location.accuracyMeters == null -> "위치 정확도 알 수 없음"
                    else -> "위치 정확도 약 ${location.accuracyMeters.toInt()} m"
                }, style = SafetyStepTokens.caption, color = c.secondary)
                Text("도로 진행 방향은 주변 표지로 확인해주세요.", style = SafetyStepTokens.caption, color = c.secondary)
            }
        }
        if (location.detail.isNotBlank() && location.status != ReportLocationStatus.AVAILABLE) Text(location.detail, style = SafetyStepTokens.caption, color = c.secondary)
        TextButton(onClick = refresh, enabled = location.status != ReportLocationStatus.LOADING,
            modifier = Modifier.fillMaxWidth().heightIn(min = SafetyStepTokens.touch).testTag("report-location-refresh")) {
            Text("위치 다시 확인", style = SafetyStepTokens.label)
        }
    }
}
