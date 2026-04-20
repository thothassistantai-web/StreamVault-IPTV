package com.streamvault.data.util

object ProviderInputSanitizer {
    const val MAX_PROVIDER_NAME_LENGTH = 80
    const val MAX_URL_LENGTH = 2048
    const val MAX_USERNAME_LENGTH = 128
    const val MAX_PASSWORD_LENGTH = 256
    const val MAX_MAC_ADDRESS_LENGTH = 17
    const val MAX_DEVICE_PROFILE_LENGTH = 32
    const val MAX_TIMEZONE_LENGTH = 64
    const val MAX_LOCALE_LENGTH = 16

    fun sanitizeProviderNameForEditing(input: String): String = sanitizeSingleLine(input, MAX_PROVIDER_NAME_LENGTH)

    fun sanitizeUrlForEditing(input: String): String = sanitizeRaw(input, MAX_URL_LENGTH)

    fun sanitizeUsernameForEditing(input: String): String = sanitizeSingleLine(input, MAX_USERNAME_LENGTH)

    fun sanitizePasswordForEditing(input: String): String = sanitizeRaw(input, MAX_PASSWORD_LENGTH)

    fun sanitizeMacAddressForEditing(input: String): String = sanitizeSingleLine(input.uppercase(), MAX_MAC_ADDRESS_LENGTH)

    fun sanitizeDeviceProfileForEditing(input: String): String = sanitizeSingleLine(input, MAX_DEVICE_PROFILE_LENGTH)

    fun sanitizeTimezoneForEditing(input: String): String = sanitizeSingleLine(input, MAX_TIMEZONE_LENGTH)

    fun sanitizeLocaleForEditing(input: String): String = sanitizeSingleLine(input, MAX_LOCALE_LENGTH)

    fun normalizeProviderName(input: String): String =
        sanitizeSingleLine(input, MAX_PROVIDER_NAME_LENGTH)
            .trim()
            .replace(WHITESPACE_REGEX, " ")

    fun normalizeUrl(input: String): String = sanitizeRaw(input, MAX_URL_LENGTH).trim()

    fun normalizeUsername(input: String): String = sanitizeSingleLine(input, MAX_USERNAME_LENGTH).trim()

    fun normalizeMacAddress(input: String): String {
        val raw = sanitizeSingleLine(input, MAX_MAC_ADDRESS_LENGTH + 5)
            .uppercase()
            .filter { it.isLetterOrDigit() }
        if (raw.length != 12) {
            return sanitizeSingleLine(input.uppercase(), MAX_MAC_ADDRESS_LENGTH).trim()
        }
        return raw.chunked(2).joinToString(":")
    }

    fun normalizeDeviceProfile(input: String): String =
        sanitizeSingleLine(input, MAX_DEVICE_PROFILE_LENGTH).trim()

    fun normalizeTimezone(input: String): String =
        sanitizeSingleLine(input, MAX_TIMEZONE_LENGTH).trim()

    fun normalizeLocale(input: String): String =
        sanitizeSingleLine(input, MAX_LOCALE_LENGTH).trim()

    fun validateUrl(url: String): String? {
        return if (url.any(Char::isWhitespace)) {
            "URLs cannot contain spaces or line breaks."
        } else {
            null
        }
    }

    fun validateMacAddress(macAddress: String): String? {
        if (macAddress.isBlank()) {
            return "Please enter MAC address"
        }
        return if (MAC_ADDRESS_REGEX.matches(macAddress)) {
            null
        } else {
            "MAC address must be six hex pairs like 00:1A:79:12:34:56."
        }
    }

    private fun sanitizeSingleLine(input: String, maxLength: Int): String {
        return sanitizeRaw(input, maxLength).replace(WHITESPACE_REGEX, " ")
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

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val MAC_ADDRESS_REGEX = Regex("^[0-9A-F]{2}(?::[0-9A-F]{2}){5}$")
}
