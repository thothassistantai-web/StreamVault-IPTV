package com.streamvault.data.manager

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonWriter
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.VirtualGroupDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.preferences.ParentalPinBackupData
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.manager.BackupData
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupImportResult
import com.streamvault.domain.manager.BackupManager
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.manager.ProtectedCategoryBackup
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.manager.ScheduledRecordingBackup
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.CategoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.lang.reflect.Type
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val credentialCrypto: CredentialCrypto,
    private val providerDao: ProviderDao,
    private val favoriteDao: FavoriteDao,
    private val virtualGroupDao: VirtualGroupDao,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val movieDao: MovieDao,
    private val episodeDao: EpisodeDao,
    private val categoryRepository: CategoryRepository,
    private val recordingManager: RecordingManager,
    private val gson: Gson
) : BackupManager {

    override suspend fun exportConfig(uriString: String): com.streamvault.domain.model.Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            val parentalPinBackup = preferencesRepository.exportParentalPinBackup()
            val providerEntities = providerDao.getAll().first()
            
            // 1. Gather Data
            val prefs = mapOf(
                "parentalControlLevel" to preferencesRepository.parentalControlLevel.first().toString(),
                "parentalPinHash" to (parentalPinBackup?.hash ?: ""),
                "parentalPinSalt" to (parentalPinBackup?.saltBase64 ?: ""),
                "appLanguage" to preferencesRepository.appLanguage.first(),
                "liveTvCategoryFilters" to preferencesRepository.liveTvCategoryFilters.first().joinToString("\n"),
                "liveTvQuickFilterVisibility" to (preferencesRepository.liveTvQuickFilterVisibility.first() ?: "always"),
                "playerMediaSessionEnabled" to preferencesRepository.playerMediaSessionEnabled.first().toString(),
                "playerDecoderMode" to preferencesRepository.playerDecoderMode.first().name,
                "playerPlaybackSpeed" to preferencesRepository.playerPlaybackSpeed.first().toString(),
                "preferredAudioLanguage" to (preferencesRepository.preferredAudioLanguage.first() ?: "auto"),
                "playerSubtitleTextScale" to preferencesRepository.playerSubtitleTextScale.first().toString(),
                "playerSubtitleTextColor" to preferencesRepository.playerSubtitleTextColor.first().toString(),
                "playerSubtitleBackgroundColor" to preferencesRepository.playerSubtitleBackgroundColor.first().toString(),
                "playerWifiMaxVideoHeight" to (preferencesRepository.playerWifiMaxVideoHeight.first() ?: 0).toString(),
                "playerEthernetMaxVideoHeight" to (preferencesRepository.playerEthernetMaxVideoHeight.first() ?: 0).toString(),
                "guideDensity" to (preferencesRepository.guideDensity.first() ?: ""),
                "guideChannelMode" to (preferencesRepository.guideChannelMode.first() ?: ""),
                "guideDefaultCategoryId" to (preferencesRepository.guideDefaultCategoryId.first() ?: 0L).toString(),
                "guideFavoritesOnly" to preferencesRepository.guideFavoritesOnly.first().toString(),
                "guideAnchorTime" to (preferencesRepository.guideAnchorTime.first() ?: 0L).toString(),
                "lastActiveProviderId" to (preferencesRepository.lastActiveProviderId.first() ?: -1L).toString(),
                "promotedLiveGroupIds" to preferencesRepository.promotedLiveGroupIds.first().sorted().joinToString(",")
            )

            val providers = providerEntities.map { entity ->
                entity.toDomain().copy(
                    password = "",  // Strip credentials from backup export
                    username = entity.toDomain().username // Keep username for provider identification
                )
            }
            val providerIds = providerEntities.map { it.id }
            val providersById = providers.associateBy { it.id }

            // Gather all favorites across all types
            val liveFavs = providerIds.flatMap { providerId ->
                favoriteDao.getAllByType(providerId, "LIVE").first().map { it.toDomain() }
            }
            val movieFavs = providerIds.flatMap { providerId ->
                favoriteDao.getAllByType(providerId, "MOVIE").first().map { it.toDomain() }
            }
            val seriesFavs = providerIds.flatMap { providerId ->
                favoriteDao.getAllByType(providerId, "SERIES").first().map { it.toDomain() }
            }
            val allFavorites = liveFavs + movieFavs + seriesFavs

            // Gather all custom groups
            val liveGroups = providerIds.flatMap { providerId ->
                virtualGroupDao.getByType(providerId, "LIVE").first().map { it.toDomain() }
            }
            val movieGroups = providerIds.flatMap { providerId ->
                virtualGroupDao.getByType(providerId, "MOVIE").first().map { it.toDomain() }
            }
            val seriesGroups = providerIds.flatMap { providerId ->
                virtualGroupDao.getByType(providerId, "SERIES").first().map { it.toDomain() }
            }
            val allGroups = liveGroups + movieGroups + seriesGroups

            val playbackHistory = playbackHistoryDao.getAllSync().map { it.toDomain() }
            val multiViewPresets = mapOf(
                "preset_1" to preferencesRepository.getMultiViewPreset(0).first(),
                "preset_2" to preferencesRepository.getMultiViewPreset(1).first(),
                "preset_3" to preferencesRepository.getMultiViewPreset(2).first()
            )
            val protectedCategories = providers.flatMap { provider ->
                categoryRepository.getCategories(provider.id).first()
                    .filter { it.isUserProtected }
                    .map { category ->
                        ProtectedCategoryBackup(
                            providerServerUrl = provider.serverUrl,
                            providerUsername = provider.username,
                            providerStalkerMacAddress = provider.stalkerMacAddress.takeIf { it.isNotBlank() },
                            categoryId = category.id,
                            categoryName = category.name,
                            type = category.type
                        )
                    }
            }
            val scheduledRecordings = recordingManager.observeRecordingItems().first()
                .filter { it.status == RecordingStatus.SCHEDULED && it.scheduledEndMs > System.currentTimeMillis() }
                .mapNotNull { item ->
                    val provider = providersById[item.providerId] ?: return@mapNotNull null
                    ScheduledRecordingBackup(
                        providerServerUrl = provider.serverUrl,
                        providerUsername = provider.username,
                        providerStalkerMacAddress = provider.stalkerMacAddress.takeIf { it.isNotBlank() },
                        channelId = item.channelId,
                        channelName = item.channelName,
                        streamUrl = item.streamUrl,
                        scheduledStartMs = item.scheduledStartMs,
                        scheduledEndMs = item.scheduledEndMs,
                        programTitle = item.programTitle,
                        recurrence = item.recurrence
                    )
                }

            val backupData = BackupData(
                version = 5,
                preferences = prefs,
                providers = providers,
                favorites = allFavorites,
                virtualGroups = allGroups,
                playbackHistory = playbackHistory,
                multiViewPresets = multiViewPresets,
                protectedCategories = protectedCategories,
                scheduledRecordings = scheduledRecordings
            )

            // Compute checksum over the data without checksum field
            val backupWithChecksum = backupData.copy(checksum = buildSha256Checksum(backupData))

            // 2. Serialize and write to URI
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writeBackupDataJson(writer, backupWithChecksum)
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
            if (backupData.version > 5) {
                return@withContext Result.error("Unsupported backup version")
            }
            if (!verifyChecksum(backupData)) {
                return@withContext Result.error("Backup file is corrupted (checksum mismatch)")
            }

            val existingProviders = providerDao.getAll().first()
            val existingProviderIds = existingProviders.map { it.id }
            val existingGroups = buildList {
                existingProviderIds.forEach { providerId ->
                    addAll(virtualGroupDao.getByType(providerId, "LIVE").first())
                    addAll(virtualGroupDao.getByType(providerId, "MOVIE").first())
                    addAll(virtualGroupDao.getByType(providerId, "SERIES").first())
                }
            }
            val existingFavorites = buildList {
                existingProviderIds.forEach { providerId ->
                    addAll(favoriteDao.getAllByType(providerId, "LIVE").first())
                    addAll(favoriteDao.getAllByType(providerId, "MOVIE").first())
                    addAll(favoriteDao.getAllByType(providerId, "SERIES").first())
                }
            }
            val existingHistory = playbackHistoryDao.getAllSync()
            val existingProtectedCategories = existingProviders.flatMap { provider ->
                categoryRepository.getCategories(provider.id).first()
                    .filter { it.isUserProtected }
                    .map { category -> Triple(provider, category.name.lowercase(), category.type) }
            }
            val existingScheduledRecordings = recordingManager.observeRecordingItems().first()
                .filter { it.status == RecordingStatus.SCHEDULED }

            val providerConflicts = backupData.providers.orEmpty().count { incoming ->
                existingProviders.findMatchingProvider(
                    serverUrl = incoming.serverUrl,
                    username = incoming.username,
                    stalkerMacAddress = incoming.stalkerMacAddress
                ) != null
            }
            val groupConflicts = backupData.virtualGroups.orEmpty().count { incoming ->
                existingGroups.any { it.name.equals(incoming.name, ignoreCase = true) && it.contentType == incoming.contentType }
            }
            val favoriteConflicts = backupData.favorites.orEmpty().count { incoming ->
                existingFavorites.any {
                    it.contentId == incoming.contentId &&
                        it.contentType == incoming.contentType &&
                        it.groupId == incoming.groupId
                }
            }
            val historyConflicts = backupData.playbackHistory.orEmpty().count { incoming ->
                existingHistory.any {
                    it.contentId == incoming.contentId &&
                        it.contentType == incoming.contentType &&
                        it.providerId == incoming.providerId
                }
            }
            val protectedCategoryConflicts = backupData.protectedCategories.orEmpty().count { incoming ->
                val provider = existingProviders.findMatchingProvider(
                    serverUrl = incoming.providerServerUrl,
                    username = incoming.providerUsername,
                    stalkerMacAddress = incoming.providerStalkerMacAddress
                ) ?: return@count false
                existingProtectedCategories.any { (existingProvider, categoryName, type) ->
                    existingProvider.id == provider.id &&
                        categoryName == incoming.categoryName.lowercase() &&
                        type == incoming.type
                }
            }
            val recordingConflicts = backupData.scheduledRecordings.orEmpty().count { incoming ->
                val provider = existingProviders.findMatchingProvider(
                    serverUrl = incoming.providerServerUrl,
                    username = incoming.providerUsername,
                    stalkerMacAddress = incoming.providerStalkerMacAddress
                ) ?: return@count false
                existingScheduledRecordings.any {
                    it.providerId == provider.id &&
                        it.scheduledStartMs == incoming.scheduledStartMs &&
                        (it.channelId == incoming.channelId || it.streamUrl == incoming.streamUrl)
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
                    protectedCategoryCount = backupData.protectedCategories.orEmpty().size,
                    scheduledRecordingCount = backupData.scheduledRecordings.orEmpty().size,
                    providerConflicts = providerConflicts,
                    favoriteConflicts = favoriteConflicts,
                    groupConflicts = groupConflicts,
                    historyConflicts = historyConflicts,
                    protectedCategoryConflicts = protectedCategoryConflicts,
                    recordingConflicts = recordingConflicts
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

            if (backupData.version > 5) {
                return@withContext com.streamvault.domain.model.Result.error("Unsupported backup version")
            }
            if (!verifyChecksum(backupData)) {
                return@withContext com.streamvault.domain.model.Result.error("Backup file is corrupted (checksum mismatch)")
            }

            var storedProviders = providerDao.getAllSync()

            val importedSections = mutableListOf<String>()
            val skippedSections = mutableListOf<String>()

            // 2. Restore Preferences
            if (plan.importPreferences) {
                backupData.preferences?.let { prefs ->
                prefs["parentalControlLevel"]?.toIntOrNull()?.let {
                    preferencesRepository.setParentalControlLevel(it)
                }
                preferencesRepository.restoreParentalPinBackup(
                    ParentalPinBackupData(
                        hash = prefs["parentalPinHash"].orEmpty(),
                        saltBase64 = prefs["parentalPinSalt"].orEmpty()
                    ).takeIf { it.hash.isNotBlank() && it.saltBase64.isNotBlank() }
                )
                prefs["appLanguage"]?.takeIf { it.isNotBlank() }?.let { preferencesRepository.setAppLanguage(it) }
                prefs["liveTvCategoryFilters"]?.let { preferencesRepository.setLiveTvCategoryFilters(it.split('\n')) }
                prefs["liveTvQuickFilterVisibility"]?.takeIf { it.isNotBlank() }
                    ?.let { preferencesRepository.setLiveTvQuickFilterVisibility(it) }
                prefs["playerMediaSessionEnabled"]?.toBooleanStrictOrNull()
                    ?.let { preferencesRepository.setPlayerMediaSessionEnabled(it) }
                prefs["playerDecoderMode"]?.takeIf { it.isNotBlank() }?.let { savedMode ->
                    val decoderMode = com.streamvault.domain.model.DecoderMode.entries
                        .firstOrNull { entry -> entry.name == savedMode }
                    if (decoderMode != null) {
                        preferencesRepository.setPlayerDecoderMode(decoderMode)
                    }
                }
                prefs["playerPlaybackSpeed"]?.toFloatOrNull()?.let { preferencesRepository.setPlayerPlaybackSpeed(it) }
                preferencesRepository.setPreferredAudioLanguage(prefs["preferredAudioLanguage"])
                prefs["playerSubtitleTextScale"]?.toFloatOrNull()?.let { preferencesRepository.setPlayerSubtitleTextScale(it) }
                prefs["playerSubtitleTextColor"]?.toIntOrNull()?.let { preferencesRepository.setPlayerSubtitleTextColor(it) }
                prefs["playerSubtitleBackgroundColor"]?.toIntOrNull()?.let { preferencesRepository.setPlayerSubtitleBackgroundColor(it) }
                preferencesRepository.setPlayerWifiMaxVideoHeight(prefs["playerWifiMaxVideoHeight"]?.toIntOrNull()?.takeIf { it > 0 })
                preferencesRepository.setPlayerEthernetMaxVideoHeight(prefs["playerEthernetMaxVideoHeight"]?.toIntOrNull()?.takeIf { it > 0 })
                prefs["guideDensity"]?.takeIf { it.isNotBlank() }?.let { preferencesRepository.setGuideDensity(it) }
                prefs["guideChannelMode"]?.takeIf { it.isNotBlank() }?.let { preferencesRepository.setGuideChannelMode(it) }
                prefs["guideDefaultCategoryId"]?.toLongOrNull()?.takeIf { it != 0L }?.let {
                    preferencesRepository.setGuideDefaultCategoryId(it)
                }
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
                    val existing = storedProviders.findMatchingProvider(
                        serverUrl = provider.serverUrl,
                        username = provider.username,
                        stalkerMacAddress = provider.stalkerMacAddress
                    )
                    if (existing != null && plan.conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                        return@forEach
                    }
                    val entity = provider.copy(
                        id = existing?.id ?: 0L  // 0 = let Room auto-assign PK; avoids ID collision on import
                    ).toSecureEntityForBackup(credentialCrypto)
                    providerDao.insert(entity)
                }
                storedProviders = providerDao.getAllSync()
                val providerIdMap = backupData.providers.orEmpty().mapNotNull { provider ->
                    storedProviders.findMatchingProvider(
                        serverUrl = provider.serverUrl,
                        username = provider.username,
                        stalkerMacAddress = provider.stalkerMacAddress
                    )?.let { stored ->
                        provider.id to stored.id
                    }
                }.toMap()
                backupData.preferences
                    ?.get("lastActiveProviderId")
                    ?.toLongOrNull()
                    ?.let { backupProviderId ->
                        providerIdMap[backupProviderId]
                    }
                    ?.let { resolvedProviderId ->
                        providerDao.setActive(resolvedProviderId)
                    }
                    importedSections += "Providers"
                } ?: run { skippedSections += "Providers" }
            } else {
                skippedSections += "Providers"
            }

            // 3. Restore Virtual Groups
            if (plan.importSavedLibrary) {
                val providerIdMap = backupData.providers.orEmpty().mapNotNull { provider ->
                    storedProviders.findMatchingProvider(
                        serverUrl = provider.serverUrl,
                        username = provider.username,
                        stalkerMacAddress = provider.stalkerMacAddress
                    )?.let { stored ->
                        provider.id to stored.id
                    }
                }.toMap()
                val groupIdMap = mutableMapOf<Long, Long>()
                backupData.virtualGroups?.let { groups ->
                    val existingGroups = buildList {
                        providerIdMap.values.distinct().forEach { providerId ->
                            addAll(virtualGroupDao.getByType(providerId, "LIVE").first())
                            addAll(virtualGroupDao.getByType(providerId, "MOVIE").first())
                            addAll(virtualGroupDao.getByType(providerId, "SERIES").first())
                        }
                    }
                groups.forEach { group ->
                    val resolvedProviderId = providerIdMap[group.providerId] ?: return@forEach
                    val conflict = existingGroups.firstOrNull {
                        it.providerId == resolvedProviderId &&
                        it.name.equals(group.name, ignoreCase = true) &&
                            it.contentType == group.contentType
                    }
                    if (conflict != null && plan.conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                        groupIdMap[group.id] = conflict.id
                        return@forEach
                    }
                    val insertedId = virtualGroupDao.insert(
                        group.copy(id = 0L, providerId = resolvedProviderId).toEntity()
                    )
                    groupIdMap[group.id] = insertedId
                }
                } ?: run { skippedSections += "Saved Library" }

            // 4. Restore Favorites
                backupData.favorites?.let { favs ->
                favs.forEach { fav ->
                    val resolvedProviderId = providerIdMap[fav.providerId] ?: return@forEach
                    val resolvedGroupId = fav.groupId?.let { groupIdMap[it] }
                    val existing = favoriteDao.get(
                        providerId = resolvedProviderId,
                        contentId = fav.contentId,
                        contentType = fav.contentType.name,
                        groupId = resolvedGroupId
                    )
                    if (existing != null && plan.conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                        return@forEach
                    }
                    favoriteDao.insert(
                        fav.copy(
                            id = 0L,
                            providerId = resolvedProviderId,
                            groupId = resolvedGroupId
                        ).toEntity()
                    )
                }
                }
                val categoriesByProviderId = mutableMapOf<Long, List<com.streamvault.domain.model.Category>>()
                backupData.protectedCategories?.forEach { protectedCategory ->
                    val provider = storedProviders.findMatchingProvider(
                        serverUrl = protectedCategory.providerServerUrl,
                        username = protectedCategory.providerUsername,
                        stalkerMacAddress = protectedCategory.providerStalkerMacAddress
                    ) ?: return@forEach
                    val categories = categoriesByProviderId.getOrPut(provider.id) {
                        categoryRepository.getCategories(provider.id).first()
                    }
                    val resolvedCategory = categories.firstOrNull {
                        it.type == protectedCategory.type && it.id == protectedCategory.categoryId
                    } ?: categories.firstOrNull {
                        it.type == protectedCategory.type &&
                            it.name.equals(protectedCategory.categoryName, ignoreCase = true)
                    }
                    if (resolvedCategory == null) {
                        return@forEach
                    }
                    if (plan.conflictStrategy == BackupConflictStrategy.KEEP_EXISTING && resolvedCategory.isUserProtected) {
                        return@forEach
                    }
                    categoryRepository.setCategoryProtection(
                        providerId = provider.id,
                        categoryId = resolvedCategory.id,
                        type = resolvedCategory.type,
                        isProtected = true
                    )
                }
                importedSections += "Saved Library"
            } else {
                skippedSections += "Saved Library"
            }

            if (plan.importPlaybackHistory) {
                backupData.playbackHistory?.let { history ->
                val providerIdMap = backupData.providers.orEmpty().mapNotNull { provider ->
                    storedProviders.findMatchingProvider(
                        serverUrl = provider.serverUrl,
                        username = provider.username,
                        stalkerMacAddress = provider.stalkerMacAddress
                    )?.let { stored ->
                        provider.id to stored.id
                    }
                }.toMap()
                if (plan.conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING) {
                    playbackHistoryDao.deleteAll()
                }
                history.forEach { item ->
                    val resolvedProviderId = providerIdMap[item.providerId] ?: return@forEach
                    if (plan.conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                        val existing = playbackHistoryDao.get(
                            contentId = item.contentId,
                            contentType = item.contentType.name,
                            providerId = resolvedProviderId
                        )
                        if (existing != null) return@forEach
                    }
                    playbackHistoryDao.insertOrUpdate(item.copy(providerId = resolvedProviderId).toEntity())
                }
                    movieDao.syncAllWatchProgressFromHistory()
                    episodeDao.syncAllWatchProgressFromHistory()
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

            if (plan.importRecordingSchedules) {
                backupData.scheduledRecordings?.let { recordings ->
                    val existingSchedules = recordingManager.observeRecordingItems().first()
                        .filter { it.status == RecordingStatus.SCHEDULED }
                        .toMutableList()
                    recordings.forEach { scheduled ->
                        if (scheduled.scheduledEndMs <= System.currentTimeMillis()) {
                            return@forEach
                        }
                        val provider = storedProviders.findMatchingProvider(
                            serverUrl = scheduled.providerServerUrl,
                            username = scheduled.providerUsername,
                            stalkerMacAddress = scheduled.providerStalkerMacAddress
                        ) ?: return@forEach
                        val conflict = existingSchedules.firstOrNull {
                            it.providerId == provider.id &&
                                it.scheduledStartMs == scheduled.scheduledStartMs &&
                                (it.channelId == scheduled.channelId || it.streamUrl == scheduled.streamUrl)
                        }
                        if (conflict != null) {
                            if (plan.conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                                return@forEach
                            }
                            recordingManager.cancelRecording(conflict.id)
                            existingSchedules.remove(conflict)
                        }
                        val result = recordingManager.scheduleRecording(
                            RecordingRequest(
                                providerId = provider.id,
                                channelId = scheduled.channelId,
                                channelName = scheduled.channelName,
                                streamUrl = scheduled.streamUrl,
                                scheduledStartMs = scheduled.scheduledStartMs,
                                scheduledEndMs = scheduled.scheduledEndMs,
                                programTitle = scheduled.programTitle,
                                recurrence = scheduled.recurrence
                            )
                        )
                        if (result is Result.Success) {
                            existingSchedules += result.data
                        }
                    }
                    importedSections += "Recording Schedules"
                } ?: run { skippedSections += "Recording Schedules" }
            } else {
                skippedSections += "Recording Schedules"
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

        return if (storedChecksum.startsWith(SHA256_PREFIX)) {
            buildSha256Checksum(dataWithoutChecksum) == storedChecksum
        } else {
            verifyLegacyCrc32Checksum(dataWithoutChecksum, storedChecksum)
        }
    }

    private fun buildSha256Checksum(backupData: BackupData): String {
        val digest = MessageDigest.getInstance("SHA-256")
        OutputStreamWriter(MessageDigestOutputStream(digest), Charsets.UTF_8).use { writer ->
            writeBackupDataJson(writer, backupData.copy(checksum = null))
        }
        return SHA256_PREFIX + digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun verifyLegacyCrc32Checksum(backupData: BackupData, checksum: String): Boolean {
        val crc = CRC32()
        OutputStreamWriter(Crc32OutputStream(crc), Charsets.UTF_8).use { writer ->
            writeBackupDataJson(writer, backupData.copy(checksum = null))
        }
        return crc.value.toString(16) == checksum
    }

    private fun writeBackupDataJson(writer: Writer, backupData: BackupData) {
        JsonWriter(writer).use { jsonWriter ->
            jsonWriter.beginObject()
            jsonWriter.name("version").value(backupData.version.toLong())
            backupData.checksum?.let { checksum ->
                jsonWriter.name("checksum").value(checksum)
            }
            writeNamedJsonField(jsonWriter, "preferences", backupData.preferences, MAP_STRING_STRING_TYPE)
            writeNamedJsonField(jsonWriter, "providers", backupData.providers, PROVIDER_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "favorites", backupData.favorites, FAVORITE_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "virtualGroups", backupData.virtualGroups, VIRTUAL_GROUP_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "playbackHistory", backupData.playbackHistory, PLAYBACK_HISTORY_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "multiViewPresets", backupData.multiViewPresets, MULTIVIEW_PRESETS_TYPE)
            writeNamedJsonField(jsonWriter, "protectedCategories", backupData.protectedCategories, PROTECTED_CATEGORY_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "scheduledRecordings", backupData.scheduledRecordings, SCHEDULED_RECORDING_LIST_TYPE)
            jsonWriter.endObject()
        }
    }

    private fun <T> writeNamedJsonField(jsonWriter: JsonWriter, name: String, value: T?, type: Type) {
        if (value == null) return
        jsonWriter.name(name)
        gson.toJson(value, type, jsonWriter)
    }

    private class MessageDigestOutputStream(
        private val digest: MessageDigest
    ) : OutputStream() {
        override fun write(b: Int) {
            digest.update(b.toByte())
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            digest.update(b, off, len)
        }
    }

    private class Crc32OutputStream(
        private val crc32: CRC32
    ) : OutputStream() {
        override fun write(b: Int) {
            crc32.update(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            crc32.update(b, off, len)
        }
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

private fun com.streamvault.domain.model.Provider.toSecureEntityForBackup(
    credentialCrypto: CredentialCrypto
) = copy(password = credentialCrypto.encryptIfNeeded(password)).toEntity()

private fun Iterable<ProviderEntity>.findMatchingProvider(
    serverUrl: String,
    username: String,
    stalkerMacAddress: String?
): ProviderEntity? {
    val candidates = filter {
        it.serverUrl == serverUrl &&
            it.username == username
    }
    val normalizedMacAddress = stalkerMacAddress
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.uppercase()

    if (normalizedMacAddress != null) {
        return candidates.firstOrNull { it.stalkerMacAddress.equals(normalizedMacAddress, ignoreCase = true) }
    }

    return candidates.singleOrNull { it.stalkerMacAddress.isBlank() }
        ?: candidates.singleOrNull()
}

private const val SHA256_PREFIX = "sha256:"
private val MAP_STRING_STRING_TYPE: Type = object : TypeToken<Map<String, String>>() {}.type
private val PROVIDER_LIST_TYPE: Type = object : TypeToken<List<com.streamvault.domain.model.Provider>>() {}.type
private val FAVORITE_LIST_TYPE: Type = object : TypeToken<List<com.streamvault.domain.model.Favorite>>() {}.type
private val VIRTUAL_GROUP_LIST_TYPE: Type = object : TypeToken<List<com.streamvault.domain.model.VirtualGroup>>() {}.type
private val PLAYBACK_HISTORY_LIST_TYPE: Type = object : TypeToken<List<com.streamvault.domain.model.PlaybackHistory>>() {}.type
private val MULTIVIEW_PRESETS_TYPE: Type = object : TypeToken<Map<String, List<Long>>>() {}.type
private val PROTECTED_CATEGORY_LIST_TYPE: Type = object : TypeToken<List<ProtectedCategoryBackup>>() {}.type
private val SCHEDULED_RECORDING_LIST_TYPE: Type = object : TypeToken<List<ScheduledRecordingBackup>>() {}.type
