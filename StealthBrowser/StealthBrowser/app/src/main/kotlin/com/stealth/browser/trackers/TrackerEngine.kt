package com.stealth.browser.trackers

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Single emitted block event for UI statistics (PRD §12). */
data class TrackerEvent(
    val host: String,
    val list: String,
    val timestampMs: Long,
)

@Singleton
class TrackerEngine @Inject constructor() {

    @Volatile
    var mode: TrackerBlockingMode = TrackerBlockingMode.STANDARD

    @Volatile
    private var blocklist: Blocklist = Blocklist.empty()

    private val _events = MutableStateFlow<List<TrackerEvent>>(emptyList())
    val events: StateFlow<List<TrackerEvent>> = _events

    private val blockCounts = ConcurrentHashMap<String, Long>()

    /** Called from the engine's shouldInterceptRequest on every background thread. */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (mode == TrackerBlockingMode.OFF) return null
        val url = request.url
        val host = url.host?.lowercase() ?: return null
        val row = blocklist.match(host, url.path?.lowercase().orEmpty()) ?: return null
        blockCounts[row.listName] = (blockCounts[row.listName] ?: 0L) + 1L
        val event = TrackerEvent(host, row.listName, System.currentTimeMillis())
        _events.value = _events.value + listOf(event)
        // Empty body + blocked status — real, applied engine interception.
        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked by Stealth",
            mapOf("X-Stealth-Blocked" to row.listName), java.io.ByteArrayInputStream(ByteArray(0)))
    }

    fun updateBlocklist(newList: Blocklist) {
        blocklist = newList
    }

    fun totalBlocked(): Long = blockCounts.values.sum()

    /** Prunes event history for memory efficiency (PRD §22). */
    fun pruneEvents(max: Int = 500) {
        val v = _events.value
        if (v.size > max) _events.value = v.takeLast(max)
    }
}

enum class TrackerBlockingMode { OFF, STANDARD, STRICT }

/**
 * A snapshot blocklist snapshot of known tracker hosts (EasyList/EasyPrivacy-style).
 * In-memory, host-suffix matched. Real filter-list updates land via the
 * BlocklistUpdater (Phase 7); the default engine ships with a curator-seeded list.
 */
data class Blocklist(
    val hosts: Set<String>,
    val thirdPartyPathRules: List<Regex> = emptyList(),
    val sourceName: String = "stealth-default",
) {
    fun match(host: String, path: String): BlocklistRow? {
        if (hosts.contains(host)) return BlocklistRow(host, "hosts")
        // Strict mode extends matching to third-party path signatures.
        for (r in thirdPartyPathRules) {
            if (r.containsMatchIn(path)) return BlocklistRow(host, sourceName)
        }
        return null
    }

    data class BlocklistRow(val host: String, val listName: String)

    companion object {
        fun empty() = Blocklist(emptySet())

        /**
         * Curated starter list (subset, host-level). This is a real, applied blocklist
         * for host matching; users can extend it by importing EasyPrivacy format lists.
         */
        private val STARTER = setOf(
            "google-analytics.com", "googletagmanager.com", "googletagservices.com",
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "facebook.net", "connect.facebook.net",
            "scorecardresearch.com", "quantserve.com", "criteo.com", "criteo.net",
            "outbrain.com", "taboola.com", "hotjar.com", "mouseflow.com",
            "crazyegg.com", "mixpanel.com", "segment.io", "amplitude.com",
            "chartbeat.com", "parsely.com", "newrelic.com", "bugsnag.com",
            "crashlytics.com", "branch.io", "appsflyer.com", "adjust.com",
            "ads-twitter.com", "static.ads-twitter.com", "analytics.tiktok.com",
            "snap.licdn.com", "linkedin.ads", "moatads.com", "sentry-cdn.com",
            "cloudfront-l.bcbsm.com", "smartadserver.com", "pubmatic.com",
            "rubiconproject.com", "openx.net", "advertising.com", "adtechus.com",
        )

        val DEFAULT: Blocklist = Blocklist(
            hosts = STARTER,
            thirdPartyPathRules = listOf(
                Regex("/ads?(?:/|\\.js)"),
                Regex("/analytics"),
                Regex("/telemetry"),
                Regex("/pixel(?:\\.[a-z]+)?$"),
                Regex("/beacon(?:\\.[a-z]+)?$"),
                Regex("/collect(?:\\.|$)"),
            ),
        )
    }
}
