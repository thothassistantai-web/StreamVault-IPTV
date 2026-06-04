package com.streamvault.app.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTranslationClientTest {

    @Test
    fun parseTranslationUpdate_mapsFinalUpdate() {
        val payload = """
            {
              "sessionId": "s1",
              "chunkId": 7,
              "isFinal": true,
              "text": "hello world",
              "sourceLanguage": "es"
            }
        """.trimIndent()

        val update = parseTranslationUpdate(payload)

        assertEquals(7L, update.chunkId)
        assertTrue(update.isFinal)
        assertEquals("hello world", update.text)
        assertEquals("es", update.sourceLanguage)
    }

    @Test
    fun parseTranslationUpdate_handlesEmptyAndPartial() {
        val payload = """
            {
              "sessionId": "s1",
              "chunkId": 8,
              "isFinal": false,
              "text": "   "
            }
        """.trimIndent()

        val update = parseTranslationUpdate(payload)

        assertEquals(8L, update.chunkId)
        assertFalse(update.isFinal)
        assertEquals("", update.text)
        assertNull(update.sourceLanguage)
    }
}
