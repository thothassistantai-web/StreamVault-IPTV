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
    @SerialName("allowed_output_formats") @Serializable(with = LenientStringListSerializer::class) val allowedOutputFormats: List<String> = emptyList()
)

@Serializable
data class XtreamServerInfo(
    @SerialName("url") val url: String = "",
    @SerialName("port") val port: String = "",
    @SerialName("https_port") val httpsPort: String = "",
    @SerialName("server_protocol") val serverProtocol: String = "http",
    @SerialName("rtmp_port") val rtmpPort: String = "",
    @SerialName("api_version") val apiVersion: String? = null,
    @SerialName("version") val version: String? = null,
    @SerialName("timezone") val timezone: String = "",
    @SerialName("timestamp_now") @Serializable(with = LenientLongSerializer::class) val timestampNow: Long = 0,
    @SerialName("time_now") val timeNow: String = ""
)

@Serializable
data class XtreamCategory(
    @SerialName("category_id") @Serializable(with = LenientStringSerializer::class) val categoryId: String = "0",
    @SerialName("category_name") @Serializable(with = LenientStringSerializer::class) val categoryName: String = "",
    @SerialName("parent_id") @Serializable(with = LenientIntSerializer::class) val parentId: Int = 0,
    @SerialName("is_adult") @Serializable(with = LenientNullableBooleanSerializer::class) val isAdult: Boolean? = null
)

@Serializable
data class XtreamStream(
    @SerialName("num") @Serializable(with = LenientIntSerializer::class) val num: Int = 0,
    @SerialName("name") @Serializable(with = LenientStringSerializer::class) val name: String = "",
    @SerialName("stream_type") @Serializable(with = LenientStringSerializer::class) val streamType: String = "",
    @SerialName("stream_id") @Serializable(with = LenientLongSerializer::class) val streamId: Long = 0,
    @SerialName("stream_icon") @Serializable(with = LenientNullableStringSerializer::class) val streamIcon: String? = null,
    @SerialName("cover_big") @Serializable(with = LenientNullableStringSerializer::class) val coverBig: String? = null,
    @SerialName("epg_channel_id") @Serializable(with = LenientNullableStringSerializer::class) val epgChannelId: String? = null,
    @SerialName("added") @Serializable(with = LenientNullableStringSerializer::class) val added: String? = null,
    @SerialName("category_id") @Serializable(with = LenientNullableStringSerializer::class) val categoryId: String? = null,
    @SerialName("category_name") @Serializable(with = LenientNullableStringSerializer::class) val categoryName: String? = null,
    @SerialName("category_ids") @Serializable(with = LenientNullableStringListSerializer::class) val categoryIds: List<String>? = null,
    @SerialName("custom_sid") @Serializable(with = LenientNullableStringSerializer::class) val customSid: String? = null,
    @SerialName("tv_archive") @Serializable(with = LenientIntSerializer::class) val tvArchive: Int = 0,
    @SerialName("direct_source") @Serializable(with = LenientNullableStringSerializer::class) val directSource: String? = null,
    @SerialName("tv_archive_duration") @Serializable(with = LenientNullableIntSerializer::class) val tvArchiveDuration: Int? = null,
    @SerialName("container_extension") @Serializable(with = LenientNullableStringSerializer::class) val containerExtension: String? = null,
    @SerialName("rating") @Serializable(with = LenientNullableStringSerializer::class) val rating: String? = null,
    @SerialName("rating_5based") @Serializable(with = LenientNullableStringSerializer::class) val rating5based: String? = null,
    @SerialName("tmdb") @Serializable(with = LenientNullableStringSerializer::class) val tmdb: String? = null,
    @SerialName("trailer") @Serializable(with = LenientNullableStringSerializer::class) val trailer: String? = null,
    @SerialName("youtube_trailer") @Serializable(with = LenientNullableStringSerializer::class) val youtubeTrailer: String? = null,
    @SerialName("is_adult") @Serializable(with = LenientNullableBooleanSerializer::class) val isAdult: Boolean? = null
)

@Serializable
data class XtreamLiveStreamRow(
    @SerialName("num") @Serializable(with = LenientIntSerializer::class) val num: Int = 0,
    @SerialName("name") @Serializable(with = LenientStringSerializer::class) val name: String = "",
    @SerialName("stream_id") @Serializable(with = LenientLongSerializer::class) val streamId: Long = 0,
    @SerialName("stream_icon") @Serializable(with = LenientNullableStringSerializer::class) val streamIcon: String? = null,
    @SerialName("epg_channel_id") @Serializable(with = LenientNullableStringSerializer::class) val epgChannelId: String? = null,
    @SerialName("category_id") @Serializable(with = LenientNullableStringSerializer::class) val categoryId: String? = null,
    @SerialName("category_name") @Serializable(with = LenientNullableStringSerializer::class) val categoryName: String? = null,
    @SerialName("category_ids") @Serializable(with = LenientNullableStringListSerializer::class) val categoryIds: List<String>? = null,
    @SerialName("tv_archive") @Serializable(with = LenientIntSerializer::class) val tvArchive: Int = 0,
    @SerialName("tv_archive_duration") @Serializable(with = LenientNullableIntSerializer::class) val tvArchiveDuration: Int? = null,
    @SerialName("container_extension") @Serializable(with = LenientNullableStringSerializer::class) val containerExtension: String? = null,
    @SerialName("is_adult") @Serializable(with = LenientNullableBooleanSerializer::class) val isAdult: Boolean? = null
)

