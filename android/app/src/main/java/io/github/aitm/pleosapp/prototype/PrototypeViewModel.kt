package io.github.aitm.pleosapp.prototype

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.aitm.pleosapp.BuildConfig
import io.github.aitm.pleosapp.vehicle.VehicleGatewayFactory
import io.github.aitm.pleosapp.vehicle.VehicleSnapshot
import io.github.aitm.pleosapp.vehicle.CabinClearGate
import io.github.aitm.pleosapp.vehicle.CabinPolicy
import android.os.SystemClock
import java.util.UUID
import java.io.File
import android.os.Build
import io.github.aitm.pleosapp.speech.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray

data class PrototypeUi(
    val incident: Incident? = null,
    val consent: Boolean = false,
    val sdk: String = "SDK 점검 전",
    val airbag: String = "에어백 구독 전",
    val trunk: String = "제어 전 · 명시적 버튼 요청만 허용",
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
    val voicePurpose: String = "QUESTION",
    val drivingRestricted: Boolean = BuildConfig.FLAVOR == "pleos",
    val cabinClearConfirmed: Boolean = false,
    val occupiedSeatAreas: Set<Int> = emptySet(),
    val hazardAction: ControlState = ControlState.IDLE,
    val hazardCommands: Int = 0,
    val hazardDetail: String = "",
)

class PrototypeViewModel(application: Application) : AndroidViewModel(application) {
    val owner = if (BuildConfig.FLAVOR == "pleos") "A" else "B"
    private val gateway = VehicleGatewayFactory.create(application)
    private val speech = SpeechGatewayFactory.create(application)
    private var voiceJob: Job? = null
    private var hazardJob: Job? = null
    private var voiceToken = 0L
    private var voiceAuto = false
    private val operatorPcmQueue = ArrayDeque<ByteArray>()
    private var warningIds = emptyList<String>()
    private val cabinClearGate = CabinClearGate()
    private val spokenGuidance = mutableSetOf<String>()
    private val store = PrototypeStore(application)
    private val client = HazardClient(owner)
    private val edge = DeploymentEdge(store.lastDeployment)
    private var outbox = store.outbox().toMutableList()
    private var hazards = emptyList<Hazard>()
    private val seenWarnings = mutableSetOf<String>()
    private val restoredIncident = store.incident()
    private val mutable = MutableStateFlow(PrototypeUi(
        incident = restoredIncident, consent = store.consent, pending = outbox.size,
        theme = store.theme, journey = store.journey(restoredIncident?.id),
        hazardAction = if (restoredIncident?.let { store.hazardAttempted(it.id) } == true) ControlState.UNCONFIRMED else ControlState.IDLE,
        hazardCommands = restoredIncident?.let { store.hazardCommands(it.id) } ?: 0,
    ))
    val state = mutable.asStateFlow()
    val sourceLabel = gateway.sourceLabel
    private var timer: Job? = null
    private var networkJob: Job? = null
    private var foreground = false
    private var vehicleJob: Job? = null
    private var responseDeadline = 0L
    private var responseRemaining = 0L
    private var previewEvent: Hazard? = null

