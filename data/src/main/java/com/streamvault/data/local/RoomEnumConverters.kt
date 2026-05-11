package com.streamvault.data.local

import androidx.room.TypeConverter
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import java.util.logging.Logger

class RoomEnumConverters {
    private companion object {
        val logger: Logger = Logger.getLogger(RoomEnumConverters::class.java.name)
    }

    @TypeConverter
    fun fromProviderType(value: ProviderType?): String? = value?.name

    @TypeConverter
    fun toProviderType(value: String?): ProviderType? =
        enumValueOrDefault(value, ProviderType.M3U, providerTypeAliases())

    @TypeConverter
    fun fromProviderStatus(value: ProviderStatus?): String? = value?.name

    @TypeConverter
    fun toProviderStatus(value: String?): ProviderStatus? = enumValueOrDefault(value, ProviderStatus.UNKNOWN)

    @TypeConverter
    fun fromProviderEpgSyncMode(value: ProviderEpgSyncMode?): String? = value?.name

    @TypeConverter
    fun toProviderEpgSyncMode(value: String?): ProviderEpgSyncMode? =
        enumValueOrDefault(value, ProviderEpgSyncMode.SKIP, providerEpgSyncModeAliases())

    @TypeConverter
    fun fromProviderXtreamLiveSyncMode(value: ProviderXtreamLiveSyncMode?): String? = value?.name

    @TypeConverter
    fun toProviderXtreamLiveSyncMode(value: String?): ProviderXtreamLiveSyncMode? =
        enumValueOrDefault(value, ProviderXtreamLiveSyncMode.AUTO, providerXtreamLiveSyncModeAliases())

    @TypeConverter
    fun fromContentType(value: ContentType?): String? = value?.name

    @TypeConverter
    fun toContentType(value: String?): ContentType? =
        enumValueOrDefault(value, ContentType.LIVE, contentTypeAliases())

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        value: String?,
        defaultValue: T,
        aliases: Map<String, T> = emptyMap()
    ): T? {
        if (value == null) return null
        val normalizedValue = value.trim()
        aliases[normalizedValue.uppercase()]?.let { return it }
        return enumValues<T>().firstOrNull { candidate ->
            candidate.name.equals(normalizedValue, ignoreCase = true)
        } ?: defaultValue.also {
            logger.warning(
                "Unknown ${T::class.java.simpleName} value '$value'; defaulting to ${defaultValue.name}"
            )
        }
    }

    private fun providerTypeAliases(): Map<String, ProviderType> = mapOf(
        "XTREAM" to ProviderType.XTREAM_CODES,
        "XTREAM_CODES_API" to ProviderType.XTREAM_CODES,
        "STALKER" to ProviderType.STALKER_PORTAL,
        "STB" to ProviderType.STALKER_PORTAL,
        "PLAYLIST" to ProviderType.M3U
    )

    private fun providerEpgSyncModeAliases(): Map<String, ProviderEpgSyncMode> = mapOf(
        "DISABLED" to ProviderEpgSyncMode.SKIP,
        "OFF" to ProviderEpgSyncMode.SKIP,
        "FOREGROUND" to ProviderEpgSyncMode.UPFRONT
    )

    private fun providerXtreamLiveSyncModeAliases(): Map<String, ProviderXtreamLiveSyncMode> = mapOf(
        "SEGMENTED" to ProviderXtreamLiveSyncMode.CATEGORY_BY_CATEGORY,
        "CATEGORY" to ProviderXtreamLiveSyncMode.CATEGORY_BY_CATEGORY,
        "CATEGORIES" to ProviderXtreamLiveSyncMode.CATEGORY_BY_CATEGORY,
        "FULL" to ProviderXtreamLiveSyncMode.STREAM_ALL,
        "FULL_CATALOG" to ProviderXtreamLiveSyncMode.STREAM_ALL,
        "STREAM" to ProviderXtreamLiveSyncMode.STREAM_ALL
    )

    private fun contentTypeAliases(): Map<String, ContentType> = mapOf(
        "EPISODE" to ContentType.SERIES_EPISODE,
        "SHOW" to ContentType.SERIES,
        "VOD" to ContentType.MOVIE,
        "CHANNEL" to ContentType.LIVE
    )
}