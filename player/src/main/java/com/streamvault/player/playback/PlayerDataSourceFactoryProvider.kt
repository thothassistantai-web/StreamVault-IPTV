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
import okhttp3.OkHttpClient
import okhttp3.Protocol

internal fun shouldUsePlatformHttpDataSource(resolvedStreamType: ResolvedStreamType): Boolean =
    false

@UnstableApi
class PlayerDataSourceFactoryProvider(
    private val context: Context,
    private val baseClient: OkHttpClient
) {
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
        val headers = streamInfo.headers
        val forceHttp1 = PlayerHttpProtocolPolicy.forceHttp1(
            resolvedStreamType = resolvedStreamType,
            vodHttpProtocolMode = vodHttpProtocolMode
        )
        val port = streamPort(streamInfo.url)
        val client = clientsByKey.computeIfAbsent(ClientKey(profile, forceHttp1, port)) {
            baseClient.newBuilder()
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
            streamInfo.userAgent?.takeIf { it.isNotBlank() }?.let(::setUserAgent)
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

    private fun streamPort(url: String): Int {
        val uri = runCatching { URI(url) }.getOrNull()
        uri?.port?.takeIf { it > 0 }?.let { return it }
        return when (uri?.scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
    }

    private companion object {
        const val TAG = "PlayerDataSourceFactory"
    }
}