    init {
        gateway.watchDrivingRestrictions { restricted ->
            viewModelScope.launch {
                mutable.update { it.copy(drivingRestricted = restricted) }
                if (restricted) { stopVoice(); stopTimer() }
                else if (voiceAuto && state.value.incident?.response in listOf(ResponseState.WAITING, ResponseState.RETRYING)) requestVoice()
            }
        }
        if (BuildConfig.DEBUG) viewModelScope.launch {
            while (true) {
                val s = state.value
                val payload = JSONObject().apply {
                    put("heartbeat", android.os.SystemClock.elapsedRealtime()); put("visible", foreground)
                    put("screen", if (s.drivingRestricted) "DRIVING" else if (s.receiverView) "RECEIVER" else SupportPolicy.screen(s.incident, s.journey, s.cabinClearConfirmed).name)
                    put("source", s.incident?.source ?: "NONE"); put("collision", s.journey.collision.name)
                    put("incidentId", s.incident?.id ?: JSONObject.NULL); put("owner", owner)
                    put("consent", s.consent); put("pending", s.pending); put("connected", s.connected)
                    put("warningIds", JSONArray(warningIds)); put("received", JSONArray(s.received))
                    put("evacuated", s.incident?.evacuated ?: false)
                    put("voice", s.voicePhase.name); put("voiceInput", s.voiceInput); put("voiceDetail", s.voiceDetail)
                    put("voiceText", s.voiceText)
                    put("response", s.incident?.response?.name); put("help", s.incident?.help?.name)
                    put("responder", s.incident?.responder?.name)
                    put("hazard", s.incident?.hazard?.name); put("trunk", s.journey.trunk.name)
                    put("rearClearConfirmed", s.journey.rearClearConfirmed); put("autoTrunkClaimed", s.journey.autoTrunkClaimed)
                    put("trunkAttempts", s.journey.trunkAttempts); put("restricted", s.drivingRestricted)
                    put("gear", s.vehicle.gear.current(s.now) ?: JSONObject.NULL)
                    put("doorOpen", s.vehicle.driverDoorOpen.current(s.now) ?: JSONObject.NULL)
                    put("trunkOpen", s.vehicle.trunkOpen.current(s.now) ?: JSONObject.NULL)
                    put("occupied", s.vehicle.driverOccupied.current(s.now) ?: JSONObject.NULL)
                    put("occupancyDetail", s.vehicle.driverOccupied.detail)
                    put("cabinPresence", CabinPolicy.presence(s.vehicle.seats, s.now).name)
                    put("cabinClearConfirmed", s.cabinClearConfirmed)
                    put("stationary", BitboxPolicy.stationary(s.vehicle, s.now))
                    put("hazardLights", s.vehicle.hazardLights.current(s.now) ?: JSONObject.NULL)
                    put("hazardLightsDetail", s.vehicle.hazardLights.detail)
                    put("hazardControlMode", File(application.filesDir, "hazard-control-mode.txt").takeIf { it.exists() }?.readText()?.trim() ?: "VEHICLE_SERVICE")
                    put("hazardAction", s.hazardAction.name); put("hazardCommands", s.hazardCommands); put("hazardDetail", s.hazardDetail)
                    put("speedMps", s.vehicle.speedMps.current(s.now) ?: JSONObject.NULL)
                    put("speedDetail", s.vehicle.speedMps.detail)
                    put("voicePurpose", s.voicePurpose)
                    put("occupiedSeatAreas", JSONArray(s.occupiedSeatAreas.sorted()))
                    put("seats", JSONArray(s.vehicle.seats.map { seat -> JSONObject().apply {
                        put("area", seat.area); put("label", seat.label)
                        put("occupied", seat.occupied.current(s.now) ?: JSONObject.NULL)
                        put("detail", seat.occupied.detail)
                    } }))
                    put("belt", s.vehicle.driverBelt.current(s.now) ?: JSONObject.NULL)
                    put("sample", s.vehicle.sample)
                }
                runCatching {
                    val temp = File(application.filesDir, "operator-state.tmp")
                    temp.writeText(payload.toString()); temp.renameTo(File(application.filesDir, "operator-state.json"))
                }
                delay(500)
            }
        }
    }

