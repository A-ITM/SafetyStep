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

    @Test fun twoClientsExchangeHazardOverActualHttp() = runBlocking {
        val incident = Incident(UUID.randomUUID().toString(), System.currentTimeMillis(), "MANUAL_TEST")
        val a = HazardClient("A")
        val b = HazardClient("B")
        assertTrue(a.post(incident.toSharedJson("A")).getBoolean("accepted"))
        assertTrue(a.post(incident.toSharedJson("A")).getBoolean("duplicate"))
        val received = b.list().single { it.id == incident.id }
        val now = System.currentTimeMillis()
        val position = RoadPosition("test-highway", "east", 600.0, 25.0, now)
        assertEquals(WarningDecision.WARN, WarningPolicy.evaluate(received, "B", position, now))
        assertEquals(WarningDecision.DIFFERENT_DIRECTION, WarningPolicy.evaluate(received, "B", position.copy(direction="west"), now))
        assertEquals(HazardState.ACTIVE, b.list().single { it.id == incident.id }.status)
        a.post(incident.resolve().toSharedJson("A"))
        assertEquals(HazardState.RESOLVED, b.list().single { it.id == incident.id }.status)
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
