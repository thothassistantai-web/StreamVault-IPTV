package com.streamvault.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StreamVaultDatabase =
        Room.databaseBuilder(
            context,
            StreamVaultDatabase::class.java,
            "streamvault.db"
        )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
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
                StreamVaultDatabase.MIGRATION_12_13
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
    @Provides fun provideProgramDao(db: StreamVaultDatabase): ProgramDao = db.programDao()
    @Provides fun provideFavoriteDao(db: StreamVaultDatabase): FavoriteDao = db.favoriteDao()
    @Provides fun provideVirtualGroupDao(db: StreamVaultDatabase): VirtualGroupDao = db.virtualGroupDao()
    @Provides fun providePlaybackHistoryDao(db: StreamVaultDatabase): PlaybackHistoryDao = db.playbackHistoryDao()
    @Provides fun provideSyncMetadataDao(db: StreamVaultDatabase): SyncMetadataDao = db.syncMetadataDao()
}
