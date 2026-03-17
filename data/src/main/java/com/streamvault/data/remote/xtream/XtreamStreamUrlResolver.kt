package com.streamvault.data.remote.xtream

import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XtreamStreamUrlResolver @Inject constructor(
    private val providerDao: ProviderDao
) {
    fun isInternalStreamUrl(url: String?): Boolean = XtreamUrlFactory.isInternalStreamUrl(url)

    suspend fun resolve(
        url: String,
        fallbackProviderId: Long? = null,
        fallbackStreamId: Long? = null,
        fallbackContentType: ContentType? = null,
        fallbackContainerExtension: String? = null
    ): String? {
        if (url.isNotBlank() && !XtreamUrlFactory.isInternalStreamUrl(url)) {
            return url
        }

        val token = XtreamUrlFactory.parseInternalStreamUrl(url)
        val providerId = token?.providerId ?: fallbackProviderId?.takeIf { it > 0 } ?: return null
        val provider = providerDao.getById(providerId) ?: return null
        if (provider.type != ProviderType.XTREAM_CODES) {
            return url.takeIf { it.isNotBlank() }
        }

        val kind = token?.kind ?: fallbackContentType?.let(XtreamUrlFactory::kindForContentType) ?: return null
        val streamId = token?.streamId ?: fallbackStreamId?.takeIf { it > 0 } ?: return null
        val ext = token?.containerExtension ?: fallbackContainerExtension
        val decryptedPassword = CredentialCrypto.decryptIfNeeded(provider.password)

        return XtreamUrlFactory.buildPlaybackUrl(
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = decryptedPassword,
            kind = kind,
            streamId = streamId,
            containerExtension = ext
        )
    }
}