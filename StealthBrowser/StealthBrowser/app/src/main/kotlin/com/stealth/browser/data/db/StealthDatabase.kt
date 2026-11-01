package com.stealth.browser.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        ProfileEntity::class,
        TabEntity::class,
        BookmarkEntity::class,
        HistoryEntity::class,
        ExtensionEntity::class,
        ExtensionProfileStateEntity::class,
        ProxyConfigEntity::class,
        SitePermissionEntity::class,
        DownloadEntity::class,
        TrackerStatEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class StealthDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun tabDao(): TabDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun extensionDao(): ExtensionDao
    abstract fun proxyConfigDao(): ProxyConfigDao
    abstract fun sitePermissionDao(): SitePermissionDao
    abstract fun downloadDao(): DownloadDao
    abstract fun trackerStatDao(): TrackerStatDao

    @Module
    @InstallIn(SingletonComponent::class)
    object Provider {
        @[Provides Singleton]
        fun provideDatabase(@ApplicationContext context: Context): StealthDatabase =
            Room.databaseBuilder(context, StealthDatabase::class.java, "stealth.db")
                // Destructive migration is NOT used: schema changes go through proper
                // migrations so stored profile settings survive updates (PRD §7, §19).
                .addMigrations()
                .build()

        @[Provides Singleton]
        fun profileDao(db: StealthDatabase): ProfileDao = db.profileDao()

        @[Provides Singleton]
        fun tabDao(db: StealthDatabase): TabDao = db.tabDao()

        @[Provides Singleton]
        fun bookmarkDao(db: StealthDatabase): BookmarkDao = db.bookmarkDao()

        @[Provides Singleton]
        fun historyDao(db: StealthDatabase): HistoryDao = db.historyDao()

        @[Provides Singleton]
        fun extensionDao(db: StealthDatabase): ExtensionDao = db.extensionDao()

        @[Provides Singleton]
        fun proxyConfigDao(db: StealthDatabase): ProxyConfigDao = db.proxyConfigDao()

        @[Provides Singleton]
        fun sitePermissionDao(db: StealthDatabase): SitePermissionDao = db.sitePermissionDao()

        @[Provides Singleton]
        fun downloadDao(db: StealthDatabase): DownloadDao = db.downloadDao()

        @[Provides Singleton]
        fun trackerStatDao(db: StealthDatabase): TrackerStatDao = db.trackerStatDao()
    }
}
