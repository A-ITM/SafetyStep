package io.github.aitm.pleosapp.prototype

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.aitm.pleosapp.BuildConfig
import io.github.aitm.pleosapp.location.ReportLocation
import io.github.aitm.pleosapp.location.ReportLocationGatewayFactory
import io.github.aitm.pleosapp.location.ReportLocationStatus
import io.github.aitm.pleosapp.speech.*
import io.github.aitm.pleosapp.vehicle.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class PrototypeUi(
    val incident: Incident? = null,
    val consent: Boolean = false,
    val sdk: String = "SDK 점검 전",
    val airbag: String = "에어백 구독 전",
    val trunk: String = "이 프로토타입에서는 트렁크를 제어하지 않아요",
    val network: String = "로컬 서버 연결 전",
    val gleo: String = "Gleo 실제 음성 세션 미검증",
    val receiverScenario: String = "approach",
    val received: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val logs: List<String> = emptyList(),
    val sdkBusy: Boolean = false,
    val trunkBusy: Boolean = false,
    val timerRunning: Boolean = false,
    val online: Boolean = true,
    val pending: Int = 0,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val journey: SupportJourney = SupportJourney(),
    val vehicle: VehicleSnapshot = VehicleSnapshot(),
    val vehicleBusy: Boolean = false,
    val now: Long = System.currentTimeMillis(),
    val receiverView: Boolean = false,
    val previewHazard: Boolean = false,
    val connected: Boolean = false,
    val voicePhase: VoicePhase = VoicePhase.IDLE,
    val voiceText: String = "",
    val voiceDetail: String = "",
    val voiceInput: String = "NONE",
    val voicePurpose: String = "GUIDANCE",
    val drivingRestricted: Boolean = BuildConfig.FLAVOR == "pleos",
    val cabinClearConfirmed: Boolean = false,
    val occupiedSeatAreas: Set<Int> = emptySet(),
    val hazardAction: ControlState = ControlState.IDLE,
    val hazardCommands: Int = 0,
    val hazardDetail: String = "비상등은 상태를 읽어 안내해요",
    val receiverEnabled: Boolean = false,
    val receiverStatus: ReceiverStatus = ReceiverStatus.OFF,
    val receiverDetail: String = "위험 알림 수신이 꺼져 있어요",
    val warningCards: List<WarningCard> = emptyList(),
    val stopReportStatus: StopReportStatus = StopReportStatus.NONE,
    val stopReportId: String? = null,
    val stopReportDetail: String = "정차 관측 전",
    val supportQualified: Boolean = false,
    val reportLocation: ReportLocation = ReportLocation(),
)

class PrototypeViewModel(application: Application) : AndroidViewModel(application) {
    val owner = if (BuildConfig.FLAVOR == "pleos") "A" else "B"
    private val gateway = VehicleGatewayFactory.create(application)
    private val speech = SpeechGatewayFactory.create(application)
    private val reportGateway = ReportLocationGatewayFactory.create(application)
    private val store = PrototypeStore(application)
    private val client = HazardClient(owner)
    private val metrics = PrototypeMetrics(application, owner)
    private val edge = DeploymentEdge(store.lastDeployment)
    private val snapshot = ReceiverSnapshotState()
    private val stopPolicy = StopObservationPolicy()
    private val voicePolicy = WarningVoicePolicy()
    private val cabinClearGate = CabinClearGate()
    private val outbox = store.outbox().mapNotNull { runCatching { HazardCommand.fromJson(it) }.getOrNull() }.toMutableList()
    private var sharedEvent = store.sharedStop()
    private val spokenIds = store.spokenIds().toMutableSet()
    private val voiceAttempts = mutableMapOf<String, Int>()
    private val receivedIds = mutableSetOf<String>()
    private val firstReceivedElapsed = mutableMapOf<String, Long>()
    private val completedVoiceElapsed = mutableMapOf<String, Long>()
    private val crossingTargets = mutableMapOf<String, Hazard>()
    private val crossedIds = mutableSetOf<String>()
    private val operatorPcmQueue = ArrayDeque<ByteArray>()
    private val restoredIncident = store.incident()
    private val mutable = MutableStateFlow(PrototypeUi(
        incident = restoredIncident, consent = store.consent, pending = outbox.size,
        theme = store.theme, journey = store.journey(restoredIncident?.id), receiverEnabled = store.receiverEnabled,
        receiverStatus = if (store.receiverEnabled) ReceiverStatus.PAUSED else ReceiverStatus.OFF,
        receiverDetail = if (store.receiverEnabled) "앱을 열면 수신을 재개해요" else "위험 알림 수신이 꺼져 있어요",
        stopReportId = sharedEvent?.id,
        stopReportStatus = if (outbox.any { it.kind == CommandKind.CREATE && it.event.id == sharedEvent?.id }) {
            if (store.consent) StopReportStatus.QUEUED else StopReportStatus.HELD
        } else store.stopReportStatus,
    ))
    val state = mutable.asStateFlow()
    val sourceLabel = gateway.sourceLabel
    private var foreground = false
    private var vehicleJob: Job? = null
    private var uploaderJob: Job? = null
    private var receiverJob: Job? = null
    private var voiceJob: Job? = null
    private var voiceToken = 0L
    private var voiceIncidentId: String? = null
    private var voiceMetricPurpose: String? = null
    private var voiceContextGeneration = 0L
    private val uploadMutex = Mutex()
    private val receiveMutex = Mutex()
    private var contextGeneration = 0L
    private var currentPosition: RoadPosition? = null
    private var previewEvent: Hazard? = null
    private var latestStop = StopObservation(null, false)
    private var firstStopElapsed: Long? = null
    private var firstStopUtc: Long? = null
    private var firstActionComplete: String? = null
    private var firstActionAttempted: String? = null
    private var firstActionRequested = true
    private var listeningAttempted: String? = null
    private var guidanceOutside: String? = null
    private var supportInputElapsed = mutableMapOf<String, Long>()
    private var firstEvidenceElapsed = mutableMapOf<String, Long>()
    private var reportJob: Job? = null

    init {
        gateway.watchDrivingRestrictions { restricted -> viewModelScope.launch { mutable.update { it.copy(drivingRestricted = restricted) } } }
        if (BuildConfig.DEBUG) viewModelScope.launch {
            while (true) { writeOperatorState(); delay(500) }
        }
    }

