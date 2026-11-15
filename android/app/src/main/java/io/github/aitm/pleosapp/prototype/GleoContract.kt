package io.github.aitm.pleosapp.prototype

object GleoContract {
    private val functions = setOf("START_ROADSIDE_SUPPORT", "REPORT_OCCUPANT_STATUS", "GET_ROADSIDE_STATUS")

    // Gleo 4.2 sends <packageName>.<schema function name>. Accept legacy local
    // ADB actions too, but never interpret another app's prefix as our action.
    fun functionName(action: String?, packageName: String): String? {
        val name = action?.removePrefix("$packageName.") ?: return null
        return name.takeIf { it in functions }
    }
}
