package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.Result
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StalkerProviderTest {

    @Test
    fun authenticate_treats_status_zero_as_partial_not_expired() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(
                    accountId = "758423",
                    accountName = "Room",
                    statusLabel = "0",
                    authAccess = false
                )
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.authenticate()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.status).isEqualTo(ProviderStatus.PARTIAL)
    }

    @Test
    fun authenticate_maps_expired_date_to_expired() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(
                    accountName = "Room",
                    expirationDate = 1L
                )
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.authenticate()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.status).isEqualTo(ProviderStatus.EXPIRED)
    }

    @Test
    fun getLiveStreams_maps_archive_capabilities_to_catch_up_fields() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                liveStreams = listOf(
                    StalkerItemRecord(
                        id = "1200",
                        name = "Archive TV",
                        cmd = "ffmpeg http://localhost/ch/1200_",
                        streamUrl = "http://localhost/ch/1200_",
                        portalCapabilities = StalkerPortalCapabilities(
                            archiveAvailable = true,
                            allowLocalTimeshift = true
                        ),
                        archiveAvailable = true
                    )
                )
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.getLiveStreams()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        val channel = success.data.single()
        assertThat(channel.catchUpSupported).isTrue()
        assertThat(channel.catchUpSource).isNotEmpty()
    }

    @Test
    fun buildCatchUpUrls_returns_internal_archive_candidates_for_live_token() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(profile = StalkerProviderProfile(accountName = "Room")),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            playbackBackendHint = com.streamvault.domain.model.StalkerPlaybackBackendHint.PLAY_LIVE,
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
        val start = Instant.parse("2024-01-01T10:00:00Z").epochSecond
        val end = Instant.parse("2024-01-01T11:00:00Z").epochSecond
        val sourceStreamUrl = StalkerUrlFactory.buildInternalStreamUrl(
            providerId = 7,
            kind = StalkerStreamKind.LIVE,
            itemId = 1200L,
            cmd = "ffmpeg http://localhost/ch/1200_",
            playbackDescriptor = checkNotNull(
                buildStalkerPlaybackDescriptor(
                    primaryCmd = "ffmpeg http://localhost/ch/1200_",
                    capabilities = StalkerPortalCapabilities(archiveAvailable = true)
                )
            ),
        )

        val urls = provider.buildCatchUpUrls(
            streamId = 1200L,
            start = start,
            end = end,
            sourceStreamUrl = sourceStreamUrl,
            sourceCatchUpSource = sourceStreamUrl
        )

        assertThat(urls).isNotEmpty()
        val token = StalkerUrlFactory.parseInternalStreamUrl(urls.first())
        assertThat(token?.kind).isEqualTo(StalkerStreamKind.ARCHIVE)
        assertThat(token?.archiveStartSeconds).isEqualTo(start)
        assertThat(token?.archiveEndSeconds).isEqualTo(end)
    }

    @Test
    fun buildCatchUpUrls_ignores_tokens_from_other_providers() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(profile = StalkerProviderProfile(accountName = "Room")),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
        val foreignToken = StalkerUrlFactory.buildInternalStreamUrl(
            providerId = 99,
            kind = StalkerStreamKind.LIVE,
            itemId = 1200L,
            cmd = "ffmpeg http://localhost/ch/1200_"
        )

        val urls = provider.buildCatchUpUrls(
            streamId = 1200L,
            start = Instant.parse("2024-01-01T10:00:00Z").epochSecond,
            end = Instant.parse("2024-01-01T11:00:00Z").epochSecond,
            sourceStreamUrl = foreignToken,
            sourceCatchUpSource = foreignToken
        )

        assertThat(urls).isEmpty()
    }

    @Test
    fun resolvePlaybackInfo_rejects_invalid_archive_window() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(profile = StalkerProviderProfile(accountName = "Room")),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.ARCHIVE,
            descriptor = buildStalkerPlaybackDescriptor("ffmpeg http://localhost/ch/1200_"),
            archiveStartSeconds = 1_000L,
            archiveEndSeconds = 999L
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.message).contains("end time after the start time")
    }

    @Test
    fun resolvePlaybackInfo_omits_authorization_for_play_live_temp_links() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                createLinkUrl = "http://fdox.org:8080/play/live.php?mac=00:1A:79:BA:73:FA&stream=228556&extension=ts&play_token=abc123"
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            playbackBackendHint = com.streamvault.domain.model.StalkerPlaybackBackendHint.TEMP_LINK_STRICT,
            cookieModeHint = com.streamvault.domain.model.StalkerCookieMode.CREATE_LINK,
            deviceProfile = "MAG322",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.LIVE,
            descriptor = checkNotNull(
                buildStalkerPlaybackDescriptor(
                    primaryCmd = "ffmpeg http://localhost/ch/1200_",
                    capabilities = StalkerPortalCapabilities(
                        useHttpTemporaryLink = true,
                        nginxSecureLink = true
                    )
                )
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.headers).doesNotContainKey("Authorization")
        assertThat(success.data.headers).containsKey("Referer")
        assertThat(success.data.headers).containsKey("Cookie")
        assertThat(success.data.headers).containsKey("X-User-Agent")
        assertThat(success.data.cookieMode).isNotEqualTo(com.streamvault.domain.model.StalkerCookieMode.NONE)
    }

    @Test
    fun resolvePlaybackInfo_uses_latest_api_cookie_snapshot_for_playback_headers() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                createLinkUrl = "http://fdox.org:8080/play/live.php?stream=228556&play_token=abc123",
                currentCookieHeader = "PHPSESSID=fresh-session"
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            playbackBackendHint = com.streamvault.domain.model.StalkerPlaybackBackendHint.TEMP_LINK_STRICT,
            cookieModeHint = com.streamvault.domain.model.StalkerCookieMode.CREATE_LINK,
            deviceProfile = "MAG322",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.LIVE,
            descriptor = checkNotNull(
                buildStalkerPlaybackDescriptor(
                    primaryCmd = "ffmpeg http://localhost/ch/1200_",
                    capabilities = StalkerPortalCapabilities(useHttpTemporaryLink = true)
                )
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.headers["Cookie"]).contains("PHPSESSID=fresh-session")
        assertThat(success.data.headers["Accept-Encoding"]).isEqualTo("identity")
    }

    @Test
    fun resolvePlaybackInfo_repairs_blank_live_stream_from_localhost_channel_command() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                createLinkUrl = "http://fdox.org:8080/play/live.php?mac=00:1A:79:BA:73:FA&stream=&extension=ts&play_token=abc123"
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            playbackBackendHint = com.streamvault.domain.model.StalkerPlaybackBackendHint.TEMP_LINK_STRICT,
            cookieModeHint = com.streamvault.domain.model.StalkerCookieMode.CREATE_LINK,
            deviceProfile = "MAG322",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.LIVE,
            descriptor = checkNotNull(
                buildStalkerPlaybackDescriptor(
                    primaryCmd = "ffmpeg http://localhost/ch/61523_",
                    capabilities = StalkerPortalCapabilities(useHttpTemporaryLink = true)
                )
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.url).isEqualTo(
            "http://fdox.org:8080/play/live.php?mac=00:1A:79:BA:73:FA&stream=61523&extension=ts&play_token=abc123"
        )
    }

    @Test
    fun resolvePlaybackInfo_sends_absolute_portal_channel_commands_through_create_link() = runTest {
        val api = FakeStalkerApiService(
            profile = StalkerProviderProfile(accountName = "Room"),
            createLinkUrl = "http://fdox.org:8080/play/live.php?mac=00:1A:79:BA:73:FA&stream=390414&extension=ts&play_token=abc123"
        )
        val provider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG322",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.LIVE,
            descriptor = checkNotNull(
                buildStalkerPlaybackDescriptor(
                    primaryCmd = "ffmpeg http://portal.example.com/ch/390414_"
                )
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.url).contains("/play/live.php")
        assertThat(api.createLinkCalls).isEqualTo(1)
    }

    @Test
    fun resolvePlaybackInfo_corrects_stored_direct_mode_for_absolute_portal_channel_commands() = runTest {
        val api = FakeStalkerApiService(
            profile = StalkerProviderProfile(accountName = "Room"),
            createLinkUrl = "http://fdox.org:8080/play/live.php?mac=00:1A:79:BA:73:FA&stream=390414&extension=ts&play_token=abc123"
        )
        val provider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG322",
            timezone = "UTC",
            locale = "en"
        )
        val descriptor = StalkerPlaybackDescriptor(
            primaryMode = StalkerPlaybackMode.DIRECT_URL,
            candidates = listOf(
                StalkerCommandVariant(
                    cmd = "ffmpeg http://portal.example.com/ch/390414_",
                    playbackMode = StalkerPlaybackMode.DIRECT_URL
                )
            )
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.LIVE,
            descriptor = descriptor
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(api.createLinkCalls).isEqualTo(1)
    }

    @Test
    fun authenticate_persists_effective_learned_mag_identity() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(
                    accountName = "Room",
                    effectiveAuthMode = com.streamvault.domain.model.StalkerAuthMode.MAC_ONLY,
                    portalProfile = com.streamvault.domain.model.StalkerPortalProfile.MODULE_GATED,
                    portalFingerprint = com.streamvault.domain.model.StalkerPortalFingerprint.MODULE_GATED,
                    magPreset = com.streamvault.domain.model.StalkerMagPreset.MINISTRA_MODERN,
                    bootstrapRecipe = com.streamvault.domain.model.StalkerBootstrapRecipe.MODULE_GATED,
                    fingerprintEvidence = StalkerFingerprintEvidence(
                        endpointPreference = com.streamvault.domain.model.StalkerEndpointPreference.PORTAL,
                        cookieMode = com.streamvault.domain.model.StalkerCookieMode.BOTH,
                        playbackBackendHint = com.streamvault.domain.model.StalkerPlaybackBackendHint.TEMP_LINK_STRICT
                    )
                )
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.authenticate()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.stalkerMagPreset).isEqualTo(com.streamvault.domain.model.StalkerMagPreset.MINISTRA_MODERN)
        assertThat(success.data.stalkerDeviceProfile).isEqualTo("MAG322")
        assertThat(success.data.stalkerDeviceId).isNotEmpty()
        assertThat(success.data.stalkerSignature).isNotEmpty()
    }

    private class FakeStalkerApiService(
        private val profile: StalkerProviderProfile,
        private val liveStreams: List<StalkerItemRecord> = emptyList(),
        private val createLinkUrl: String = "http://cdn.example.com/stream.ts",
        private val currentCookieHeader: String = ""
    ) : StalkerApiService {
        var createLinkCalls: Int = 0
            private set

        override suspend fun authenticate(profile: StalkerDeviceProfile): Result<Pair<StalkerSession, StalkerProviderProfile>> =
            Result.success(
                StalkerSession(
                    loadUrl = "https://portal.example.com/server/load.php",
                    portalReferer = "https://portal.example.com/c/",
                    token = "token"
                ) to this.profile
            )

        override suspend fun getLiveCategories(
            session: StalkerSession,
            profile: StalkerDeviceProfile
        ) = Result.success(emptyList<StalkerCategoryRecord>())

        override suspend fun getLiveStreams(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?
        ) = Result.success(liveStreams)

        override suspend fun streamLiveStreams(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            onItem: suspend (StalkerItemRecord) -> Unit
        ) = Result.success(0)

        override suspend fun getVodCategories(
            session: StalkerSession,
            profile: StalkerDeviceProfile
        ) = Result.success(emptyList<StalkerCategoryRecord>())

        override suspend fun getVodStreams(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?
        ) = Result.success(emptyList<StalkerItemRecord>())

        override suspend fun getVodStreamsPage(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?,
            page: Int
        ) = Result.success(StalkerPagedItems(emptyList(), page, page, 0))

        override suspend fun getSeriesCategories(
            session: StalkerSession,
            profile: StalkerDeviceProfile
        ) = Result.success(emptyList<StalkerCategoryRecord>())

        override suspend fun getSeries(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?
        ) = Result.success(emptyList<StalkerItemRecord>())

        override suspend fun getSeriesPage(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?,
            page: Int
        ) = Result.success(StalkerPagedItems(emptyList(), page, page, 0))

        override suspend fun getSeriesDetails(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            seriesId: String
        ) = Result.success(
            StalkerSeriesDetails(
                series = StalkerItemRecord(id = seriesId, name = "Series"),
                seasons = emptyList()
            )
        )

        override suspend fun getShortEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            channelId: String,
            limit: Int
        ) = Result.success(emptyList<StalkerProgramRecord>())

        override suspend fun getEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            channelId: String
        ) = Result.success(emptyList<StalkerProgramRecord>())

        override suspend fun getBulkEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            periodHours: Int
        ) = Result.success(emptyList<StalkerProgramRecord>())

        override suspend fun streamBulkEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            periodHours: Int,
            onProgram: suspend (StalkerProgramRecord) -> Unit
        ) = Result.success(0)

        override suspend fun streamEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            channelId: String,
            periodHours: Int,
            onProgram: suspend (StalkerProgramRecord) -> Unit
        ) = Result.success(0)

        override suspend fun createLink(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            kind: StalkerStreamKind,
            cmd: String,
            seriesNumber: Int?,
            archiveStartSeconds: Long?,
            archiveEndSeconds: Long?
        ): Result<String> {
            createLinkCalls += 1
            return Result.success(createLinkUrl)
        }

        override fun currentCookieHeader(session: StalkerSession): String = currentCookieHeader
    }
}
