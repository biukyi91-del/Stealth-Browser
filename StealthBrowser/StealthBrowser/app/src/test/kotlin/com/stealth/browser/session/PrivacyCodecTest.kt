package com.stealth.browser.session

import com.stealth.browser.engine.core.PrivacyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyCodecRoundTripTest {

    @Test
    fun roundTripPreservesAllFlags() {
        val config = PrivacyConfig(
            canvasProtection = true,
            webglPrivacy = true,
            audioContextPrivacy = true,
            webgpuPrivacy = true,
            fontEnumerationPrivacy = true,
            screenInfoPrivacy = true,
            hardwareInfoPrivacy = true,
            clientHintsPrivacy = true,
            timezonePrivacy = true,
            localePrivacy = true,
            userAgentOverride = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        )
        assertEquals(config, PrivacyCodec.decode(PrivacyCodec.encode(config)))
    }

    @Test
    fun nullAndBlankDecodeToNull() {
        assertNull(PrivacyCodec.decode(null))
        assertNull(PrivacyCodec.decode(""))
        assertNull(PrivacyCodec.decode("   "))
    }

    @Test
    fun corruptedPayloadFallsBackToNullNotCrash() {
        assertNull(PrivacyCodec.decode("{\"canvas\": true, \"broken"))
    }

    @Test
    fun offConfigRoundTripsToAllFalse() {
        val decoded = PrivacyCodec.decode(PrivacyCodec.encode(PrivacyConfig.OFF))!!
        assertFalse(decoded.canvasProtection)
        assertFalse(decoded.webglPrivacy)
        assertTrue(decoded == PrivacyConfig.OFF)
    }
}
