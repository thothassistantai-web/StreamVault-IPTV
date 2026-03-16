package com.streamvault.data.parser

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.util.ChannelNormalizer
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Robust M3U parser that handles real-world malformed playlists.
 * Parses line-by-line in a streaming fashion to handle large files.
 *
 * Supports:
 * - #EXTM3U header
 * - #EXTINF tags with attributes
 * - Group-title for categories
 * - TVG attributes (tvg-id, tvg-name, tvg-logo, tvg-chno)
 * - Tokenized / expiring URLs
 * - Broken / malformed entries (skipped gracefully)
 */
class M3uParser {

    data class M3uHeader(
        val tvgUrl: String? = null,
        val userAgent: String? = null
    )

    data class M3uEntry(
        val name: String,
        val groupTitle: String,
        val tvgId: String?,
        val tvgName: String?,
        val tvgLogo: String?,
        val tvgChno: Int?,
        val tvgLanguage: String?,
        val tvgCountry: String?,
        val catchUp: String?,
        val catchUpDays: Int?,
        val catchUpSource: String?,
        val timeshift: String?,
        val url: String,
        val userAgent: String? = null,
        val rating: String? = null,
        val year: String? = null,
        val genre: String? = null,
        val durationSeconds: Int? = null
    )

    data class ParseResult(
        val header: M3uHeader,
        val entries: List<M3uEntry>
    )

    fun parse(inputStream: InputStream): ParseResult {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val entries = mutableListOf<M3uEntry>()
        var header = M3uHeader()
        var pendingExtinf: ParsedExtinf? = null

        reader.use {
            while (true) {
                val rawLine = reader.readLine() ?: break
                val line = rawLine.trim()
                if (line.isEmpty()) {
                    continue
                }

                when {
                    line.startsWith("#EXTM3U", ignoreCase = true) -> {
                        header = parseHeader(line)
                    }
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        pendingExtinf = parseExtinf(line)
                    }
                    line.startsWith("#") -> {
                        pendingExtinf = null
                    }
                    pendingExtinf != null -> {
                        val extinf = pendingExtinf
                        if (extinf != null) {
                            parseEntry(extinf, line, header.userAgent)?.let(entries::add)
                        }
                        pendingExtinf = null
                    }
                    else -> {
                        pendingExtinf = null
                    }
                }
            }
        }