@Serializable
data class XtreamSeriesItem(
    @SerialName("series_id") @Serializable(with = LenientLongSerializer::class) val seriesId: Long = 0,
    @SerialName("name") @Serializable(with = LenientStringSerializer::class) val name: String = "",
    @SerialName("cover") @Serializable(with = LenientNullableStringSerializer::class) val cover: String? = null,
    @SerialName("cover_big") @Serializable(with = LenientNullableStringSerializer::class) val coverBig: String? = null,
    @SerialName("movie_image") @Serializable(with = LenientNullableStringSerializer::class) val movieImage: String? = null,
    @SerialName("plot") @Serializable(with = LenientNullableStringSerializer::class) val plot: String? = null,
    @SerialName("description") @Serializable(with = LenientNullableStringSerializer::class) val description: String? = null,
    @SerialName("cast") @Serializable(with = LenientNullableStringSerializer::class) val cast: String? = null,
    @SerialName("director") @Serializable(with = LenientNullableStringSerializer::class) val director: String? = null,
    @SerialName("genre") @Serializable(with = LenientNullableStringSerializer::class) val genre: String? = null,
    @SerialName("releaseDate") @Serializable(with = LenientNullableStringSerializer::class) val releaseDate: String? = null,
    @SerialName("releasedate") @Serializable(with = LenientNullableStringSerializer::class) val releaseDateAlt: String? = null,
    @SerialName("rating") @Serializable(with = LenientNullableStringSerializer::class) val rating: String? = null,
    @SerialName("rating_5based") @Serializable(with = LenientNullableStringSerializer::class) val rating5based: String? = null,
    @SerialName("backdrop_path") @Serializable(with = LenientNullableStringListSerializer::class) val backdropPath: List<String>? = null,
    @SerialName("youtube_trailer") @Serializable(with = LenientNullableStringSerializer::class) val youtubeTrailer: String? = null,
    @SerialName("trailer") @Serializable(with = LenientNullableStringSerializer::class) val trailer: String? = null,
    @SerialName("tmdb") @Serializable(with = LenientNullableStringSerializer::class) val tmdb: String? = null,
    @SerialName("tmdb_id") @Serializable(with = LenientNullableStringSerializer::class) val tmdbId: String? = null,
    @SerialName("episode_run_time") @Serializable(with = LenientNullableStringSerializer::class) val episodeRunTime: String? = null,
    @SerialName("category_id") @Serializable(with = LenientNullableStringSerializer::class) val categoryId: String? = null,
    @SerialName("category_name") @Serializable(with = LenientNullableStringSerializer::class) val categoryName: String? = null,
    @SerialName("last_modified") @Serializable(with = LenientNullableStringSerializer::class) val lastModified: String? = null,
    @SerialName("is_adult") @Serializable(with = LenientNullableBooleanSerializer::class) val isAdult: Boolean? = null
)

@Serializable(with = XtreamSeriesInfoResponseSerializer::class)
data class XtreamSeriesInfoResponse(
    val info: XtreamSeriesItem? = null,
    val episodes: Map<String, List<XtreamEpisode>> = emptyMap(),
    val seasons: List<XtreamSeason> = emptyList()
)

@Serializable
data class XtreamSeason(
    @SerialName("season_number") @Serializable(with = LenientIntSerializer::class) val seasonNumber: Int = 0,
    @SerialName("name") @Serializable(with = LenientStringSerializer::class) val name: String = "",
    @SerialName("cover") @Serializable(with = LenientNullableStringSerializer::class) val cover: String? = null,
    @SerialName("air_date") @Serializable(with = LenientNullableStringSerializer::class) val airDate: String? = null,
    @SerialName("episode_count") @Serializable(with = LenientIntSerializer::class) val episodeCount: Int = 0
)

