package com.stealth.browser.engine.privacy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Real JavaScript injected as a document-start script (via WebViewCompat.addDocumentStartJavaScript)
 * so every patch executes BEFORE any page script runs.
 *
 * Design rule (PRD §8): consistent privacy configuration, NOT random parameter flashing. Random
 * values break websites and become their own fingerprint vector. Each protected read returns a
 * stable, coherent value derived from a per-session fingerprint payload.
 */
object FingerprintScripts {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /**
     * Coherent per-session fingerprint values. Stable for the session's lifetime;
     * regenerated per session, never per page load.
     */
    @Serializable
    data class FingerprintPayload(
        val canvasNoise: Int,
        val canvasNoiseOffset: Int,
        val audioNoise: Double,
        val audioNoiseOffset: Int = 12,
        val screen: ScreenValues,
        val hardware: HardwareValues,
        val timezone: String?,
        val timezoneOffsetMinutes: Int = 0,
        val languages: List<String>,
        val fontStack: List<String> = listOf("sans-serif", "serif", "monospace"),
        val webglVendor: String,
        val webglRenderer: String,
        val clientHints: ClientHintsValues,
    ) {
        @Serializable
        data class ScreenValues(
            val width: Int,
            val height: Int,
            val chromeHeight: Int = 48,
            val colorDepth: Int,
            val devicePixelRatio: Float,
        )

        @Serializable
        data class HardwareValues(
            val hardwareConcurrency: Int,
            val deviceMemory: Int,
        )

        @Serializable
        data class ClientHintsValues(
            val platform: String = "android",
            val mobile: Boolean = true,
            val platformVersion: String = "15.0.0",
            val model: String = "",
        )
    }

    /** Deterministic noise derived per session — see docs/ENGINE_CAPABILITIES.md. */
    fun newSessionPayload(
        screenWidth: Int,
        screenHeight: Int,
        colorDepth: Int,
        devicePixelRatio: Float,
        hardwareConcurrency: Int,
        deviceMemory: Int,
        timezone: String?,
        timezoneOffsetMinutes: Int,
        locale: String,
        webglVendor: String = "GPU vendor (protected)",
        webglRenderer: String = "GPU renderer (protected)",
    ): FingerprintPayload {
        val seeded = kotlin.random.Random(System.currentTimeMillis() xor SessionSeed)
        return FingerprintPayload(
            canvasNoise = seeded.nextInt(6, 16),
            canvasNoiseOffset = seeded.nextInt(0, 64),
            audioNoise = 1.0e-7 + seeded.nextDouble(1.0e-9),
            screen = FingerprintPayload.ScreenValues(screenWidth, screenHeight, colorDepth = colorDepth, devicePixelRatio = devicePixelRatio),
            hardware = FingerprintPayload.HardwareValues(hardwareConcurrency, deviceMemory),
            timezone = timezone,
            timezoneOffsetMinutes = timezoneOffsetMinutes,
            languages = listOf(locale),
            webglVendor = webglVendor,
            webglRenderer = webglRenderer,
            clientHints = FingerprintPayload.ClientHintsValues(),
        )
    }

    fun encodePayload(payload: FingerprintPayload): String = json.encodeToString(payload)

    fun encodeConfig(config: com.stealth.browser.engine.core.PrivacyConfig): String = Json.encodeToString(
        ConfigJson(
            c = config.canvasProtection,
            w = config.webglPrivacy,
            a = config.audioContextPrivacy,
            g = config.webgpuPrivacy,
            f = config.fontEnumerationPrivacy,
            s = config.screenInfoPrivacy,
            h = config.hardwareInfoPrivacy,
            u = config.userAgentOverride,
            ch = config.clientHintsPrivacy,
            t = config.timezonePrivacy,
            l = config.localePrivacy,
        ),
    )

    @kotlinx.serialization.Serializable
    private data class ConfigJson(
        val c: Boolean,
        val w: Boolean,
        val a: Boolean,
        val g: Boolean,
        val f: Boolean,
        val s: Boolean,
        val h: Boolean,
        val u: String?,
        val ch: Boolean,
        val t: Boolean,
        val l: Boolean,
    )

    private const val SessionSeed = 0x57_41_LL

