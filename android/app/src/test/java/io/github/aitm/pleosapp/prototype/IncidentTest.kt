package io.github.aitm.pleosapp.prototype

import org.junit.Assert.*
import org.junit.Test

class IncidentTest {
    private val incident = Incident("one", 1_000, "TEST")
    private val hazard = Hazard("one", "A", 1, 1_000, 60_000, "highway", "east", 1_000.0, HazardState.ACTIVE, "TEST")
    private val position = RoadPosition("highway", "east", 600.0, 25.0, 2_000)

    @Test fun responseRetriesOnceWithoutDiagnosingUnconsciousness() {
        val second = incident.beginResponse().timeout()
        assertEquals(ResponseState.RETRYING, second.response)
        val unanswered = second.timeout()
        assertEquals(ResponseState.UNCONFIRMED, unanswered.response)
        assertEquals(unanswered, unanswered.timeout())
        assertEquals(Responder.UNKNOWN, unanswered.responder)
        assertEquals(ResponseState.RESPONDED, unanswered.respond(Responder.PASSENGER, HelpState.NEEDS_HELP).response)
    }

    @Test fun failureAndPassengerResponseAreSeparateFromDriverHealth() {
        assertEquals(ResponseState.UNAVAILABLE, incident.beginResponse().voiceFailed().response)
        val passenger = incident.respond(Responder.PASSENGER, HelpState.CAN_MOVE)
        assertEquals(Responder.PASSENGER, passenger.responder)
        assertEquals(passenger, passenger.timeout())
    }

    @Test fun evacuationDoesNotResolveHazardOrPublishOccupantChanges() {
        val evacuated = incident.reportEvacuated()
        assertEquals(HazardState.ACTIVE, evacuated.hazard)
        assertEquals(1, evacuated.version)
        assertEquals(2, evacuated.resolve().version)
        assertEquals(evacuated.resolve(), evacuated.resolve().resolve())
    }

    @Test fun deploymentRequiresNewEdgeAndDeduplicatesUpdates() {
        val edge = DeploymentEdge()
        assertFalse(edge.update(true))
        assertFalse(edge.update(false))
        assertFalse(edge.update(null))
        assertTrue(edge.update(true))
        assertFalse(edge.update(true))
        assertFalse(DeploymentEdge(true).update(true))
    }

    @Test fun onlyRelevantFreshApproachingVehicleIsWarned() {
        assertEquals(WarningDecision.WARN, WarningPolicy.evaluate(hazard, "B", position, 3_000))
        assertEquals(WarningDecision.OWN_EVENT, WarningPolicy.evaluate(hazard, "A", position, 3_000))
        assertEquals(WarningDecision.DIFFERENT_ROAD, WarningPolicy.evaluate(hazard, "B", position.copy(road="parallel"), 3_000))
        assertEquals(WarningDecision.DIFFERENT_DIRECTION, WarningPolicy.evaluate(hazard, "B", position.copy(direction="west"), 3_000))
        assertEquals(WarningDecision.PASSED, WarningPolicy.evaluate(hazard, "B", position.copy(meters=1_001.0), 3_000))
        assertEquals(WarningDecision.TOO_FAR, WarningPolicy.evaluate(hazard, "B", position.copy(meters=0.0), 3_000))
        assertEquals(WarningDecision.INVALID_POSITION, WarningPolicy.evaluate(hazard, "B", position.copy(observedAt=0), 16_000))
        assertEquals(WarningDecision.STALE, WarningPolicy.evaluate(hazard, "B", position, 60_000))
        assertEquals(WarningDecision.RESOLVED, WarningPolicy.evaluate(hazard.copy(status=HazardState.RESOLVED), "B", position, 3_000))
    }
}
