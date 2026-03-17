package com.streamvault.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Movie
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.app.ui.screens.dashboard.DashboardScreen
import com.streamvault.app.ui.screens.favorites.FavoritesScreen
import com.streamvault.app.ui.screens.favorites.FavoriteUiModel
import com.streamvault.app.ui.screens.multiview.MultiViewScreen
import com.streamvault.app.ui.screens.home.HomeScreen
import com.streamvault.app.ui.screens.movies.MoviesScreen
import com.streamvault.app.ui.screens.player.PlayerScreen
import com.streamvault.app.ui.screens.provider.ProviderSetupScreen
import com.streamvault.app.ui.screens.series.SeriesScreen
import com.streamvault.app.ui.screens.settings.SettingsScreen
import com.streamvault.app.ui.screens.welcome.WelcomeScreen
import java.io.Serializable


private const val PLAYER_REQUEST_KEY = "player_request"

data class PlayerNavigationRequest(
    val streamUrl: String,
    val title: String,
    val channelId: String? = null,
    val internalId: Long = -1L,
    val categoryId: Long? = null,
    val providerId: Long? = null,
    val isVirtual: Boolean = false,
    val contentType: String = "LIVE",
    val archiveStartMs: Long? = null,
    val archiveEndMs: Long? = null,
    val archiveTitle: String? = null,
    val returnRoute: String? = null
) : Serializable

object Routes {
    const val PROVIDER_SETUP = "provider_setup?providerId={providerId}"
    const val HOME = "home"
    const val LIVE_TV = "live_tv"
    const val LIVE_TV_DESTINATION = "live_tv?categoryId={categoryId}"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val FAVORITES = "favorites"
    const val EPG = "epg"
    const val EPG_DESTINATION = "epg?categoryId={categoryId}&anchorTime={anchorTime}&favoritesOnly={favoritesOnly}"
    const val SETTINGS = "settings"
    const val PLAYER = "player"
    const val SEARCH = "search"
    const val SERIES_DETAIL = "series_detail/{seriesId}"
    const val WELCOME = "welcome"
    const val PARENTAL_CONTROL_GROUPS = "parental_control_groups/{providerId}"
    const val MULTI_VIEW = "multi_view"


    fun providerSetup(providerId: Long? = null) = "provider_setup?providerId=${providerId ?: -1L}"
    fun liveTv(categoryId: Long? = null) = if (categoryId == null) LIVE_TV else "$LIVE_TV?categoryId=$categoryId"
    fun epg(categoryId: Long? = null, anchorTime: Long? = null, favoritesOnly: Boolean? = null): String {
        val resolvedCategoryId = categoryId ?: -1L
        val resolvedAnchorTime = anchorTime ?: -1L
        val resolvedFavoritesOnly = favoritesOnly ?: false
        return "$EPG?categoryId=$resolvedCategoryId&anchorTime=$resolvedAnchorTime&favoritesOnly=$resolvedFavoritesOnly"
    }

    fun livePlayer(
        channel: Channel,
        categoryId: Long? = channel.categoryId,
        providerId: Long? = channel.providerId,
        isVirtual: Boolean = false,
        returnRoute: String? = null
    ): PlayerNavigationRequest {
        val effectiveCategoryId = categoryId ?: ChannelRepository.ALL_CHANNELS_ID
        return player(
            streamUrl = channel.streamUrl,
            title = channel.name,
            channelId = channel.epgChannelId,
            internalId = channel.id,
            categoryId = effectiveCategoryId,
            providerId = providerId,
            isVirtual = isVirtual,
            contentType = "LIVE",
            returnRoute = returnRoute
        )
    }

    fun moviePlayer(movie: Movie): PlayerNavigationRequest {
        return player(
            streamUrl = movie.streamUrl,
            title = movie.name,
            internalId = movie.id,
            categoryId = movie.categoryId,
            providerId = movie.providerId,
            contentType = "MOVIE"
        )
    }

    fun episodePlayer(episode: Episode): PlayerNavigationRequest {
        return player(
            streamUrl = episode.streamUrl,
            title = "${episode.title} - S${episode.seasonNumber}E${episode.episodeNumber}",
            internalId = episode.id,
            providerId = episode.providerId,
            contentType = "SERIES_EPISODE"
        )
    }

