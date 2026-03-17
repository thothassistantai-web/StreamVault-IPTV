package com.streamvault.data.mapper

import com.streamvault.data.local.entity.*
import com.streamvault.domain.model.*

// ── Provider ───────────────────────────────────────────────────────

fun ProviderEntity.toDomain() = Provider(
    id = id,
    name = name,
    type = type,
    serverUrl = serverUrl,
    username = username,
    password = password,
    m3uUrl = m3uUrl,
    epgUrl = epgUrl,
    isActive = isActive,
    maxConnections = maxConnections,
    expirationDate = expirationDate,
    status = status,
    lastSyncedAt = lastSyncedAt,
    createdAt = createdAt
)

fun Provider.toEntity() = ProviderEntity(
    id = id,
    name = name,
    type = type,
    serverUrl = serverUrl,
    username = username,
    password = password,
    m3uUrl = m3uUrl,
    epgUrl = epgUrl,
    isActive = isActive,
    maxConnections = maxConnections,
    expirationDate = expirationDate,
    status = status,
    lastSyncedAt = lastSyncedAt,
    createdAt = createdAt
)

// ── Channel ────────────────────────────────────────────────────────

fun ChannelEntity.toDomain() = Channel(
    id = id,
    name = name,
    logoUrl = logoUrl,
    groupTitle = groupTitle,
    categoryId = categoryId,
    categoryName = categoryName,
    streamUrl = streamUrl,
    epgChannelId = epgChannelId,
    number = number,
    catchUpSupported = catchUpSupported,
    catchUpDays = catchUpDays,
    catchUpSource = catchUpSource,
    providerId = providerId,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    logicalGroupId = logicalGroupId,
    errorCount = errorCount,
    streamId = streamId
)

fun Channel.toEntity() = ChannelEntity(
    id = id,
    streamId = streamId.takeIf { it > 0 } ?: id,
    name = name,
    logoUrl = logoUrl,
    groupTitle = groupTitle,
    categoryId = categoryId,
    categoryName = categoryName,
    streamUrl = streamUrl,
    epgChannelId = epgChannelId,
    number = number,
    catchUpSupported = catchUpSupported,
    catchUpDays = catchUpDays,
    catchUpSource = catchUpSource,
    providerId = providerId,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    logicalGroupId = logicalGroupId,
    errorCount = errorCount
)

// ── Movie ──────────────────────────────────────────────────────────

fun MovieEntity.toDomain() = Movie(
    id = id,
    name = name,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    categoryId = categoryId,
    categoryName = categoryName,
    streamUrl = streamUrl,
    containerExtension = containerExtension,
    plot = plot,
    cast = cast,
    director = director,
    genre = genre,
    releaseDate = releaseDate,
    duration = duration,
    durationSeconds = durationSeconds,
    rating = rating,
    year = year,
    tmdbId = tmdbId,
    youtubeTrailer = youtubeTrailer,
    providerId = providerId,
    watchProgress = watchProgress,
    lastWatchedAt = lastWatchedAt,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    streamId = streamId
)

fun Movie.toEntity() = MovieEntity(
    id = id,
    streamId = streamId.takeIf { it > 0 } ?: id,
    name = name,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    categoryId = categoryId,
    categoryName = categoryName,
    streamUrl = streamUrl,
    containerExtension = containerExtension,
    plot = plot,
    cast = cast,
    director = director,
    genre = genre,
    releaseDate = releaseDate,
    duration = duration,
    durationSeconds = durationSeconds,
    rating = rating,
    year = year,
    tmdbId = tmdbId,
    youtubeTrailer = youtubeTrailer,
    providerId = providerId,
    watchProgress = watchProgress,
    lastWatchedAt = lastWatchedAt,
    isAdult = isAdult,
    isUserProtected = isUserProtected
)

// ── Series ─────────────────────────────────────────────────────────

fun SeriesEntity.toDomain() = Series(
    id = id,
    name = name,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    categoryId = categoryId,
    categoryName = categoryName,
    plot = plot,
    cast = cast,
    director = director,
    genre = genre,
    releaseDate = releaseDate,
    rating = rating,
    tmdbId = tmdbId,
    youtubeTrailer = youtubeTrailer,
    episodeRunTime = episodeRunTime,
    lastModified = lastModified,
    providerId = providerId,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    seriesId = seriesId
)

fun Series.toEntity() = SeriesEntity(
    id = id,
    seriesId = seriesId.takeIf { it > 0 } ?: id,
    name = name,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    categoryId = categoryId,
    categoryName = categoryName,
    plot = plot,
    cast = cast,
    director = director,
    genre = genre,
    releaseDate = releaseDate,
    rating = rating,
    tmdbId = tmdbId,
    youtubeTrailer = youtubeTrailer,
    episodeRunTime = episodeRunTime,
    lastModified = lastModified,
    providerId = providerId,
    isAdult = isAdult,
    isUserProtected = isUserProtected
)

// ── Episode ────────────────────────────────────────────────────────