@Serializable
data class XtreamEpisode(
    @SerialName("id") @Serializable(with = LenientStringSerializer::class) val id: String = "",
    @SerialName("episode_num") @Serializable(with = LenientIntSerializer::class) val episodeNum: Int = 0,
    @SerialName("title") @Serializable(with = LenientStringSerializer::class) val title: String = "",
    @SerialName("container_extension") @Serializable(with = LenientNullableStringSerializer::class) val containerExtension: String? = null,
    @SerialName("custom_sid") @Serializable(with = LenientNullableStringSerializer::class) val customSid: String? = null,
    @SerialName("added") @Serializable(with = LenientNullableStringSerializer::class) val added: String? = null,
    @SerialName("season") @Serializable(with = LenientIntSerializer::class) val season: Int = 0,
    @SerialName("direct_source") @Serializable(with = LenientNullableStringSerializer::class) val directSource: String? = null,
    @SerialName("info") val info: XtreamEpisodeInfo? = null
)

@Serializable
data class XtreamEpisodeInfo(
    @SerialName("movie_image") @Serializable(with = LenientNullableStringSerializer::class) val movieImage: String? = null,
    @SerialName("plot") @Serializable(with = LenientNullableStringSerializer::class) val plot: String? = null,
    @SerialName("releasedate") @Serializable(with = LenientNullableStringSerializer::class) val releaseDate: String? = null,
    @SerialName("rating") @Serializable(with = LenientNullableStringSerializer::class) val rating: String? = null,
    @SerialName("duration_secs") @Serializable(with = LenientIntSerializer::class) val durationSecs: Int = 0,
    @SerialName("duration") @Serializable(with = LenientNullableStringSerializer::class) val duration: String? = null,
    @SerialName("name") @Serializable(with = LenientNullableStringSerializer::class) val name: String? = null,
    @SerialName("bitrate") @Serializable(with = LenientIntSerializer::class) val bitrate: Int = 0
)

@Serializable
data class XtreamVodInfoResponse(
    @SerialName("info") val info: XtreamVodInfo? = null,
    @SerialName("movie_data") val movieData: XtreamVodMovieData? = null
)

@Serializable
data class XtreamVodInfo(
    @SerialName("movie_image") @Serializable(with = LenientNullableStringSerializer::class) val movieImage: String? = null,
    @SerialName("tmdb_id") @Serializable(with = LenientNullableLongSerializer::class) val tmdbId: Long? = null,
    @SerialName("plot") @Serializable(with = LenientNullableStringSerializer::class) val plot: String? = null,
    @SerialName("cast") @Serializable(with = LenientNullableStringSerializer::class) val cast: String? = null,
    @SerialName("director") @Serializable(with = LenientNullableStringSerializer::class) val director: String? = null,
    @SerialName("genre") @Serializable(with = LenientNullableStringSerializer::class) val genre: String? = null,
    @SerialName("releasedate") @Serializable(with = LenientNullableStringSerializer::class) val releaseDate: String? = null,
    @SerialName("rating") @Serializable(with = LenientNullableStringSerializer::class) val rating: String? = null,
    @SerialName("rating_5based") @Serializable(with = LenientNullableStringSerializer::class) val rating5based: String? = null,
    @SerialName("youtube_trailer") @Serializable(with = LenientNullableStringSerializer::class) val youtubeTrailer: String? = null,
    @SerialName("duration_secs") @Serializable(with = LenientIntSerializer::class) val durationSecs: Int = 0,
    @SerialName("duration") @Serializable(with = LenientNullableStringSerializer::class) val duration: String? = null,
    @SerialName("backdrop_path") @Serializable(with = LenientNullableStringListSerializer::class) val backdropPath: List<String>? = null,
    @SerialName("bitrate") @Serializable(with = LenientIntSerializer::class) val bitrate: Int = 0
)

@Serializable
data class XtreamVodMovieData(
    @SerialName("stream_id") @Serializable(with = LenientLongSerializer::class) val streamId: Long = 0,
    @SerialName("name") @Serializable(with = LenientStringSerializer::class) val name: String = "",
    @SerialName("added") @Serializable(with = LenientNullableStringSerializer::class) val added: String? = null,
    @SerialName("category_id") @Serializable(with = LenientNullableStringSerializer::class) val categoryId: String? = null,
    @SerialName("container_extension") @Serializable(with = LenientNullableStringSerializer::class) val containerExtension: String? = null,
    @SerialName("custom_sid") @Serializable(with = LenientNullableStringSerializer::class) val customSid: String? = null,
    @SerialName("direct_source") @Serializable(with = LenientNullableStringSerializer::class) val directSource: String? = null,
    @SerialName("tmdb") @Serializable(with = LenientNullableStringSerializer::class) val tmdb: String? = null,
    @SerialName("trailer") @Serializable(with = LenientNullableStringSerializer::class) val trailer: String? = null,
    @SerialName("youtube_trailer") @Serializable(with = LenientNullableStringSerializer::class) val youtubeTrailer: String? = null,
    @SerialName("is_adult") @Serializable(with = LenientNullableBooleanSerializer::class) val isAdult: Boolean? = null
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
