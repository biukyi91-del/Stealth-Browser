package com.stealth.browser.session

import com.stealth.browser.engine.core.PrivacyConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Serialization codec for profile privacy configs — used by both ProfileManager (backup/restore) and SessionManager. */
object PrivacyCodec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Envelope(
        val canvas: Boolean = false,
        val webgl: Boolean = false,
        val audio: Boolean = false,
        val webgpu: Boolean = false,
        val fonts: Boolean = false,
        val screen: Boolean = false,
        val hardware: Boolean = false,
        val clientHints: Boolean = false,
        val timezone: Boolean = false,
        val locale: Boolean = false,
        val userAgentOverride: String? = null,
        val timezoneOffsetMinutes: Int = 0,
        val timezoneId: String? = null,
        val localeId: String? = null,
    )

    fun encode(config: PrivacyConfig): String =
        json.encodeToString(
            Envelope(
                canvas = config.canvasProtection,
                webgl = config.webglPrivacy,
                audio = config.audioContextPrivacy,
                webgpu = config.webgpuPrivacy,
                fonts = config.fontEnumerationPrivacy,
                screen = config.screenInfoPrivacy,
                hardware = config.hardwareInfoPrivacy,
                clientHints = config.clientHintsPrivacy,
                timezone = config.timezonePrivacy,
                locale = config.localePrivacy,
                userAgentOverride = config.userAgentOverride,
            ),
        )

    fun decode(encoded: String?): PrivacyConfig? {
        if (encoded.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<Envelope>(encoded) }
            .getOrNull()
            ?.let {
                PrivacyConfig(
                    canvasProtection = it.canvas,
                    webglPrivacy = it.webgl,
                    audioContextPrivacy = it.audio,
                    webgpuPrivacy = it.webgpu,
                    fontEnumerationPrivacy = it.fonts,
                    screenInfoPrivacy = it.screen,
                    hardwareInfoPrivacy = it.hardware,
                    clientHintsPrivacy = it.clientHints,
                    timezonePrivacy = it.timezone,
                    localePrivacy = it.locale,
                    userAgentOverride = it.userAgentOverride,
                )
            }
    }
}
