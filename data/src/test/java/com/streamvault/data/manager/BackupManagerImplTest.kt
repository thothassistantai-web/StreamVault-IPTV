package com.streamvault.data.manager

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.RecordingScheduleDao
import com.streamvault.data.local.dao.VirtualGroupDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.RecordingScheduleEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.manager.BackupData
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.manager.RecordingScheduleImportDisposition
import com.streamvault.domain.manager.ScheduledRecordingBackup
import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.CategoryRepository
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BackupManagerImplTest {

    @Test
    fun `inspectBackup rejects structurally empty json instead of previewing version zero`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.openInputStream(Uri.parse("content://empty-backup"))).thenReturn(
            ByteArrayInputStream("{}".toByteArray())
        )

        val manager = backupManagerForValidation(context = context)

        val result = manager.inspectBackup("content://empty-backup")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("does not contain any importable data")
    }

    @Test
    fun `importConfig rejects structurally empty json before mutating data`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.openInputStream(Uri.parse("content://empty-backup-import"))).thenReturn(
            ByteArrayInputStream("{}".toByteArray())
        )

        val manager = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository
        )

        val result = manager.importConfig("content://empty-backup-import")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("does not contain any importable data")
        verify(preferencesRepository, never()).setParentalControlLevel(any())
    }

    @Test
    fun `importConfig replace history only deletes imported providers and resyncs them in transaction`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)

        val providerDao: ProviderDao = mock()
        val playbackHistoryDao: PlaybackHistoryDao = mock()
        val movieDao: MovieDao = mock()
        val episodeDao: EpisodeDao = mock()
        val transactionRunner = RecordingTransactionRunner()
        val gson = Gson()

        val backupProvider = Provider(
            id = 100L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            username = "user",
            password = "",
            stalkerMacAddress = ""
        )
        val backupData = BackupData(
            providers = listOf(backupProvider),
            playbackHistory = listOf(
                PlaybackHistory(
                    contentId = 55L,
                    contentType = ContentType.MOVIE,
                    providerId = 100L,
                    title = "Movie",
                    streamUrl = "https://stream.example.test/movie.mp4",
                    resumePositionMs = 12_000L,
                    totalDurationMs = 5_400_000L
                )
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://backup"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backupData).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(
            listOf(
                ProviderEntity(
                    id = 7L,
                    name = "Stored Provider",
                    type = ProviderType.M3U,
                    serverUrl = "https://example.com",
                    username = "user"
                )
            )
        )

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = mock<PreferencesRepository>(),
            credentialCrypto = mock<CredentialCrypto>(),
            providerDao = providerDao,
            favoriteDao = mock<FavoriteDao>(),
            virtualGroupDao = mock<VirtualGroupDao>(),
            playbackHistoryDao = playbackHistoryDao,
            movieDao = movieDao,
            episodeDao = episodeDao,
            categoryRepository = mock<CategoryRepository>(),
            recordingScheduleDao = mock<RecordingScheduleDao>(),
            recordingManager = mock<RecordingManager>(),
            transactionRunner = transactionRunner,
            gson = gson
        )

        val result = manager.importConfig(
            uriString = "content://backup",
            plan = BackupImportPlan(
                importPreferences = false,
                importProviders = false,
                importSavedLibrary = false,
                importPlaybackHistory = true,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.REPLACE_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(transactionRunner.calls).isEqualTo(1)
        verify(playbackHistoryDao).deleteByProvider(7L)
        verify(playbackHistoryDao, never()).deleteAll()
        verify(playbackHistoryDao).insertOrUpdate(argThat {
            providerId == 7L &&
                contentId == 55L &&
                contentType == ContentType.MOVIE
        })
        verify(movieDao).syncWatchProgressFromHistoryByProvider(7L)
        verify(episodeDao).syncWatchProgressFromHistoryByProvider(7L)
        verify(movieDao, never()).syncAllWatchProgressFromHistory()
        verify(episodeDao, never()).syncAllWatchProgressFromHistory()
    }

    @Test
    fun `importConfig keeps saved library and history writes inside one room transaction`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)

        val providerDao: ProviderDao = mock()
        val favoriteDao: FavoriteDao = mock()
        val playbackHistoryDao: PlaybackHistoryDao = mock()
        val movieDao: MovieDao = mock()
        val episodeDao: EpisodeDao = mock()
        val transactionRunner = RecordingTransactionRunner()
        val gson = Gson()
        val backupProvider = Provider(
            id = 100L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            username = "user",
            password = "",
            stalkerMacAddress = ""
        )
        val backupData = BackupData(
            providers = listOf(backupProvider),
            favorites = listOf(
                Favorite(
                    providerId = 100L,
                    contentId = 88L,
                    contentType = ContentType.MOVIE,
                    position = 0
                )
            ),
            playbackHistory = listOf(
                PlaybackHistory(
                    contentId = 55L,
                    contentType = ContentType.MOVIE,
                    providerId = 100L,
                    title = "Movie",
                    streamUrl = "https://stream.example.test/movie.mp4",
                    watchCount = 1
                )
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://backup-transaction"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backupData).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(
            listOf(
                ProviderEntity(
                    id = 7L,
                    name = "Stored Provider",
                    type = ProviderType.M3U,
                    serverUrl = "https://example.com",
                    username = "user"
                )
            )
        )
        whenever(favoriteDao.get(any(), any(), any(), any())).thenReturn(null)
        doAnswer {
            assertThat(transactionRunner.isInTransaction).isTrue()
            1L
        }.whenever(favoriteDao).insert(any())
        doAnswer {
            assertThat(transactionRunner.isInTransaction).isTrue()
            Unit
        }.whenever(playbackHistoryDao).deleteByProvider(any())
        doAnswer {
            assertThat(transactionRunner.isInTransaction).isTrue()
            Unit
        }.whenever(playbackHistoryDao).insertOrUpdate(any())
        doAnswer {
            assertThat(transactionRunner.isInTransaction).isTrue()
            Unit
        }.whenever(movieDao).syncWatchProgressFromHistoryByProvider(any())
        doAnswer {
            assertThat(transactionRunner.isInTransaction).isTrue()
            Unit
        }.whenever(episodeDao).syncWatchProgressFromHistoryByProvider(any())

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = mock<PreferencesRepository>(),
            credentialCrypto = mock<CredentialCrypto>(),
            providerDao = providerDao,
            favoriteDao = favoriteDao,
            virtualGroupDao = mock<VirtualGroupDao>(),
            playbackHistoryDao = playbackHistoryDao,
            movieDao = movieDao,
            episodeDao = episodeDao,
            categoryRepository = mock<CategoryRepository>(),
            recordingScheduleDao = mock<RecordingScheduleDao>(),
            recordingManager = mock<RecordingManager>(),
            transactionRunner = transactionRunner,
            gson = gson
        )

        val result = manager.importConfig(
            uriString = "content://backup-transaction",
            plan = BackupImportPlan(
                importPreferences = false,
                importProviders = false,
                importSavedLibrary = true,
                importPlaybackHistory = true,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.REPLACE_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(transactionRunner.calls).isEqualTo(1)
        verify(favoriteDao).insert(any())
        verify(playbackHistoryDao).insertOrUpdate(any())
    }

    @Test
    fun `importConfig does not restore preferences before room-backed import succeeds`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)

        val providerDao: ProviderDao = mock()
        val favoriteDao: FavoriteDao = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val gson = Gson()
        val backupProvider = Provider(
            id = 100L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            username = "user",
            password = "",
            stalkerMacAddress = ""
        )
        val backupData = BackupData(
            preferences = mapOf("parentalControlLevel" to "4"),
            providers = listOf(backupProvider),
            favorites = listOf(
                Favorite(
                    providerId = 100L,
                    contentId = 88L,
                    contentType = ContentType.MOVIE,
                    position = 0
                )
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://backup-preferences-order"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backupData).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(
            listOf(
                ProviderEntity(
                    id = 7L,
                    name = "Stored Provider",
                    type = ProviderType.M3U,
                    serverUrl = "https://example.com",
                    username = "user"
                )
            )
        )
        whenever(favoriteDao.get(any(), any(), any(), any())).thenReturn(null)
        whenever(favoriteDao.insert(any())).thenThrow(IllegalStateException("favorite insert failed"))

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = preferencesRepository,
            credentialCrypto = mock<CredentialCrypto>(),
            providerDao = providerDao,
            favoriteDao = favoriteDao,
            virtualGroupDao = mock<VirtualGroupDao>(),
            playbackHistoryDao = mock<PlaybackHistoryDao>(),
            movieDao = mock<MovieDao>(),
            episodeDao = mock<EpisodeDao>(),
            categoryRepository = mock<CategoryRepository>(),
            recordingScheduleDao = mock<RecordingScheduleDao>(),
            recordingManager = mock<RecordingManager>(),
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            gson = gson
        )

        val result = manager.importConfig(
            uriString = "content://backup-preferences-order",
            plan = BackupImportPlan(
                importPreferences = true,
                importProviders = false,
                importSavedLibrary = true,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.REPLACE_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        verify(preferencesRepository, never()).setParentalControlLevel(any())
    }

    @Test
    fun `importConfig restores audio video sync enabled preference`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)

        val providerDao: ProviderDao = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val gson = Gson()
        val backupData = BackupData(
            preferences = mapOf(
                "playerAudioVideoSyncEnabled" to "true",
                "playerAudioVideoOffsetMs" to "150"
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://backup-av-sync-preferences"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backupData).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(emptyList())

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = preferencesRepository,
            credentialCrypto = mock<CredentialCrypto>(),
            providerDao = providerDao,
            favoriteDao = mock<FavoriteDao>(),
            virtualGroupDao = mock<VirtualGroupDao>(),
            playbackHistoryDao = mock<PlaybackHistoryDao>(),
            movieDao = mock<MovieDao>(),
            episodeDao = mock<EpisodeDao>(),
            categoryRepository = mock<CategoryRepository>(),
            recordingScheduleDao = mock<RecordingScheduleDao>(),
            recordingManager = mock<RecordingManager>(),
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            gson = gson
        )

        val result = manager.importConfig(
            uriString = "content://backup-av-sync-preferences",
            plan = BackupImportPlan(
                importPreferences = true,
                importProviders = false,
                importSavedLibrary = false,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.KEEP_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(preferencesRepository).setPlayerAudioVideoSyncEnabled(true)
        verify(preferencesRepository).setPlayerAudioVideoOffsetMs(150)
    }

    @Test
    fun `importConfig restores top navigation destinations preference`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)

        val providerDao: ProviderDao = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val gson = Gson()
        val backupData = BackupData(
            preferences = mapOf(
                "appTopLevelDestinations" to "home,search,settings"
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://backup-top-navigation"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backupData).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(emptyList())

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = preferencesRepository,
            credentialCrypto = mock<CredentialCrypto>(),
            providerDao = providerDao,
            favoriteDao = mock<FavoriteDao>(),
            virtualGroupDao = mock<VirtualGroupDao>(),
            playbackHistoryDao = mock<PlaybackHistoryDao>(),
            movieDao = mock<MovieDao>(),
            episodeDao = mock<EpisodeDao>(),
            categoryRepository = mock<CategoryRepository>(),
            recordingScheduleDao = mock<RecordingScheduleDao>(),
            recordingManager = mock<RecordingManager>(),
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            gson = gson
        )

        val result = manager.importConfig(
            uriString = "content://backup-top-navigation",
            plan = BackupImportPlan(
                importPreferences = true,
                importProviders = false,
                importSavedLibrary = false,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.KEEP_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(preferencesRepository).setAppTopLevelDestinations(
            listOf(
                AppTopLevelDestination.HOME,
                AppTopLevelDestination.SEARCH,
                AppTopLevelDestination.SETTINGS
            )
        )
    }

    @Test
    fun `importConfig restores home dashboard shelves preference`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val providerDao: ProviderDao = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)
        val gson = Gson()
        val backupData = BackupData(
            preferences = mapOf(
                "appHomeDashboardShelves" to "favorite_channels,recommended_movies,top_rated_movies"
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://backup-home-dashboard"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backupData).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(emptyList())

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = preferencesRepository,
            credentialCrypto = mock<CredentialCrypto>(),
            providerDao = providerDao,
            favoriteDao = mock<FavoriteDao>(),
            virtualGroupDao = mock<VirtualGroupDao>(),
            playbackHistoryDao = mock<PlaybackHistoryDao>(),
            movieDao = mock<MovieDao>(),
            episodeDao = mock<EpisodeDao>(),
            categoryRepository = mock<CategoryRepository>(),
            recordingScheduleDao = mock<RecordingScheduleDao>(),
            recordingManager = mock<RecordingManager>(),
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            gson = gson
        )

        val result = manager.importConfig(
            uriString = "content://backup-home-dashboard",
            plan = BackupImportPlan(
                importPreferences = true,
                importProviders = false,
                importSavedLibrary = false,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.KEEP_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(preferencesRepository).setAppHomeDashboardShelves(
            listOf(
                AppHomeDashboardShelf.FAVORITE_CHANNELS,
                AppHomeDashboardShelf.RECOMMENDED_MOVIES,
                AppHomeDashboardShelf.TOP_RATED_MOVIES
            )
        )
    }

    @Test
    fun `toScheduledRecordingBackup stores requested window and padding separately`() {
        val provider = Provider(
            id = 7L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            username = "user",
            stalkerMacAddress = ""
        )
        val item = RecordingItem(
            id = "scheduled-1",
            scheduleId = 21L,
            providerId = 7L,
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = 1_700_000_000_000L,
            scheduledEndMs = 1_700_000_540_000L,
            programTitle = "World News",
            recurrence = RecordingRecurrence.DAILY,
            status = RecordingStatus.SCHEDULED
        )
        val schedule = RecordingScheduleEntity(
            id = 21L,
            providerId = 7L,
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            programTitle = "World News",
            requestedStartMs = 1_700_000_120_000L,
            requestedEndMs = 1_700_000_480_000L,
            recurrence = RecordingRecurrence.DAILY
        )

        val backup = item.toScheduledRecordingBackup(provider, schedule)

        assertThat(backup.scheduledStartMs).isEqualTo(item.scheduledStartMs)
        assertThat(backup.scheduledEndMs).isEqualTo(item.scheduledEndMs)
        assertThat(backup.requestedStartMs).isEqualTo(schedule.requestedStartMs)
        assertThat(backup.requestedEndMs).isEqualTo(schedule.requestedEndMs)
        assertThat(backup.paddingBeforeMs).isEqualTo(120_000L)
        assertThat(backup.paddingAfterMs).isEqualTo(60_000L)
        assertThat(backup.recurringRuleId).isEqualTo(schedule.recurringRuleId)
    }

    @Test
    fun `toRecordingRequest preserves legacy effective backup windows`() {
        val backup = ScheduledRecordingBackup(
            providerServerUrl = "https://example.com",
            providerUsername = "user",
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = 1_700_000_000_000L,
            scheduledEndMs = 1_700_000_540_000L,
            programTitle = "World News",
            recurrence = RecordingRecurrence.NONE
        )

        val request = backup.toRecordingRequest(providerId = 7L)

        assertThat(request.scheduledStartMs).isEqualTo(backup.scheduledStartMs)
        assertThat(request.scheduledEndMs).isEqualTo(backup.scheduledEndMs)
        assertThat(request.paddingBeforeMs).isEqualTo(0L)
        assertThat(request.paddingAfterMs).isEqualTo(0L)
    }

    @Test
    fun `toRecordingRequest restores requested window and explicit padding from new backups`() {
        val backup = ScheduledRecordingBackup(
            providerServerUrl = "https://example.com",
            providerUsername = "user",
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = 1_700_000_000_000L,
            scheduledEndMs = 1_700_000_540_000L,
            requestedStartMs = 1_700_000_120_000L,
            requestedEndMs = 1_700_000_480_000L,
            paddingBeforeMs = 120_000L,
            paddingAfterMs = 60_000L,
            programTitle = "World News",
            recurrence = RecordingRecurrence.WEEKLY,
            recurringRuleId = "rule-1"
        )

        val request = backup.toRecordingRequest(providerId = 7L)

        assertThat(request.scheduledStartMs).isEqualTo(backup.requestedStartMs)
        assertThat(request.scheduledEndMs).isEqualTo(backup.requestedEndMs)
        assertThat(request.paddingBeforeMs).isEqualTo(backup.paddingBeforeMs)
        assertThat(request.paddingAfterMs).isEqualTo(backup.paddingAfterMs)
        assertThat(request.recurrence).isEqualTo(RecordingRecurrence.WEEKLY)
        assertThat(request.recurringRuleId).isEqualTo("rule-1")
    }

    @Test
    fun `normalizedRecurringBackups collapses duplicate occurrences for the same recurring rule`() {
        val recurringFirst = ScheduledRecordingBackup(
            providerServerUrl = "https://example.com",
            providerUsername = "user",
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = 1_700_000_000_000L,
            scheduledEndMs = 1_700_000_540_000L,
            requestedStartMs = 1_700_000_120_000L,
            requestedEndMs = 1_700_000_480_000L,
            programTitle = "World News",
            recurrence = RecordingRecurrence.DAILY,
            recurringRuleId = "rule-1"
        )
        val recurringSecond = recurringFirst.copy(
            scheduledStartMs = 1_700_086_400_000L,
            scheduledEndMs = 1_700_086_940_000L,
            requestedStartMs = 1_700_086_520_000L,
            requestedEndMs = 1_700_086_880_000L
        )
        val oneShot = ScheduledRecordingBackup(
            providerServerUrl = "https://example.com",
            providerUsername = "user",
            channelId = 101L,
            channelName = "BBC Two",
            streamUrl = "https://example.com/other.ts",
            scheduledStartMs = 1_700_010_000_000L,
            scheduledEndMs = 1_700_010_540_000L,
            programTitle = "Documentary",
            recurrence = RecordingRecurrence.NONE
        )

        val normalized = listOf(recurringSecond, oneShot, recurringFirst).normalizedRecurringBackups()

        assertThat(normalized).hasSize(2)
        assertThat(normalized).contains(recurringFirst)
        assertThat(normalized).contains(oneShot)
    }

    @Test
    fun `normalizedRecurringBackups keeps recurring entries without stable identity`() {
        val first = ScheduledRecordingBackup(
            providerServerUrl = "https://example.com",
            providerUsername = "user",
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = 1_700_000_000_000L,
            scheduledEndMs = 1_700_000_540_000L,
            programTitle = "World News",
            recurrence = RecordingRecurrence.DAILY
        )
        val second = first.copy(
            scheduledStartMs = 1_700_086_400_000L,
            scheduledEndMs = 1_700_086_940_000L
        )

        val normalized = listOf(first, second).normalizedRecurringBackups()

        assertThat(normalized).containsExactly(first, second)
    }

    @Test
    fun `importScheduledRecordingBackups reports skipped and failed outcomes`() {
        val recordingManager: RecordingManager = mock()
        val provider = ProviderEntity(
            id = 7L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            username = "user"
        )
        val existingSchedule = RecordingItem(
            id = "existing-1",
            providerId = 7L,
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = 1_700_000_000_000L,
            scheduledEndMs = 1_700_000_540_000L,
            status = RecordingStatus.SCHEDULED
        )
        val keepExisting = ScheduledRecordingBackup(
            providerServerUrl = provider.serverUrl,
            providerUsername = provider.username,
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = existingSchedule.scheduledStartMs,
            scheduledEndMs = existingSchedule.scheduledEndMs,
            programTitle = "World News"
        )
        val validationFailure = keepExisting.copy(
            channelId = 101L,
            channelName = "BBC Two",
            streamUrl = "https://example.com/other.ts",
            scheduledStartMs = 1_700_001_000_000L,
            scheduledEndMs = 1_700_001_540_000L
        )

        runBlocking {
            whenever(recordingManager.scheduleRecording(any()))
                .thenReturn(Result.error("Recording conflicts with an existing active recording for World News."))
        }

        val summary = kotlinx.coroutines.runBlocking {
            importScheduledRecordingBackups(
                recordings = listOf(keepExisting, validationFailure),
                storedProviders = listOf(provider),
                existingSchedules = mutableListOf(existingSchedule),
                conflictStrategy = BackupConflictStrategy.KEEP_EXISTING,
                recordingManager = recordingManager,
                nowMs = 1_699_999_000_000L
            )
        }

        assertThat(summary.importedCount).isEqualTo(0)
        assertThat(summary.skippedCount).isEqualTo(1)
        assertThat(summary.failedCount).isEqualTo(1)
        assertThat(summary.outcomes.map { it.disposition }).containsExactly(
            RecordingScheduleImportDisposition.SKIPPED_EXISTING,
            RecordingScheduleImportDisposition.FAILED
        )
        assertThat(summary.outcomes.last().reason).contains("conflicts")
        runBlocking {
            verify(recordingManager).scheduleRecording(any())
        }
    }

    @Test
    fun `importScheduledRecordingBackups reports replaced existing schedules`() {
        val recordingManager: RecordingManager = mock()
        val provider = ProviderEntity(
            id = 7L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            username = "user"
        )
        val existingSchedule = RecordingItem(
            id = "existing-1",
            providerId = 7L,
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = 1_700_000_000_000L,
            scheduledEndMs = 1_700_000_540_000L,
            status = RecordingStatus.SCHEDULED
        )
        val imported = ScheduledRecordingBackup(
            providerServerUrl = provider.serverUrl,
            providerUsername = provider.username,
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = existingSchedule.scheduledStartMs,
            scheduledEndMs = existingSchedule.scheduledEndMs,
            programTitle = "World News"
        )
        val importedItem = existingSchedule.copy(id = "imported-1")

        runBlocking {
            whenever(recordingManager.cancelRecording(existingSchedule.id)).thenReturn(Result.success(Unit))
            whenever(recordingManager.scheduleRecording(any())).thenReturn(Result.success(importedItem))
        }

        val summary = kotlinx.coroutines.runBlocking {
            importScheduledRecordingBackups(
                recordings = listOf(imported),
                storedProviders = listOf(provider),
                existingSchedules = mutableListOf(existingSchedule),
                conflictStrategy = BackupConflictStrategy.REPLACE_EXISTING,
                recordingManager = recordingManager,
                nowMs = 1_699_999_000_000L
            )
        }

        assertThat(summary.importedCount).isEqualTo(1)
        assertThat(summary.failedCount).isEqualTo(0)
        assertThat(summary.outcomes.single().disposition).isEqualTo(RecordingScheduleImportDisposition.REPLACED_EXISTING)
        runBlocking {
            verify(recordingManager).cancelRecording(existingSchedule.id)
            verify(recordingManager).scheduleRecording(any())
        }
    }

    private class RecordingTransactionRunner : DatabaseTransactionRunner {
        var calls: Int = 0
        private var depth: Int = 0

        val isInTransaction: Boolean
            get() = depth > 0

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            calls += 1
            depth += 1
            return try {
                block()
            } finally {
                depth -= 1
            }
        }
    }

    private fun backupManagerForValidation(
        context: Context,
        preferencesRepository: PreferencesRepository = mock(),
    ): BackupManagerImpl = BackupManagerImpl(
        context = context,
        preferencesRepository = preferencesRepository,
        credentialCrypto = mock<CredentialCrypto>(),
        providerDao = mock<ProviderDao>(),
        favoriteDao = mock<FavoriteDao>(),
        virtualGroupDao = mock<VirtualGroupDao>(),
        playbackHistoryDao = mock<PlaybackHistoryDao>(),
        movieDao = mock<MovieDao>(),
        episodeDao = mock<EpisodeDao>(),
        categoryRepository = mock<CategoryRepository>(),
        recordingScheduleDao = mock<RecordingScheduleDao>(),
        recordingManager = mock<RecordingManager>(),
        transactionRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
        },
        gson = Gson()
    )
}
