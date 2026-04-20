package com.streamvault.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.streamvault.app.BuildConfig
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.dao.*
import com.streamvault.data.local.dao.ChannelPreferenceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    private const val DEBUG_SLOW_QUERY_THRESHOLD_MS = 100L

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StreamVaultDatabase =
        Room.databaseBuilder(
            context,
            StreamVaultDatabase::class.java,
            "streamvault.db"
        )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .openHelperFactory(
                if (BuildConfig.DEBUG) {
                    SlowQueryLoggingOpenHelperFactory(
                        delegate = FrameworkSQLiteOpenHelperFactory(),
                        slowQueryThresholdMs = DEBUG_SLOW_QUERY_THRESHOLD_MS
                    )
                } else {
                    FrameworkSQLiteOpenHelperFactory()
                }
            )
            .addMigrations(
                StreamVaultDatabase.MIGRATION_1_2,
                StreamVaultDatabase.MIGRATION_2_3,
                StreamVaultDatabase.MIGRATION_3_4,
                StreamVaultDatabase.MIGRATION_4_5,
                StreamVaultDatabase.MIGRATION_5_6,
                StreamVaultDatabase.MIGRATION_6_7,
                StreamVaultDatabase.MIGRATION_7_8,
                StreamVaultDatabase.MIGRATION_8_9,
                StreamVaultDatabase.MIGRATION_9_10,
                StreamVaultDatabase.MIGRATION_10_11,
                StreamVaultDatabase.MIGRATION_11_12,
                StreamVaultDatabase.MIGRATION_12_13,
                StreamVaultDatabase.MIGRATION_13_14,
                StreamVaultDatabase.MIGRATION_14_15,
                StreamVaultDatabase.MIGRATION_15_16,
                StreamVaultDatabase.MIGRATION_16_17,
                StreamVaultDatabase.MIGRATION_17_18,
                StreamVaultDatabase.MIGRATION_18_19,
                StreamVaultDatabase.MIGRATION_19_20,
                StreamVaultDatabase.MIGRATION_20_21,
                StreamVaultDatabase.MIGRATION_21_22,
                StreamVaultDatabase.MIGRATION_22_23,
                StreamVaultDatabase.MIGRATION_23_24,
                StreamVaultDatabase.MIGRATION_24_25,
                StreamVaultDatabase.MIGRATION_25_26,
                StreamVaultDatabase.MIGRATION_26_27,
                StreamVaultDatabase.MIGRATION_27_28,
                StreamVaultDatabase.MIGRATION_28_29,
                StreamVaultDatabase.MIGRATION_29_30,
                StreamVaultDatabase.MIGRATION_30_31,
                StreamVaultDatabase.MIGRATION_31_32,
                StreamVaultDatabase.MIGRATION_32_33,
                StreamVaultDatabase.MIGRATION_33_34,
                StreamVaultDatabase.MIGRATION_34_35,
                StreamVaultDatabase.MIGRATION_35_36,
                StreamVaultDatabase.MIGRATION_36_37,
                StreamVaultDatabase.MIGRATION_37_38,
                StreamVaultDatabase.MIGRATION_38_39
            )
            // NOTE: fallbackToDestructiveMigration() intentionally removed.
            // All future schema changes MUST add a corresponding Migration in StreamVaultDatabase.
            .build()

    @Provides fun provideProviderDao(db: StreamVaultDatabase): ProviderDao = db.providerDao()
    @Provides fun provideChannelDao(db: StreamVaultDatabase): ChannelDao = db.channelDao()
    @Provides fun provideChannelPreferenceDao(db: StreamVaultDatabase): ChannelPreferenceDao = db.channelPreferenceDao()
    @Provides fun provideMovieDao(db: StreamVaultDatabase): MovieDao = db.movieDao()
    @Provides fun provideSeriesDao(db: StreamVaultDatabase): SeriesDao = db.seriesDao()
    @Provides fun provideEpisodeDao(db: StreamVaultDatabase): EpisodeDao = db.episodeDao()
    @Provides fun provideCategoryDao(db: StreamVaultDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideCatalogSyncDao(db: StreamVaultDatabase): CatalogSyncDao = db.catalogSyncDao()
    @Provides fun provideProgramDao(db: StreamVaultDatabase): ProgramDao = db.programDao()
    @Provides fun provideFavoriteDao(db: StreamVaultDatabase): FavoriteDao = db.favoriteDao()
    @Provides fun provideVirtualGroupDao(db: StreamVaultDatabase): VirtualGroupDao = db.virtualGroupDao()
    @Provides fun providePlaybackHistoryDao(db: StreamVaultDatabase): PlaybackHistoryDao = db.playbackHistoryDao()
    @Provides fun provideTmdbIdentityDao(db: StreamVaultDatabase): TmdbIdentityDao = db.tmdbIdentityDao()
    @Provides fun provideSearchHistoryDao(db: StreamVaultDatabase): SearchHistoryDao = db.searchHistoryDao()
    @Provides fun provideSyncMetadataDao(db: StreamVaultDatabase): SyncMetadataDao = db.syncMetadataDao()
    @Provides fun provideMovieCategoryHydrationDao(db: StreamVaultDatabase): MovieCategoryHydrationDao = db.movieCategoryHydrationDao()
    @Provides fun provideSeriesCategoryHydrationDao(db: StreamVaultDatabase): SeriesCategoryHydrationDao = db.seriesCategoryHydrationDao()
    @Provides fun provideEpgSourceDao(db: StreamVaultDatabase): EpgSourceDao = db.epgSourceDao()
    @Provides fun provideProviderEpgSourceDao(db: StreamVaultDatabase): ProviderEpgSourceDao = db.providerEpgSourceDao()
    @Provides fun provideEpgChannelDao(db: StreamVaultDatabase): EpgChannelDao = db.epgChannelDao()
    @Provides fun provideEpgProgrammeDao(db: StreamVaultDatabase): EpgProgrammeDao = db.epgProgrammeDao()
    @Provides fun provideChannelEpgMappingDao(db: StreamVaultDatabase): ChannelEpgMappingDao = db.channelEpgMappingDao()
    @Provides fun provideCombinedM3uProfileDao(db: StreamVaultDatabase): CombinedM3uProfileDao = db.combinedM3uProfileDao()
    @Provides fun provideCombinedM3uProfileMemberDao(db: StreamVaultDatabase): CombinedM3uProfileMemberDao = db.combinedM3uProfileMemberDao()
    @Provides fun provideRecordingScheduleDao(db: StreamVaultDatabase): RecordingScheduleDao = db.recordingScheduleDao()
    @Provides fun provideRecordingRunDao(db: StreamVaultDatabase): RecordingRunDao = db.recordingRunDao()
    @Provides fun provideProgramReminderDao(db: StreamVaultDatabase): ProgramReminderDao = db.programReminderDao()
    @Provides fun provideRecordingStorageDao(db: StreamVaultDatabase): RecordingStorageDao = db.recordingStorageDao()
}
