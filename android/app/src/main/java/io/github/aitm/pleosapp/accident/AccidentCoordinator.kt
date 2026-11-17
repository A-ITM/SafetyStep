package io.github.aitm.pleosapp.accident

import android.content.Context
import android.os.SystemClock
import io.github.aitm.pleosapp.location.AccidentLocationGatewayFactory
import io.github.aitm.pleosapp.prototype.*
import java.util.UUID
import kotlinx.coroutines.*

/** Foreground prototype transport; local occupant support never waits for these jobs. */
class AccidentCoordinator(
    context: Context, private val scope: CoroutineScope, private val owner: String,
    private val onState: (AccidentNetworkUi) -> Unit,
) : AutoCloseable {
    private val gateway = AccidentLocationGatewayFactory.create(context)
    private val store = AccidentStore(context)
    private val client = AccidentClient(owner)
    private var incident: Incident? = null
    @Volatile private var foreground = false
    @Volatile private var consent = false
    private var enabled = false
    @Volatile private var online = true
    private var generation = 0L
    private var latest: AccidentLocation? = null
    private var testOverride = false
    private var refreshRequested = false
    private var locationJob: Job? = null
    private var uploadJob: Job? = null
    private var receiveJob: Job? = null
    private var received: NearbySnapshot? = null
    private var receivedElapsed = 0L
    private var confirmed = false
    private var detail = ""
    private var localFailure = false
    private val versions = mutableMapOf<String, Int>()

    fun context(incident: Incident?, consent: Boolean, enabled: Boolean, online: Boolean, foreground: Boolean) {
        val wasForeground = this.foreground
        if (this.enabled != enabled || this.online != online || wasForeground != foreground) { generation++; confirmed = false }
        if (this.incident?.id != incident?.id) localFailure = false
        this.incident = incident?.takeIf { it.eventType == EventType.ACCIDENT }
        if (this.consent != consent || this.online != online) { uploadJob?.cancel(); uploadJob = null }
        this.consent = consent; this.enabled = enabled; this.online = online; this.foreground = foreground
        if (!enabled) received = null
        capture()
        if (!foreground) {
            locationJob?.cancel(); locationJob = null; uploadJob?.cancel(); uploadJob = null; receiveJob?.cancel(); receiveJob = null
        } else if (!wasForeground) {
            locationJob = scope.launch { locationLoop() }
            receiveJob = scope.launch { receiveLoop() }
        }
        if (foreground && uploadJob == null) uploadJob = scope.launch { uploadLoop() }
        publish()
    }

    fun refresh() { refreshRequested = true; publish() }
    fun testLocation(value: AccidentLocation?) {
        testOverride = true; acceptLocation(value); capture(); publish()
    }
    fun liveLocation() { testOverride = false; acceptLocation(null); refreshRequested = true; publish() }
    fun wasSpoken(id: String): Boolean = id in store.spoken
    fun markSpoken(id: String) { store.markSpoken(id) }

    private fun acceptLocation(value: AccidentLocation?) {
        if (latest?.latitude != value?.latitude || latest?.longitude != value?.longitude) {
            generation++; confirmed = false
        }
        latest = value
    }

    private fun capture() {
        val session = incident ?: return
        if (store.readFailure || localFailure) return
        try {
            val prior = store.entries[session.id]
            if (prior != null) {
                if (consent && !prior.queued && !prior.registered) store.save(prior.copy(queued = true))
                return
            }
            val fix = latest?.takeIf { it.current(System.currentTimeMillis()) && it.receivedAt - session.observedAt in -15_000..30_000 } ?: return
            store.save(StoredAccident(AccidentEvent(session.id, owner, session.source, session.observedAt, fix), consent))
        } catch (error: Exception) { localFailure = true; detail = error.message ?: "사고 위치 저장 실패" }
    }

    private suspend fun locationLoop() {
        while (foreground) {
            if (!testOverride && (enabled || incident != null || refreshRequested)) {
                refreshRequested = false
                try {
                    val fix = gateway.read()
                    if (!testOverride && foreground) acceptLocation(fix?.takeIf { it.current(System.currentTimeMillis()) })
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { if (!testOverride && foreground) { acceptLocation(null); detail = error.message ?: "현재 위치 확인 실패" } }
            }
            capture(); publish(); delay(2_000)
        }
    }

    private suspend fun uploadLoop() {
        var retryMs = 1_000L
        while (foreground) {
            capture()
            val entry = store.entries.values.firstOrNull { it.queued && !it.registered && it.failure == null }
            if (!consent || !online || entry == null || store.readFailure) { publish(); delay(500); continue }
            try {
                val ack = client.register(entry.event) { foreground && consent && online }
                store.save(entry.copy(event = ack, registered = true))
                detail = "사고 위치 서버 등록 확인"; retryMs = 1000
                publish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                detail = error.message ?: "사고 위치 전송 실패"
                if (error is HazardHttpException && !error.retryable) {
                    try { store.save(entry.copy(failure = error.code)) } catch (_: Exception) { localFailure = true }
                }
                publish(); delay(retryMs); retryMs = (retryMs * 2).coerceAtMost(8000)
            }
            delay(250)
        }
    }

    private suspend fun receiveLoop() {
        while (foreground) {
            val fix = latest?.takeIf { it.current(System.currentTimeMillis()) }
            if (!enabled || !online || fix == null) { confirmed = false; publish(); delay(500); continue }
            val gen = generation
            try {
                val result = client.nearby("geo-${UUID.randomUUID()}", fix)
                val current = latest
                if (foreground && enabled && online && gen == generation && current?.current(System.currentTimeMillis()) == true &&
                    current.latitude == fix.latitude && current.longitude == fix.longitude) {
                    require(result.events.none { it.version < (versions[it.id] ?: 0) }) { "사고 상태 버전 역전" }
                    result.events.forEach { versions[it.id] = it.version }
                    received = result; receivedElapsed = SystemClock.elapsedRealtime(); confirmed = true
                    detail = "주변 사고 조회 확인"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (gen == generation) { confirmed = false; detail = error.message ?: "주변 사고 조회 실패" } }
            publish(); delay(1000)
        }
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        val current = latest?.takeIf { it.current(now) }
        val entry = incident?.id?.let(store.entries::get)
        val sharing = when {
            incident == null -> AccidentShareStatus.IDLE
            store.readFailure || localFailure -> AccidentShareStatus.FAILED
            entry?.registered == true && entry.event.expiresAt <= now -> AccidentShareStatus.EXPIRED
            entry?.registered == true -> AccidentShareStatus.REGISTERED
            !consent -> AccidentShareStatus.CONSENT_REQUIRED
            entry?.failure != null -> AccidentShareStatus.FAILED
            entry != null -> AccidentShareStatus.QUEUED
            now - incident!!.observedAt > 30_000 -> AccidentShareStatus.FAILED
            else -> AccidentShareStatus.LOCATING
        }
        val status = when {
            !enabled -> ReceiverStatus.OFF
            !foreground -> ReceiverStatus.PAUSED
            !online || current == null || !confirmed -> ReceiverStatus.UNAVAILABLE
            else -> ReceiverStatus.ACTIVE
        }
        val serverNow = received?.serverNow?.plus((SystemClock.elapsedRealtime() - receivedElapsed).coerceAtLeast(0)) ?: now
        val nearby = if (!enabled) emptyList() else received?.events.orEmpty()
            .filter { it.owner != owner && it.status == "ACTIVE" }
            .map { event -> NearbyAccident(event, current?.let { accidentDistance(it, event.location) }, when {
                status != ReceiverStatus.ACTIVE -> EventFreshness.UNCONFIRMED
                serverNow >= event.expiresAt -> EventFreshness.STALE
                else -> EventFreshness.FRESH
            }) }.filter { it.distanceMeters == null || it.distanceMeters <= 1000 }
        onState(AccidentNetworkUi(sharing, entry?.event, current, nearby, status, detail, gateway.needsPermission()))
    }

    override fun close() { locationJob?.cancel(); uploadJob?.cancel(); receiveJob?.cancel(); gateway.close() }
}
