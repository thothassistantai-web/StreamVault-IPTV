package com.streamvault.data.util

import java.text.Normalizer
import java.util.Locale

object AdultContentClassifier {
    private val keywords = listOf(
        // English
        "xxx",
        "adult",
        "18+",
        "18 plus",
        "18plus",
        "porn",
        "porno",
        "sex",
        "erotic",
        "hustler",
        "playboy",
        "redlight",
        "red light",
        // Spanish
        "adulto",
        "adultos",
        // French
        "adulte",
        "adultes",
        "erotique",
        // German
        "erwachsene",
        "erotik",
        // Russian (transliterated)
        "vzroslye",
        "dlya vzroslykh",
        // Arabic (transliterated)
        "lilkbar",
        // Turkish
        "yetiskin",
        // Common IPTV labels
        "for adults",
        "pour adultes",
        "para adultos"
    )

    fun isAdultCategoryName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        // Pad with spaces so we can do word-boundary matching via contains().
        // This prevents "sex" matching "Essex" or "Middlesex", "adult" matching "adulting", etc.
        val normalized = " ${normalize(name)} "
        return keywords.any { keyword ->
            normalized.contains(" ${normalize(keyword)} ")
        }
    }

    fun adultCategoryIds(namesById: Map<Long, String>): Set<Long> {
        return namesById
            .filterValues(::isAdultCategoryName)
            .keys
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9+]+"), " ")
            .trim()
    }
}
