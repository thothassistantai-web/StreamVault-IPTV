package com.streamvault.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streamvault.data.local.dao.ChannelPreferenceDao
import com.streamvault.data.local.entity.ChannelPreferenceEntity
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channelPreferenceDao: ChannelPreferenceDao
) {
    companion object {
        private const val PIN_SALT_BYTES = 16
        private const val PIN_HASH_ITERATIONS = 120_000
        private const val PIN_HASH_KEY_BITS = 256
        private val secureRandom = SecureRandom()
    }

    private object PreferencesKeys {
        val LAST_ACTIVE_PROVIDER_ID = longPreferencesKey("last_active_provider_id")
        val DEFAULT_VIEW_MODE = stringPreferencesKey("default_view_mode")
        val PARENTAL_CONTROL_LEVEL = intPreferencesKey("parental_control_level")
        val LEGACY_PARENTAL_PIN = stringPreferencesKey("parental_pin")
        val PARENTAL_PIN_HASH = stringPreferencesKey("parental_pin_hash")
        val PARENTAL_PIN_SALT = stringPreferencesKey("parental_pin_salt")
        val DEFAULT_CATEGORY_ID = longPreferencesKey("default_category_id")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val LIVE_TV_CHANNEL_MODE = stringPreferencesKey("live_tv_channel_mode")
        val GUIDE_DENSITY = stringPreferencesKey("guide_density")
        val GUIDE_CHANNEL_MODE = stringPreferencesKey("guide_channel_mode")
        val GUIDE_FAVORITES_ONLY = intPreferencesKey("guide_favorites_only")
        val GUIDE_ANCHOR_TIME = longPreferencesKey("guide_anchor_time")
        val PROMOTED_LIVE_GROUP_IDS = stringPreferencesKey("promoted_live_group_ids")
        val MULTIVIEW_PRESET_1 = stringPreferencesKey("multiview_preset_1")
        val MULTIVIEW_PRESET_2 = stringPreferencesKey("multiview_preset_2")
        val MULTIVIEW_PRESET_3 = stringPreferencesKey("multiview_preset_3")
        val MULTIVIEW_PERFORMANCE_MODE = stringPreferencesKey("multiview_performance_mode")
        val IS_INCOGNITO_MODE = booleanPreferencesKey("is_incognito_mode")
    }

    val lastActiveProviderId: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LAST_ACTIVE_PROVIDER_ID]
    }

    val defaultViewMode: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.DEFAULT_VIEW_MODE]
    }

    val isIncognitoMode: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.IS_INCOGNITO_MODE] ?: false
    }

    val parentalControlLevel: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.PARENTAL_CONTROL_LEVEL] ?: 1 // Default to 1 = LOCKED
        }

    val hasParentalPin: Flow<Boolean> = context.dataStore.data.map(::hasStoredParentalPin)

    suspend fun setLastActiveProviderId(id: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_ACTIVE_PROVIDER_ID] = id
        }
    }

    suspend fun setDefaultViewMode(viewMode: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_VIEW_MODE] = viewMode
        }
    }

    suspend fun setParentalControlLevel(level: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PARENTAL_CONTROL_LEVEL] = level
        }
    }

    suspend fun setIncognitoMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_INCOGNITO_MODE] = enabled
        }
    }

    suspend fun setParentalPin(pin: String) {
        val salt = ByteArray(PIN_SALT_BYTES).also { secureRandom.nextBytes(it) }
        val hash = hashPin(pin, salt)
        val saltBase64 = java.util.Base64.getEncoder().encodeToString(salt)
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PARENTAL_PIN_HASH] = hash
            preferences[PreferencesKeys.PARENTAL_PIN_SALT] = saltBase64
            preferences.remove(PreferencesKeys.LEGACY_PARENTAL_PIN)
        }
    }

    suspend fun verifyParentalPin(pin: String): Boolean {
        val preferences = context.dataStore.data.first()

        val storedHash = preferences[PreferencesKeys.PARENTAL_PIN_HASH]
        val storedSaltBase64 = preferences[PreferencesKeys.PARENTAL_PIN_SALT]

        if (!storedHash.isNullOrBlank() && !storedSaltBase64.isNullOrBlank()) {
            val salt = runCatching { java.util.Base64.getDecoder().decode(storedSaltBase64) }.getOrNull()
                ?: return false
            return hashPin(pin, salt) == storedHash
        }

        val legacyPin = preferences[PreferencesKeys.LEGACY_PARENTAL_PIN]
        val valid = !legacyPin.isNullOrBlank() && pin == legacyPin

        if (valid) {
            // One-way migrate legacy PIN storage to hashed PIN storage.
            setParentalPin(pin)
        }

        return valid
    }

    suspend fun clearDefaultViewMode() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.DEFAULT_VIEW_MODE)
        }
    }

    val defaultCategoryId: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.DEFAULT_CATEGORY_ID]
    }

    suspend fun setDefaultCategory(categoryId: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_CATEGORY_ID] = categoryId
        }
    }

    fun getLastLiveCategoryId(providerId: Long): Flow<Long?> {
        val key = longPreferencesKey("last_live_category_id_$providerId")
        return context.dataStore.data.map { preferences ->
            preferences[key]
        }
    }

    suspend fun setLastLiveCategoryId(providerId: Long, categoryId: Long) {
        val key = longPreferencesKey("last_live_category_id_$providerId")
        context.dataStore.edit { preferences ->
            preferences[key] = categoryId
        }
    }

    val appLanguage: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.APP_LANGUAGE] ?: "system"
    }

    suspend fun setAppLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_LANGUAGE] = language
        }
    }

    val liveTvChannelMode: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LIVE_TV_CHANNEL_MODE]
    }

    suspend fun setLiveTvChannelMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LIVE_TV_CHANNEL_MODE] = mode
        }
    }

    val guideDensity: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.GUIDE_DENSITY]
    }

    suspend fun setGuideDensity(density: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GUIDE_DENSITY] = density
        }
    }

    val guideChannelMode: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.GUIDE_CHANNEL_MODE]
    }

    suspend fun setGuideChannelMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GUIDE_CHANNEL_MODE] = mode
        }
    }

    val guideFavoritesOnly: Flow<Boolean> = context.dataStore.data.map { preferences ->
        (preferences[PreferencesKeys.GUIDE_FAVORITES_ONLY] ?: 0) == 1
    }

    suspend fun setGuideFavoritesOnly(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GUIDE_FAVORITES_ONLY] = if (enabled) 1 else 0
        }
    }

    val guideAnchorTime: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.GUIDE_ANCHOR_TIME]
    }

    suspend fun setGuideAnchorTime(anchorTimeMs: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GUIDE_ANCHOR_TIME] = anchorTimeMs
        }
    }

    val promotedLiveGroupIds: Flow<Set<Long>> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PROMOTED_LIVE_GROUP_IDS]
            ?.split(",")
            ?.mapNotNull { token -> token.toLongOrNull() }
            ?.toSet()
            .orEmpty()
    }

    suspend fun setPromotedLiveGroupIds(groupIds: Set<Long>) {
        context.dataStore.edit { preferences ->
            if (groupIds.isEmpty()) {
                preferences.remove(PreferencesKeys.PROMOTED_LIVE_GROUP_IDS)
            } else {
                preferences[PreferencesKeys.PROMOTED_LIVE_GROUP_IDS] = groupIds.sorted().joinToString(",")
            }
        }
    }

    fun getMultiViewPreset(presetIndex: Int): Flow<List<Long>> {
        val key = when (presetIndex) {
            0 -> PreferencesKeys.MULTIVIEW_PRESET_1
            1 -> PreferencesKeys.MULTIVIEW_PRESET_2
            else -> PreferencesKeys.MULTIVIEW_PRESET_3
        }
        return context.dataStore.data.map { preferences ->
            preferences[key]
                ?.split(",")
                ?.mapNotNull { token -> token.toLongOrNull() }
                .orEmpty()
        }
    }

    suspend fun setMultiViewPreset(presetIndex: Int, channelIds: List<Long>) {
        val key = when (presetIndex) {
            0 -> PreferencesKeys.MULTIVIEW_PRESET_1
            1 -> PreferencesKeys.MULTIVIEW_PRESET_2
            else -> PreferencesKeys.MULTIVIEW_PRESET_3
        }
        context.dataStore.edit { preferences ->
            if (channelIds.isEmpty()) {
                preferences.remove(key)
            } else {
                preferences[key] = channelIds.joinToString(",")
            }
        }
    }

    val multiViewPerformanceMode: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.MULTIVIEW_PERFORMANCE_MODE]
    }

    suspend fun setMultiViewPerformanceMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MULTIVIEW_PERFORMANCE_MODE] = mode
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getAspectRatioForChannel(channelId: Long): Flow<String?> {
        return channelPreferenceDao.observeAspectRatio(channelId).flatMapLatest { persistedRatio ->
            if (persistedRatio != null) {
                flowOf(persistedRatio)
            } else {
                val legacyKey = stringPreferencesKey("aspect_ratio_$channelId")
                context.dataStore.data.map { preferences ->
                    preferences[legacyKey]
                }
            }
        }
    }

    suspend fun setAspectRatioForChannel(channelId: Long, ratio: String) {
        channelPreferenceDao.upsert(
            ChannelPreferenceEntity(
                channelId = channelId,
                aspectRatio = ratio,
                updatedAt = System.currentTimeMillis()
            )
        )
        val key = stringPreferencesKey("aspect_ratio_$channelId")
        context.dataStore.edit { preferences ->
            preferences.remove(key)
        }
    }

    suspend fun clearAllRecentData() {
        channelPreferenceDao.deleteAll()
        context.dataStore.edit { preferences ->
            val keysToRemove = preferences.asMap().keys.filter { key ->
                key.name.startsWith("last_live_category_id_") || 
                key.name.startsWith("aspect_ratio_") ||
                key.name == PreferencesKeys.DEFAULT_CATEGORY_ID.name
            }
            keysToRemove.forEach { key ->
                preferences.remove(key)
            }
        }
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PIN_HASH_ITERATIONS, PIN_HASH_KEY_BITS)
        val secret = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return java.util.Base64.getEncoder().encodeToString(secret.encoded)
    }

    private fun hasStoredParentalPin(preferences: Preferences): Boolean {
        val storedHash = preferences[PreferencesKeys.PARENTAL_PIN_HASH]
        val storedSaltBase64 = preferences[PreferencesKeys.PARENTAL_PIN_SALT]
        if (!storedHash.isNullOrBlank() && !storedSaltBase64.isNullOrBlank()) {
            return true
        }

        return !preferences[PreferencesKeys.LEGACY_PARENTAL_PIN].isNullOrBlank()
    }
}
