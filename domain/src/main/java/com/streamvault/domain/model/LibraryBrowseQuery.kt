package com.streamvault.domain.model

data class LibraryBrowseQuery(
    val providerId: Long,
    val categoryId: Long? = null,
    val offset: Int = 0,
    val limit: Int = 40
) {
    init {
        require(offset >= 0) { "offset must be non-negative" }
        require(limit > 0) { "limit must be positive" }
    }
}

data class PagedResult<T>(
    val items: List<T>,
    val totalCount: Int,
    val offset: Int,
    val limit: Int
) {
    init {
        require(totalCount >= 0) { "totalCount must be non-negative" }
        require(offset >= 0) { "offset must be non-negative" }
        require(limit > 0) { "limit must be positive" }
    }

    val hasNextPage: Boolean get() = offset + items.size < totalCount
    val hasPreviousPage: Boolean get() = offset > 0
    val currentPage: Int get() = if (limit > 0) offset / limit else 0
    val totalPages: Int get() = if (limit > 0) (totalCount + limit - 1) / limit else 0
}
