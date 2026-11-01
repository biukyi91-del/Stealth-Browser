package com.stealth.browser.session

import com.stealth.browser.engine.core.PrivacyConfig

/**
 * A "BrowserOwner" is the isolation unit: every tab belongs to exactly one owner,
 * and every owner has its own engine data directory, cookies, cache, site storage,
 * permissions, history, and extension availability (PRD §3, §7).
 */
sealed interface BrowserOwner {
    val ownerId: String
    val isTemporary: Boolean
}

data class PermanentProfileOwner(
    val profileId: String,
    val privacyConfig: PrivacyConfig?,
) : BrowserOwner {
    override val ownerId: String get() = profileId
    override val isTemporary: Boolean get() = false
}

data class TemporarySessionOwner(
    val sessionInstanceId: String,
    val sourceProfileId: String?,
    val privacyConfig: PrivacyConfig,
    val approvedExtensionIds: Set<String>,
    /** None = start with all extensions disabled (PRD §6 option A). */
    val workspaceName: String = "Stealth Session",
) : BrowserOwner {
    override val ownerId: String get() = sessionInstanceId
    override val isTemporary: Boolean get() = true
}
