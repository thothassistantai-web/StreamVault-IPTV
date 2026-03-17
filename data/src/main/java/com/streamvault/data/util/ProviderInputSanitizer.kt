package com.streamvault.data.util

object ProviderInputSanitizer {
    const val MAX_PROVIDER_NAME_LENGTH = 80
    const val MAX_URL_LENGTH = 2048
    const val MAX_USERNAME_LENGTH = 128
    const val MAX_PASSWORD_LENGTH = 256

    fun sanitizeProviderNameForEditing(input: String): String = sanitizeSingleLine(input, MAX_PROVIDER_NAME_LENGTH)

    fun sanitizeUrlForEditing(input: String): String = sanitizeRaw(input, MAX_URL_LENGTH)

    fun sanitizeUsernameForEditing(input: String): String = sanitizeSingleLine(input, MAX_USERNAME_LENGTH)

    fun sanitizePasswordForEditing(input: String): String = sanitizeRaw(input, MAX_PASSWORD_LENGTH)

    fun normalizeProviderName(input: String): String =
        sanitizeSingleLine(input, MAX_PROVIDER_NAME_LENGTH)
            .trim()
            .replace(WHITESPACE_REGEX, " ")

    fun normalizeUrl(input: String): String = sanitizeRaw(input, MAX_URL_LENGTH).trim()

    fun normalizeUsername(input: String): String = sanitizeSingleLine(input, MAX_USERNAME_LENGTH).trim()

    fun validateUrl(url: String): String? {
        return if (url.any(Char::isWhitespace)) {
            "URLs cannot contain spaces or line breaks."
        } else {
            null
        }
    }

    private fun sanitizeSingleLine(input: String, maxLength: Int): String {
        return sanitizeRaw(input, maxLength).replace(LINE_BREAK_REGEX, " ")
    }

    private fun sanitizeRaw(input: String, maxLength: Int): String {
        val sanitized = buildString(input.length.coerceAtMost(maxLength)) {
            input.forEach { char ->
                if (!char.isISOControl()) {
                    append(char)
                }
            }
        }
        return sanitized.take(maxLength)
    }

    private val LINE_BREAK_REGEX = Regex("\\s+")
    private val WHITESPACE_REGEX = Regex("\\s+")
}