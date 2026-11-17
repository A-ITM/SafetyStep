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

    @Test fun storedHelpPreemptsClearSeatsAndTerminalRoadEvent() {
        dashboard(PrototypeUi(
            incident = incident.resolve().respond(Responder.PASSENGER, HelpState.NEEDS_HELP),
            supportQualified = true, cabinClearConfirmed = true,
        ))
        compose.onNodeWithText("무리하게 움직이지 마세요").assertExists()
        compose.onNodeWithTag("contact-phone").assertExists()
        compose.onNodeWithTag("urgent-help").assertDoesNotExist()
        visibleInsidePanel("report-location")
        visibleInsidePanel("contact-phone")
        compose.onNodeWithTag("contact-phone").assertHeightIsAtLeast(80.dp)
    }

    @Test fun clearedSeatsKeepReportingGuidanceWithoutPhoneOrHelpButtons() {
        dashboard(PrototypeUi(incident = incident, supportQualified = false, cabinClearConfirmed = true))
        compose.onNodeWithTag("contact-phone").assertDoesNotExist()
        compose.onNodeWithTag("urgent-help").assertDoesNotExist()
        compose.onNodeWithText("차량으로 돌아가지 마세요").assertIsDisplayed()
        compose.onNodeWithText("안전하게 정차해주세요").assertDoesNotExist()
        compose.onNodeWithTag("report-location").assertExists()
        compose.onNodeWithText("대피 완료").assertDoesNotExist()
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