    fun player(
        streamUrl: String,
        title: String,
        channelId: String? = null,
        internalId: Long = -1L,
        categoryId: Long? = null,
        providerId: Long? = null,
        isVirtual: Boolean = false,
        contentType: String = "LIVE",
        archiveStartMs: Long? = null,
        archiveEndMs: Long? = null,
        archiveTitle: String? = null,
        returnRoute: String? = null
    ): PlayerNavigationRequest {
        return PlayerNavigationRequest(
            streamUrl = streamUrl,
            title = title,
            channelId = channelId,
            internalId = internalId,
            categoryId = categoryId,
            providerId = providerId,
            isVirtual = isVirtual,
            contentType = contentType,
            archiveStartMs = archiveStartMs,
            archiveEndMs = archiveEndMs,
            archiveTitle = archiveTitle,
            returnRoute = returnRoute
        )
    }

    fun seriesDetail(seriesId: Long) = "series_detail/$seriesId"
    fun parentalControlGroups(providerId: Long) = "parental_control_groups/$providerId"
}

/** Accepts only media streaming schemes; rejects javascript:, file://, data:, etc. */
private fun isStreamUrlSafe(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val scheme = url.substringBefore("://").lowercase()
    return scheme in setOf("http", "https", "rtsp", "rtmp", "rtsps", "mms", "xtream")
}

private fun NavHostController.navigateToPlayer(request: PlayerNavigationRequest) {
    currentBackStackEntry?.savedStateHandle?.set(PLAYER_REQUEST_KEY, request)
    navigate(Routes.PLAYER) { launchSingleTop = true }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    // NAV-M02/NAV-H02: Single helper replacing repeated tab lambdas without serializing
    // each tab's full UI tree into saved state on every switch.
    fun tabNavigate(route: String) {
        val currentRoute = navController.currentBackStackEntry?.destination?.route
        if (currentRoute == route || currentRoute?.startsWith("$route?") == true) return

        navController.navigate(route) {
            popUpTo(Routes.HOME)
            launchSingleTop = true
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.WELCOME
    ) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onNavigateToHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
                onNavigateToSetup = {
                    navController.navigate(Routes.PROVIDER_SETUP) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.PROVIDER_SETUP,
            arguments = listOf(
                navArgument("providerId") { type = NavType.LongType; defaultValue = -1L }
            )
        ) { backStackEntry ->
            val providerId = backStackEntry.arguments?.getLong("providerId")?.takeIf { it != -1L }
            
            ProviderSetupScreen(
                editProviderId = providerId,
                onProviderAdded = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.PROVIDER_SETUP) { inclusive = true }
                    }
                }
            )
        }
