package com.stealth.browser.extensions.manifest

import com.stealth.browser.extensions.model.ExtensionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityCheckerTest {

    @Test
    fun mv3WithSupportedPermissionsIsFullyCompatible() {
        val manifest = ManifestParser.parse(
            """{"manifest_version": 3, "name": "ok", "version": "1.0",
                "permissions": ["storage", "tabs"]}""",
        ) as ManifestParseResult.Success
        val report = CompatibilityChecker.analyze(manifest.manifest)
        assertEquals(ExtensionStatus.FULLY_COMPATIBLE, report.result)
        assertTrue("storage" in report.supportedApis)
    }

    @Test
    fun requiredUnsupportedPermissionDowngradesStatus() {
        val manifest = ManifestParser.parse(
            """{"manifest_version": 3, "name": "needs downloads API", "version": "1.0",
                "permissions": ["storage", "downloads"]}""",
        ) as ManifestParseResult.Success
        val report = CompatibilityChecker.analyze(manifest.manifest)
        assertEquals(ExtensionStatus.PARTIALLY_COMPATIBLE, report.result)
        assertTrue(report.unsupportedApis.contains("downloads"))
    }

    @Test
    fun blockingWebRequestIsFlagged() {
        val manifest = ManifestParser.parse(
            """{"manifest_version": 2, "name": "old blocker", "version": "1.0",
                "permissions": ["webRequest", "webRequestBlocking", "storage"]}""",
        ) as ManifestParseResult.Success
        val report = CompatibilityChecker.analyze(manifest.manifest)
        assertTrue(report.blockedReasons.any { it.contains("Blocking WebRequest") })
    }
}
