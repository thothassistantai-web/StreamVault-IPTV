package com.streamvault.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.epg.EpgResolutionEngine
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ChannelEpgMappingDao
import com.streamvault.data.local.dao.EpgChannelDao
import com.streamvault.data.local.dao.EpgProgrammeDao
import com.streamvault.data.local.dao.EpgSourceDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.ProviderEpgSourceDao
import com.streamvault.data.local.entity.ChannelEpgMappingEntity
import com.streamvault.data.local.entity.EpgChannelEntity
import com.streamvault.data.local.entity.EpgProgrammeEntity
import com.streamvault.data.local.entity.EpgSourceEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.ProviderEpgSourceEntity
import com.streamvault.data.parser.XmltvParser
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.EpgMatchType
import com.streamvault.domain.model.EpgSourceType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.ProviderType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.util.zip.GZIPOutputStream

class EpgSourceRepositoryImplTest {

    private val context: Context = mock()
    private val contentResolver: ContentResolver = mock()
    private val epgSourceDao: EpgSourceDao = mock()
    private val providerEpgSourceDao: ProviderEpgSourceDao = mock()
    private val providerDao: ProviderDao = mock()
    private val channelEpgMappingDao: ChannelEpgMappingDao = mock()
    private val epgChannelDao: EpgChannelDao = mock()
    private val epgProgrammeDao: EpgProgrammeDao = mock()
    private val xmltvParser: XmltvParser = mock()
    private val okHttpClient: OkHttpClient = mock()
    private val epgHttpClientBuilder: OkHttpClient.Builder = mock()
    private val resolutionEngine: EpgResolutionEngine = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private lateinit var repository: EpgSourceRepositoryImpl

    @Before
    fun setup() {
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(preferencesRepository.getHiddenCategoryIds(any(), eq(ContentType.LIVE))).thenReturn(flowOf(emptySet()))
        whenever(okHttpClient.newBuilder()).thenReturn(epgHttpClientBuilder)
        whenever(epgHttpClientBuilder.readTimeout(any<Long>(), any())).thenReturn(epgHttpClientBuilder)
        whenever(epgHttpClientBuilder.build()).thenReturn(okHttpClient)
        runBlocking {
            whenever(providerDao.getById(any())).thenReturn(null)
        }
        repository = EpgSourceRepositoryImpl(
            context = context,
            epgSourceDao = epgSourceDao,
            providerEpgSourceDao = providerEpgSourceDao,
            providerDao = providerDao,
            channelEpgMappingDao = channelEpgMappingDao,
            epgChannelDao = epgChannelDao,
            epgProgrammeDao = epgProgrammeDao,
            xmltvParser = xmltvParser,
            okHttpClient = okHttpClient,
            resolutionEngine = resolutionEngine,
            preferencesRepository = preferencesRepository,
            transactionRunner = transactionRunner
        )
    }

    @Test
    fun `assignSourceToProvider_resolvesProviderImmediately`() = runTest {
        whenever(epgSourceDao.getById(10L)).thenReturn(
            EpgSourceEntity(id = 10L, name = "Primary", url = "https://example.com/epg.xml")
        )

        val result = repository.assignSourceToProvider(providerId = 7L, epgSourceId = 10L, priority = 1)

        assertThat(result is Result.Success).isTrue()
        verify(providerEpgSourceDao).insert(any())
        verify(resolutionEngine).resolveForProvider(7L, emptySet())
    }

    @Test
    fun `setSourceEnabled_resolvesEachAffectedProviderOnce`() = runTest {
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(listOf(7L, 8L, 7L))
        whenever(preferencesRepository.getHiddenCategoryIds(7L, ContentType.LIVE)).thenReturn(flowOf(setOf(101L)))
        whenever(preferencesRepository.getHiddenCategoryIds(8L, ContentType.LIVE)).thenReturn(flowOf(setOf(202L)))

        repository.setSourceEnabled(10L, enabled = false)

        verify(epgSourceDao).setEnabled(eq(10L), eq(false), any())
        verify(resolutionEngine).resolveForProvider(7L, setOf(101L))
        verify(resolutionEngine).resolveForProvider(8L, setOf(202L))
        verifyNoMoreInteractions(resolutionEngine)
    }

    @Test
    fun `deleteSource_rebuildsAffectedProviderMappings`() = runTest {
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(listOf(4L, 5L))
        whenever(preferencesRepository.getHiddenCategoryIds(4L, ContentType.LIVE)).thenReturn(flowOf(setOf(401L)))
        whenever(preferencesRepository.getHiddenCategoryIds(5L, ContentType.LIVE)).thenReturn(flowOf(setOf(501L)))

        repository.deleteSource(10L)

        verify(epgProgrammeDao).deleteBySource(10L)
        verify(epgChannelDao).deleteBySource(10L)
        verify(epgSourceDao).delete(10L)
        verify(resolutionEngine).resolveForProvider(4L, setOf(401L))
        verify(resolutionEngine).resolveForProvider(5L, setOf(501L))
    }

