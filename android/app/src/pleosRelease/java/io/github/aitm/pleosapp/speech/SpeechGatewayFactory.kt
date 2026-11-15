package io.github.aitm.pleosapp.speech
import android.content.Context
object SpeechGatewayFactory {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): SpeechGateway = UnavailableSpeechGateway()
}
