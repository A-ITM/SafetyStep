package io.github.aitm.pleosapp.prototype

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aitm.pleosapp.MainActivity
import io.github.aitm.pleosapp.BuildConfig
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetyStepSpeechTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun firstActualGuidanceCompletesBeforeSyntheticSdkExceptionSetsHelp() {
        assumeTrue(BuildConfig.FLAVOR == "pleos")
        val model = ViewModelProvider(compose.activity)[PrototypeViewModel::class.java]
        compose.waitUntil(15_000) { !model.state.value.drivingRestricted }
        val pcm = File(compose.activity.filesDir, "response-test.pcm")
        assertTrue("Install the synthetic cannot-move PCM fixture first", pcm.exists())
        compose.runOnIdle {
            model.newLocalDemo(); model.setConsent(false); model.setReceiverEnabled(false); model.setOnline(false)
            model.setForeground(true)
            model.operatorAction(Intent("${compose.activity.packageName}.OPERATOR")
                .putExtra("command", "position").putExtra("road", "test-highway")
                .putExtra("direction", "east").putExtra("roadKind", "SHOULDER")
                .putExtra("meters", "1000").putExtra("speedMps", "0")
                .putExtra("observedAt", System.currentTimeMillis().toString()).putExtra("reset", true))
            model.begin()
            // Queue this before first-action completion; no TTS is replayed by listen().
            model.requestVoice(pcm.readBytes())
        }
        compose.waitUntil(55_000) { model.state.value.incident?.help == HelpState.NEEDS_HELP }
        val sessionId = compose.runOnIdle {
            val s = model.state.value
            assertEquals("${s.voicePhase}: ${s.voiceDetail}", HelpState.NEEDS_HELP, s.incident?.help)
            assertEquals("SYNTHETIC_PCM", s.voiceInput)
            assertEquals(HazardState.ACTIVE, s.incident?.hazard)
            assertEquals(EventType.STOPPED_VEHICLE, s.incident?.eventType)
            assertFalse(s.incident!!.evacuated)
            assertEquals(0, s.hazardCommands)
            assertEquals(0, s.journey.trunkAttempts)
            s.incident!!.id
        }
        compose.onNodeWithTag("screen-HELP").assertExists()
        val metrics = File(compose.activity.filesDir, "prototype-metrics.jsonl").readLines()
            .map(::JSONObject).filter { it.optString("supportSessionId") == sessionId }
        val names = metrics.map { it.getString("metric") }
        val started = names.indexOf("support_voice_start")
        val completed = names.indexOf("support_voice_complete")
        val listening = names.indexOf("exception_listen_start")
        val response = names.indexOf("occupant_response")
        assertTrue("Actual TTS onStart must be recorded", started >= 0)
        assertTrue("Actual TTS completion must precede actual recognition start", completed > started && listening > completed)
        assertTrue("Recognition start must precede the interpreted response", response > listening)
        assertEquals(1, names.count { it == "support_voice_start" })
        assertEquals(1, names.count { it == "support_voice_complete" })
        assertTrue(metrics[completed].getLong("elapsedMs") >= metrics[started].getLong("elapsedMs"))
    }
}
