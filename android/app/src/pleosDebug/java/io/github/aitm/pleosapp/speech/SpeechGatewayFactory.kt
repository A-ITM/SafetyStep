package io.github.aitm.pleosapp.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitialize
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitialize
import ai.pleos.playground.tts.listener.EventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

object SpeechGatewayFactory {
    fun create(context: Context): SpeechGateway = PleosSpeechGateway(context.applicationContext)
}

/** One SDK pair per question: callbacks from a cancelled turn cannot enter a later turn. */
private class PleosSpeechGateway(private val context: Context) : SpeechGateway {
    override val available = true
    override suspend fun ask(prompt: String, phase: (VoicePhase) -> Unit, testPcm: ByteArray?): VoiceResult = session(prompt, phase, testPcm, true)
    override suspend fun say(prompt: String, phase: (VoicePhase) -> Unit): VoiceResult = session(prompt, phase, null, false)
    private suspend fun session(prompt: String, phase: (VoicePhase) -> Unit, testPcm: ByteArray?, listenAfter: Boolean): VoiceResult =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val handler = Handler(Looper.getMainLooper())
                var active = true
                var operation = VoicePhase.PREPARING
                var tts: TextToSpeech? = null
                var stt: SpeechToText? = null
                var ttsReady = false
                var sttReady = !listenAfter
                var playbackStarted = false
                fun cleanup() {
                    active = false
                    handler.removeCallbacksAndMessages(null)
                    runCatching { tts?.stop(); tts?.release() }
                    runCatching { stt?.stop(); stt?.release() }
                }
                fun finish(result: VoiceResult) {
                    if (!active || !continuation.isActive) return
                    cleanup()
                    continuation.resume(result)
                }
                fun callback(block: () -> Unit) { handler.post { if (active && continuation.isActive) block() } }
                fun attempt(block: () -> Unit) {
                    runCatching(block).onFailure { finish(VoiceResult.Failed(it.message ?: "음성 연결 오류")) }
                }
                fun stage(value: VoicePhase) { operation = value; phase(value) }
                fun speakWhenReady() {
                    if (ttsReady && sttReady && operation == VoicePhase.PREPARING) {
                        stage(VoicePhase.SPEAKING)
                        attempt { tts?.speak(prompt) }
                    }
                }
                fun listen() {
                    stage(VoicePhase.LISTENING)
                    attempt {
                        if (testPcm == null) stt?.request()
                        else { stt?.sendAudio(testPcm); stt?.completeAudioSend() }
                    }
                    handler.postDelayed({ if (active && operation == VoicePhase.LISTENING) finish(VoiceResult.NoSpeech) }, 15_000)
                }
                continuation.invokeOnCancellation { handler.post { cleanup() } }
                phase(VoicePhase.PREPARING)
                attempt {
                    tts = TextToSpeech(context, TtsMode.ON_DEVICE)
                    if (listenAfter) stt = SpeechToText(context, SttMode.ON_DEVICE)
                    val ttsListener = object : EventListener {
                        override fun onReady() = Unit
                        override fun onStart() = callback { playbackStarted = true }
                        override fun onUpdatedRms(rms: Double) = Unit
                        override fun onDone() = callback {
                            if (operation == VoicePhase.SPEAKING) {
                                if (playbackStarted) { if (listenAfter) listen() else finish(VoiceResult.Spoken) } else finish(VoiceResult.Failed("질문 음성 재생을 확인할 수 없어요"))
                            }
                        }
                        override fun onStop() = Unit
                        override fun onError(errorType: TtsError) = callback { finish(VoiceResult.Failed("TTS ${errorType.name}")) }
                    }
                    val sttListener = object : ResultListener {
                        override fun onReady() = Unit
                        override fun onStartedRecognition() = Unit
                        override fun onUpdated(stt: String, completed: Boolean) = callback {
                            if (operation == VoicePhase.LISTENING && completed && stt.isNotBlank()) finish(VoiceResult.Text(stt))
                        }
                        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                        override fun onUpdatedRms(rms: Double) = Unit
                        override fun onEndedRecognition() = callback {
                            // This service can end before delivering final text. Allow settlement.
                            handler.postDelayed({ if (active && operation == VoicePhase.LISTENING) finish(VoiceResult.NoSpeech) }, 1_000)
                        }
                        override fun onError(errorType: SttError) = callback { finish(VoiceResult.Failed("STT ${errorType.name}")) }
                    }
                    tts?.initialize(object : TtsInitialize {
                        override fun onSuccess() = callback { attempt { tts?.addEventListener(ttsListener); tts?.setLocale(Locale.KOREA); ttsReady = true; speakWhenReady() } }
                        override fun onFailure(errorType: TtsError) = callback { finish(VoiceResult.Failed("TTS ${errorType.name}")) }
                    })
                    stt?.initialize(object : SttInitialize {
                        override fun onSuccess() = callback { attempt { stt?.addListener(sttListener); stt?.setLocale(Locale.KOREA); sttReady = true; speakWhenReady() } }
                        override fun onFailure(errorType: SttError) = callback { finish(VoiceResult.Failed("STT ${errorType.name}")) }
                    })
                    handler.postDelayed({ if (active) finish(VoiceResult.Failed("음성 서비스 연결 시간이 초과됐어요")) }, 35_000)
                }
            }
        }
}
