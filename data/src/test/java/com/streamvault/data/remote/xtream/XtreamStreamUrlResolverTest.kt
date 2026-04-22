package com.streamvault.data.remote.xtream

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.stalker.StalkerCategoryRecord
import com.streamvault.data.remote.stalker.StalkerDeviceProfile
import com.streamvault.data.remote.stalker.StalkerEpisodeRecord
import com.streamvault.data.remote.stalker.StalkerItemRecord
import com.streamvault.data.remote.stalker.StalkerProgramRecord
import com.streamvault.data.remote.stalker.StalkerProviderProfile
import com.streamvault.data.remote.stalker.StalkerSeasonRecord
import com.streamvault.data.remote.stalker.StalkerSeriesDetails
import com.streamvault.data.remote.stalker.StalkerSession
import com.streamvault.data.remote.stalker.StalkerStreamKind
import com.streamvault.data.remote.stalker.StalkerUrlFactory
import com.streamvault.domain.model.ContentType
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class XtreamStreamUrlResolverTest {
    private val credentialCrypto = object : CredentialCrypto {
        override fun encryptIfNeeded(value: String): String = value
        override fun decryptIfNeeded(value: String): String = value
    }

    private val stalkerApiService = FakeStalkerApiService()


    @Test
    fun buildPlaybackUrl_uses_live_container_extension_when_present() {
        val url = XtreamUrlFactory.buildPlaybackUrl(
            serverUrl = "https://stream.example.com",
            username = "alice",
            password = "secret",
            kind = XtreamStreamKind.LIVE,
            streamId = 123,
            containerExtension = ".M3U8"
        )

        assertThat(url).isEqualTo("https://stream.example.com/live/alice/secret/123.m3u8")
    }

    @Test
    fun buildInternalStreamUrl_normalizes_container_extension() {
        val url = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 9,
            kind = XtreamStreamKind.LIVE,
            streamId = 456,
            containerExtension = ".TS",
            directSource = "https://edge.example.com/live/456/index.ts"
        )

        assertThat(url).isEqualTo("xtream://9/live/456?ext=ts&src=https%3A%2F%2Fedge.example.com%2Flive%2F456%2Findex.ts")
        assertThat(XtreamUrlFactory.parseInternalStreamUrl(url)?.containerExtension).isEqualTo("ts")
        assertThat(XtreamUrlFactory.parseInternalStreamUrl(url)?.directSource).isEqualTo("https://edge.example.com/live/456/index.ts")
    }

    @Test
    fun resolveWithMetadata_prefers_allowed_direct_source() = runBlocking {
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                ProviderEntity(
                    id = 9,
                    name = "Xtream",
                    type = ProviderType.XTREAM_CODES,
                    serverUrl = "https://portal.example.com",
                    username = "alice",
                    password = "secret"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = stalkerApiService
        )
        val url = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 9,
            kind = XtreamStreamKind.LIVE,
            streamId = 456,
            containerExtension = "ts",
            directSource = "http://edge.example.com/live/456/index.ts?exp=1774017000"
        )

        val resolved = resolver.resolveWithMetadata(url)

        assertThat(resolved?.url).isEqualTo("http://edge.example.com/live/456/index.ts?exp=1774017000")
        assertThat(resolved?.expirationTime).isEqualTo(1_774_017_000_000L)
    }

    @Test
    fun resolveWithMetadata_ignores_unsupported_direct_source_scheme() = runBlocking {
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                ProviderEntity(
                    id = 9,
                    name = "Xtream",
                    type = ProviderType.XTREAM_CODES,
                    serverUrl = "https://portal.example.com",
                    username = "alice",
                    password = "secret"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = stalkerApiService
        )
        val url = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 9,
            kind = XtreamStreamKind.LIVE,
            streamId = 456,
            containerExtension = "m3u8",
            directSource = "ftp://edge.example.com/live/456/index.m3u8"
        )

        val resolved = resolver.resolveWithMetadata(url)

        assertThat(resolved?.url).isEqualTo("https://portal.example.com/live/alice/secret/456.m3u8")
    }

    @Test
    fun extractStreamExpirationTime_reads_unix_seconds_query_parameter() {
        val expirationTime = extractStreamExpirationTime(
            "https://stream.example.com/live.m3u8?token=abc123&expire=1774017000"
        )

        assertThat(expirationTime).isEqualTo(1_774_017_000_000L)
    }

    @Test
    fun extractStreamExpirationTime_reads_iso_encoded_query_parameter() {
        val expirationTime = extractStreamExpirationTime(
            "https://stream.example.com/live.m3u8?expires_at=2026-03-20T14%3A30%3A00Z"
        )

        assertThat(expirationTime).isEqualTo(1_774_017_000_000L)
    }

    @Test
    fun extractStreamExpirationTime_returns_null_when_missing() {
        val expirationTime = extractStreamExpirationTime(
            "https://stream.example.com/live.m3u8?token=abc123"
        )

        assertThat(expirationTime).isNull()
    }

    @Test
    fun resolveWithMetadata_resolves_stalker_internal_url_via_cached_provider() = runBlocking {
        val fakeStalkerApiService = FakeStalkerApiService()
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                ProviderEntity(
                    id = 14,
                    name = "Stalker",
                    type = ProviderType.STALKER_PORTAL,
                    serverUrl = "https://portal.example.com",
                    stalkerMacAddress = "00:1A:79:12:34:56",
                    stalkerDeviceProfile = "MAG250",
                    stalkerDeviceTimezone = "UTC",
                    stalkerDeviceLocale = "en"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = fakeStalkerApiService
        )
        val internalUrl = StalkerUrlFactory.buildInternalStreamUrl(
            providerId = 14,
            kind = StalkerStreamKind.LIVE,
            itemId = 77,
            cmd = "ffrt http://edge.example.com/live/77.m3u8",
            containerExtension = "m3u8"
        )

        val firstResolved = resolver.resolveWithMetadata(internalUrl)
        val secondResolved = resolver.resolveWithMetadata(internalUrl)

        assertThat(firstResolved?.url).isEqualTo("http://edge.example.com/live/77.m3u8")
        assertThat(firstResolved?.expirationTime).isNull()
        assertThat(firstResolved?.headers?.get("Referer")).isEqualTo("https://portal.example.com/c/")
        assertThat(firstResolved?.headers?.get("Cookie")).contains("mac=00:1A:79:12:34:56")
        assertThat(firstResolved?.headers?.get("Cookie")).contains("stb_lang=en")
        assertThat(firstResolved?.headers?.get("Cookie")).contains("timezone=UTC")
        assertThat(firstResolved?.headers?.get("Cookie")).contains("sn=0001A79123456")
        assertThat(firstResolved?.headers?.get("Cookie")).contains("device_id=")
        assertThat(firstResolved?.headers?.get("Cookie")).contains("device_id2=")
        assertThat(firstResolved?.headers?.get("Cookie")).contains("signature=")
        assertThat(firstResolved?.headers?.get("Authorization")).isEqualTo("Bearer token")
        assertThat(firstResolved?.headers?.get("X-User-Agent")).isEqualTo("Model: MAG250; Link: Ethernet")
        assertThat(firstResolved?.userAgent).contains("MAG250 stbapp")
        assertThat(secondResolved?.url).isEqualTo("http://edge.example.com/live/77.m3u8")
        assertThat(fakeStalkerApiService.authenticateCalls).isEqualTo(1)
        assertThat(fakeStalkerApiService.createLinkCalls).isEqualTo(0)
    }

    @Test
    fun resolveWithMetadata_uses_direct_stalker_absolute_cmd_without_create_link() = runBlocking {
        val fakeStalkerApiService = FakeStalkerApiService()
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                ProviderEntity(
                    id = 14,
                    name = "Stalker",
                    type = ProviderType.STALKER_PORTAL,
                    serverUrl = "https://portal.example.com",
                    stalkerMacAddress = "00:1A:79:12:34:56",
                    stalkerDeviceProfile = "MAG250",
                    stalkerDeviceTimezone = "UTC",
                    stalkerDeviceLocale = "en"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = fakeStalkerApiService
        )
        val directCmd = "http://0connect.top:8080/9UTXtQcxuxkk/9fa4ed5x07/443?play_token=iwdgLK23Yl"
        val internalUrl = StalkerUrlFactory.buildInternalStreamUrl(
            providerId = 14,
            kind = StalkerStreamKind.LIVE,
            itemId = 77,
            cmd = directCmd,
            containerExtension = "ts"
        )

        val resolved = resolver.resolveWithMetadata(internalUrl)

        assertThat(resolved?.url).isEqualTo(directCmd)
        assertThat(resolved?.headers?.get("Referer")).isEqualTo("https://portal.example.com/c/")
        assertThat(resolved?.headers?.get("Authorization")).isEqualTo("Bearer token")
        assertThat(resolved?.userAgent).contains("MAG250 stbapp")
        assertThat(fakeStalkerApiService.authenticateCalls).isEqualTo(1)
        assertThat(fakeStalkerApiService.createLinkCalls).isEqualTo(0)
    }

    @Test
    fun resolveWithMetadata_uses_wrapped_direct_stalker_live_cmd_without_create_link() = runBlocking {
        val fakeStalkerApiService = FakeStalkerApiService().apply {
            createLinkResponse = "http://line.trxdnscloud.ru/play/live.php?mac=00:1A:79:40:8B:D7&stream=&extension=ts&play_token=broken"
        }
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                ProviderEntity(
                    id = 14,
                    name = "Stalker",
                    type = ProviderType.STALKER_PORTAL,
                    serverUrl = "http://line.trxdnscloud.ru/c/",
                    stalkerMacAddress = "00:1A:79:40:8B:D7",
                    stalkerDeviceProfile = "MAG250",
                    stalkerDeviceTimezone = "UTC",
                    stalkerDeviceLocale = "en"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = fakeStalkerApiService
        )
        val internalUrl = StalkerUrlFactory.buildInternalStreamUrl(
            providerId = 14,
            kind = StalkerStreamKind.LIVE,
            itemId = 978715,
            cmd = "ffmpeg http://line.trxdnscloud.ru/play/live.php?mac=00:1A:79:40:8B:D7&stream=978715&extension=ts&play_token=R7KbxtDJj3",
            containerExtension = "ts"
        )

        val resolved = resolver.resolveWithMetadata(internalUrl)

        assertThat(resolved?.url).isEqualTo(
            "http://line.trxdnscloud.ru/play/live.php?mac=00:1A:79:40:8B:D7&stream=978715&extension=ts&play_token=R7KbxtDJj3"
        )
        assertThat(resolved?.headers?.get("Referer")).isEqualTo("https://portal.example.com/c/")
        assertThat(resolved?.headers?.get("Authorization")).isEqualTo("Bearer token")
        assertThat(resolved?.userAgent).contains("MAG250 stbapp")
        assertThat(fakeStalkerApiService.authenticateCalls).isEqualTo(1)
        assertThat(fakeStalkerApiService.createLinkCalls).isEqualTo(0)
    }

    @Test
    fun resolveWithMetadata_repairs_stale_direct_stalker_live_url_and_applies_headers() = runBlocking {
        val fakeStalkerApiService = FakeStalkerApiService()
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                ProviderEntity(
                    id = 14,
                    name = "Stalker",
                    type = ProviderType.STALKER_PORTAL,
                    serverUrl = "https://portal.example.com",
                    stalkerMacAddress = "00:1A:79:12:34:56",
                    stalkerDeviceProfile = "MAG250",
                    stalkerDeviceTimezone = "UTC",
                    stalkerDeviceLocale = "en"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = fakeStalkerApiService
        )

        val resolved = resolver.resolveWithMetadata(
            url = "http://portal.example.com/play/live.php?mac=00:1A:79:12:34:56&stream=&extension=ts&play_token=abc123",
            fallbackProviderId = 14,
            fallbackStreamId = 978715,
            fallbackContentType = ContentType.LIVE,
            fallbackContainerExtension = "ts"
        )

        assertThat(resolved?.url).isEqualTo(
            "http://portal.example.com/play/live.php?mac=00:1A:79:12:34:56&stream=978715&extension=ts&play_token=abc123"
        )
        assertThat(resolved?.headers?.get("Referer")).isEqualTo("https://portal.example.com/c/")
        assertThat(resolved?.headers?.get("Authorization")).isEqualTo("Bearer token")
        assertThat(resolved?.userAgent).contains("MAG250 stbapp")
        assertThat(fakeStalkerApiService.authenticateCalls).isEqualTo(1)
        assertThat(fakeStalkerApiService.createLinkCalls).isEqualTo(0)
    }

    private class FakeProviderDao(
        private val provider: ProviderEntity?
    ) : ProviderDao() {
        override fun getAll() = flowOf(listOfNotNull(provider))
        override suspend fun getAllSync(): List<ProviderEntity> = listOfNotNull(provider)
        override fun getActive() = flowOf(provider)
        override suspend fun getByUrlAndUser(serverUrl: String, username: String, stalkerMacAddress: String): ProviderEntity? = null
        override suspend fun getById(id: Long): ProviderEntity? = provider?.takeIf { it.id == id }
        override suspend fun getByIds(ids: List<Long>): List<ProviderEntity> =
            listOfNotNull(provider).filter { it.id in ids }
        override suspend fun insert(provider: ProviderEntity): Long = provider.id
        override suspend fun update(provider: ProviderEntity) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun deactivateAll() = Unit
        override suspend fun activate(id: Long) = Unit
        override suspend fun updateSyncTime(id: Long, timestamp: Long) = Unit
        override suspend fun updateEpgUrl(id: Long, epgUrl: String) = Unit
    }

    private class FakeStalkerApiService : StalkerApiService {
        var authenticateCalls: Int = 0
        var createLinkCalls: Int = 0
        var createLinkResponse: String = "http://edge.example.com/live/77.m3u8?exp=1774017000"

        override suspend fun authenticate(profile: StalkerDeviceProfile): Result<Pair<StalkerSession, StalkerProviderProfile>> {
            authenticateCalls += 1
            return Result.success(
                StalkerSession(
                    loadUrl = "https://portal.example.com/server/load.php",
                    portalReferer = "https://portal.example.com/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Test")
            )
        }

        override suspend fun getLiveCategories(
            session: StalkerSession,
            profile: StalkerDeviceProfile
        ): Result<List<StalkerCategoryRecord>> = Result.success(emptyList())

        override suspend fun getLiveStreams(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?
        ): Result<List<StalkerItemRecord>> = Result.success(emptyList())

        override suspend fun getVodCategories(
            session: StalkerSession,
            profile: StalkerDeviceProfile
        ): Result<List<StalkerCategoryRecord>> = Result.success(emptyList())

        override suspend fun getVodStreams(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?
        ): Result<List<StalkerItemRecord>> = Result.success(emptyList())

        override suspend fun getSeriesCategories(
            session: StalkerSession,
            profile: StalkerDeviceProfile
        ): Result<List<StalkerCategoryRecord>> = Result.success(emptyList())

        override suspend fun getSeries(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?
        ): Result<List<StalkerItemRecord>> = Result.success(emptyList())

        override suspend fun getSeriesDetails(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            seriesId: String
        ): Result<StalkerSeriesDetails> = Result.success(
            StalkerSeriesDetails(
                series = StalkerItemRecord(id = seriesId, name = "Series"),
                seasons = listOf(StalkerSeasonRecord(seasonNumber = 1, name = "Season 1", episodes = listOf(StalkerEpisodeRecord(id = "1", title = "Episode", episodeNumber = 1, seasonNumber = 1))))
            )
        )

        override suspend fun getShortEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            channelId: String,
            limit: Int
        ): Result<List<StalkerProgramRecord>> = Result.success(emptyList())

        override suspend fun getEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            channelId: String
        ): Result<List<StalkerProgramRecord>> = Result.success(emptyList())

        override suspend fun getBulkEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            periodHours: Int
        ): Result<List<StalkerProgramRecord>> = Result.success(emptyList())

        override suspend fun createLink(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            kind: StalkerStreamKind,
            cmd: String
        ): Result<String> {
            createLinkCalls += 1
            return Result.success(createLinkResponse)
        }
    }
}
