package io.github.aitm.pleosapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aitm.pleosapp.location.ReportLocation
import io.github.aitm.pleosapp.location.ReportLocationStatus
import io.github.aitm.pleosapp.prototype.*
import io.github.aitm.pleosapp.speech.VoicePhase
import io.github.aitm.pleosapp.vehicle.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetyStepPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val incident = Incident("support-ui", 50_000L, "MANUAL_TEST")
    private fun dashboard(state: PrototypeUi, onHelp: () -> Unit = {}) {
        compose.setContent {
            SafetyStepTheme(ThemePreference.DARK) {
                Box(Modifier.size(544.dp, 732.dp).testTag("panel")) {
                    IncidentDashboardContent(state.copy(drivingRestricted = false), onHelp, {}, {})
                }
            }
        }
    }

    @Test fun actionAndReportingDoNotRequireSituationRoleOrMobilityAnswers() {
        var helps = 0
        dashboard(PrototypeUi(incident = incident, supportQualified = true)) { helps++ }
        compose.onNodeWithText("안전한 곳으로 이동하세요").assertExists()
        compose.onNodeWithText(CabinGuidance.EXIT).assertExists()
        compose.onNodeWithTag("incident-dashboard").assertExists()
        compose.onNodeWithTag("report-location").assertExists()
        compose.onNodeWithTag("bitbox-hazard").assertExists()
        compose.onNodeWithTag("bitbox-exit").assertExists()
        compose.onNodeWithTag("trunk-request").assertDoesNotExist()
        compose.onNodeWithText("어떤 상황인가요?").assertDoesNotExist()
        compose.onNodeWithText("움직일 수 있나요?").assertDoesNotExist()
        visibleInsidePanel("urgent-help")
        compose.onNodeWithTag("urgent-help").assertHeightIsAtLeast(80.dp).performClick()
        assertEquals(1, helps)
    }

    @Test fun quietCabinKeepsHelpRecordWithoutPromptsOrControls() {
        dashboard(PrototypeUi(
            incident = incident.resolve().respond(Responder.PASSENGER, HelpState.NEEDS_HELP),
            supportQualified = true, cabinClearConfirmed = true,
        ))
        compose.onNodeWithText("좌석 비움 관측").assertIsDisplayed()
        compose.onNodeWithText("도움 요청 기록 있음").assertIsDisplayed()
        assertQuietCabin()
    }

    @Test fun clearedSeatsKeepPassiveHistoricalStateOnly() {
        dashboard(PrototypeUi(incident = incident, supportQualified = false, cabinClearConfirmed = true))
        compose.onNodeWithText("좌석 비움 관측").assertIsDisplayed()
        compose.onNodeWithText("현재 좌석 상태 미확인").assertIsDisplayed()
        compose.onNodeWithText("도움 요청 기록 있음").assertDoesNotExist()
        compose.onNodeWithText("안전하게 정차해주세요").assertDoesNotExist()
        compose.onNodeWithText("대피 완료").assertDoesNotExist()
        assertQuietCabin()
    }

    private fun assertQuietCabin() {
        compose.onNodeWithTag("cabin-quiet").assertIsDisplayed()
        listOf("contact-phone", "urgent-help", "support-voice", "report-location", "bitbox-hazard", "support-actions")
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)).assertCountEquals(0)
    }

    private fun visibleInsidePanel(tag: String) {
        val panel = compose.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("$tag must remain inside the pane", node.left >= panel.left && node.right <= panel.right &&
            node.top >= panel.top && node.bottom <= panel.bottom)
    }

    @Test fun drivingUsesOneEventAndHasNoTouchOrScrollTasks() {
        compose.setContent {
            SafetyStepTheme(ThemePreference.LIGHT) {
                Box(Modifier.size(544.dp, 732.dp).testTag("panel")) {
                    DrivingContent(PrototypeUi(drivingRestricted = true, receiverView = true, warningCards = listOf(
                        WarningCard("old", EventType.ACCIDENT, 120.0, EventFreshness.STALE, "TEST"),
                        WarningCard("fresh", EventType.STOPPED_VEHICLE, 350.0, EventFreshness.FRESH, "TEST"),
                    )))
                }
            }
        }
        visibleInsidePanel("warning-fresh")
        compose.onNodeWithTag("warning-old").assertDoesNotExist()
        compose.onNodeWithText("전방 사고 알림").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)).assertCountEquals(0)
    }

    @Test fun remainingPassengerAndUnknownSeatDoNotBecomeAnAllClearMessage() {
        val now = 50_000L
        dashboard(PrototypeUi(incident = incident, supportQualified = true, now = now,
            occupiedSeatAreas = setOf(1, 4), vehicle = VehicleSnapshot(seats = listOf(
                SeatObservation(1, "운전석", VehicleReading(false, now)),
                SeatObservation(4, "동승석", VehicleReading(true, now)),
                SeatObservation(16, "뒷좌석 왼쪽", VehicleReading(null, now)),
            ))))
        visibleInsidePanel("cabin-map")
        visibleInsidePanel("urgent-help")
        compose.onNodeWithText(CabinGuidance.PASSENGER).assertExists()
        compose.onNodeWithText("안전한 곳에 머무르세요").assertDoesNotExist()
        compose.onNodeWithText("대피 완료").assertDoesNotExist()
    }

    @Test fun drivingUnconfirmedEventKeepsItsUnconfirmedStateVisible() {
        compose.setContent {
            SafetyStepTheme(ThemePreference.DARK) {
                Box(Modifier.size(544.dp, 732.dp).testTag("panel")) {
                    DrivingContent(PrototypeUi(drivingRestricted = true, receiverView = true, warningCards = listOf(
                        WarningCard("unknown", EventType.ACCIDENT, null, EventFreshness.UNCONFIRMED, "TEST"),
                    )))
                }
            }
        }
        visibleInsidePanel("warning-unknown")
        compose.onNodeWithText("거리 확인 필요").assertIsDisplayed()
        compose.onNodeWithText("현재 상태 확인 필요", substring = true).assertIsDisplayed()
    }

    @Test fun storedHelpKeepsContactVisibleWithLongAddress() {
        dashboard(PrototypeUi(
            incident = incident.respond(Responder.UNKNOWN, HelpState.NEEDS_HELP), supportQualified = true,
            reportLocation = ReportLocation(status = ReportLocationStatus.AVAILABLE,
                address = "경기도 성남시 수정구 시흥동 도로 주변의 매우 긴 주소 ".repeat(5)),
        ))
        visibleInsidePanel("report-location")
        visibleInsidePanel("contact-phone")
        compose.onNodeWithTag("urgent-help").assertDoesNotExist()
    }

    @Test fun newHelpDismissesLocationDetailsAndShowsContact() {
        val state = mutableStateOf(PrototypeUi(incident = incident, supportQualified = true, drivingRestricted = false,
            reportLocation = ReportLocation(status = ReportLocationStatus.AVAILABLE,
                address = "샘플 도로", receivedAt = 45_000L, sample = true), now = 50_000L))
        compose.setContent {
            SafetyStepTheme(ThemePreference.DARK) {
                Box(Modifier.size(745.dp, 1042.dp).testTag("panel")) {
                    IncidentDashboardContent(state.value, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("report-location-details").performScrollTo().performClick()
        compose.onNodeWithTag("report-location-refresh").assertExists()
        compose.runOnIdle { state.value = state.value.copy(incident = incident.respond(Responder.UNKNOWN, HelpState.NEEDS_HELP)) }
        compose.onNodeWithTag("report-location-refresh").assertDoesNotExist()
        visibleInsidePanel("contact-phone")
    }

    @Test fun quietCabinClosesLocationAndVoiceDialogsAndReentryDoesNotRestoreThem() {
        val state = mutableStateOf(PrototypeUi(incident = incident, supportQualified = true,
            drivingRestricted = false, now = 50_000L))
        compose.setContent {
            SafetyStepTheme(ThemePreference.DARK) {
                Box(Modifier.size(745.dp, 1042.dp)) { IncidentDashboardContent(state.value, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("report-location-details").performScrollTo().performClick()
        compose.onNodeWithTag("report-location-refresh").assertExists()
        compose.runOnIdle { state.value = state.value.copy(cabinClearConfirmed = true) }
        compose.onNodeWithTag("report-location-refresh").assertDoesNotExist()
        assertQuietCabin()
        compose.runOnIdle { state.value = state.value.copy(cabinClearConfirmed = false) }
        compose.onNodeWithTag("report-location-refresh").assertDoesNotExist()
        compose.onNodeWithTag("voice-details").performScrollTo().performClick()
        compose.onNodeWithTag("voice-action").assertExists()
        compose.runOnIdle { state.value = state.value.copy(cabinClearConfirmed = true) }
        compose.onNodeWithTag("voice-action").assertDoesNotExist()
        assertQuietCabin()
        compose.runOnIdle { state.value = state.value.copy(cabinClearConfirmed = false) }
        compose.onNodeWithTag("voice-action").assertDoesNotExist()
        compose.onNodeWithTag("urgent-help").assertIsDisplayed()
    }

    @Test fun passengerGuidanceAppearsFirstAfterDriverDepartureAndYieldsToHelpOrMotion() {
        val state = mutableStateOf(PrototypeUi(incident = incident, supportQualified = true, drivingRestricted = false,
            voicePhase = VoicePhase.UNAVAILABLE, now = 50_000L, vehicle = VehicleSnapshot(seats = listOf(
            SeatObservation(1, "운전석", VehicleReading(false, 50_000L)),
            SeatObservation(4, "동승석", VehicleReading(true, 50_000L)),
        ))))
        compose.setContent {
            SafetyStepTheme(ThemePreference.LIGHT) {
                Box(Modifier.size(544.dp, 732.dp).testTag("panel")) { IncidentDashboardContent(state.value, {}, {}, {}) }
            }
        }
        compose.onNodeWithText("안전한 곳으로 이동하세요").assertIsDisplayed()
        compose.onNodeWithText(CabinGuidance.PASSENGER).assertDoesNotExist()
        compose.onNodeWithTag("report-location-details").performScrollTo()
        compose.runOnIdle { state.value = state.value.copy(occupiedSeatAreas = setOf(1, 4)) }
        compose.onNodeWithText("동승 좌석 감지됨").assertIsDisplayed()
        compose.onNodeWithText(CabinGuidance.PASSENGER).assertIsDisplayed()
        compose.onAllNodesWithText(CabinGuidance.PASSENGER).assertCountEquals(1)
        visibleInsidePanel("support-heading")
        visibleInsidePanel("support-guidance")
        visibleInsidePanel("passenger-help-cue")
        visibleInsidePanel("urgent-help")
        compose.onNodeWithText("도움이 필요하면 아래 ‘움직이기 어려워요’를 눌러주세요.").assertIsDisplayed()
        compose.onNodeWithText("도움이 필요하면 말씀해 주세요.").assertDoesNotExist()
        listOf(VoicePhase.PREPARING, VoicePhase.SPEAKING, VoicePhase.LISTENING).forEach { phase ->
            compose.runOnIdle { state.value = state.value.copy(voicePhase = phase) }
            compose.onNodeWithTag("passenger-help-cue").assertDoesNotExist()
            visibleInsidePanel("urgent-help")
        }
        compose.onAllNodesWithText("도움이 필요하면 말씀해주세요").assertCountEquals(1)
        compose.runOnIdle { state.value = state.value.copy(voicePhase = VoicePhase.PAUSED) }
        compose.onNodeWithTag("passenger-help-cue").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(incident = incident.respond(Responder.PASSENGER, HelpState.NEEDS_HELP)) }
        compose.onNodeWithText("무리하게 움직이지 마세요").assertIsDisplayed()
        compose.onNodeWithText(CabinGuidance.PASSENGER).assertDoesNotExist()
        visibleInsidePanel("contact-phone")
        compose.runOnIdle { state.value = state.value.copy(incident = incident,
            vehicle = state.value.vehicle.copy(speedMps = VehicleReading(10f, 50_000L))) }
        compose.onNodeWithText("안전하게 정차해주세요").assertIsDisplayed()
        compose.onNodeWithText(CabinGuidance.PASSENGER).assertDoesNotExist()
        compose.onNodeWithTag("passenger-help-cue").assertDoesNotExist()
    }

    @Test fun partialEmptyFrameInQuietStateRemainsCurrentlyUnconfirmed() {
        dashboard(PrototypeUi(incident = incident, cabinClearConfirmed = true, cabinEmptyReadingComplete = false,
            now = 50_000L, occupiedSeatAreas = setOf(1, 4), vehicle = VehicleSnapshot(seats = listOf(
                SeatObservation(1, "운전석", VehicleReading(false, 50_000L)),
            ))))
        compose.onNodeWithText("좌석 비움 관측").assertIsDisplayed()
        compose.onNodeWithText("현재 좌석 상태 미확인").assertIsDisplayed()
        compose.onNodeWithText("현재 좌석 감지됨").assertDoesNotExist()
        compose.onNodeWithText("관측 좌석 비움").assertDoesNotExist()
        assertQuietCabin()
    }

    @Test fun quietLatchSuppressesDrivingPromptsAfterSeatReadingsBecomeStale() {
        compose.setContent {
            SafetyStepTheme(ThemePreference.DARK) {
                DrivingContent(PrototypeUi(incident = incident.respond(Responder.UNKNOWN, HelpState.NEEDS_HELP),
                    cabinClearConfirmed = true, drivingRestricted = true, now = 90_000L,
                    vehicle = VehicleSnapshot(seats = listOf(SeatObservation(1, "운전석", VehicleReading(false, 50_000L))))))
            }
        }
        compose.onNodeWithText("좌석 비움 관측").assertIsDisplayed()
        compose.onNodeWithText("현재 좌석 상태 미확인").assertIsDisplayed()
        compose.onNodeWithText("도움 요청 기록 있음").assertIsDisplayed()
        compose.onNodeWithText("안전하게 정차해주세요").assertDoesNotExist()
        compose.onNodeWithText("무리하게 움직이지 마세요").assertDoesNotExist()
        compose.onNodeWithText("관측 좌석 비움").assertDoesNotExist()
        assertQuietCabin()
    }

    @Test fun restrictedStoppedViewUsesSharedExitPassengerMotionAndHelpPriorityWithoutTouchTasks() {
        val state = mutableStateOf(PrototypeUi(incident = incident, supportQualified = true, drivingRestricted = true,
            now = 50_000L, vehicle = VehicleSnapshot(speedMps = VehicleReading(0f, 50_000L))))
        compose.setContent {
            SafetyStepTheme(ThemePreference.LIGHT) {
                Box(Modifier.size(544.dp, 732.dp)) { DrivingContent(state.value) }
            }
        }
        fun assertTouchFree() {
            compose.onAllNodes(hasClickAction()).assertCountEquals(0)
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)).assertCountEquals(0)
            compose.onNodeWithTag("passenger-help-cue").assertDoesNotExist()
        }
        compose.onNodeWithText("안전한 곳으로 이동하세요").assertIsDisplayed()
        compose.onNodeWithText(CabinGuidance.EXIT).assertIsDisplayed()
        compose.onNodeWithText("안전하게 정차해주세요").assertDoesNotExist()
        assertTouchFree()
        compose.runOnIdle { state.value = state.value.copy(occupiedSeatAreas = setOf(1, 4),
            vehicle = state.value.vehicle.copy(seats = listOf(
                SeatObservation(1, "운전석", VehicleReading(false, 50_000L)),
                SeatObservation(4, "동승석", VehicleReading(true, 50_000L)),
            ))) }
        compose.onNodeWithText("동승 좌석 감지됨").assertIsDisplayed()
        compose.onNodeWithText(CabinGuidance.PASSENGER).assertIsDisplayed()
        assertTouchFree()
        compose.runOnIdle { state.value = state.value.copy(vehicle = state.value.vehicle.copy(speedMps = VehicleReading(10f, 50_000L))) }
        compose.onNodeWithText("안전하게 정차해주세요").assertIsDisplayed()
        compose.onNodeWithText(CabinGuidance.PASSENGER).assertDoesNotExist()
        assertTouchFree()
        compose.runOnIdle { state.value = state.value.copy(incident = incident.respond(Responder.PASSENGER, HelpState.NEEDS_HELP)) }
        compose.onNodeWithText("무리하게 움직이지 마세요").assertIsDisplayed()
        compose.onNodeWithText("안전하게 정차해주세요").assertDoesNotExist()
        assertTouchFree()
        compose.runOnIdle { state.value = state.value.copy(cabinClearConfirmed = true) }
        compose.onNodeWithText("좌석 비움 관측").assertIsDisplayed()
        compose.onNodeWithText("도움 요청 기록 있음").assertIsDisplayed()
        compose.onNodeWithText("무리하게 움직이지 마세요").assertDoesNotExist()
        assertTouchFree()
    }

    @Test fun reportResponseAgeDoesNotClaimProviderFixFreshness() {
        compose.setContent {
            SafetyStepTheme(ThemePreference.LIGHT) {
                ReportLocationCard(ReportLocation(
                    status = ReportLocationStatus.AVAILABLE, latitude = 37.5, longitude = 127.0,
                    address = "샘플 도로", headingDegrees = 90.0, receivedAt = 45_000L, sample = true,
                ), 50_000L, {})
            }
        }
        compose.onNodeWithTag("report-location-details").assertHeightIsAtLeast(80.dp).performClick()
        compose.onNodeWithText("5초 전 내비게이션에서 받은 위치").assertExists()
        compose.onNodeWithText("측위 시각·정확도 알 수 없음").assertExists()
        compose.onNodeWithText("샘플 위치 · 실제 신고에 사용하지 마세요").assertExists()
        compose.onNodeWithText("도로 진행 방향은 주변 표지로 확인해주세요.").assertExists()
        compose.onNodeWithTag("report-coordinates").assertExists()
    }
}
