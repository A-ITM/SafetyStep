package io.github.aitm.pleosapp.speech

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import java.io.File
import java.util.Locale
import org.json.JSONObject

/** Debug-only SDK diagnostic. Never updates incident state, controls a vehicle, or sends a report. */
class SpeechProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val startedAt = SystemClock.elapsedRealtime()
    private lateinit var output: TextView
    private lateinit var tts: TextToSpeech
    private lateinit var stt: SpeechToText
    private val lines = mutableListOf<String>()
    private var ttsReady = false
    private var sttReady = false
    private var closed = false
    private var operation = "IDLE"
    private var generation = 0
    private var chainMicrophone = false
    private var playbackStarted = false
    private var finalTextReceived = false
    private var lastRmsAt = 0L
    private var autoRun = false

    private fun event(kind: String, detail: String = "") {
        val ms = SystemClock.elapsedRealtime() - startedAt
        val row = JSONObject().put("elapsedMs", ms).put("event", kind).put("detail", detail)
        File(filesDir, "speech-probe.jsonl").appendText("$row\n")
        Log.i("SpeechProbe", row.toString())
        lines.add("${ms}ms $kind $detail")
        output.text = lines.takeLast(35).joinToString("\n")
    }

    private fun callback(block: () -> Unit) {
        handler.post { if (!closed) block() }
    }

    private fun attempt(label: String, block: () -> Unit) {
        runCatching(block).onFailure {
            event("${label}_EXCEPTION", "${it.javaClass.simpleName}: ${it.message}")
            operation = "ERROR"
            chainMicrophone = false
        }
    }

    private val ttsListener = object : EventListener {
        override fun onReady() = callback { event("TTS_READY") }
        override fun onStart() = callback { playbackStarted = true; event("TTS_STARTED") }
        override fun onUpdatedRms(rms: Double) = callback { rms("TTS", rms) }
        override fun onDone() = callback {
            event("TTS_DONE", "SDK 재생 완료; 사람이 들었는지는 별도 확인")
            if (operation == "TTS") {
                val listen = chainMicrophone && playbackStarted
                chainMicrophone = false
                operation = "IDLE"
                generation++
                if (listen) startMicrophone()
            }
        }
        override fun onStop() = callback { event("TTS_STOPPED") }
        override fun onError(errorType: TtsError) = callback { fail("TTS_ERROR", errorType.name) }
    }

    private val sttListener = object : ResultListener {
        override fun onReady() = callback { event("STT_READY") }
        override fun onStartedRecognition() = callback { event("STT_STARTED") }
        override fun onUpdated(stt: String, completed: Boolean) = callback {
            event(if (completed) "STT_FINAL" else "STT_PARTIAL", stt)
            if (operation in listOf("MICROPHONE", "PCM") && completed && stt.isNotBlank()) {
                finalTextReceived = true
                event("RECOGNIZED_TEXT", "텍스트 수신만 확인; 응답자·도움 필요·의식 상태 판정 아님")
                event("RECOGNITION_COMPLETE")
                operation = "IDLE"
                generation++
                runCatching { this@SpeechProbeActivity.stt.stop() }
            }
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = callback { event("STT_EPD", "$on:$off") }
        override fun onUpdatedRms(rms: Double) = callback { rms("STT", rms) }
        override fun onEndedRecognition() = callback {
            event("STT_ENDED")
            if (operation in listOf("MICROPHONE", "PCM")) {
                // The installed service sends onEndedRecognition BEFORE the final text.
                // Keep a short diagnostic settlement window; never map this callback to nonresponse.
                val token = generation
                handler.postDelayed({
                    if (!closed && generation == token && !finalTextReceived) {
                        event("NO_FINAL_TEXT", "종료 후 1초 동안 최종 텍스트 없음; 탑승자 무응답 판정 아님")
                        operation = "IDLE"
                        generation++
                    }
                }, 1_000)
            }
        }
        override fun onError(errorType: SttError) = callback { fail("STT_ERROR", errorType.name) }
    }

    private fun rms(source: String, value: Double) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRmsAt >= 1000) { lastRmsAt = now; event("${source}_RMS", value.toString()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))) {
            finish(); return
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        fun label(text: String) { content.addView(TextView(this).apply { this.text = text; textSize = 18f }) }
        fun button(text: String, action: () -> Unit) {
            content.addView(Button(this).apply { this.text = text; setOnClickListener { action() } })
        }
        label("음성 SDK 진단 · ON_DEVICE / ko-KR / 에뮬레이터 전용")
        label("마이크는 아래 버튼으로 시작 · 인식 텍스트와 오류는 앱 내부 로그에만 저장 · 실제 신고/차량 제어 없음")
        button("1. 질문 음성 출력") { speak(false) }
        button("2. 마이크 응답 인식 (최대 15초)") { startMicrophone() }
        button("3. 질문 완료 후 마이크 응답 인식") { speak(true) }
        button("4. 합성 테스트 PCM 인식 (마이크 제외)") { sendPcm() }
        button("중지") { stop("USER_STOP") }
        output = TextView(this).apply { textSize = 15f; setTextIsSelectable(true) }
        content.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(content)
        File(filesDir, "speech-probe.jsonl").writeText("")
        event("PROBE_START", "SDK 3.3.1; ON_DEVICE; ko-KR")
        attempt("CREATE") {
            tts = TextToSpeech(applicationContext, TtsMode.ON_DEVICE)
            stt = SpeechToText(applicationContext, SttMode.ON_DEVICE)
            tts.initialize(object : TtsInitialize {
                override fun onSuccess() = callback {
                    attempt("TTS_SETUP") {
                        tts.addEventListener(ttsListener); tts.setLocale(Locale.KOREA)
                        ttsReady = true; event("TTS_INITIALIZED"); auto()
                    }
                }
                override fun onFailure(errorType: TtsError) = callback { event("TTS_INIT_ERROR", errorType.name) }
            })
            stt.initialize(object : SttInitialize {
                override fun onSuccess() = callback {
                    attempt("STT_SETUP") {
                        stt.addListener(sttListener); stt.setLocale(Locale.KOREA)
                        sttReady = true; event("STT_INITIALIZED"); auto()
                    }
                }
                override fun onFailure(errorType: SttError) = callback { event("STT_INIT_ERROR", errorType.name) }
            })
            handler.postDelayed({
                if (!closed) {
                    if (!ttsReady) event("TTS_NOT_READY_AFTER_10S")
                    if (!sttReady) event("STT_NOT_READY_AFTER_10S")
                }
            }, 10_000)
        }
    }

    // Automation accepts playback or a synthetic file only. Microphone requires an on-screen action.
    private fun auto() {
        if (autoRun) return
        when (intent.getStringExtra("probe")) {
            "tts" -> if (ttsReady) { autoRun = true; speak(false) }
            "pcm" -> if (sttReady) { autoRun = true; sendPcm() }
        }
    }

    private fun begin(kind: String): Boolean {
        if (operation !in listOf("IDLE", "ERROR")) { event("BUSY", operation); return false }
        operation = kind
        finalTextReceived = false
        val token = ++generation
        event("${kind}_REQUESTED")
        handler.postDelayed({
            if (!closed && generation == token) {
                event("PROBE_TIMEOUT", "$kind / 진단 시간 초과; 탑승자 무응답 판정 아님")
                stop("TIMEOUT_STOP")
            }
        }, 15_000)
        return true
    }

    private fun speak(listenAfter: Boolean) {
        if (!ttsReady || (listenAfter && !sttReady)) { event("NOT_READY", "TTS=$ttsReady STT=$sttReady"); return }
        if (!begin("TTS")) return
        chainMicrophone = listenAfter
        playbackStarted = false
        attempt("TTS_SPEAK") { tts.speak("음성 기능 시험입니다. 제 말이 들리면 들려요라고 답해 주세요.") }
    }

    private fun startMicrophone() {
        if (!sttReady) { event("NOT_READY", "STT"); return }
        if (begin("MICROPHONE")) attempt("STT_REQUEST") { stt.request() }
    }

    private fun sendPcm() {
        if (!sttReady) { event("NOT_READY", "STT"); return }
        val pcm = File(filesDir, "response-test.pcm")
        if (!pcm.exists() || pcm.length() !in 2..480_000 || pcm.length() % 2L != 0L) {
            event("PCM_MISSING_OR_INVALID", "response-test.pcm: 16kHz / s16le / mono / 최대 15초"); return
        }
        if (!begin("PCM")) return
        event("SYNTHETIC_PCM", "${pcm.length()} bytes; 마이크·실제 발화 검증 제외")
        attempt("STT_PCM") { stt.sendAudio(pcm.readBytes()); stt.completeAudioSend() }
    }

    private fun fail(kind: String, detail: String) {
        event(kind, detail)
        stop("SDK_ERROR_STOP")
        operation = "ERROR"
    }

    private fun stop(reason: String) {
        generation++
        chainMicrophone = false
        operation = "IDLE"
        if (::tts.isInitialized) runCatching { tts.stop() }
        if (::stt.isInitialized) runCatching { stt.stop() }
        event(reason)
    }

    override fun onStop() {
        if (::output.isInitialized && !closed) {
            closed = true
            stop("ACTIVITY_STOP")
            handler.removeCallbacksAndMessages(null)
            if (::tts.isInitialized) runCatching { tts.removeEventListener(ttsListener); tts.release() }
            if (::stt.isInitialized) runCatching { stt.removeListener(sttListener); stt.release() }
            finish()
        }
        super.onStop()
    }
}