        return ParseResult(header, entries)
    }

    suspend fun parseStreaming(
        inputStream: InputStream,
        onHeader: suspend (M3uHeader) -> Unit = {},
        onEntry: suspend (M3uEntry) -> Unit
    ) {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        var header = M3uHeader()
        var pendingExtinf: ParsedExtinf? = null

        reader.use {
            while (true) {
                val rawLine = reader.readLine() ?: break
                val line = rawLine.trim()
                if (line.isEmpty()) {
                    continue
                }

                when {
                    line.startsWith("#EXTM3U", ignoreCase = true) -> {
                        header = parseHeader(line)
                        onHeader(header)
                    }
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        pendingExtinf = parseExtinf(line)
                    }
                    line.startsWith("#") -> {
                        pendingExtinf = null
                    }
                    pendingExtinf != null -> {
                        val extinf = pendingExtinf
                        if (extinf != null) {
                            parseEntry(extinf, line, header.userAgent)?.let { onEntry(it) }
                        }
                        pendingExtinf = null
                    }
                    else -> {
                        pendingExtinf = null
                    }
                }
            }
        }
    }

    fun parseToChannels(inputStream: InputStream, providerId: Long): List<Channel> {
        return parse(inputStream).entries.mapIndexed { index, entry ->
            Channel(
                id = index.toLong() + 1, // Will be replaced by stableId in SyncManager
                name = entry.name,
                logoUrl = entry.tvgLogo,
                groupTitle = entry.groupTitle,
                epgChannelId = entry.tvgId ?: entry.tvgName,
                number = entry.tvgChno ?: (index + 1),
                streamUrl = entry.url,
                catchUpSupported = entry.catchUp != null,
                catchUpDays = entry.catchUpDays ?: 0,
                catchUpSource = entry.catchUpSource,
                providerId = providerId,
                logicalGroupId = ChannelNormalizer.getLogicalGroupId(entry.name, providerId)
            )
        }
    }

    private fun parseEntry(extinf: ParsedExtinf, url: String, globalUserAgent: String?): M3uEntry? {
        if (url.isBlank() || extinf.name.isBlank()) return null
        if (!isAllowedStreamUrl(url)) return null
        return M3uEntry(
            name = extinf.name.take(500),
            groupTitle = (extinf.groupTitle ?: "Uncategorized").take(200),
            tvgId = extinf.tvgId,
            tvgName = extinf.tvgName,
            tvgLogo = extinf.tvgLogo,
            tvgChno = extinf.tvgChno?.toIntOrNull(),
            tvgLanguage = extinf.tvgLanguage,
            tvgCountry = extinf.tvgCountry,
            catchUp = extinf.catchUp,
            catchUpDays = extinf.catchUpDays?.toIntOrNull(),
            catchUpSource = extinf.catchUpSource,
            timeshift = extinf.timeshift,
            url = url,
            userAgent = extinf.userAgent ?: globalUserAgent,
            rating = extinf.rating,
            year = extinf.year,
            genre = extinf.genre,
            durationSeconds = extinf.durationSeconds
        )
    }

    private data class ParsedExtinf(
        val name: String,
        val durationSeconds: Int?,
        val tvgId: String?,
        val tvgName: String?,
        val tvgLogo: String?,
        val groupTitle: String?,
        val tvgChno: String?,
        val tvgLanguage: String?,
        val tvgCountry: String?,
        val catchUp: String?,
        val catchUpDays: String?,
        val catchUpSource: String?,
        val timeshift: String?,
        val userAgent: String?,
        val rating: String?,
        val year: String?,
        val genre: String?
    )

    private fun parseHeader(line: String): M3uHeader {
        val attributes = parseAttributes(line, line.indexOf(' '))
        return M3uHeader(
            tvgUrl = attributes["x-tvg-url"] ?: attributes["url-tvg"],
            userAgent = attributes["user-agent"]
        )
    }

    private fun parseExtinf(line: String): ParsedExtinf? {
        val colonIndex = line.indexOf(':')
        if (colonIndex < 0 || colonIndex == line.lastIndex) return null
        val payload = line.substring(colonIndex + 1).trim()
        if (payload.isEmpty()) return null

        val commaIndex = findFirstUnquotedComma(payload)
        if (commaIndex < 0) return null

        val metadata = payload.substring(0, commaIndex).trim()
        val name = payload.substring(commaIndex + 1).trim()
        if (name.isEmpty()) return null

        val attributes = parseAttributes(metadata, findAttributeStart(metadata))
        return ParsedExtinf(
            name = name,
            durationSeconds = extractDuration(metadata),
            tvgId = attributes["tvg-id"],
            tvgName = attributes["tvg-name"],
            tvgLogo = attributes["tvg-logo"],
            groupTitle = attributes["group-title"],
            tvgChno = attributes["tvg-chno"],
            tvgLanguage = attributes["tvg-language"],
            tvgCountry = attributes["tvg-country"],
            catchUp = attributes["catchup"],
            catchUpDays = attributes["catchup-days"],
            catchUpSource = attributes["catchup-source"],
            timeshift = attributes["timeshift"],
            userAgent = attributes["user-agent"],
            rating = attributes["rating"],
            year = attributes["year"],
            genre = attributes["genre"]
        )
    }

    private fun extractDuration(metadata: String): Int? {
        val attributeStart = findAttributeStart(metadata)
        val durationEnd = if (attributeStart >= 0) attributeStart else metadata.length
        return metadata.substring(0, durationEnd).trim().toIntOrNull()
    }

    private fun findAttributeStart(metadata: String): Int {
        val length = metadata.length
        var index = 0
        while (index < length && !metadata[index].isWhitespace()) {
            index++
        }
        while (index < length && metadata[index].isWhitespace()) {
            index++
        }
        return if (index < length) index else -1
    }

    private fun findFirstUnquotedComma(value: String): Int {
        var inQuotes = false
        for (i in value.indices) {
            when (value[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }

    private fun String.indexOfFirst(startIndex: Int, predicate: (Char) -> Boolean): Int {
        for (i in startIndex until length) {
            if (predicate(this[i])) return i
        }
        return -1
    }

    private fun parseAttributes(content: String, startIndex: Int): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        if (startIndex < 0 || startIndex >= content.length) {
            return attributes
        }

        val length = content.length
        var index = startIndex
        while (index < length) {
            while (index < length && content[index].isWhitespace()) {
                index++
            }
            if (index >= length) {
                break
            }

            val keyStart = index
            while (index < length && !content[index].isWhitespace() && content[index] != '=') {
                index++
            }
            if (index <= keyStart) {
                break
            }
            val key = content.substring(keyStart, index).lowercase()

            while (index < length && content[index].isWhitespace()) {
                index++
            }
            if (index >= length || content[index] != '=') {
                while (index < length && !content[index].isWhitespace()) {
                    index++
                }
                continue
            }
            index++

            while (index < length && content[index].isWhitespace()) {
                index++
            }
            if (index >= length) {
                break
            }

            val value = if (content[index] == '"') {
                index++
                val valueStart = index
                while (index < length && content[index] != '"') {
                    index++
                }
                val parsed = content.substring(valueStart, minOf(index, length))
                if (index < length && content[index] == '"') {
                    index++
                }
                parsed
            } else {
                // Unquoted value — consume until the next known attribute key or end
                val valueStart = index
                var end = index
                while (end < length) {
                    if (content[end].isWhitespace()) {
                        val nextTokenStart = content.indexOfFirst(end) { !it.isWhitespace() }
                        if (nextTokenStart < 0) break
                        val eqPos = content.indexOf('=', nextTokenStart)
                        if (eqPos > nextTokenStart) {
                            val candidateKey = content.substring(nextTokenStart, eqPos).trim().lowercase()
                            if (candidateKey.isNotEmpty() && !candidateKey.any { it.isWhitespace() } && candidateKey in knownAttributes) {
                                break
                            }
                        }
                        end++
                    } else {
                        end++
                    }
                }
                index = end
                content.substring(valueStart, end).trim()
            }

            if (key.isNotBlank()) {
                attributes[key] = value
            }
        }

        return attributes
    }

    private fun isVodEntry(entry: M3uEntry): Boolean {
        val url = entry.url.lowercase()
        val group = entry.groupTitle.lowercase()

        return url.endsWith(".mp4") ||
                url.endsWith(".mkv") ||
                url.endsWith(".avi") ||
                url.contains("/movie/") ||
                group.contains("movie") ||
                group.contains("vod") ||
                group.contains("film")
    }

    private fun isAllowedStreamUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.length > 8192) return false
        val lower = trimmed.lowercase()
        if (lower.contains("%0a") || lower.contains("%0d")) return false
        val allowedPrefixes = listOf("http://", "https://", "rtsp://", "rtmp://", "mms://")
        return allowedPrefixes.any { lower.startsWith(it) }
    }

    private companion object {
        val knownAttributes = setOf(
            "tvg-id",
            "tvg-name",
            "tvg-logo",
            "group-title",
            "tvg-chno",
            "tvg-language",
            "tvg-country",
            "catchup",
            "catchup-days",
            "catchup-source",
            "timeshift",
            "user-agent",
            "rating",
            "year",
            "genre"
        )
    }
}
