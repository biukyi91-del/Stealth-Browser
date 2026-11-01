package com.stealth.browser.trackers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistTest {

    @Test
    fun blocksKnownTrackerHost() {
        val row = Blocklist.DEFAULT.match("www.google-analytics.com", "/collect")
        assertNotNull(row)
    }

    @Test
    fun allowsFirstParty() {
        assertNull(Blocklist.DEFAULT.match("example.org", "/about"))
    }

    @Test
    fun strictRulesBlockAdPaths() {
        val row = Blocklist.DEFAULT.match("unknown-cdn.example.net", "/ads/banner.js")
        assertNotNull(row)
    }

    @Test
    fun emptyListBlocksNothing() {
        val empty = Blocklist.empty()
        assertNull(empty.match("google-analytics.com", "/collect"))
        assertFalse(empty.hosts.isNotEmpty())
    }

    @Test
    fun hostMatchingIsCaseInsensitive() {
        assertTrue(
            Blocklist.DEFAULT.match("DOUBLECLICK.NET", "/")
                ?.listName != null,
        )
    }
}
