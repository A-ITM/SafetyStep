package io.github.aitm.pleosapp.prototype
import io.github.aitm.pleosapp.speech.ResponseInterpreter
import org.junit.Assert.*
import org.junit.Test
class ResponseInterpreterTest {
    @Test fun helpAndNegationTakePriority() {
        for (text in listOf("저는 운전자예요. 움직일 수 없어요.", "못 움직여요. 도와주세요", "움직일 수 있어요. 아니요 도와주세요")) {
            assertEquals(text, HelpState.NEEDS_HELP, ResponseInterpreter.interpret(text).help)
        }
        for (text in listOf("움직일 수 있는지 모르겠어요", "움직일 수 있을 것 같아요", "괜찮아요", "잘 모르겠어요", "도움이 필요하지 않아요")) {
            assertEquals(text, HelpState.UNKNOWN, ResponseInterpreter.interpret(text).help)
        }
    }
    @Test fun roleAndExplicitMobilityAreIndependent() {
        val a=ResponseInterpreter.interpret("동승자예요. 움직일 수 있어요.")
        assertEquals(Responder.PASSENGER,a.responder);assertEquals(HelpState.CAN_MOVE,a.help)
        val b=ResponseInterpreter.interpret("네, 들려요")
        assertTrue(b.acknowledged);assertEquals(Responder.UNKNOWN,b.responder);assertEquals(HelpState.UNKNOWN,b.help)
        assertFalse(ResponseInterpreter.interpret("운전자가 아닌데요").acknowledged)
        assertEquals(HelpState.UNKNOWN,ResponseInterpreter.interpret("움직일 수 있어요? 모르겠어요").help)
    }
    @Test fun contractedAndSpacedHelpRequestsDoNotRequireRoleOrMobilityAnswers() {
        for (text in listOf("도와줘", "도와 줘!", "살려줘", "살려 줘요", "도와주세요", "도움 필요해요", "도움이 좀 필요해요")) {
            val heard = ResponseInterpreter.interpret(text)
            assertEquals(text, HelpState.NEEDS_HELP, heard.help)
            assertTrue(text, heard.acknowledged)
            assertEquals(Responder.UNKNOWN, heard.responder)
        }
    }
    @Test fun denyingHelpNeverMeansHelpNeededOrMovementConfirmed() {
        for (text in listOf("도움 필요 없어요", "도움이 필요없어요", "도움이 필요하지 않아요", "도움이 필요는 없어요",
            "도와주지 마세요", "도와주지 않아도 돼요", "도와줄 필요 없어요", "도와줄 필요가 없어요",
            "도와주실 필요가 없어요", "도움이 필요한 건 아니에요", "도움이 필요하지도 않아요")) {
            val heard = ResponseInterpreter.interpret(text)
            assertEquals(text, HelpState.UNKNOWN, heard.help)
            assertFalse(text, heard.acknowledged)
        }
    }
    @Test fun lateHelpOverridesPriorAcknowledgementOrMobilityWithoutChangingRoadLifecycle() {
        for (prior in listOf(HelpState.UNKNOWN, HelpState.CAN_MOVE)) {
            val incident = Incident("late-help", 1_000, "MANUAL_TEST").respond(Responder.DRIVER, prior).resolve()
            val heard = ResponseInterpreter.interpret("도와 줘")
            val updated = incident.respond(heard.responder, heard.help)
            assertEquals(HelpState.NEEDS_HELP, updated.help)
            assertEquals(HazardState.RESOLVED, updated.hazard)
            assertEquals(incident.id, updated.id)
        }
    }
}