    private fun writeOperatorState() {
        val s = state.value
        val p = currentPosition
        val payload = JSONObject().apply {
            put("heartbeat", SystemClock.elapsedRealtime()); put("visible", foreground); put("owner", owner); put("runId", metrics.runId)
            put("screen", if (s.receiverView || (s.drivingRestricted && s.incident == null)) "RECEIVER" else SupportPolicy.screen(s.incident, s.journey, s.cabinClearConfirmed).name)
            put("incidentId", s.incident?.id ?: JSONObject.NULL); put("source", s.incident?.source ?: "NONE")
            put("eventType", s.incident?.eventType?.name); put("collision", s.journey.collision.name)
            put("consent", s.consent); put("pending", s.pending); put("connected", s.connected)
            put("receiverEnabled", s.receiverEnabled); put("receiverStatus", s.receiverStatus.name); put("receiverDetail", s.receiverDetail)
            put("warningIds", JSONArray(s.warningCards.map { it.incidentId })); put("received", JSONArray(s.received))
            put("snapshotConfirmed", snapshot.confirmed)
            put("receivedEvents", JSONArray(snapshot.events.map { e -> JSONObject().put("id", e.id).put("version", e.version)
                .put("status", e.status.name).put("eventType", e.eventType.name) }))
            put("warningCards", JSONArray(s.warningCards.map { c -> JSONObject().put("incidentId", c.incidentId).put("eventType", c.eventType.name)
                .put("distanceMeters", c.distanceMeters ?: JSONObject.NULL).put("freshness", c.freshness.name).put("voiceHeld", c.voiceHeld) }))
            put("stopReportId", s.stopReportId ?: JSONObject.NULL); put("stopReportStatus", s.stopReportStatus.name)
            put("stopReportDetail", s.stopReportDetail); put("supportQualified", s.supportQualified)
            put("position", p?.let { JSONObject().put("road", it.road).put("direction", it.direction).put("meters", it.meters)
                .put("speedMps", it.speedMps ?: JSONObject.NULL).put("roadKind", it.roadKind.name).put("observedAt", it.observedAt).put("source", it.source) } ?: JSONObject.NULL)
            put("contextGeneration", contextGeneration); put("metricsFile", metrics.file.absolutePath)
            put("reportLocation", JSONObject().put("status", s.reportLocation.status.name).put("latitude", s.reportLocation.latitude ?: JSONObject.NULL)
                .put("longitude", s.reportLocation.longitude ?: JSONObject.NULL).put("address", s.reportLocation.address ?: JSONObject.NULL)
                .put("receivedAt", s.reportLocation.receivedAt ?: JSONObject.NULL).put("fixObservedAt", s.reportLocation.fixObservedAt ?: JSONObject.NULL)
                .put("accuracyMeters", s.reportLocation.accuracyMeters ?: JSONObject.NULL).put("sample", s.reportLocation.sample))
            put("response", s.incident?.response?.name); put("help", s.incident?.help?.name); put("responder", s.incident?.responder?.name)
            put("evacuated", s.incident?.evacuated ?: false); put("hazard", s.incident?.hazard?.name)
            put("voice", s.voicePhase.name); put("voiceInput", s.voiceInput); put("voiceDetail", s.voiceDetail); put("voicePurpose", s.voicePurpose)
            put("voiceText", s.voiceText); put("restricted", s.drivingRestricted)
            put("trunk", s.journey.trunk.name); put("trunkAttempts", s.journey.trunkAttempts); put("autoTrunkClaimed", false)
            put("rearClearConfirmed", s.journey.rearClearConfirmed); put("hazardCommands", s.hazardCommands); put("hazardAction", s.hazardAction.name)
            put("hazardDetail", s.hazardDetail); put("hazardLights", s.vehicle.hazardLights.current(s.now) ?: JSONObject.NULL)
            put("hazardLightsDetail", s.vehicle.hazardLights.detail)
            put("gear", s.vehicle.gear.current(s.now) ?: JSONObject.NULL); put("speedMps", s.vehicle.speedMps.current(s.now) ?: JSONObject.NULL)
            put("speedDetail", s.vehicle.speedMps.detail); put("doorOpen", s.vehicle.driverDoorOpen.current(s.now) ?: JSONObject.NULL)
            put("trunkOpen", s.vehicle.trunkOpen.current(s.now) ?: JSONObject.NULL); put("occupied", s.vehicle.driverOccupied.current(s.now) ?: JSONObject.NULL)
            put("occupancyDetail", s.vehicle.driverOccupied.detail); put("belt", s.vehicle.driverBelt.current(s.now) ?: JSONObject.NULL)
            put("cabinPresence", CabinPolicy.presence(s.vehicle.seats, s.now).name); put("cabinClearConfirmed", s.cabinClearConfirmed)
            put("stationary", supportStationary()); put("occupiedSeatAreas", JSONArray(s.occupiedSeatAreas.sorted())); put("sample", s.vehicle.sample)
            put("seats", JSONArray(s.vehicle.seats.map { seat -> JSONObject().put("area", seat.area).put("label", seat.label)
                .put("occupied", seat.occupied.current(s.now) ?: JSONObject.NULL).put("detail", seat.occupied.detail) }))
        }
        runCatching {
            val temp = File(getApplication<Application>().filesDir, "operator-state.tmp")
            temp.writeText(payload.toString()); temp.renameTo(File(getApplication<Application>().filesDir, "operator-state.json"))
        }
    }

    private fun log(message: String) {
        Log.i("RoadsidePrototype", "$owner $message")
        mutable.update { it.copy(logs = (listOf(message) + it.logs).take(15)) }
    }
    private fun updateIncident(value: Incident) { store.saveIncident(value); mutable.update { it.copy(incident = value) } }
    private fun journey(value: SupportJourney) { store.saveJourney(state.value.incident?.id, value); mutable.update { it.copy(journey = value) } }
    fun setTheme(value: ThemePreference) { store.theme = value; mutable.update { it.copy(theme = value) } }
    fun setCollision(value: CollisionContext) {
        journey(state.value.journey.copy(collision = value, trunk = ControlState.HELD, trunkNote = "이 프로토타입에서는 트렁크를 제어하지 않아요"))
        if (sharedEvent == null) state.value.incident?.let {
            val type = if (value in setOf(CollisionContext.FRONT, CollisionContext.REAR, CollisionContext.OTHER)) EventType.ACCIDENT else EventType.STOPPED_VEHICLE
            updateIncident(it.copy(eventType = type))
        }
    }
    fun chooseSituation(value: CollisionContext) { setCollision(value); startSupportResponse() }
    fun confirmStationary(value: Boolean) { journey(state.value.journey.copy(stationaryConfirmed = value && supportStationary(), routeConfirmed = false)) }
    fun confirmRoute(value: Boolean) { journey(state.value.journey.copy(routeConfirmed = value)) }
    fun finishGuidance() { journey(state.value.journey.copy(departureDeclared = true)); log("대피 안내는 지원 상태와 도로 사건을 종료하지 않아요") }
    fun simulateContact(active: Boolean) { interrupt(); journey(state.value.journey.copy(callSimulation = active)) }
    fun receiverView(value: Boolean) { mutable.update { it.copy(receiverView = value) } }

