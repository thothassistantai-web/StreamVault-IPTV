package com.streamvault.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamVaultDatabaseMigrationTest {

    private val testDbName = "streamvault-migration-test"

    @get:Rule
    val migrationTestHelper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StreamVaultDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate9To10_createsBackfillsAndMaintainsFtsTables() {
        migrationTestHelper.createDatabase(testDbName, 9).apply {
            execSQL(
                """
                INSERT INTO channels (
                    stream_id, name, stream_url, number, catch_up_supported, catch_up_days,
                    provider_id, is_adult, is_user_protected, logical_group_id, error_count
                ) VALUES (1001, 'News One', 'http://test/live/news', 101, 0, 0, 1, 0, 0, '', 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO movies (
                    stream_id, name, stream_url, duration_seconds, rating, provider_id,
                    watch_progress, last_watched_at, is_adult, is_user_protected
                ) VALUES (2001, 'Movie Alpha', 'http://test/movie/alpha', 7200, 7.5, 1, 0, 0, 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO series (
                    series_id, name, rating, last_modified, provider_id, is_adult, is_user_protected
                ) VALUES (3001, 'Series Prime', 8.1, 0, 1, 0, 0)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            testDbName,
            10,
            true,
            StreamVaultDatabase.MIGRATION_9_10
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM channels_fts WHERE channels_fts MATCH 'news*'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM movies_fts WHERE movies_fts MATCH 'movie*'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM series_fts WHERE series_fts MATCH 'series*'"))

        migratedDb.execSQL(
            """
            INSERT INTO channels (
                stream_id, name, stream_url, number, catch_up_supported, catch_up_days,
                provider_id, is_adult, is_user_protected, logical_group_id, error_count
            ) VALUES (1002, 'Sports Arena', 'http://test/live/sports', 102, 0, 0, 1, 0, 0, '', 0)
            """.trimIndent()
        )
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM channels_fts WHERE channels_fts MATCH 'sports*'"))

        migratedDb.execSQL("UPDATE channels SET name = 'Sports Central' WHERE stream_id = 1002")
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM channels_fts WHERE channels_fts MATCH 'central*'"))

        migratedDb.execSQL("DELETE FROM channels WHERE stream_id = 1002")
        assertFalse(exists(migratedDb, "SELECT 1 FROM channels_fts WHERE channels_fts MATCH 'central*'"))

        migratedDb.close()
    }

    @Test
    fun migrate1To39_fullChainValidatesLatestSchema() {
        migrationTestHelper.createDatabase("streamvault-full-chain-test", 1).close()

        migrationTestHelper.runMigrationsAndValidate(
            "streamvault-full-chain-test",
            39,
            true,
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
        ).close()
    }

    @Test
    fun migrate38To39_addsStalkerProviderColumnsAndUniqueIndex() {
        migrationTestHelper.createDatabase("streamvault-38-39-test", 38).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    is_active, max_connections, allowed_output_formats_json, epg_sync_mode,
                    xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES (1, 'Provider', 'XTREAM_CODES', 'https://provider.example.com', 'demo', 'secret', '', '', 1, 1, '[]', 'UPFRONT', 1, 0, 'ACTIVE', 0, 0)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-38-39-test",
            39,
            true,
            StreamVaultDatabase.MIGRATION_38_39
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_mac_address'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_device_profile'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_device_timezone'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_device_locale'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM providers WHERE id = 1 AND stalker_mac_address = ''"))
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_providers_server_url_username_stalker_mac_address'"
            )
        )

        migratedDb.close()
    }

    @Test
    fun migrate32To33_backfillsMovieWatchCountAndAddsGlobalFavoritesIndex() {
        migrationTestHelper.createDatabase("streamvault-32-33-test", 32).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    is_active, max_connections, allowed_output_formats_json, epg_sync_mode,
                    xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES (1, 'Provider', 'XTREAM_CODES', 'https://provider.example.com', 'demo', 'secret', '', '', 1, 1, '[]', 'PROVIDER', 0, 0, 'ACTIVE', 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO movies (
                    id, stream_id, name, stream_url, duration_seconds, rating, provider_id,
                    watch_progress, last_watched_at, is_adult, is_user_protected, sync_fingerprint, added_at
                ) VALUES (10, 2001, 'Movie', 'https://provider.example.com/movie.mp4', 7200, 7.5, 1, 0, 0, 0, 0, 'fp-10', 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO playback_history (
                    content_id, content_type, provider_id, title, poster_url, stream_url,
                    resume_position_ms, total_duration_ms, last_watched_at, watch_count, watched_status,
                    series_id, season_number, episode_number
                ) VALUES (10, 'MOVIE', 1, 'Movie', NULL, 'https://provider.example.com/movie.mp4', 1200, 7200, 55, 6, 'IN_PROGRESS', NULL, NULL, NULL)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO favorites (id, provider_id, content_id, content_type, position, group_id, added_at)
                VALUES (1, 1, 10, 'MOVIE', 0, NULL, 0)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-32-33-test",
            33,
            true,
            StreamVaultDatabase.MIGRATION_32_33
        )

        assertEquals(6, countRows(migratedDb, "SELECT watch_count FROM movies WHERE id = 10"))
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_favorites_global_provider_id_content_type_content_id' AND sql LIKE '%WHERE group_id IS NULL%'"
            )
        )

        migratedDb.close()
    }

    @Test
    fun migrate33To34_addsSuccessTimestampsAndProgramsEndTimeIndex() {
        migrationTestHelper.createDatabase("streamvault-33-34-test", 33).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    is_active, max_connections, allowed_output_formats_json, epg_sync_mode,
                    xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES (1, 'Provider', 'XTREAM_CODES', 'https://provider.example.com', 'demo', 'secret', '', '', 1, 1, '[]', 'PROVIDER', 0, 0, 'ACTIVE', 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO sync_metadata (
                    provider_id, last_live_sync, last_movie_sync, last_series_sync, last_epg_sync,
                    last_movie_attempt, last_movie_success, last_movie_partial,
                    live_count, movie_count, series_count, epg_count, last_sync_status, movie_sync_mode,
                    movie_warnings_count, movie_catalog_stale, live_avoid_full_until, movie_avoid_full_until,
                    series_avoid_full_until, live_sequential_failures_remembered, live_healthy_sync_streak,
                    movie_parallel_failures_remembered, movie_healthy_sync_streak,
                    series_sequential_failures_remembered, series_healthy_sync_streak
                ) VALUES (
                    1, 11, 22, 33, 44,
                    55, 66, 77,
                    1, 2, 3, 4, 'SUCCESS', 'FULL',
                    0, 0, 0, 0,
                    0, 0, 0,
                    0, 0,
                    0, 0
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-33-34-test",
            34,
            true,
            StreamVaultDatabase.MIGRATION_33_34
        )

        assertEquals(11, countRows(migratedDb, "SELECT last_live_success FROM sync_metadata WHERE provider_id = 1"))
        assertEquals(33, countRows(migratedDb, "SELECT last_series_success FROM sync_metadata WHERE provider_id = 1"))
        assertEquals(44, countRows(migratedDb, "SELECT last_epg_success FROM sync_metadata WHERE provider_id = 1"))
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_programs_provider_id_end_time_channel_id'"
            )
        )

        migratedDb.close()
    }

    @Test
    fun migrate34To35_createsSearchHistoryTable() {
        migrationTestHelper.createDatabase("streamvault-34-35-test", 34).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-34-35-test",
            35,
            true,
            StreamVaultDatabase.MIGRATION_34_35
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('search_history') WHERE name = 'query'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('search_history') WHERE name = 'content_scope'"))
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_search_history_query_content_scope_provider_id'"
            )
        )

        migratedDb.close()
    }

    @Test
    fun migrate31To32_scopesFavoritesAndSplitsMixedProviderGroups() {
        migrationTestHelper.createDatabase("streamvault-31-32-test", 31).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    is_active, max_connections, allowed_output_formats_json, epg_sync_mode,
                    xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES
                    (1, 'Provider One', 'XTREAM_CODES', 'https://one.example.com', 'one', 'secret', '', '', 1, 1, '[]', 'PROVIDER', 0, 0, 'ACTIVE', 0, 0),
                    (2, 'Provider Two', 'XTREAM_CODES', 'https://two.example.com', 'two', 'secret', '', '', 0, 1, '[]', 'PROVIDER', 0, 0, 'ACTIVE', 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO channels (
                    id, stream_id, name, stream_url, number, catch_up_supported, catch_up_days,
                    provider_id, is_adult, is_user_protected, logical_group_id, error_count, sync_fingerprint
                ) VALUES
                    (101, 1001, 'Provider One Global', 'https://one.example.com/live/1001', 1, 0, 0, 1, 0, 0, 'lg-101', 0, 'fp-101'),
                    (102, 1002, 'Provider One Grouped', 'https://one.example.com/live/1002', 2, 0, 0, 1, 0, 0, 'lg-102', 0, 'fp-102'),
                    (201, 2001, 'Provider Two Grouped', 'https://two.example.com/live/2001', 1, 0, 0, 2, 0, 0, 'lg-201', 0, 'fp-201')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO virtual_groups (id, name, icon_emoji, position, created_at, content_type)
                VALUES (10, 'Mixed Group', NULL, 0, 100, 'LIVE')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO favorites (id, content_id, content_type, position, group_id, added_at)
                VALUES
                    (1, 101, 'LIVE', 0, NULL, 1000),
                    (2, 102, 'LIVE', 1, 10, 1001),
                    (3, 201, 'LIVE', 0, 10, 1002)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-31-32-test",
            32,
            true,
            StreamVaultDatabase.MIGRATION_31_32
        )

        assertEquals(2, countRows(migratedDb, "SELECT COUNT(*) FROM virtual_groups WHERE name = 'Mixed Group'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM virtual_groups WHERE provider_id = 1 AND name = 'Mixed Group'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM virtual_groups WHERE provider_id = 2 AND name = 'Mixed Group'"))

        assertEquals(2, countRows(migratedDb, "SELECT COUNT(*) FROM favorites WHERE provider_id = 1"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM favorites WHERE provider_id = 2"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM favorites WHERE provider_id = 1 AND group_id IS NULL"))

        val providerOneGroupId = firstLong(
            migratedDb,
            "SELECT group_id FROM favorites WHERE provider_id = 1 AND content_id = 102"
        )
        val providerTwoGroupId = firstLong(
            migratedDb,
            "SELECT group_id FROM favorites WHERE provider_id = 2 AND content_id = 201"
        )

        assertEquals(10L, providerOneGroupId)
        assertNotEquals(providerOneGroupId, providerTwoGroupId)

        migratedDb.close()
    }

    @Test
    fun migrate31To32_assignsLegacyGroupsWhenNoProviderIsActive() {
        migrationTestHelper.createDatabase("streamvault-31-32-no-active-test", 31).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    is_active, max_connections, allowed_output_formats_json, epg_sync_mode,
                    xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES
                    (5, 'First Provider', 'XTREAM_CODES', 'https://one.example.com', 'one', 'secret', '', '', 0, 1, '[]', 'PROVIDER', 0, 0, 'ACTIVE', 0, 10),
                    (7, 'Second Provider', 'XTREAM_CODES', 'https://two.example.com', 'two', 'secret', '', '', 0, 1, '[]', 'PROVIDER', 0, 0, 'ACTIVE', 0, 20)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO virtual_groups (id, name, icon_emoji, position, created_at, content_type)
                VALUES (10, 'Legacy Group', NULL, 0, 100, 'LIVE')
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-31-32-no-active-test",
            32,
            true,
            StreamVaultDatabase.MIGRATION_31_32
        )

        assertEquals(0, countRows(migratedDb, "SELECT COUNT(*) FROM virtual_groups WHERE provider_id = 0 OR provider_id IS NULL"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM virtual_groups WHERE id = 10 AND provider_id = 5"))

        migratedDb.close()
    }

    @Test
    fun migrate35To36_addsProgramRemindersAndEpgMatchMetadata() {
        migrationTestHelper.createDatabase("streamvault-35-36-test", 35).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-35-36-test",
            36,
            true,
            StreamVaultDatabase.MIGRATION_35_36
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('channel_epg_mappings') WHERE name = 'matched_at'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('channel_epg_mappings') WHERE name = 'failed_attempts'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('channel_epg_mappings') WHERE name = 'source'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'program_reminders'"))
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_program_reminders_provider_id_channel_id_program_title_program_start_time'"
            )
        )

        migratedDb.close()
    }

    @Test
    fun migrate36To37_createsTmdbIdentityTable() {
        migrationTestHelper.createDatabase("streamvault-36-37-test", 36).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    is_active, max_connections, allowed_output_formats_json, epg_sync_mode,
                    xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES (1, 'Provider', 'XTREAM_CODES', 'https://provider.example.com', 'demo', 'secret', '', '', 1, 1, '[]', 'PROVIDER', 0, 0, 'ACTIVE', 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO movies (
                    id, stream_id, name, stream_url, duration_seconds, rating, tmdb_id, provider_id,
                    watch_progress, watch_count, last_watched_at, is_adult, is_user_protected, sync_fingerprint, added_at
                ) VALUES (10, 2001, 'Movie', 'https://provider.example.com/movie.mp4', 7200, 7.5, 12345, 1, 0, 0, 0, 0, 0, 'fp-10', 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO series (
                    id, series_id, name, rating, tmdb_id, last_modified, provider_id, is_adult, is_user_protected, sync_fingerprint
                ) VALUES (20, 3001, 'Series', 8.1, 67890, 0, 1, 0, 0, 'fp-20')
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-36-37-test",
            37,
            true,
            StreamVaultDatabase.MIGRATION_36_37
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'tmdb_identity'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM tmdb_identity WHERE tmdb_id = 12345 AND content_type = 'MOVIE'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM tmdb_identity WHERE tmdb_id = 67890 AND content_type = 'SERIES'"))

        migratedDb.close()
    }

    @Test
    fun migrate14To15_addsAuditCompletionColumns() {
        migrationTestHelper.createDatabase("streamvault-14-15-test", 14).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    is_active, max_connections, expiration_date, status, last_synced_at, created_at
                ) VALUES (1, 'Provider', 'XTREAM_CODES', 'https://provider.example.com', 'demo', 'secret', '', '', 1, 1, NULL, 'ACTIVE', 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO playback_history (
                    content_id, content_type, provider_id, title, poster_url, stream_url,
                    resume_position_ms, total_duration_ms, last_watched_at, watch_count, series_id, season_number, episode_number
                ) VALUES (10, 'MOVIE', 1, 'Movie', NULL, 'https://provider.example.com/movie.mp4', 0, 7200, 0, 1, NULL, NULL, NULL)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-14-15-test",
            15,
            true,
            StreamVaultDatabase.MIGRATION_14_15
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'api_version'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('channels') WHERE name = 'quality_options_json'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('programs') WHERE name = 'rating'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('playback_history') WHERE name = 'watched_status'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM playback_history WHERE watched_status = 'IN_PROGRESS'"))

        migratedDb.close()
    }

    private fun countRows(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int {
        db.query(sql).use { cursor ->
            if (!cursor.moveToFirst()) return 0
            return cursor.getInt(0)
        }
    }

    private fun exists(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Boolean {
        db.query(sql).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun firstLong(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long {
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }
}
