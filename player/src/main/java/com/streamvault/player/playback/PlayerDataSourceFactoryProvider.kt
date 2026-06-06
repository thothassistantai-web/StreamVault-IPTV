package com.streamvault.player.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.model.StreamInfo
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol

internal fun shouldUsePlatformHttpDataSource(resolvedStreamType: ResolvedStreamType): Boolean =
    false

@UnstableApi
class PlayerDataSourceFactoryProvider(
    private val context: Context,
    private val baseClient: OkHttpClient
) {
    private companion object {
        private const val TAG = "PlayerDataSource"
    }

    private data class ClientKey(
        val profile: PlayerTimeoutProfile,
        val forceHttp1: Boolean,
        val port: Int
    )

    private val addressHealthStore = PlayerAddressHealthStore()
    private val clientsByKey = ConcurrentHashMap<ClientKey, OkHttpClient>()

    fun createFactory(
        streamInfo: StreamInfo,
        resolvedStreamType: ResolvedStreamType,
        vodHttpProtocolMode: VodHttpProtocolMode = VodHttpProtocolMode.COMPATIBILITY_HTTP1,
        preload: Boolean = false
    ): Pair<PlayerTimeoutProfile, DataSource.Factory> {
        val profile = PlayerTimeoutProfile.resolve(streamInfo, resolvedStreamType, preload)
        val headers = effectivePlaybackRequestProperties(
            headers = streamInfo.headers,
            userAgent = streamInfo.userAgent
        )
        logRequestShape(streamInfo, headers, preload)
        val forceHttp1 = PlayerHttpProtocolPolicy.forceHttp1(
            resolvedStreamType = resolvedStreamType,
            vodHttpProtocolMode = vodHttpProtocolMode
        )
        val port = streamPort(streamInfo.url)
        val client = clientsByKey.computeIfAbsent(ClientKey(profile, forceHttp1, port)) {
            baseClient.newBuilder()
                .addInterceptor(StalkerPlaybackRequestLoggingInterceptor)
                .connectTimeout(profile.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(profile.readTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(profile.writeTimeoutMs, TimeUnit.MILLISECONDS)
                .dns(PlayerDnsPolicy.healthAwareDns(port = port, healthStore = addressHealthStore))
                .eventListener(PlayerAddressHealthEventListener(addressHealthStore))
                .apply {
                    if (forceHttp1) {
                        protocols(listOf(Protocol.HTTP_1_1))
                    }
                }
                .build()
        }
        if (forceHttp1) {
            Log.i(TAG, "data-source streamType=$resolvedStreamType timeout=$profile httpProtocol=HTTP_1_1")
        }
        val upstreamFactory = OkHttpDataSource.Factory(client).apply {
            if (headers.isNotEmpty()) {
                setDefaultRequestProperties(headers)
            }
        }
        val defaultFactory = DefaultDataSource.Factory(context, upstreamFactory)
        val factory = if (shouldWrapDataSourceReadStats(resolvedStreamType)) {
            PlayerDataSourceReadStatsFactory(
                upstream = defaultFactory,
                resolvedStreamType = resolvedStreamType,
                initialTargetUrl = streamInfo.url
            )
        } else {
            defaultFactory
        }
        return profile to factory
    }

    private fun logRequestShape(
        streamInfo: StreamInfo,
        headers: Map<String, String>,
        preload: Boolean
    ) {
        val hasStalkerHeaders = headers.containsKey("X-User-Agent") ||
            headers.containsKey("Authorization") ||
            headers["Cookie"]?.contains("mac=", ignoreCase = true) == true
        if (!hasStalkerHeaders) {
            return
        }
        val uri = runCatching { URI(streamInfo.url) }.getOrNull()
        Log.d(
            TAG,
            "Playback request headers preload=$preload host=${uri?.host.orEmpty()} path=${uri?.path.orEmpty()} " +
                "ua=${!streamInfo.userAgent.isNullOrBlank()} referer=${headers.containsKey("Referer")} " +
                "cookie=${headers.containsKey("Cookie")} auth=${headers.containsKey("Authorization")} " +
                "xua=${headers.containsKey("X-User-Agent")}"
        )
    }

    private fun streamPort(url: String): Int {
        val uri = runCatching { URI(url) }.getOrNull()
        uri?.port?.takeIf { it > 0 }?.let { return it }
        return when (uri?.scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
    }
}

internal fun effectivePlaybackRequestProperties(
    headers: Map<String, String>,
    userAgent: String?
): Map<String, String> {
    val normalizedUserAgent = userAgent?.trim().orEmpty()
    if (normalizedUserAgent.isBlank()) {
        return headers
    }
    return buildMap(headers.size + 1) {
        headers.forEach { (name, value) ->
            if (!name.equals("User-Agent", ignoreCase = true)) {
                put(name, value)
            }
        }
        put("User-Agent", normalizedUserAgent)
    }
}

private object StalkerPlaybackRequestLoggingInterceptor : Interceptor {
    private const val TAG = "PlayerDataSource"

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        if (!request.hasStalkerPlaybackShape()) {
            return chain.proceed(request)
        }
        Log.d(
            TAG,
            "Playback request actual method=${request.method} target=${PlaybackLogSanitizer.sanitizeUrl(request.url.toString())} " +
                "ua=${request.header("User-Agent") != null} referer=${request.header("Referer") != null} " +
                "cookie=${request.header("Cookie") != null} auth=${request.header("Authorization") != null} " +
                "xua=${request.header("X-User-Agent") != null} range=${request.header("Range") != null} " +
                "acceptEncoding=${request.header("Accept-Encoding")?.take(24).orEmpty()} cookieKeys=${request.cookieKeySummary()}"
        )
        val response = chain.proceed(request)
        Log.d(
            TAG,
            "Playback response actual target=${PlaybackLogSanitizer.sanitizeUrl(request.url.toString())} " +
                "code=${response.code} length=${response.header("Content-Length").orEmpty()} " +
                "type=${response.header("Content-Type").orEmpty()}"
        )
        return response
    }

    private fun okhttp3.Request.hasStalkerPlaybackShape(): Boolean {
        val path = url.encodedPath.lowercase()
        return header("X-User-Agent") != null ||
            header("Authorization") != null ||
            header("Cookie")?.contains("mac=", ignoreCase = true) == true ||
            path.endsWith("/play/live.php") ||
            path.endsWith("/play/movie.php")
    }

    private fun okhttp3.Request.cookieKeySummary(): String {
        val cookie = header("Cookie") ?: return ""
        return cookie.split(';')
            .mapNotNull { part -> part.substringBefore('=', missingDelimiterValue = "").trim().takeIf(String::isNotBlank) }
            .take(12)
            .joinToString("|")
    }
}
