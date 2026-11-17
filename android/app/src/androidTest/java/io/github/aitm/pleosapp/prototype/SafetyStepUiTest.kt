package io.github.aitm.pleosapp.prototype

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aitm.pleosapp.MainActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetyStepUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun model() = ViewModelProvider(compose.activity)[PrototypeViewModel::class.java]
    private fun change(block: (PrototypeViewModel) -> Unit) = compose.runOnIdle { block(model()) }
    private fun theme(mode: String) {
        compose.onNodeWithTag("tools").performClick()
        compose.onNodeWithTag("theme-$mode").performClick()
    }
    private fun stationaryPosition() = Intent("${compose.activity.packageName}.OPERATOR").putExtra("command", "position")
        .putExtra("road", "test-highway").putExtra("direction", "east").putExtra("roadKind", "SHOULDER")
        .putExtra("meters", "1000").putExtra("speedMps", "0")
        .putExtra("observedAt", System.currentTimeMillis().toString()).putExtra("reset", true)

    @Before fun reset() {
        change {
            it.newLocalDemo(); it.setTheme(ThemePreference.DARK); it.setConsent(false); it.setOnline(false)
            it.setReceiverEnabled(false); it.receiverView(false); it.operatorAction(stationaryPosition())
        }
        compose.waitUntil(15_000) { !model().state.value.drivingRestricted }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "safetystep-checks").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun lightDarkAndRecreationKeepActionFirstDashboardAndSameIncident() {
        capture("01-drive-dark")
        theme("light")
        capture("02-drive-light")
        compose.onNodeWithTag("start-support").assertTextEquals("사고 도움받기").assertIsDisplayed().performClick()
        change { it.pauseVoice() }
        compose.runOnIdle { assertEquals(EventType.ACCIDENT, model().state.value.incident!!.eventType) }
        compose.onNodeWithText("사고 후 안전 지원").assertIsDisplayed()
        compose.onNodeWithTag("accident-share").assertExists()
        compose.onNodeWithTag("stop-report-status").assertDoesNotExist()
        compose.onNodeWithTag("incident-dashboard").assertExists()
        compose.onNodeWithText("안전한 곳으로 이동하세요").assertExists()
        compose.onNodeWithText("어떤 상황인가요?").assertDoesNotExist()
        compose.onNodeWithTag("report-location").assertExists()
        capture("03-action-first-light")
        change { it.respond(Responder.UNKNOWN, HelpState.UNKNOWN) }
        compose.onNodeWithTag("incident-dashboard").assertExists()
        compose.runOnIdle { assertEquals(HelpState.UNKNOWN, model().state.value.incident!!.help) }
        val id = compose.runOnIdle { model().state.value.incident!!.id }
        theme("dark")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("incident-dashboard").assertExists()
        compose.runOnIdle {
            assertEquals(id, model().state.value.incident!!.id)
            assertEquals(HazardState.ACTIVE, model().state.value.incident!!.hazard)
            assertFalse(model().state.value.incident!!.evacuated)
            assertEquals(0, model().state.value.journey.trunkAttempts)
            assertEquals(0, model().state.value.hazardCommands)
        }
        capture("04-dashboard-dark")
    }

    @Test fun normalSupportNeverCommandsHazardOrTrunkAcrossResponsesAndRecreation() {
        change {
            it.begin(eventType = EventType.ACCIDENT, useVoice = false); it.pauseVoice(); it.setCollision(CollisionContext.FRONT)
            it.respond(Responder.DRIVER, HelpState.CAN_MOVE); it.confirmStationary(true); it.confirmRoute(true)
            it.refreshVehicle()
        }
        compose.onNodeWithTag("trunk-request").assertDoesNotExist()
        compose.onNodeWithTag("trunk-confirm").assertDoesNotExist()
        compose.onNodeWithTag("bitbox-hazard").assertExists()
        compose.onNodeWithTag("bitbox-exit").assertExists()
        theme("light")
        compose.activityRule.scenario.recreate()
        compose.runOnIdle {
            assertEquals(0, model().state.value.journey.trunkAttempts)
            assertEquals(0, model().state.value.hazardCommands)
            assertFalse(model().state.value.journey.autoTrunkClaimed)
            assertFalse(model().state.value.incident!!.evacuated)
        }
        capture("05-no-normal-control-light")
    }

    @Test fun voiceFailureAndSilenceRemainDistinctAndLateHelpStaysAvailable() {
        change { it.begin(); it.voiceFailure() }
        compose.runOnIdle {
            assertEquals(ResponseState.UNAVAILABLE, model().state.value.incident!!.response)
            assertFalse(model().state.value.timerRunning)
        }
        capture("06-voice-unavailable")
        change {
            it.newLocalDemo(); it.operatorAction(stationaryPosition()); it.begin(); it.pauseVoice()
            it.startResponseTimer(); it.pauseVoice(); it.responseTimeout(); it.responseTimeout()
        }
        compose.runOnIdle {
            assertEquals(ResponseState.UNCONFIRMED, model().state.value.incident!!.response)
            assertFalse(model().state.value.timerRunning)
        }
        compose.onNodeWithTag("urgent-help").assertIsDisplayed().assertHeightIsAtLeast(80.dp).performClick()
        compose.onNodeWithTag("screen-HELP").assertExists()
        compose.onNodeWithTag("contact-phone").assertExists()
        compose.runOnIdle { assertEquals(HelpState.NEEDS_HELP, model().state.value.incident!!.help) }
        capture("07-help-priority")
    }

    @Test fun receiverSettingIsSeparateFromSharingAndSurvivesRecreation() {
        compose.onNodeWithTag("receiver-enabled").assertIsOff().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(model().state.value.receiverEnabled)
            assertFalse(model().state.value.consent)
            assertNotEquals(ReceiverStatus.ACTIVE, model().state.value.receiverStatus)
        }
        theme("light")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("receiver-enabled").assertIsOn()
        compose.runOnIdle {
            assertTrue(PrototypeStore(compose.activity).receiverEnabled)
            assertFalse(model().state.value.consent)
        }
        capture("08-receiver-unavailable-light")
        change { it.setReceiverEnabled(false) }
        compose.runOnIdle { assertEquals(ReceiverStatus.OFF, model().state.value.receiverStatus) }
    }

    @Test fun incomingSupportClosesSettingsWithoutAnotherTap() {
        compose.onNodeWithTag("tools").performClick()
        compose.onNodeWithTag("theme-light").assertExists()
        change { it.begin(eventType = EventType.ACCIDENT, useVoice = false) }
        compose.onNodeWithTag("theme-light").assertDoesNotExist()
        compose.onNodeWithTag("incident-dashboard").assertIsDisplayed()
        compose.onNodeWithTag("urgent-help").assertIsDisplayed()
    }

    @Test fun observedAccidentReplacesStoppedSessionAndSurvivesClassificationChanges() {
        change {
            it.begin(eventType = EventType.STOPPED_VEHICLE, useVoice = false)
            it.respond(Responder.PASSENGER, HelpState.NEEDS_HELP)
        }
        val stopped = compose.runOnIdle { model().state.value.incident!! }
        compose.waitUntil(1_000) { System.currentTimeMillis() > stopped.observedAt }
        change {
            it.operatorAction(Intent("${compose.activity.packageName}.OPERATOR")
                .putExtra("command", "geo_position")
                .putExtra("latitude", "37.4127").putExtra("longitude", "127.0946")
                .putExtra("observedAt", System.currentTimeMillis().toString()))
            it.begin(source = "SDK_AIRBAG", eventType = EventType.ACCIDENT, useVoice = false)
        }
        val accident = compose.runOnIdle { model().state.value.incident!! }
        assertNotEquals(stopped.id, accident.id)
        assertTrue(accident.observedAt > stopped.observedAt)
        assertEquals(EventType.ACCIDENT, accident.eventType)
        assertEquals("SDK_AIRBAG", accident.source)
        assertEquals(HelpState.NEEDS_HELP, accident.help)
        assertEquals(Responder.PASSENGER, accident.responder)
        compose.onNodeWithText("무리하게 움직이지 마세요").assertIsDisplayed()
        listOf(CollisionContext.UNKNOWN, CollisionContext.BREAKDOWN).forEach { classification ->
            change { it.setCollision(classification); it.refreshReportLocation() }
            compose.runOnIdle {
                val state = model().state.value
                assertEquals(accident.id, state.incident!!.id)
                assertEquals(accident.observedAt, state.incident!!.observedAt)
                assertEquals(EventType.ACCIDENT, state.incident!!.eventType)
                assertEquals(37.4127, state.reportLocation.latitude!!, .000001)
                assertEquals(127.0946, state.reportLocation.longitude!!, .000001)
            }
        }
        compose.onNodeWithText("사고 후 안전 지원").assertIsDisplayed()
        compose.onNodeWithTag("stop-report-status").assertDoesNotExist()
    }

    @Test fun warningCompletionPersistsWithoutRestoringOldControlAuthority() {
        val context = compose.activity
        val name = "warning-completion-test"
        context.getSharedPreferences(name, 0).edit().clear().commit()
        try {
            val first = PrototypeStore(context, name)
            first.markSpoken("incident-a")
            first.markSpoken("incident-a")
            first.saveJourney("front", SupportJourney(
                collision = CollisionContext.FRONT, autoTrunkClaimed = true, rearClearConfirmed = true, trunkAttempts = 1,
            ))
            val reopened = PrototypeStore(context, name)
            assertEquals(setOf("incident-a"), reopened.spokenIds())
            reopened.markSpoken("incident-b")
            assertEquals(setOf("incident-a", "incident-b"), PrototypeStore(context, name).spokenIds())
            val restored = reopened.journey("front")
            assertFalse(restored.autoTrunkClaimed)
            assertEquals(0, restored.trunkAttempts)
            assertFalse(restored.rearClearConfirmed)
        } finally { context.deleteSharedPreferences(name) }
    }
}
