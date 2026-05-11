package com.streamvault.domain.manager

import com.streamvault.domain.model.Result

data class ValidatedXtreamProviderInput(
    val serverUrl: String,
    val username: String,
    val password: String,
    val name: String,
    val httpUserAgent: String,
    val httpHeaders: String
)

data class ValidatedM3uProviderInput(
    val url: String,
    val name: String,
    val httpUserAgent: String,
    val httpHeaders: String
)

data class ValidatedStalkerProviderInput(
    val portalUrl: String,
    val macAddress: String,
    val name: String,
    val deviceProfile: String,
    val timezone: String,
    val locale: String
)

interface ProviderSetupInputValidator {
    fun validateXtream(
        serverUrl: String,
        username: String,
        password: String,
        allowBlankPassword: Boolean = false,
        name: String,
        httpUserAgent: String = "",
        httpHeaders: String = ""
    ): Result<ValidatedXtreamProviderInput>

    fun validateM3u(
        url: String,
        name: String,
        httpUserAgent: String = "",
        httpHeaders: String = ""
    ): Result<ValidatedM3uProviderInput>

    fun validateStalker(
        portalUrl: String,
        macAddress: String,
        name: String,
        deviceProfile: String,
        timezone: String,
        locale: String
    ): Result<ValidatedStalkerProviderInput>
}
