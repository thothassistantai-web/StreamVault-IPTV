package com.streamvault.data.remote.xtream

import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.streamvault.data.remote.dto.XtreamAuthResponse
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamEpgResponse
import com.streamvault.data.remote.dto.XtreamSeriesInfoResponse
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.dto.XtreamVodInfoResponse
import com.streamvault.data.remote.NetworkTimeoutConfig
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.net.URI
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit.SECONDS

@OptIn(ExperimentalSerializationApi::class)
class OkHttpXtreamApiService(
    private val client: OkHttpClient,
    private val json: Json
) : XtreamApiService {
    private companion object {
        const val PREVIEW_INPUT_LIMIT = 512
        const val PREVIEW_OUTPUT_LIMIT = 140
        const val RESPONSE_BUDGET_HEADROOM_BYTES = 1L * 1024L * 1024L
        const val MAX_FULL_LIVE_CATALOG_BYTES = 96L * 1024L * 1024L
        const val MAX_FULL_VOD_CATALOG_BYTES = 80L * 1024L * 1024L
        const val MAX_FULL_SERIES_CATALOG_BYTES = 100L * 1024L * 1024L
        const val MAX_PARTIAL_CATALOG_BYTES = 40L * 1024L * 1024L
        const val MAX_EPG_BYTES = 12L * 1024L * 1024L
    }

    private enum class RequestProfile {
        STANDARD,
        HEAVY_CATALOG
    }

    private data class EndpointDescriptor(
        val action: String?,
        val host: String?,
        val path: String?,
        val hint: String,
        val queryKeys: Set<String>
    )

    private val heavyCatalogClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(NetworkTimeoutConfig.XTREAM_HEAVY_READ_TIMEOUT_SECONDS, SECONDS)
            .writeTimeout(NetworkTimeoutConfig.XTREAM_HEAVY_WRITE_TIMEOUT_SECONDS, SECONDS)
            .callTimeout(NetworkTimeoutConfig.XTREAM_HEAVY_CALL_TIMEOUT_SECONDS, SECONDS)
            .build()
    }

    override suspend fun authenticate(endpoint: String): XtreamAuthResponse = get(endpoint)

    override suspend fun getLiveCategories(endpoint: String): List<XtreamCategory> = get(endpoint)

    override suspend fun getLiveStreams(endpoint: String): List<XtreamStream> = get(endpoint, RequestProfile.HEAVY_CATALOG)

    override suspend fun getVodCategories(endpoint: String): List<XtreamCategory> = get(endpoint)

    override suspend fun getVodStreams(endpoint: String): List<XtreamStream> = get(endpoint, RequestProfile.HEAVY_CATALOG)

    suspend fun streamVodStreams(endpoint: String, onItem: suspend (XtreamStream) -> Unit): Int =
        streamArray(
            endpoint = endpoint,
            profile = RequestProfile.HEAVY_CATALOG,
            deserializer = XtreamStream.serializer(),
            onItem = onItem
        )

    suspend fun streamLiveStreams(endpoint: String, onItem: suspend (XtreamStream) -> Unit): Int =
        streamArray(
            endpoint = endpoint,
            profile = RequestProfile.HEAVY_CATALOG,
            deserializer = XtreamStream.serializer(),
            onItem = onItem
        )

    override suspend fun getVodInfo(endpoint: String): XtreamVodInfoResponse = get(endpoint)

    override suspend fun getSeriesCategories(endpoint: String): List<XtreamCategory> = get(endpoint)

    override suspend fun getSeriesList(endpoint: String): List<XtreamSeriesItem> = get(endpoint, RequestProfile.HEAVY_CATALOG)

    suspend fun streamSeriesList(endpoint: String, onItem: suspend (XtreamSeriesItem) -> Unit): Int =
        streamArray(
            endpoint = endpoint,
            profile = RequestProfile.HEAVY_CATALOG,
            deserializer = XtreamSeriesItem.serializer(),
            onItem = onItem
        )

    override suspend fun getSeriesInfo(endpoint: String): XtreamSeriesInfoResponse = get(endpoint)

    override suspend fun getShortEpg(endpoint: String): XtreamEpgResponse = get(endpoint)

    override suspend fun getFullEpg(endpoint: String): XtreamEpgResponse = get(endpoint)

    private suspend inline fun <reified T> get(
        endpoint: String,
        profile: RequestProfile = RequestProfile.STANDARD
    ): T = withContext(Dispatchers.IO) {
        val descriptor = describeEndpoint(endpoint)
        val request = Request.Builder()
            .url(endpoint)
            .get()
            .build()
        try {
            val call = clientFor(profile).newCall(request)
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val message = "HTTP ${response.code}"
                    when (response.code) {
                        401 -> throw XtreamAuthenticationException(response.code, message)
                        403 -> {
                            if (descriptor.action.isNullOrBlank()) {
                                throw XtreamAuthenticationException(response.code, message)
                            }
                            throw XtreamRequestException(response.code, message)
                        }
                        in 500..599, 429 -> throw XtreamNetworkException(message)
                        else -> throw XtreamRequestException(response.code, message)
                    }
                }
                val body = response.body
                    ?: throw XtreamParsingException("Empty response body from ${descriptor.hint}")
                decodeBodyBounded(
                    body = body,
                    descriptor = descriptor,
                    contentType = response.header("Content-Type"),
                    maxBytes = responseBudgetFor(descriptor)
                )
            }
        } catch (e: XtreamApiException) {
            throw e
        } catch (e: IOException) {
            throw XtreamNetworkException(XtreamUrlFactory.sanitizeLogMessage(e.message ?: "Network request failed"), e)
        }
    }

    private fun clientFor(profile: RequestProfile): OkHttpClient = when (profile) {
        RequestProfile.STANDARD -> client
        RequestProfile.HEAVY_CATALOG -> heavyCatalogClient
    }

    private fun describeEndpoint(endpoint: String): EndpointDescriptor {
        val uri = runCatching { URI(endpoint) }.getOrNull()
        val queryParams = uri?.rawQuery
            ?.split('&')
            ?.mapNotNull { token ->
                val key = token.substringBefore('=', "").trim()
                if (key.isBlank()) null else key.lowercase() to token.substringAfter('=', "")
            }
            .orEmpty()
        val action = queryParams
            .firstOrNull { (key, _) -> key == "action" }
            ?.second
            ?.takeIf { it.isNotBlank() }
        val host = uri?.host
        val path = uri?.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val hint = buildString {
            append(host ?: "<unknown-host>")
            if (!path.isNullOrBlank()) append("/").append(path)
            if (!action.isNullOrBlank()) append("?action=").append(action)
        }
        return EndpointDescriptor(
            action = action,
            host = host,
            path = path,
            hint = XtreamUrlFactory.sanitizeLogMessage(hint),
            queryKeys = queryParams.mapTo(linkedSetOf()) { (key, _) -> key }
        )
    }

    private fun responseBudgetFor(descriptor: EndpointDescriptor): Long? {
        val action = descriptor.action?.lowercase().orEmpty()
        val queryKeys = descriptor.queryKeys
        val isSegmentedCatalogRequest = queryKeys.any { key ->
            key == "category_id" || key == "page" || key == "offset" || key == "items_per_page" || key == "limit"
        }
        return when {
            (action == "get_live_streams" || action == "get_vod_streams" || action == "get_series") &&
                isSegmentedCatalogRequest -> MAX_PARTIAL_CATALOG_BYTES
            action == "get_live_streams" -> MAX_FULL_LIVE_CATALOG_BYTES
            action == "get_vod_streams" -> MAX_FULL_VOD_CATALOG_BYTES
            action == "get_series" -> MAX_FULL_SERIES_CATALOG_BYTES
            action == "get_short_epg" || action == "get_simple_data_table" -> MAX_EPG_BYTES
            else -> null
        }
    }

    private suspend fun <T> streamArray(
        endpoint: String,
        profile: RequestProfile,
        deserializer: DeserializationStrategy<T>,
        onItem: suspend (T) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val descriptor = describeEndpoint(endpoint)
        val request = Request.Builder()
            .url(endpoint)
            .get()
            .build()
        try {
            val call = clientFor(profile).newCall(request)
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val message = "HTTP ${response.code}"
                    when (response.code) {
                        401 -> throw XtreamAuthenticationException(response.code, message)
                        403 -> {
                            if (descriptor.action.isNullOrBlank()) {
                                throw XtreamAuthenticationException(response.code, message)
                            }
                            throw XtreamRequestException(response.code, message)
                        }
                        in 500..599, 429 -> throw XtreamNetworkException(message)
                        else -> throw XtreamRequestException(response.code, message)
                    }
                }
                val body = response.body
                    ?: throw XtreamParsingException("Empty response body from ${descriptor.hint}")
                streamBodyBounded(
                    body = body,
                    descriptor = descriptor,
                    contentType = response.header("Content-Type"),
                    maxBytes = responseBudgetFor(descriptor),
                    deserializer = deserializer,
                    onItem = onItem
                )
            }
        } catch (e: XtreamApiException) {
            throw e
        } catch (e: IOException) {
            throw XtreamNetworkException(XtreamUrlFactory.sanitizeLogMessage(e.message ?: "Network request failed"), e)
        }
    }

    private inline fun <reified T> decodeBodyBounded(
        body: ResponseBody,
        descriptor: EndpointDescriptor,
        contentType: String?,
        maxBytes: Long?
    ): T {
        val effectiveMaxBytes = maxBytes?.plus(RESPONSE_BUDGET_HEADROOM_BYTES)
        val announcedLength = body.contentLength()
        if (effectiveMaxBytes != null && announcedLength > effectiveMaxBytes) {
            throw XtreamResponseTooLargeException(
                hint = descriptor.hint,
                observedBytes = announcedLength,
                maxAllowedBytes = effectiveMaxBytes
            )
        }

        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val input = PushbackInputStream(
            BoundedInputStream(
                delegate = body.byteStream(),
                descriptor = descriptor,
                maxAllowedBytes = effectiveMaxBytes
            ),
            PREVIEW_INPUT_LIMIT
        )
        input.use { stream ->
            val previewBytes = readPreviewBytes(stream)
            if (previewBytes.isEmpty()) {
                throw XtreamParsingException("Empty response body from ${descriptor.hint}")
            }
            val preview = previewBytes.toString(charset)
            inspectResponseShape(
                body = preview,
                contentType = contentType,
                descriptor = descriptor
            )?.let { throw it }
            stream.unread(previewBytes)
            while (true) {
                return try {
                    json.decodeFromStream<T>(stream)
                } catch (e: SerializationException) {
                    throw XtreamParsingException(
                        "Malformed JSON from ${descriptor.hint}${sanitizedPreview(preview)?.let { " (preview=$it)" } ?: ""}",
                        e
                    )
                }
            }
        }
    }

    private suspend fun <T> streamBodyBounded(
        body: ResponseBody,
        descriptor: EndpointDescriptor,
        contentType: String?,
        maxBytes: Long?,
        deserializer: DeserializationStrategy<T>,
        onItem: suspend (T) -> Unit
    ): Int {
        val effectiveMaxBytes = maxBytes?.plus(RESPONSE_BUDGET_HEADROOM_BYTES)
        val announcedLength = body.contentLength()
        if (effectiveMaxBytes != null && announcedLength > effectiveMaxBytes) {
            throw XtreamResponseTooLargeException(
                hint = descriptor.hint,
                observedBytes = announcedLength,
                maxAllowedBytes = effectiveMaxBytes
            )
        }

        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val input = PushbackInputStream(
            BoundedInputStream(
                delegate = body.byteStream(),
                descriptor = descriptor,
                maxAllowedBytes = effectiveMaxBytes
            ),
            PREVIEW_INPUT_LIMIT
        )
        input.use { stream ->
            val previewBytes = readPreviewBytes(stream)
            if (previewBytes.isEmpty()) {
                throw XtreamParsingException("Empty response body from ${descriptor.hint}")
            }
            val preview = previewBytes.toString(charset)
            inspectResponseShape(
                body = preview,
                contentType = contentType,
                descriptor = descriptor
            )?.let { throw it }
            stream.unread(previewBytes)

            val reader = JsonReader(InputStreamReader(stream, charset))
            reader.isLenient = true
            return when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    var emittedCount = 0
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val element = try {
                            JsonParser.parseReader(reader)
                        } catch (e: RuntimeException) {
                            throw XtreamParsingException(
                                "Malformed JSON from ${descriptor.hint}${sanitizedPreview(preview)?.let { " (preview=$it)" } ?: ""}",
                                e
                            )
                        }
                        val item = try {
                            json.decodeFromString(deserializer, element.toString())
                        } catch (e: SerializationException) {
                            throw XtreamParsingException(
                                "Malformed JSON from ${descriptor.hint}${sanitizedPreview(preview)?.let { " (preview=$it)" } ?: ""}",
                                e
                            )
                        }
                        onItem(item)
                        emittedCount++
                    }
                    reader.endArray()
                    emittedCount
                }
                JsonToken.NULL -> {
                    reader.nextNull()
                    0
                }
                else -> throw XtreamParsingException(
                    "Expected JSON array from ${descriptor.hint}${sanitizedPreview(preview)?.let { " (preview=$it)" } ?: ""}"
                )
            }
        }
    }

    private fun readPreviewBytes(input: InputStream): ByteArray {
        val buffer = ByteArray(PREVIEW_INPUT_LIMIT)
        var totalRead = 0
        while (totalRead < PREVIEW_INPUT_LIMIT) {
            val read = input.read(buffer, totalRead, PREVIEW_INPUT_LIMIT - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return if (totalRead == buffer.size) buffer else buffer.copyOf(totalRead)
    }

    private class BoundedInputStream(
        private val delegate: InputStream,
        private val descriptor: EndpointDescriptor,
        private val maxAllowedBytes: Long?
    ) : InputStream() {
        private var totalBytesRead = 0L

        override fun read(): Int {
            val value = delegate.read()
            if (value != -1) {
                recordBytesRead(1)
            }
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = delegate.read(b, off, len)
            if (read > 0) {
                recordBytesRead(read.toLong())
            }
            return read
        }

        override fun close() {
            delegate.close()
        }

        private fun recordBytesRead(bytesRead: Long) {
            totalBytesRead += bytesRead
            val limit = maxAllowedBytes ?: return
            if (totalBytesRead > limit) {
                throw XtreamResponseTooLargeException(
                    hint = descriptor.hint,
                    observedBytes = totalBytesRead,
                    maxAllowedBytes = limit
                )
            }
        }
    }

    private fun inspectResponseShape(
        body: String,
        contentType: String?,
        descriptor: EndpointDescriptor
    ): XtreamParsingException? {
        val trimmed = body.trimStart()
        val normalizedContentType = contentType?.lowercase().orEmpty()
        val preview = sanitizedPreview(body)
        val previewSuffix = if (preview != null) " (preview=$preview)" else ""
        return when {
            trimmed.isBlank() ->
                XtreamParsingException("Blank response body from ${descriptor.hint}")
            normalizedContentType.contains("html") ||
                trimmed.startsWith("<!doctype html", ignoreCase = true) ||
                trimmed.startsWith("<html", ignoreCase = true) ||
                trimmed.contains("<body", ignoreCase = true) ||
                trimmed.contains("</html>", ignoreCase = true) ->
                XtreamParsingException("HTML error page returned from ${descriptor.hint}$previewSuffix")
            trimmed.startsWith("<") ->
                XtreamParsingException("Markup/non-JSON response returned from ${descriptor.hint}$previewSuffix")
            !trimmed.startsWith("{") && !trimmed.startsWith("[") ->
                XtreamParsingException("Non-JSON text response returned from ${descriptor.hint}$previewSuffix")
            else -> null
        }
    }

    private fun sanitizedPreview(body: String): String? {
        val boundedInput = body
            .take(PREVIEW_INPUT_LIMIT)
            .replace(Regex("\\s+"), " ")
            .trim()
        return boundedInput
            .takeIf { it.isNotEmpty() }
            ?.take(PREVIEW_OUTPUT_LIMIT)
            ?.let(XtreamUrlFactory::sanitizeLogMessage)
    }
}
