package com.streamvault.data.remote.stalker

import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.domain.model.ContentType
import java.util.Locale

internal fun stalkerSyntheticId(providerId: Long, type: ContentType, seed: String): Long {
    val normalized = "$providerId/${type.name}/${seed.trim().lowercase(Locale.ROOT)}"
    return (normalized.hashCode().toLong() and 0x7fff_ffffL).coerceAtLeast(1L)
}

internal fun StalkerCategoryRecord.toCategoryEntity(
    providerId: Long,
    type: ContentType
): CategoryEntity {
    val syntheticId = stalkerSyntheticId(providerId, type, id.ifBlank { name })
    return CategoryEntity(
        providerId = providerId,
        categoryId = syntheticId,
        name = name,
        type = type,
        isAdult = com.streamvault.data.util.AdultContentClassifier.isAdultCategoryName(name)
    )
}