    fun buildScript(configJson: String, fingerprintJson: String): String = """
(function() {
    'use strict';
    if (window.__stealth_active) return;
    window.__stealth_active = true;
    var CONFIG = $configJson;
    var FP = $fingerprintJson;

    // --- Canvas ---
    if (CONFIG.c) {
        var origGetImageData = CanvasRenderingContext2D.prototype.getImageData;
        CanvasRenderingContext2D.prototype.getImageData = function(sx, sy, sw, sh) {
            var img = origGetImageData.call(this, sx, sy, sw, sh);
            var d = img.data;
            for (var i = Math.floor(FP.canvasNoiseOffset % 4); i < d.length; i += 64) {
                if (d[i] < 15) { d[i] = FP.canvasNoise; }
                else { d[i] = d[i] + FP.canvasNoise - 8; }
            }
            return img;
        };
        var origToDataURL = HTMLCanvasElement.prototype.toDataURL;
        HTMLCanvasElement.prototype.toDataURL = function() {
            if (this.width === 0 || this.height === 0) return origToDataURL.apply(this, arguments);
            var ctx = this.getContext('2d');
            if (ctx) {
                ctx.save();
                ctx.fillStyle = 'rgba(' + FP.canvasNoise + ',' + FP.canvasNoiseOffset + ',7,0.02)';
                ctx.fillRect(0, this.height - 1, this.width, 1);
                ctx.restore();
            }
            return origToDataURL.apply(this, arguments);
        };
    }

    // --- WebGL ---
    if (CONFIG.w) {
        var patch = function(proto) {
            if (!proto) return;
            var orig = proto.getParameter;
            proto.getParameter = function(p) {
                if (p === 37445) return FP.webglVendor;   // UNMASKED_VENDOR_WEBGL
                if (p === 37446) return FP.webglRenderer; // UNMASKED_RENDERER_WEBGL
                return orig.call(this, p);
            };
        };
        patch(window.WebGLRenderingContext ? WebGLRenderingContext.prototype : null);
        patch(window.WebGL2RenderingContext ? WebGL2RenderingContext.prototype : null);
    }

    // --- AudioContext ---
    if (CONFIG.a) {
        var origGetChannelData = AudioBuffer.prototype.getChannelData;
        AudioBuffer.prototype.getChannelData = function(channel) {
            var data = origGetChannelData.call(this, channel);
            for (var j = FP.audioNoiseOffset; j < data.length; j += 1234) {
                data[j] = data[j] + FP.audioNoise;
            }
            return data;
        };
        var origGetFFD = AnalyserNode.prototype.getFloatFrequencyData;
        AnalyserNode.prototype.getFloatFrequencyData = function(array) {
            origGetFFD.call(this, array);
            if (array.length > FP.audioNoiseOffset) { array[FP.audioNoiseOffset] += FP.audioNoise; }
        };
    }

    // --- WebGPU ---
    if (CONFIG.g && navigator.gpu) {
        navigator.gpu.requestAdapter = function() {
            return Promise.reject(new DOMException('WebGPU unavailable (privacy policy)', 'NotAllowedError'));
        };
    }

    // --- Font enumeration ---
    if (CONFIG.f && document.fonts && FontFaceSet.prototype.check) {
        var origCheck = FontFaceSet.prototype.check;
        FontFaceSet.prototype.check = function(font, text) {
            // Only system fallback families report as installed.
            for (var k = 0; k < FP.fontStack.length; k++) {
                if (font && font.indexOf(FP.fontStack[k]) !== -1) return origCheck.call(this, font, text);
            }
            return false;
        };
    }

    // --- Screen information ---
    if (CONFIG.s) {
        Object.defineProperty(screen, 'width',        { get: function() { return FP.screen.width; },  configurable: false });
        Object.defineProperty(screen, 'height',       { get: function() { return FP.screen.height; }, configurable: false });
        Object.defineProperty(screen, 'availWidth',   { get: function() { return FP.screen.width; },  configurable: false });
        Object.defineProperty(screen, 'availHeight',  { get: function() { return FP.screen.height - FP.screen.chromeHeight; }, configurable: false });
        Object.defineProperty(screen, 'colorDepth',   { get: function() { return FP.screen.colorDepth; }, configurable: false });
        Object.defineProperty(screen, 'pixelDepth',   { get: function() { return FP.screen.colorDepth; }, configurable: false });
        window.devicePixelRatio = FP.screen.devicePixelRatio;
    }

    // --- Hardware information ---
    if (CONFIG.h) {
        Object.defineProperty(navigator, 'hardwareConcurrency', {
            get: function() { return FP.hardware.hardwareConcurrency; }, configurable: false
        });
        // deviceMemory is only masked when the platform exposes the API; never spoof a non-existent API.
        if (navigator.deviceMemory !== undefined) {
            Object.defineProperty(navigator, 'deviceMemory', {
                get: function() { return FP.hardware.deviceMemory; }, configurable: false
            });
        }
    }

    // --- Client Hints ---
    if (CONFIG.ch && navigator.userAgentData && navigator.userAgentData.getHighEntropyValues) {
        navigator.userAgentData.getHighEntropyValues = function(hints) {
            return Promise.resolve({
                platform: FP.clientHints.platform,
                mobile: FP.clientHints.mobile,
                platformVersion: FP.clientHints.platformVersion,
                architecture: 'arm',
                bitness: '64',
                model: FP.clientHints.model
            });
        };
    }

    // --- User agent ---
    if (CONFIG.u) {
        Object.defineProperty(navigator, 'userAgent', {
            get: function() { return CONFIG.u; }, configurable: false
        });
    }

    // --- Timezone ---
    if (CONFIG.t && FP.timezoneOffsetMinutes !== null) {
        Date.prototype.getTimezoneOffset = function() { return FP.timezoneOffsetMinutes; };
        if (FP.timezone) {
            var OrigDTF = Intl.DateTimeFormat;
            window.Intl.DateTimeFormat = function(locales, options) {
                options = options || {};
                if (!options.timeZone) options.timeZone = FP.timezone;
                return new OrigDTF(locales, options);
            };
            window.Intl.DateTimeFormat.prototype = OrigDTF.prototype;
            window.Intl.DateTimeFormat.supportedLocalesOf = OrigDTF.supportedLocalesOf;
        }
    }

    // --- Locale ---
    if (CONFIG.l && FP.languages && FP.languages.length) {
        Object.defineProperty(navigator, 'languages', {
            get: function() { return FP.languages; }, configurable: false
        });
    }
})();
""".trimIndent()
}
