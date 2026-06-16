package com.streamvault.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.manager.ProviderSetupInputValidator
import com.streamvault.domain.manager.ValidatedJellyfinProviderInput
import com.streamvault.domain.manager.ValidatedJellyfinQuickConnectProviderInput
import com.streamvault.domain.model.Program
import com.streamvault.domain.manager.ValidatedM3uProviderInput
import com.streamvault.domain.manager.ValidatedStalkerProviderInput
import com.streamvault.domain.manager.ValidatedXtreamProviderInput
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderSavedWithSyncErrorException
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ValidateAndAddProviderTest {

    @Test
    fun `validateXtreamInput returns null when input is valid without calling repository`() {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(),
            providerRepository = repository
        )

        val error = useCase.validateXtreamInput(
            XtreamProviderSetupCommand(serverUrl = "https://example.com", username = "alice", password = "secret", name = "Provider")
        )

        assertThat(error).isNull()
        assertThat(repository.lastXtreamCall).isNull()
    }

    @Test
    fun `validateXtreamInput returns ValidationError without calling repository`() {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                xtreamResult = Result.error("Please enter server URL")
            ),
            providerRepository = repository
        )

        val error = useCase.validateXtreamInput(
            XtreamProviderSetupCommand(serverUrl = "", username = "alice", password = "secret", name = "Provider")
        )

        assertThat(error).isInstanceOf(ValidateAndAddProviderResult.ValidationError::class.java)
        assertThat((error as ValidateAndAddProviderResult.ValidationError).message).isEqualTo("Please enter server URL")
        assertThat(repository.lastXtreamCall).isNull()
    }

    @Test
    fun `validateM3uInput returns null when input is valid without calling repository`() {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(),
            providerRepository = repository
        )

        val error = useCase.validateM3uInput(
            M3uProviderSetupCommand(url = "https://example.com/list.m3u", name = "Playlist")
        )

        assertThat(error).isNull()
        assertThat(repository.lastM3uCall).isNull()
    }

    @Test
    fun `validateM3uInput returns ValidationError without calling repository`() {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                m3uResult = Result.error("Please enter M3U URL")
            ),
            providerRepository = repository
        )

        val error = useCase.validateM3uInput(
            M3uProviderSetupCommand(url = "", name = "Playlist")
        )

        assertThat(error).isInstanceOf(ValidateAndAddProviderResult.ValidationError::class.java)
        assertThat(repository.lastM3uCall).isNull()
    }

    @Test
    fun `validateStalkerInput returns null when input is valid without calling repository`() {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(),
            providerRepository = repository
        )

        val error = useCase.validateStalkerInput(
            StalkerProviderSetupCommand(
                portalUrl = "https://portal.example.com",
                macAddress = "00:1A:79:12:34:56",
                name = "MAG"
            )
        )

        assertThat(error).isNull()
        assertThat(repository.lastStalkerCall).isNull()
    }

    @Test
    fun `validateStalkerInput returns ValidationError without calling repository`() {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                stalkerResult = Result.error("Please enter portal URL")
            ),
            providerRepository = repository
        )

        val error = useCase.validateStalkerInput(
            StalkerProviderSetupCommand(portalUrl = "", macAddress = "00:1A:79:12:34:56", name = "MAG")
        )

        assertThat(error).isInstanceOf(ValidateAndAddProviderResult.ValidationError::class.java)
        assertThat(repository.lastStalkerCall).isNull()
    }

    @Test
    fun returns_validation_error_without_calling_repository_for_xtream() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                xtreamResult = Result.error("Please enter server URL")
            ),
            providerRepository = repository
        )

        val result = useCase.loginXtream(
            XtreamProviderSetupCommand(
                serverUrl = "",
                username = "user",
                password = "secret",
                name = "Provider"
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.ValidationError::class.java)
        assertThat((result as ValidateAndAddProviderResult.ValidationError).message).isEqualTo("Please enter server URL")
        assertThat(repository.lastXtreamCall).isNull()
    }

    @Test
    fun delegates_normalized_xtream_input_to_repository() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                xtreamResult = Result.success(
                    ValidatedXtreamProviderInput(
                        serverUrl = "https://example.com",
                        username = "alice",
                        password = "normalized-secret",
                        name = "Premium",
                        httpUserAgent = "StreamVaultTest/1.0",
                        httpHeaders = "Referer: https://example.com"
                    )
                )
            ),
            providerRepository = repository
        )

        val result = useCase.loginXtream(
            XtreamProviderSetupCommand(
                serverUrl = " https://example.com ",
                username = " alice ",
                password = "secret\u0000",
                name = " Premium ",
                httpUserAgent = " StreamVaultTest/1.0 ",
                httpHeaders = " Referer: https://example.com ",
                xtreamFastSyncEnabled = true,
                epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                existingProviderId = 7L
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.Success::class.java)
        assertThat(repository.lastXtreamCall).isEqualTo(
            XtreamCall(
                serverUrl = "https://example.com",
                username = "alice",
                password = "normalized-secret",
                name = "Premium",
                httpUserAgent = "StreamVaultTest/1.0",
                httpHeaders = "Referer: https://example.com",
                xtreamFastSyncEnabled = true,
                epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                xtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
                id = 7L
            )
        )
    }

    @Test
    fun allows_blank_xtream_password_when_editing_existing_provider() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                xtreamResult = Result.success(
                    ValidatedXtreamProviderInput(
                        serverUrl = "https://example.com",
                        username = "alice",
                        password = "",
                        name = "Premium",
                        httpUserAgent = "",
                        httpHeaders = ""
                    )
                )
            ),
            providerRepository = repository
        )

        val result = useCase.loginXtream(
            XtreamProviderSetupCommand(
                serverUrl = "https://example.com",
                username = "alice",
                password = "",
                name = "Premium",
                existingProviderId = 7L
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.Success::class.java)
        assertThat(repository.lastXtreamCall?.password).isEmpty()
    }

    @Test
    fun maps_saved_provider_sync_failure_to_saved_with_warning() = runTest {
        val repository = FakeProviderRepository().apply {
            xtreamResult = Result.error(
                "Provider login succeeded, but initial sync failed. The provider was saved and can be retried from Settings: timeout",
                ProviderSavedWithSyncErrorException(
                    provider = provider(id = 7L, name = "Premium", type = ProviderType.XTREAM_CODES).copy(status = ProviderStatus.ERROR),
                    message = "Provider login succeeded, but initial sync failed. The provider was saved and can be retried from Settings: timeout"
                )
            )
        }
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(),
            providerRepository = repository
        )

        val result = useCase.loginXtream(
            XtreamProviderSetupCommand(
                serverUrl = "https://example.com",
                username = "alice",
                password = "secret",
                name = "Premium"
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.SavedWithWarning::class.java)
        result as ValidateAndAddProviderResult.SavedWithWarning
        assertThat(result.provider.id).isEqualTo(7L)
        assertThat(result.provider.status).isEqualTo(ProviderStatus.ERROR)
        assertThat(result.warning).contains("initial sync failed")
    }

    @Test
    fun delegates_validated_m3u_input_to_repository() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                m3uResult = Result.success(
                    ValidatedM3uProviderInput(
                        url = "file://playlist.m3u",
                        name = "Local Playlist",
                        httpUserAgent = "PlaylistAgent/2.0",
                        httpHeaders = "Referer: https://example.com"
                    )
                )
            ),
            providerRepository = repository
        )

        val result = useCase.addM3u(
            M3uProviderSetupCommand(
                url = "file://playlist.m3u",
                name = "Local Playlist",
                httpUserAgent = "PlaylistAgent/2.0",
                httpHeaders = "Referer: https://example.com",
                epgSyncMode = ProviderEpgSyncMode.SKIP,
                existingProviderId = 11L
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.Success::class.java)
        assertThat(repository.lastM3uCall).isEqualTo(
            M3uCall(
                url = "file://playlist.m3u",
                name = "Local Playlist",
                httpUserAgent = "PlaylistAgent/2.0",
                httpHeaders = "Referer: https://example.com",
                epgSyncMode = ProviderEpgSyncMode.SKIP,
                m3uVodClassificationEnabled = false,
                id = 11L
            )
        )
    }

    @Test
    fun auto_converts_xtream_playlist_url_to_xtream_login() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                m3uResult = Result.success(
                    ValidatedM3uProviderInput(
                        url = "http://extapk2302.shop:8080/get.php?username=Hakan1605&password=wg9daUwzfV&type=m3u_plus",
                        name = "Imported Playlist",
                        httpUserAgent = "PlaylistAgent/2.0",
                        httpHeaders = "Referer: https://example.com"
                    )
                )
            ),
            providerRepository = repository
        )

        val result = useCase.addM3u(
            M3uProviderSetupCommand(
                url = "http://extapk2302.shop:8080/get.php?username=Hakan1605&password=wg9daUwzfV&type=m3u_plus",
                name = "Imported Playlist",
                epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                existingProviderId = 19L
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.Success::class.java)
        assertThat(repository.lastM3uCall).isNull()
        assertThat(repository.lastXtreamCall).isEqualTo(
            XtreamCall(
                serverUrl = "http://extapk2302.shop:8080",
                username = "Hakan1605",
                password = "wg9daUwzfV",
                name = "Imported Playlist",
                httpUserAgent = "PlaylistAgent/2.0",
                httpHeaders = "Referer: https://example.com",
                xtreamFastSyncEnabled = false,
                epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                xtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
                id = 19L
            )
        )
    }

    @Test
    fun rejects_xtream_playlist_url_with_embedded_credentials_in_authority() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                m3uResult = Result.success(
                    ValidatedM3uProviderInput(
                        url = "http://tvappapk@extapk2302.shop:8080/get.php?username=Hakan1605&password=wg9daUwzfV&type=m3u_plus",
                        name = "Imported Playlist",
                        httpUserAgent = "",
                        httpHeaders = ""
                    )
                )
            ),
            providerRepository = repository
        )

        val result = useCase.addM3u(
            M3uProviderSetupCommand(
                url = "http://tvappapk@extapk2302.shop:8080/get.php?username=Hakan1605&password=wg9daUwzfV&type=m3u_plus",
                name = "Imported Playlist"
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.ValidationError::class.java)
        assertThat((result as ValidateAndAddProviderResult.ValidationError).message)
            .isEqualTo("Playlist sources must not include embedded credentials in the URL authority.")
        assertThat(repository.lastXtreamCall).isNull()
    }

    @Test
    fun rejects_xtream_playlist_with_oversized_decoded_password() = runTest {
        val repository = FakeProviderRepository()
        val oversizedPassword = "p".repeat(257)
        val encodedPassword = java.net.URLEncoder.encode(oversizedPassword, java.nio.charset.StandardCharsets.UTF_8.name())
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                m3uResult = Result.success(
                    ValidatedM3uProviderInput(
                        url = "https://example.com/get.php?username=user&password=$encodedPassword&type=m3u_plus",
                        name = "Imported Playlist",
                        httpUserAgent = "",
                        httpHeaders = ""
                    )
                )
            ),
            providerRepository = repository
        )

        val result = useCase.addM3u(
            M3uProviderSetupCommand(
                url = "https://example.com/get.php?username=user&password=$encodedPassword&type=m3u_plus",
                name = "Imported Playlist"
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.ValidationError::class.java)
        assertThat((result as ValidateAndAddProviderResult.ValidationError).message)
            .isEqualTo("Playlist password is too long.")
        assertThat(repository.lastXtreamCall).isNull()
        assertThat(repository.lastM3uCall).isNull()
    }

    @Test
    fun delegates_validated_stalker_input_to_repository() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                stalkerResult = Result.success(
                    ValidatedStalkerProviderInput(
                        portalUrl = "https://portal.example.com",
                        macAddress = "00:1A:79:12:34:56",
                        authMode = StalkerAuthMode.AUTO,
                        username = "",
                        password = "",
                        name = "MAG",
                        httpUserAgent = "Stalker Agent/1.0",
                        httpHeaders = "Referer: | X-Test: enabled",
                        deviceProfile = "MAG250",
                        timezone = "UTC",
                        locale = "en"
                    )
                )
            ),
            providerRepository = repository
        )

        val result = useCase.loginStalker(
            StalkerProviderSetupCommand(
                portalUrl = " https://portal.example.com ",
                macAddress = "00-1a-79-12-34-56",
                authMode = StalkerAuthMode.AUTO,
                username = "",
                password = "",
                name = " MAG ",
                httpUserAgent = " Stalker Agent/1.0 ",
                httpHeaders = " Referer: | X-Test: enabled ",
                deviceProfile = " MAG250 ",
                timezone = " UTC ",
                locale = " en ",
                epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                existingProviderId = 21L
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.Success::class.java)
        assertThat(repository.lastStalkerCall).isEqualTo(
            StalkerCall(
                portalUrl = "https://portal.example.com",
                macAddress = "00:1A:79:12:34:56",
                authMode = StalkerAuthMode.AUTO,
                username = "",
                password = "",
                name = "MAG",
                httpUserAgent = "Stalker Agent/1.0",
                httpHeaders = "Referer: | X-Test: enabled",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en",
                epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                id = 21L
            )
        )
    }
}

