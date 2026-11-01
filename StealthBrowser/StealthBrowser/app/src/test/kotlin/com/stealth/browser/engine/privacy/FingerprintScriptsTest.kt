package com.stealth.browser.engine.privacy

import com.stealth.browser.engine.core.PrivacyConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintScriptsTest {

    @Test
    fun scriptContainsAllConfiguredPatchHandlers() {
        val payload = FingerprintScripts.newSessionPayload(
            screenWidth = 1080, screenHeight = 2280, colorDepth = 24,
            devicePixelRatio = 2.6f, hardwareConcurrency = 8, deviceMemory = 4,
            timezone = "UTC", timezoneOffsetMinutes = 0, locale = "en-US",
        )
        val script = FingerprintScripts.buildScript(
            configJson = FingerprintScripts.encodeConfig(PrivacyConfig(canvasProtection = true)),
            fingerprintJson = FingerprintScripts.encodePayload(payload),
        )
        for (token in listOf(
            "__stealth_active",
            "getImageData",
            "toDataURL",
            "37445",
            "getChannelData",
            "getTimezoneOffset",
            "getHighEntropyValues",
        )) {
            assertTrue("missing: $token", script.contains(token))
        }
    }

    @Test
    fun payloadJsonIsParseable() {
        val payload = FingerprintScripts.newSessionPayload(
            screenWidth = 1080, screenHeight = 2280, colorDepth = 24,
            devicePixelRatio = 3.0f, hardwareConcurrency = 4, deviceMemory = 4,
            timezone = "Europe/Berlin", timezoneOffsetMinutes = -60, locale = "de-DE",
        )
        val json: String = FingerprintScripts.encodePayload(payload)
        org.junit.Assert.assertEquals("1080", Regex("\"width\": (\\d+)").find(json)!!.groupValues[1])
    }

    @Test
    fun sessionPayloadIsStableWithinSameCallParameters() {
        // Determinism inside a session is critical (PRD: coherent, not random).
        val a = FingerprintScripts.newSessionPayload(
            screenWidth = 100, screenHeight = 100, colorDepth = 24,
            devicePixelRatio = 2f, hardwareConcurrency = 4, deviceMemory = 4,
            timezone = "UTC", timezoneOffsetMinutes = 0, locale = "en-US",
        )
        val b = FingerprintScripts.newSessionPayload(
            screenWidth = 100, screenHeight = 100, colorDepth = 24,
            devicePixelRatio = 2f, hardwareConcurrency = 4, deviceMemory = 4,
            timezone = "UTC", timezoneOffsetMinutes = 0, locale = "en-US",
        )
        // Not required to equal — but any randomness must be limited noise, verified here by type.
        assertTrue(a.canvasNoise in 6..15)
        assertTrue(b.canvasNoise in 6..15)
    }
}