fun EpisodeEntity.toDomain() = Episode(
    id = id,
    title = title,
    episodeNumber = episodeNumber,
    seasonNumber = seasonNumber,
    streamUrl = streamUrl,
    containerExtension = containerExtension,
    coverUrl = coverUrl,
    plot = plot,
    duration = duration,
    durationSeconds = durationSeconds,
    rating = rating,
    releaseDate = releaseDate,
    seriesId = seriesId,
    providerId = providerId,
    watchProgress = watchProgress,
    lastWatchedAt = lastWatchedAt,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    episodeId = episodeId
)

fun Episode.toEntity() = EpisodeEntity(
    id = id,
    episodeId = episodeId.takeIf { it > 0 } ?: id,
    title = title,
    episodeNumber = episodeNumber,
    seasonNumber = seasonNumber,
    streamUrl = streamUrl,
    containerExtension = containerExtension,
    coverUrl = coverUrl,
    plot = plot,
    duration = duration,
    durationSeconds = durationSeconds,
    rating = rating,
    releaseDate = releaseDate,
    seriesId = seriesId,
    providerId = providerId,
    watchProgress = watchProgress,
    lastWatchedAt = lastWatchedAt,
    isAdult = isAdult,
    isUserProtected = isUserProtected
)

// ── Category ───────────────────────────────────────────────────────

fun CategoryEntity.toDomain() = com.streamvault.domain.model.Category(
    id = categoryId,
    roomId = id,
    name = name,
    parentId = parentId,
    type = type,
    isAdult = isAdult,
    isUserProtected = isUserProtected
)

fun com.streamvault.domain.model.Category.toEntity(providerId: Long) = CategoryEntity(
    categoryId = id,
    name = name,
    parentId = parentId,
    type = type,
    providerId = providerId,
    isAdult = isAdult,
    isUserProtected = isUserProtected
)

// ── Program ────────────────────────────────────────────────────────

fun ProgramEntity.toDomain() = Program(
    id = id,
    channelId = channelId,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    lang = lang,
    hasArchive = hasArchive,
    providerId = providerId
)

fun Program.toEntity() = ProgramEntity(
    id = 0,
    channelId = channelId,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    lang = lang,
    hasArchive = hasArchive,
    providerId = providerId
)

// ── Favorite ───────────────────────────────────────────────────────

fun FavoriteEntity.toDomain() = Favorite(
    id = id,
    contentId = contentId,
    contentType = contentType,
    position = position,
    groupId = groupId,
    addedAt = addedAt
)

fun Favorite.toEntity() = FavoriteEntity(
    id = id,
    contentId = contentId,
    contentType = contentType,
    position = position,
    groupId = groupId,
    addedAt = addedAt
)

// ── Virtual Group ──────────────────────────────────────────────────

fun VirtualGroupEntity.toDomain() = VirtualGroup(
    id = id,
    name = name,
    iconEmoji = iconEmoji,
    position = position,
    createdAt = createdAt,
    contentType = contentType
)

fun VirtualGroup.toEntity() = VirtualGroupEntity(
    id = id,
    name = name,
    iconEmoji = iconEmoji,
    position = position,
    createdAt = createdAt,
    contentType = contentType
)

// ── Playback History ───────────────────────────────────────────────

fun PlaybackHistoryEntity.toDomain() = PlaybackHistory(
    id = id,
    contentId = contentId,
    contentType = contentType,
    providerId = providerId,
    title = title,
    posterUrl = posterUrl,
    streamUrl = streamUrl,
    resumePositionMs = resumePositionMs,
    totalDurationMs = totalDurationMs,
    lastWatchedAt = lastWatchedAt,
    watchCount = watchCount,
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber
)

fun PlaybackHistory.toEntity() = PlaybackHistoryEntity(
    id = id,
    contentId = contentId,
    contentType = contentType,
    providerId = providerId,
    title = title,
    posterUrl = posterUrl,
    streamUrl = streamUrl,
    resumePositionMs = resumePositionMs,
    totalDurationMs = totalDurationMs,
    lastWatchedAt = lastWatchedAt,
    watchCount = watchCount,
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber
)

// ── Sync Metadata ──────────────────────────────────────────────────

fun SyncMetadataEntity.toDomain() = SyncMetadata(
    providerId = providerId,
    lastLiveSync = lastLiveSync,
    lastMovieSync = lastMovieSync,
    lastSeriesSync = lastSeriesSync,
    lastEpgSync = lastEpgSync,
    liveCount = liveCount,
    movieCount = movieCount,
    seriesCount = seriesCount,
    epgCount = epgCount,
    lastSyncStatus = lastSyncStatus
)

fun SyncMetadata.toEntity() = SyncMetadataEntity(
    providerId = providerId,
    lastLiveSync = lastLiveSync,
    lastMovieSync = lastMovieSync,
    lastSeriesSync = lastSeriesSync,
    lastEpgSync = lastEpgSync,
    liveCount = liveCount,
    movieCount = movieCount,
    seriesCount = seriesCount,
    epgCount = epgCount,
    lastSyncStatus = lastSyncStatus
)
