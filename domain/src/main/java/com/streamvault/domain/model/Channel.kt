package com.streamvault.domain.model

data class Channel(
    val id: Long,
    val name: String,
    val logoUrl: String? = null,
    val groupTitle: String? = null,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val streamUrl: String = "",
    val epgChannelId: String? = null,
    val number: Int = 0,
    val isFavorite: Boolean = false,
    val catchUpSupported: Boolean = false,
    val catchUpDays: Int = 0,
    val catchUpSource: String? = null,
    val providerId: Long = 0,
    val currentProgram: Program? = null,
    val nextProgram: Program? = null,
    val isAdult: Boolean = false,
    val isUserProtected: Boolean = false,
    val logicalGroupId: String = "",
    val errorCount: Int = 0,
    val alternativeStreams: List<String> = emptyList(),
    val streamId: Long = 0L
) {
    init {
        require(number >= 0) { "number must be non-negative" }
        require(catchUpDays >= 0) { "catchUpDays must be non-negative" }
        require(errorCount >= 0) { "errorCount must be non-negative" }
    }
}
