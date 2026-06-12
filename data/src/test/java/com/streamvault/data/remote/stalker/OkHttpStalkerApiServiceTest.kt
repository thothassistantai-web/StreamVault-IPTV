package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerBootstrapRecipe
import com.streamvault.domain.model.StalkerMagPreset
import com.streamvault.domain.model.StalkerPlaybackBackendHint
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class OkHttpStalkerApiServiceTest {

    @Test
    fun authenticate_retries_with_legacy_recipe_and_updates_profile_metadata() = runTest {
        val requestedVersions = mutableListOf<String>()
        val requestedImages = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    if (action == "get_profile") {
                        requestedVersions += request.url.queryParameter("ver").orEmpty()
                        requestedImages += request.url.queryParameter("image_version").orEmpty()
                    }
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token-123"}}"""
                        "get_profile" -> if (requestedVersions.size <= 2) {
                            ""
                        } else {
                            """{"js":{"id":"42","name":"Legacy Box","status":"1","auth_access":true}}"""
                        }
                        "get_main_info" -> """{"js":{"id":"42","name":"Legacy Box","status":"1","auth_access":true}}"""
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                authMode = StalkerAuthMode.AUTO,
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.first.magPreset).isEqualTo(StalkerMagPreset.MAG250_LEGACY)
        assertThat(success.data.first.bootstrapRecipe).isEqualTo(StalkerBootstrapRecipe.LEGACY_MAG)
        assertThat(success.data.second.magPreset).isEqualTo(StalkerMagPreset.MAG250_LEGACY)
        assertThat(success.data.second.bootstrapRecipe).isEqualTo(StalkerBootstrapRecipe.LEGACY_MAG)
        assertThat(success.data.first.recipeEvidence).containsAtLeast("fallback_recipe", "rediscovery_attempted")
        assertThat(requestedImages).containsExactly("218", "218", "216").inOrder()
        assertThat(requestedVersions.last()).contains("0.2.16-r17-250")
    }

    @Test
    fun authenticate_reads_token_and_profile_from_js_wrapper() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "handshake" to """{"js":{"token":"token-123"}}""",
                "get_profile" to """{"js":{"name":"Living Room","status":"1","max_online":"2"}}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.first.token).isEqualTo("token-123")
        assertThat(success.data.second.accountName).isEqualTo("Living Room")
        assertThat(success.data.second.maxConnections).isEqualTo(2)
    }

    @Test
    fun authenticate_module_gated_recipe_rebuilds_profile_for_modern_mag_preset() = runTest {
        val requestedStbTypes = mutableListOf<String>()
        val requestedAgents = mutableListOf<String>()
        val requestedPaths = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedPaths += request.url.encodedPath
                    val action = request.url.queryParameter("action").orEmpty()
                    if (action == "get_profile") {
                        requestedStbTypes += request.url.queryParameter("stb_type").orEmpty()
                        requestedAgents += request.header("X-User-Agent").orEmpty()
                    }
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token-123"}}"""
                        "get_profile" -> """{"js":{"id":"55","name":"Module Portal","status":"1","auth_access":true}}"""
                        "get_main_info" -> """{"js":{"id":"55","name":"Module Portal","status":"1","auth_access":true}}"""
                        "get_localization" -> """{"js":{"lang":"en","timezone":"UTC"}}"""
                        "get_modules" -> """{"js":{"modules":{"itv":1,"vod":1}}}"""
                        "get_events" -> """{"js":[] }"""
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                authMode = StalkerAuthMode.AUTO,
                magPresetHint = StalkerMagPreset.GENERIC_SAFE,
                portalFingerprintHint = com.streamvault.domain.model.StalkerPortalFingerprint.MODULE_GATED,
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(requestedPaths.first()).isEqualTo("/portal.php")
        assertThat(requestedStbTypes).contains("MAG322")
        assertThat(requestedAgents.last()).contains("MAG322")
    }

    @Test
    fun createLink_reads_cmd_from_js_wrapper() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "create_link" to """{"js":{"cmd":"ffmpeg http://cdn.example.com/live/stream.ts"}}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.createLink(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            kind = StalkerStreamKind.LIVE,
            cmd = "ffmpeg http://placeholder"
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data).isEqualTo("http://cdn.example.com/live/stream.ts")
    }

    @Test
    fun createLink_uses_mag_live_storage_selector_without_changing_vod() = runTest {
        val requested = mutableListOf<Pair<String, String>>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requested += request.url.queryParameter("type").orEmpty() to
                        request.url.queryParameter("forced_storage").orEmpty()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"js":{"cmd":"ffmpeg http://cdn.example.com/media.ts"}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )
        val session = StalkerSession(
            loadUrl = "https://portal.example.com/server/load.php",
            portalReferer = "https://portal.example.com/c/",
            token = "token-123"
        )
        val profile = buildStalkerDeviceProfile(
            portalUrl = "https://portal.example.com/c",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        service.createLink(
            session = session,
            profile = profile,
            kind = StalkerStreamKind.LIVE,
            cmd = "ffmpeg http://localhost/ch/301_"
        )
        service.createLink(
            session = session,
            profile = profile,
            kind = StalkerStreamKind.MOVIE,
            cmd = "ffmpeg http://localhost/movie/401"
        )

        assertThat(requested).containsExactly("itv" to "undefined", "vod" to "0").inOrder()
    }

    @Test
    fun createLink_prefers_portal_endpoint_for_strict_live_temp_links() = runTest {
        val requestedPaths = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedPaths += request.url.encodedPath
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"js":{"cmd":"ffmpeg http://portal.example.com/play/live.php?stream=301&play_token=abc"}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.createLink(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123",
                fingerprintEvidence = StalkerFingerprintEvidence(
                    playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT
                )
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG322",
                timezone = "UTC",
                locale = "en"
            ),
            kind = StalkerStreamKind.LIVE,
            cmd = "ffmpeg http://localhost/ch/301_"
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(requestedPaths).containsExactly("/portal.php")
    }

    @Test
    fun createLink_keeps_server_endpoint_for_strict_vod_links() = runTest {
        val requestedPaths = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedPaths += request.url.encodedPath
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"js":{"cmd":"ffmpeg http://portal.example.com/play/movie.php?stream=401.mkv&play_token=abc"}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.createLink(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123",
                fingerprintEvidence = StalkerFingerprintEvidence(
                    playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT
                )
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG322",
                timezone = "UTC",
                locale = "en"
            ),
            kind = StalkerStreamKind.MOVIE,
            cmd = "ffmpeg http://localhost/movie/401"
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(requestedPaths).containsExactly("/server/load.php")
    }

    @Test
    fun createLink_uses_episode_number_as_series_selector_for_stalker_shell_episode() = runTest {
        var requestedSeries: String? = null
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedSeries = request.url.queryParameter("series")
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"js":{"cmd":"ffmpeg http://cdn.example.com/series/episode11.mkv"}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.createLink(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            kind = StalkerStreamKind.EPISODE,
            cmd = "eyJzZXJpZXNfaWQiOjUzOTk5LCJzZWFzb25fbnVtIjoxLCJ0eXBlIjoic2VyaWVzIn0=",
            seriesNumber = 11
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(requestedSeries).isEqualTo("11")
        val success = result as Result.Success
        assertThat(success.data).isEqualTo("http://cdn.example.com/series/episode11.mkv")
    }

    @Test
    fun createLink_appends_archive_window_for_archive_streams() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"js":{"cmd":"ffmpeg http://portal.example.com/play/live.php?stream=301&play_token=abc"}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.createLink(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            kind = StalkerStreamKind.ARCHIVE,
            cmd = "ffmpeg http://localhost/ch/301_",
            archiveStartSeconds = 1000L,
            archiveEndSeconds = 1300L
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data).contains("utc=1000")
        assertThat(success.data).contains("lutc=1300")
    }

    @Test
    fun buildStalkerDeviceProfile_sanitizes_impossible_auth_mode_hints() {
        val credentialsOnly = buildStalkerDeviceProfile(
            portalUrl = "https://portal.example.com/c",
            macAddress = "",
            authMode = com.streamvault.domain.model.StalkerAuthMode.MAC_PLUS_CREDENTIALS,
            username = "alice",
            password = "secret",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
        val macOnly = buildStalkerDeviceProfile(
            portalUrl = "https://portal.example.com/c",
            macAddress = "00:1A:79:12:34:56",
            authMode = com.streamvault.domain.model.StalkerAuthMode.CREDENTIALS_ONLY,
            username = "",
            password = "",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
        val strictProfile = buildStalkerDeviceProfile(
            portalUrl = "https://portal.example.com/c",
            macAddress = "00:1A:79:12:34:56",
            authMode = com.streamvault.domain.model.StalkerAuthMode.MAC_ONLY,
            magPresetHint = com.streamvault.domain.model.StalkerMagPreset.MAG254_STRICT,
            username = "",
            password = "",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        assertThat(credentialsOnly.authMode).isEqualTo(com.streamvault.domain.model.StalkerAuthMode.CREDENTIALS_ONLY)
        assertThat(macOnly.authMode).isEqualTo(com.streamvault.domain.model.StalkerAuthMode.MAC_ONLY)
        assertThat(strictProfile.deviceProfile).isEqualTo("MAG254")
        assertThat(strictProfile.userAgent).contains("MAG254")
    }

    @Test
    fun authenticate_reads_json_from_callback_wrapper_and_control_char_noise() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "handshake" to "\u0000callback({\"js\":{\"token\":\"token-123\"}});",
                "get_profile" to "\u0000callback({\"js\":{\"name\":\"Living Room\",\"status\":\"1\"}});"
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.first.token).isEqualTo("token-123")
        assertThat(success.data.second.accountName).isEqualTo("Living Room")
    }

    @Test
    fun authenticate_retains_server_cookies_for_follow_up_playback_requests() = runTest {
        val observedCookies = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    if (action == "create_link") {
                        observedCookies += request.header("Cookie").orEmpty()
                    }
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token-123"}}"""
                        "get_profile" -> """{"js":{"name":"Living Room","status":"1"}}"""
                        "create_link" -> """{"js":{"cmd":"ffmpeg http://cdn.example.com/live/stream.ts"}}"""
                        else -> error("Unexpected action '$action'")
                    }
                    val responseBuilder = Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                    if (action == "handshake") {
                        responseBuilder.addHeader("Set-Cookie", "PHPSESSID=session-42; Path=/; HttpOnly")
                    }
                    responseBuilder.build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val authResult = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "Europe/Amsterdam",
                locale = "en us",
                serialNumberOverride = "serial-123",
                deviceIdOverride = "device-123",
                deviceId2Override = "device-456",
                signatureOverride = "signature-789"
            )
        ) as Result.Success

        val createLinkResult = service.createLink(
            session = authResult.data.first,
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "Europe/Amsterdam",
                locale = "en us",
                serialNumberOverride = "serial-123",
                deviceIdOverride = "device-123",
                deviceId2Override = "device-456",
                signatureOverride = "signature-789"
            ),
            kind = StalkerStreamKind.LIVE,
            cmd = "ffmpeg http://localhost/ch/1234_"
        )

        assertThat(createLinkResult).isInstanceOf(Result.Success::class.java)
        assertThat(authResult.data.first.serverCookieHeader).contains("PHPSESSID=session-42")
        assertThat(observedCookies.single()).contains("PHPSESSID=session-42")
        assertThat(observedCookies.single()).contains("mac=00%3A1A%3A79%3A12%3A34%3A56")
        assertThat(observedCookies.single()).contains("stb_lang=en%20us")
        assertThat(observedCookies.single()).contains("timezone=Europe%2FAmsterdam")
        assertThat(observedCookies.single()).doesNotContain("sn=")
        assertThat(observedCookies.single()).doesNotContain("device_id=")
        assertThat(observedCookies.single()).doesNotContain("device_id2=")
        assertThat(observedCookies.single()).doesNotContain("signature=")
    }

    @Test
    fun authenticate_reports_access_denied_html_clearly() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "handshake" to """<!DOCTYPE html><html><body>Access Denied.</body></html>"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.message).isEqualTo("Portal denied the request for handshake.")
    }

    @Test
    fun getLiveCategories_stays_on_selected_endpoint_after_authentication() = runTest {
        val requestedUrls = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    val request = chain.request()
                    requestedUrls += request.url.toString()
                    val body = when (request.url.encodedPath) {
                        "/server/load.php" -> if (request.url.queryParameter("action") == "get_genres") {
                            throw java.io.IOException("\\n not found: limit=1 content=0d…")
                        } else {
                            """{"js":{"token":"token-123"}}"""
                        }
                        "/portal.php" -> """{"js":[{"id":"10","title":"News"}]}"""
                        else -> error("Unexpected path ${request.url.encodedPath}")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                })
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getLiveCategories(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.message).contains("not found")
        assertThat(requestedUrls).containsExactly(
            "https://portal.example.com/server/load.php?type=itv&action=get_genres&JsHttpRequest=1-xml"
        )
    }

    @Test
    fun streamLiveStreams_stays_on_selected_endpoint_after_authentication() = runTest {
        val requestedUrls = mutableListOf<String>()
        val streamed = mutableListOf<StalkerItemRecord>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    val request = chain.request()
                    requestedUrls += request.url.toString()
                    val response = when (request.url.encodedPath) {
                        "/server/load.php" -> throw java.io.IOException("stream endpoint failed")
                        "/portal.php" -> Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(
                                """{"js":{"data":[{"id":"100","name":"News","tv_genre_id":"10","cmd":"ffmpeg http://example.com/live.ts"}]}}"""
                                    .toResponseBody("application/json".toMediaType())
                            )
                            .build()
                        else -> error("Unexpected path ${request.url.encodedPath}")
                    }
                    response
                })
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.streamLiveStreams(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        ) { item ->
            streamed += item
        }

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.message).contains("stream endpoint failed")
        assertThat(streamed).isEmpty()
        assertThat(requestedUrls).containsExactly(
            "https://portal.example.com/server/load.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml"
        )
    }

    @Test
    fun getLiveStreams_prefers_get_all_channels_for_bulk_live_loads() = runTest {
        val requestedActions = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    requestedActions += action
                    val body = when (action) {
                        "get_all_channels" -> """
                            {"js":{"data":[{"id":"100","name":"News","tv_genre_id":"10","cmd":"ffmpeg http://example.com/live.ts"}]}}
                        """.trimIndent()
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getLiveStreams(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            categoryId = null
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.map { it.name }).containsExactly("News")
        assertThat(requestedActions).containsExactly("get_all_channels")
    }

    @Test
    fun getLiveStreams_preserves_command_variants_and_temp_link_flags() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "get_all_channels" to """
                    {"js":{"data":[
                        {
                            "id":"100",
                            "name":"News",
                            "tv_genre_id":"10",
                            "cmd":"ffmpeg http://localhost/ch/100_",
                            "cmd_1":"ffmpeg http://backup.example.com/play/live.php?stream=100",
                            "cmd_2":"ffmpeg http://edge.example.com/live/news.m3u8",
                            "mc_cmd":"ffmpeg http://mc.example.com/live/100.ts",
                            "cmds":[{"url":"ffmpeg http://multi.example.com/live/100.ts"}],
                            "use_http_tmp_link":"1",
                            "nginx_secure_link":"1",
                            "allow_local_timeshift":"1",
                            "archive":"1"
                        }
                    ]}}
                """.trimIndent()
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getLiveStreams(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            categoryId = null
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val item = (result as Result.Success).data.single()
        assertThat(item.commandVariants.map { it.sourceKey })
            .containsAtLeast("cmd", "cmd_1", "cmd_2", "mc_cmd", "cmds[0]")
        assertThat(item.commandVariants.map { it.cmd })
            .contains("ffmpeg http://edge.example.com/live/news.m3u8")
        assertThat(item.playbackDescriptor?.primaryMode).isEqualTo(StalkerPlaybackMode.MULTI_CMD)
        assertThat(item.portalCapabilities.useHttpTemporaryLink).isTrue()
        assertThat(item.portalCapabilities.nginxSecureLink).isTrue()
        assertThat(item.portalCapabilities.allowLocalTimeshift).isTrue()
        assertThat(item.portalCapabilities.archiveAvailable).isTrue()
    }

    @Test
    fun streamLiveStreams_emits_bulk_channels_from_js_data_without_list_materialization() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "get_all_channels" to """
                    {"js":{"data":[
                        {"id":"100","name":"News","tv_genre_id":"10","cmd":"ffmpeg http://example.com/news.ts"},
                        {"id":"101","name":"Sports","tv_genre_id":"11","cmd":"ffmpeg http://example.com/sports.ts"}
                    ]}}
                """.trimIndent()
            ),
            json = Json { ignoreUnknownKeys = true }
        )
        val streamed = mutableListOf<StalkerItemRecord>()

        val result = service.streamLiveStreams(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        ) { item ->
            streamed += item
        }

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data).isEqualTo(2)
        assertThat(streamed.map { it.name }).containsExactly("News", "Sports").inOrder()
        assertThat(streamed.map { it.categoryId }).containsExactly("10", "11").inOrder()
    }

    @Test
    fun getLiveStreams_falls_back_to_paged_get_ordered_list_when_all_channels_is_unavailable() = runTest {
        val requestedUrls = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedUrls += request.url.toString()
                    val action = request.url.queryParameter("action").orEmpty()
                    val response = when (action) {
                        "get_all_channels" -> Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("".toResponseBody("application/json".toMediaType()))
                            .build()
                        "get_ordered_list" -> Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(
                                """
                                    {"js":{"total_items":"1","max_page_items":"50","data":[{"id":"100","name":"News","tv_genre_id":"10","cmd":"ffmpeg http://example.com/live.ts"}]}}
                                """.trimIndent().toResponseBody("application/json".toMediaType())
                            )
                            .build()
                        else -> error("Unexpected action '$action'")
                    }
                    response
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getLiveStreams(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            categoryId = null
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.map { it.name }).containsExactly("News")
        assertThat(requestedUrls).containsAtLeast(
            "https://portal.example.com/server/load.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml",
            "https://portal.example.com/server/load.php?type=itv&action=get_ordered_list&JsHttpRequest=1-xml&force_ch_link_check=0&fav=0&p=1"
        )
    }

    @Test
    fun getSeriesPage_requests_only_requested_page_and_reports_total_pages() = runTest {
        val requestedUrls = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedUrls += request.url.toString()
                    val page = request.url.queryParameter("p")
                    check(page == "3") { "Unexpected page '$page'" }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """
                                {"js":{"total_items":"45","max_page_items":"15","data":[{"id":"300","name":"Drama","category_id":"147"}]}}
                            """.trimIndent().toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getSeriesPage(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            categoryId = "147",
            page = 3
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.items.map { it.name }).containsExactly("Drama")
        assertThat(success.data.page).isEqualTo(3)
        assertThat(success.data.totalPages).isEqualTo(3)
        assertThat(success.data.isComplete).isTrue()
        assertThat(requestedUrls).containsExactly(
            "https://portal.example.com/server/load.php?type=series&action=get_ordered_list&JsHttpRequest=1-xml&category=147&p=3"
        )
    }

    @Test
    fun getSeriesPage_parses_datetime_added_field_into_last_modified_source_timestamp() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "get_ordered_list" to """
                    {"js":{"total_items":"1","max_page_items":"15","data":[{"id":"300","name":"Drama","category_id":"147","added":"2026-05-18 13:02:23","is_series":"1"}]}}
                """.trimIndent()
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getSeriesPage(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            categoryId = "147",
            page = 1
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        val item = success.data.items.single()
        val expectedAddedAt = LocalDateTime.of(2026, 5, 18, 13, 2, 23)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
        assertThat(item.addedAt).isEqualTo(expectedAddedAt)
    }

    @Test
    fun getBulkEpg_parses_channel_ids_from_bulk_response_rows() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "get_epg_info" to """
                    {"js":[
                        {"id":"p1","ch_id":"100","name":"Morning News","descr":"Top stories","start_timestamp":"1700000000","stop_timestamp":"1700003600"},
                        {"id":"p2","channel_id":"sports-guide-id","name":"Live Sports","descr":"Match coverage","start_timestamp":"1700003600","stop_timestamp":"1700007200"}
                    ]}
                """.trimIndent()
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getBulkEpg(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            periodHours = 6
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.map { it.channelId }).containsExactly("100", "sports-guide-id")
        assertThat(success.data.map { it.title }).containsExactly("Morning News", "Live Sports")
    }

    @Test
    fun getSeriesDetails_expands_season_shell_rows_into_episode_placeholders() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "get_ordered_list" to """
                    {"js":{"total_items":1,"max_page_items":14,"data":[{"id":"55000:1","name":"Season 1","description":"Doc","series":[1,2,3,4],"cmd":"eyJzZXJpZXNfaWQiOjU1MDAwLCJzZWFzb25fbnVtIjoxLCJ0eXBlIjoic2VyaWVzIn0=","screenshot_uri":"https://img.example.com/season1.jpg"}]}}
                """.trimIndent()
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getSeriesDetails(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            seriesId = "55000:55000"
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.series.name).isEmpty()
        assertThat(success.data.seasons).hasSize(1)
        val season = success.data.seasons.single()
        assertThat(season.seasonNumber).isEqualTo(1)
        assertThat(season.episodes.map { it.episodeNumber }).containsExactly(1, 2, 3, 4).inOrder()
        assertThat(season.episodes.first().cmd).isEqualTo("eyJzZXJpZXNfaWQiOjU1MDAwLCJzZWFzb25fbnVtIjoxLCJ0eXBlIjoic2VyaWVzIn0=")
    }

    @Test
    fun getSeriesDetails_fetches_shell_season_page_for_explicit_episode_cmds() = runTest {
        val requestedSeasonIds = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    val seasonId = request.url.queryParameter("season_id").orEmpty()
                    val body = when {
                        action != "get_ordered_list" -> error("Unexpected action '$action'")
                        seasonId == "0" -> """
                            {"js":{"total_items":1,"max_page_items":14,"data":[{"id":"55000:1","name":"Season 1","description":"Doc","series":[1,2,3,4],"cmd":"eyJzZXJpZXNfaWQiOjU1MDAwLCJzZWFzb25fbnVtIjoxLCJ0eXBlIjoic2VyaWVzIn0=","screenshot_uri":"https://img.example.com/season1.jpg"}]}}
                        """.trimIndent()
                        seasonId == "1" -> {
                            requestedSeasonIds += seasonId
                            """
                                {"js":{"total_items":1,"max_page_items":14,"data":[{"id":"episode-1","name":"Episode 1","series_number":"1","season_id":"1","cmd":"ffmpeg http://example.com/episode1.mp4","screenshot_uri":"https://img.example.com/episode1.jpg"}]}}
                            """.trimIndent()
                        }
                        else -> error("Unexpected season_id '$seasonId'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getSeriesDetails(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            seriesId = "55000:55000"
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(requestedSeasonIds).containsExactly("1")
        assertThat(success.data.seasons).hasSize(1)
        val season = success.data.seasons.single()
        assertThat(season.episodes).hasSize(1)
        assertThat(season.episodes.single().cmd).isEqualTo("ffmpeg http://example.com/episode1.mp4")
    }

    @Test
    fun getSeriesDetails_preserves_shell_season_numbers_when_followup_rows_omit_them() = runTest {
        val requestedSeasonIds = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    val seasonId = request.url.queryParameter("season_id").orEmpty()
                    val body = when {
                        action != "get_ordered_list" -> error("Unexpected action '$action'")
                        seasonId == "0" ->
                            """
                                {"js":{"total_items":2,"max_page_items":14,"data":[
                                    {"id":"55000:alpha","name":"Season 1","description":"Alpha","series":[1,2],"cmd":"shell-cmd-1"},
                                    {"id":"55000:beta","name":"Season 2","description":"Beta","series":[1,2,3],"cmd":"shell-cmd-2"}
                                ]}}
                            """.trimIndent()
                        seasonId == "1" -> {
                            requestedSeasonIds += seasonId
                            """
                                {"js":{"total_items":2,"max_page_items":14,"data":[
                                    {"id":"55000:alpha","name":"Season 1","description":"Alpha","series":[1,2],"cmd":"shell-cmd-1"},
                                    {"id":"55000:beta","name":"Season 2","description":"Beta","series":[1,2,3],"cmd":"shell-cmd-2"}
                                ]}}
                            """.trimIndent()
                        }
                        seasonId == "2" -> {
                            requestedSeasonIds += seasonId
                            """
                                {"js":{"total_items":2,"max_page_items":14,"data":[
                                    {"id":"55000:alpha","name":"Season 1","description":"Alpha","series":[1,2],"cmd":"shell-cmd-1"},
                                    {"id":"55000:beta","name":"Season 2","description":"Beta","series":[1,2,3],"cmd":"shell-cmd-2"}
                                ]}}
                            """.trimIndent()
                        }
                        else -> error("Unexpected season_id '$seasonId'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getSeriesDetails(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            seriesId = "55000:55000"
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(requestedSeasonIds).containsExactly("1", "2")
        assertThat(success.data.seasons.map { it.seasonNumber }).containsExactly(1, 2).inOrder()
        assertThat(success.data.seasons.map { it.name }).containsExactly("Season 1", "Season 2").inOrder()
        assertThat(success.data.seasons[0].episodes.map { it.episodeNumber }).containsExactly(1, 2).inOrder()
        assertThat(success.data.seasons[1].episodes.map { it.episodeNumber }).containsExactly(1, 2, 3).inOrder()
    }

    private fun fakeClient(vararg responses: Pair<String, String>): OkHttpClient {
        val byAction = responses.toMap()
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val action = request.url.queryParameter("action").orEmpty()
                val body = byAction[action] ?: error("Missing fake response for action '$action'")
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }
}
