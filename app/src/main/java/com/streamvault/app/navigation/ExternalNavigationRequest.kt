package com.streamvault.app.navigation

import java.io.Serializable
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

sealed class ExternalDestination : Serializable {
    data object Home : ExternalDestination()

    data class ProviderSetup(
        val providerId: Long? = null,
        val importUri: String? = null
    ) : ExternalDestination()

    data class MovieDetail(
        val movieId: Long,
        val returnRoute: String? = null
    ) : ExternalDestination()

    data class SeriesDetail(
        val seriesId: Long,
        val returnRoute: String? = null
    ) : ExternalDestination()

    fun toRoute(): String = when (this) {
        Home -> Routes.HOME
        is ProviderSetup -> Routes.providerSetup(providerId = providerId, importUri = importUri)
        is MovieDetail -> Routes.movieDetail(movieId = movieId, returnRoute = returnRoute)
        is SeriesDetail -> Routes.seriesDetail(seriesId = seriesId, returnRoute = returnRoute)
    }

    companion object {
        fun fromLegacyRoute(route: String): ExternalDestination? {
            val normalizedRoute = route.trim()
            if (normalizedRoute.isEmpty()) return null

            return when {
                normalizedRoute == Routes.HOME -> Home
                normalizedRoute.startsWith(Routes.PROVIDER_SETUP.substringBefore('?')) -> {
                    val queryParameters = normalizedRoute.queryParameters()
                    val providerId = queryParameters["providerId"]
                        ?.toLongOrNull()
                        ?.takeIf { it >= 0L }
                    val importUri = queryParameters["importUri"]
                        ?.takeIf { it.isNotBlank() }
                    ProviderSetup(providerId = providerId, importUri = importUri)
                }

                normalizedRoute.startsWith("movie_detail/") -> {
                    val pathSegments = normalizedRoute.pathSegments()
                    val movieId = pathSegments.getOrNull(1)?.toLongOrNull() ?: return null
                    MovieDetail(
                        movieId = movieId,
                        returnRoute = normalizedRoute.queryParameters()["returnRoute"]
                            ?.takeIf { it.isNotBlank() }
                    )
                }

                normalizedRoute.startsWith("series_detail/") -> {
                    val pathSegments = normalizedRoute.pathSegments()
                    val seriesId = pathSegments.getOrNull(1)?.toLongOrNull() ?: return null
                    SeriesDetail(
                        seriesId = seriesId,
                        returnRoute = normalizedRoute.queryParameters()["returnRoute"]
                            ?.takeIf { it.isNotBlank() }
                    )
                }

                else -> null
            }
        }
    }
}

private fun String.pathSegments(): List<String> = substringBefore('?').split('/').filter { it.isNotBlank() }

private fun String.queryParameters(): Map<String, String> {
    val query = substringAfter('?', missingDelimiterValue = "")
    if (query.isBlank()) return emptyMap()
    return query.split('&')
        .mapNotNull { entry ->
            val key = entry.substringBefore('=', missingDelimiterValue = "").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val rawValue = entry.substringAfter('=', missingDelimiterValue = "")
            key to URLDecoder.decode(rawValue, StandardCharsets.UTF_8)
        }
        .toMap()
}

sealed interface ExternalNavigationRequest {
    data class Search(val query: String) : ExternalNavigationRequest
    data class Player(val request: PlayerNavigationRequest) : ExternalNavigationRequest
    data class Destination(val destination: ExternalDestination) : ExternalNavigationRequest
    data class ImportM3u(val uri: String) : ExternalNavigationRequest
    data class ImportBackup(val uri: String) : ExternalNavigationRequest
}
