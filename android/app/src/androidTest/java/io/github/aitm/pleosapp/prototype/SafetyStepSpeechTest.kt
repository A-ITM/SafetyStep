package io.github.aitm.pleosapp.prototype

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aitm.pleosapp.MainActivity
import io.github.aitm.pleosapp.BuildConfig
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetyStepSpeechTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun syntheticAudioThroughActualSdkUpdatesHelpFlow() {
        assumeTrue(BuildConfig.FLAVOR == "pleos")
        val model=ViewModelProvider(compose.activity)[PrototypeViewModel::class.java]
        compose.waitUntil(15_000) { !model.state.value.drivingRestricted }
        val pcm=File(compose.activity.filesDir,"response-test.pcm")
        assertTrue("Install the synthetic cannot-move PCM fixture first",pcm.exists())
        compose.runOnIdle {
            model.newLocalDemo();model.begin();model.setCollision(CollisionContext.BREAKDOWN)
            model.startSupportResponse(false);model.requestVoice(pcm.readBytes())
        }
        compose.waitUntil(40_000) { model.state.value.incident?.help == HelpState.NEEDS_HELP || model.state.value.voiceDetail.isNotEmpty() }
        compose.runOnIdle {
            val s=model.state.value
            assertEquals("${s.voicePhase}: ${s.voiceDetail} / ${s.voiceText}",HelpState.NEEDS_HELP,s.incident?.help)
            assertEquals(SupportScreen.HELP,SupportPolicy.screen(s.incident,s.journey))
            assertEquals("SYNTHETIC_PCM",s.voiceInput)
            assertEquals(HazardState.ACTIVE,s.incident?.hazard)
            assertFalse(s.incident!!.evacuated)
        }
    }
}
