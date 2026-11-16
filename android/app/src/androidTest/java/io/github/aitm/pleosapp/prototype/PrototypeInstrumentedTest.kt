package io.github.aitm.pleosapp.prototype

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aitm.pleosapp.BuildConfig
import io.github.aitm.pleosapp.vehicle.VehicleGatewayFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrototypeInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun persistentStateAndOutboxDoNotLeakOccupantData() {
        val name = "test-${UUID.randomUUID()}"
        try {
            val store = PrototypeStore(context, name)
            val incident = Incident("test", 1000, "MANUAL_TEST").beginResponse().timeout().timeout()
                .respond(Responder.PASSENGER, HelpState.NEEDS_HELP).reportEvacuated()
            store.saveIncident(incident)
            store.saveOutbox(listOf(incident.toSharedJson("A")))
            val restored = PrototypeStore(context, name)
            assertEquals(incident, restored.incident())
            assertEquals(HazardState.ACTIVE, restored.incident()!!.hazard)
            val event = restored.outbox().single()
            listOf("response", "responder", "help", "evacuated", "voice").forEach { assertFalse(event.has(it)) }
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun twoClientsExchangeScopedHazardAndRetractOnlyTargetOverActualHttp() = runBlocking {
        val now = System.currentTimeMillis()
        val event = Hazard(UUID.randomUUID().toString(), "A", 1, now - 5_000, now + 175_000,
            "test-highway", "east", 1_000.0, HazardState.ACTIVE, "STOP_OBSERVATION")
        val other = event.copy(id = UUID.randomUUID().toString(), meters = 1_050.0)
        val a = HazardClient("A"); val b = HazardClient("B")
        val create = HazardCommand(CommandKind.CREATE, event, event.toJson())
        assertEquals(event, a.send(create)); assertEquals(event, a.send(create))
        a.send(HazardCommand(CommandKind.CREATE, other, other.toJson()))
        fun query(road: String = "test-highway", direction: String = "east", meters: Double = 600.0) =
            PollContext("poll-${UUID.randomUUID()}", RoadPosition(road, direction, meters, null, System.currentTimeMillis(), RoadKind.MAINLINE), 1)
        assertTrue(b.list(query()).events.any { it.id == event.id })
        assertFalse(b.list(query(direction = "west")).events.any { it.id == event.id })
        assertFalse(b.list(query(road = "parallel-highway")).events.any { it.id == event.id })
        assertFalse(b.list(query(meters = 1_001.0)).events.any { it.id == event.id })
        val payload = JSONObject().put("expectedVersion", 1).put("evidence", JSONObject().put("source", "TEST")
            .put("startedAt", now - 4_000).put("observedAt", now - 1_000).put("speedMps", 2.5).put("durationMs", 3_000))
        val retract = HazardCommand(CommandKind.RETRACT, event, payload)
        assertEquals(HazardState.RETRACTED, a.send(retract).status)
        assertEquals(HazardState.RETRACTED, a.send(retract).status)
        val remaining = b.list(query()).events
        assertEquals(HazardState.RETRACTED, remaining.single { it.id == event.id }.status)
        assertEquals(HazardState.ACTIVE, remaining.single { it.id == other.id }.status)
    }

    private fun fixture(): JSONObject = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("prototype-v06.json").bufferedReader().use { it.readText() })
    private fun fixtureContext() = PollContext("poll-001", RoadPosition("test-highway", "east", 600.0, 25.0, 1_800_000_010_000, RoadKind.MAINLINE), 1)

    @Test fun sharedFixtureAndWholeEnvelopeValidationRejectMalformedItemsAtomically() {
        val fixture = fixture(); val context = fixtureContext()
        val valid = snapshotFromJson(fixture.getJSONObject("snapshot"), context)
        assertEquals(EventType.STOPPED_VEHICLE, valid.events.single().eventType)
        val state = ReceiverSnapshotState()
        state.apply(valid, context, 1, context.position.scopeKey, 100)
        val invalidEvent = JSONObject(fixture.getJSONObject("create").toString()).put("simulation", false)
        val broken = JSONObject(fixture.getJSONObject("snapshot").toString())
        broken.getJSONArray("events").put(invalidEvent)
        assertTrue(runCatching { snapshotFromJson(broken, context) }.isFailure)
        assertEquals(valid.events, state.events)
        assertTrue(runCatching { snapshotFromJson(fixture.getJSONObject("incompleteSnapshot"), context) }.isFailure)
        for (field in listOf("id", "owner", "source", "road", "direction", "eventType", "status")) {
            val numeric = JSONObject(fixture.getJSONObject("create").toString()).put(field, 123)
            assertTrue("numeric $field must not coerce", runCatching { hazardFromJson(numeric) }.isFailure)
        }
        for (field in listOf("requestId", "epoch", "scopeKey", "sourceHealth")) {
            val numeric = JSONObject(fixture.getJSONObject("snapshot").toString()).put(field, 123)
            assertTrue("numeric $field must not coerce", runCatching { snapshotFromJson(numeric, context) }.isFailure)
        }
    }

    @Test fun receiverSettingSpokenIdsAndTransportAcknowledgementRestoreTruthfully() {
        val name = "test-${UUID.randomUUID()}"
        try {
            val event = hazardFromJson(fixture().getJSONObject("create"))
            val command = HazardCommand(CommandKind.CREATE, event, event.toJson())
            var store = PrototypeStore(context, name)
            assertFalse(store.receiverEnabled)
            store.receiverEnabled = true; store.markSpoken(event.id)
            store.saveTransport(event, StopReportStatus.QUEUED, listOf(command))
            store = PrototypeStore(context, name)
            assertTrue(store.receiverEnabled); assertTrue(event.id in store.spokenIds())
            assertEquals(StopReportStatus.QUEUED, store.stopReportStatus)
            assertEquals(command.key, HazardCommand.fromJson(store.outbox().single()).key)
            // Consent OFF pauses the exact command; no new ID or shared-success claim is stored.
            store.consent = false; store.saveTransport(event, StopReportStatus.HELD, listOf(command))
            store = PrototypeStore(context, name)
            assertEquals(StopReportStatus.HELD, store.stopReportStatus)
            assertEquals(event.id, HazardCommand.fromJson(store.outbox().single()).event.id)
            store.consent = true; store.saveTransport(event, StopReportStatus.QUEUED, listOf(command))
            assertEquals(command.key, HazardCommand.fromJson(PrototypeStore(context, name).outbox().single()).key)
            store.saveTransport(event, StopReportStatus.SHARED, emptyList())
            assertEquals(StopReportStatus.SHARED, PrototypeStore(context, name).stopReportStatus)
            store.quarantine(command, "HTTP 400 INVALID_REQUEST")
            store.saveTransport(event, StopReportStatus.REJECTED, emptyList())
            assertEquals(StopReportStatus.REJECTED, PrototypeStore(context, name).stopReportStatus)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun probeInstalledVehicleServiceWithoutSilentFallback() = runBlocking {
        val gateway = VehicleGatewayFactory.create(context)
        val report = JSONObject().put("flavor", BuildConfig.FLAVOR).put("source", gateway.sourceLabel)
        try {
            for ((name, read) in listOf<Pair<String, suspend () -> String>>(
                "driverDoor" to gateway::readDriverDoor, "gear" to gateway::readGear, "trunk" to gateway::readTrunk,
            )) {
                try { report.put(name, read()) }
                catch (error: Exception) { report.put(name, "UNAVAILABLE: ${error.message}") }
            }
            try {
                gateway.watchAirbag(
                    { value -> report.put("airbag", value ?: JSONObject.NULL) },
                    { error -> report.put("airbagError", error) },
                )
            } catch (error: Exception) { report.put("airbagError", error.message) }
            val facts = gateway.readSnapshot()
            report.put("snapshot", JSONObject().apply {
                put("sample", facts.sample)
                put("gear", facts.gear.value ?: JSONObject.NULL)
                listOf("driverDoorOpen" to facts.driverDoorOpen, "trunkOpen" to facts.trunkOpen,
                    "driverOccupied" to facts.driverOccupied, "driverBelt" to facts.driverBelt,
                    "brakePressed" to facts.brakePressed).forEach { (name, signal) ->
                    put(name, JSONObject().put("value", signal.value ?: JSONObject.NULL)
                        .put("detail", signal.detail).put("observedAt",signal.observedAt))
                }
            })
            File(context.filesDir, "sdk-probe.json").writeText(report.toString(2))
            if (BuildConfig.FLAVOR == "pleos") assertFalse(report.toString().contains("샘플"))
            assertTrue(report.has("driverDoor"))
        } finally { gateway.close() }
    }
}
