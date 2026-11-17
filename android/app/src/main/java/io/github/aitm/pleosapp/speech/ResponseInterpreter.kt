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
        val cannotMove = listOf("움직일수없", "움직일수가없", "못움직", "움직이지못", "일어날수없", "일어날수가없").any(t::contains)
        val negatedRequest = listOf("도와주지마", "도와주지말", "도와주지않", "살려주지마", "살려주지말", "살려주지않",
            "도와줄필요없", "도와줄필요가없", "도와줄필요는없", "도와주실필요없", "도와주실필요가없", "도와주실필요는없").any(t::contains)
        // Korean contractions (줘) and uncontracted requests (주세요) are distinct spellings.
        val requestedHelp = !negatedRequest && listOf("도와줘", "도와주", "살려줘", "살려주").any(t::contains)
        val statedNeed = listOf("도움이필요", "도움필요", "도움이좀필요", "도움좀필요").any(t::contains) &&
            listOf("필요없", "필요는없", "필요가없", "필요하지않", "필요하지는않", "필요하지도않", "필요치않", "필요한건아니", "필요한것은아니").none(t::contains)
        // Match the help action's direct statement, without expanding to uncertain/negated forms.
        val directDifficulty = t in setOf("움직이기어려워요", "움직이기힘들어요")
        val need = cannotMove || requestedHelp || statedNeed || directDifficulty
        val uncertain = listOf("없", "않", "못", "아니", "모르", "겠", "같", "힘들", "어렵").any(t::contains)
        val canMove = !uncertain && listOf("움직일수있어요", "움직일수있습니다", "움직일수있어", "걸을수있어요", "걸을수있습니다").any(t::contains)
        val help = when { need -> HelpState.NEEDS_HELP; canMove -> HelpState.CAN_MOVE; else -> HelpState.UNKNOWN }
        val ack = who != Responder.UNKNOWN || help != HelpState.UNKNOWN ||
            t in setOf("네", "예", "들려요", "네들려요", "네잘들려요", "잘들려요", "들립니다", "네들립니다")
        return HeardResponse(ack, who, help)
    }
}