    fun chooseSituation(value: CollisionContext) { setCollision(value); startSupportResponse(speech.available) }
    fun startSupportResponse(useVoice: Boolean = true) {
        val incident = state.value.incident ?: return
        stopTimer()
        updateIncident(incident.beginResponse())
        voiceAuto = useVoice && speech.available
        if (voiceAuto) requestVoice()
    }
    private fun stopVoice() {
        voiceToken++; voiceJob?.cancel(); voiceJob = null
        mutable.update { it.copy(voicePhase = VoicePhase.IDLE) }
    }
    fun pauseVoice() {
        operatorPcmQueue.clear()
        stopVoice(); voiceAuto = false
        mutable.update { it.copy(voicePhase = VoicePhase.PAUSED) }
    }
    fun requestVoice(testPcm: ByteArray? = null) {
        val s = state.value
        val screen = SupportPolicy.screen(s.incident, s.journey, s.cabinClearConfirmed)
        if (!foreground || s.drivingRestricted || screen !in listOf(SupportScreen.RESPONSE, SupportScreen.MOBILITY)) return
        stopVoice(); stopTimer()
        val token = voiceToken
        val id = s.incident?.id
        val pcm = testPcm ?: operatorPcmQueue.removeFirstOrNull()
        voiceAuto = true
        mutable.update { it.copy(voicePhase = VoicePhase.PREPARING, voicePurpose = "QUESTION", voiceText = "", voiceDetail = "", voiceInput = if (pcm == null) "MICROPHONE" else "SYNTHETIC_PCM") }
        val prompt = if (screen == SupportScreen.MOBILITY) "스스로 움직일 수 있나요? 움직일 수 있어요, 또는 도움이 필요해요라고 답해주세요."
            else "제 말이 들리나요? 운전자 또는 동승자인지 말씀해 주세요. 도움이 필요하면 도와주세요라고 말씀해 주세요."
        voiceJob = viewModelScope.launch {
            val result = speech.ask(prompt, { phase ->
                if (voiceToken == token) mutable.update { it.copy(voicePhase = phase) }
            }, pcm)
            if (voiceToken != token || state.value.incident?.id != id || SupportPolicy.screen(state.value.incident, state.value.journey, state.value.cabinClearConfirmed) != screen) return@launch
            when (result) {
                is VoiceResult.Text -> acceptSpeech(result.text, false)
                VoiceResult.Spoken -> Unit
                is VoiceResult.Failed -> {
                    mutable.update { it.copy(voicePhase = VoicePhase.UNAVAILABLE, voiceDetail = result.detail) }
                    state.value.incident?.let { updateIncident(it.voiceFailed()) }
                }
                VoiceResult.NoSpeech -> {
                    mutable.update { it.copy(voicePhase = VoicePhase.COMPLETE, voiceDetail = "답변이 들리지 않았어요") }
                    state.value.incident?.let { updateIncident(it.timeout()) }
                    if (voiceAuto && state.value.incident?.response == ResponseState.RETRYING) requestVoice()
                }
            }
        }
    }
    fun acceptSpeech(text: String, injected: Boolean) {
        val s = state.value
        if (s.drivingRestricted || SupportPolicy.screen(s.incident, s.journey, s.cabinClearConfirmed) !in listOf(SupportScreen.RESPONSE, SupportScreen.MOBILITY)) return
        val heard = ResponseInterpreter.interpret(text)
        stopVoice()
        mutable.update { it.copy(voiceText = text.take(100), voicePhase = VoicePhase.COMPLETE,
            voiceInput = if (injected) "OPERATOR_TEXT" else it.voiceInput,
            voiceDetail = if (heard.acknowledged) "" else "답변을 이해하지 못했어요. 다시 말씀하거나 화면으로 답해주세요.") }
        if (heard.acknowledged) respond(heard.responder.takeUnless { it == Responder.UNKNOWN } ?: s.incident!!.responder, heard.help)
        else if (s.incident?.response in listOf(ResponseState.WAITING, ResponseState.RETRYING)) {
            updateIncident(s.incident!!.timeout())
            if (voiceAuto && state.value.incident?.response == ResponseState.RETRYING) requestVoice()
        }
    }
    fun operatorAction(intent: Intent) {
        if (!BuildConfig.DEBUG || Build.HARDWARE !in listOf("ranchu", "goldfish") || intent.action != "${getApplication<Application>().packageName}.OPERATOR") return
        when (intent.getStringExtra("command")) {
            "reset" -> newLocalDemo()
            "scenario" -> {
                val scriptedVoice = intent.getStringExtra("voice") == "fixture"
                val fixtures = if (scriptedVoice) listOf("story-driver.pcm", "story-mobility.pcm").map { name ->
                    val file = File(getApplication<Application>().filesDir, name)
                    if (!file.exists() || file.length() !in 2..480_000 || file.length() % 2L != 0L) {
                        log("응답 시험 음성 파일을 먼저 준비해주세요: $name"); return
                    }
                    file.readBytes()
                } else emptyList()
                newLocalDemo(); begin("OPERATOR_EVENT")
                setCollision(runCatching { CollisionContext.valueOf(intent.getStringExtra("collision").orEmpty()) }.getOrDefault(CollisionContext.UNKNOWN))
                journey(state.value.journey.copy(rearClearConfirmed = intent.getStringExtra("rearClear") == "true"))
                operatorPcmQueue.addAll(fixtures)
                startSupportResponse(intent.getStringExtra("voice") != "off")
            }
            "transcript" -> { voiceAuto = false; acceptSpeech(intent.getStringExtra("text").orEmpty().take(200), true) }
            "voice" -> requestVoice()
            "pcm" -> {
                val file = File(getApplication<Application>().filesDir, "response-test.pcm")
                if (file.exists() && file.length() in 2..480_000 && file.length() % 2L == 0L) requestVoice(file.readBytes())
            }
            "receiver" -> { pauseVoice(); previewApproachingHazard() }
            "receiver_actual" -> {
                pauseVoice(); previewEvent = null
                mutable.update { it.copy(receiverView = true, previewHazard = false, receiverScenario = "approach") }
                evaluate()
            }
            "receiver_scenario" -> scenario(intent.getStringExtra("value").orEmpty())
            "consent" -> setConsent(intent.getStringExtra("value") == "true")
            "theme" -> setTheme(runCatching { ThemePreference.valueOf(intent.getStringExtra("value").orEmpty()) }.getOrDefault(ThemePreference.SYSTEM))
        }
    }

