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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aitm.pleosapp.prototype.*
import io.github.aitm.pleosapp.speech.VoicePhase

/** One incident surface. The vehicle and voice update it; there is no Next/Done flow. */
@Composable
internal fun IncidentDashboard(s: PrototypeUi, model: PrototypeViewModel, requestTrunk: () -> Unit) {
    val c = LocalSafetyColors.current
    val screen = SupportPolicy.screen(s.incident, s.journey, s.cabinClearConfirmed)
    val occupied = s.vehicle.seats.count { it.occupied.current(s.now) == true }
    val stationary = BitboxPolicy.stationary(s.vehicle, s.now)
    val passengerRemains = s.vehicle.driverOccupied.current(s.now) == false && occupied > 0
    val needsHelp = screen == SupportScreen.HELP
    val answering = screen in listOf(SupportScreen.RESPONSE, SupportScreen.MOBILITY)
    Row(Modifier.fillMaxWidth().testTag("incident-dashboard"), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (s.journey.collision == CollisionContext.UNKNOWN) "사고 상황 확인 중" else s.journey.collision.label, style = SafetyStepTokens.label, color = c.amber, fontWeight = FontWeight.SemiBold)
        Text(if (stationary) "정차 확인" else "차량 상태 확인 중", style = SafetyStepTokens.caption, color = c.secondary)
    }
    Hero(when {
        s.cabinClearConfirmed -> "안전한 곳에 머무르세요"
        needsHelp -> "무리하게 움직이지 마세요"
        s.incident?.response == ResponseState.UNCONFIRMED -> "제 말이 들리나요?"
        s.incident?.response == ResponseState.UNAVAILABLE -> "화면으로도 답할 수 있어요"
        screen == SupportScreen.RESPONSE -> "제 말이 들리나요?"
        screen == SupportScreen.MOBILITY -> "움직일 수 있나요?"
        !stationary -> "차량이 멈춘 뒤 이동하세요"
        passengerRemains -> "동승자가 남아 있어요"
        else -> "안전한 곳으로 이동하세요"
    })
    val guidance = when {
        s.cabinClearConfirmed -> "차량으로 돌아가지 마세요."
        needsHelp -> "긴급 통화로 상황을 알리고 안내를 따르세요."
        s.incident?.response == ResponseState.UNCONFIRMED -> "동승자가 대신 답할 수 있어요."
        answering && s.voicePhase == VoicePhase.LISTENING -> "답변을 듣고 있어요"
        answering && s.voicePhase == VoicePhase.SPEAKING -> "제 말을 들어주세요"
        answering -> "말씀하실 수 있다면 답해주세요."
        passengerRemains -> "이동이 어렵다면 도움을 요청하세요."
        else -> "주변을 살피고 안전하게 내릴 수 있을 때 이동하세요."
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.size(10.dp)) { drawCircle(if (s.voicePhase == VoicePhase.LISTENING) c.mint else c.blue) }
        Text(guidance, style = SafetyStepTokens.label, color = c.secondary)
    }
    CabinMap(s, preparing = true)
    Column(Modifier.fillMaxWidth().testTag("bitbox-status"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("비트박스 안전 행동", style = SafetyStepTokens.label, fontWeight = FontWeight.SemiBold)
        val hazard = s.vehicle.hazardLights.current(s.now)
        ProgressRow("비", "비상등", when { hazard == true -> "켜짐"; s.hazardAction == ControlState.CHECKING -> "자동으로 켜는 중"; hazard == false -> "꺼짐 · 확인 필요"; else -> "상태 확인 필요" }, hazard == true, if (hazard == true) c.mint else c.amber, "bitbox-hazard")
        val rear = s.journey.collision == CollisionContext.REAR
        val trunk = s.vehicle.trunkOpen.current(s.now)
        ProgressRow("트", "트렁크", when { trunk == true -> "열림"; rear -> "후방 충돌 · 열지 않음"; s.journey.collision == CollisionContext.UNKNOWN -> "상황 확인 · 열지 않음"; needsHelp || !stationary -> "열기 보류"; s.journey.collision == CollisionContext.FRONT && !s.journey.rearClearConfirmed -> "후방 공간 확인 필요"; s.journey.trunk == ControlState.CHECKING -> "열림 확인 중"; s.journey.collision == CollisionContext.FRONT && s.journey.autoTrunkClaimed -> "열림 확인 필요"; s.journey.collision == CollisionContext.FRONT -> "응답 확인 후 자동 개방"; else -> "주변 확인 후 요청" }, trunk == true, if (rear) c.amber else if (trunk == true) c.mint else c.secondary, "bitbox-trunk")
        ProgressRow("밖", "밖으로 이동", if (s.cabinClearConfirmed) "모든 좌석 비움" else if (occupied > 0) "탑승 중 ${occupied}석" else "좌석 확인 중", s.cabinClearConfirmed, if (s.cabinClearConfirmed) c.mint else c.blue, "bitbox-exit")
        ProgressRow("스", "스마트폰으로 신고", "안전한 곳에서", false, c.secondary, "bitbox-call")
    }
    // Optional fallback actions never gate the sensor-driven progress above.
    when {
        s.cabinClearConfirmed -> Unit
        needsHelp -> ContactActions()
        answering && s.voicePhase in listOf(VoicePhase.UNAVAILABLE, VoicePhase.PAUSED, VoicePhase.IDLE, VoicePhase.COMPLETE) -> {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (screen == SupportScreen.MOBILITY) Box(Modifier.weight(1f)) {
                    SecondaryAction("움직일 수 있어요", "can-move") { model.respond(s.incident!!.responder, HelpState.CAN_MOVE) }
                } else Box(Modifier.weight(1f)) {
                    SecondaryAction("들려요", "respond-driver") { model.respond(Responder.UNKNOWN, HelpState.UNKNOWN) }
                }
                Box(Modifier.weight(1f)) { SecondaryAction("도움이 필요해요", "urgent-help") { model.respond(s.incident?.responder ?: Responder.UNKNOWN, HelpState.NEEDS_HELP) } }
            }
        }
        else -> HelpAction(s, model)
    }
    if (!answering && !needsHelp && !s.cabinClearConfirmed && s.journey.collision !in listOf(CollisionContext.REAR, CollisionContext.UNKNOWN, CollisionContext.FRONT))
        LinkAction("트렁크 열기", "trunk-request") { requestTrunk() }
}

@Composable
private fun ProgressRow(mark: String, title: String, status: String, complete: Boolean, color: Color, tag: String) {
    val c = LocalSafetyColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp)).background(c.inset).padding(horizontal = 16.dp, vertical = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(mark, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.blue, modifier = Modifier.width(28.dp))
        Text(title, style = SafetyStepTokens.label, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(status, style = SafetyStepTokens.caption, color = color)
        Text(if (complete) "✓" else "—", fontSize = 24.sp, color = if (complete) c.mint else color, modifier = Modifier.width(24.dp))
    }
}
