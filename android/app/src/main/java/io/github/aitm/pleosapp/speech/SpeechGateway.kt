package io.github.aitm.pleosapp.speech

enum class VoicePhase { IDLE, PREPARING, SPEAKING, LISTENING, COMPLETE, UNAVAILABLE, PAUSED }
sealed interface VoiceResult {
    data class Text(val text: String) : VoiceResult
    data class Failed(val detail: String) : VoiceResult
    data object NoSpeech : VoiceResult
    data object Spoken : VoiceResult
}
interface SpeechGateway {
    val available: Boolean
    suspend fun ask(prompt: String, phase: (VoicePhase) -> Unit, testPcm: ByteArray? = null): VoiceResult
    suspend fun say(prompt: String, phase: (VoicePhase) -> Unit): VoiceResult = VoiceResult.Failed("음성 안내 미지원")
}
class UnavailableSpeechGateway : SpeechGateway {
    override val available = false
    override suspend fun ask(prompt: String, phase: (VoicePhase) -> Unit, testPcm: ByteArray?) =
        VoiceResult.Failed("이 빌드에는 음성 서비스가 연결되어 있지 않습니다.")
}
