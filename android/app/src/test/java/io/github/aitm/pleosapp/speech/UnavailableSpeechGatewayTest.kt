package io.github.aitm.pleosapp.speech

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UnavailableSpeechGatewayTest {
    @Test fun demoNeverReportsPlaybackOrMicrophoneSuccess() = runBlocking {
        val phases = mutableListOf<VoicePhase>()
        val speech = UnavailableSpeechGateway()
        assertFalse(speech.available)
        assertTrue(speech.say("안내", phases::add) is VoiceResult.Failed)
        assertTrue(speech.listen(phases::add) is VoiceResult.Failed)
        assertTrue(speech.ask("질문", phases::add) is VoiceResult.Failed)
        assertFalse(phases.contains(VoicePhase.SPEAKING))
        assertFalse(phases.contains(VoicePhase.LISTENING))
    }
}
