package com.streamvault.data.validation

import com.streamvault.data.util.ProviderInputSanitizer
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.manager.ProviderSetupInputValidator
import com.streamvault.domain.manager.ValidatedM3uProviderInput
import com.streamvault.domain.manager.ValidatedStalkerProviderInput
import com.streamvault.domain.manager.ValidatedXtreamProviderInput
import com.streamvault.domain.model.Result
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Headers

@Singleton
class ProviderSetupInputValidatorImpl @Inject constructor() : ProviderSetupInputValidator {

    override fun validateXtream(
        serverUrl: String,
        username: String,
        password: String,
        allowBlankPassword: Boolean,
        name: String,
        httpUserAgent: String,
        httpHeaders: String
    ): Result<ValidatedXtreamProviderInput> {
        val normalizedServerUrl = ProviderInputSanitizer.normalizeUrl(serverUrl)
        val normalizedUsername = ProviderInputSanitizer.normalizeUsername(username)
        val normalizedPassword = ProviderInputSanitizer.normalizePassword(password)
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)
        val normalizedHttpUserAgent = ProviderInputSanitizer.normalizeHttpUserAgent(httpUserAgent)
        val normalizedHttpHeaders = ProviderInputSanitizer.normalizeHttpHeaders(httpHeaders)

        if (normalizedServerUrl.isBlank()) {
            return Result.error("Please enter server URL")
        }
        ProviderInputSanitizer.validateUrl(normalizedServerUrl)?.let { message ->
            return Result.error(message)
        }
        UrlSecurityPolicy.validateXtreamServerUrl(normalizedServerUrl)?.let { message ->
            return Result.error(message)
        }
        if (normalizedUsername.isBlank()) {
            return Result.error("Please enter username")
        }
        ProviderInputSanitizer.validatePassword(password, allowBlankPassword)?.let { message ->
            return Result.error(message)
        }
        validateHttpOverrides(normalizedHttpUserAgent, normalizedHttpHeaders)?.let { message ->
            return Result.error(message)
        }