    fun setTheme(value: ThemePreference) { store.theme = value; mutable.update { it.copy(theme = value) } }
    private fun journey(value: SupportJourney) {
        store.saveJourney(state.value.incident?.id, value)
        mutable.update { it.copy(journey = value) }
    }
    fun setCollision(value: CollisionContext) {
        val current = state.value.journey
        journey(current.copy(collision = value,
            trunk = if (value == CollisionContext.REAR) ControlState.HELD else current.trunk,
            trunkNote = if (value == CollisionContext.REAR) "후방 충돌이 보고되어 트렁크 열기를 보류해요." else current.trunkNote))
    }
    fun confirmStationary(value: Boolean) { journey(state.value.journey.copy(stationaryConfirmed = value, routeConfirmed = false)) }
    fun confirmRoute(value: Boolean) { journey(state.value.journey.copy(routeConfirmed = value)) }
    fun finishGuidance() {
        val s = state.value
        if (s.incident?.help != HelpState.CAN_MOVE || !s.journey.stationaryConfirmed || !s.journey.routeConfirmed) return
        journey(s.journey.copy(departureDeclared = true))
        log("대피 안내 종료 · 대피 완료와 도로 위험 해제는 별도")
    }
    fun simulateContact(active: Boolean) {
        interrupt()
        journey(state.value.journey.copy(callSimulation = active))
        log(if (active) "연락 과정 시연 · 실제 전화/신고 없음" else "연락 과정 시연 종료 · 접수 미확인")
    }
    fun receiverView(value: Boolean) { mutable.update { it.copy(receiverView = value) } }
    fun previewApproachingHazard() {
        val now = System.currentTimeMillis()
        previewEvent = Hazard("preview-event", "sample-other", 1, now, now + 180_000, "test-highway", "east", 1000.0, HazardState.ACTIVE, "화면 시연")
        mutable.update { it.copy(receiverView = true, previewHazard = true, receiverScenario = "approach") }
        evaluate()
    }
    fun newLocalDemo() {
        if (state.value.trunkBusy) return
        stopVoice(); voiceAuto = false
        hazardJob?.cancel(); hazardJob = null
        mutable.update { it.copy(hazardAction = ControlState.IDLE, hazardCommands = 0, hazardDetail = "") }
        operatorPcmQueue.clear()
        cabinClearGate.reset(); spokenGuidance.clear()
        stopTimer()
        previewEvent = null
        store.saveIncident(null)
        mutable.update { it.copy(incident = null, journey = SupportJourney(), receiverView = false, previewHazard = false, receiverScenario = "approach", trunk = "제어 전", voiceText = "", voiceDetail = "", voiceInput = "NONE", cabinClearConfirmed = false, occupiedSeatAreas = emptySet()) }
        store.saveJourney(null, SupportJourney())
        evaluate()
        log("새 로컬 시연 · 기존 서버 사건을 종료하지 않음")
    }
    fun refreshVehicle() {
        if (state.value.vehicleBusy) return
        viewModelScope.launch { readVehicleFacts() }
    }
    private suspend fun readVehicleFacts() {
        if (state.value.vehicleBusy) return
        mutable.update { it.copy(vehicleBusy = true) }
        try {
            val facts = gateway.readSnapshot()
            val now = System.currentTimeMillis()
            val clear = state.value.incident != null && cabinClearGate.update(facts.seats, now)
            if (clear && !state.value.cabinClearConfirmed) { stopVoice(); stopTimer() }
            mutable.update { it.copy(vehicle = facts, now = now, cabinClearConfirmed = clear,
                occupiedSeatAreas = if (it.incident == null) emptySet() else it.occupiedSeatAreas + facts.seats.filter { seat -> seat.occupied.current(now) == true }.map { seat -> seat.area }) }
            val latest = state.value
            if (latest.incident != null) {
                val departed = latest.journey.departureDeclared || BitboxPolicy.somebodyLeft(facts, latest.occupiedSeatAreas, now)
                val stationary = BitboxPolicy.stationary(facts, now)
                if (latest.journey.stationaryConfirmed != stationary || latest.journey.departureDeclared != departed)
                    journey(latest.journey.copy(stationaryConfirmed = stationary, departureDeclared = departed))
                if (!clear && !latest.drivingRestricted && !latest.trunkBusy &&
                    SupportPolicy.shouldAutoOpenTrunk(latest.incident, state.value.journey, facts, now)) openTrunk(true, automatic = true)
                if (clear) announceGuidance("outside")
                else if (latest.incident.help == HelpState.CAN_MOVE && stationary) announceGuidance("evacuation")
            }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { log("차량 상태 조회 실패: ${error.message}") }
        finally { mutable.update { it.copy(vehicleBusy = false) } }
    }

    private fun log(message: String) {
        Log.i("RoadsidePrototype", "$owner $message")
        mutable.update { it.copy(logs = (listOf(message) + it.logs).take(15)) }
    }
    private fun updateIncident(value: Incident) {
        store.saveIncident(value)
        mutable.update { it.copy(incident = value) }
        log("STATE ${value.id.take(8)} response=${value.response} responder=${value.responder} help=${value.help} evacuated=${value.evacuated} hazard=${value.hazard} v=${value.version}")
    }
    private fun persistOutbox() {
        store.saveOutbox(outbox)
        mutable.update { it.copy(pending = outbox.size) }
    }
    private fun enqueue(value: Incident) {
        if (!state.value.consent) { log("위치 공유 동의 없음 · 전파 보류"); return }
        val payload = value.toSharedJson(owner)
        if (outbox.none { it.getString("id") == value.id && it.getInt("version") == value.version }) {
            outbox.add(payload); persistOutbox()
        }
    }
    fun setConsent(value: Boolean) {
        store.consent = value
        mutable.update { it.copy(consent = value) }
        if (!value) { outbox.clear(); persistOutbox() }
        else state.value.incident?.takeIf { it.hazard == HazardState.ACTIVE }?.let(::enqueue)
    }
    fun begin(source: String = "MANUAL_TEST") {
        if (!BuildConfig.DEBUG) return
        val current = state.value.incident
        if (current != null && current.hazard == HazardState.ACTIVE) {
            log("활성 사건 재사용 · 중복 사건 생성 안 함"); return
        }
        timer?.cancel()
        val incident = Incident(UUID.randomUUID().toString(), System.currentTimeMillis(), source)
        cabinClearGate.reset(); spokenGuidance.clear()
        updateIncident(incident)
        journey(SupportJourney())
        mutable.update { it.copy(receiverView = false, cabinClearConfirmed = false,
            occupiedSeatAreas = it.vehicle.seats.filter { seat -> seat.occupied.current(System.currentTimeMillis()) == true }.map { seat -> seat.area }.toSet()) }
        enqueue(incident)
        activateHazards(incident.id)
        log("사건 접수 · 비상등 자동 실행과 위험 정보 전파 시작")
    }
    private fun activateHazards(id: String) {
        if (!BuildConfig.DEBUG || !store.claimHazard(id)) return
        mutable.update { it.copy(hazardAction = ControlState.CHECKING, hazardDetail = "비상등 자동 켜기 요청") }
        hazardJob = viewModelScope.launch {
            try {
                val detail = gateway.enableHazardLights {
                    store.recordHazardCommand(id)
                    if (state.value.incident?.id == id) mutable.update { it.copy(hazardCommands = 1) }
                }
                if (state.value.incident?.id == id) mutable.update { it.copy(hazardAction = ControlState.CONFIRMED, hazardDetail = detail) }
                log("비상등: $detail")
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                if (state.value.incident?.id == id) mutable.update { it.copy(hazardAction = ControlState.UNCONFIRMED, hazardDetail = "비상등 제어 시간 초과") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (state.value.incident?.id == id) mutable.update { it.copy(hazardAction = ControlState.UNCONFIRMED, hazardDetail = error.message.orEmpty()) }
                log("비상등 자동 켜기 미확인: ${error.message}")
            }
            refreshVehicle()
        }
    }
    fun startResponseTimer() {
        val current = state.value.incident ?: return
        if (current.response !in listOf(ResponseState.IDLE, ResponseState.WAITING, ResponseState.RETRYING)) return
        if (state.value.timerRunning) return
        updateIncident(current.beginResponse())
        responseRemaining = 15_000
        resumeTimer()
        log("모의 응답 대기 시작 · 15초는 시연용 값")
    }
    private fun resumeTimer() {
        if (!foreground || responseRemaining <= 0 || state.value.journey.callSimulation) return
        if (state.value.incident?.response !in listOf(ResponseState.WAITING, ResponseState.RETRYING)) return
        timer?.cancel()
        mutable.update { it.copy(timerRunning = true) }
        timer = viewModelScope.launch {
            while (foreground) {
                responseDeadline = SystemClock.elapsedRealtime() + responseRemaining
                delay(responseRemaining)
                responseRemaining = 15_000
                responseTimeout()
                if (state.value.incident?.response != ResponseState.RETRYING) break
            }
            mutable.update { it.copy(timerRunning = false) }
        }
    }
    fun responseTimeout() {
        state.value.incident?.let { updateIncident(it.timeout()) }
                    if (voiceAuto && state.value.incident?.response == ResponseState.RETRYING) requestVoice()
        if (state.value.incident?.response == ResponseState.UNCONFIRMED) stopTimer()
    }
    private fun stopTimer() { timer?.cancel(); timer = null; responseRemaining = 0; mutable.update { it.copy(timerRunning = false) } }
    fun respond(who: Responder, help: HelpState) {
        stopVoice()
        state.value.incident?.let { updateIncident(it.respond(who, help)) }
        stopTimer()
        if (voiceAuto && help == HelpState.UNKNOWN) requestVoice()
        else if (voiceAuto && help == HelpState.CAN_MOVE) announceGuidance("evacuation")
    }
    private fun announceGuidance(kind: String) {
        val s = state.value
        if (!foreground || s.drivingRestricted || !speech.available || s.incident == null || kind in spokenGuidance) return
        if (kind == "evacuation" && !BitboxPolicy.stationary(s.vehicle, s.now)) return
        // This is output only: no microphone opens after the response questions.
        spokenGuidance.add(kind)
        stopVoice()
        val token = voiceToken
        val id = s.incident.id
        val prompt = if (kind == "outside") "차량으로 돌아가지 말고 안전한 곳에 머무르세요. 안전을 확보한 뒤 스마트폰으로 신고하세요."
            else if (s.journey.collision == CollisionContext.REAR) "후방 충돌로 트렁크는 열지 않을게요. 주변 차량을 살피고 안전하게 내릴 수 있다면 가드레일 밖 등 안전한 곳으로 이동하세요."
            else "주변 차량을 살피고 안전하게 내릴 수 있다면 가드레일 밖 등 안전한 곳으로 이동하세요."
        mutable.update { it.copy(voicePhase = VoicePhase.PREPARING, voicePurpose = "GUIDANCE", voiceDetail = "") }
        voiceJob = viewModelScope.launch {
            val result = speech.say(prompt) { phase -> if (voiceToken == token) mutable.update { it.copy(voicePhase = phase) } }
            if (voiceToken == token && state.value.incident?.id == id) mutable.update {
                it.copy(voicePhase = if (result is VoiceResult.Failed) VoicePhase.UNAVAILABLE else VoicePhase.COMPLETE)
            }
        }
    }
    fun voiceFailure() {
        stopVoice(); voiceAuto = false
        state.value.incident?.let { updateIncident(it.beginResponse().voiceFailed()) }
        stopTimer()
    }
    fun evacuate() { state.value.incident?.let { updateIncident(it.reportEvacuated()) } }
    fun resolve() {
        state.value.incident?.let { updateIncident(it.resolve()); enqueue(it.resolve()) }
        log("테스트 제보자가 해당 사건만 해제 · 대피 버튼과 별도")
    }
    fun interrupt() { pauseVoice(); stopTimer(); log("긴급 통화 중단 모의 · 음성 대기 중단, 사건 보존") }
    fun setOnline(value: Boolean) { mutable.update { it.copy(online = value) } }
    fun scenario(value: String) { mutable.update { it.copy(receiverScenario = value) }; evaluate() }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) {
            pauseVoice()
            if (state.value.timerRunning) responseRemaining = (responseDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)
            timer?.cancel(); timer = null
            mutable.update { it.copy(timerRunning = false) }
            networkJob?.cancel(); networkJob = null
            vehicleJob?.cancel(); vehicleJob = null
            return
        }
        resumeTimer()
        if (vehicleJob == null && BuildConfig.DEBUG) vehicleJob = viewModelScope.launch {
            while (foreground) { readVehicleFacts(); delay(1_000) }
        }
        if (networkJob == null && BuildConfig.DEBUG) networkJob = viewModelScope.launch {
            while (foreground) {
                if (state.value.online) syncNetwork() else {
                    mutable.update { it.copy(network = "통신 중단 모의 · 전파 미확인", connected = false) }; evaluate()
                }
                delay(2_000)
            }
        }
    }
    private suspend fun syncNetwork() {
        try {
            while (state.value.consent && outbox.isNotEmpty()) {
                val next = outbox.first()
                if (next.getLong("expiresAt") <= System.currentTimeMillis() && next.getString("status") == "ACTIVE") {
                    val id = next.getString("id")
                    outbox.removeAll { it.getString("id") == id }; persistOutbox()
                    log("미접수 사건 만료 · 오래된 사고 재전파 안 함"); continue
                }
                val result = client.post(next)
                check(result.optBoolean("accepted")) { "서버 접수 미확인" }
                outbox.removeAll { it.getString("id") == next.getString("id") && it.getInt("version") == next.getInt("version") }
                persistOutbox(); log("서버 ACK ${next.getString("id").take(8)} v=${next.getInt("version")}")
            }
            hazards = client.list()
            mutable.update { it.copy(network = "로컬 서버 연결 · 사건 ${hazards.size}개 · 대기 ${outbox.size}개", connected = true) }
            evaluate()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            mutable.update { it.copy(network = "전파/수신 미확인: ${error.message?.take(160)}", connected = false) }
            evaluate()
        }
    }
    private fun evaluate() {
        val now = System.currentTimeMillis()
        val mode = state.value.receiverScenario
        val position = RoadPosition(
            if (mode == "parallel") "parallel-road" else "test-highway",
            if (mode == "opposite") "west" else "east",
            if (mode == "passed") 1100.0 else 600.0, 25.0,
            if (mode == "stale") now - 60_000 else now,
        )
        val evaluated = (hazards + listOfNotNull(previewEvent)).map { it to WarningPolicy.evaluate(it, owner, position, now) }
        warningIds = evaluated.filter { it.second == WarningDecision.WARN }.map { it.first.id }
        val warnings = evaluated.filter { it.second == WarningDecision.WARN }.map { (hazard, _) ->
            val key = "${hazard.id}:${hazard.version}"
            if (seenWarnings.add(key)) log("WARN 전방 400m 모의 위험 · ${hazard.id.take(8)}")
            "전방 400m 위험 (모의 위치) · ${hazard.source} · ${(now-hazard.observedAt)/1000}초 전"
        }
        mutable.update { it.copy(
            now = now,
            warnings = warnings,
            received = evaluated.map { (h, d) -> "${h.id.take(8)} v${h.version} ${h.status} → $d" },
        ) }
    }

