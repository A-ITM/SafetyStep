package io.github.aitm.pleosapp.prototype

import android.graphics.Bitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aitm.pleosapp.BuildConfig
import io.github.aitm.pleosapp.MainActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetyStepUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun model() = ViewModelProvider(compose.activity)[PrototypeViewModel::class.java]
    private fun change(block: (PrototypeViewModel)->Unit) = compose.runOnIdle { block(model()) }
    private fun theme(mode: String) {
        compose.onNodeWithTag("tools").performClick()
        compose.onNodeWithTag("theme-$mode").performClick()
    }

    @Before fun reset() {
        change { it.newLocalDemo(); it.setTheme(ThemePreference.DARK); it.setConsent(false); it.setOnline(true); it.receiverView(false) }
        compose.waitUntil(15_000) { !model().state.value.drivingRestricted }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.filesDir,"safetystep-checks").apply{mkdirs()}
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }

    @Test fun lightDarkAndRecreationKeepTheSameIncident() {
        capture("01-drive-dark")
        theme("light")
        capture("02-drive-light")
        compose.onNodeWithTag("start-support").performClick()
        change { it.setCollision(CollisionContext.BREAKDOWN) }
        capture("02a-intake-light")
        change { it.startSupportResponse(false) }
        capture("02b-response-light")
        theme("dark")
        capture("02c-response-dark")
        compose.onNodeWithTag("respond-driver").performClick()
        compose.onNodeWithTag("screen-MOBILITY").assertExists()
        val id=compose.runOnIdle { model().state.value.incident!!.id }
        theme("dark")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("screen-MOBILITY").assertExists()
        compose.runOnIdle {
            assertEquals(id,model().state.value.incident!!.id)
            assertEquals(HazardState.ACTIVE,model().state.value.incident!!.hazard)
            assertEquals(0,model().state.value.journey.trunkAttempts)
        }
        capture("03-mobility-dark")
    }

    @Test fun rearCollisionHoldsTrunkWithoutChecklistActions() {
        change { it.begin();it.setCollision(CollisionContext.REAR);it.respond(Responder.DRIVER,HelpState.CAN_MOVE);it.confirmStationary(true);it.confirmRoute(true) }
        compose.onNodeWithTag("trunk-request").assertDoesNotExist()
        compose.onNodeWithText("후방 충돌 · 열지 않음").assertExists()
        compose.runOnIdle { assertEquals(ControlState.HELD,model().state.value.journey.trunk);assertEquals(0,model().state.value.journey.trunkAttempts) }
        capture("04-rear-impact-held-dark")
        theme("light")
        capture("05-evacuation-light")
        compose.onNodeWithTag("finish-guidance").assertDoesNotExist()
        compose.onNodeWithTag("stationary").assertDoesNotExist()
        compose.onNodeWithTag("route").assertDoesNotExist()
        compose.onNodeWithTag("incident-dashboard").assertExists()
        compose.onNodeWithTag("bitbox-hazard").assertExists()
        compose.onNodeWithTag("bitbox-exit").assertExists()
        compose.runOnIdle { assertFalse(model().state.value.incident!!.evacuated);assertEquals(HazardState.ACTIVE,model().state.value.incident!!.hazard) }

    }

    @Test fun voiceErrorUnansweredAndContactRemainSeparate() {
        change {it.begin();it.voiceFailure()}
        compose.runOnIdle {assertEquals(ResponseState.UNAVAILABLE,model().state.value.incident!!.response);assertFalse(model().state.value.timerRunning)}
        capture("07-voice-error")
        change {it.newLocalDemo();it.begin();it.startResponseTimer();it.responseTimeout();it.responseTimeout()}
        compose.runOnIdle {assertEquals(ResponseState.UNCONFIRMED,model().state.value.incident!!.response);assertFalse(model().state.value.timerRunning)}
        capture("08-unconfirmed")
        compose.onNodeWithTag("urgent-help").performScrollTo().performClick()
        compose.onNodeWithTag("screen-HELP").assertExists()
        compose.onNodeWithTag("contact-phone").assertExists()
        compose.runOnIdle {assertFalse(model().state.value.timerRunning)}
        capture("09-contact-simulation")
    }

    @Test fun receiverIsSeparateAndOfflineDoesNotClaimCurrentInformation() {
        change {it.previewApproachingHazard()}
        compose.onNodeWithTag("screen-RECEIVER").assertExists()
        compose.onNodeWithText("400").assertIsDisplayed()
        capture("10-receiver-dark")
        change {it.setOnline(false)}
        compose.onNodeWithText("연결을 확인하고 있어요").assertExists()
        capture("11-receiver-offline")
        change {it.setOnline(true);it.scenario("opposite")}
        compose.onNodeWithText("현재 표시할\n위험 알림이 없어요").assertExists()
    }

    @Test fun explicitDemoCommandIsNotRepeatedByThemeOrRecreation() {
        assumeTrue(BuildConfig.FLAVOR=="demo")
        change {it.begin();it.setCollision(CollisionContext.BREAKDOWN);it.respond(Responder.DRIVER,HelpState.CAN_MOVE);it.confirmStationary(true);it.confirmRoute(true);it.refreshVehicle()}
        compose.waitUntil(15_000){model().state.value.vehicle.gear.current(System.currentTimeMillis())=="P"}
        compose.onNodeWithTag("trunk-request").performScrollTo().performClick()
        compose.onNodeWithTag("trunk-confirm").performClick()
        compose.waitUntil(15_000){model().state.value.journey.trunk==ControlState.CONFIRMED}
        val attempts=compose.runOnIdle {model().state.value.journey.trunkAttempts}
        theme("light")
        compose.activityRule.scenario.recreate()
        compose.runOnIdle {assertEquals(attempts,model().state.value.journey.trunkAttempts);assertTrue(attempts<=1)}
        capture("12-trunk-confirmed-demo")
    }
    @Test fun hazardClaimSurvivesReopeningStoreAndDoesNotReplay() {
        val context = compose.activity
        val name = "hazard-claim-test"
        context.getSharedPreferences(name, 0).edit().clear().commit()
        val first = PrototypeStore(context, name)
        assertTrue(first.claimHazard("incident-a"))
        first.recordHazardCommand("incident-a")
        val reopened = PrototypeStore(context, name)
        assertFalse(reopened.claimHazard("incident-a"))
        assertEquals(1, reopened.hazardCommands("incident-a"))
        assertTrue(reopened.claimHazard("incident-b"))
        assertEquals(0, reopened.hazardCommands("incident-b"))
        reopened.saveJourney("front", SupportJourney(collision=CollisionContext.FRONT, autoTrunkClaimed=true, rearClearConfirmed=true, trunkAttempts=1))
        val restored = PrototypeStore(context, name).journey("front")
        assertTrue(restored.autoTrunkClaimed)
        assertEquals(1, restored.trunkAttempts)
        assertFalse(restored.rearClearConfirmed)
        context.getSharedPreferences(name, 0).edit().clear().commit()
    }

    @Test fun incidentDashboardPersistsAcrossQuestionsAndMobility() {
        change { it.begin(); it.setCollision(CollisionContext.REAR); it.startSupportResponse(false) }
        compose.onNodeWithTag("incident-dashboard").assertExists()
        compose.onNodeWithTag("bitbox-status").assertExists()
        change { it.respond(Responder.DRIVER, HelpState.UNKNOWN) }
        compose.onNodeWithTag("incident-dashboard").assertExists()
        compose.onNodeWithTag("bitbox-status").assertExists()
        change { it.respond(Responder.DRIVER, HelpState.CAN_MOVE) }
        compose.onNodeWithTag("bitbox-exit").assertExists()
        compose.onNodeWithTag("finish-guidance").assertDoesNotExist()
        compose.onNodeWithTag("urgent-help").performScrollTo().assertHeightIsAtLeast(80.dp)
        compose.runOnIdle { assertEquals(HazardState.ACTIVE, model().state.value.incident!!.hazard); assertFalse(model().state.value.incident!!.evacuated) }
    }

}
