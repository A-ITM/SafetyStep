package io.github.aitm.pleosapp.accident

import org.json.JSONObject

internal fun accidentFixture(): JSONObject = requireNotNull(
    AccidentEvent::class.java.classLoader?.getResourceAsStream("accident-v07.json"),
) { "Shared accident-v07.json contract fixture is missing from test resources" }
    .bufferedReader().use { JSONObject(it.readText()) }
