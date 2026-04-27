package com.streamvault.domain.model

enum class LibrarySortBy {
    LIBRARY,
    TITLE,
    RELEASE,
    UPDATED,
    RATING,
    WATCH_COUNT
}

enum class LibraryFilterType {
    ALL,
    FAVORITES,
    IN_PROGRESS,
    UNWATCHED,
    TOP_RATED,
    RECENTLY_UPDATED
}

data class LibraryFilterBy(
    val type: LibraryFilterType = LibraryFilterType.ALL,
    val language: String? = null
)

data class LibraryBrowseQuery(
    val providerId: Long,
    val categoryId: Long? = null,
    val sortBy: LibrarySortBy = LibrarySortBy.LIBRARY,
    val filterBy: LibraryFilterBy = LibraryFilterBy(),
    val searchQuery: String = "",
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
    val limit: Int,
    /**
     * True when the repository knows there are more items available remotely
     * that are not yet reflected in [totalCount]. This is used for partially
     * cached sources (e.g. paged Stalker categories) so callers can offer
     * "load more" affordances even when the local cache appears full
     * relative to its own count.
     */
    val hasMoreRemote: Boolean = false
) {
    init {
        require(totalCount >= 0) { "totalCount must be non-negative" }
        require(offset >= 0) { "offset must be non-negative" }
        require(limit > 0) { "limit must be positive" }
    }

    val hasNextPage: Boolean get() = offset + items.size < totalCount || hasMoreRemote
    val hasPreviousPage: Boolean get() = offset > 0
    val currentPage: Int get() = if (limit > 0) offset / limit else 0
    val totalPages: Int get() = if (limit > 0) (totalCount + limit - 1) / limit else 0
}
