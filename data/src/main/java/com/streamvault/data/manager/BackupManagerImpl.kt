package com.streamvault.data.manager

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.VirtualGroupDao
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.manager.BackupData
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupImportResult
import com.streamvault.domain.manager.BackupManager
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.model.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton

class BackupManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val providerDao: ProviderDao,
    private val favoriteDao: FavoriteDao,
    private val virtualGroupDao: VirtualGroupDao,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val gson: Gson
) : BackupManager {

    override suspend fun exportConfig(uriString: String): com.streamvault.domain.model.Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            
            // 1. Gather Data
            val prefs = mapOf(
                "parentalControlLevel" to preferencesRepository.parentalControlLevel.first().toString(),
                "appLanguage" to preferencesRepository.appLanguage.first(),
                "guideDensity" to (preferencesRepository.guideDensity.first() ?: ""),
                "guideChannelMode" to (preferencesRepository.guideChannelMode.first() ?: ""),
                "guideFavoritesOnly" to preferencesRepository.guideFavoritesOnly.first().toString(),
                "guideAnchorTime" to (preferencesRepository.guideAnchorTime.first() ?: 0L).toString(),
                "lastActiveProviderId" to (preferencesRepository.lastActiveProviderId.first() ?: -1L).toString(),
                "promotedLiveGroupIds" to preferencesRepository.promotedLiveGroupIds.first().sorted().joinToString(",")
            )

            val providers = providerDao.getAll().first().map { entity ->
                entity.toDomain().copy(
                    password = "",  // Strip credentials from backup export
                    username = entity.toDomain().username // Keep username for provider identification
                )
            }

            // Gather all favorites across all types
            val liveFavs = favoriteDao.getAllByType("LIVE").first().map { it.toDomain() }
            val movieFavs = favoriteDao.getAllByType("MOVIE").first().map { it.toDomain() }
            val seriesFavs = favoriteDao.getAllByType("SERIES").first().map { it.toDomain() }
            val allFavorites = liveFavs + movieFavs + seriesFavs

            // Gather all custom groups
            val liveGroups = virtualGroupDao.getByType("LIVE").first().map { it.toDomain() }
            val movieGroups = virtualGroupDao.getByType("MOVIE").first().map { it.toDomain() }
            val seriesGroups = virtualGroupDao.getByType("SERIES").first().map { it.toDomain() }
            val allGroups = liveGroups + movieGroups + seriesGroups

            val playbackHistory = playbackHistoryDao.getAllSync().map { it.toDomain() }
            val multiViewPresets = mapOf(
                "preset_1" to preferencesRepository.getMultiViewPreset(0).first(),
                "preset_2" to preferencesRepository.getMultiViewPreset(1).first(),
                "preset_3" to preferencesRepository.getMultiViewPreset(2).first()
            )

            val backupData = BackupData(
                version = 2,
                preferences = prefs,
                providers = providers,
                favorites = allFavorites,
                virtualGroups = allGroups,
                playbackHistory = playbackHistory,
                multiViewPresets = multiViewPresets
            )

            // Compute checksum over the data without checksum field
            val jsonWithoutChecksum = gson.toJson(backupData)
            val crc = CRC32()
            crc.update(jsonWithoutChecksum.toByteArray(Charsets.UTF_8))
            val backupWithChecksum = backupData.copy(checksum = crc.value.toString(16))

            // 2. Serialize and write to URI
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    gson.toJson(backupWithChecksum, writer)
                }
            } ?: return@withContext com.streamvault.domain.model.Result.error("Failed to open output stream")

            com.streamvault.domain.model.Result.success(Unit)
        } catch (e: Exception) {
            com.streamvault.domain.model.Result.error("Failed to export backup: ${e.message}", e)
        }
    }

    override suspend fun inspectBackup(uriString: String): Result<BackupPreview> = withContext(Dispatchers.IO) {
        try {
            val backupData = readBackupData(uriString)
                ?: return@withContext Result.error("Failed to open input stream")
            if (backupData.version > 2) {
                return@withContext Result.error("Unsupported backup version")
            }
            if (!verifyChecksum(backupData)) {
                return@withContext Result.error("Backup file is corrupted (checksum mismatch)")
            }

            val existingProviders = providerDao.getAll().first()
            val existingGroups = buildList {
                addAll(virtualGroupDao.getByType("LIVE").first())
                addAll(virtualGroupDao.getByType("MOVIE").first())
                addAll(virtualGroupDao.getByType("SERIES").first())
            }
            val existingFavorites = buildList {
                addAll(favoriteDao.getAllByType("LIVE").first())
                addAll(favoriteDao.getAllByType("MOVIE").first())
                addAll(favoriteDao.getAllByType("SERIES").first())
            }
            val existingHistory = playbackHistoryDao.getAllSync()

            val providerConflicts = backupData.providers.orEmpty().count { incoming ->
                existingProviders.any { it.serverUrl == incoming.serverUrl && it.username == incoming.username }
            }
            val groupConflicts = backupData.virtualGroups.orEmpty().count { incoming ->
                existingGroups.any { it.name.equals(incoming.name, ignoreCase = true) && it.contentType == incoming.contentType.name }
            }
            val favoriteConflicts = backupData.favorites.orEmpty().count { incoming ->
                existingFavorites.any {
                    it.contentId == incoming.contentId &&
                        it.contentType == incoming.contentType.name &&
                        it.groupId == incoming.groupId
                }
            }
            val historyConflicts = backupData.playbackHistory.orEmpty().count { incoming ->
                existingHistory.any {
                    it.contentId == incoming.contentId &&
                        it.contentType == incoming.contentType.name &&
                        it.providerId == incoming.providerId
                }
            }

            Result.success(
                BackupPreview(
                    version = backupData.version,
                    providerCount = backupData.providers.orEmpty().size,
                    favoriteCount = backupData.favorites.orEmpty().size,
                    groupCount = backupData.virtualGroups.orEmpty().size,
                    playbackHistoryCount = backupData.playbackHistory.orEmpty().size,
                    multiViewPresetCount = backupData.multiViewPresets.orEmpty().count { it.value.isNotEmpty() },
                    preferenceCount = backupData.preferences.orEmpty().size,
                    providerConflicts = providerConflicts,
                    favoriteConflicts = favoriteConflicts,
                    groupConflicts = groupConflicts,
                    historyConflicts = historyConflicts
                )
            )
        } catch (e: Exception) {
            Result.error("Failed to inspect backup: ${e.message}", e)
        }
    }

    override suspend fun importConfig(
        uriString: String,
        plan: BackupImportPlan
    ): com.streamvault.domain.model.Result<BackupImportResult> = withContext(Dispatchers.IO) {
        try {
            val backupData = readBackupData(uriString)
                ?: return@withContext com.streamvault.domain.model.Result.error("Failed to open input stream")

            if (backupData.version > 2) {
                return@withContext com.streamvault.domain.model.Result.error("Unsupported backup version")
            }
            if (!verifyChecksum(backupData)) {
                return@withContext com.streamvault.domain.model.Result.error("Backup file is corrupted (checksum mismatch)")
            }

            val importedSections = mutableListOf<String>()
            val skippedSections = mutableListOf<String>()

            // 2. Restore Preferences
            if (plan.importPreferences) {
                backupData.preferences?.let { prefs ->
                prefs["parentalControlLevel"]?.toIntOrNull()?.let {
                    preferencesRepository.setParentalControlLevel(it)
                }
                prefs["appLanguage"]?.takeIf { it.isNotBlank() }?.let { preferencesRepository.setAppLanguage(it) }
                prefs["guideDensity"]?.takeIf { it.isNotBlank() }?.let { preferencesRepository.setGuideDensity(it) }
                prefs["guideChannelMode"]?.takeIf { it.isNotBlank() }?.let { preferencesRepository.setGuideChannelMode(it) }
                prefs["guideFavoritesOnly"]?.toBooleanStrictOrNull()?.let { preferencesRepository.setGuideFavoritesOnly(it) }
                prefs["guideAnchorTime"]?.toLongOrNull()?.takeIf { it > 0L }?.let { preferencesRepository.setGuideAnchorTime(it) }
                prefs["promotedLiveGroupIds"]?.let { token ->
                    preferencesRepository.setPromotedLiveGroupIds(
                        token.split(",").mapNotNull { it.toLongOrNull() }.toSet()
                    )
                }
                    importedSections += "Preferences"
                } ?: run { skippedSections += "Preferences" }
            } else {
                skippedSections += "Preferences"
            }

            if (plan.importProviders) {
                backupData.providers?.let { providers ->
                providers.forEach { provider ->
                    val existing = providerDao.getByUrlAndUser(provider.serverUrl, provider.username)
                    if (existing != null && plan.conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                        return@forEach
                    }
                    val entity = provider.copy(
                        id = existing?.id ?: provider.id
                    ).toSecureEntityForBackup()
                    providerDao.insert(entity)
                }
                backupData.preferences
                    ?.get("lastActiveProviderId")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?.let { activeId ->
                        providerDao.deactivateAll()
                        providerDao.activate(activeId)
                    }
                    importedSections += "Providers"
                } ?: run { skippedSections += "Providers" }
            } else {
                skippedSections += "Providers"
            }

            // 3. Restore Virtual Groups
            if (plan.importSavedLibrary) {
                backupData.virtualGroups?.let { groups ->
                    val existingGroups = buildList {
                        addAll(virtualGroupDao.getByType("LIVE").first())
                        addAll(virtualGroupDao.getByType("MOVIE").first())
                        addAll(virtualGroupDao.getByType("SERIES").first())
                    }
                groups.forEach { group ->
                    val conflict = existingGroups.firstOrNull {
                        it.name.equals(group.name, ignoreCase = true) &&
                            it.contentType == group.contentType.name
                    }
                    if (conflict != null && plan.conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                        return@forEach
                    }
                    virtualGroupDao.insert(group.toEntity())
                }
                } ?: run { skippedSections += "Saved Library" }

            // 4. Restore Favorites
                backupData.favorites?.let { favs ->
                favs.forEach { fav ->
                    val existing = favoriteDao.get(
                        contentId = fav.contentId,
                        contentType = fav.contentType.name,
                        groupId = fav.groupId
                    )
                    if (existing != null && plan.conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                        return@forEach
                    }
                    favoriteDao.insert(fav.toEntity())
                }
                }
                importedSections += "Saved Library"
            } else {
                skippedSections += "Saved Library"
            }

            if (plan.importPlaybackHistory) {
                backupData.playbackHistory?.let { history ->
                if (plan.conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING) {
                    playbackHistoryDao.deleteAll()
                }
                history.forEach { item ->
                    if (plan.conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                        val existing = playbackHistoryDao.get(
                            contentId = item.contentId,
                            contentType = item.contentType.name,
                            providerId = item.providerId
                        )
                        if (existing != null) return@forEach
                    }
                    playbackHistoryDao.insertOrUpdate(item.toEntity())
                }
                    importedSections += "Playback History"
                } ?: run { skippedSections += "Playback History" }
            } else {
                skippedSections += "Playback History"
            }

            if (plan.importMultiViewPresets) {
                backupData.multiViewPresets?.let { presets ->
                preferencesRepository.setMultiViewPreset(0, presets["preset_1"].orEmpty())
                preferencesRepository.setMultiViewPreset(1, presets["preset_2"].orEmpty())
                preferencesRepository.setMultiViewPreset(2, presets["preset_3"].orEmpty())
                    importedSections += "Split Screen Presets"
                } ?: run { skippedSections += "Split Screen Presets" }
            } else {
                skippedSections += "Split Screen Presets"
            }

            com.streamvault.domain.model.Result.success(
                BackupImportResult(
                    importedSections = importedSections.distinct(),
                    skippedSections = skippedSections.distinct()
                )
            )
        } catch (e: Exception) {
            com.streamvault.domain.model.Result.error("Failed to import backup: ${e.message}", e)
        }
    }

    private fun verifyChecksum(backupData: BackupData): Boolean {
        val storedChecksum = backupData.checksum ?: return true // no checksum = legacy backup, skip verification
        val dataWithoutChecksum = backupData.copy(checksum = null)
        val json = gson.toJson(dataWithoutChecksum)
        val crc = CRC32()
        crc.update(json.toByteArray(Charsets.UTF_8))
        return crc.value.toString(16) == storedChecksum
    }

    private fun readBackupData(uriString: String): BackupData? {
        val uri = Uri.parse(uriString)
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            InputStreamReader(inputStream).use { reader ->
                gson.fromJson(reader, BackupData::class.java)
            }
        }
    }
}

private fun com.streamvault.domain.model.Provider.toSecureEntityForBackup() =
    copy(password = CredentialCrypto.encryptIfNeeded(password)).toEntity()
