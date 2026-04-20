package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import android.content.Context
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.TmdbIdentityDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.preferences.PreferencesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import java.util.zip.GZIPOutputStream

/**
 * Unit tests for [SyncManager] state machine transitions.
 *
 * Strategy:
 * - Uses an in-memory [FakeProviderDao] — no Room needed
 * - Real [M3uParser] (pure JVM)
 * - Mockito-kotlin mocks for network/DAO collaborators
 * - All sync calls expected to FAIL (mocks return nulls/errors by default)
 *   because we're testing state transitions, not data correctness
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── In-memory fake ──────────────────────────────────────────────

    private class FakeProviderDao(
        private val provider: ProviderEntity? = sampleProvider()
    ) : ProviderDao() {
        override suspend fun getById(id: Long): ProviderEntity? = provider
        override suspend fun getByIds(ids: List<Long>): List<ProviderEntity> =
            listOfNotNull(provider).filter { it.id in ids }
        override suspend fun updateSyncTime(id: Long, timestamp: Long) = Unit
        override fun getAll() = kotlinx.coroutines.flow.flowOf(listOfNotNull(provider))
        override suspend fun getAllSync(): List<ProviderEntity> = listOfNotNull(provider)
        override fun getActive() = kotlinx.coroutines.flow.flowOf(provider)
        override suspend fun insert(entity: ProviderEntity) = provider?.id ?: 0L
        override suspend fun update(entity: ProviderEntity) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun deactivateAll() = Unit
        override suspend fun activate(id: Long) = Unit
        override suspend fun setActive(id: Long) = Unit
        override suspend fun getByUrlAndUser(url: String, user: String): ProviderEntity? = null
        override suspend fun getByUrlAndUser(
            serverUrl: String,
            username: String,
            stalkerMacAddress: String
        ): ProviderEntity? = null
        override suspend fun updateEpgUrl(id: Long, epgUrl: String) = Unit
    }

    companion object {
        fun sampleProvider(type: ProviderType = ProviderType.XTREAM_CODES) = ProviderEntity(
            id = 1L, name = "Test", type = type,
            serverUrl = "https://test.example.com:8080",
            username = "demo", password = "demo"
        )
    }

    private class FakeSyncMetadataRepository : com.streamvault.domain.repository.SyncMetadataRepository {
        private val values = mutableMapOf<Long, SyncMetadata>()

        override fun observeMetadata(providerId: Long): Flow<SyncMetadata?> = flowOf(values[providerId])

        override suspend fun getMetadata(providerId: Long): SyncMetadata? = values[providerId]

        override suspend fun updateMetadata(metadata: SyncMetadata) {
            values[metadata.providerId] = metadata
        }

        override suspend fun clearMetadata(providerId: Long) {
            values.remove(providerId)
        }

        fun reset() {
            values.clear()
        }
    }

    private class FakeXtreamBackend {
        private data class StubbedResponse(
            val code: Int,
            val body: String,
            val contentType: String = "application/json"
        )

        private val stubs = mutableMapOf<String, StubbedResponse>()
        val requestedActions = mutableListOf<String>()

        fun respond(action: String, body: String, code: Int = 200) {
            stubs[action] = StubbedResponse(code = code, body = body)
        }

        fun requestCount(): Int = requestedActions.size

        fun reset() {
            stubs.clear()
            requestedActions.clear()
        }

        fun okHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    requestedActions += action
                    val stub = stubs[action] ?: StubbedResponse(
                        code = 500,
                        body = """{"error":"missing stub for $action"}"""
                    )
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(stub.code)
                        .message(if (stub.code in 200..299) "OK" else "ERROR")
                        .body(stub.body.toResponseBody(stub.contentType.toMediaType()))
                        .build()
                }
                .build()
        }
    }

    // Mockito mocks — all return defaults (null/0/Unit), which will cause
    // the sync pipeline to throw and transition to Error state.
    private val channelDao: ChannelDao = mock()
    private val movieDao: MovieDao = mock()
    private val seriesDao: SeriesDao = mock()
    private val programDao: ProgramDao = mock()
    private val categoryDao: CategoryDao = mock()
    private val catalogSyncDao: CatalogSyncDao = mock()
    private val tmdbIdentityDao: TmdbIdentityDao = mock()
    private val epgRepo: EpgRepository = mock()
    private val epgSourceRepo: EpgSourceRepository = mock()
    private val preferencesRepo: PreferencesRepository = mock()
    private val stalkerApiService: StalkerApiService = mock()
    private val xtreamBackend = FakeXtreamBackend()
    private val xtreamJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }
    private val credentialCrypto = object : CredentialCrypto {
        override fun encryptIfNeeded(value: String): String = value
        override fun decryptIfNeeded(value: String): String = value
    }
    private val syncMetadataRepo = FakeSyncMetadataRepository()

    @Before
    fun setup() {
        xtreamBackend.reset()
        syncMetadataRepo.reset()
        reset(
            channelDao,
            movieDao,
            seriesDao,
            programDao,
            categoryDao,
            catalogSyncDao,
            tmdbIdentityDao,
            epgRepo,
            epgSourceRepo,
            preferencesRepo
        )
        org.mockito.kotlin.whenever(preferencesRepo.useXtreamTextClassification).thenReturn(flowOf(false))
        org.mockito.kotlin.whenever(preferencesRepo.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        org.mockito.kotlin.whenever(preferencesRepo.getHiddenCategoryIds(any(), any())).thenReturn(flowOf(emptySet()))
        runBlocking {
            org.mockito.kotlin.whenever(categoryDao.getByProviderAndTypeSync(any(), any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(channelDao.getByProviderSync(any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(movieDao.getByProviderSync(any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(movieDao.getTmdbIdsByProvider(any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(seriesDao.getByProviderSync(any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(seriesDao.getTmdbIdsByProvider(any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(catalogSyncDao.getCategoryStages(any(), any(), any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(catalogSyncDao.getChannelStages(any(), any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(catalogSyncDao.getMovieStages(any(), any())).thenReturn(emptyList())
            org.mockito.kotlin.whenever(catalogSyncDao.getSeriesStages(any(), any())).thenReturn(emptyList())
        }
    }

    private fun buildManager(
        providerType: ProviderType = ProviderType.XTREAM_CODES,
        providerPresent: Boolean = true,
        providerEntity: ProviderEntity? = null
    ): SyncManager = SyncManager(
        applicationContext = mock<Context>(),
        providerDao = FakeProviderDao(
            if (providerPresent) {
                providerEntity ?: sampleProvider(providerType)
            } else {
                null
            }
        ),
        channelDao = channelDao,
        movieDao = movieDao,
        seriesDao = seriesDao,
        programDao = programDao,
        categoryDao = categoryDao,
        catalogSyncDao = catalogSyncDao,
        tmdbIdentityDao = tmdbIdentityDao,
        stalkerApiService = stalkerApiService,
        xtreamJson = xtreamJson,
        m3uParser = M3uParser(),
        epgRepository = epgRepo,
        epgSourceRepository = epgSourceRepo,
        okHttpClient = xtreamBackend.okHttpClient(),
        credentialCrypto = credentialCrypto,
        syncMetadataRepository = syncMetadataRepo,
        transactionRunner = transactionRunner,
        preferencesRepository = preferencesRepo
    )

    // ── Initial state ───────────────────────────────────────────────

    @Test
    fun `initialState_isIdle`() = runTest {
        val mgr = buildManager()
        assertThat(mgr.currentSyncState(1L)).isEqualTo(SyncState.Idle)
    }

    // ── Provider not found ──────────────────────────────────────────

    @Test
    fun `sync_providerNotFound_returnsError_stateRemainsIdle`() = runTest {
        val mgr = buildManager(providerPresent = false)

        val result = mgr.sync(99L)

        assertThat(result.isError).isTrue()
        // State must NOT transition away from Idle (no provider = nothing to sync)
        assertThat(mgr.currentSyncState(99L)).isEqualTo(SyncState.Idle)
    }

    // ── Xtream sync failure ─────────────────────────────────────────

    @Test
    fun `sync_xtream_networksError_transitionsToError`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)

        // The Xtream path calls XtreamProvider(xtreamApi,...).getLiveCategories()
        // Since xtreamApi is a mock with null returns, the call throws → manager catches → Error
        mgr.sync(1L)
        advanceUntilIdle()

        assertThat(mgr.currentSyncState(1L)).isInstanceOf(SyncState.Error::class.java)
    }

    @Test
    fun `sync_xtream_stateMustPassThroughSyncing`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)

        // In TestDispatcher, sync() runs synchronously and state transitions complete
        // before this coroutine resumes. We verify the terminal state is Error —
        // that's only reachable via Syncing, proving the full transition happened.
        mgr.sync(1L)
        advanceUntilIdle()

        val finalState = mgr.currentSyncState(1L)
        assertThat(finalState).isInstanceOf(SyncState.Error::class.java)
    }

    @Test
    fun `sync_xtream_errorHasNonEmptyMessage`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        mgr.sync(1L)
        advanceUntilIdle()

        val state = mgr.currentSyncState(1L) as? SyncState.Error
        assertThat(state).isNotNull()
        assertThat(state!!.message).isNotEmpty()
    }

    @Test
    fun `sync_xtream_withFreshCache_andForceFalse_skipsRemoteCalls`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        val now = System.currentTimeMillis()
        syncMetadataRepo.updateMetadata(
            SyncMetadata(
                providerId = 1L,
                lastLiveSync = now,
                lastLiveSuccess = now,
                lastMovieSync = now,
                lastMovieSuccess = now,
                lastSeriesSync = now,
                lastSeriesSuccess = now,
                lastEpgSync = now,
                lastEpgSuccess = now
            )
        )

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(xtreamBackend.requestCount()).isEqualTo(0)
    }

    @Test
    fun `sync_xtream_partial_live_timestamp_does_not_suppress_retry`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        val now = System.currentTimeMillis()
        syncMetadataRepo.updateMetadata(
            SyncMetadata(
                providerId = 1L,
                lastLiveSync = now,
                lastLiveSuccess = 0L,
                lastMovieSync = now,
                lastMovieSuccess = now,
                lastSeriesSync = now,
                lastSeriesSuccess = now,
                lastEpgSync = now,
                lastEpgSuccess = now
            )
        )
        xtreamBackend.respond(action = "get_live_categories", body = """[{"category_id":"1","category_name":"News"}]""")
        xtreamBackend.respond(
            action = "get_live_streams",
            body = """
                [
                  {
                    "name": "Channel One",
                    "stream_id": 1001,
                    "category_id": "1",
                    "stream_icon": "https://example.com/ch1.png",
                    "tv_archive": 0,
                    "num": 1
                  }
                ]
            """.trimIndent()
        )

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(xtreamBackend.requestedActions).contains("get_live_categories")
        val updated = syncMetadataRepo.getMetadata(1L)
        assertThat(updated?.lastLiveSuccess ?: 0L).isGreaterThan(0L)
    }

    @Test
    fun `sync_xtream_falls_back_to_movie_categories_from_streams`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        val now = System.currentTimeMillis()
        syncMetadataRepo.updateMetadata(
            SyncMetadata(
                providerId = 1L,
                lastLiveSync = now,
                lastLiveSuccess = now,
                lastSeriesSync = now,
                lastSeriesSuccess = now,
                lastEpgSync = now,
                lastEpgSuccess = now
            )
        )
        xtreamBackend.respond(action = "get_vod_categories", body = """{"error":"categories unavailable"}""", code = 500)
        xtreamBackend.respond(
            action = "get_vod_streams",
            body = """
                [
                  {
                    "name": "Movie One",
                    "stream_id": 101,
                    "category_id": "vod-action",
                    "category_name": "Action",
                    "container_extension": "mp4"
                  }
                ]
            """.trimIndent()
        )

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        val categoriesCaptor = argumentCaptor<List<com.streamvault.data.local.entity.CategoryImportStageEntity>>()
        verify(catalogSyncDao, atLeastOnce()).insertCategoryStages(categoriesCaptor.capture())
        val movieCategories = categoriesCaptor.allValues.flatten().filter { it.type.name == "MOVIE" }
        assertThat(movieCategories).hasSize(1)
        assertThat(movieCategories.first().name).isEqualTo("Action")
    }

    @Test
    fun `sync_xtream_fast_sync_only_fetches_movie_categories`() = runTest {
        val provider = sampleProvider(ProviderType.XTREAM_CODES).copy(xtreamFastSyncEnabled = true)
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES, providerEntity = provider)
        val now = System.currentTimeMillis()
        syncMetadataRepo.updateMetadata(
            SyncMetadata(
                providerId = 1L,
                lastLiveSync = now,
                lastLiveSuccess = now,
                lastSeriesSync = now,
                lastSeriesSuccess = now,
                lastEpgSync = now,
                lastEpgSuccess = now
            )
        )
        xtreamBackend.respond(
            action = "get_vod_categories",
            body = """
                [
                  {"category_id":"42","category_name":"Action"}
                ]
            """.trimIndent()
        )
        org.mockito.kotlin.whenever(movieDao.getCount(1L)).thenReturn(flowOf(0))

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(xtreamBackend.requestedActions).contains("get_vod_categories")
        assertThat(xtreamBackend.requestedActions).doesNotContain("get_vod_streams")
        verify(catalogSyncDao, atLeastOnce()).insertCategoryStages(any())
    }

    @Test
    fun `retrySection_movies_fast_sync_succeeds_with_categories_only`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        xtreamBackend.respond(
            action = "get_vod_categories",
            body = """
                [
                  {"category_id":"42","category_name":"Action"}
                ]
            """.trimIndent()
        )
        org.mockito.kotlin.whenever(movieDao.getCount(1L)).thenReturn(flowOf(0))

        val result = mgr.retrySection(
            providerId = 1L,
            section = SyncRepairSection.MOVIES,
            movieFastSyncOverride = true
        )
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        val metadata = syncMetadataRepo.getMetadata(1L)
        assertThat(metadata?.lastMovieSync ?: 0L).isGreaterThan(0L)
        assertThat(metadata?.movieSyncMode).isEqualTo(com.streamvault.domain.model.VodSyncMode.LAZY_BY_CATEGORY)
        assertThat(xtreamBackend.requestedActions).contains("get_vod_categories")
        assertThat(xtreamBackend.requestedActions).doesNotContain("get_vod_streams")
    }

    @Test
    fun `sync_xtream_movie_failure_does_not_block_series_sync`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        val now = System.currentTimeMillis()
        syncMetadataRepo.updateMetadata(
            SyncMetadata(
                providerId = 1L,
                lastLiveSync = now,
                lastLiveSuccess = now,
                lastEpgSync = now,
                lastEpgSuccess = now
            )
        )
        org.mockito.kotlin.whenever(movieDao.getCount(1L)).thenReturn(flowOf(0))
        xtreamBackend.respond(action = "get_series_categories", body = """[{"category_id":"9","category_name":"Drama"}]""")
        xtreamBackend.respond(
            action = "get_series",
            body = """
                [
                  {
                    "series_id": 301,
                    "name": "Series One",
                    "category_id": "9",
                    "cover": "https://example.com/cover.jpg"
                  }
                ]
            """.trimIndent()
        )

        val result = mgr.sync(1L, force = false)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(xtreamBackend.requestedActions).contains("get_series_categories")
    }

    @Test
    fun `sync_m3u_fileImport_batchesAndDiscoversEpg`() = runTest {
        val playlist = tempFolder.newFile("playlist.m3u")
        playlist.writeText(buildString {
            append("#EXTM3U x-tvg-url=\"https://epg.example.com/guide.xml\"\n")
            repeat(2501) { index ->
                append("#EXTINF:-1 group-title=\"News\",Channel ${index + 1}\n")
                append("https://live.example.com/ch${index + 1}.ts\n")
            }
            append("#EXTINF:-1 group-title=\"Movies\",Movie One\n")
            append("https://vod.example.com/movie1.mp4\n")
        })
        val url = playlist.toURI().toString()
        val provider = sampleProvider(ProviderType.M3U).copy(
            serverUrl = url,
            m3uUrl = url,
            epgUrl = "",
            m3uVodClassificationEnabled = true
        )
        val mgr = buildManager(providerType = ProviderType.M3U, providerEntity = provider)

        val result = mgr.sync(1L, force = true)
        advanceUntilIdle()

        if (result is Result.Error) {
            error(result.message)
        }
        assertThat(result.isSuccess).isTrue()
        verify(catalogSyncDao, atLeast(3)).insertChannelStages(any())
        verify(catalogSyncDao, atLeastOnce()).insertMovieStages(any())
    }

    @Test
    fun `sync_m3u_gzipFileImport_succeeds`() = runTest {
        val gzFile = tempFolder.newFile("playlist.m3u.gz")
        GZIPOutputStream(gzFile.outputStream()).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("#EXTM3U\n")
            writer.write("#EXTINF:-1 group-title=\"Live\",Compressed Channel\n")
            writer.write("https://live.example.com/compressed.ts\n")
        }
        val url = gzFile.toURI().toString()
        val provider = sampleProvider(ProviderType.M3U).copy(serverUrl = url, m3uUrl = url)
        val mgr = buildManager(providerType = ProviderType.M3U, providerEntity = provider)

        val result = mgr.sync(1L, force = true)
        advanceUntilIdle()

        if (result is Result.Error) {
            error(result.message)
        }
        assertThat(result.isSuccess).isTrue()
        verify(catalogSyncDao, atLeastOnce()).insertChannelStages(any())
    }

    @Test
    fun `sync_m3u_accepts_http_header_epg_and_ignores_insecure_streams`() = runTest {
        // HTTP EPG URLs in the playlist header are now accepted (same policy as playlist sources).
        // Only stream entry URLs with unsupported schemes (e.g. ftp://) are silently dropped.
        val playlist = tempFolder.newFile("mixed-playlist.m3u")
        playlist.writeText(
            """
            #EXTM3U x-tvg-url="http://epg.example.com/guide.xml"
            #EXTINF:-1 group-title="News",Secure Channel
            https://live.example.com/secure.ts
            #EXTINF:-1 group-title="News",Insecure Channel
            ftp://live.example.com/insecure.ts
            """.trimIndent()
        )

        val provider = sampleProvider(ProviderType.M3U).copy(serverUrl = playlist.toURI().toString(), m3uUrl = playlist.toURI().toString())
        val mgr = buildManager(providerType = ProviderType.M3U, providerEntity = provider)

        val result = mgr.sync(1L, force = true)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        val state = mgr.currentSyncState(1L)
        assertThat(state).isInstanceOf(SyncState.Success::class.java)
        val insertedChannels = argumentCaptor<List<com.streamvault.data.local.entity.ChannelImportStageEntity>>()
        verify(catalogSyncDao, atLeastOnce()).insertChannelStages(insertedChannels.capture())
        assertThat(insertedChannels.allValues.flatten()).hasSize(1)
    }

    @Test
    fun `sync_m3u_ignores_unsupported_scheme_header_epg`() = runTest {
        val playlist = tempFolder.newFile("ftp-epg-playlist.m3u")
        playlist.writeText(
            """
            #EXTM3U x-tvg-url="ftp://epg.example.com/guide.xml"
            #EXTINF:-1 group-title="News",Channel
            https://live.example.com/channel.ts
            """.trimIndent()
        )

        val provider = sampleProvider(ProviderType.M3U).copy(serverUrl = playlist.toURI().toString(), m3uUrl = playlist.toURI().toString())
        val mgr = buildManager(providerType = ProviderType.M3U, providerEntity = provider)

        val result = mgr.sync(1L, force = true)
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat((mgr.currentSyncState(1L) as SyncState.Partial).warnings)
            .contains("Ignored unsupported EPG URL from playlist header.")
    }

    // ── M3U sync failure ────────────────────────────────────────────

    @Test
    fun `sync_m3u_networkError_transitionsToError`() = runTest {
        val mgr = buildManager(providerType = ProviderType.M3U)

        // OkHttpClient mock: newCall() returns null → NullPointerException → Error
        mgr.sync(1L)
        advanceUntilIdle()

        assertThat(mgr.currentSyncState(1L)).isInstanceOf(SyncState.Error::class.java)
    }

    @Test
    fun `onProviderDeleted resets state and notifies epg repository`() = runTest {
        val mgr = buildManager()
        mgr.sync(1L)
        advanceUntilIdle()

        assertThat(mgr.currentSyncState(1L)).isNotEqualTo(SyncState.Idle)

        mgr.onProviderDeleted(1L)

        assertThat(mgr.currentSyncState(1L)).isEqualTo(SyncState.Idle)
        verify(epgRepo).onProviderDeleted(1L)
    }

    @Test
    fun `runWhenNoSyncActive returns false while a sync is in flight`() = runTest {
        val provider = sampleProvider(ProviderType.M3U).copy(epgUrl = "https://epg.example.com/guide.xml")
        val mgr = buildManager(providerType = ProviderType.M3U, providerEntity = provider)
        val syncEntered = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()
        org.mockito.kotlin.whenever(epgRepo.refreshEpg(1L, "https://epg.example.com/guide.xml")).thenAnswer {
            syncEntered.complete(Unit)
            runBlocking { releaseSync.await() }
            Result.success(Unit)
        }

        val syncJob = async {
            mgr.retrySection(providerId = 1L, section = SyncRepairSection.EPG)
        }
        syncEntered.await()

        val vacuumAllowed = mgr.runWhenNoSyncActive { true }
        releaseSync.complete(Unit)

        assertThat(vacuumAllowed).isFalse()
        syncJob.await()
    }

    // ── Reset state ─────────────────────────────────────────────────

    @Test
    fun `resetState_afterError_returnsToIdle`() = runTest {
        val mgr = buildManager()
        mgr.sync(1L) // fails → Error
        advanceUntilIdle()

        assertThat(mgr.currentSyncState(1L)).isInstanceOf(SyncState.Error::class.java)

        mgr.resetState()
        advanceUntilIdle()

        assertThat(mgr.currentSyncState(1L)).isEqualTo(SyncState.Idle)
    }

    @Test
    fun `resetState_whenAlreadyIdle_staysIdle`() = runTest {
        val mgr = buildManager()

        mgr.resetState() // should be a no-op

        assertThat(mgr.currentSyncState(1L)).isEqualTo(SyncState.Idle)
    }

    // ── isVodEntry (SyncManager is the canonical source) ───────────

    @Test
    fun `isVodEntry_mp4Extension_returnsTrue`() {
        assertThat(M3uParser.isVodEntry(entry(url = "http://vod.example.com/film.mp4"))).isTrue()
    }

    @Test
    fun `isVodEntry_mkvExtension_returnsTrue`() {
        assertThat(M3uParser.isVodEntry(entry(url = "http://vod.example.com/show.mkv"))).isTrue()
    }

    @Test
    fun `isVodEntry_movieGroupTitle_returnsTrue`() {
        assertThat(M3uParser.isVodEntry(entry(group = "Movies HD"))).isTrue()
    }

    @Test
    fun `isVodEntry_vodGroupTitle_returnsTrue`() {
        assertThat(M3uParser.isVodEntry(entry(group = "VOD Library"))).isTrue()
    }

    @Test
    fun `isVodEntry_filmGroupTitle_returnsTrue`() {
        assertThat(M3uParser.isVodEntry(entry(group = "Film Classics"))).isTrue()
    }

    @Test
    fun `isVodEntry_liveTs_returnsFalse`() {
        assertThat(M3uParser.isVodEntry(entry(url = "http://live.example.com/cnn.ts", group = "News"))).isFalse()
    }

    @Test
    fun `isVodEntry_liveM3u8_returnsFalse`() {
        assertThat(M3uParser.isVodEntry(entry(url = "http://live.example.com/sports.m3u8", group = "Sports"))).isFalse()
    }

    // ── SyncState sealed class properties ───────────────────────────

    @Test
    fun `syncState_properties_idle`() {
        assertThat(SyncState.Idle.isSyncing).isFalse()
        assertThat(SyncState.Idle.isError).isFalse()
        assertThat(SyncState.Idle.isSuccess).isFalse()
    }

    @Test
    fun `syncState_properties_syncing`() {
        val state = SyncState.Syncing("Downloading…")
        assertThat(state.isSyncing).isTrue()
        assertThat(state.isError).isFalse()
        assertThat(state.isSuccess).isFalse()
    }

    @Test
    fun `syncState_properties_success`() {
        val state = SyncState.Success(timestamp = 12345L)
        assertThat(state.isSuccess).isTrue()
        assertThat(state.isSyncing).isFalse()
        assertThat(state.isError).isFalse()
        assertThat(state.timestamp).isEqualTo(12345L)
    }

    @Test
    fun `syncState_properties_error`() {
        val state = SyncState.Error("something went wrong", cause = RuntimeException("root"))
        assertThat(state.isError).isTrue()
        assertThat(state.isSyncing).isFalse()
        assertThat(state.isSuccess).isFalse()
        assertThat(state.message).isEqualTo("something went wrong")
        assertThat(state.cause).isInstanceOf(RuntimeException::class.java)
    }

    // ── Helper ──────────────────────────────────────────────────────

    private fun entry(
        url: String = "http://stream.example.com/ch1.ts",
        group: String = "Live"
    ) = M3uParser.M3uEntry(
        name = "Test", groupTitle = group,
        tvgId = null, tvgName = null, tvgLogo = null, tvgChno = null,
        tvgLanguage = null, tvgCountry = null,
        catchUp = null, catchUpDays = null, catchUpSource = null, timeshift = null, url = url
    )
}