// ...

        composable(Routes.HOME) {
            DashboardScreen(
                onNavigate = { route -> tabNavigate(route) },
                onAddProvider = {
                    navController.navigate(Routes.providerSetup(null))
                },
                onChannelClick = { channel ->
                    navController.navigateToPlayer(
                        Routes.livePlayer(
                            channel = channel,
                            categoryId = channel.categoryId ?: ChannelRepository.ALL_CHANNELS_ID,
                            providerId = channel.providerId,
                            isVirtual = false
                        )
                    )
                },
                onMovieClick = { movie ->
                    navController.navigateToPlayer(Routes.moviePlayer(movie))
                },
                onSeriesClick = { series ->
                    navController.navigate(Routes.seriesDetail(series.id))
                },
                onPlaybackHistoryClick = { history ->
                    val route = when (history.contentType) {
                        com.streamvault.domain.model.ContentType.LIVE -> {
                            Routes.player(
                                streamUrl = history.streamUrl,
                                title = history.title,
                                internalId = history.contentId,
                                providerId = history.providerId,
                                contentType = history.contentType.name
                            )
                        }
                        com.streamvault.domain.model.ContentType.MOVIE -> {
                            Routes.player(
                                streamUrl = history.streamUrl,
                                title = history.title,
                                internalId = history.contentId,
                                providerId = history.providerId,
                                contentType = history.contentType.name
                            )
                        }
                        com.streamvault.domain.model.ContentType.SERIES -> {
                            Routes.seriesDetail(history.seriesId ?: history.contentId)
                        }
                        com.streamvault.domain.model.ContentType.SERIES_EPISODE -> {
                            Routes.player(
                                streamUrl = history.streamUrl,
                                title = history.title,
                                internalId = history.contentId,
                                providerId = history.providerId,
                                contentType = history.contentType.name
                            )
                        }
                    }
                    if (route is PlayerNavigationRequest) {
                        navController.navigateToPlayer(route)
                    } else {
                        navController.navigate(route) { launchSingleTop = true }
                    }
                },
                currentRoute = Routes.HOME
            )
        }

        composable(
            route = Routes.LIVE_TV_DESTINATION,
            arguments = listOf(
                navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L }
            )
        ) { backStackEntry ->
            val initialCategoryId = backStackEntry.arguments?.getLong("categoryId")?.takeIf { it != -1L }
            HomeScreen(
                onChannelClick = { channel, category, provider ->
                    navController.navigateToPlayer(
                        Routes.livePlayer(
                            channel = channel,
                            categoryId = category?.id,
                            providerId = provider?.id,
                            isVirtual = category?.isVirtual == true
                        )
                    )
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.LIVE_TV,
                initialCategoryId = initialCategoryId
            )
        }
// ... (rest of file)

        composable(Routes.MOVIES) {
            MoviesScreen(
                onMovieClick = { movie ->
                    navController.navigateToPlayer(Routes.moviePlayer(movie))
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.MOVIES
            )
        }

        composable(Routes.SERIES) {
            SeriesScreen(
                onSeriesClick = { seriesId ->
                    navController.navigate(Routes.seriesDetail(seriesId))
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.SERIES
            )
        }

        composable(Routes.FAVORITES) {
            FavoritesScreen(
                onItemClick = { item ->
                    val route = when (item.favorite.contentType) {
                        com.streamvault.domain.model.ContentType.LIVE -> {
                            Routes.player(
                                streamUrl = item.streamUrl,
                                title = item.title,
                                channelId = item.epgChannelId,
                                internalId = item.favorite.contentId,
                                categoryId = item.launchCategoryId,
                                providerId = item.providerId,
                                isVirtual = item.launchIsVirtual,
                                contentType = item.favorite.contentType.name
                            )
                        }
                        com.streamvault.domain.model.ContentType.MOVIE -> {
                            Routes.player(
                                streamUrl = item.streamUrl,
                                title = item.title,
                                internalId = item.favorite.contentId,
                                categoryId = item.categoryId,
                                providerId = item.providerId,
                                contentType = item.favorite.contentType.name
                            )
                        }
                        else -> Routes.seriesDetail(item.favorite.contentId)
                    }
                    if (route is PlayerNavigationRequest) {
                        navController.navigateToPlayer(route)
                    } else {
                        navController.navigate(route) { launchSingleTop = true }
                    }
                },
                onHistoryClick = { item ->
                    val route = when (item.history.contentType) {
                        com.streamvault.domain.model.ContentType.LIVE -> {
                            Routes.player(
                                streamUrl = item.history.streamUrl,
                                title = item.title,
                                channelId = item.epgChannelId,
                                internalId = item.history.contentId,
                                categoryId = item.categoryId,
                                providerId = item.providerId,
                                isVirtual = item.launchIsVirtual,
                                contentType = item.history.contentType.name
                            )
                        }
                        com.streamvault.domain.model.ContentType.MOVIE -> {
                            Routes.player(
                                streamUrl = item.history.streamUrl,
                                title = item.title,
                                internalId = item.history.contentId,
                                providerId = item.providerId,
                                contentType = item.history.contentType.name
                            )
                        }
                        com.streamvault.domain.model.ContentType.SERIES -> {
                            Routes.seriesDetail(item.history.seriesId ?: item.history.contentId)
                        }
                        com.streamvault.domain.model.ContentType.SERIES_EPISODE -> {
                            Routes.player(
                                streamUrl = item.history.streamUrl,
                                title = item.title,
                                internalId = item.history.contentId,
                                providerId = item.providerId,
                                contentType = item.history.contentType.name
                            )
                        }
                    }
                    if (route is PlayerNavigationRequest) {
                        navController.navigateToPlayer(route)
                    } else {
                        navController.navigate(route) { launchSingleTop = true }
                    }
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.FAVORITES
            )
        }

        composable(
            route = Routes.EPG_DESTINATION,
            arguments = listOf(
                navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("anchorTime") { type = NavType.LongType; defaultValue = -1L },
                navArgument("favoritesOnly") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            val epgCategoryId = backStackEntry.arguments?.getLong("categoryId")?.takeIf { it != -1L }
            val epgAnchorTime = backStackEntry.arguments?.getLong("anchorTime")?.takeIf { it != -1L }
            val epgFavoritesOnly = backStackEntry.arguments?.getBoolean("favoritesOnly") ?: false
            com.streamvault.app.ui.screens.epg.FullEpgScreen(
                currentRoute = Routes.EPG,
                initialCategoryId = epgCategoryId,
                initialAnchorTime = epgAnchorTime,
                initialFavoritesOnly = epgFavoritesOnly,
                onPlayChannel = { channel, returnRoute ->
                    navController.navigateToPlayer(
                        Routes.livePlayer(
                            channel = channel,
                            categoryId = channel.categoryId ?: ChannelRepository.ALL_CHANNELS_ID,
                            providerId = channel.providerId,
                            isVirtual = false,
                            returnRoute = returnRoute
                        )
                    )
                },
                onPlayArchive = { channel, program, returnRoute ->
                    navController.navigateToPlayer(
                        Routes.player(
                            streamUrl = channel.streamUrl,
                            title = channel.name,
                            channelId = channel.epgChannelId,
                            internalId = channel.id,
                            categoryId = channel.categoryId ?: ChannelRepository.ALL_CHANNELS_ID,
                            providerId = channel.providerId,
                            isVirtual = false,
                            contentType = "LIVE",
                            archiveStartMs = program.startTime,
                            archiveEndMs = program.endTime,
                            archiveTitle = "${channel.name}: ${program.title}",
                            returnRoute = returnRoute
                        )
                    )
                },
                onNavigate = { route -> tabNavigate(route) }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigate = { route -> tabNavigate(route) },
                onAddProvider = {
                    navController.navigate(Routes.providerSetup(null))
                },
                onEditProvider = { provider ->
                    navController.navigate(Routes.providerSetup(provider.id))
                },
                onNavigateToParentalControl = { providerId ->
                    navController.navigate(Routes.parentalControlGroups(providerId))
                },
                currentRoute = Routes.SETTINGS
            )
        }

        composable(
            route = Routes.PARENTAL_CONTROL_GROUPS,
            arguments = listOf(
                navArgument("providerId") { type = NavType.LongType }
            )
        ) {
            com.streamvault.app.ui.screens.settings.parental.ParentalControlGroupScreen(
                currentRoute = Routes.SETTINGS,
                onNavigate = { route -> tabNavigate(route) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SEARCH) {
            com.streamvault.app.ui.screens.search.SearchScreen(
                onChannelClick = { channel ->
                    navController.navigateToPlayer(
                        Routes.livePlayer(
                            channel = channel,
                            categoryId = channel.categoryId ?: ChannelRepository.ALL_CHANNELS_ID,
                            providerId = channel.providerId,
                            isVirtual = false
                        )
                    )
                },
                onMovieClick = { movie ->
                     navController.navigateToPlayer(Routes.moviePlayer(movie))
                },
                onSeriesClick = { series ->
                     navController.navigate(Routes.seriesDetail(series.id))
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.SEARCH
            )
        }

        composable(route = Routes.PLAYER) { backStackEntry ->
            val playerRequest = backStackEntry.savedStateHandle.get<PlayerNavigationRequest>(PLAYER_REQUEST_KEY)
                ?: navController.previousBackStackEntry?.savedStateHandle?.get<PlayerNavigationRequest>(PLAYER_REQUEST_KEY)?.also {
                    backStackEntry.savedStateHandle[PLAYER_REQUEST_KEY] = it
                }
            val streamUrl = if (isStreamUrlSafe(playerRequest?.streamUrl)) playerRequest?.streamUrl.orEmpty() else ""
            PlayerScreen(
                streamUrl = streamUrl,
                title = playerRequest?.title.orEmpty(),
                epgChannelId = playerRequest?.channelId,
                internalChannelId = playerRequest?.internalId ?: -1L,
                categoryId = playerRequest?.categoryId,
                providerId = playerRequest?.providerId,
                isVirtual = playerRequest?.isVirtual ?: false,
                contentType = playerRequest?.contentType ?: "LIVE",
                archiveStartMs = playerRequest?.archiveStartMs,
                archiveEndMs = playerRequest?.archiveEndMs,
                archiveTitle = playerRequest?.archiveTitle,
                returnRoute = playerRequest?.returnRoute,
                onBack = { navController.popBackStack() },
                onNavigate = { route -> navController.navigate(route) }
            )
        }

        composable(
            route = Routes.SERIES_DETAIL,
            arguments = listOf(
                navArgument("seriesId") { type = NavType.LongType }
            )
        ) {
            com.streamvault.app.ui.screens.series.SeriesDetailScreen(
                onEpisodeClick = { episode ->
                     navController.navigateToPlayer(Routes.episodePlayer(episode))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.MULTI_VIEW) {
            MultiViewScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