    fun checkSdk() {
        if (state.value.sdkBusy) return
        mutable.update { it.copy(sdkBusy = true) }
        viewModelScope.launch {
            val results = mutableListOf<String>()
            for (read in listOf<suspend () -> String>(gateway::readDriverDoor, gateway::readGear, gateway::readTrunk)) {
                try { results.add(read()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { results.add("SDK 오류: ${error.message}") }
            }
            mutable.update { it.copy(sdk = results.joinToString("\n"), sdkBusy = false) }
            log("SDK ${results.joinToString(" / ")}")
            try {
                gateway.watchAirbag(
                    onValue = { value -> viewModelScope.launch {
                        val rising = edge.update(value); store.lastDeployment = edge.last
                        mutable.update { it.copy(airbag = "SDK 전개 값=$value · 구독 중") }
                        log("SDK airbag=$value rising=$rising")
                        if (rising) begin("SDK_AIRBAG")
                    } },
                    onError = { message -> mutable.update { it.copy(airbag = message) }; log(message) },
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.update { it.copy(airbag = "에어백 미지원/오류: ${error.message}") }; log(state.value.airbag)
            }
        }
    }
    fun openTrunk(finalConfirmation: Boolean = false, automatic: Boolean = false) {
        if (!BuildConfig.DEBUG || state.value.trunkBusy || state.value.incident == null || state.value.drivingRestricted) return
        val current = state.value
        if (current.incident?.help != HelpState.CAN_MOVE || current.incident.hazard != HazardState.ACTIVE) {
            journey(current.journey.copy(trunk = ControlState.HELD, trunkNote = "응답과 대피 가능 여부를 먼저 확인해주세요."))
            return
        }
        if (automatic && !SupportPolicy.shouldAutoOpenTrunk(current.incident, current.journey, current.vehicle, System.currentTimeMillis())) return
        val blocked = SupportPolicy.trunkBlock(current.journey, current.vehicle, System.currentTimeMillis(), finalConfirmation)
        if (blocked != null) { journey(current.journey.copy(trunk = ControlState.HELD, trunkNote = blocked)); return }
        mutable.update { it.copy(trunkBusy = true) }
        journey(current.journey.copy(trunk = ControlState.CHECKING, autoTrunkClaimed = automatic || current.journey.autoTrunkClaimed, trunkNote = "차량에서 실제 열림 상태를 확인하고 있어요"))
        viewModelScope.launch {
            var commandRequested = false
            try {
                val facts = gateway.readSnapshot()
                mutable.update { it.copy(vehicle = facts, now = System.currentTimeMillis()) }
                // Conditions may change while the fresh SDK snapshot is in flight.
                check(state.value.incident?.id == current.incident.id &&
                    state.value.incident?.hazard == HazardState.ACTIVE &&
                    state.value.incident?.help == HelpState.CAN_MOVE && !state.value.drivingRestricted) { "지원 상태가 바뀌어 차량 제어를 보류해요." }
                if (automatic) check(state.value.journey.collision == CollisionContext.FRONT && state.value.journey.rearClearConfirmed) { "후방 개방 조건이 바뀌어 열기를 보류해요." }
                val reason = SupportPolicy.trunkBlock(state.value.journey, facts, System.currentTimeMillis(), finalConfirmation)
                check(reason == null) { reason.orEmpty() }
                if (facts.trunkOpen.current(System.currentTimeMillis()) == true) {
                    journey(state.value.journey.copy(trunk = ControlState.CONFIRMED, trunkNote = "이미 열린 상태를 확인했어요"))
                    return@launch
                }
                journey(state.value.journey.copy(trunkAttempts = state.value.journey.trunkAttempts + 1))
                commandRequested = true
                val result = gateway.openTrunk()
                mutable.update { it.copy(trunk = result) }
                journey(state.value.journey.copy(trunk = ControlState.CONFIRMED, trunkNote = if (facts.sample) "샘플 열림 확인 · 실제 차량 제어 없음" else "차량에서 트렁크 열림을 확인했어요"))
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.update { it.copy(trunk = "제어 실패/차단: ${error.message}") }
                journey(state.value.journey.copy(
                    trunk = if (commandRequested) ControlState.UNCONFIRMED else ControlState.HELD,
                    trunkNote = if (commandRequested) "열림을 확인하지 못했어요. 대피 안내는 계속할 수 있어요." else error.message ?: "상태 미확인으로 열기를 보류해요.",
                ))
            }
            finally { mutable.update { it.copy(trunkBusy = false) }; log(state.value.trunk) }
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
                    status = "asking"; message = "Please indicate whether you are the driver or a passenger and whether you need help."
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

    override fun onCleared() { stopVoice(); gateway.close(); super.onCleared() }
}
