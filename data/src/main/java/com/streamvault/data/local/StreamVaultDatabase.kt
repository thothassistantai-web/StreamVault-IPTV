package com.streamvault.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.streamvault.data.local.dao.*
import com.streamvault.data.local.entity.*

@Database(
    entities = [
        ProviderEntity::class,
        ChannelEntity::class,
        ChannelPreferenceEntity::class,
        ChannelFtsEntity::class,
        MovieEntity::class,
        MovieFtsEntity::class,
        SeriesEntity::class,
        SeriesFtsEntity::class,
        EpisodeEntity::class,
        CategoryEntity::class,
        ChannelImportStageEntity::class,
        MovieImportStageEntity::class,
        SeriesImportStageEntity::class,
        CategoryImportStageEntity::class,
        ProgramEntity::class,
        FavoriteEntity::class,
        VirtualGroupEntity::class,
        PlaybackHistoryEntity::class,
        TmdbIdentityEntity::class,
        SearchHistoryEntity::class,
        SyncMetadataEntity::class,
        MovieCategoryHydrationEntity::class,
        SeriesCategoryHydrationEntity::class,
        EpgSourceEntity::class,
        ProviderEpgSourceEntity::class,
        EpgChannelEntity::class,
        EpgProgrammeEntity::class,
        ChannelEpgMappingEntity::class,
        CombinedM3uProfileEntity::class,
        CombinedM3uProfileMemberEntity::class,
        RecordingScheduleEntity::class,
        RecordingRunEntity::class,
        ProgramReminderEntity::class,
        RecordingStorageEntity::class,
        PlaybackCompatibilityRecordEntity::class,
        XtreamContentIndexEntity::class,
        XtreamIndexJobEntity::class,
        XtreamLiveOnboardingStateEntity::class,
        DownloadEntity::class
    ],
    version = 60,
    exportSchema = true   // ← was false; schema JSON now tracked in version control
)
@TypeConverters(RoomEnumConverters::class)
abstract class StreamVaultDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun channelDao(): ChannelDao
    abstract fun channelPreferenceDao(): ChannelPreferenceDao
    abstract fun movieDao(): MovieDao
    abstract fun seriesDao(): SeriesDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun categoryDao(): CategoryDao
    abstract fun catalogSyncDao(): CatalogSyncDao
    abstract fun programDao(): ProgramDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun virtualGroupDao(): VirtualGroupDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun tmdbIdentityDao(): TmdbIdentityDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun searchDao(): SearchDao
    abstract fun syncMetadataDao(): SyncMetadataDao
    abstract fun movieCategoryHydrationDao(): MovieCategoryHydrationDao
    abstract fun seriesCategoryHydrationDao(): SeriesCategoryHydrationDao
    abstract fun epgSourceDao(): EpgSourceDao
    abstract fun providerEpgSourceDao(): ProviderEpgSourceDao
    abstract fun epgChannelDao(): EpgChannelDao
    abstract fun epgProgrammeDao(): EpgProgrammeDao
    abstract fun channelEpgMappingDao(): ChannelEpgMappingDao
    abstract fun combinedM3uProfileDao(): CombinedM3uProfileDao
    abstract fun combinedM3uProfileMemberDao(): CombinedM3uProfileMemberDao
    abstract fun recordingScheduleDao(): RecordingScheduleDao
    abstract fun recordingRunDao(): RecordingRunDao
    abstract fun programReminderDao(): ProgramReminderDao
    abstract fun recordingStorageDao(): RecordingStorageDao
    abstract fun playbackCompatibilityDao(): PlaybackCompatibilityDao
    abstract fun xtreamContentIndexDao(): XtreamContentIndexDao
    abstract fun xtreamIndexJobDao(): XtreamIndexJobDao
    abstract fun xtreamLiveOnboardingDao(): XtreamLiveOnboardingDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        /**
         * Checks FK integrity for the specified tables only.
         * Always pass the tables the migration actually wrote to — never call with no arguments,
         * as that would check the entire database and can crash on pre-existing violations in
         * unrelated tables that the migration didn't touch.
         */
        private fun validateForeignKeys(database: SupportSQLiteDatabase, vararg tableNames: String) {
            for (table in tableNames) {
                database.query("PRAGMA foreign_key_check($table)").use { cursor ->
                    if (cursor.moveToFirst()) {
                        val tbl = if (!cursor.isNull(0)) cursor.getString(0) else "<unknown>"
                        val rowId = if (!cursor.isNull(1)) cursor.getLong(1) else -1L
                        val parent = if (!cursor.isNull(2)) cursor.getString(2) else "<unknown>"
                        throw IllegalStateException(
                            "Foreign key violation after migration: table=$tbl rowId=$rowId parent=$parent"
                        )
                    }
                }
            }
        }

        /**
         * Migration 1 → 2: no-op stub.
         * v1 databases had the same table structure as v2; this migration prevents Room
         * from crashing with an "unsatisfied migration" exception on very early installs.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Schema was identical between v1 and v2; nothing to alter.
            }
        }

        /**
         * Migration 2 → 3: added parental-control protection columns.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE categories ADD COLUMN is_user_protected INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE channels ADD COLUMN is_user_protected INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE movies ADD COLUMN is_adult INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE movies ADD COLUMN is_user_protected INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE series ADD COLUMN is_adult INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE series ADD COLUMN is_user_protected INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE episodes ADD COLUMN is_adult INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE episodes ADD COLUMN is_user_protected INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. Create playback_history table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS playback_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        content_id INTEGER NOT NULL,
                        content_type TEXT NOT NULL,
                        provider_id INTEGER NOT NULL,
                        title TEXT NOT NULL DEFAULT '',
                        poster_url TEXT,
                        stream_url TEXT NOT NULL DEFAULT '',
                        resume_position_ms INTEGER NOT NULL DEFAULT 0,
                        total_duration_ms INTEGER NOT NULL DEFAULT 0,
                        last_watched_at INTEGER NOT NULL DEFAULT 0,
                        watch_count INTEGER NOT NULL DEFAULT 1,
                        series_id INTEGER,
                        season_number INTEGER,
                        episode_number INTEGER
                    )
                """)
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_history_unique ON playback_history(content_id, content_type, provider_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_history_last_watched ON playback_history(last_watched_at DESC)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_history_provider ON playback_history(provider_id)")
                
                // 2. Create sync_metadata table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_metadata (
                        provider_id INTEGER PRIMARY KEY NOT NULL,
                        last_live_sync INTEGER NOT NULL DEFAULT 0,
                        last_movie_sync INTEGER NOT NULL DEFAULT 0,
                        last_series_sync INTEGER NOT NULL DEFAULT 0,
                        last_epg_sync INTEGER NOT NULL DEFAULT 0,
                        live_count INTEGER NOT NULL DEFAULT 0,
                        movie_count INTEGER NOT NULL DEFAULT 0,
                        series_count INTEGER NOT NULL DEFAULT 0,
                        epg_count INTEGER NOT NULL DEFAULT 0,
                        last_sync_status TEXT NOT NULL DEFAULT 'NONE'
                    )
                """)
                
                // 3. Migrate existing watch progress to history
                database.execSQL("""
                    INSERT OR IGNORE INTO playback_history (content_id, content_type, provider_id, title, resume_position_ms, last_watched_at)
                    SELECT id, 'MOVIE', provider_id, name, watch_progress, last_watched_at
                    FROM movies WHERE watch_progress > 0
                """)
                database.execSQL("""
                    INSERT OR IGNORE INTO playback_history (content_id, content_type, provider_id, title, resume_position_ms, last_watched_at, series_id, season_number, episode_number)
                    SELECT id, 'SERIES_EPISODE', provider_id, title, watch_progress, last_watched_at, series_id, season_number, episode_number
                    FROM episodes WHERE watch_progress > 0
                """)
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Note: Room auto-generates index names as 'index_tableName_columnNames'
                // Channels
                database.execSQL("DROP INDEX IF EXISTS index_channels_category_id")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_channels_provider_id_category_id ON channels(provider_id, category_id)")
                
                // Movies
                database.execSQL("DROP INDEX IF EXISTS index_movies_category_id")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_movies_provider_id_category_id ON movies(provider_id, category_id)")
                
                // Series
                database.execSQL("DROP INDEX IF EXISTS index_series_category_id")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_series_provider_id_category_id ON series(provider_id, category_id)")
                
                // Favorites
                database.execSQL("DROP INDEX IF EXISTS index_favorites_group_id")
                database.execSQL("DROP INDEX IF EXISTS index_favorites_position")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_content_type_group_id ON favorites(content_type, group_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_group_id_position ON favorites(group_id, position)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE virtual_groups ADD COLUMN content_type TEXT NOT NULL DEFAULT 'LIVE'")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE channels ADD COLUMN logical_group_id TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE channels ADD COLUMN error_count INTEGER NOT NULL DEFAULT 0")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_channels_logical_group_id ON channels(logical_group_id)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE programs ADD COLUMN provider_id INTEGER NOT NULL DEFAULT 0")
                database.execSQL("DROP INDEX IF EXISTS index_programs_channel_id")
                database.execSQL("DROP INDEX IF EXISTS index_programs_channel_id_start_time")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_programs_provider_id ON programs(provider_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_programs_provider_id_channel_id ON programs(provider_id, channel_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_programs_provider_id_channel_id_start_time ON programs(provider_id, channel_id, start_time)")
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_programs_provider_id_channel_id_start_time_end_time " +
                        "ON programs(provider_id, channel_id, start_time, end_time)"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Rebuild media tables to normalize legacy local IDs and keep only provider-scoped remote IDs as remote keys.
                // This preserves user-facing references by remapping favorites/history through temporary ID maps.

                // Channels
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS channels_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        stream_id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        logo_url TEXT,
                        group_title TEXT,
                        category_id INTEGER,
                        category_name TEXT,
                        stream_url TEXT NOT NULL,
                        epg_channel_id TEXT,
                        number INTEGER NOT NULL,
                        catch_up_supported INTEGER NOT NULL,
                        catch_up_days INTEGER NOT NULL,
                        catchUpSource TEXT,
                        provider_id INTEGER NOT NULL,
                        is_adult INTEGER NOT NULL,
                        is_user_protected INTEGER NOT NULL,
                        logical_group_id TEXT NOT NULL,
                        error_count INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO channels_new (
                        stream_id, name, logo_url, group_title, category_id, category_name, stream_url,
                        epg_channel_id, number, catch_up_supported, catch_up_days, catchUpSource,
                        provider_id, is_adult, is_user_protected, logical_group_id, error_count
                    )
                    SELECT
                        stream_id, name, logo_url, group_title, category_id, category_name, stream_url,
                        epg_channel_id, number, catch_up_supported, catch_up_days, catchUpSource,
                        provider_id, is_adult, is_user_protected, logical_group_id, error_count
                    FROM channels
                    """.trimIndent()
                )
                database.execSQL("CREATE TEMP TABLE channel_id_map(old_id INTEGER PRIMARY KEY NOT NULL, new_id INTEGER NOT NULL)")
                database.execSQL(
                    """
                    INSERT INTO channel_id_map(old_id, new_id)
                    SELECT old.id, MIN(new.id)
                    FROM channels old
                    JOIN channels_new new
                      ON new.provider_id = old.provider_id
                     AND new.stream_id = old.stream_id
                    GROUP BY old.id
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE favorites
                    SET content_id = (SELECT new_id FROM channel_id_map WHERE old_id = content_id)
                    WHERE content_type = 'LIVE'
                      AND content_id IN (SELECT old_id FROM channel_id_map)
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE playback_history
                    SET content_id = (SELECT new_id FROM channel_id_map WHERE old_id = content_id)
                    WHERE content_type = 'LIVE'
                      AND content_id IN (SELECT old_id FROM channel_id_map)
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE channels")
                database.execSQL("ALTER TABLE channels_new RENAME TO channels")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_channels_provider_id ON channels(provider_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_channels_provider_id_category_id ON channels(provider_id, category_id)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_channels_provider_id_stream_id ON channels(provider_id, stream_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_channels_logical_group_id ON channels(logical_group_id)")
                database.execSQL("DROP TABLE channel_id_map")

                // Movies
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS movies_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        stream_id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        poster_url TEXT,
                        backdrop_url TEXT,
                        category_id INTEGER,
                        category_name TEXT,
                        stream_url TEXT NOT NULL,
                        container_extension TEXT,
                        plot TEXT,
                        "cast" TEXT,
                        director TEXT,
                        genre TEXT,
                        release_date TEXT,
                        duration TEXT,
                        duration_seconds INTEGER NOT NULL,
                        rating REAL NOT NULL,
                        year TEXT,
                        tmdb_id INTEGER,
                        youtube_trailer TEXT,
                        provider_id INTEGER NOT NULL,
                        watch_progress INTEGER NOT NULL,
                        last_watched_at INTEGER NOT NULL,
                        is_adult INTEGER NOT NULL,
                        is_user_protected INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO movies_new (
                        stream_id, name, poster_url, backdrop_url, category_id, category_name, stream_url,
                        container_extension, plot, "cast", director, genre, release_date, duration, duration_seconds,
                        rating, year, tmdb_id, youtube_trailer, provider_id, watch_progress, last_watched_at,
                        is_adult, is_user_protected
                    )
                    SELECT
                        stream_id, name, poster_url, backdrop_url, category_id, category_name, stream_url,
                        container_extension, plot, "cast", director, genre, release_date, duration, duration_seconds,
                        rating, year, tmdb_id, youtube_trailer, provider_id, watch_progress, last_watched_at,
                        is_adult, is_user_protected
                    FROM movies
                    """.trimIndent()
                )
                database.execSQL("CREATE TEMP TABLE movie_id_map(old_id INTEGER PRIMARY KEY NOT NULL, new_id INTEGER NOT NULL)")
                database.execSQL(
                    """
                    INSERT INTO movie_id_map(old_id, new_id)
                    SELECT old.id, MIN(new.id)
                    FROM movies old
                    JOIN movies_new new
                      ON new.provider_id = old.provider_id
                     AND new.stream_id = old.stream_id
                    GROUP BY old.id
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE favorites
                    SET content_id = (SELECT new_id FROM movie_id_map WHERE old_id = content_id)
                    WHERE content_type = 'MOVIE'
                      AND content_id IN (SELECT old_id FROM movie_id_map)
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE playback_history
                    SET content_id = (SELECT new_id FROM movie_id_map WHERE old_id = content_id)
                    WHERE content_type = 'MOVIE'
                      AND content_id IN (SELECT old_id FROM movie_id_map)
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE movies")
                database.execSQL("ALTER TABLE movies_new RENAME TO movies")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_movies_provider_id ON movies(provider_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_movies_provider_id_category_id ON movies(provider_id, category_id)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_movies_provider_id_stream_id ON movies(provider_id, stream_id)")
                database.execSQL("DROP TABLE movie_id_map")

                // Series
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS series_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        series_id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        poster_url TEXT,
                        backdrop_url TEXT,
                        category_id INTEGER,
                        category_name TEXT,
                        plot TEXT,
                        "cast" TEXT,
                        director TEXT,
                        genre TEXT,
                        release_date TEXT,
                        rating REAL NOT NULL,
                        tmdb_id INTEGER,
                        youtube_trailer TEXT,
                        episode_run_time TEXT,
                        last_modified INTEGER NOT NULL,
                        provider_id INTEGER NOT NULL,
                        is_adult INTEGER NOT NULL,
                        is_user_protected INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO series_new (
                        series_id, name, poster_url, backdrop_url, category_id, category_name, plot, "cast",
                        director, genre, release_date, rating, tmdb_id, youtube_trailer, episode_run_time,
                        last_modified, provider_id, is_adult, is_user_protected
                    )
                    SELECT
                        series_id, name, poster_url, backdrop_url, category_id, category_name, plot, "cast",
                        director, genre, release_date, rating, tmdb_id, youtube_trailer, episode_run_time,
                        last_modified, provider_id, is_adult, is_user_protected
                    FROM series
                    """.trimIndent()
                )
                database.execSQL("CREATE TEMP TABLE series_id_map(old_id INTEGER PRIMARY KEY NOT NULL, new_id INTEGER NOT NULL)")
                database.execSQL(
                    """
                    INSERT INTO series_id_map(old_id, new_id)
                    SELECT old.id, MIN(new.id)
                    FROM series old
                    JOIN series_new new
                      ON new.provider_id = old.provider_id
                     AND new.series_id = old.series_id
                    GROUP BY old.id
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE favorites
                    SET content_id = (SELECT new_id FROM series_id_map WHERE old_id = content_id)
                    WHERE content_type = 'SERIES'
                      AND content_id IN (SELECT old_id FROM series_id_map)
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE playback_history
                    SET content_id = (SELECT new_id FROM series_id_map WHERE old_id = content_id)
                    WHERE content_type = 'SERIES'
                      AND content_id IN (SELECT old_id FROM series_id_map)
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE playback_history
                    SET series_id = (SELECT new_id FROM series_id_map WHERE old_id = series_id)
                    WHERE series_id IS NOT NULL
                      AND series_id IN (SELECT old_id FROM series_id_map)
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE episodes
                    SET series_id = (SELECT new_id FROM series_id_map WHERE old_id = series_id)
                    WHERE series_id IN (SELECT old_id FROM series_id_map)
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE series")
                database.execSQL("ALTER TABLE series_new RENAME TO series")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_series_provider_id ON series(provider_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_series_provider_id_category_id ON series(provider_id, category_id)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_series_provider_id_series_id ON series(provider_id, series_id)")
                database.execSQL("DROP TABLE series_id_map")

                // Episodes
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS episodes_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        episode_id INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        episode_number INTEGER NOT NULL,
                        season_number INTEGER NOT NULL,
                        stream_url TEXT NOT NULL,
                        container_extension TEXT,
                        cover_url TEXT,
                        plot TEXT,
                        duration TEXT,
                        duration_seconds INTEGER NOT NULL,
                        rating REAL NOT NULL,
                        release_date TEXT,
                        series_id INTEGER NOT NULL,
                        provider_id INTEGER NOT NULL,
                        watch_progress INTEGER NOT NULL,
                        last_watched_at INTEGER NOT NULL,
                        is_adult INTEGER NOT NULL,
                        is_user_protected INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO episodes_new (
                        episode_id, title, episode_number, season_number, stream_url, container_extension, cover_url,
                        plot, duration, duration_seconds, rating, release_date, series_id, provider_id, watch_progress,
                        last_watched_at, is_adult, is_user_protected
                    )
                    SELECT
                        episode_id, title, episode_number, season_number, stream_url, container_extension, cover_url,
                        plot, duration, duration_seconds, rating, release_date, series_id, provider_id, watch_progress,
                        last_watched_at, is_adult, is_user_protected
                    FROM episodes
                    """.trimIndent()
                )
                database.execSQL("CREATE TEMP TABLE episode_id_map(old_id INTEGER PRIMARY KEY NOT NULL, new_id INTEGER NOT NULL)")
                database.execSQL(
                    """
                    INSERT INTO episode_id_map(old_id, new_id)
                    SELECT old.id, new.id
                    FROM episodes old
                    JOIN episodes_new new
                      ON new.provider_id = old.provider_id
                     AND new.episode_id = old.episode_id
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE playback_history
                    SET content_id = (SELECT new_id FROM episode_id_map WHERE old_id = content_id)
                    WHERE content_type = 'SERIES_EPISODE'
                      AND content_id IN (SELECT old_id FROM episode_id_map)
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE episodes")
                database.execSQL("ALTER TABLE episodes_new RENAME TO episodes")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_series_id ON episodes(series_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_provider_id ON episodes(provider_id)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_episodes_provider_id_episode_id ON episodes(provider_id, episode_id)")
                database.execSQL("DROP TABLE episode_id_map")
                validateForeignKeys(database, "episodes")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS channels_fts USING fts4(name, content='channels')")
                database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS movies_fts USING fts4(name, content='movies')")
                database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS series_fts USING fts4(name, content='series')")

                database.execSQL("INSERT INTO channels_fts(rowid, name) SELECT id, name FROM channels")
                database.execSQL("INSERT INTO movies_fts(rowid, name) SELECT id, name FROM movies")
                database.execSQL("INSERT INTO series_fts(rowid, name) SELECT id, name FROM series")

                database.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS channels_ai
                    AFTER INSERT ON channels BEGIN
                        INSERT INTO channels_fts(rowid, name) VALUES (new.id, new.name);
                    END
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS channels_ad
                    AFTER DELETE ON channels BEGIN
                        DELETE FROM channels_fts WHERE rowid = old.id;
                    END
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS channels_au
                    AFTER UPDATE OF name ON channels BEGIN
                        UPDATE channels_fts SET name = new.name WHERE rowid = old.id;
                    END
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS movies_ai
                    AFTER INSERT ON movies BEGIN
                        INSERT INTO movies_fts(rowid, name) VALUES (new.id, new.name);
                    END
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS movies_ad
                    AFTER DELETE ON movies BEGIN
                        DELETE FROM movies_fts WHERE rowid = old.id;
                    END
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS movies_au
                    AFTER UPDATE OF name ON movies BEGIN
                        UPDATE movies_fts SET name = new.name WHERE rowid = old.id;
                    END
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS series_ai
                    AFTER INSERT ON series BEGIN
                        INSERT INTO series_fts(rowid, name) VALUES (new.id, new.name);
                    END
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS series_ad
                    AFTER DELETE ON series BEGIN
                        DELETE FROM series_fts WHERE rowid = old.id;
                    END
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS series_au
                    AFTER UPDATE OF name ON series BEGIN
                        UPDATE series_fts SET name = new.name WHERE rowid = old.id;
                    END
                    """.trimIndent()
                )
            }
        }

        /**
         * Migration 10 → 11: Add foreign key constraints to child tables.
         * Tables referencing providers get ON DELETE CASCADE.
         * Favorites referencing virtual_groups get ON DELETE SET NULL.
         * Programs table excluded (uses negative staging provider IDs).
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Clean up orphaned records before adding FK constraints
                database.execSQL("DELETE FROM channels WHERE provider_id NOT IN (SELECT id FROM providers)")
                database.execSQL("DELETE FROM movies WHERE provider_id NOT IN (SELECT id FROM providers)")
                database.execSQL("DELETE FROM series WHERE provider_id NOT IN (SELECT id FROM providers)")
                database.execSQL("DELETE FROM episodes WHERE provider_id NOT IN (SELECT id FROM providers)")
                database.execSQL("DELETE FROM categories WHERE provider_id NOT IN (SELECT id FROM providers)")
                database.execSQL("DELETE FROM playback_history WHERE provider_id NOT IN (SELECT id FROM providers)")
                database.execSQL("DELETE FROM sync_metadata WHERE provider_id NOT IN (SELECT id FROM providers)")
                database.execSQL("UPDATE favorites SET group_id = NULL WHERE group_id IS NOT NULL AND group_id NOT IN (SELECT id FROM virtual_groups)")

                // ── Drop FTS triggers and tables (channels, movies, series) ──
                database.execSQL("DROP TRIGGER IF EXISTS channels_ai")
                database.execSQL("DROP TRIGGER IF EXISTS channels_ad")
                database.execSQL("DROP TRIGGER IF EXISTS channels_au")
                database.execSQL("DROP TRIGGER IF EXISTS movies_ai")
                database.execSQL("DROP TRIGGER IF EXISTS movies_ad")
                database.execSQL("DROP TRIGGER IF EXISTS movies_au")
                database.execSQL("DROP TRIGGER IF EXISTS series_ai")
                database.execSQL("DROP TRIGGER IF EXISTS series_ad")
                database.execSQL("DROP TRIGGER IF EXISTS series_au")
                database.execSQL("DROP TABLE IF EXISTS channels_fts")
                database.execSQL("DROP TABLE IF EXISTS movies_fts")
                database.execSQL("DROP TABLE IF EXISTS series_fts")

                // ── Channels ──
                database.execSQL("""
                    CREATE TABLE channels_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        stream_id INTEGER NOT NULL DEFAULT 0,
                        name TEXT NOT NULL,
                        logo_url TEXT,
                        group_title TEXT,
                        category_id INTEGER,
                        category_name TEXT,
                        stream_url TEXT NOT NULL DEFAULT '',
                        epg_channel_id TEXT,
                        number INTEGER NOT NULL DEFAULT 0,
                        catch_up_supported INTEGER NOT NULL DEFAULT 0,
                        catch_up_days INTEGER NOT NULL DEFAULT 0,
                        catchUpSource TEXT,
                        provider_id INTEGER NOT NULL DEFAULT 0,
                        is_adult INTEGER NOT NULL DEFAULT 0,
                        is_user_protected INTEGER NOT NULL DEFAULT 0,
                        logical_group_id TEXT NOT NULL DEFAULT '',
                        error_count INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("INSERT INTO channels_new SELECT * FROM channels")
                database.execSQL("DROP TABLE channels")
                database.execSQL("ALTER TABLE channels_new RENAME TO channels")
                database.execSQL("CREATE INDEX index_channels_provider_id ON channels(provider_id)")
                database.execSQL("CREATE INDEX index_channels_provider_id_category_id ON channels(provider_id, category_id)")
                database.execSQL("CREATE UNIQUE INDEX index_channels_provider_id_stream_id ON channels(provider_id, stream_id)")
                database.execSQL("CREATE INDEX index_channels_logical_group_id ON channels(logical_group_id)")

                // ── Movies ──
                database.execSQL("""
                    CREATE TABLE movies_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        stream_id INTEGER NOT NULL DEFAULT 0,
                        name TEXT NOT NULL,
                        poster_url TEXT,
                        backdrop_url TEXT,
                        category_id INTEGER,
                        category_name TEXT,
                        stream_url TEXT NOT NULL DEFAULT '',
                        container_extension TEXT,
                        plot TEXT,
                        "cast" TEXT,
                        director TEXT,
                        genre TEXT,
                        release_date TEXT,
                        duration TEXT,
                        duration_seconds INTEGER NOT NULL DEFAULT 0,
                        rating REAL NOT NULL DEFAULT 0,
                        year TEXT,
                        tmdb_id INTEGER,
                        youtube_trailer TEXT,
                        provider_id INTEGER NOT NULL DEFAULT 0,
                        watch_progress INTEGER NOT NULL DEFAULT 0,
                        last_watched_at INTEGER NOT NULL DEFAULT 0,
                        is_adult INTEGER NOT NULL DEFAULT 0,
                        is_user_protected INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("INSERT INTO movies_new SELECT * FROM movies")
                database.execSQL("DROP TABLE movies")
                database.execSQL("ALTER TABLE movies_new RENAME TO movies")
                database.execSQL("CREATE INDEX index_movies_provider_id ON movies(provider_id)")
                database.execSQL("CREATE INDEX index_movies_provider_id_category_id ON movies(provider_id, category_id)")
                database.execSQL("CREATE UNIQUE INDEX index_movies_provider_id_stream_id ON movies(provider_id, stream_id)")

                // ── Series ──
                database.execSQL("""
                    CREATE TABLE series_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        series_id INTEGER NOT NULL DEFAULT 0,
                        name TEXT NOT NULL,
                        poster_url TEXT,
                        backdrop_url TEXT,
                        category_id INTEGER,
                        category_name TEXT,
                        plot TEXT,
                        "cast" TEXT,
                        director TEXT,
                        genre TEXT,
                        release_date TEXT,
                        rating REAL NOT NULL DEFAULT 0,
                        tmdb_id INTEGER,
                        youtube_trailer TEXT,
                        episode_run_time TEXT,
                        last_modified INTEGER NOT NULL DEFAULT 0,
                        provider_id INTEGER NOT NULL DEFAULT 0,
                        is_adult INTEGER NOT NULL DEFAULT 0,
                        is_user_protected INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("INSERT INTO series_new SELECT * FROM series")
                database.execSQL("DROP TABLE series")
                database.execSQL("ALTER TABLE series_new RENAME TO series")
                database.execSQL("CREATE INDEX index_series_provider_id ON series(provider_id)")
                database.execSQL("CREATE INDEX index_series_provider_id_category_id ON series(provider_id, category_id)")
                database.execSQL("CREATE UNIQUE INDEX index_series_provider_id_series_id ON series(provider_id, series_id)")

                // ── Recreate FTS tables and triggers ──
                database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS channels_fts USING fts4(name, content='channels')")
                database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS movies_fts USING fts4(name, content='movies')")
                database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS series_fts USING fts4(name, content='series')")
                database.execSQL("INSERT INTO channels_fts(rowid, name) SELECT id, name FROM channels")
                database.execSQL("INSERT INTO movies_fts(rowid, name) SELECT id, name FROM movies")
                database.execSQL("INSERT INTO series_fts(rowid, name) SELECT id, name FROM series")
                database.execSQL("CREATE TRIGGER IF NOT EXISTS channels_ai AFTER INSERT ON channels BEGIN INSERT INTO channels_fts(rowid, name) VALUES (new.id, new.name); END")
                database.execSQL("CREATE TRIGGER IF NOT EXISTS channels_ad AFTER DELETE ON channels BEGIN DELETE FROM channels_fts WHERE rowid = old.id; END")
                database.execSQL("CREATE TRIGGER IF NOT EXISTS channels_au AFTER UPDATE OF name ON channels BEGIN UPDATE channels_fts SET name = new.name WHERE rowid = old.id; END")
                database.execSQL("CREATE TRIGGER IF NOT EXISTS movies_ai AFTER INSERT ON movies BEGIN INSERT INTO movies_fts(rowid, name) VALUES (new.id, new.name); END")
                database.execSQL("CREATE TRIGGER IF NOT EXISTS movies_ad AFTER DELETE ON movies BEGIN DELETE FROM movies_fts WHERE rowid = old.id; END")
                database.execSQL("CREATE TRIGGER IF NOT EXISTS movies_au AFTER UPDATE OF name ON movies BEGIN UPDATE movies_fts SET name = new.name WHERE rowid = old.id; END")
                database.execSQL("CREATE TRIGGER IF NOT EXISTS series_ai AFTER INSERT ON series BEGIN INSERT INTO series_fts(rowid, name) VALUES (new.id, new.name); END")
                database.execSQL("CREATE TRIGGER IF NOT EXISTS series_ad AFTER DELETE ON series BEGIN DELETE FROM series_fts WHERE rowid = old.id; END")
                database.execSQL("CREATE TRIGGER IF NOT EXISTS series_au AFTER UPDATE OF name ON series BEGIN UPDATE series_fts SET name = new.name WHERE rowid = old.id; END")

                // ── Episodes ──
                database.execSQL("""
                    CREATE TABLE episodes_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        episode_id INTEGER NOT NULL DEFAULT 0,
                        title TEXT NOT NULL,
                        episode_number INTEGER NOT NULL,
                        season_number INTEGER NOT NULL,
                        stream_url TEXT NOT NULL DEFAULT '',
                        container_extension TEXT,
                        cover_url TEXT,
                        plot TEXT,
                        duration TEXT,
                        duration_seconds INTEGER NOT NULL DEFAULT 0,
                        rating REAL NOT NULL DEFAULT 0,
                        release_date TEXT,
                        series_id INTEGER NOT NULL DEFAULT 0,
                        provider_id INTEGER NOT NULL DEFAULT 0,
                        watch_progress INTEGER NOT NULL DEFAULT 0,
                        last_watched_at INTEGER NOT NULL DEFAULT 0,
                        is_adult INTEGER NOT NULL DEFAULT 0,
                        is_user_protected INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("INSERT INTO episodes_new SELECT * FROM episodes")
                database.execSQL("DROP TABLE episodes")
                database.execSQL("ALTER TABLE episodes_new RENAME TO episodes")
                database.execSQL("CREATE INDEX index_episodes_series_id ON episodes(series_id)")
                database.execSQL("CREATE INDEX index_episodes_provider_id ON episodes(provider_id)")
                database.execSQL("CREATE UNIQUE INDEX index_episodes_provider_id_episode_id ON episodes(provider_id, episode_id)")

                // ── Categories ──
                database.execSQL("""
                    CREATE TABLE categories_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        category_id INTEGER NOT NULL DEFAULT 0,
                        name TEXT NOT NULL,
                        parent_id INTEGER,
                        type TEXT NOT NULL DEFAULT 'LIVE',
                        provider_id INTEGER NOT NULL DEFAULT 0,
                        is_adult INTEGER NOT NULL DEFAULT 0,
                        is_user_protected INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("INSERT INTO categories_new SELECT * FROM categories")
                database.execSQL("DROP TABLE categories")
                database.execSQL("ALTER TABLE categories_new RENAME TO categories")
                database.execSQL("CREATE INDEX index_categories_provider_id ON categories(provider_id)")
                database.execSQL("CREATE UNIQUE INDEX index_categories_provider_id_category_id_type ON categories(provider_id, category_id, type)")

                // ── Playback History ──
                database.execSQL("""
                    CREATE TABLE playback_history_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        content_id INTEGER NOT NULL,
                        content_type TEXT NOT NULL,
                        provider_id INTEGER NOT NULL,
                        title TEXT NOT NULL DEFAULT '',
                        poster_url TEXT,
                        stream_url TEXT NOT NULL DEFAULT '',
                        resume_position_ms INTEGER NOT NULL DEFAULT 0,
                        total_duration_ms INTEGER NOT NULL DEFAULT 0,
                        last_watched_at INTEGER NOT NULL DEFAULT 0,
                        watch_count INTEGER NOT NULL DEFAULT 1,
                        series_id INTEGER,
                        season_number INTEGER,
                        episode_number INTEGER,
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("INSERT INTO playback_history_new SELECT * FROM playback_history")
                database.execSQL("DROP TABLE playback_history")
                database.execSQL("ALTER TABLE playback_history_new RENAME TO playback_history")
                database.execSQL("CREATE UNIQUE INDEX index_playback_history_content_id_content_type_provider_id ON playback_history(content_id, content_type, provider_id)")
                database.execSQL("CREATE INDEX index_playback_history_last_watched_at ON playback_history(last_watched_at)")
                database.execSQL("CREATE INDEX index_playback_history_provider_id ON playback_history(provider_id)")

                // ── Sync Metadata ──
                database.execSQL("""
                    CREATE TABLE sync_metadata_new (
                        provider_id INTEGER NOT NULL PRIMARY KEY,
                        last_live_sync INTEGER NOT NULL DEFAULT 0,
                        last_movie_sync INTEGER NOT NULL DEFAULT 0,
                        last_series_sync INTEGER NOT NULL DEFAULT 0,
                        last_epg_sync INTEGER NOT NULL DEFAULT 0,
                        live_count INTEGER NOT NULL DEFAULT 0,
                        movie_count INTEGER NOT NULL DEFAULT 0,
                        series_count INTEGER NOT NULL DEFAULT 0,
                        epg_count INTEGER NOT NULL DEFAULT 0,
                        last_sync_status TEXT NOT NULL DEFAULT 'NONE',
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("INSERT INTO sync_metadata_new SELECT * FROM sync_metadata")
                database.execSQL("DROP TABLE sync_metadata")
                database.execSQL("ALTER TABLE sync_metadata_new RENAME TO sync_metadata")

                // ── Favorites ──
                database.execSQL("""
                    CREATE TABLE favorites_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        content_id INTEGER NOT NULL,
                        content_type TEXT NOT NULL,
                        position INTEGER NOT NULL DEFAULT 0,
                        group_id INTEGER,
                        added_at INTEGER NOT NULL,
                        FOREIGN KEY(group_id) REFERENCES virtual_groups(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("INSERT INTO favorites_new SELECT * FROM favorites")
                database.execSQL("DROP TABLE favorites")
                database.execSQL("ALTER TABLE favorites_new RENAME TO favorites")
                database.execSQL("CREATE UNIQUE INDEX index_favorites_content_id_content_type_group_id ON favorites(content_id, content_type, group_id)")
                database.execSQL("CREATE INDEX index_favorites_content_type_group_id ON favorites(content_type, group_id)")
                database.execSQL("CREATE INDEX index_favorites_group_id_position ON favorites(group_id, position)")
                validateForeignKeys(database, "favorites")
            }
        }
        /**
         * Migration 11 → 12: adds series_id FK to episodes.
         * Episodes whose series_id has no matching row in the series table are silently
         * removed — they are unreachable orphans and would violate the new constraint.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create new episodes table with both FK constraints
                database.execSQL("""
                    CREATE TABLE episodes_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        episode_id INTEGER NOT NULL DEFAULT 0,
                        title TEXT NOT NULL,
                        episode_number INTEGER NOT NULL,
                        season_number INTEGER NOT NULL,
                        stream_url TEXT NOT NULL DEFAULT '',
                        container_extension TEXT,
                        cover_url TEXT,
                        plot TEXT,
                        duration TEXT,
                        duration_seconds INTEGER NOT NULL DEFAULT 0,
                        rating REAL NOT NULL DEFAULT 0,
                        release_date TEXT,
                        series_id INTEGER NOT NULL DEFAULT 0,
                        provider_id INTEGER NOT NULL DEFAULT 0,
                        watch_progress INTEGER NOT NULL DEFAULT 0,
                        last_watched_at INTEGER NOT NULL DEFAULT 0,
                        is_adult INTEGER NOT NULL DEFAULT 0,
                        is_user_protected INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE,
                        FOREIGN KEY(series_id) REFERENCES series(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                // Migrate only episodes with a valid series parent; orphans are discarded
                database.execSQL("""
                    INSERT INTO episodes_new
                    SELECT * FROM episodes
                    WHERE series_id IN (SELECT id FROM series)
                """.trimIndent())
                database.execSQL("DROP TABLE episodes")
                database.execSQL("ALTER TABLE episodes_new RENAME TO episodes")
                database.execSQL("CREATE INDEX index_episodes_series_id ON episodes(series_id)")
                database.execSQL("CREATE INDEX index_episodes_provider_id ON episodes(provider_id)")
                database.execSQL("CREATE UNIQUE INDEX index_episodes_provider_id_episode_id ON episodes(provider_id, episode_id)")
            }
        }

        /**
         * Migration 12 → 13: move high-cardinality per-channel UI state into Room.
         * Existing DataStore aspect-ratio entries are read lazily as a legacy fallback and
         * get replaced in Room on the next write for each channel.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS channel_preferences (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        channel_id INTEGER NOT NULL,
                        aspect_ratio TEXT,
                        updated_at INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(channel_id) REFERENCES channels(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_channel_preferences_channel_id ON channel_preferences(channel_id)"
                )
            }
        }

        /**
         * Migration 13 → 14: add targeted secondary indexes for broad EPG windows,
         * provider/type category filtering, and virtual group content type lookups.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_programs_provider_id_start_time_end_time ON programs(provider_id, start_time, end_time)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_categories_provider_id_type ON categories(provider_id, type)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_virtual_groups_content_type ON virtual_groups(content_type)"
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE providers ADD COLUMN api_version TEXT")
                database.execSQL("ALTER TABLE channels ADD COLUMN quality_options_json TEXT")
                database.execSQL("ALTER TABLE programs ADD COLUMN rating TEXT")
                database.execSQL("ALTER TABLE programs ADD COLUMN image_url TEXT")
                database.execSQL("ALTER TABLE programs ADD COLUMN genre TEXT")
                database.execSQL("ALTER TABLE programs ADD COLUMN category TEXT")
                database.execSQL("ALTER TABLE playback_history ADD COLUMN watched_status TEXT NOT NULL DEFAULT 'IN_PROGRESS'")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_channels_provider_id_category_id_logical_group_id " +
                        "ON channels(provider_id, category_id, logical_group_id)"
                )
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE providers ADD COLUMN allowed_output_formats_json TEXT NOT NULL DEFAULT '[]'"
                )
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN last_movie_attempt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN last_movie_success INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN last_movie_partial INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN movie_sync_mode TEXT NOT NULL DEFAULT 'UNKNOWN'")
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN movie_warnings_count INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN movie_catalog_stale INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN movie_parallel_failures_remembered INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN movie_healthy_sync_streak INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE channels ADD COLUMN sync_fingerprint TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE movies ADD COLUMN sync_fingerprint TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE series ADD COLUMN sync_fingerprint TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE categories ADD COLUMN sync_fingerprint TEXT NOT NULL DEFAULT ''")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS channel_import_stage (
                        session_id INTEGER NOT NULL,
                        provider_id INTEGER NOT NULL,
                        stream_id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        logo_url TEXT,
                        group_title TEXT,
                        category_id INTEGER,
                        category_name TEXT,
                        stream_url TEXT NOT NULL,
                        epg_channel_id TEXT,
                        number INTEGER NOT NULL,
                        catch_up_supported INTEGER NOT NULL,
                        catch_up_days INTEGER NOT NULL,
                        catchUpSource TEXT,
                        is_adult INTEGER NOT NULL,
                        sync_fingerprint TEXT NOT NULL,
                        PRIMARY KEY(session_id, provider_id, stream_id),
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_channel_import_stage_provider_id ON channel_import_stage(provider_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_channel_import_stage_session_id_provider_id ON channel_import_stage(session_id, provider_id)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS movie_import_stage (
                        session_id INTEGER NOT NULL,
                        provider_id INTEGER NOT NULL,
                        stream_id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        poster_url TEXT,
                        backdrop_url TEXT,
                        category_id INTEGER,
                        category_name TEXT,
                        stream_url TEXT NOT NULL,
                        container_extension TEXT,
                        plot TEXT,
                        "cast" TEXT,
                        director TEXT,
                        genre TEXT,
                        release_date TEXT,
                        duration TEXT,
                        duration_seconds INTEGER NOT NULL,
                        rating REAL NOT NULL,
                        year TEXT,
                        tmdb_id INTEGER,
                        youtube_trailer TEXT,
                        is_adult INTEGER NOT NULL,
                        sync_fingerprint TEXT NOT NULL,
                        PRIMARY KEY(session_id, provider_id, stream_id),
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_movie_import_stage_provider_id ON movie_import_stage(provider_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_movie_import_stage_session_id_provider_id ON movie_import_stage(session_id, provider_id)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS series_import_stage (
                        session_id INTEGER NOT NULL,
                        provider_id INTEGER NOT NULL,
                        series_id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        poster_url TEXT,
                        backdrop_url TEXT,
                        category_id INTEGER,
                        category_name TEXT,
                        plot TEXT,
                        "cast" TEXT,
                        director TEXT,
                        genre TEXT,
                        release_date TEXT,
                        rating REAL NOT NULL,
                        tmdb_id INTEGER,
                        youtube_trailer TEXT,
                        episode_run_time TEXT,
                        last_modified INTEGER NOT NULL,
                        is_adult INTEGER NOT NULL,
                        sync_fingerprint TEXT NOT NULL,
                        PRIMARY KEY(session_id, provider_id, series_id),
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_series_import_stage_provider_id ON series_import_stage(provider_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_series_import_stage_session_id_provider_id ON series_import_stage(session_id, provider_id)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS category_import_stage (
                        session_id INTEGER NOT NULL,
                        provider_id INTEGER NOT NULL,
                        category_id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        parent_id INTEGER,
                        type TEXT NOT NULL,
                        is_adult INTEGER NOT NULL,
                        sync_fingerprint TEXT NOT NULL,
                        PRIMARY KEY(session_id, provider_id, category_id, type),
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_category_import_stage_provider_id ON category_import_stage(provider_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_category_import_stage_session_id_provider_id ON category_import_stage(session_id, provider_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_category_import_stage_provider_id_type ON category_import_stage(provider_id, type)")
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN live_avoid_full_until INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN movie_avoid_full_until INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN series_avoid_full_until INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN live_sequential_failures_remembered INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN live_healthy_sync_streak INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN series_sequential_failures_remembered INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN series_healthy_sync_streak INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE channel_import_stage ADD COLUMN logical_group_id TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE channel_import_stage ADD COLUMN error_count INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE providers ADD COLUMN xtream_fast_sync_enabled INTEGER NOT NULL DEFAULT 0")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS movie_category_hydration (
                        provider_id INTEGER NOT NULL,
                        category_id INTEGER NOT NULL,
                        last_hydrated_at INTEGER NOT NULL DEFAULT 0,
                        item_count INTEGER NOT NULL DEFAULT 0,
                        last_status TEXT NOT NULL DEFAULT 'IDLE',
                        last_error TEXT,
                        PRIMARY KEY(provider_id, category_id),
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_movie_category_hydration_provider_id ON movie_category_hydration(provider_id)"
                )
            }
        }

        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS series_category_hydration (
                        provider_id INTEGER NOT NULL,
                        category_id INTEGER NOT NULL,
                        last_hydrated_at INTEGER NOT NULL DEFAULT 0,
                        item_count INTEGER NOT NULL DEFAULT 0,
                        last_status TEXT NOT NULL DEFAULT 'IDLE',
                        last_error TEXT,
                        PRIMARY KEY(provider_id, category_id),
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_series_category_hydration_provider_id ON series_category_hydration(provider_id)")
            }
        }

        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE providers ADD COLUMN epg_sync_mode TEXT NOT NULL DEFAULT 'UPFRONT'")
            }
        }

        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE providers ADD COLUMN m3u_vod_classification_enabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recording_schedules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        provider_id INTEGER NOT NULL,
                        channel_id INTEGER NOT NULL,
                        channel_name TEXT NOT NULL,
                        stream_url TEXT NOT NULL,
                        program_title TEXT,
                        requested_start_ms INTEGER NOT NULL,
                        requested_end_ms INTEGER NOT NULL,
                        recurrence TEXT NOT NULL,
                        recurring_rule_id TEXT,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        is_manual INTEGER NOT NULL DEFAULT 0,
                        priority INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_recording_schedules_provider_id ON recording_schedules(provider_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_recording_schedules_enabled_requested_start_ms ON recording_schedules(enabled, requested_start_ms)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_recording_schedules_recurring_rule_id ON recording_schedules(recurring_rule_id)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recording_runs (
                        id TEXT PRIMARY KEY NOT NULL,
                        schedule_id INTEGER NOT NULL,
                        provider_id INTEGER NOT NULL,
                        channel_id INTEGER NOT NULL,
                        channel_name TEXT NOT NULL,
                        stream_url TEXT NOT NULL,
                        program_title TEXT,
                        scheduled_start_ms INTEGER NOT NULL,
                        scheduled_end_ms INTEGER NOT NULL,
                        recurrence TEXT NOT NULL,
                        recurring_rule_id TEXT,
                        status TEXT NOT NULL,
                        source_type TEXT NOT NULL,
                        resolved_url TEXT,
                        headers_json TEXT NOT NULL DEFAULT '{}',
                        user_agent TEXT,
                        expiration_time INTEGER,
                        provider_label TEXT,
                        output_uri TEXT,
                        output_display_path TEXT,
                        bytes_written INTEGER NOT NULL DEFAULT 0,
                        average_throughput_bps INTEGER NOT NULL DEFAULT 0,
                        retry_count INTEGER NOT NULL DEFAULT 0,
                        last_progress_at_ms INTEGER,
                        failure_category TEXT NOT NULL DEFAULT 'NONE',
                        failure_reason TEXT,
                        terminal_at_ms INTEGER,
                        started_at_ms INTEGER,
                        ended_at_ms INTEGER,
                        schedule_enabled INTEGER NOT NULL DEFAULT 1,
                        priority INTEGER NOT NULL DEFAULT 0,
                        alarm_start_at_ms INTEGER,
                        alarm_stop_at_ms INTEGER,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        FOREIGN KEY(schedule_id) REFERENCES recording_schedules(id) ON DELETE CASCADE,
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_recording_runs_schedule_id ON recording_runs(schedule_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_recording_runs_provider_id ON recording_runs(provider_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_recording_runs_status_scheduled_start_ms ON recording_runs(status, scheduled_start_ms)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_recording_runs_alarm_start_at_ms ON recording_runs(alarm_start_at_ms)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_recording_runs_alarm_stop_at_ms ON recording_runs(alarm_stop_at_ms)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recording_storage (
                        id INTEGER PRIMARY KEY NOT NULL,
                        tree_uri TEXT,
                        display_name TEXT,
                        output_directory TEXT,
                        available_bytes INTEGER,
                        is_writable INTEGER NOT NULL DEFAULT 0,
                        file_name_pattern TEXT NOT NULL DEFAULT 'ChannelName_yyyy-MM-dd_HH-mm_ProgramTitle.ts',
                        retention_days INTEGER,
                        max_simultaneous_recordings INTEGER NOT NULL DEFAULT 2,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                val now = System.currentTimeMillis()
                database.execSQL(
                    """
                    INSERT OR IGNORE INTO recording_storage (
                        id, tree_uri, display_name, output_directory, available_bytes, is_writable, file_name_pattern,
                        retention_days, max_simultaneous_recordings, updated_at
                    ) VALUES (
                        1, NULL, NULL, NULL, NULL, 0, 'ChannelName_yyyy-MM-dd_HH-mm_ProgramTitle.ts', NULL, 2, $now
                    )
                    """.trimIndent()
                )
                validateForeignKeys(database, "recording_schedules", "recording_runs")
            }
        }

        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS combined_m3u_profiles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS combined_m3u_profile_members (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        profile_id INTEGER NOT NULL,
                        provider_id INTEGER NOT NULL,
                        priority INTEGER NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        FOREIGN KEY(profile_id) REFERENCES combined_m3u_profiles(id) ON DELETE CASCADE,
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_combined_m3u_profile_members_profile_id ON combined_m3u_profile_members(profile_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_combined_m3u_profile_members_provider_id ON combined_m3u_profile_members(provider_id)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_combined_m3u_profile_members_profile_id_provider_id ON combined_m3u_profile_members(profile_id, provider_id)"
                )
            }
        }

        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE movies ADD COLUMN added_at INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(database: SupportSQLiteDatabase) {
                fun firstLong(query: String): Long? = database.query(query).use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                }

                // FK enforcement was never enabled at runtime (setForeignKeyConstraintsEnabled was
                // never called), so ON DELETE CASCADE never fired when a provider was deleted.
                // Purge orphaned content rows now so the provider_id inference below can only
                // ever produce valid FK values. Favorites pointing to purged content will resolve
                // to NULL and be silently dropped via the `continue` below.
                database.execSQL("DELETE FROM channels WHERE provider_id NOT IN (SELECT id FROM providers)")
                database.execSQL("DELETE FROM movies WHERE provider_id NOT IN (SELECT id FROM providers)")
                database.execSQL("DELETE FROM series WHERE provider_id NOT IN (SELECT id FROM providers)")

                val defaultProviderId = firstLong(
                    "SELECT id FROM providers WHERE is_active = 1 ORDER BY id LIMIT 1"
                ) ?: firstLong(
                    "SELECT id FROM providers ORDER BY id LIMIT 1"
                )

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS virtual_groups_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        provider_id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        icon_emoji TEXT,
                        position INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        content_type TEXT NOT NULL,
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                val providersByLegacyGroup = mutableMapOf<Long, MutableSet<Long>>()
                database.query(
                    """
                    SELECT f.group_id,
                           CASE f.content_type
                               WHEN 'LIVE' THEN (SELECT provider_id FROM channels WHERE id = f.content_id)
                               WHEN 'MOVIE' THEN (SELECT provider_id FROM movies WHERE id = f.content_id)
                               WHEN 'SERIES' THEN (SELECT provider_id FROM series WHERE id = f.content_id)
                               ELSE NULL
                           END AS provider_id
                    FROM favorites f
                    WHERE f.group_id IS NOT NULL
                    """.trimIndent()
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.isNull(0) || cursor.isNull(1)) continue
                        val groupId = cursor.getLong(0)
                        val providerId = cursor.getLong(1)
                        providersByLegacyGroup.getOrPut(groupId) { linkedSetOf() }.add(providerId)
                    }
                }

                val groupProviderToNewId = mutableMapOf<Pair<Long, Long>, Long>()
                var nextGroupId = (firstLong("SELECT MAX(id) FROM virtual_groups") ?: 0L) + 1L

                database.query(
                    "SELECT id, name, icon_emoji, position, created_at, content_type FROM virtual_groups ORDER BY id"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val legacyGroupId = cursor.getLong(0)
                        val name = cursor.getString(1)
                        val iconEmoji = if (cursor.isNull(2)) null else cursor.getString(2)
                        val position = cursor.getInt(3)
                        val createdAt = cursor.getLong(4)
                        val contentType = cursor.getString(5)
                        val providerIds = providersByLegacyGroup[legacyGroupId]
                            ?.toList()
                            ?.sorted()
                            ?: defaultProviderId?.let(::listOf)
                            ?: emptyList()

                        providerIds.forEachIndexed { index, providerId ->
                            val newGroupId = if (index == 0) legacyGroupId else nextGroupId++
                            groupProviderToNewId[legacyGroupId to providerId] = newGroupId
                            database.execSQL(
                                """
                                INSERT INTO virtual_groups_new(
                                    id, provider_id, name, icon_emoji, position, created_at, content_type
                                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                                """.trimIndent(),
                                arrayOf(newGroupId, providerId, name, iconEmoji, position, createdAt, contentType)
                            )
                        }
                    }
                }

                database.execSQL("ALTER TABLE virtual_groups RENAME TO virtual_groups_legacy")
                database.execSQL("ALTER TABLE virtual_groups_new RENAME TO virtual_groups")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_virtual_groups_provider_id_content_type ON virtual_groups(provider_id, content_type)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_virtual_groups_position ON virtual_groups(position)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_virtual_groups_content_type ON virtual_groups(content_type)"
                )

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS favorites_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        provider_id INTEGER NOT NULL,
                        content_id INTEGER NOT NULL,
                        content_type TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        group_id INTEGER,
                        added_at INTEGER NOT NULL,
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE,
                        FOREIGN KEY(group_id) REFERENCES virtual_groups(id) ON DELETE SET NULL
                    )
                    """.trimIndent()
                )

                database.query(
                    """
                    SELECT f.id, f.content_id, f.content_type, f.position, f.group_id, f.added_at,
                           CASE f.content_type
                               WHEN 'LIVE' THEN (SELECT provider_id FROM channels WHERE id = f.content_id)
                               WHEN 'MOVIE' THEN (SELECT provider_id FROM movies WHERE id = f.content_id)
                               WHEN 'SERIES' THEN (SELECT provider_id FROM series WHERE id = f.content_id)
                               ELSE NULL
                           END AS provider_id
                    FROM favorites f
                    ORDER BY f.id ASC
                    """.trimIndent()
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val favoriteId = cursor.getLong(0)
                        val contentId = cursor.getLong(1)
                        val contentType = cursor.getString(2)
                        val position = cursor.getInt(3)
                        val legacyGroupId = if (cursor.isNull(4)) null else cursor.getLong(4)
                        val addedAt = cursor.getLong(5)
                        val providerId = when {
                            !cursor.isNull(6) -> cursor.getLong(6)
                            legacyGroupId != null -> groupProviderToNewId.keys
                                .firstOrNull { it.first == legacyGroupId }
                                ?.second
                            else -> null
                        } ?: continue

                        val newGroupId = legacyGroupId?.let { groupId ->
                            groupProviderToNewId[groupId to providerId]
                                ?: groupProviderToNewId.entries.firstOrNull { it.key.first == groupId }?.value
                        }

                        database.execSQL(
                            """
                            INSERT OR REPLACE INTO favorites_new(
                                id, provider_id, content_id, content_type, position, group_id, added_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            arrayOf(favoriteId, providerId, contentId, contentType, position, newGroupId, addedAt)
                        )
                    }
                }

                database.execSQL("ALTER TABLE favorites RENAME TO favorites_legacy")
                database.execSQL("ALTER TABLE favorites_new RENAME TO favorites")
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_favorites_provider_id_content_id_content_type_group_id ON favorites(provider_id, content_id, content_type, group_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_favorites_provider_id_content_type_group_id ON favorites(provider_id, content_type, group_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_favorites_group_id_position ON favorites(group_id, position)"
                )

                database.execSQL("DROP TABLE favorites_legacy")
                database.execSQL("DROP TABLE virtual_groups_legacy")
                validateForeignKeys(database, "virtual_groups", "favorites")
            }
        }

        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE movies ADD COLUMN watch_count INTEGER NOT NULL DEFAULT 0")
                database.execSQL(
                    """
                    UPDATE movies
                    SET watch_count = COALESCE((
                        SELECT playback_history.watch_count
                        FROM playback_history
                        WHERE playback_history.content_id = movies.id
                          AND playback_history.content_type = 'MOVIE'
                          AND playback_history.provider_id = movies.provider_id
                    ), 0)
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_favorites_global_provider_id_content_type_content_id
                    ON favorites(provider_id, content_type, content_id)
                    WHERE group_id IS NULL
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN last_live_success INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN last_series_success INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sync_metadata ADD COLUMN last_epg_success INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE sync_metadata SET last_live_success = last_live_sync")
                database.execSQL("UPDATE sync_metadata SET last_series_success = last_series_sync")
                database.execSQL("UPDATE sync_metadata SET last_epg_success = last_epg_sync")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_programs_provider_id_end_time_channel_id ON programs(provider_id, end_time, channel_id)"
                )
                // No FK-bearing rows added; only column additions and indexes.
            }
        }

        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS search_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        query TEXT NOT NULL,
                        content_scope TEXT NOT NULL,
                        provider_id INTEGER NOT NULL DEFAULT 0,
                        used_at INTEGER NOT NULL,
                        use_count INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_search_history_content_scope_provider_id_used_at ON search_history(content_scope, provider_id, used_at)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_search_history_used_at ON search_history(used_at)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_search_history_provider_id ON search_history(provider_id)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_search_history_query_content_scope_provider_id ON search_history(query, content_scope, provider_id)"
                )
                // search_history has no FK columns; no FK check needed.
            }
        }

        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE channel_epg_mappings ADD COLUMN matched_at INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE channel_epg_mappings ADD COLUMN failed_attempts INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE channel_epg_mappings ADD COLUMN source TEXT")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS program_reminders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        provider_id INTEGER NOT NULL,
                        channel_id TEXT NOT NULL,
                        channel_name TEXT NOT NULL,
                        program_title TEXT NOT NULL,
                        program_start_time INTEGER NOT NULL,
                        remind_at INTEGER NOT NULL,
                        lead_time_minutes INTEGER NOT NULL DEFAULT 5,
                        is_dismissed INTEGER NOT NULL DEFAULT 0,
                        notified_at INTEGER,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_program_reminders_provider_id_remind_at ON program_reminders(provider_id, remind_at)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_program_reminders_is_dismissed_notified_at_remind_at ON program_reminders(is_dismissed, notified_at, remind_at)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_program_reminders_provider_id_channel_id_program_start_time ON program_reminders(provider_id, channel_id, program_start_time)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_program_reminders_provider_id_channel_id_program_title_program_start_time ON program_reminders(provider_id, channel_id, program_title, program_start_time)"
                )
                validateForeignKeys(database, "program_reminders")
            }
        }

        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // ── epg_sources ──
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS epg_sources (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        url TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        last_refresh_at INTEGER NOT NULL DEFAULT 0,
                        last_success_at INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT,
                        priority INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_epg_sources_url ON epg_sources(url)")

                // ── provider_epg_sources ──
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS provider_epg_sources (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        provider_id INTEGER NOT NULL,
                        epg_source_id INTEGER NOT NULL,
                        priority INTEGER NOT NULL DEFAULT 0,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE,
                        FOREIGN KEY(epg_source_id) REFERENCES epg_sources(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_provider_epg_sources_provider_id_epg_source_id ON provider_epg_sources(provider_id, epg_source_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_provider_epg_sources_epg_source_id ON provider_epg_sources(epg_source_id)")

                // ── epg_channels ──
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS epg_channels (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        epg_source_id INTEGER NOT NULL,
                        xmltv_channel_id TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        normalized_name TEXT NOT NULL DEFAULT '',
                        icon_url TEXT,
                        FOREIGN KEY(epg_source_id) REFERENCES epg_sources(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_epg_channels_epg_source_id_xmltv_channel_id ON epg_channels(epg_source_id, xmltv_channel_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_epg_channels_epg_source_id ON epg_channels(epg_source_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_epg_channels_normalized_name ON epg_channels(normalized_name)")

                // ── epg_programmes ──
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS epg_programmes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        epg_source_id INTEGER NOT NULL,
                        xmltv_channel_id TEXT NOT NULL,
                        start_time INTEGER NOT NULL DEFAULT 0,
                        end_time INTEGER NOT NULL DEFAULT 0,
                        title TEXT NOT NULL,
                        subtitle TEXT,
                        description TEXT NOT NULL DEFAULT '',
                        category TEXT,
                        lang TEXT NOT NULL DEFAULT '',
                        rating TEXT,
                        image_url TEXT,
                        episode_info TEXT,
                        FOREIGN KEY(epg_source_id) REFERENCES epg_sources(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_epg_programmes_epg_source_id_xmltv_channel_id_start_time ON epg_programmes(epg_source_id, xmltv_channel_id, start_time)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_epg_programmes_epg_source_id_xmltv_channel_id_start_time_end_time ON epg_programmes(epg_source_id, xmltv_channel_id, start_time, end_time)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_epg_programmes_epg_source_id ON epg_programmes(epg_source_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_epg_programmes_start_time ON epg_programmes(start_time)")

                // ── channel_epg_mappings ──
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS channel_epg_mappings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        provider_channel_id INTEGER NOT NULL,
                        provider_id INTEGER NOT NULL,
                        source_type TEXT NOT NULL DEFAULT 'NONE',
                        epg_source_id INTEGER,
                        xmltv_channel_id TEXT,
                        match_type TEXT,
                        confidence REAL NOT NULL DEFAULT 0,
                        is_manual_override INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_channel_epg_mappings_provider_id_provider_channel_id ON channel_epg_mappings(provider_id, provider_channel_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_channel_epg_mappings_provider_id ON channel_epg_mappings(provider_id)")
            }
        }

        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tmdb_identity (
                        tmdb_id INTEGER NOT NULL,
                        content_type TEXT NOT NULL,
                        canonical_provider_id INTEGER NOT NULL,
                        first_seen_at INTEGER NOT NULL,
                        PRIMARY KEY (tmdb_id, content_type),
                        FOREIGN KEY(canonical_provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_tmdb_identity_content_type ON tmdb_identity(content_type)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_tmdb_identity_canonical_provider_id ON tmdb_identity(canonical_provider_id)")
                database.execSQL(
                    """
                    INSERT OR REPLACE INTO tmdb_identity (tmdb_id, content_type, canonical_provider_id, first_seen_at)
                    SELECT tmdb_id, 'MOVIE', MIN(provider_id), 0
                    FROM movies
                    WHERE tmdb_id IS NOT NULL
                    GROUP BY tmdb_id
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT OR REPLACE INTO tmdb_identity (tmdb_id, content_type, canonical_provider_id, first_seen_at)
                    SELECT tmdb_id, 'SERIES', MIN(provider_id), 0
                    FROM series
                    WHERE tmdb_id IS NOT NULL
                    GROUP BY tmdb_id
                    """.trimIndent()
                )
                validateForeignKeys(database, "tmdb_identity")
            }
        }

        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE epg_sources ADD COLUMN etag TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE epg_sources ADD COLUMN last_modified_header TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE providers ADD COLUMN stalker_mac_address TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE providers ADD COLUMN stalker_device_profile TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE providers ADD COLUMN stalker_device_timezone TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE providers ADD COLUMN stalker_device_locale TEXT NOT NULL DEFAULT ''")
                database.execSQL("DROP INDEX IF EXISTS index_providers_server_url_username")
                database.execSQL("DROP INDEX IF EXISTS index_providers_server_url_username_stalker_mac_address")
                database.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_providers_server_url_username_stalker_mac_address
                    ON providers(server_url, username, stalker_mac_address)
                    """.trimIndent()
                )
            }
        }

        /**
         * Migration 39 → 40: Drop per-row FTS triggers.
         * The triggers (channels_ai/ad/au, movies_ai/ad/au, series_ai/ad/au) fired for every
         * individual row INSERT/DELETE/UPDATE and serialised 52k+ writes through the FTS index
         * one row at a time, causing minute-long first-sync freezes on large providers.
         * FTS is now rebuilt in bulk via INSERT INTO table_fts(table_fts) VALUES('rebuild')
         * once per sync, after each catalog transaction commits.
         */
        val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TRIGGER IF EXISTS channels_ai")
                database.execSQL("DROP TRIGGER IF EXISTS channels_ad")
                database.execSQL("DROP TRIGGER IF EXISTS channels_au")
                database.execSQL("DROP TRIGGER IF EXISTS movies_ai")
                database.execSQL("DROP TRIGGER IF EXISTS movies_ad")
                database.execSQL("DROP TRIGGER IF EXISTS movies_au")
                database.execSQL("DROP TRIGGER IF EXISTS series_ai")
                database.execSQL("DROP TRIGGER IF EXISTS series_ad")
                database.execSQL("DROP TRIGGER IF EXISTS series_au")
            }
        }

        /**
         * Migration 40 → 41: add per-channel A/V sync override.
         * Null means the channel follows the global player default.
         */
        val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE channel_preferences ADD COLUMN audio_video_offset_ms INTEGER DEFAULT NULL")
            }
        }

        /**
         * Migration 41 -> 42: remember playback decoder/surface combinations that fail
         * silently on a device so Auto mode can avoid repeating them.
         */
        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playback_compatibility_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        device_fingerprint TEXT NOT NULL,
                        device_model TEXT NOT NULL,
                        android_sdk INTEGER NOT NULL,
                        stream_type TEXT NOT NULL,
                        video_mime_type TEXT NOT NULL,
                        resolution_bucket TEXT NOT NULL,
                        decoder_name TEXT NOT NULL,
                        surface_type TEXT NOT NULL,
                        failure_type TEXT NOT NULL DEFAULT '',
                        last_failed_at INTEGER NOT NULL DEFAULT 0,
                        last_succeeded_at INTEGER NOT NULL DEFAULT 0,
                        failure_count INTEGER NOT NULL DEFAULT 0,
                        success_count INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_playback_compatibility_records_device_fingerprint_stream_type_video_mime_type_resolution_bucket_decoder_name_surface_type
                    ON playback_compatibility_records(device_fingerprint, stream_type, video_mime_type, resolution_bucket, decoder_name, surface_type)
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_playback_compatibility_records_device_fingerprint_stream_type_video_mime_type_resolution_bucket
                    ON playback_compatibility_records(device_fingerprint, stream_type, video_mime_type, resolution_bucket)
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_playback_compatibility_records_last_failed_at ON playback_compatibility_records(last_failed_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_playback_compatibility_records_last_succeeded_at ON playback_compatibility_records(last_succeeded_at)")
            }
        }

        /**
         * Migration 42 -> 43: preserve provider-native series identifiers so Stalker
         * series details can round-trip composite portal IDs.
         */
        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE series ADD COLUMN provider_series_id TEXT")
                database.execSQL(
                    "UPDATE series SET provider_series_id = CAST(series_id AS TEXT) WHERE provider_series_id IS NULL"
                )
            }
        }

        /**
         * Migration 43 -> 44: add page-aware VOD/series category hydration metadata
         * for on-demand Stalker paging while preserving existing complete caches.
         */
        val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addPagedHydrationColumns(database, "movie_category_hydration")
                addPagedHydrationColumns(database, "series_category_hydration")
            }

            private fun addPagedHydrationColumns(database: SupportSQLiteDatabase, tableName: String) {
                database.execSQL("ALTER TABLE $tableName ADD COLUMN last_loaded_page INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE $tableName ADD COLUMN total_pages INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE $tableName ADD COLUMN is_complete INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE $tableName ADD COLUMN page_size INTEGER NOT NULL DEFAULT 0")
                database.execSQL(
                    """
                    UPDATE $tableName
                    SET last_loaded_page = CASE
                            WHEN last_status = 'SUCCESS' THEN 1
                            ELSE 0
                        END,
                        total_pages = CASE
                            WHEN last_status = 'SUCCESS' THEN 1
                            ELSE 0
                        END,
                        is_complete = CASE
                            WHEN last_status = 'SUCCESS' THEN 1
                            ELSE 0
                        END,
                        page_size = CASE
                            WHEN last_status = 'SUCCESS' THEN item_count
                            ELSE 0
                        END
                    """.trimIndent()
                )
            }
        }

        /**
         * Migration 44 -> 45: make favorite uniqueness null-safe by materializing a non-null
         * group scope key and deduping any pre-existing global favorite collisions.
         */
        val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE favorites ADD COLUMN group_key INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE favorites SET group_key = COALESCE(group_id, 0)")
                database.execSQL(
                    """
                    UPDATE favorites
                    SET position = (
                            SELECT MIN(dupe.position)
                            FROM favorites AS dupe
                            WHERE dupe.group_id IS NULL
                              AND dupe.provider_id = favorites.provider_id
                              AND dupe.content_id = favorites.content_id
                              AND dupe.content_type = favorites.content_type
                        ),
                        added_at = (
                            SELECT MIN(dupe.added_at)
                            FROM favorites AS dupe
                            WHERE dupe.group_id IS NULL
                              AND dupe.provider_id = favorites.provider_id
                              AND dupe.content_id = favorites.content_id
                              AND dupe.content_type = favorites.content_type
                        )
                    WHERE favorites.group_id IS NULL
                      AND favorites.id IN (
                          SELECT MIN(id)
                          FROM favorites
                          WHERE group_id IS NULL
                          GROUP BY provider_id, content_id, content_type
                          HAVING COUNT(*) > 1
                      )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    DELETE FROM favorites
                    WHERE group_id IS NULL
                      AND id NOT IN (
                          SELECT MIN(id)
                          FROM favorites
                          WHERE group_id IS NULL
                          GROUP BY provider_id, content_id, content_type
                      )
                    """.trimIndent()
                )

                database.execSQL("DROP INDEX IF EXISTS index_favorites_provider_id_content_id_content_type_group_id")
                database.execSQL("DROP TABLE IF EXISTS favorites_new")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS favorites_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        provider_id INTEGER NOT NULL,
                        content_id INTEGER NOT NULL,
                        content_type TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        group_id INTEGER,
                        group_key INTEGER NOT NULL,
                        added_at INTEGER NOT NULL,
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE,
                        FOREIGN KEY(group_id) REFERENCES virtual_groups(id) ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO favorites_new(
                        id,
                        provider_id,
                        content_id,
                        content_type,
                        position,
                        group_id,
                        group_key,
                        added_at
                    )
                    SELECT
                        id,
                        provider_id,
                        content_id,
                        content_type,
                        position,
                        group_id,
                        group_key,
                        added_at
                    FROM favorites
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE favorites")
                database.execSQL("ALTER TABLE favorites_new RENAME TO favorites")
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_favorites_provider_id_content_id_content_type_group_key ON favorites(provider_id, content_id, content_type, group_key)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_favorites_provider_id_content_type_group_id ON favorites(provider_id, content_type, group_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_favorites_group_id_position ON favorites(group_id, position)"
                )
                validateForeignKeys(database, "favorites")
            }
        }

        /**
         * Migration 45 -> 46: preserve provider-native series IDs through staging by storing both
         * the raw provider ID and a non-null remote key used for staged apply matching.
         */
        val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS series_import_stage_new (
                        session_id INTEGER NOT NULL,
                        provider_id INTEGER NOT NULL,
                        series_id INTEGER NOT NULL,
                        provider_series_id TEXT,
                        provider_series_key TEXT NOT NULL,
                        name TEXT NOT NULL,
                        poster_url TEXT,
                        backdrop_url TEXT,
                        category_id INTEGER,
                        category_name TEXT,
                        plot TEXT,
                        "cast" TEXT,
                        director TEXT,
                        genre TEXT,
                        release_date TEXT,
                        rating REAL NOT NULL,
                        tmdb_id INTEGER,
                        youtube_trailer TEXT,
                        episode_run_time TEXT,
                        last_modified INTEGER NOT NULL,
                        is_adult INTEGER NOT NULL,
                        sync_fingerprint TEXT NOT NULL,
                        PRIMARY KEY(session_id, provider_id, provider_series_key),
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO series_import_stage_new (
                        session_id,
                        provider_id,
                        series_id,
                        provider_series_id,
                        provider_series_key,
                        name,
                        poster_url,
                        backdrop_url,
                        category_id,
                        category_name,
                        plot,
                        "cast",
                        director,
                        genre,
                        release_date,
                        rating,
                        tmdb_id,
                        youtube_trailer,
                        episode_run_time,
                        last_modified,
                        is_adult,
                        sync_fingerprint
                    )
                    SELECT
                        session_id,
                        provider_id,
                        series_id,
                        NULL,
                        CAST(series_id AS TEXT),
                        name,
                        poster_url,
                        backdrop_url,
                        category_id,
                        category_name,
                        plot,
                        "cast",
                        director,
                        genre,
                        release_date,
                        rating,
                        tmdb_id,
                        youtube_trailer,
                        episode_run_time,
                        last_modified,
                        is_adult,
                        sync_fingerprint
                    FROM series_import_stage
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE series_import_stage")
                database.execSQL("ALTER TABLE series_import_stage_new RENAME TO series_import_stage")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_series_import_stage_provider_id ON series_import_stage(provider_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_series_import_stage_session_id_provider_id ON series_import_stage(session_id, provider_id)")
                validateForeignKeys(database, "series_import_stage")
            }
        }

        /**
         * Migration 46 -> 47: add provider-leading browse indexes so large-provider cursor pages,
         * category/rating sorts, and correlated playback-history filters avoid wide scans.
         */
        val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS index_movies_provider_id_name_id ON movies(provider_id, name, id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_movies_provider_id_category_id_name_id ON movies(provider_id, category_id, name, id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_movies_provider_id_rating_name_id ON movies(provider_id, rating, name, id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_movies_provider_id_added_at_release_date_name_id ON movies(provider_id, added_at, release_date, name, id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_series_provider_id_name_id ON series(provider_id, name, id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_series_provider_id_category_id_name_id ON series(provider_id, category_id, name, id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_series_provider_id_rating_name_id ON series(provider_id, rating, name, id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_series_provider_id_last_modified_name_id ON series(provider_id, last_modified, name, id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_playback_history_provider_id_content_type_content_id ON playback_history(provider_id, content_type, content_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_playback_history_provider_id_content_type_last_watched_at ON playback_history(provider_id, content_type, last_watched_at)")
            }
        }

        /**
         * Migration 47 -> 48: create the Xtream summary index and section job state tables.
         * Existing Xtream live/movie/series rows are backfilled without deleting or remapping
         * the playable/detail tables that favorites and history already reference.
         */
        val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE movies ADD COLUMN cache_state TEXT NOT NULL DEFAULT 'DETAIL_HYDRATED'")
                database.execSQL("ALTER TABLE movies ADD COLUMN detail_hydrated_at INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE movies ADD COLUMN remote_stale_at INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE series ADD COLUMN cache_state TEXT NOT NULL DEFAULT 'DETAIL_HYDRATED'")
                database.execSQL("ALTER TABLE series ADD COLUMN detail_hydrated_at INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE series ADD COLUMN remote_stale_at INTEGER NOT NULL DEFAULT 0")

                database.execSQL(
                    """
                    UPDATE movies
                    SET cache_state = 'SUMMARY_ONLY'
                    WHERE COALESCE(plot, '') = ''
                      AND COALESCE("cast", '') = ''
                      AND COALESCE(director, '') = ''
                      AND COALESCE(genre, '') = ''
                      AND COALESCE(duration, '') = ''
                      AND duration_seconds = 0
                      AND tmdb_id IS NULL
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE series
                    SET cache_state = 'SUMMARY_ONLY'
                    WHERE COALESCE(plot, '') = ''
                      AND COALESCE("cast", '') = ''
                      AND COALESCE(director, '') = ''
                      AND COALESCE(genre, '') = ''
                      AND COALESCE(episode_run_time, '') = ''
                      AND tmdb_id IS NULL
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE movies
                    SET detail_hydrated_at = COALESCE(
                        (SELECT providers.last_synced_at FROM providers WHERE providers.id = movies.provider_id),
                        0
                    )
                    WHERE cache_state = 'DETAIL_HYDRATED'
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE series
                    SET detail_hydrated_at = COALESCE(
                        (SELECT providers.last_synced_at FROM providers WHERE providers.id = series.provider_id),
                        0
                    )
                    WHERE cache_state = 'DETAIL_HYDRATED'
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS xtream_content_index (
                        provider_id INTEGER NOT NULL,
                        content_type TEXT NOT NULL,
                        remote_id TEXT NOT NULL,
                        local_content_id INTEGER,
                        name TEXT NOT NULL,
                        category_id INTEGER,
                        category_name TEXT,
                        image_url TEXT,
                        container_extension TEXT,
                        rating REAL NOT NULL,
                        added_at INTEGER NOT NULL,
                        remote_updated_at INTEGER NOT NULL,
                        is_adult INTEGER NOT NULL,
                        indexed_at INTEGER NOT NULL,
                        detail_hydrated_at INTEGER NOT NULL,
                        stale_state TEXT NOT NULL,
                        error_state TEXT,
                        sync_fingerprint TEXT NOT NULL,
                        PRIMARY KEY(provider_id, content_type, remote_id),
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_content_index_provider_id_content_type ON xtream_content_index(provider_id, content_type)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_content_index_provider_id_content_type_category_id ON xtream_content_index(provider_id, content_type, category_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_content_index_provider_id_content_type_name ON xtream_content_index(provider_id, content_type, name)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_content_index_provider_id_content_type_local_content_id ON xtream_content_index(provider_id, content_type, local_content_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_content_index_provider_id_indexed_at ON xtream_content_index(provider_id, indexed_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_content_index_stale_state ON xtream_content_index(stale_state)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS xtream_index_jobs (
                        provider_id INTEGER NOT NULL,
                        section TEXT NOT NULL,
                        state TEXT NOT NULL,
                        total_categories INTEGER NOT NULL,
                        completed_categories INTEGER NOT NULL,
                        next_category_index INTEGER NOT NULL,
                        failed_categories INTEGER NOT NULL,
                        indexed_rows INTEGER NOT NULL,
                        skipped_malformed_rows INTEGER NOT NULL,
                        deleted_pruned_rows INTEGER NOT NULL,
                        priority_category_id INTEGER,
                        priority_requested_at INTEGER NOT NULL,
                        last_error TEXT,
                        last_attempt_at INTEGER NOT NULL,
                        last_success_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(provider_id, section),
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_index_jobs_provider_id ON xtream_index_jobs(provider_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_index_jobs_section ON xtream_index_jobs(section)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_index_jobs_state ON xtream_index_jobs(state)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_index_jobs_updated_at ON xtream_index_jobs(updated_at)")

                database.execSQL(
                    """
                    INSERT OR REPLACE INTO xtream_content_index (
                        provider_id, content_type, remote_id, local_content_id, name, category_id,
                        category_name, image_url, container_extension, rating, added_at, remote_updated_at,
                        is_adult, indexed_at, detail_hydrated_at, stale_state, error_state, sync_fingerprint
                    )
                    SELECT
                        c.provider_id,
                        'LIVE',
                        CAST(c.stream_id AS TEXT),
                        c.id,
                        c.name,
                        c.category_id,
                        c.category_name,
                        c.logo_url,
                        NULL,
                        0,
                        0,
                        0,
                        c.is_adult,
                        COALESCE(p.last_synced_at, 0),
                        COALESCE(p.last_synced_at, 0),
                        'ACTIVE',
                        NULL,
                        c.sync_fingerprint
                    FROM channels c
                    JOIN providers p ON p.id = c.provider_id
                    WHERE p.type = 'XTREAM_CODES'
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT OR REPLACE INTO xtream_content_index (
                        provider_id, content_type, remote_id, local_content_id, name, category_id,
                        category_name, image_url, container_extension, rating, added_at, remote_updated_at,
                        is_adult, indexed_at, detail_hydrated_at, stale_state, error_state, sync_fingerprint
                    )
                    SELECT
                        m.provider_id,
                        'MOVIE',
                        CAST(m.stream_id AS TEXT),
                        m.id,
                        m.name,
                        m.category_id,
                        m.category_name,
                        m.poster_url,
                        m.container_extension,
                        m.rating,
                        m.added_at,
                        0,
                        m.is_adult,
                        COALESCE(p.last_synced_at, 0),
                        CASE WHEN m.cache_state = 'DETAIL_HYDRATED' THEN COALESCE(p.last_synced_at, 0) ELSE 0 END,
                        'ACTIVE',
                        NULL,
                        m.sync_fingerprint
                    FROM movies m
                    JOIN providers p ON p.id = m.provider_id
                    WHERE p.type = 'XTREAM_CODES'
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT OR REPLACE INTO xtream_content_index (
                        provider_id, content_type, remote_id, local_content_id, name, category_id,
                        category_name, image_url, container_extension, rating, added_at, remote_updated_at,
                        is_adult, indexed_at, detail_hydrated_at, stale_state, error_state, sync_fingerprint
                    )
                    SELECT
                        s.provider_id,
                        'SERIES',
                        COALESCE(s.provider_series_id, CAST(s.series_id AS TEXT)),
                        s.id,
                        s.name,
                        s.category_id,
                        s.category_name,
                        s.poster_url,
                        NULL,
                        s.rating,
                        0,
                        s.last_modified,
                        s.is_adult,
                        COALESCE(p.last_synced_at, 0),
                        CASE WHEN s.cache_state = 'DETAIL_HYDRATED' THEN COALESCE(p.last_synced_at, 0) ELSE 0 END,
                        'ACTIVE',
                        NULL,
                        s.sync_fingerprint
                    FROM series s
                    JOIN providers p ON p.id = s.provider_id
                    WHERE p.type = 'XTREAM_CODES'
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    INSERT OR REPLACE INTO xtream_index_jobs (
                        provider_id, section, state, total_categories, completed_categories, next_category_index, failed_categories,
                        indexed_rows, skipped_malformed_rows, deleted_pruned_rows, priority_category_id, priority_requested_at, last_error,
                        last_attempt_at, last_success_at, updated_at
                    )
                    SELECT
                        p.id,
                        section.name,
                        CASE
                            WHEN section.name = 'LIVE' AND (
                                COALESCE(NULLIF(sm.last_live_success, 0), NULLIF(sm.last_live_sync, 0), 0) > 0
                                OR (SELECT COUNT(*) FROM channels c WHERE c.provider_id = p.id) > 0
                            ) THEN 'SUCCESS'
                            WHEN section.name = 'MOVIE' AND (
                                COALESCE(NULLIF(sm.last_movie_success, 0), NULLIF(sm.last_movie_sync, 0), 0) > 0
                                OR (SELECT COUNT(*) FROM movies m WHERE m.provider_id = p.id) > 0
                            ) THEN 'SUCCESS'
                            WHEN section.name = 'SERIES' AND (
                                COALESCE(NULLIF(sm.last_series_success, 0), NULLIF(sm.last_series_sync, 0), 0) > 0
                                OR (SELECT COUNT(*) FROM series s WHERE s.provider_id = p.id) > 0
                            ) THEN 'SUCCESS'
                            WHEN section.name = 'EPG' AND COALESCE(NULLIF(sm.last_epg_success, 0), NULLIF(sm.last_epg_sync, 0), 0) > 0 THEN 'SUCCESS'
                            ELSE 'IDLE'
                        END,
                        CASE section.name
                            WHEN 'LIVE' THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'LIVE')
                            WHEN 'MOVIE' THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'MOVIE')
                            WHEN 'SERIES' THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'SERIES')
                            ELSE 0
                        END,
                        CASE
                            WHEN section.name = 'LIVE' AND (SELECT COUNT(*) FROM channels c WHERE c.provider_id = p.id) > 0
                                THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'LIVE')
                            WHEN section.name = 'MOVIE' AND (SELECT COUNT(*) FROM movies m WHERE m.provider_id = p.id) > 0
                                THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'MOVIE')
                            WHEN section.name = 'SERIES' AND (SELECT COUNT(*) FROM series s WHERE s.provider_id = p.id) > 0
                                THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'SERIES')
                            ELSE 0
                        END,
                        CASE
                            WHEN section.name = 'LIVE' AND (SELECT COUNT(*) FROM channels c WHERE c.provider_id = p.id) > 0
                                THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'LIVE')
                            WHEN section.name = 'MOVIE' AND (SELECT COUNT(*) FROM movies m WHERE m.provider_id = p.id) > 0
                                THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'MOVIE')
                            WHEN section.name = 'SERIES' AND (SELECT COUNT(*) FROM series s WHERE s.provider_id = p.id) > 0
                                THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'SERIES')
                            ELSE 0
                        END,
                        0,
                        CASE section.name
                            WHEN 'LIVE' THEN (SELECT COUNT(*) FROM channels c WHERE c.provider_id = p.id)
                            WHEN 'MOVIE' THEN (SELECT COUNT(*) FROM movies m WHERE m.provider_id = p.id)
                            WHEN 'SERIES' THEN (SELECT COUNT(*) FROM series s WHERE s.provider_id = p.id)
                            WHEN 'EPG' THEN COALESCE(sm.epg_count, 0)
                            ELSE 0
                        END,
                        0,
                        0,
                        NULL,
                        0,
                        NULL,
                        CASE section.name
                            WHEN 'LIVE' THEN COALESCE(sm.last_live_sync, 0)
                            WHEN 'MOVIE' THEN COALESCE(NULLIF(sm.last_movie_attempt, 0), sm.last_movie_sync, 0)
                            WHEN 'SERIES' THEN COALESCE(sm.last_series_sync, 0)
                            WHEN 'EPG' THEN COALESCE(sm.last_epg_sync, 0)
                            ELSE 0
                        END,
                        CASE section.name
                            WHEN 'LIVE' THEN COALESCE(NULLIF(sm.last_live_success, 0), sm.last_live_sync, 0)
                            WHEN 'MOVIE' THEN COALESCE(NULLIF(sm.last_movie_success, 0), sm.last_movie_sync, 0)
                            WHEN 'SERIES' THEN COALESCE(NULLIF(sm.last_series_success, 0), sm.last_series_sync, 0)
                            WHEN 'EPG' THEN COALESCE(NULLIF(sm.last_epg_success, 0), sm.last_epg_sync, 0)
                            ELSE 0
                        END,
                        COALESCE(p.last_synced_at, 0)
                    FROM providers p
                    CROSS JOIN (
                        SELECT 'LIVE' AS name
                        UNION ALL SELECT 'MOVIE'
                        UNION ALL SELECT 'SERIES'
                        UNION ALL SELECT 'EPG'
                    ) section
                    LEFT JOIN sync_metadata sm ON sm.provider_id = p.id
                    WHERE p.type = 'XTREAM_CODES'
                    """.trimIndent()
                )

                validateForeignKeys(database, "xtream_content_index", "xtream_index_jobs")
            }
        }

        val MIGRATION_48_49 = object : Migration(48, 49) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE providers ADD COLUMN http_user_agent TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE providers ADD COLUMN http_headers TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_49_50 = object : Migration(49, 50) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS xtream_live_onboarding_state (
                        provider_id INTEGER NOT NULL,
                        provider_type TEXT NOT NULL DEFAULT 'XTREAM_CODES',
                        content_type TEXT NOT NULL DEFAULT 'LIVE',
                        phase TEXT NOT NULL DEFAULT 'STARTING',
                        staged_session_id INTEGER,
                        import_strategy TEXT,
                        next_category_index INTEGER NOT NULL DEFAULT 0,
                        accepted_row_count INTEGER NOT NULL DEFAULT 0,
                        staged_flush_count INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0,
                        completed_at INTEGER,
                        PRIMARY KEY(provider_id),
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_live_onboarding_state_provider_id ON xtream_live_onboarding_state(provider_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_live_onboarding_state_phase ON xtream_live_onboarding_state(phase)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_live_onboarding_state_updated_at ON xtream_live_onboarding_state(updated_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_live_onboarding_state_staged_session_id ON xtream_live_onboarding_state(staged_session_id)")

                validateForeignKeys(database, "xtream_live_onboarding_state")
            }
        }

        val MIGRATION_50_51 = object : Migration(50, 51) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sync_profile_tier TEXT")
                database.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sync_profile_batch_size INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sync_profile_strategy TEXT")
                database.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sync_profile_low_memory INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sync_profile_memory_class_mb INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sync_profile_available_mem_mb INTEGER NOT NULL DEFAULT 0")
                validateForeignKeys(database, "xtream_live_onboarding_state")
            }
        }

        val MIGRATION_51_52 = object : Migration(51, 52) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE providers ADD COLUMN xtream_live_sync_mode TEXT NOT NULL DEFAULT 'AUTO'")
                validateForeignKeys(database, "providers")
            }
        }

        val MIGRATION_55_56 = object : Migration(55, 56) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE providers ADD COLUMN stalker_portal_fingerprint TEXT NOT NULL DEFAULT 'BASIC_MAC'"
                )
                database.execSQL(
                    "ALTER TABLE providers ADD COLUMN stalker_mag_preset TEXT NOT NULL DEFAULT 'GENERIC_SAFE'"
                )
                database.execSQL(
                    "ALTER TABLE providers ADD COLUMN stalker_last_bootstrap_recipe TEXT NOT NULL DEFAULT 'GENERIC_SAFE'"
                )
                database.execSQL(
                    "ALTER TABLE providers ADD COLUMN stalker_strict_fingerprint_required INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE providers ADD COLUMN stalker_recipe_fallback_used INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE providers ADD COLUMN stalker_recipe_rediscovery_attempts INTEGER NOT NULL DEFAULT 0"
                )
                validateForeignKeys(database, "providers")
            }
        }

        val MIGRATION_56_57 = object : Migration(56, 57) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE providers ADD COLUMN stalker_endpoint_preference TEXT NOT NULL DEFAULT 'AUTO'"
                )
                database.execSQL(
                    "ALTER TABLE providers ADD COLUMN stalker_cookie_mode TEXT NOT NULL DEFAULT 'NONE'"
                )
                database.execSQL(
                    "ALTER TABLE providers ADD COLUMN stalker_playback_backend_hint TEXT NOT NULL DEFAULT 'AUTO'"
                )
                validateForeignKeys(database, "providers")
            }
        }

        /**
         * Migration 57 → 58: add downloads table for tracking in-app download operations.
         */
        val MIGRATION_57_58 = object : Migration(57, 58) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS downloads (
                        id TEXT NOT NULL PRIMARY KEY,
                        provider_id INTEGER NOT NULL,
                        content_type TEXT NOT NULL,
                        content_id INTEGER NOT NULL,
                        content_name TEXT NOT NULL,
                        stream_url TEXT NOT NULL,
                        source_stream_url TEXT,
                        source_stream_id INTEGER,
                        container_extension TEXT,
                        poster_url TEXT,
                        output_uri TEXT,
                        output_display_path TEXT,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        bytes_written INTEGER NOT NULL DEFAULT 0,
                        total_bytes INTEGER,
                        created_at INTEGER NOT NULL,
                        completed_at INTEGER,
                        failure_reason TEXT,
                        series_id INTEGER,
                        season_number INTEGER,
                        episode_number INTEGER
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_status ON downloads(status)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_provider_id ON downloads(provider_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_content_type_content_id ON downloads(content_type, content_id)")
            }
        }

        val MIGRATION_58_59 = object : Migration(58, 59) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE downloads ADD COLUMN supports_resume INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE downloads ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_59_60 = object : Migration(59, 60) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(database, "downloads", "source_stream_url", "TEXT")
                addColumnIfMissing(database, "downloads", "source_stream_id", "INTEGER")
                addColumnIfMissing(database, "downloads", "container_extension", "TEXT")
            }
        }

        val MIGRATION_52_53 = object : Migration(52, 53) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addStalkerHardeningColumns(database, "movie_category_hydration")
                addStalkerHardeningColumns(database, "series_category_hydration")
                validateForeignKeys(database, "movie_category_hydration")
                validateForeignKeys(database, "series_category_hydration")
            }
        }

        val MIGRATION_53_54 = object : Migration(53, 54) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE providers ADD COLUMN stalker_serial_number TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE providers ADD COLUMN stalker_device_id TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE providers ADD COLUMN stalker_device_id2 TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE providers ADD COLUMN stalker_signature TEXT NOT NULL DEFAULT ''")
                validateForeignKeys(database, "providers")
            }
        }

        val MIGRATION_54_55 = object : Migration(54, 55) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE providers ADD COLUMN stalker_auth_mode TEXT NOT NULL DEFAULT 'AUTO'")
                database.execSQL("ALTER TABLE providers ADD COLUMN stalker_portal_profile TEXT NOT NULL DEFAULT 'MAG_BASIC'")
                database.execSQL("ALTER TABLE providers ADD COLUMN stalker_last_playback_mode TEXT")
                database.execSQL("ALTER TABLE providers ADD COLUMN stalker_credentials_required INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE providers ADD COLUMN stalker_mac_required INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE providers ADD COLUMN stalker_uses_temp_links INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE providers ADD COLUMN stalker_module_restricted INTEGER NOT NULL DEFAULT 0")
                validateForeignKeys(database, "providers")
            }
        }

        private fun addStalkerHardeningColumns(database: SupportSQLiteDatabase, tableName: String) {
            database.execSQL("ALTER TABLE $tableName ADD COLUMN last_attempted_page INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE $tableName ADD COLUMN last_successful_page INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE $tableName ADD COLUMN retry_after_ms INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE $tableName ADD COLUMN failure_count INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE $tableName ADD COLUMN retry_budget_remaining INTEGER NOT NULL DEFAULT 3")
            database.execSQL("ALTER TABLE $tableName ADD COLUMN last_page_fingerprint TEXT")
        }

        private fun addColumnIfMissing(
            database: SupportSQLiteDatabase,
            tableName: String,
            columnName: String,
            columnDefinition: String
        ) {
            if (tableHasColumn(database, tableName, columnName)) {
                return
            }
            database.execSQL("ALTER TABLE $tableName ADD COLUMN $columnName $columnDefinition")
        }

        private fun tableHasColumn(
            database: SupportSQLiteDatabase,
            tableName: String,
            columnName: String
        ): Boolean {
            database.query("PRAGMA table_info($tableName)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && cursor.getString(nameIndex) == columnName) {
                        return true
                    }
                }
            }
            return false
        }
    }
}
