package io.github.aitm.pleosapp

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aitm.pleosapp.prototype.*
import io.github.aitm.pleosapp.ui.SafetyStepApp

class MainActivity : ComponentActivity() {
    private val model: PrototypeViewModel by viewModels()
    private val operatorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { model.operatorAction(intent) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) ContextCompat.registerReceiver(this, operatorReceiver,
            IntentFilter("$packageName.OPERATOR"), ContextCompat.RECEIVER_EXPORTED)
        if (savedInstanceState == null) { model.operatorAction(intent); model.gleoAction(intent) }
        setContent { SafetyStepApp(model) { PrototypeScreen(model) } }
    }
    override fun onDestroy() {
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(operatorReceiver) }
        super.onDestroy()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.operatorAction(intent)
        model.gleoAction(intent)
    }
    override fun onStart() { super.onStart(); model.setForeground(true) }
    override fun onPause() { model.pauseVoice(); super.onPause() }
    override fun onStop() { model.setForeground(false); super.onStop() }
}

@Composable
private fun PrototypeScreen(model: PrototypeViewModel) {
    val s by model.state.collectAsStateWithLifecycle()
    Scaffold { inset ->
        Column(Modifier.fillMaxSize().padding(inset).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("세이프티스탭 · 기술 진단 / 차량 ${model.owner}", style = MaterialTheme.typography.headlineSmall)
            Text("정차된 에뮬레이터 테스트 전용 · 도로와 위치는 모의 데이터 · 실제 긴급 신고 없음")
            if (!BuildConfig.DEBUG) { Text("프로토타입 실행은 debug 빌드에서만 지원합니다."); return@Column }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. 사고·고장 지원 · 행동 안내 우선", style = MaterialTheme.typography.titleLarge)
                    Text("사건: ${s.incident?.id?.take(8) ?: "없음"} / 출처: ${s.incident?.source ?: "-"}")
                    Text("응답: ${s.incident?.response ?: ResponseState.IDLE} / ${s.incident?.responder ?: Responder.UNKNOWN}")
                    Text("도움: ${s.incident?.help ?: HelpState.UNKNOWN} / 대피 보고: ${s.incident?.evacuated ?: false}")
                    Text("도로 위험: ${s.incident?.hazard ?: "없음"} / 응답 타이머: ${s.timerRunning}")
                    Row { Checkbox(s.consent, model::setConsent); Text("모의 사건 위치를 로컬 서버에 공유") }
                    Button(onClick = { model.begin(eventType = EventType.ACCIDENT) }) { Text("명시적 사고 주입 (TEST)") }
                    Button(onClick = { model.begin() }) { Text("고장·지원 주입 (TEST)") }
                    Button(onClick = model::startResponseTimer, enabled = s.incident != null) { Text("행동 안내 · 예외 청취 재시도") }
                    Text("정차를 관측하면 먼저 행동을 안내하고, 완료 뒤 한 번 청취합니다.")
                    Button(onClick = { model.respond(Responder.DRIVER, HelpState.CAN_MOVE) }, enabled = s.incident != null) { Text("운전자 응답 · 행동 가능") }
                    Button(onClick = { model.respond(Responder.DRIVER, HelpState.NEEDS_HELP) }, enabled = s.incident != null) { Text("운전자 응답 · 도움 필요") }
                    Button(onClick = { model.respond(Responder.PASSENGER, HelpState.NEEDS_HELP) }, enabled = s.incident != null) { Text("동승자 응답 · 도움 필요") }
                    Button(onClick = model::voiceFailure, enabled = s.incident != null) { Text("음성 장애 주입") }
                    Button(onClick = model::interrupt) { Text("긴급 통화 중단 모의") }
                    Button(onClick = model::evacuate, enabled = s.incident != null) { Text("대피 보고 (위험 유지)") }
                    Text("사고 해소는 loopback 시험 관리자 경로에서만 처리해요. 도움 상태는 유지돼요.")
                    Text("도움이 필요하면 기존 긴급 통화 경로를 우선합니다. 대피 보고는 도로 위험 해제가 아닙니다.")
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2. SDK · Gleo 검증", style = MaterialTheme.typography.titleLarge)
                    Text(model.sourceLabel)
                    Button(onClick = model::checkSdk, enabled = !s.sdkBusy) { Text(if (s.sdkBusy) "SDK 조회 중" else "SDK 조회 · 에어백 구독") }
                    Text(s.sdk)
                    Text(s.airbag)
                    Text("초기 전개=true는 새 사고로 전파하지 않습니다. false → true 변화만 새 사건 후보로 처리합니다.")
                    Text("정상 지원 흐름에서는 비상등과 트렁크를 제어하지 않습니다.")
                    Text(s.trunk)
                    HorizontalDivider()
                    Text("Gleo 연동", style = MaterialTheme.typography.titleMedium)
                    Text(s.gleo)
                    Text("현재 버튼 응답은 모의 입력입니다. Gleo Intent 수신과 실제 음성 인식·선제 대화는 별도 검증입니다.")
                    Text("중단·앱 복귀 시 사건 상태를 복원합니다. 미실행 앱의 자동 기동과 백그라운드 보호는 미검증입니다.")
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("3. 접근 차량 · 실제 HTTP", style = MaterialTheme.typography.titleLarge)
                    Text(s.network)
                    Text("전송 대기 ${s.pending}개 · 서버 127.0.0.1:8787 (adb reverse)")
                    Row { Checkbox(s.online, model::setOnline); Text("로컬 서버 통신 허용") }
                    Row { Checkbox(s.receiverEnabled, model::setReceiverEnabled); Text("위험 알림 수신") }
                    Text("${s.receiverStatus} · ${s.receiverDetail}")
                    Text("${s.stopReportStatus} · ${s.stopReportDetail}")
                    Text("아래 위치 입력은 명시적 TEST이며 시간이 지나면 만료됩니다.")
                    listOf("approach" to "같은 방향 접근", "opposite" to "반대 방향", "parallel" to "평행도로", "passed" to "사고 지점 통과", "stale" to "위치 만료").forEach { (value, label) ->
                        OutlinedButton(onClick = { model.scenario(value) }, enabled = s.receiverScenario != value) { Text(label) }
                    }
                    if (s.warnings.isEmpty()) Text("현재 경고 대상 없음 · 도로 안전 확인을 뜻하지 않음")
                    s.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium) }
                    s.received.forEach { Text(it) }
                    HorizontalDivider()
                    Text("최근 이벤트", style = MaterialTheme.typography.titleMedium)
                    s.logs.take(10).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
