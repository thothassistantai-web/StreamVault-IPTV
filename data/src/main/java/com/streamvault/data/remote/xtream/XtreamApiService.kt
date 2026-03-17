package com.streamvault.data.remote.xtream

import com.streamvault.data.remote.dto.XtreamAuthResponse
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamEpgResponse
import com.streamvault.data.remote.dto.XtreamSeriesInfoResponse
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.dto.XtreamVodInfoResponse

/**
 * Xtream Codes player API abstraction.
 */
interface XtreamApiService {
    suspend fun authenticate(endpoint: String): XtreamAuthResponse

    suspend fun getLiveCategories(endpoint: String): List<XtreamCategory>

    suspend fun getLiveStreams(endpoint: String): List<XtreamStream>

    suspend fun getVodCategories(endpoint: String): List<XtreamCategory>

    suspend fun getVodStreams(endpoint: String): List<XtreamStream>

    suspend fun getVodInfo(endpoint: String): XtreamVodInfoResponse

    suspend fun getSeriesCategories(endpoint: String): List<XtreamCategory>

    suspend fun getSeriesList(endpoint: String): List<XtreamSeriesItem>

    suspend fun getSeriesInfo(endpoint: String): XtreamSeriesInfoResponse

    suspend fun getShortEpg(endpoint: String): XtreamEpgResponse

    suspend fun getFullEpg(endpoint: String): XtreamEpgResponse
}
