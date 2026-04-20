package com.streamvault.domain.manager

import com.streamvault.domain.model.Result

data class ValidatedXtreamProviderInput(
    val serverUrl: String,
    val username: String,
    val name: String
)

data class ValidatedM3uProviderInput(
    val url: String,
    val name: String
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
        name: String
    ): Result<ValidatedXtreamProviderInput>

    fun validateM3u(
        url: String,
        name: String
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