    fun begin(source: String = "MANUAL_TEST", eventType: EventType = EventType.STOPPED_VEHICLE, useVoice: Boolean = true) {
        if (!BuildConfig.DEBUG) return
        if (state.value.incident != null) { log("기존 지원 세션 유지 · 뒤늦은 도움 입력을 계속 받을 수 있어요"); return }
        stopVoice()
        val now = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime()
        val type = sharedEvent?.takeIf { it.status == HazardState.ACTIVE }?.eventType ?: eventType
        val incident = Incident(UUID.randomUUID().toString(), now, source, eventType = type)
        updateIncident(incident); journey(SupportJourney()); cabinClearGate.reset()
        firstActionComplete = null; firstActionAttempted = null; listeningAttempted = null; firstActionRequested = useVoice
        supportInputElapsed[incident.id] = elapsed; firstEvidenceElapsed[incident.id] = minOf(elapsed, firstStopElapsed ?: elapsed)
        mutable.update { it.copy(receiverView = false, cabinClearConfirmed = false, occupiedSeatAreas = it.vehicle.seats.filter { seat -> seat.occupied.current(now) == true }.map { seat -> seat.area }.toSet()) }
        metrics.record("support_input", sharedEvent?.id, incident.id, details = mapOf("source" to source, "eventType" to type.name))
        refreshReportLocation(); updateSupportQualification(); maybeShare()
        log("지원 입력 접수 · 정차 관측 뒤 행동 안내 · 차량 제어 없음")
    }
    private fun supportStationary(): Boolean {
        val now = System.currentTimeMillis()
        // An explicit TEST sample, including unknown speed, takes precedence over SDK data.
        return currentPosition?.let { it.valid(now) && it.speedMps?.let { speed -> speed <= .5 } == true }
            ?: state.value.vehicle.speedMps.current(now)?.let { it >= 0f && it <= .5f } == true
    }
    private fun updateSupportQualification() {
        val qualified = state.value.incident != null && supportStationary()
        if (!qualified && state.value.supportQualified && voiceMetricPurpose == "support_voice") stopVoice()
        mutable.update { it.copy(supportQualified = qualified, now = System.currentTimeMillis()) }
        if (qualified && firstActionRequested && firstActionAttempted != state.value.incident?.id) startFirstAction()
    }
    fun startSupportResponse(useVoice: Boolean = true) {
        firstActionRequested = useVoice
        updateSupportQualification()
        if (useVoice && firstActionComplete == state.value.incident?.id) requestVoice()
    }
    private fun stopVoice() {
        val id = voiceIncidentId; val purpose = voiceMetricPurpose
        if (voiceJob?.isActive == true && id != null && purpose != null) metrics.record("${purpose}_interrupted", id, state.value.incident?.id)
        voiceToken++; voiceJob?.cancel(); voiceJob = null; voiceIncidentId = null; voiceMetricPurpose = null
        mutable.update { it.copy(voicePhase = VoicePhase.IDLE) }
    }
    fun pauseVoice() {
        operatorPcmQueue.clear(); stopVoice()
        mutable.update { it.copy(voicePhase = VoicePhase.PAUSED) }
    }
    private fun startFirstAction() {
        val s = state.value; val incident = s.incident ?: return
        if (!foreground || !s.supportQualified || !firstActionRequested || firstActionAttempted == incident.id) return
        firstActionAttempted = incident.id
        stopVoice(); val token = voiceToken
        voiceIncidentId = sharedEvent?.id ?: incident.id; voiceMetricPurpose = "support_voice"
        mutable.update { it.copy(voicePhase = VoicePhase.PREPARING, voicePurpose = "GUIDANCE", voiceDetail = "", voiceInput = "NONE") }
        val prompt = if (incident.help == HelpState.NEEDS_HELP) "무리하게 움직이지 마세요. 가능한 방법으로 도움을 요청하세요."
            else "주변을 살피고, 안전하게 내릴 수 있으면 가드레일 밖 등 안전한 곳으로 이동하세요."
        voiceJob = viewModelScope.launch {
            var started = false
            val result = try {
                speech.say(prompt) { phase ->
                    if (voiceToken == token) {
                        mutable.update { it.copy(voicePhase = phase) }
                        if (phase == VoicePhase.SPEAKING && !started) {
                            started = true
                            metrics.record("support_voice_start", sharedEvent?.id, incident.id, details = mapOf("inputToStartMs" to supportInputElapsed[incident.id]?.let { SystemClock.elapsedRealtime() - it }))
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { VoiceResult.Failed(error.message ?: "음성 안내 실패") }
            if (voiceToken != token || state.value.incident?.id != incident.id) return@launch
            voiceMetricPurpose = null; voiceIncidentId = null
            if (result == VoiceResult.Spoken) {
                firstActionComplete = incident.id
                val elapsed = SystemClock.elapsedRealtime()
                metrics.record("support_voice_complete", sharedEvent?.id, incident.id, details = mapOf(
                    "inputToCompleteMs" to supportInputElapsed[incident.id]?.let { elapsed - it },
                    "firstEvidenceToCompleteMs" to firstEvidenceElapsed[incident.id]?.let { elapsed - it }))
                mutable.update { it.copy(voicePhase = VoicePhase.COMPLETE) }
                // The hazard-lights reminder is separate and makes no control/success claim.
                val hazard = state.value.vehicle.hazardLights.current(System.currentTimeMillis())
                mutable.update { it.copy(hazardDetail = when (hazard) { true -> "비상등 켜짐을 관측했어요"; false -> "안전하게 조작할 수 있으면 비상등을 켜세요"; null -> "비상등 상태를 확인하지 못했어요" }) }
                requestVoice()
            } else {
                val detail = (result as? VoiceResult.Failed)?.detail ?: "음성 안내 완료를 확인하지 못했어요"
                metrics.record("support_voice_failed", sharedEvent?.id, incident.id, details = mapOf("reason" to detail.take(160)))
                mutable.update { it.copy(voicePhase = VoicePhase.UNAVAILABLE, voiceDetail = detail) }
                updateIncident(state.value.incident!!.beginResponse().voiceFailed())
            }
        }
    }
    fun requestVoice(testPcm: ByteArray? = null) {
        val s = state.value; val incident = s.incident ?: return
        if (!foreground || s.journey.callSimulation) return
        if (firstActionComplete != incident.id) {
            if (testPcm != null) operatorPcmQueue.addFirst(testPcm)
            if (firstActionAttempted == incident.id && voiceJob?.isActive == true) return
            firstActionRequested = true; firstActionAttempted = null; updateSupportQualification(); return
        }
        if (s.voicePhase == VoicePhase.LISTENING || s.voicePhase == VoicePhase.SPEAKING) return
        stopVoice(); val token = voiceToken
        val pcm = testPcm ?: operatorPcmQueue.removeFirstOrNull()
        listeningAttempted = incident.id
        updateIncident(incident.beginResponse())
        mutable.update { it.copy(voicePhase = VoicePhase.PREPARING, voicePurpose = "EXCEPTION", voiceText = "", voiceDetail = "", voiceInput = if (pcm == null) "MICROPHONE" else "SYNTHETIC_PCM") }
        voiceJob = viewModelScope.launch {
            var started = false
            val result = try { speech.listen({ phase -> if (voiceToken == token) {
                mutable.update { it.copy(voicePhase = phase) }
                if (phase == VoicePhase.LISTENING && !started) { started = true; metrics.record("exception_listen_start", sharedEvent?.id, incident.id) }
            } }, pcm) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { VoiceResult.Failed(error.message ?: "청취 실패") }
            if (voiceToken != token || state.value.incident?.id != incident.id) return@launch
            when (result) {
                is VoiceResult.Text -> acceptSpeech(result.text, false)
                is VoiceResult.Failed -> {
                    updateIncident(state.value.incident!!.voiceFailed())
                    mutable.update { it.copy(voicePhase = VoicePhase.UNAVAILABLE, voiceDetail = result.detail) }
                    metrics.record("exception_listen_failed", sharedEvent?.id, incident.id)
                }
                VoiceResult.NoSpeech -> {
                    updateIncident(state.value.incident!!.copy(response = ResponseState.UNCONFIRMED))
                    mutable.update { it.copy(voicePhase = VoicePhase.COMPLETE, voiceDetail = "응답을 확인하지 못했어요. 이동할 수 없으면 도움을 요청하세요") }
                    metrics.record("exception_no_speech", sharedEvent?.id, incident.id)
                }
                VoiceResult.Spoken -> Unit
            }
        }
    }
    fun acceptSpeech(text: String, injected: Boolean) {
        val incident = state.value.incident ?: return
        val heard = ResponseInterpreter.interpret(text)
        stopVoice()
        mutable.update { it.copy(voiceText = text.take(100), voicePhase = VoicePhase.COMPLETE,
            voiceInput = if (injected) "OPERATOR_TEXT" else it.voiceInput,
            voiceDetail = if (heard.acknowledged) "" else "부정·불확실한 응답은 이동 가능으로 판단하지 않아요") }
        metrics.record("occupant_response", sharedEvent?.id, incident.id, details = mapOf("input" to if (injected) "OPERATOR_TEXT" else "STT", "acknowledged" to heard.acknowledged, "responder" to heard.responder.name, "help" to heard.help.name))
        if (heard.acknowledged) respond(heard.responder.takeUnless { it == Responder.UNKNOWN } ?: incident.responder,
            if (incident.help == HelpState.NEEDS_HELP && heard.help == HelpState.UNKNOWN) HelpState.NEEDS_HELP else heard.help)
        else updateIncident(incident.copy(response = ResponseState.UNCONFIRMED))
    }
    fun respond(who: Responder, help: HelpState) {
        stopVoice()
        val incident = state.value.incident ?: return
        updateIncident(incident.respond(who, if (incident.help == HelpState.NEEDS_HELP && help == HelpState.UNKNOWN) HelpState.NEEDS_HELP else help))
        if (help == HelpState.NEEDS_HELP) saySupportException()
    }
    private fun saySupportException() {
        val incident = state.value.incident ?: return
        if (!foreground || !speech.available) return
        val token = voiceToken
        mutable.update { it.copy(voicePurpose = "HELP", voicePhase = VoicePhase.PREPARING) }
        voiceJob = viewModelScope.launch {
            val result = try { speech.say("무리하게 움직이지 마세요. 가능한 방법으로 도움을 요청하세요.") { phase -> if (voiceToken == token) mutable.update { it.copy(voicePhase = phase) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { VoiceResult.Failed(error.message ?: "음성 안내 실패") }
            if (voiceToken == token && state.value.incident?.id == incident.id) mutable.update { it.copy(voicePhase = if (result == VoiceResult.Spoken) VoicePhase.COMPLETE else VoicePhase.UNAVAILABLE) }
        }
    }
    fun startResponseTimer() { startSupportResponse() }
    fun responseTimeout() { state.value.incident?.let { updateIncident(it.copy(response = ResponseState.UNCONFIRMED)) } }
    fun voiceFailure() { stopVoice(); state.value.incident?.let { updateIncident(it.beginResponse().voiceFailed()) } }
    fun evacuate() { state.value.incident?.let { updateIncident(it.reportEvacuated()) } }
    fun resolve() { log("사고 해소는 별도 loopback 시험 관리자 경로에서만 할 수 있어요. 탑승자 지원은 유지돼요") }
    fun interrupt() { pauseVoice(); log("오디오 중단 · 도움 상태 보존") }
    fun openTrunk(finalConfirmation: Boolean = false, automatic: Boolean = false) {
        journey(state.value.journey.copy(trunk = ControlState.HELD, trunkNote = "이 프로토타입에서는 트렁크를 제어하지 않아요"))
        log("차량 제어 미실행 · 트렁크 제어는 0.6 범위 밖")
    }
    fun refreshReportLocation() {
        if (reportJob?.isActive == true) return
        mutable.update { it.copy(reportLocation = it.reportLocation.copy(status = ReportLocationStatus.LOADING)) }
        reportJob = viewModelScope.launch {
            val location = try { reportGateway.read() } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { ReportLocation(status = ReportLocationStatus.UNAVAILABLE, detail = error.message ?: "위치 응답을 받지 못했어요") }
            mutable.update { it.copy(reportLocation = location) }
            metrics.record("report_location_result", sharedEvent?.id, state.value.incident?.id, details = mapOf("status" to location.status.name, "sample" to location.sample, "fixTimeKnown" to (location.fixObservedAt != null)))
        }
    }

    private fun persistOutbox(status: StopReportStatus = state.value.stopReportStatus) {
        store.saveTransport(sharedEvent, status, outbox)
        mutable.update { it.copy(pending = outbox.size, stopReportStatus = status) }
    }
    fun setConsent(value: Boolean) {
        store.consent = value; mutable.update { it.copy(consent = value) }
        if (!value) {
            // Preserve the exact pending command: an in-flight ACK may have been lost.
            persistOutbox(if (outbox.any { it.kind == CommandKind.CREATE }) StopReportStatus.HELD else state.value.stopReportStatus)
        } else {
            if (outbox.any { it.kind == CommandKind.CREATE && it.event.id == sharedEvent?.id }) persistOutbox(StopReportStatus.QUEUED)
            maybeShare()
        }
    }
    private fun maybeShare() {
        val p = currentPosition ?: return
        if (!latestStop.qualified || !p.valid(System.currentTimeMillis())) return
        if (sharedEvent?.status == HazardState.ACTIVE) return
        if (!state.value.consent) {
            mutable.update { it.copy(stopReportStatus = StopReportStatus.HELD, stopReportDetail = "정차를 관측했어요. 위치 공유 동의가 꺼져 있어요") }; return
        }
        val local = state.value.incident
        val firstSharedForThisSupport = sharedEvent == null
        val event = Hazard(UUID.randomUUID().toString(), owner, 1, p.observedAt, p.observedAt + 180_000,
            p.road, p.direction, p.meters, HazardState.ACTIVE, if (firstSharedForThisSupport) local?.source ?: "STOP_OBSERVATION" else "STOP_OBSERVATION",
            if (firstSharedForThisSupport) local?.eventType ?: EventType.STOPPED_VEHICLE else EventType.STOPPED_VEHICLE)
        sharedEvent = event
        val command = HazardCommand(CommandKind.CREATE, event, event.toJson())
        outbox.add(command); persistOutbox(StopReportStatus.QUEUED)
        mutable.update { it.copy(stopReportId = event.id, stopReportStatus = StopReportStatus.QUEUED, stopReportDetail = "정차 위치 공유 대기 · TEST") }
        metrics.record("first_stop_observed", event.id, local?.id, utc = firstStopUtc ?: p.observedAt,
            elapsed = firstStopElapsed ?: SystemClock.elapsedRealtime(), details = mapOf("source" to "TEST", "roadKind" to p.roadKind.name))
        metrics.record("shared_queued", event.id, local?.id, details = mapOf("eventType" to event.eventType.name, "source" to event.source))
    }
    private fun maybeRetract(evidence: RestartEvidence) {
        val event = sharedEvent ?: return
        if (event.status != HazardState.ACTIVE || event.eventType != EventType.STOPPED_VEHICLE || evidence.startedAt <= event.observedAt) return
        if (outbox.any { it.kind == CommandKind.RETRACT && it.event.id == event.id }) return
        val payload = JSONObject().put("expectedVersion", event.version).put("evidence", JSONObject().put("source", "TEST")
            .put("startedAt", evidence.startedAt).put("observedAt", evidence.observedAt).put("speedMps", evidence.speedMps).put("durationMs", evidence.durationMs))
        outbox.add(HazardCommand(CommandKind.RETRACT, event, payload)); persistOutbox()
        mutable.update { it.copy(stopReportDetail = "연속 재출발 관측 · 해당 정차 알림 철회 대기") }
        metrics.record("retract_queued", event.id, state.value.incident?.id, details = mapOf("durationMs" to evidence.durationMs, "speedMps" to evidence.speedMps))
    }
    private fun acceptPosition(position: RoadPosition?, reset: Boolean = false, runId: String? = null) {
        val previous = currentPosition
        runId?.takeIf { it.isNotBlank() && it.length <= 100 }?.let { metrics.runId = it }
        val positionValid = position?.valid(System.currentTimeMillis()) == true
        val scopeChanged = positionValid && previous?.scopeKey != position?.scopeKey
        if (reset || scopeChanged || !positionValid) {
            contextGeneration++; snapshot.invalidate(clearDisplay = reset || scopeChanged); voicePolicy.reset(); stopPolicy.reset()
            latestStop = StopObservation(null, false); firstStopUtc = null; firstStopElapsed = null
        }
        currentPosition = position
        val now = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime()
        if (position == null || !position.valid(now)) {
            latestStop = stopPolicy.observe(null, now, elapsed)
            firstStopUtc = null; firstStopElapsed = null; snapshot.invalidate()
            if (sharedEvent == null) mutable.update { it.copy(stopReportStatus = StopReportStatus.HELD, stopReportDetail = "유효한 TEST 위치·속도 관측이 필요해요") }
        } else {
            latestStop = stopPolicy.observe(position, now, elapsed)
            if (latestStop.firstObservedAt != firstStopUtc) {
                firstStopUtc = latestStop.firstObservedAt; firstStopElapsed = latestStop.firstObservedAt?.let { elapsed }
            }
            if (sharedEvent == null || (sharedEvent?.status != HazardState.ACTIVE && latestStop.firstObservedAt != null)) mutable.update { it.copy(
                stopReportStatus = if (latestStop.firstObservedAt != null) StopReportStatus.CANDIDATE else StopReportStatus.NONE,
                stopReportDetail = if (latestStop.firstObservedAt != null) "TEST 정차 연속 관측 중 · 응답과 별개" else "공유할 정차 관측이 없어요") }
            maybeShare(); latestStop.restarted?.let(::maybeRetract)
            if (!reset && previous?.valid(now) == true && previous.scopeKey == position.scopeKey && position.observedAt > previous.observedAt) {
                crossingTargets.values.toList().forEach { event ->
                    if (event.road == position.road && event.direction == position.direction && previous.meters < event.meters && position.meters >= event.meters && crossedIds.add(event.id)) {
                        metrics.record("trajectory_crossing", event.id, details = mapOf("source" to "TEST", "meters" to position.meters,
                            "eventMeters" to event.meters, "positionObservedAt" to position.observedAt,
                            "receivedLeadMs" to firstReceivedElapsed[event.id]?.let { elapsed - it },
                            "voiceLeadMs" to completedVoiceElapsed[event.id]?.let { elapsed - it }))
                    }
                }
            }
        }
        voicePolicy.update(position, now, elapsed); updateSupportQualification(); evaluateReceiver()
    }
    fun scenario(value: String) {
        mutable.update { it.copy(receiverScenario = value) }
        val now = System.currentTimeMillis()
        acceptPosition(RoadPosition(if (value == "parallel") "parallel-highway" else "test-highway", if (value == "opposite") "west" else "east",
            if (value == "passed") 1_100.0 else 600.0, 25.0, if (value == "stale") now - 60_000 else now, RoadKind.MAINLINE), reset = true)
    }
    fun previewApproachingHazard() {
        val now = System.currentTimeMillis()
        previewEvent = Hazard("preview-event", if (owner == "A") "B" else "A", 1, now, now + 180_000, "test-highway", "east", 1_000.0, HazardState.ACTIVE, "MANUAL_TEST")
        mutable.update { it.copy(receiverView = true, previewHazard = true, receiverScenario = "approach") }
        acceptPosition(RoadPosition("test-highway", "east", 600.0, 25.0, now, RoadKind.MAINLINE), reset = true)
    }
    fun setReceiverEnabled(value: Boolean) {
        if (state.value.receiverEnabled == value) return
        store.receiverEnabled = value; contextGeneration++; snapshot.invalidate(clearDisplay = true); voicePolicy.reset()
        if (voiceMetricPurpose == "warning_voice") stopVoice()
        mutable.update { it.copy(receiverEnabled = value, previewHazard = false) }; previewEvent = null
        evaluateReceiver()
    }
    fun setOnline(value: Boolean) {
        contextGeneration++; snapshot.invalidate()
        mutable.update { it.copy(online = value, connected = false) }; evaluateReceiver()
    }
    private fun receiverStatus(): Pair<ReceiverStatus, String> = when {
        !state.value.receiverEnabled -> ReceiverStatus.OFF to "위험 알림 수신이 꺼져 있어요"
        !foreground -> ReceiverStatus.PAUSED to "앱을 열면 수신을 재개해요"
        !state.value.online -> ReceiverStatus.UNAVAILABLE to "연결을 확인할 수 없어요 · 이전 알림은 최신 상태 미확인"
        currentPosition?.valid(System.currentTimeMillis()) != true -> ReceiverStatus.UNAVAILABLE to "유효한 TEST 현재 위치가 필요해요"
        !snapshot.confirmed -> ReceiverStatus.UNAVAILABLE to "최신 알림을 확인하고 있어요"
        else -> ReceiverStatus.ACTIVE to "위험 알림 수신 중 · TEST"
    }
    private fun evaluateReceiver() {
        val now = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime(); val p = currentPosition
        voicePolicy.update(p, now, elapsed)
        val positionValid = p?.valid(now) == true
        val visible = if (previewEvent != null) listOfNotNull(previewEvent) else if (state.value.receiverEnabled) snapshot.events else emptyList()
        val cards = visible.filter { event -> event.owner != owner && event.status == HazardState.ACTIVE &&
            (!positionValid || (event.road == p!!.road && event.direction == p.direction && event.meters > p.meters && event.meters - p.meters <= 1_000))
        }.map { event ->
            val distance = if (positionValid) event.meters - p!!.meters else null
            val freshness = if (previewEvent != null || !positionValid) EventFreshness.UNCONFIRMED else snapshot.freshness(event, elapsed)
            WarningCard(event.id, event.eventType, distance, freshness, if (previewEvent != null) "화면 시연 TEST" else event.source,
                distance?.let { voicePolicy.held(event, it, elapsed) } == true)
        }.sortedWith(compareBy<WarningCard> { if (it.freshness == EventFreshness.FRESH) 0 else 1 }.thenBy { it.distanceMeters ?: Double.MAX_VALUE })
        val status = receiverStatus()
        mutable.update { it.copy(now = now, warningCards = cards, receiverStatus = status.first, receiverDetail = status.second,
            warnings = cards.map { c -> "전방 ${c.distanceMeters?.toInt()?.let { "$it m" } ?: "거리 미확인"} ${if (c.eventType == EventType.ACCIDENT) "사고" else "정차 차량"} · ${c.freshness.name} · TEST" },
            received = visible.map { "${it.id.take(8)} v${it.version} ${it.status} ${it.eventType}" }) }
        if (voiceMetricPurpose == "warning_voice" && (voiceContextGeneration != contextGeneration ||
                status.first != ReceiverStatus.ACTIVE || cards.none { it.incidentId == voiceIncidentId && it.freshness == EventFreshness.FRESH })) stopVoice()
        if (previewEvent != null || !foreground || status.first != ReceiverStatus.ACTIVE || voiceJob?.isActive == true) return
        // Local occupant support has priority over approach warnings, including urgent help.
        if (state.value.incident != null && (!state.value.cabinClearConfirmed || state.value.incident?.help == HelpState.NEEDS_HELP || state.value.journey.callSimulation)) return
        val event = snapshot.events.sortedBy { it.meters - p!!.meters }.firstOrNull { event ->
            val card = cards.firstOrNull { it.incidentId == event.id }
            card != null && card.freshness == EventFreshness.FRESH && !card.voiceHeld && event.id !in spokenIds &&
                (voiceAttempts[event.id] ?: 0) < 2 && card.distanceMeters!! <= (p!!.speedMps?.let { (it * 20).coerceIn(200.0, 1_000.0) } ?: 1_000.0)
        } ?: return
        speakWarning(event, event.meters - p!!.meters)
    }
    private fun speakWarning(event: Hazard, distance: Double) {
        voiceAttempts[event.id] = (voiceAttempts[event.id] ?: 0) + 1
        stopVoice(); val token = voiceToken
        voiceIncidentId = event.id; voiceMetricPurpose = "warning_voice"
        voiceContextGeneration = contextGeneration
        mutable.update { it.copy(voicePhase = VoicePhase.PREPARING, voicePurpose = "WARNING", voiceDetail = "") }
        voiceJob = viewModelScope.launch {
            var started = false
            val result = try { speech.say("전방 ${distance.toInt()}미터에 ${if (event.eventType == EventType.ACCIDENT) "사고" else "정차 차량"}가 있습니다. 주변을 살피고 주의하세요.") { phase ->
                if (voiceToken == token) {
                    mutable.update { it.copy(voicePhase = phase) }
                    if (phase == VoicePhase.SPEAKING && !started) { started = true; metrics.record("warning_voice_start", event.id, details = mapOf("distanceMeters" to distance)) }
                }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { VoiceResult.Failed(error.message ?: "음성 안내 실패") }
            if (voiceToken != token || !foreground || voiceContextGeneration != contextGeneration || !state.value.receiverEnabled ||
                state.value.warningCards.none { it.incidentId == event.id && it.freshness == EventFreshness.FRESH }) return@launch
            voiceMetricPurpose = null; voiceIncidentId = null
            if (result == VoiceResult.Spoken) {
                store.markSpoken(event.id); spokenIds.add(event.id); completedVoiceElapsed[event.id] = SystemClock.elapsedRealtime()
                metrics.record("warning_voice_complete", event.id, details = mapOf("distanceMetersAtStart" to distance))
                mutable.update { it.copy(voicePhase = VoicePhase.COMPLETE) }
            } else {
                val detail = (result as? VoiceResult.Failed)?.detail ?: "음성 완료 미확인"
                metrics.record("warning_voice_failed", event.id, details = mapOf("attempt" to voiceAttempts[event.id], "reason" to detail.take(160)))
                mutable.update { it.copy(voicePhase = VoicePhase.UNAVAILABLE, voiceDetail = detail) }
            }
        }
    }
    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value; contextGeneration++; snapshot.invalidate(); stopPolicy.reset(); latestStop = StopObservation(null, false)
        if (!value) {
            pauseVoice(); vehicleJob?.cancel(); vehicleJob = null; uploaderJob?.cancel(); uploaderJob = null; receiverJob?.cancel(); receiverJob = null
            evaluateReceiver(); return
        }
        voiceAttempts.clear()
        if (BuildConfig.DEBUG) {
            vehicleJob = viewModelScope.launch { while (foreground) { readVehicleFacts(); updateSupportQualification(); evaluateReceiver(); delay(500) } }
            uploaderJob = viewModelScope.launch { uploadLoop() }
            receiverJob = viewModelScope.launch { receiveLoop() }
        }
        updateSupportQualification(); evaluateReceiver()
    }
    private suspend fun uploadLoop() {
        var backoff = 1_000L
        while (foreground) {
            val command = outbox.firstOrNull { it.kind == CommandKind.RETRACT || state.value.consent }
            if (!state.value.online || command == null) { delay(500); continue }
            try {
                val result = uploadMutex.withLock {
                    if (!foreground || !state.value.online || (command.kind == CommandKind.CREATE && !state.value.consent) || outbox.none { it.key == command.key }) null
                    else client.send(command)
                }
                if (result == null) { delay(250); continue }
                outbox.removeAll { it.key == command.key }; backoff = 1_000L
                if (sharedEvent?.id == result.id) {
                    sharedEvent = result
                    mutable.update { it.copy(stopReportId = result.id, stopReportStatus = if (result.status == HazardState.RETRACTED) StopReportStatus.RETRACTED else StopReportStatus.SHARED,
                        stopReportDetail = if (result.status == HazardState.RETRACTED) "재출발 관측으로 해당 정차 알림을 철회했어요" else "TEST 정차 위치 공유 확인") }
                }
                persistOutbox()
                metrics.record(if (command.kind == CommandKind.CREATE) "shared_ack" else "retract_ack", result.id, state.value.incident?.id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // An invalid/mismatched ACK leaves acceptance unknown. Retry the exact command;
                // only an explicit permanent HTTP rejection can quarantine it.
                val permanent = error is HazardHttpException && !error.retryable
                if (permanent) {
                    store.quarantine(command, error.message ?: "영구 거절"); outbox.removeAll { it.key == command.key }
                    if (sharedEvent?.id == command.event.id) mutable.update { it.copy(stopReportStatus = StopReportStatus.REJECTED, stopReportDetail = "공유 요청을 접수하지 못했어요 · 개발 진단 확인") }
                    persistOutbox()
                    metrics.record("outbox_quarantined", command.event.id, details = mapOf("kind" to command.kind.name, "reason" to (error.message ?: "거절").take(160)))
                } else {
                    metrics.record("upload_retry", command.event.id, details = mapOf("kind" to command.kind.name, "delayMs" to backoff))
                    delay(backoff); backoff = (backoff * 2).coerceAtMost(8_000)
                }
            }
        }
    }
    private suspend fun receiveLoop() {
        while (foreground) {
            val p = currentPosition
            if (!state.value.receiverEnabled || !state.value.online || p?.valid(System.currentTimeMillis()) != true || previewEvent != null) {
                if (state.value.receiverEnabled && (!state.value.online || p?.valid(System.currentTimeMillis()) != true)) snapshot.invalidate()
                evaluateReceiver(); delay(500); continue
            }
            val captured = PollContext("poll-${UUID.randomUUID()}", p, contextGeneration)
            try {
                val response = receiveMutex.withLock {
                    if (!foreground || !state.value.receiverEnabled || !state.value.online || captured.generation != contextGeneration) null
                    else client.list(captured)
                }
                if (response == null) { delay(250); continue }
                val applied = snapshot.apply(response, captured, contextGeneration, currentPosition?.scopeKey, SystemClock.elapsedRealtime())
                if (applied == SnapshotApplication.APPLIED || applied == SnapshotApplication.RESET) {
                    if (applied == SnapshotApplication.RESET) { metrics.record("receiver_epoch_reset"); log("시험 서버 자료가 초기화됐어요 · 도로 안전 확인 아님") }
                    response.events.filter { it.owner != owner && it.status == HazardState.ACTIVE }.forEach { event ->
                        crossingTargets[event.id] = event
                        if (receivedIds.add(event.id)) {
                            firstReceivedElapsed[event.id] = SystemClock.elapsedRealtime()
                            metrics.record("warning_received", event.id, details = mapOf("eventType" to event.eventType.name, "distanceMeters" to (event.meters - captured.position.meters), "source" to event.source,
                                "eventObservedAt" to event.observedAt, "freshness" to snapshot.freshness(event, SystemClock.elapsedRealtime()).name))
                        }
                    }
                    mutable.update { it.copy(network = "로컬 TEST 수신 확인 · ${response.events.size}건 · 전송 대기 ${outbox.size}건", connected = true) }
                } else if (applied == SnapshotApplication.REJECTED) {
                    mutable.update { it.copy(network = "최신 상태 미확인 · 응답 순서 또는 버전 확인 필요", connected = false) }
                    metrics.record("snapshot_rejected")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (captured.generation == contextGeneration) {
                    snapshot.invalidate(); mutable.update { it.copy(network = "최신 상태 미확인 · 연결 또는 TEST 위치를 확인하세요", connected = false) }
                    metrics.record("receiver_failure", details = mapOf("reason" to (error.message ?: "수신 실패").take(160)))
                }
            }
            evaluateReceiver(); delay(1_000)
        }
    }
    fun refreshVehicle() { if (!state.value.vehicleBusy) viewModelScope.launch { readVehicleFacts() } }
    private suspend fun readVehicleFacts() {
        if (state.value.vehicleBusy) return
        mutable.update { it.copy(vehicleBusy = true) }
        try {
            val facts = gateway.readSnapshot(); val now = System.currentTimeMillis(); val s = state.value
            val observed = s.occupiedSeatAreas + facts.seats.filter { it.occupied.current(now) == true }.map { it.area }
            // Only observed seat areas can participate in departure/empty confirmation.
            val clear = s.incident != null && facts.seats.isNotEmpty() && cabinClearGate.update(facts.seats, now)
            mutable.update { it.copy(vehicle = facts, now = now, occupiedSeatAreas = if (s.incident == null) emptySet() else observed,
                cabinClearConfirmed = clear, hazardDetail = when (facts.hazardLights.current(now)) {
                    true -> "비상등 켜짐을 관측했어요"; false -> "안전하게 조작할 수 있으면 비상등을 켜세요"; null -> "비상등 상태를 확인하지 못했어요" }) }
            if (s.incident != null) {
                journey(state.value.journey.copy(stationaryConfirmed = supportStationary(), departureDeclared = state.value.journey.departureDeclared || BitboxPolicy.somebodyLeft(facts, observed, now)))
                if (clear && guidanceOutside != s.incident.id && foreground && firstActionComplete == s.incident.id && s.incident.help != HelpState.NEEDS_HELP) {
                    guidanceOutside = s.incident.id
                    // Keep the visible location/support panel; no command or automatic call is made.
                    log("관측 좌석의 비움을 확인했어요 · 차량으로 돌아가지 말고 안전한 곳에 머무르세요")
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { log("차량 상태 조회 실패: ${error.message}") }
        finally { mutable.update { it.copy(vehicleBusy = false) } }
    }
    fun checkSdk() {
        if (state.value.sdkBusy) return
        mutable.update { it.copy(sdkBusy = true) }
        viewModelScope.launch {
            val results = mutableListOf<String>()
            for (read in listOf<suspend () -> String>(gateway::readDriverDoor, gateway::readGear, gateway::readTrunk)) {
                try { results.add(read()) } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { results.add("SDK 오류: ${error.message}") }
            }
            mutable.update { it.copy(sdk = results.joinToString("\n"), sdkBusy = false) }
            try { gateway.watchAirbag({ value -> viewModelScope.launch {
                val rising = edge.update(value); store.lastDeployment = edge.last
                mutable.update { it.copy(airbag = "SDK 전개 값=$value · 구독 중") }
                if (rising) begin("SDK_AIRBAG", EventType.ACCIDENT)
            } }, { detail -> mutable.update { it.copy(airbag = detail) } }) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(airbag = "에어백 미지원/오류: ${error.message}") } }
        }
    }
    fun newLocalDemo() {
        stopVoice(); operatorPcmQueue.clear(); cabinClearGate.reset(); voicePolicy.reset(); stopPolicy.reset()
        firstActionComplete = null; firstActionAttempted = null; listeningAttempted = null; guidanceOutside = null; firstActionRequested = true
        latestStop = StopObservation(null, false); firstStopUtc = null; firstStopElapsed = null
        sharedEvent = null; store.saveTransport(null, StopReportStatus.NONE, outbox); store.saveIncident(null); store.saveJourney(null, SupportJourney())
        previewEvent = null; currentPosition = null; contextGeneration++; snapshot.invalidate(clearDisplay = true)
        receivedIds.clear(); crossingTargets.clear(); crossedIds.clear(); firstReceivedElapsed.clear(); completedVoiceElapsed.clear()
        metrics.runId = "run-${UUID.randomUUID()}"
        mutable.update { it.copy(incident = null, journey = SupportJourney(), receiverView = false, previewHazard = false,
            supportQualified = false, stopReportId = null, stopReportStatus = StopReportStatus.NONE, stopReportDetail = "새 TEST 정차 관측 전",
            voiceText = "", voiceDetail = "", voiceInput = "NONE", cabinClearConfirmed = false, occupiedSeatAreas = emptySet(), hazardCommands = 0) }
        evaluateReceiver(); metrics.record("scenario_reset"); log("새 로컬 시험 · 기존 서버 사건은 유지돼요")
    }
    fun operatorAction(intent: Intent) {
        if (!BuildConfig.DEBUG || Build.HARDWARE !in listOf("ranchu", "goldfish") || intent.action != "${getApplication<Application>().packageName}.OPERATOR") return
        when (intent.getStringExtra("command")) {
            "reset" -> newLocalDemo()
            "position" -> {
                val position = runCatching {
                    fun number(name: String) = intent.getStringExtra(name)?.toDoubleOrNull() ?: error("$name 누락/오류")
                    val speed = if (intent.hasExtra("speedMps")) number("speedMps") else null
                    RoadPosition(intent.getStringExtra("road") ?: error("road 누락"), intent.getStringExtra("direction") ?: error("direction 누락"), number("meters"), speed,
                        intent.getStringExtra("observedAt")?.toLongOrNull() ?: error("observedAt 오류"), RoadKind.valueOf(intent.getStringExtra("roadKind") ?: "UNKNOWN"))
                        .also { require(it.valid(System.currentTimeMillis())) }
                }.getOrNull()
                acceptPosition(position, intent.getStringExtra("reset") == "true" || intent.getBooleanExtra("reset", false), intent.getStringExtra("runId"))
                if (position == null) log("유효하지 않은 TEST 위치 입력 · 이전 좌표로 대체하지 않음")
            }
            "receiver_enabled" -> setReceiverEnabled(intent.getStringExtra("value") == "true")
            "scenario" -> {
                val fixtures = if (intent.getStringExtra("voice") == "fixture") listOf("story-driver.pcm", "story-mobility.pcm").map { name ->
                    val file = File(getApplication<Application>().filesDir, name)
                    if (!file.exists() || file.length() !in 2..480_000 || file.length() % 2L != 0L) { log("응답 시험 음성 파일을 준비해주세요: $name"); return }
                    file.readBytes()
                } else emptyList()
                val priorPosition = currentPosition
                newLocalDemo(); operatorPcmQueue.addAll(fixtures); firstActionRequested = intent.getStringExtra("voice") != "off"
                val collision = runCatching { CollisionContext.valueOf(intent.getStringExtra("collision").orEmpty()) }.getOrDefault(CollisionContext.UNKNOWN)
                priorPosition?.takeIf { it.valid(System.currentTimeMillis()) }?.let { acceptPosition(it, reset = true) }
                begin("OPERATOR_EVENT", if (collision in setOf(CollisionContext.FRONT, CollisionContext.REAR, CollisionContext.OTHER)) EventType.ACCIDENT else EventType.STOPPED_VEHICLE,
                    useVoice = intent.getStringExtra("voice") != "off")
                setCollision(collision); journey(state.value.journey.copy(rearClearConfirmed = intent.getStringExtra("rearClear") == "true"))
                startSupportResponse(intent.getStringExtra("voice") != "off")
            }
            "transcript" -> acceptSpeech(intent.getStringExtra("text").orEmpty().take(200), true)
            "voice" -> requestVoice()
            "pcm" -> { val file = File(getApplication<Application>().filesDir, "response-test.pcm"); if (file.exists() && file.length() in 2..480_000 && file.length() % 2L == 0L) requestVoice(file.readBytes()) }
            "receiver" -> { pauseVoice(); previewApproachingHazard() }
            "receiver_actual" -> { pauseVoice(); previewEvent = null; mutable.update { it.copy(receiverView = true, previewHazard = false) }; evaluateReceiver() }
            "receiver_scenario" -> scenario(intent.getStringExtra("value").orEmpty())
            "consent" -> setConsent(intent.getStringExtra("value") == "true")
            "online" -> setOnline(intent.getStringExtra("value") == "true")
            "theme" -> setTheme(runCatching { ThemePreference.valueOf(intent.getStringExtra("value").orEmpty()) }.getOrDefault(ThemePreference.SYSTEM))
        }
    }
    fun gleoAction(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val action = GleoContract.functionName(intent.action, getApplication<Application>().packageName) ?: return
        var status = "success"
        var message = "The local prototype state is available. No emergency service was contacted."
        try {
            val raw = intent.getStringExtra("ACTION_PARAM") ?: "{}"
            require(raw.length <= 4096)
            val params = JSONObject(raw)
            when (action) {
                "START_ROADSIDE_SUPPORT" -> {
                    begin("GLEO_REQUEST")
                    status = "success"
                    message = if (state.value.supportQualified) {
                        "Roadside support is active. Follow the on-screen safety guidance. No emergency service was contacted."
                    } else {
                        "Roadside support is active. A current stationary observation is required for the initial safety guidance. No emergency service was contacted."
                    }
                }
                "REPORT_OCCUPANT_STATUS" -> {
                    check(state.value.incident != null) { "No active roadside incident exists." }
                    val who = params.optString("responder", intent.getStringExtra("responder") ?: "")
                    val need = params.optString("help", intent.getStringExtra("help") ?: "")
                    if (who !in listOf("DRIVER", "PASSENGER") || need !in listOf("CAN_MOVE", "NEEDS_HELP")) {
                        status = "asking"; message = "Please provide your role and whether you need help."
                    } else respond(Responder.valueOf(who), HelpState.valueOf(need))
                }
            }
        } catch (error: Exception) { status = "fail"; message = error.message ?: "Invalid action parameters." }
        val response = JSONObject().apply {
            put("functionName", action); put("status", status); put("message", message); put("mode", "voice")
            state.value.incident?.let { current ->
                put("data", org.json.JSONArray().put(JSONObject().apply {
                    put("incidentId", current.id); put("responseState", current.response.name)
                    put("responder", current.responder.name); put("help", current.help.name)
                    put("evacuated", current.evacuated); put("hazard", current.hazard.name)
                    put("emergencyDispatchConfirmed", false)
                }).toString())
            }
        }
        val callback = Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE")
            .putExtra("packageName", getApplication<Application>().packageName)
            .putExtra("response", response.toString())
        val context = getApplication<Application>()
        val receivers = context.packageManager.queryBroadcastReceivers(callback, PackageManager.MATCH_ALL)
        // The installed emulator registers its receiver dynamically, so PackageManager
        // receiver enumeration alone cannot discover it. Package name verified on v2.1.
        val emulatorGleo = runCatching {
            context.packageManager.getApplicationInfo("ai.umos.ambientai", 0).packageName
        }.getOrNull()
        val targets = (receivers.map { it.activityInfo.packageName } + listOfNotNull(emulatorGleo)).distinct()
        targets.forEach { pkg ->
            context.sendBroadcast(Intent(callback).setPackage(pkg))
        }
        val result = "Gleo Intent 수신: $action → $status · callback 전송 대상 ${targets.size}개"
        mutable.update { it.copy(gleo = "$result\n음성 대화 성공을 의미하지 않음") }; log(result)
        Log.i("RoadsidePrototype", "GLEO_CALLBACK $response")
    }

    override fun onCleared() { stopVoice(); reportJob?.cancel(); reportGateway.close(); gateway.close(); super.onCleared() }
}
