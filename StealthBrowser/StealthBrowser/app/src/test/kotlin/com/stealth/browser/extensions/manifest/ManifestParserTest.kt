package com.stealth.browser.extensions.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestParserTest {

    private val validV3 = """
        {
          "manifest_version": 3,
          "name": "Ad Guard Test",
          "version": "1.2.3",
          "description": "blocks tracker requests",
          "permissions": ["storage", "declarativeNetRequest"],
          "background": { "service_worker": "sw.js" },
          "action": { "default_popup": "popup.html" }
        }
    """.trimIndent()

    @Test
    fun parsesValidV3Manifest() {
        val result = ManifestParser.parse(validV3)
        assertTrue(result is ManifestParseResult.Success)
        val manifest = (result as ManifestParseResult.Success).manifest
        assertEquals(3, manifest.manifestVersion)
        assertEquals("1.2.3", manifest.version)
        assertEquals("sw.js", manifest.background?.serviceWorker)
    }

    @Test
    fun parsesValidV2Manifest() {
        val raw = """{"manifest_version": 2, "name": "Legacy", "version": "1.0",
                      "background": { "scripts": ["bg.js"], "persistent": false }}"""
        val result = ManifestParser.parse(raw)
        assertTrue(result is ManifestParseResult.Success)
        assertEquals(2, (result as ManifestParseResult.Success).manifest.manifestVersion)
    }

    @Test
    fun rejectsInvalidJson() {
        val result = ManifestParser.parse("{ not json }")
        assertTrue(result is ManifestParseResult.Failure)
        assertTrue((result as ManifestParseResult.Failure).reason.contains("valid JSON"))
    }

    @Test
    fun rejectsUnknownManifestVersion() {
        val raw = """{"manifest_version": 4, "name": "X", "version": "1.0"}"""
        val result = ManifestParser.parse(raw)
        assertTrue(result is ManifestParseResult.Failure)
    }

    @Test
    fun rejectsMalformedVersion() {
        val raw = """{"manifest_version": 3, "name": "X", "version": "1.0.beta"}"""
        val result = ManifestParser.parse(raw)
        assertTrue(result is ManifestParseResult.Failure)
    }

    @Test
    fun rejectsExperimentalPermissions() {
        val raw = """{"manifest_version": 3, "name": "X", "version": "1.0",
                      "permissions": ["experimental.newTab"]}"""
        val result = ManifestParser.parse(raw)
        assertTrue(result is ManifestParseResult.Failure)
        assertTrue((result as ManifestParseResult.Failure).reason.contains("experimental"))
    }
}
