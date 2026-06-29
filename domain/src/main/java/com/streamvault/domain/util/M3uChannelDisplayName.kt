package com.streamvault.domain.util

/**
 * Resolves M3U channel labels for Live TV and Guide.
 *
 * StepDaddy Gateway embeds event metadata (region prefix, health dots, language) in the
 * comma-separated EXTINF title. StreamVault should keep that title as-is and only enrich
 * from `tvg-language` when the attribute is present but not already visible in the name.
 */
object M3uChannelDisplayName {
    data class Input(
        val name: String,
        val groupTitle: String = "",
        val streamUrl: String = "",
        val tvgLanguage: String? = null,
        val tvgCountry: String? = null,
    )

    private val specialEventsGroupRe = Regex("""special\s*events""", RegexOption.IGNORE_CASE)
    private val gatewayEventStreamRe = Regex("""/dlhd-event-(?:stream|guide)/""", RegexOption.IGNORE_CASE)
    private val gatewayEventTitleRe = Regex(
        """^(?:[🟢🟡⚪🔴]\s*)?(?:US|UK|CA|AU|NZ|DE|FR|IT|ES|IE|MX|BR|INT)\s*:""",
        RegexOption.IGNORE_CASE,
    )
    private val explicitLanguageTagRe = Regex("""\[(EN|FR|ES|DE|IT|PT)\]""", RegexOption.IGNORE_CASE)
    private val languageFlagRe = Regex("""[🇺🇸🇬🇧🇨🇦🇦🇺🇳🇿🇩🇪🇫🇷🇮🇹🇪🇸🇵🇱🇬🇷🇶🇦🇮🇱🇦🇪🇷🇸🇭🇷🇧🇦🇧🇬🇿🇦🇩🇰🇵🇹🇲🇽🇸🇪🇨🇿🇳🇱🇹🇷🇧🇷🇲🇾🇷🇴🇦🇷🇨🇾🇷🇺🇮🇳🇮🇪🇵🇰🇭🇺🇪🇬🇦🇹🇧🇩🇨🇱🇺🇾🇨🇴]""")

    private val tvgLanguageToBadge = mapOf(
        "eng" to "EN",
        "en" to "EN",
        "fra" to "FR",
        "fr" to "FR",
        "spa" to "ES",
        "es" to "ES",
        "deu" to "DE",
        "de" to "DE",
        "ita" to "IT",
        "it" to "IT",
        "por" to "PT",
        "pt" to "PT",
    )

    fun resolve(input: Input): String {
        val base = input.name.trim()
        if (!preserveProviderLabel(input)) return base
        return appendLanguageBadgeIfNeeded(base, input.tvgLanguage)
    }

    /**
     * Gateway event rows should show the provider-formatted title (health dots, region, league)
     * even when grouped-channel label mode prefers canonical names.
     */
    fun preserveProviderLabel(input: Input): Boolean {
        if (gatewayEventStreamRe.containsMatchIn(input.streamUrl.substringBefore("|"))) return true
        if (specialEventsGroupRe.containsMatchIn(input.groupTitle)) return true
        if (gatewayEventTitleRe.containsMatchIn(input.name.trim())) return true
        return false
    }

    private fun appendLanguageBadgeIfNeeded(title: String, tvgLanguage: String?): String {
        val normalized = tvgLanguage?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return title
        val badge = tvgLanguageToBadge[normalized] ?: return title
        if (languageVisibleInTitle(title, badge)) return title
        return "$title [$badge]".trim()
    }

    private fun languageVisibleInTitle(title: String, badge: String): Boolean {
        if (explicitLanguageTagRe.containsMatchIn(title)) return true
        if (languageFlagRe.containsMatchIn(title)) return true
        return title.contains("[$badge]", ignoreCase = true)
    }
}