    @Test
    fun `applyManualOverride_persistsManualExternalMapping`() = runTest {
        whenever(providerEpgSourceDao.getEnabledForProviderSync(7L)).thenReturn(
            listOf(ProviderEpgSourceEntity(id = 1L, providerId = 7L, epgSourceId = 10L, priority = 0))
        )
        whenever(epgChannelDao.getBySourceAndChannelId(10L, "bbc.one")).thenReturn(
            EpgChannelEntity(
                id = 5L,
                epgSourceId = 10L,
                xmltvChannelId = "bbc.one",
                displayName = "BBC One"
            )
        )
        whenever(channelEpgMappingDao.getForChannel(7L, 101L)).thenReturn(null)

        val result = repository.applyManualOverride(
            providerId = 7L,
            channelId = 101L,
            epgSourceId = 10L,
            xmltvChannelId = "bbc.one"
        )

        assertThat(result is Result.Success).isTrue()
        val captor = argumentCaptor<ChannelEpgMappingEntity>()
        verify(channelEpgMappingDao).upsert(captor.capture())
        assertThat(captor.firstValue.providerId).isEqualTo(7L)
        assertThat(captor.firstValue.providerChannelId).isEqualTo(101L)
        assertThat(captor.firstValue.epgSourceId).isEqualTo(10L)
        assertThat(captor.firstValue.xmltvChannelId).isEqualTo("bbc.one")
        assertThat(captor.firstValue.sourceType).isEqualTo(EpgSourceType.EXTERNAL.name)
        assertThat(captor.firstValue.matchType).isEqualTo(EpgMatchType.MANUAL.name)
        assertThat(captor.firstValue.isManualOverride).isTrue()
    }

    @Test
    fun `clearManualOverride_rebuildsAutomaticResolution`() = runTest {
        whenever(channelEpgMappingDao.getForChannel(7L, 101L)).thenReturn(
            ChannelEpgMappingEntity(
                id = 1L,
                providerChannelId = 101L,
                providerId = 7L,
                sourceType = EpgSourceType.EXTERNAL.name,
                epgSourceId = 10L,
                xmltvChannelId = "bbc.one",
                matchType = EpgMatchType.MANUAL.name,
                confidence = 1f,
                isManualOverride = true
            )
        )

        val result = repository.clearManualOverride(providerId = 7L, channelId = 101L)

        assertThat(result is Result.Success).isTrue()
        verify(resolutionEngine).resolveForProvider(7L, emptySet())
    }

    @Test
    fun `getOverrideCandidates_readsEnabledAssignedSourcesOnly`() = runTest {
        whenever(providerEpgSourceDao.getEnabledForProviderSync(7L)).thenReturn(
            listOf(ProviderEpgSourceEntity(id = 1L, providerId = 7L, epgSourceId = 10L, priority = 0))
        )
        whenever(epgSourceDao.getAllSync()).thenReturn(
            listOf(EpgSourceEntity(id = 10L, name = "Primary", url = "https://example.com/epg.xml"))
        )
        whenever(epgChannelDao.searchBySource(10L, "%bbc%", 150)).thenReturn(
            listOf(
                EpgChannelEntity(id = 1L, epgSourceId = 10L, xmltvChannelId = "bbc.one", displayName = "BBC One")
            )
        )

        val result = repository.getOverrideCandidates(providerId = 7L, query = "bbc")

        assertThat(result.map { it.displayName }).containsExactly("BBC One")
        assertThat(result.single().epgSourceName).isEqualTo("Primary")
    }

