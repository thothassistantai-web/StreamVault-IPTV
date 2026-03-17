package com.streamvault.data.local

import androidx.room.TypeConverter
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType

class RoomEnumConverters {
    @TypeConverter
    fun fromProviderType(value: ProviderType?): String? = value?.name

    @TypeConverter
    fun toProviderType(value: String?): ProviderType? = value?.let(ProviderType::valueOf)

    @TypeConverter
    fun fromProviderStatus(value: ProviderStatus?): String? = value?.name

    @TypeConverter
    fun toProviderStatus(value: String?): ProviderStatus? = value?.let(ProviderStatus::valueOf)

    @TypeConverter
    fun fromContentType(value: ContentType?): String? = value?.name

    @TypeConverter
    fun toContentType(value: String?): ContentType? = value?.let(ContentType::valueOf)
}