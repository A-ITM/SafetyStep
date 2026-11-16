package io.github.aitm.pleosapp.prototype

import io.github.aitm.pleosapp.vehicle.VehicleReading
import io.github.aitm.pleosapp.vehicle.VehicleSnapshot
import org.junit.Assert.*
import org.junit.Test

class SupportPolicyTest {
    private val now = 50_000L
    private val incident = Incident("one", now, "TEST")
    private val facts = VehicleSnapshot(gear = VehicleReading("GEAR_PARK", now), trunkOpen = VehicleReading(false, now), speedMps = VehicleReading(0f, now))
    private val ready = SupportJourney(collision = CollisionContext.BREAKDOWN, stationaryConfirmed = true)

    @Test fun rearUnknownStaleAndUnconfirmedNeverPermitTrunk() {
        assertNull(SupportPolicy.trunkBlock(ready, facts, now, true))
        assertNotNull(SupportPolicy.trunkBlock(ready.copy(collision=CollisionContext.REAR), facts, now, true))
        assertNotNull(SupportPolicy.trunkBlock(ready.copy(collision=CollisionContext.UNKNOWN), facts, now, true))
        assertNotNull(SupportPolicy.trunkBlock(ready.copy(stationaryConfirmed=false), facts, now, true))
        assertNotNull(SupportPolicy.trunkBlock(ready, facts, now, false))
        assertNotNull(SupportPolicy.trunkBlock(ready, facts, now+30_001, true))
        assertNotNull(SupportPolicy.trunkBlock(ready, facts.copy(gear=VehicleReading("GEAR_DRIVE",now)), now, true))
        assertNotNull(SupportPolicy.trunkBlock(ready, VehicleSnapshot(), now, true))
        assertNotNull(SupportPolicy.trunkBlock(ready, facts.copy(speedMps=VehicleReading(3f,now)), now, true))
        assertNotNull(SupportPolicy.trunkBlock(ready, facts.copy(speedMps=VehicleReading()), now, true))
        assertNotNull(SupportPolicy.trunkBlock(ready, facts.copy(trunkOpen=VehicleReading()), now, true))
        assertNotNull(SupportPolicy.trunkBlock(ready.copy(departureDeclared=true), facts, now, true))
        assertNotNull(SupportPolicy.trunkBlock(ready.copy(callSimulation=true), facts, now, true))
    }

    @Test fun missingFalseAndStaleSignalsStayDistinct() {
        assertEquals(false, VehicleReading(false, now).current(now))
        assertNull(VehicleReading<Boolean>().current(now))
        assertNull(VehicleReading(true, now).current(now+30_001))
        assertNull(VehicleReading(true, now+1).current(now))
    }

    @Test fun responseIsNotMobilityAndDepartureIsNotEvacuation() {
        val answered = incident.respond(Responder.DRIVER, HelpState.UNKNOWN)
        assertEquals(SupportScreen.MOBILITY, SupportPolicy.screen(answered,ready))
        val canMove = answered.respond(Responder.DRIVER,HelpState.CAN_MOVE)
        assertEquals(SupportScreen.EVACUATION,SupportPolicy.screen(canMove,ready.copy(trunk=ControlState.UNCONFIRMED)))
        assertEquals(SupportScreen.SUPPORT,SupportPolicy.screen(canMove,ready.copy(departureDeclared=true)))
        assertFalse(canMove.evacuated)
        assertEquals(HazardState.ACTIVE,canMove.hazard)
    }

    @Test fun helpPreemptsDepartureAndVoiceFailureDoesNotMeanSilence() {
        val help = incident.respond(Responder.PASSENGER,HelpState.NEEDS_HELP)
        assertEquals(SupportScreen.HELP,SupportPolicy.screen(help,ready.copy(departureDeclared=true)))
        val failed=incident.beginResponse().voiceFailed()
        assertEquals(ResponseState.UNAVAILABLE,failed.timeout().response)
        assertEquals(SupportScreen.RESPONSE,SupportPolicy.screen(failed,ready))
    }
    @Test fun automaticTrunkIsDisabledEvenWithAllPreviousConditions() {
        val incident = Incident("front", now, "TEST").respond(Responder.DRIVER, HelpState.CAN_MOVE)
        val j = SupportJourney(collision=CollisionContext.FRONT, stationaryConfirmed=true, rearClearConfirmed=true)
        assertFalse(SupportPolicy.shouldAutoOpenTrunk(incident,j,facts,now))
        assertFalse(SupportPolicy.shouldAutoOpenTrunk(incident,j.copy(rearClearConfirmed=false),facts,now))
        assertFalse(SupportPolicy.shouldAutoOpenTrunk(incident,j.copy(collision=CollisionContext.REAR),facts,now))
        assertFalse(SupportPolicy.shouldAutoOpenTrunk(incident,j.copy(collision=CollisionContext.UNKNOWN),facts,now))
        assertFalse(SupportPolicy.shouldAutoOpenTrunk(incident,j.copy(autoTrunkClaimed=true),facts,now))
        assertFalse(SupportPolicy.shouldAutoOpenTrunk(incident,j.copy(departureDeclared=true),facts,now))
        assertFalse(SupportPolicy.shouldAutoOpenTrunk(incident.copy(help=HelpState.NEEDS_HELP),j,facts,now))
        assertFalse(SupportPolicy.shouldAutoOpenTrunk(incident,j,facts.copy(speedMps=VehicleReading(1f,now)),now))
        assertFalse(SupportPolicy.shouldAutoOpenTrunk(incident,j,facts,now+30_001))
    }

    @Test fun helpSurvivesRoadTerminationAndClearCabin() {
        val help = incident.respond(Responder.PASSENGER, HelpState.NEEDS_HELP).resolve()
        assertEquals(SupportScreen.HELP, SupportPolicy.screen(help, ready, cabinClear = true))
        assertEquals(HelpState.NEEDS_HELP, help.help)
    }
}
