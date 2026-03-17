package com.streamvault.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class XtreamAuthResponse(
    @SerialName("user_info") val userInfo: XtreamUserInfo,
    @SerialName("server_info") val serverInfo: XtreamServerInfo
)

@Serializable
data class XtreamUserInfo(
    @SerialName("username") val username: String = "",
    @SerialName("password") val password: String = "",
    @SerialName("message") val message: String = "",
    @SerialName("auth") @Serializable(with = LenientIntSerializer::class) val auth: Int = 0,
    @SerialName("status") val status: String = "",
    @SerialName("exp_date") val expDate: String? = null,
    @SerialName("is_trial") val isTrial: String = "0",
    @SerialName("active_cons") val activeConnections: String = "0",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("max_connections") val maxConnections: String = "1",
    @SerialName("allowed_output_formats") val allowedOutputFormats: List<String> = emptyList()
)

@Serializable
data class XtreamServerInfo(
    @SerialName("url") val url: String = "",
    @SerialName("port") val port: String = "",
    @SerialName("https_port") val httpsPort: String = "",
    @SerialName("server_protocol") val serverProtocol: String = "http",
    @SerialName("rtmp_port") val rtmpPort: String = "",
    @SerialName("timezone") val timezone: String = "",
    @SerialName("timestamp_now") @Serializable(with = LenientLongSerializer::class) val timestampNow: Long = 0,
    @SerialName("time_now") val timeNow: String = ""
)

@Serializable
data class XtreamCategory(
    @SerialName("category_id") val categoryId: String = "0",
    @SerialName("category_name") val categoryName: String = "",
    @SerialName("parent_id") @Serializable(with = LenientIntSerializer::class) val parentId: Int = 0
)

@Serializable
data class XtreamStream(
    @SerialName("num") @Serializable(with = LenientIntSerializer::class) val num: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("stream_type") val streamType: String = "",
    @SerialName("stream_id") @Serializable(with = LenientLongSerializer::class) val streamId: Long = 0,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("added") val added: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("custom_sid") val customSid: String? = null,
    @SerialName("tv_archive") @Serializable(with = LenientIntSerializer::class) val tvArchive: Int = 0,
    @SerialName("direct_source") val directSource: String? = null,
    @SerialName("tv_archive_duration") @Serializable(with = LenientNullableIntSerializer::class) val tvArchiveDuration: Int? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    @SerialName("rating") val rating: String? = null,
    @SerialName("rating_5based") val rating5based: String? = null
)

@Serializable
data class XtreamSeriesItem(
    @SerialName("series_id") @Serializable(with = LenientLongSerializer::class) val seriesId: Long = 0,
    @SerialName("name") val name: String = "",
    @SerialName("cover") val cover: String? = null,
    @SerialName("plot") val plot: String? = null,
    @SerialName("cast") val cast: String? = null,
    @SerialName("director") val director: String? = null,
    @SerialName("genre") val genre: String? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
    @SerialName("rating") val rating: String? = null,
    @SerialName("rating_5based") val rating5based: String? = null,
    @SerialName("backdrop_path") val backdropPath: List<String>? = null,
    @SerialName("youtube_trailer") val youtubeTrailer: String? = null,
    @SerialName("episode_run_time") val episodeRunTime: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("last_modified") val lastModified: String? = null
)

@Serializable
data class XtreamSeriesInfoResponse(
    @SerialName("info") val info: XtreamSeriesItem? = null,
    @SerialName("episodes") val episodes: Map<String, List<XtreamEpisode>> = emptyMap(),
    @SerialName("seasons") val seasons: List<XtreamSeason> = emptyList()
)

@Serializable
data class XtreamSeason(
    @SerialName("season_number") @Serializable(with = LenientIntSerializer::class) val seasonNumber: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("cover") val cover: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("episode_count") @Serializable(with = LenientIntSerializer::class) val episodeCount: Int = 0
)

@Serializable
data class XtreamEpisode(
    @SerialName("id") val id: String = "",
    @SerialName("episode_num") @Serializable(with = LenientIntSerializer::class) val episodeNum: Int = 0,
    @SerialName("title") val title: String = "",
    @SerialName("container_extension") val containerExtension: String? = null,
    @SerialName("custom_sid") val customSid: String? = null,
    @SerialName("added") val added: String? = null,
    @SerialName("season") @Serializable(with = LenientIntSerializer::class) val season: Int = 0,
    @SerialName("direct_source") val directSource: String? = null,
    @SerialName("info") val info: XtreamEpisodeInfo? = null
)

@Serializable
data class XtreamEpisodeInfo(
    @SerialName("movie_image") val movieImage: String? = null,
    @SerialName("plot") val plot: String? = null,
    @SerialName("releasedate") val releaseDate: String? = null,
    @SerialName("rating") val rating: String? = null,
    @SerialName("duration_secs") @Serializable(with = LenientIntSerializer::class) val durationSecs: Int = 0,
    @SerialName("duration") val duration: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("bitrate") @Serializable(with = LenientIntSerializer::class) val bitrate: Int = 0
)

@Serializable
data class XtreamVodInfoResponse(
    @SerialName("info") val info: XtreamVodInfo? = null,
    @SerialName("movie_data") val movieData: XtreamVodMovieData? = null
)

@Serializable
data class XtreamVodInfo(
    @SerialName("movie_image") val movieImage: String? = null,
    @SerialName("tmdb_id") @Serializable(with = LenientNullableLongSerializer::class) val tmdbId: Long? = null,
    @SerialName("plot") val plot: String? = null,
    @SerialName("cast") val cast: String? = null,
    @SerialName("director") val director: String? = null,
    @SerialName("genre") val genre: String? = null,
    @SerialName("releasedate") val releaseDate: String? = null,
    @SerialName("rating") val rating: String? = null,
    @SerialName("youtube_trailer") val youtubeTrailer: String? = null,
    @SerialName("duration_secs") @Serializable(with = LenientIntSerializer::class) val durationSecs: Int = 0,
    @SerialName("duration") val duration: String? = null,
    @SerialName("backdrop_path") val backdropPath: List<String>? = null,
    @SerialName("bitrate") @Serializable(with = LenientIntSerializer::class) val bitrate: Int = 0
)

@Serializable
data class XtreamVodMovieData(
    @SerialName("stream_id") @Serializable(with = LenientLongSerializer::class) val streamId: Long = 0,
    @SerialName("name") val name: String = "",
    @SerialName("added") val added: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    @SerialName("custom_sid") val customSid: String? = null,
    @SerialName("direct_source") val directSource: String? = null
)

@Serializable
data class XtreamEpgResponse(
    @SerialName("epg_listings") val epgListings: List<XtreamEpgListing> = emptyList()
)

@Serializable
data class XtreamEpgListing(
    @SerialName("id") val id: String = "",
    @SerialName("epg_id") val epgId: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("lang") val lang: String = "",
    @SerialName("start") val start: String = "",
    @SerialName("end") val end: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("channel_id") val channelId: String = "",
    @SerialName("start_timestamp") @Serializable(with = LenientLongSerializer::class) val startTimestamp: Long = 0,
    @SerialName("stop_timestamp") @Serializable(with = LenientLongSerializer::class) val stopTimestamp: Long = 0,
    @SerialName("now_playing") @Serializable(with = LenientIntSerializer::class) val nowPlaying: Int = 0,
    @SerialName("has_archive") @Serializable(with = LenientIntSerializer::class) val hasArchive: Int = 0
)
