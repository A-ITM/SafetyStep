package io.github.aitm.pleosapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aitm.pleosapp.location.ReportLocation
import io.github.aitm.pleosapp.location.ReportLocationStatus
import io.github.aitm.pleosapp.prototype.*
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
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    IncidentDashboardContent(state, onHelp, {}, {})
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
        compose.onNodeWithTag("urgent-help").performScrollTo().performClick()
        assertEquals(1, helps)
    }

    @Test fun storedHelpPreemptsClearSeatsAndTerminalRoadEvent() {
        dashboard(PrototypeUi(
            incident = incident.resolve().respond(Responder.PASSENGER, HelpState.NEEDS_HELP),
            supportQualified = true, cabinClearConfirmed = true,
        ))
        compose.onNodeWithText("무리하게 움직이지 마세요").assertExists()
        compose.onNodeWithTag("contact-phone").assertExists()
        compose.onNodeWithTag("urgent-help").assertExists()
        val location = compose.onNodeWithTag("report-location").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val contact = compose.onNodeWithTag("contact-phone").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Reporting location must appear before the help contact action", location.bottom <= contact.top)
    }

    @Test fun clearedSeatsKeepReportingGuidanceWithoutPhoneOrHelpButtons() {
        dashboard(PrototypeUi(incident = incident, supportQualified = true, cabinClearConfirmed = true))
        compose.onNodeWithTag("contact-phone").assertDoesNotExist()
        compose.onNodeWithTag("urgent-help").assertDoesNotExist()
        compose.onNodeWithText("안전한 곳에서 휴대전화로 상황과 위치를 알려주세요.").assertExists()
        compose.onNodeWithTag("report-location").assertExists()
        compose.onNodeWithText("안내 내용이 화면에 남아 있어요").assertExists()
    }

    @Test fun warningsShowActualDistanceTypeFreshnessAndVoiceHold() {
        compose.setContent {
            SafetyStepTheme(ThemePreference.LIGHT) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    WarningCards(listOf(
                        WarningCard("stop-a", EventType.STOPPED_VEHICLE, 347.1, EventFreshness.STALE, "STOP_OBSERVATION", voiceHeld = true),
                        WarningCard("crash-b", EventType.ACCIDENT, null, EventFreshness.UNCONFIRMED, "MANUAL_TEST"),
                    ))
                }
            }
        }
        compose.onNodeWithText("348 m 앞").assertExists()
        compose.onNodeWithText("거리 확인 필요").assertExists()
        compose.onNodeWithText("전방 사고 알림").assertExists()
        compose.onNodeWithText("오래된 알림 · 현재 상태 확인 필요").assertExists()
        compose.onNodeWithText("최신 상태 확인 안 됨").assertExists()
        compose.onNodeWithText("저속 정차 중 · 음성 안내 보류").assertExists()
        compose.onNodeWithText("400").assertDoesNotExist()
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
        compose.onNodeWithText("5초 전 내비게이션에서 받은 위치").assertExists()
        compose.onNodeWithText("측위 시각·정확도 알 수 없음").assertExists()
        compose.onNodeWithText("샘플 위치 · 실제 신고에 사용하지 마세요").assertExists()
        compose.onNodeWithText("도로 진행 방향은 주변 표지로 확인해주세요.").assertExists()
        compose.onNodeWithTag("report-coordinates").assertExists()
    }
}
