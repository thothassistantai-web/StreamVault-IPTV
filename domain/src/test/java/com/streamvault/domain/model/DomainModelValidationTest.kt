package com.streamvault.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class DomainModelValidationTest {

    // --- Channel ---

    @Test
    fun `Channel - valid defaults`() {
        val channel = Channel(id = 1, name = "Test")
        assertThat(channel.number).isEqualTo(0)
        assertThat(channel.catchUpDays).isEqualTo(0)
        assertThat(channel.errorCount).isEqualTo(0)
    }

    @Test
    fun `Channel - negative number throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            Channel(id = 1, name = "Test", number = -1)
        }
    }

    @Test
    fun `Channel - negative catchUpDays throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            Channel(id = 1, name = "Test", catchUpDays = -1)
        }
    }

    @Test
    fun `Channel - negative errorCount throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            Channel(id = 1, name = "Test", errorCount = -1)
        }
    }

    // --- Movie ---

    @Test
    fun `Movie - valid defaults`() {
        val movie = Movie(id = 1, name = "Test")
        assertThat(movie.durationSeconds).isEqualTo(0)
        assertThat(movie.rating).isEqualTo(0f)
    }

    @Test
    fun `Movie - negative durationSeconds throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            Movie(id = 1, name = "Test", durationSeconds = -1)
        }
    }

    @Test
    fun `Movie - rating above 10 throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            Movie(id = 1, name = "Test", rating = 10.1f)
        }
    }

    @Test
    fun `Movie - negative rating throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            Movie(id = 1, name = "Test", rating = -0.1f)
        }
    }

    @Test
    fun `Movie - rating at boundary is valid`() {
        val movie = Movie(id = 1, name = "Test", rating = 10f)
        assertThat(movie.rating).isEqualTo(10f)
    }

    @Test
    fun `Movie - negative watchProgress throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            Movie(id = 1, name = "Test", watchProgress = -1)
        }
    }

    @Test
    fun `Movie - negative lastWatchedAt throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            Movie(id = 1, name = "Test", lastWatchedAt = -1)
        }
    }

    // --- Provider ---

    @Test
    fun `Provider - valid construction`() {
        val provider = Provider(
            name = "My Provider",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "http://example.com"
        )
        assertThat(provider.name).isEqualTo("My Provider")
    }

    @Test
    fun `Provider - blank name throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            Provider(name = "  ", type = ProviderType.M3U, serverUrl = "http://example.com")
        }
    }

    @Test
    fun `Provider - zero maxConnections throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            Provider(
                name = "Test",
                type = ProviderType.M3U,
                serverUrl = "http://example.com",
                maxConnections = 0
            )
        }
    }

    @Test
    fun `Provider - negative lastSyncedAt throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            Provider(
                name = "Test",
                type = ProviderType.M3U,
                serverUrl = "http://example.com",
                lastSyncedAt = -1
            )
        }
    }

    // --- Category ---

    @Test
    fun `Category - valid defaults`() {
        val category = Category(id = 1, name = "Movies")
        assertThat(category.count).isEqualTo(0)
    }

    @Test
    fun `Category - negative count throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            Category(id = 1, name = "Movies", count = -1)
        }
    }

    // --- StreamInfo ---

    @Test
    fun `StreamInfo - valid construction`() {
        val info = StreamInfo(url = "http://stream.example.com/live.m3u8")
        assertThat(info.url).isEqualTo("http://stream.example.com/live.m3u8")
    }

    @Test
    fun `StreamInfo - blank url throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamInfo(url = "")
        }
    }

    @Test
    fun `StreamInfo - whitespace-only url throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamInfo(url = "   ")
        }
    }

    // --- PagedResult ---

    @Test
    fun `PagedResult - hasNextPage true when more items`() {
        val result = PagedResult(items = listOf(1, 2, 3), totalCount = 10, offset = 0, limit = 3)
        assertThat(result.hasNextPage).isTrue()
    }

    @Test
    fun `PagedResult - hasNextPage false when at end`() {
        val result = PagedResult(items = listOf(1, 2), totalCount = 5, offset = 3, limit = 3)
        assertThat(result.hasNextPage).isFalse()
    }

    @Test
    fun `PagedResult - hasPreviousPage false at start`() {
        val result = PagedResult(items = listOf(1, 2, 3), totalCount = 10, offset = 0, limit = 3)
        assertThat(result.hasPreviousPage).isFalse()
    }

    @Test
    fun `PagedResult - hasPreviousPage true after first page`() {
        val result = PagedResult(items = listOf(4, 5, 6), totalCount = 10, offset = 3, limit = 3)
        assertThat(result.hasPreviousPage).isTrue()
    }

    @Test
    fun `PagedResult - totalPages calculated correctly`() {
        val result = PagedResult(items = listOf(1), totalCount = 10, offset = 0, limit = 3)
        assertThat(result.totalPages).isEqualTo(4)  // ceil(10/3)
    }

    @Test
    fun `PagedResult - currentPage calculated correctly`() {
        val result = PagedResult(items = listOf(1), totalCount = 10, offset = 6, limit = 3)
        assertThat(result.currentPage).isEqualTo(2)
    }

    @Test
    fun `PagedResult - negative totalCount throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            PagedResult(items = emptyList<Int>(), totalCount = -1, offset = 0, limit = 10)
        }
    }
}