        return Result.success(
            ValidatedXtreamProviderInput(
                serverUrl = normalizedServerUrl,
                username = normalizedUsername,
                password = normalizedPassword,
                name = normalizedName,
                httpUserAgent = normalizedHttpUserAgent,
                httpHeaders = normalizedHttpHeaders
            )
        )
    }

    override fun validateM3u(
        url: String,
        name: String,
        httpUserAgent: String,
        httpHeaders: String
    ): Result<ValidatedM3uProviderInput> {
        val normalizedUrl = ProviderInputSanitizer.normalizeUrl(url)
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)
        val normalizedHttpUserAgent = ProviderInputSanitizer.normalizeHttpUserAgent(httpUserAgent)
        val normalizedHttpHeaders = ProviderInputSanitizer.normalizeHttpHeaders(httpHeaders)

        if (normalizedUrl.isBlank()) {
            return Result.error("Please enter M3U URL")
        }
        ProviderInputSanitizer.validateUrl(normalizedUrl)?.let { message ->
            return Result.error(message)
        }
        UrlSecurityPolicy.validatePlaylistSourceUrl(normalizedUrl)?.let { message ->
            return Result.error(message)
        }
        validateHttpOverrides(normalizedHttpUserAgent, normalizedHttpHeaders)?.let { message ->
            return Result.error(message)
        }

        return Result.success(
            ValidatedM3uProviderInput(
                url = normalizedUrl,
                name = normalizedName,
                httpUserAgent = normalizedHttpUserAgent,
                httpHeaders = normalizedHttpHeaders
            )
        )
    }

    override fun validateStalker(
        portalUrl: String,
        macAddress: String,
        name: String,
        deviceProfile: String,
        timezone: String,
        locale: String
    ): Result<ValidatedStalkerProviderInput> {
        val normalizedPortalUrl = ProviderInputSanitizer.normalizeUrl(portalUrl)
        val normalizedMacAddress = ProviderInputSanitizer.normalizeMacAddress(macAddress)
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)
        val normalizedDeviceProfile = ProviderInputSanitizer.normalizeDeviceProfile(deviceProfile)
        val normalizedTimezone = ProviderInputSanitizer.normalizeTimezone(timezone)
        val normalizedLocale = ProviderInputSanitizer.normalizeLocale(locale)

        if (normalizedPortalUrl.isBlank()) {
            return Result.error("Please enter portal URL")
        }
        ProviderInputSanitizer.validateUrl(normalizedPortalUrl)?.let { message ->
            return Result.error(message)
        }
        UrlSecurityPolicy.validateStalkerPortalUrl(normalizedPortalUrl)?.let { message ->
            return Result.error(message)
        }
        ProviderInputSanitizer.validateMacAddress(normalizedMacAddress)?.let { message ->
            return Result.error(message)
        }

        // deviceProfile becomes the X-User-Agent model and part of the device signature. Blank
        // is fine (defaults to MAG250 internally); a non-blank value must be a safe ASCII token.
        if (normalizedDeviceProfile.isNotBlank() && !DEVICE_PROFILE_SAFE_REGEX.matches(normalizedDeviceProfile)) {
            return Result.error("Device profile must contain only letters, digits, dots, hyphens, and underscores (e.g. MAG250).")
        }

        // timezone is embedded literally in the Cookie header:
        //   Cookie: mac=...; stb_lang=...; timezone=<value>
        // A semicolon in the value would inject additional cookie pairs. An unknown zone ID
        // would silently cause MAG portals to reject authentication or use the wrong time.
        if (normalizedTimezone.isNotBlank()) {
            if (!COOKIE_VALUE_SAFE_REGEX.matches(normalizedTimezone)) {
                return Result.error("Timezone contains characters that are not allowed in this field.")
            }
            runCatching { ZoneId.of(normalizedTimezone) }.onFailure {
                return Result.error("Timezone is not a recognized identifier (e.g. America/New_York, Europe/London, UTC).")
            }
        }

        // locale is embedded in the Cookie header as stb_lang. Apply the same cookie-safety
        // check and require a conservative BCP-47 language-tag shape.
        if (normalizedLocale.isNotBlank()) {
            if (!LOCALE_SAFE_REGEX.matches(normalizedLocale)) {
                return Result.error("Locale must be a language tag like 'en' or 'en-US'.")
            }
        }

        return Result.success(
            ValidatedStalkerProviderInput(
                portalUrl = normalizedPortalUrl,
                macAddress = normalizedMacAddress,
                name = normalizedName,
                deviceProfile = normalizedDeviceProfile,
                timezone = normalizedTimezone,
                locale = normalizedLocale
            )
        )
    }

    private companion object {
        private val HEADER_SEPARATOR_REGEX = Regex("\\s*\\|\\s*|[\\r\\n]+")
        private val DISALLOWED_GENERIC_HEADER_NAMES = setOf(
            "authorization",
            "cookie",
            "proxy-authorization",
            "set-cookie",
            "user-agent"
        )

        // Safe ASCII token for device profiles (MAG250, MAG254, etc.). Excludes cookie
        // delimiters (;, ,, =, ") and HTTP header-breaking characters.
        private val DEVICE_PROFILE_SAFE_REGEX = Regex("^[A-Za-z0-9._-]+$")

        // Validates that a value can be safely embedded in a cookie without breaking the
        // Cookie: header. Semicolons, commas, double-quotes, and backslashes are all cookie
        // delimiters or quoting characters defined in RFC 6265 §4.1.1. Control characters
        // are already stripped by ProviderInputSanitizer before this point.
        private val COOKIE_VALUE_SAFE_REGEX = Regex("^[^;,\"\\\\]+$")

        // BCP-47 language tag: primary subtag (2–8 letters) optionally followed by
        // hyphen-separated extension subtags (1–8 alphanumeric characters each).
        private val LOCALE_SAFE_REGEX = Regex("^[a-zA-Z]{1,8}(-[a-zA-Z0-9]{1,8})*$")
    }

    private fun validateHttpOverrides(httpUserAgent: String, httpHeaders: String): String? {
        if (httpUserAgent.any { it == '\r' || it == '\n' }) {
            return "User-Agent must stay on a single line."
        }
        if (httpHeaders.isBlank()) {
            return null
        }
        val entries = httpHeaders
            .split(HEADER_SEPARATOR_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (entries.size > 12) {
            return "Too many custom headers. Limit the override to 12 entries."
        }
        val seenNames = linkedSetOf<String>()
        entries.forEach { entry ->
            val separatorIndex = entry.indexOf(':')
            if (separatorIndex <= 0 || separatorIndex == entry.lastIndex) {
                return "Custom headers must use 'Header-Name: value' format. Separate multiple headers with '|'."
            }
            val name = entry.substring(0, separatorIndex).trim()
            val value = entry.substring(separatorIndex + 1).trim()
            if (name.isEmpty() || value.isEmpty()) {
                return "Custom headers must include both a header name and a value."
            }
            if (name.lowercase() in DISALLOWED_GENERIC_HEADER_NAMES) {
                return if (name.equals("User-Agent", ignoreCase = true)) {
                    "Set the User-Agent in the dedicated field instead of custom headers."
                } else {
                    "$name cannot be overridden here."
                }
            }
            try {
                Headers.Builder().add(name, value)
            } catch (_: IllegalArgumentException) {
                return "Invalid custom header: $name"
            }
            if (!seenNames.add(name.lowercase())) {
                return "Duplicate custom header: $name"
            }
        }
        return null
    }
}
