package com.stealth.browser.extensions.manifest

import com.stealth.browser.extensions.model.ExtensionManifest
import kotlinx.serialization.json.Json

sealed interface ManifestParseResult {
    data class Success(val manifest: ExtensionManifest) : ManifestParseResult
    data class Failure(val reason: String) : ManifestParseResult
}

/**
 * Parses and validates extension manifest.json strictly:
 *  - JSON must be valid UTF-8; unknown keys are IGNORED (Chromium behavior).
 *  - Required keys: manifest_version, name, version.
 *  - manifest_version must be 2 or 3.
 *  - Rejects oversized names/descriptions and malformed version strings,
 *    keeping installation from executing untrusted content (PRD §13.1/13.9).
 */
object ManifestParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = false
    }

    fun parse(raw: String): ManifestParseResult {
        if (raw.length > MAX_MANIFEST_BYTES) {
            return ManifestParseResult.Failure("manifest.json too large (>1 MiB)")
        }
        val manifest = runCatching { json.decodeFromString<ExtensionManifest>(raw) }
            .getOrElse { return ManifestParseResult.Failure("manifest.json is not valid JSON: ${it.message}") }

        if (manifest.manifestVersion !in VALID_VERSIONS) {
            return ManifestParseResult.Failure("Unsupported manifest_version: ${manifest.manifestVersion}")
        }
        if (manifest.name.isBlank() || manifest.name.length > 100) {
            return ManifestParseResult.Failure("name is required (max 100 chars)")
        }
        if (!VERSION_PATTERN.matches(manifest.version)) {
            return ManifestParseResult.Failure("version must be dotted integers, e.g. 1.2.3")
        }
        manifest.description?.let {
            if (it.length > 500) return ManifestParseResult.Failure("description too long")
        }
        if (manifest.manifestVersion == 3 && manifest.permissions.any { it.startsWith("experimental") }) {
            // Unsupported flag must fail validation loudly, not silently install.
            return ManifestParseResult.Failure("experimental permissions are unsupported on this platform")
        }
        return ManifestParseResult.Success(manifest)
    }

    companion object {
        const val MAX_MANIFEST_BYTES = 1 shl 20
        private val VALID_VERSIONS = 2..3
        private val VERSION_PATTERN = Regex("""^\d+(\.\d+){0,3}${'$'}""")
    }
}
