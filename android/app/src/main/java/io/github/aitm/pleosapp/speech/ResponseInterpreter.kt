package io.github.aitm.pleosapp.speech

import io.github.aitm.pleosapp.prototype.HelpState
import io.github.aitm.pleosapp.prototype.Responder

data class HeardResponse(val acknowledged: Boolean, val responder: Responder = Responder.UNKNOWN, val help: HelpState = HelpState.UNKNOWN)

/** Deliberately narrow prototype grammar. Unrecognized or negated language never means "can move". */
object ResponseInterpreter {
    fun interpret(text: String): HeardResponse {
        val t = text.replace(Regex("[\\s.,!?。]"), "")
        val who = when {
            listOf("동승자예요", "동승자에요", "동승자입니다", "동승자야").any(t::contains) -> Responder.PASSENGER
            listOf("운전자예요", "운전자에요", "운전자입니다", "운전자야").any(t::contains) -> Responder.DRIVER
            else -> Responder.UNKNOWN
        }
        val need = listOf("움직일수없", "못움직", "움직이지못", "도와주", "살려주", "일어날수없").any(t::contains) ||
            ("도움이필요" in t && "필요없" !in t && "필요하지않" !in t)
        val uncertain = listOf("없", "않", "못", "아니", "모르", "겠", "같", "힘들", "어렵").any(t::contains)
        val canMove = !uncertain && listOf("움직일수있어요", "움직일수있습니다", "움직일수있어", "걸을수있어요", "걸을수있습니다").any(t::contains)
        val help = when { need -> HelpState.NEEDS_HELP; canMove -> HelpState.CAN_MOVE; else -> HelpState.UNKNOWN }
        val ack = who != Responder.UNKNOWN || help != HelpState.UNKNOWN ||
            t in setOf("네", "예", "들려요", "네들려요", "네잘들려요", "잘들려요", "들립니다", "네들립니다")
        return HeardResponse(ack, who, help)
    }
}
