package io.github.aitm.pleosapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aitm.pleosapp.accident.*
import io.github.aitm.pleosapp.prototype.*
import io.github.aitm.pleosapp.vehicle.SeatObservation
import io.github.aitm.pleosapp.vehicle.VehicleReading
import io.github.aitm.pleosapp.vehicle.VehicleSnapshot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccidentLocationPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val now = 50_000L
    private val incident = Incident("accident-ui", now, "OPERATOR_EVENT", eventType = EventType.ACCIDENT)
    private val gps = AccidentLocation(37.4127, 127.0946, now, now, 5.0, origin = LocationOrigin.GPS)
    private fun nearby(id: String, distance: Double?, freshness: EventFreshness) = NearbyAccident(
        AccidentEvent(id, "A", "OPERATOR_EVENT", now, gps), distance, freshness,
    )

    @Test fun accidentContextVehicleMotionAndRegistrationRemainIndependent() {
        val state = mutableStateOf(PrototypeUi(
            incident = incident, now = now, drivingRestricted = false,
            stopReportStatus = StopReportStatus.SHARED,
            accidentNetwork = AccidentNetworkUi(shareStatus = AccidentShareStatus.LOCATING),
        ))
        compose.setContent {
            SafetyStepTheme(ThemePreference.LIGHT) {
                Box(Modifier.size(544.dp, 732.dp).testTag("panel")) {
                    IncidentDashboardContent(state.value, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("사고 후 안전 지원").assertIsDisplayed()
        compose.onNodeWithText("안전하게 정차해주세요").assertIsDisplayed()
        compose.onNodeWithText("정지 여부 확인 필요").assertIsDisplayed()
        compose.onNodeWithText("위치 확인 중 · 공유 보류").assertIsDisplayed()
        compose.onNodeWithTag("stop-report-status").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(vehicle = VehicleSnapshot(speedMps = VehicleReading(0f, now))) }
        compose.onNodeWithText("정지 확인").assertIsDisplayed()
        compose.onNodeWithText("위치 확인 중 · 공유 보류").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(
                vehicle = VehicleSnapshot(speedMps = VehicleReading(20f, now)),
                accidentNetwork = AccidentNetworkUi(shareStatus = AccidentShareStatus.REGISTERED,
                    sharedEvent = AccidentEvent("registered", "B", "OPERATOR_EVENT", now, gps)),
            )
        }
        compose.onNodeWithText("이동 중").assertIsDisplayed()
        compose.onNodeWithText("서버 등록 완료").assertIsDisplayed()
        compose.onNodeWithText("뒤차 안내 완료").assertDoesNotExist()
        compose.onNodeWithText("수신 완료").assertDoesNotExist()
        compose.onNodeWithTag("urgent-help").assertIsDisplayed().assertHeightIsAtLeast(80.dp)
        compose.runOnIdle { state.value = state.value.copy(now = now + 31_000L) }
        compose.onNodeWithText("정지 여부 확인 필요").assertIsDisplayed()
        compose.onNodeWithText("서버 등록 완료").assertIsDisplayed()
    }

    @Test fun normalDrivingShowsNearestFreshGpsAccidentWithRadialDistance() {
        compose.setContent {
            SafetyStepTheme(ThemePreference.DARK) {
                Box(Modifier.size(544.dp, 732.dp)) {
                    DrivingContent(PrototypeUi(drivingRestricted = true,
                        warningCards = listOf(WarningCard("legacy-stopped", EventType.STOPPED_VEHICLE, 20.0, EventFreshness.FRESH, "TEST")),
                        accidentNetwork = AccidentNetworkUi(receiverStatus = ReceiverStatus.ACTIVE, nearby = listOf(
                            nearby("stale", 10.0, EventFreshness.STALE),
                            nearby("far", 350.0, EventFreshness.FRESH),
                            nearby("nearest", 120.2, EventFreshness.FRESH),
                        ))))
                }
            }
        }
        compose.onNodeWithTag("nearby-accident-nearest").assertIsDisplayed()
        compose.onNodeWithTag("nearby-accident-stale").assertDoesNotExist()
        compose.onNodeWithTag("nearby-accident-far").assertDoesNotExist()
        compose.onNodeWithTag("warning-legacy-stopped").assertDoesNotExist()
        compose.onNodeWithText("주변 사고").assertIsDisplayed()
        compose.onNodeWithText("약 121 m").assertIsDisplayed()
        compose.onNodeWithText("직선 거리 · 도로 방향 미확인").assertIsDisplayed()
        compose.onNodeWithText("전방 사고 알림").assertDoesNotExist()
        compose.onNodeWithText("전방 정차 차량").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)).assertCountEquals(0)
    }

    @Test fun unavailableUpdatePreservesAccidentAsUnconfirmedRatherThanResolved() {
        val event = mutableStateOf(nearby("unconfirmed", null, EventFreshness.UNCONFIRMED))
        compose.setContent { SafetyStepTheme(ThemePreference.LIGHT) { NearbyAccidentWarning(event.value) } }
        compose.onNodeWithText("주변 사고").assertIsDisplayed()
        compose.onNodeWithText("거리 확인 필요").assertIsDisplayed()
        compose.onNodeWithText("최신 상태 미확인").assertIsDisplayed()
        compose.onNodeWithText("이전 확인 위치 기준 · 도로 방향 미확인").assertIsDisplayed()
        compose.runOnIdle { event.value = nearby("unconfirmed", 250.0, EventFreshness.STALE) }
        compose.onNodeWithText("약 250 m").assertIsDisplayed()
        compose.onNodeWithText("오래된 사고 알림 · 최신 상태 미확인").assertIsDisplayed()
        compose.onNodeWithText("해결", substring = true).assertDoesNotExist()
    }

    @Test fun accidentHelpKeepsReportingAndEmergencyActionInSmallPane() {
        compose.setContent {
            SafetyStepTheme(ThemePreference.DARK) {
                Box(Modifier.size(544.dp, 732.dp).testTag("panel")) {
                    IncidentDashboardContent(PrototypeUi(
                        incident = incident.respond(Responder.PASSENGER, HelpState.NEEDS_HELP),
                        now = now, drivingRestricted = false,
                        accidentNetwork = AccidentNetworkUi(shareStatus = AccidentShareStatus.CONSENT_REQUIRED),
                    ), {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("사고 후 안전 지원").assertIsDisplayed()
        compose.onNodeWithText("무리하게 움직이지 마세요").assertIsDisplayed()
        visibleInsidePanel("report-location")
        visibleInsidePanel("contact-phone")
        compose.onNodeWithTag("contact-phone").assertHeightIsAtLeast(80.dp)
        compose.onNodeWithTag("urgent-help").assertDoesNotExist()
    }

    @Test fun quietAccidentClosesSharingDetailsAndKeepsOnlyPassiveStateAsSensorsAge() {
        val state = mutableStateOf(PrototypeUi(
            incident = incident.respond(Responder.PASSENGER, HelpState.NEEDS_HELP),
            now = now, drivingRestricted = false,
            vehicle = VehicleSnapshot(seats = listOf(SeatObservation(1, "운전석", VehicleReading(false, now)))),
            accidentNetwork = AccidentNetworkUi(shareStatus = AccidentShareStatus.QUEUED),
        ))
        compose.setContent {
            SafetyStepTheme(ThemePreference.DARK) {
                Box(Modifier.size(745.dp, 1042.dp)) { IncidentDashboardContent(state.value, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("accident-share-details").performScrollTo().performClick()
        compose.onNodeWithTag("accident-location-refresh").assertExists()
        compose.runOnIdle { state.value = state.value.copy(cabinClearConfirmed = true, cabinEmptyReadingComplete = true) }
        compose.onNodeWithTag("accident-location-refresh").assertDoesNotExist()
        compose.onNodeWithText("좌석 비움 관측").assertIsDisplayed()
        compose.onNodeWithText("도움 요청 기록 있음").assertIsDisplayed()
        compose.onNodeWithText("서버 등록 대기").assertIsDisplayed()
        compose.onNodeWithText("무리하게 움직이지 마세요").assertDoesNotExist()
        listOf("contact-phone", "urgent-help", "report-location", "support-voice", "bitbox-hazard", "accident-share-details")
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(now = now + 31_000L, cabinEmptyReadingComplete = false,
            accidentNetwork = state.value.accidentNetwork.copy(shareStatus = AccidentShareStatus.REGISTERED)) }
        compose.onNodeWithText("좌석 비움 관측").assertIsDisplayed()
        compose.onNodeWithText("현재 좌석 상태 미확인").assertIsDisplayed()
        compose.onNodeWithText("관측 좌석 비움").assertDoesNotExist()
        compose.onNodeWithText("서버 등록 완료").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)).assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(cabinClearConfirmed = false) }
        compose.onNodeWithTag("accident-location-refresh").assertDoesNotExist()
        compose.onNodeWithTag("contact-phone").assertIsDisplayed()
    }

    @Test fun accidentDrivingHasContextAndShareStatusWithoutTouchTasks() {
        compose.setContent {
            SafetyStepTheme(ThemePreference.LIGHT) {
                DrivingContent(PrototypeUi(incident = incident, now = now, drivingRestricted = true,
                    vehicle = VehicleSnapshot(speedMps = VehicleReading(15f, now)),
                    accidentNetwork = AccidentNetworkUi(shareStatus = AccidentShareStatus.QUEUED)))
            }
        }
        compose.onNodeWithText("사고 후 안전 지원").assertIsDisplayed()
        compose.onNodeWithText("이동 중").assertIsDisplayed()
        compose.onNodeWithText("서버 등록 대기").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)).assertCountEquals(0)
    }

    private fun visibleInsidePanel(tag: String) {
        val panel = compose.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("$tag must remain inside the pane", node.left >= panel.left && node.right <= panel.right &&
            node.top >= panel.top && node.bottom <= panel.bottom)
    }
}
