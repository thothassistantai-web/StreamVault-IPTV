package com.streamvault.data.remote.xtream

import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.stalker.StalkerStreamKind
import com.streamvault.data.remote.stalker.StalkerUrlFactory
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedStreamUrl(
    val url: String,
    val expirationTime: Long? = null,
    val containerExtension: String? = null
)

@Singleton
class XtreamStreamUrlResolver @Inject constructor(
    private val providerDao: ProviderDao,
    private val credentialCrypto: CredentialCrypto,
    private val stalkerApiService: StalkerApiService
) {
    private data class CachedStalkerProvider(
        val serverUrl: String,
        val macAddress: String,
        val deviceProfile: String,
        val timezone: String,
        val locale: String,
        val provider: StalkerProvider
    )

    private val stalkerProviders = ConcurrentHashMap<Long, CachedStalkerProvider>()

    fun isInternalStreamUrl(url: String?): Boolean =
        XtreamUrlFactory.isInternalStreamUrl(url) || StalkerUrlFactory.isInternalStreamUrl(url)

    suspend fun resolve(
        url: String,
        fallbackProviderId: Long? = null,
        fallbackStreamId: Long? = null,
        fallbackContentType: ContentType? = null,
        fallbackContainerExtension: String? = null,
        preferStableUrl: Boolean = false
    ): String? = resolveWithMetadata(
        url = url,
        fallbackProviderId = fallbackProviderId,
        fallbackStreamId = fallbackStreamId,
        fallbackContentType = fallbackContentType,
        fallbackContainerExtension = fallbackContainerExtension,
        preferStableUrl = preferStableUrl
    )?.url

    /**
     * @param preferStableUrl when true, skip the tokenized direct-source URL and return
     *   the credential-based portal URL instead. Use this for long-lived sessions (e.g.
     *   Chromecast) where the token may expire before the session ends.
     */
    suspend fun resolveWithMetadata(
        url: String,
        fallbackProviderId: Long? = null,
        fallbackStreamId: Long? = null,
        fallbackContentType: ContentType? = null,
        fallbackContainerExtension: String? = null,
        preferStableUrl: Boolean = false
    ): ResolvedStreamUrl? {
        if (url.isNotBlank() && !XtreamUrlFactory.isInternalStreamUrl(url) && !StalkerUrlFactory.isInternalStreamUrl(url)) {
            return ResolvedStreamUrl(
                url = url,
                expirationTime = extractStreamExpirationTime(url)
            )
        }

        val xtreamToken = XtreamUrlFactory.parseInternalStreamUrl(url)
        val stalkerToken = StalkerUrlFactory.parseInternalStreamUrl(url)
        val providerId = xtreamToken?.providerId ?: stalkerToken?.providerId ?: fallbackProviderId?.takeIf { it > 0 } ?: return null
        val provider = providerDao.getById(providerId) ?: return null
        return when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                val kind = xtreamToken?.kind ?: fallbackContentType?.let(XtreamUrlFactory::kindForContentType) ?: return null
                val streamId = xtreamToken?.streamId ?: fallbackStreamId?.takeIf { it > 0 } ?: return null
                val ext = xtreamToken?.containerExtension ?: fallbackContainerExtension
                val directSource = xtreamToken?.directSource?.takeIf(UrlSecurityPolicy::isAllowedStreamEntryUrl)
                val decryptedPassword = credentialCrypto.decryptIfNeeded(provider.password)

                val fallbackResolvedUrl = XtreamUrlFactory.buildPlaybackUrl(
                    serverUrl = provider.serverUrl,
                    username = provider.username,
                    password = decryptedPassword,
                    kind = kind,
                    streamId = streamId,
                    containerExtension = ext
                )
                val resolvedUrl = if (preferStableUrl) fallbackResolvedUrl else (directSource ?: fallbackResolvedUrl)

                ResolvedStreamUrl(
                    url = resolvedUrl,
                    expirationTime = extractStreamExpirationTime(resolvedUrl),
                    containerExtension = ext
                )
            }
            ProviderType.STALKER_PORTAL -> {
                val token = stalkerToken ?: return url.takeIf { it.isNotBlank() }?.let { passthroughUrl ->
                    ResolvedStreamUrl(
                        url = passthroughUrl,
                        expirationTime = extractStreamExpirationTime(passthroughUrl)
                    )
                }
                val resolvedUrl = when (
                    val resolvedResult = getOrCreateStalkerProvider(provider).resolvePlaybackUrl(token.kind, token.cmd)
                ) {
                    is com.streamvault.domain.model.Result.Success -> resolvedResult.data
                    else -> return null
                }
                ResolvedStreamUrl(
                    url = resolvedUrl,
                    expirationTime = extractStreamExpirationTime(resolvedUrl),
                    containerExtension = token.containerExtension ?: fallbackContainerExtension
                )
            }
            ProviderType.M3U -> url.takeIf { it.isNotBlank() }?.let { passthroughUrl ->
                ResolvedStreamUrl(
                    url = passthroughUrl,
                    expirationTime = extractStreamExpirationTime(passthroughUrl)
                )
            }
        }
    }

    private fun getOrCreateStalkerProvider(provider: ProviderEntity): StalkerProvider {
        val providerId = provider.id
        val cached = stalkerProviders[providerId]
        if (cached != null &&
            cached.serverUrl == provider.serverUrl &&
            cached.macAddress == provider.stalkerMacAddress &&
            cached.deviceProfile == provider.stalkerDeviceProfile &&
            cached.timezone == provider.stalkerDeviceTimezone &&
            cached.locale == provider.stalkerDeviceLocale
        ) {
            return cached.provider
        }

        val resolvedProvider = StalkerProvider(
            providerId = provider.id,
            api = stalkerApiService,
            portalUrl = provider.serverUrl,
            macAddress = provider.stalkerMacAddress,
            deviceProfile = provider.stalkerDeviceProfile,
            timezone = provider.stalkerDeviceTimezone,
            locale = provider.stalkerDeviceLocale
        )
        stalkerProviders[providerId] = CachedStalkerProvider(
            serverUrl = provider.serverUrl,
            macAddress = provider.stalkerMacAddress,
            deviceProfile = provider.stalkerDeviceProfile,
            timezone = provider.stalkerDeviceTimezone,
            locale = provider.stalkerDeviceLocale,
            provider = resolvedProvider
        )
        return resolvedProvider
    }
}

internal fun extractStreamExpirationTime(url: String): Long? {
    val query = runCatching { URI(url).rawQuery }.getOrNull()
        ?: url.substringAfter('?', missingDelimiterValue = "").takeIf { it.isNotBlank() }
        ?: return null

    val expirationKeys = setOf(
        "expire",
        "expires",
        "expiry",
        "expiration",
        "expires_at",
        "exp",
        "token_exp",
        "token_expires",
        "token_expiry"
    )

    return query.split('&')
        .asSequence()
        .mapNotNull { part ->
            val key = part.substringBefore('=', missingDelimiterValue = "")
                .lowercase()
                .takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (key !in expirationKeys) return@mapNotNull null

            val rawValue = part.substringAfter('=', missingDelimiterValue = "")
            val decodedValue = XtreamUrlCodec.decode(rawValue)
            parseXtreamExpirationDate(decodedValue)
        }
        .firstOrNull()
}