    @Test
    fun `refreshSource_closesDecompressedXmlStream`() = runTest {
        val source = EpgSourceEntity(
            id = 10L,
            name = "Primary",
            url = "content://epg/source.xml.gz"
        )
        val decompressedStream = CloseTrackingInputStream()

        whenever(epgSourceDao.getById(10L)).thenReturn(source)
        whenever(contentResolver.openInputStream(Uri.parse(source.url))).thenReturn(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        whenever(xmltvParser.maybeDecompressGzip(eq(source.url), any())).thenReturn(decompressedStream)
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(emptyList())

        val result = repository.refreshSource(10L)

        assertThat(result is Result.Success).isTrue()
        assertThat(decompressedStream.closed).isTrue()
    }

    @Test
    fun `refreshSource returns typed error when parser hits oversized chunked response`() = runTest {
        val source = EpgSourceEntity(
            id = 10L,
            name = "Primary",
            url = "content://epg/source.xml"
        )

        whenever(epgSourceDao.getById(10L)).thenReturn(source)
        whenever(contentResolver.openInputStream(Uri.parse(source.url))).thenReturn(ByteArrayInputStream("<tv/>".toByteArray()))
        whenever(xmltvParser.maybeDecompressGzip(eq(source.url), any())).thenAnswer { it.arguments[1] }
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(emptyList())
        doAnswer { throw IOException("EPG response too large (>200 MB)") }
            .whenever(xmltvParser)
            .parseStreamingWithChannels(any(), anyOrNull(), any(), any())

        val result = repository.refreshSource(10L)

        assertThat(result is Result.Error).isTrue()
        assertThat((result as Result.Error).message).isEqualTo("EPG response exceeded 200 MB limit")
        verify(epgSourceDao).updateRefreshError(eq(10L), eq("EPG response exceeded 200 MB limit"), any())
    }

    @Test
    fun `refreshSource imports gzip xmltv when download url has no gz suffix`() = runTest {
        val source = EpgSourceEntity(
            id = 10L,
            name = "MyEPG",
            url = "https://myepg.example/download?order=private&key=redacted",
            lastRefreshAt = 0L
        )
        val response = Response.Builder()
            .request(Request.Builder().url(source.url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(
                gzip(
                    """
                    <tv>
                      <channel id="myepg.ch1">
                        <display-name>MyEPG Channel</display-name>
                      </channel>
                      <programme channel="myepg.ch1" start="20260101000000 +0000" stop="20260101010000 +0000">
                        <title>MyEPG News</title>
                      </programme>
                    </tv>
                    """.trimIndent().toByteArray(Charsets.UTF_8)
                ).toResponseBody("application/octet-stream".toMediaType())
            )
            .build()
        val call: Call = mock()
        val repositoryWithRealParser = EpgSourceRepositoryImpl(
            context = context,
            epgSourceDao = epgSourceDao,
            providerEpgSourceDao = providerEpgSourceDao,
            providerDao = providerDao,
            channelEpgMappingDao = channelEpgMappingDao,
            epgChannelDao = epgChannelDao,
            epgProgrammeDao = epgProgrammeDao,
            xmltvParser = XmltvParser(),
            okHttpClient = okHttpClient,
            resolutionEngine = resolutionEngine,
            preferencesRepository = preferencesRepository,
            transactionRunner = transactionRunner
        )

        whenever(epgSourceDao.getById(10L)).thenReturn(source)
        whenever(okHttpClient.newCall(any())).thenReturn(call)
        whenever(call.execute()).thenReturn(response)
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(emptyList())

        val result = repositoryWithRealParser.refreshSource(10L)

        assertThat(result is Result.Success).isTrue()
        val channelCaptor = argumentCaptor<List<EpgChannelEntity>>()
        val programmeCaptor = argumentCaptor<List<EpgProgrammeEntity>>()
        verify(epgChannelDao).insertAll(channelCaptor.capture())
        verify(epgProgrammeDao).insertAll(programmeCaptor.capture())
        assertThat(channelCaptor.firstValue.single().xmltvChannelId).isEqualTo("myepg.ch1")
        assertThat(channelCaptor.firstValue.single().displayName).isEqualTo("MyEPG Channel")
        assertThat(channelCaptor.firstValue.single().epgSourceId).isEqualTo(-10L)
        assertThat(programmeCaptor.firstValue.single().title).isEqualTo("MyEPG News")
        assertThat(programmeCaptor.firstValue.single().epgSourceId).isEqualTo(-10L)
        verify(epgSourceDao).insert(argThat {
            id == -10L &&
                url == "streamvault://epg-source-staging/10" &&
                !enabled
        })
        inOrder(epgProgrammeDao, epgChannelDao, epgSourceDao).apply {
            verify(epgProgrammeDao).deleteBySource(-10L)
            verify(epgChannelDao).deleteBySource(-10L)
            verify(epgSourceDao).delete(-10L)
            verify(epgSourceDao).insert(argThat {
                id == -10L &&
                    url == "streamvault://epg-source-staging/10" &&
                    !enabled
            })
            verify(epgChannelDao).insertAll(any())
            verify(epgProgrammeDao).insertAll(any())
        }
        verify(epgChannelDao).moveToSource(-10L, 10L)
        verify(epgProgrammeDao).moveToSource(-10L, 10L)
        verify(epgSourceDao, times(2)).delete(-10L)
        verify(epgSourceDao).updateRefreshSuccess(eq(10L), any())
    }

    @Test
    fun `refreshSource does not force identity encoding for remote downloads`() = runTest {
        val source = EpgSourceEntity(
            id = 10L,
            name = "MyEPG",
            url = "https://myepg.example/download?order=private&key=redacted",
            lastRefreshAt = 0L
        )
        val requestCaptor = argumentCaptor<Request>()
        val response = Response.Builder()
            .request(Request.Builder().url(source.url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("<tv></tv>".toResponseBody("application/xml".toMediaType()))
            .build()
        val call: Call = mock()

        whenever(epgSourceDao.getById(10L)).thenReturn(source)
        whenever(okHttpClient.newCall(requestCaptor.capture())).thenReturn(call)
        whenever(call.execute()).thenReturn(response)
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(emptyList())
        whenever(xmltvParser.maybeDecompressGzip(eq(source.url), any())).thenAnswer { it.arguments[1] }

        val result = repository.refreshSource(10L)

        assertThat(result is Result.Success).isTrue()
        assertThat(requestCaptor.firstValue.header("Accept-Encoding")).isNull()
    }

    @Test
    fun `refreshSource rebuilds affected providers when remote source returns 304`() = runTest {
        val source = EpgSourceEntity(
            id = 10L,
            name = "Primary",
            url = "https://example.com/epg.xml",
            lastRefreshAt = 0L,
            etag = "etag-1"
        )
        val request = Request.Builder().url(source.url).build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(304)
            .message("Not Modified")
            .body("".toResponseBody())
            .build()
        val call: Call = mock()

        whenever(epgSourceDao.getById(10L)).thenReturn(source)
        whenever(okHttpClient.newCall(any())).thenReturn(call)
        whenever(call.execute()).thenReturn(response)
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(listOf(7L, 8L))

        val result = repository.refreshSource(10L)

        assertThat(result is Result.Success).isTrue()
        verify(epgSourceDao).updateRefreshSuccess(eq(10L), any())
        verify(resolutionEngine).resolveForProvider(7L, emptySet())
        verify(resolutionEngine).resolveForProvider(8L, emptySet())
    }

    @Test
    fun `refreshSource passes shared provider timezone to parser when assignments agree`() = runTest {
        val source = EpgSourceEntity(
            id = 10L,
            name = "Primary",
            url = "https://example.com/epg.xml"
        )
        val request = Request.Builder().url(source.url).build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("<tv></tv>".toResponseBody())
            .build()
        val call: Call = mock()

        whenever(epgSourceDao.getById(10L)).thenReturn(source)
        whenever(okHttpClient.newCall(any())).thenReturn(call)
        whenever(call.execute()).thenReturn(response)
        whenever(xmltvParser.maybeDecompressGzip(eq(source.url), any())).thenAnswer { it.arguments[1] }
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(listOf(7L, 8L))
        whenever(providerDao.getById(7L)).thenReturn(
            ProviderEntity(
                id = 7L,
                name = "Provider 7",
                type = ProviderType.M3U,
                serverUrl = "https://provider7.example.com",
                stalkerDeviceTimezone = "America/New_York"
            )
        )
        whenever(providerDao.getById(8L)).thenReturn(
            ProviderEntity(
                id = 8L,
                name = "Provider 8",
                type = ProviderType.M3U,
                serverUrl = "https://provider8.example.com",
                stalkerDeviceTimezone = "America/New_York"
            )
        )

        val result = repository.refreshSource(10L)

        assertThat(result is Result.Success).isTrue()
        verify(xmltvParser).parseStreamingWithChannels(any(), eq("America/New_York"), any(), any())
    }

    @Test
    fun `refreshSource falls back to system timezone when assigned providers disagree`() = runTest {
        val source = EpgSourceEntity(
            id = 10L,
            name = "Primary",
            url = "https://example.com/epg.xml"
        )
        val request = Request.Builder().url(source.url).build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("<tv></tv>".toResponseBody())
            .build()
        val call: Call = mock()

        whenever(epgSourceDao.getById(10L)).thenReturn(source)
        whenever(okHttpClient.newCall(any())).thenReturn(call)
        whenever(call.execute()).thenReturn(response)
        whenever(xmltvParser.maybeDecompressGzip(eq(source.url), any())).thenAnswer { it.arguments[1] }
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(listOf(7L, 8L))
        whenever(providerDao.getById(7L)).thenReturn(
            ProviderEntity(
                id = 7L,
                name = "Provider 7",
                type = ProviderType.M3U,
                serverUrl = "https://provider7.example.com",
                stalkerDeviceTimezone = "America/New_York"
            )
        )
        whenever(providerDao.getById(8L)).thenReturn(
            ProviderEntity(
                id = 8L,
                name = "Provider 8",
                type = ProviderType.M3U,
                serverUrl = "https://provider8.example.com",
                stalkerDeviceTimezone = "Europe/London"
            )
        )

        val result = repository.refreshSource(10L)

        assertThat(result is Result.Success).isTrue()
        verify(xmltvParser).parseStreamingWithChannels(any(), isNull(), any(), any())
    }

    private class CloseTrackingInputStream : ByteArrayInputStream(byteArrayOf()) {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }
}