private class FakeProviderSetupInputValidator(
    private val xtreamResult: Result<ValidatedXtreamProviderInput> = Result.success(
        ValidatedXtreamProviderInput(
            serverUrl = "https://example.com",
            username = "user",
            password = "secret",
            name = "Provider",
            httpUserAgent = "",
            httpHeaders = ""
        )
    ),
    private val m3uResult: Result<ValidatedM3uProviderInput> = Result.success(
        ValidatedM3uProviderInput(
            url = "https://example.com/playlist.m3u",
            name = "Playlist",
            httpUserAgent = "",
            httpHeaders = ""
        )
    ),
    private val stalkerResult: Result<ValidatedStalkerProviderInput> = Result.success(
        ValidatedStalkerProviderInput(
            portalUrl = "https://portal.example.com",
            macAddress = "00:1A:79:12:34:56",
            authMode = StalkerAuthMode.AUTO,
            username = "",
            password = "",
            name = "Provider",
            httpUserAgent = "",
            httpHeaders = "",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
    ),
    private val jellyfinResult: Result<ValidatedJellyfinProviderInput> = Result.success(
        ValidatedJellyfinProviderInput(
            serverUrl = "https://jellyfin.example.com",
            username = "user",
            password = "secret",
            name = "Jellyfin"
        )
    ),
    private val jellyfinQuickConnectResult: Result<ValidatedJellyfinQuickConnectProviderInput> = Result.success(
        ValidatedJellyfinQuickConnectProviderInput(
            serverUrl = "https://jellyfin.example.com",
            name = "Jellyfin"
        )
    )
) : ProviderSetupInputValidator {
    override fun validateXtream(
        serverUrl: String,
        username: String,
        password: String,
        allowBlankPassword: Boolean,
        name: String,
        httpUserAgent: String,
        httpHeaders: String
    ): Result<ValidatedXtreamProviderInput> = xtreamResult

    override fun validateM3u(
        url: String,
        name: String,
        httpUserAgent: String,
        httpHeaders: String
    ): Result<ValidatedM3uProviderInput> = m3uResult

    override fun validateStalker(
        portalUrl: String,
        macAddress: String,
        name: String,
        authMode: StalkerAuthMode,
        username: String,
        password: String,
        allowBlankPassword: Boolean,
        httpUserAgent: String,
        httpHeaders: String,
        deviceProfile: String,
        timezone: String,
        locale: String,
        serialNumber: String,
        deviceId: String,
        deviceId2: String,
        signature: String,
        stalkerAdvancedOptionsJson: String
    ): Result<ValidatedStalkerProviderInput> = stalkerResult

    override fun validateJellyfin(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        allowBlankPassword: Boolean
    ): Result<ValidatedJellyfinProviderInput> = jellyfinResult

    override fun validateJellyfinQuickConnect(
        serverUrl: String,
        name: String
    ): Result<ValidatedJellyfinQuickConnectProviderInput> = jellyfinQuickConnectResult
}

private data class XtreamCall(
    val serverUrl: String,
    val username: String,
    val password: String,
    val name: String,
    val httpUserAgent: String,
    val httpHeaders: String,
    val xtreamFastSyncEnabled: Boolean,
    val epgSyncMode: ProviderEpgSyncMode,
    val xtreamLiveSyncMode: ProviderXtreamLiveSyncMode,
    val id: Long?
)

private data class M3uCall(
    val url: String,
    val name: String,
    val httpUserAgent: String,
    val httpHeaders: String,
    val epgSyncMode: ProviderEpgSyncMode,
    val m3uVodClassificationEnabled: Boolean,
    val id: Long?
)

private data class StalkerCall(
    val portalUrl: String,
    val macAddress: String,
    val authMode: StalkerAuthMode,
    val username: String,
    val password: String,
    val name: String,
    val httpUserAgent: String,
    val httpHeaders: String,
    val deviceProfile: String,
    val timezone: String,
    val locale: String,
    val serialNumber: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
    val stalkerAdvancedOptionsJson: String = "",
    val epgSyncMode: ProviderEpgSyncMode,
    val id: Long?
)

private class FakeProviderRepository : ProviderRepository {
    var lastXtreamCall: XtreamCall? = null
    var lastM3uCall: M3uCall? = null
    var lastStalkerCall: StalkerCall? = null
    var xtreamResult: Result<Provider>? = null
    var m3uResult: Result<Provider>? = null
    var stalkerResult: Result<Provider>? = null

    override fun getProviders(): Flow<List<Provider>> = flowOf(emptyList())

    override fun getActiveProvider(): Flow<Provider?> = flowOf(null)

    override suspend fun getProvider(id: Long): Provider? = null

    override suspend fun addProvider(provider: Provider): Result<Long> = error("Not used in test")

    override suspend fun updateProvider(provider: Provider): Result<Unit> = error("Not used in test")

    override suspend fun deleteProvider(id: Long): Result<Unit> = error("Not used in test")

    override suspend fun getAllProviderCredentials(): List<ProviderCredentials> = emptyList()

    override suspend fun updateProviderPassword(
        serverUrl: String,
        username: String,
        cleartextPassword: String
    ): Boolean = false

    override suspend fun setActiveProvider(id: Long): Result<Unit> = error("Not used in test")

    override suspend fun loginXtream(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        httpUserAgent: String,
        httpHeaders: String,
        xtreamFastSyncEnabled: Boolean,
        epgSyncMode: ProviderEpgSyncMode,
        xtreamLiveSyncMode: ProviderXtreamLiveSyncMode,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> {
        lastXtreamCall = XtreamCall(serverUrl, username, password, name, httpUserAgent, httpHeaders, xtreamFastSyncEnabled, epgSyncMode, xtreamLiveSyncMode, id)
        return xtreamResult ?: Result.success(provider(id = id ?: 1L, name = name, type = ProviderType.XTREAM_CODES))
    }

    override suspend fun validateM3u(
        url: String,
        name: String,
        httpUserAgent: String,
        httpHeaders: String,
        epgSyncMode: ProviderEpgSyncMode,
        m3uVodClassificationEnabled: Boolean,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> {
        lastM3uCall = M3uCall(url, name, httpUserAgent, httpHeaders, epgSyncMode, m3uVodClassificationEnabled, id)
        return m3uResult ?: Result.success(provider(id = id ?: 2L, name = name, type = ProviderType.M3U, m3uUrl = url))
    }

    override suspend fun loginStalker(
        portalUrl: String,
        macAddress: String,
        name: String,
        authMode: StalkerAuthMode,
        username: String,
        password: String,
        httpUserAgent: String,
        httpHeaders: String,
        deviceProfile: String,
        timezone: String,
        locale: String,
        serialNumber: String,
        deviceId: String,
        deviceId2: String,
        signature: String,
        stalkerAdvancedOptionsJson: String,
        epgSyncMode: ProviderEpgSyncMode,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> {
        lastStalkerCall = StalkerCall(
            portalUrl = portalUrl,
            macAddress = macAddress,
            authMode = authMode,
            username = username,
            password = password,
            name = name,
            httpUserAgent = httpUserAgent,
            httpHeaders = httpHeaders,
            deviceProfile = deviceProfile,
            timezone = timezone,
            locale = locale,
            serialNumber = serialNumber,
            deviceId = deviceId,
            deviceId2 = deviceId2,
            signature = signature,
            stalkerAdvancedOptionsJson = stalkerAdvancedOptionsJson,
            epgSyncMode = epgSyncMode,
            id = id
        )
        return stalkerResult ?: Result.success(
            provider(id = id ?: 3L, name = name, type = ProviderType.STALKER_PORTAL).copy(
                serverUrl = portalUrl,
                username = username,
                password = password,
                httpUserAgent = httpUserAgent,
                httpHeaders = httpHeaders,
                stalkerMacAddress = macAddress,
                stalkerAuthMode = authMode,
                stalkerDeviceProfile = deviceProfile,
                stalkerDeviceTimezone = timezone,
                stalkerDeviceLocale = locale,
                stalkerSerialNumber = serialNumber,
                stalkerDeviceId = deviceId,
                stalkerDeviceId2 = deviceId2,
                stalkerSignature = signature
            )
        )
    }

    override suspend fun loginJellyfin(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> {
        return Result.success(
            provider(id = id ?: 4L, name = name, type = ProviderType.JELLYFIN).copy(
                serverUrl = serverUrl,
                username = username,
                password = password
            )
        )
    }

    override suspend fun loginJellyfinQuickConnect(
        serverUrl: String,
        name: String,
        onCode: ((String) -> Unit)?,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> {
        onCode?.invoke("ABCD")
        return Result.success(
            provider(id = id ?: 5L, name = name, type = ProviderType.JELLYFIN).copy(
                serverUrl = serverUrl,
                username = name,
                password = "quick-connect-token"
            )
        )
    }

    override suspend fun refreshProviderData(
        providerId: Long,
        force: Boolean,
        movieFastSyncOverride: Boolean?,
        epgSyncModeOverride: ProviderEpgSyncMode?,
        onProgress: ((String) -> Unit)?
    ): Result<Unit> = error("Not used in test")

    override suspend fun getProgramsForLiveStream(
        providerId: Long,
        streamId: Long,
        epgChannelId: String?,
        limit: Int
    ): Result<List<Program>> = error("Not used in test")

    override suspend fun buildCatchUpUrl(providerId: Long, streamId: Long, start: Long, end: Long): String? = null

    fun provider(
        id: Long,
        name: String,
        type: ProviderType,
        m3uUrl: String = ""
    ) = Provider(
        id = id,
        name = name,
        type = type,
        serverUrl = if (type == ProviderType.M3U) m3uUrl else "https://example.com",
        username = if (type == ProviderType.XTREAM_CODES) "user" else "",
        m3uUrl = m3uUrl,
        status = ProviderStatus.ACTIVE
    )
}
