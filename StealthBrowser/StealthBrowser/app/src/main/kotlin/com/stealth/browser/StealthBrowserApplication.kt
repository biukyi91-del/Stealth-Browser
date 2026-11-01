package com.stealth.browser

import android.app.Application
import com.stealth.browser.trackers.Blocklist
import com.stealth.browser.trackers.TrackerEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class StealthBrowserApplication : Application() {

    @Inject
    lateinit var trackerEngine: TrackerEngine

    @Inject
    lateinit var engineEventCoordinator: com.stealth.browser.session.EngineEventCoordinator

    override fun onCreate() {
        super.onCreate()
        // Seed the request filter with the real default blocklist (Phase 7 material).
        trackerEngine.updateBlocklist(Blocklist.DEFAULT)
        // Start engine event fan-out (downloads, proxy failure kill switch).
        engineEventCoordinator.start()
    }
}
