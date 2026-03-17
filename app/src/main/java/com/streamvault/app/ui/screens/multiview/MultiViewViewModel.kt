package com.streamvault.app.ui.screens.multiview

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.di.AuxiliaryPlayerEngine
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.player.PlayerEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

@HiltViewModel
class MultiViewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val multiViewManager: MultiViewManager,
    @AuxiliaryPlayerEngine
    private val playerEngineProvider: Provider<PlayerEngine>,
    private val preferencesRepository: PreferencesRepository,
    private val channelRepository: ChannelRepository,
    private val favoriteRepository: FavoriteRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver
) : ViewModel() {

    private val _uiState = MutableStateFlow(MultiViewUiState())
    val uiState: StateFlow<MultiViewUiState> = _uiState.asStateFlow()
    
    private var borderHideJob: kotlinx.coroutines.Job? = null
    private var telemetryJob: kotlinx.coroutines.Job? = null
    private var pinnedAudioSlotIndex: Int? = null
    private val detectedTier: DevicePerformanceTier by lazy { detectDeviceTier() }
    private val powerManager: PowerManager? by lazy { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    private var thermalStatus: MultiViewThermalStatus = readThermalStatus()
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var runtimeActiveSlotLimit: Int = MultiViewManager.MAX_SLOTS
    private var sustainedStressSamples: Int = 0
    private var stableSamples: Int = 0
    private var lastPolicyAdjustmentAt: Long = 0L
    private val lastDroppedFramesBySlot = mutableMapOf<Int, Int>()
    private val slotStartupJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()
    private var slotInitVersion: Long = 0L

    /** Flow of the current 4 slot channels from the manager */
    val slotsFlow = multiViewManager.slots

    private val playerEngines = mutableMapOf<Int, PlayerEngine>()

    init {
        viewModelScope.launch {
            combine(
                preferencesRepository.getMultiViewPreset(0),
                preferencesRepository.getMultiViewPreset(1),
                preferencesRepository.getMultiViewPreset(2),
                preferencesRepository.multiViewPerformanceMode
            ) { preset1, preset2, preset3, performanceMode ->
                listOf(preset1, preset2, preset3) to performanceMode
            }.collect { presets ->
                val presetList = presets.first
                val mode = presets.second
                    ?.let { saved -> MultiViewPerformanceMode.entries.firstOrNull { it.name == saved } }
                    ?: MultiViewPerformanceMode.AUTO
                val policy = resolvePolicy(mode, detectedTier)
                runtimeActiveSlotLimit = runtimeActiveSlotLimit
                    .coerceAtLeast(1)
                    .coerceAtMost(policy.maxActiveSlots)
                _uiState.value = _uiState.value.copy(
                    presets = presetList.mapIndexed { index, ids ->
                        MultiViewPresetUiModel(
                            index = index,
                            label = "Preset ${index + 1}",
                            isPopulated = ids.isNotEmpty(),
                            channelCount = ids.size
                        )
                    },
                    pinnedAudioSlotIndex = pinnedAudioSlotIndex,
                    performancePolicy = policy,
                    telemetry = _uiState.value.telemetry.copy(
                        activeSlotLimit = runtimeActiveSlotLimit,
                        thermalStatus = thermalStatus,
                        recommendation = buildTelemetryRecommendation(
                            activeSlotLimit = runtimeActiveSlotLimit,
                            thermalStatus = thermalStatus,
                            isLowMemory = false,
                            sustainedLoadScore = 0,
                            policy = policy
                        )
                    )
                )
            }
        }

        registerThermalListener()
        observeReplacementCandidates()
    }

    /** Called when MultiViewScreen is opened — spins up player engines for occupied slots */
    fun initSlots() {
        val initVersion = ++slotInitVersion
        telemetryJob?.cancel()
        cancelSlotStartupJobs()
        playerEngines.values.forEach { it.release() }
        playerEngines.clear()
        lastDroppedFramesBySlot.clear()
        val channels = multiViewManager.slots.value
        val maxActiveSlots = runtimeActiveSlotLimit
            .coerceAtLeast(1)
            .coerceAtMost(_uiState.value.performancePolicy.maxActiveSlots)
        val slots = channels.mapIndexed { index, channel ->
            if (channel != null) {
                MultiViewSlot(
                    index = index,
                    channel = channel,
                    streamUrl = channel.streamUrl,
                    title = channel.name,
                    isLoading = index < maxActiveSlots,
                    isAudioPinned = pinnedAudioSlotIndex == index,
                    performanceBlockedReason = if (index >= maxActiveSlots) {
                        "Held back by ${_uiState.value.performancePolicy.mode.name.lowercase()} policy on this device tier."
                    } else {
                        null
                    }
                )
            } else {
                MultiViewSlot(index = index)
            }
        }
        _uiState.value = _uiState.value.copy(
            slots = slots,
            pinnedAudioSlotIndex = pinnedAudioSlotIndex,
            telemetry = _uiState.value.telemetry.copy(
                activeSlotLimit = maxActiveSlots,
                activeSlots = slots.count { !it.isEmpty && it.performanceBlockedReason == null },
                standbySlots = slots.count { !it.isEmpty && it.performanceBlockedReason != null },
                throttledReason = _uiState.value.telemetry.throttledReason,
                thermalStatus = thermalStatus,
                recommendation = buildTelemetryRecommendation(
                    activeSlotLimit = maxActiveSlots,
                    thermalStatus = thermalStatus,
                    isLowMemory = false,
                    sustainedLoadScore = _uiState.value.telemetry.sustainedLoadScore,
                    policy = _uiState.value.performancePolicy
                )
            )
        )

        // Start all occupied engines
        slots.forEachIndexed { index, slot ->
            if (!slot.isEmpty) {
                if (index >= maxActiveSlots) {
                    updateSlot(index) { it.copy(isLoading = false, playerEngine = null) }
                    return@forEachIndexed
                }
                val startupJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(index * _uiState.value.performancePolicy.startupDelayMs)
                    if (initVersion != slotInitVersion) return@launch
                    try {
                        val engine = playerEngineProvider.get()
                        // Cap each multi-view slot to 720p so slots don't compete for 4K bandwidth
                        (engine as? com.streamvault.player.Media3PlayerEngine)
                            ?.let { it.constrainResolutionForMultiView = true }
                        if (initVersion != slotInitVersion) {
                            engine.release()
                            return@launch
                        }
                        playerEngines[index] = engine

                        val resolvedUrl = xtreamStreamUrlResolver.resolve(
                            url = slot.streamUrl,
                            fallbackProviderId = slot.channel?.providerId,
                            fallbackStreamId = slot.channel?.streamId,
                            fallbackContentType = ContentType.LIVE
                        ) ?: throw IllegalStateException("No playable URL for ${slot.title}")

                        engine.prepare(
                            com.streamvault.domain.model.StreamInfo(url = resolvedUrl)
                        )
                        engine.play()
                        
                        // Copy the engine into the slot UI state as well
                        updateSlot(index) { it.copy(isLoading = false, playerEngine = engine) }
                        
                        // Re-apply audio to make sure the focused one is audible
                        applyFocusAudio(_uiState.value.focusedSlotIndex)
                    } catch (e: Exception) {
                        if (initVersion == slotInitVersion) {
                            updateSlot(index) { it.copy(isLoading = false, hasError = true) }
                        }
                    } finally {
                        slotStartupJobs.remove(index)
                    }
                }
                slotStartupJobs[index] = startupJob
            }
        }

        applyFocusAudio(0)
        showSelectionBorderTemporarily()
        startTelemetryMonitoring()
    }

    fun setFocus(slotIndex: Int) {
        if (_uiState.value.focusedSlotIndex != slotIndex) {
            _uiState.value = _uiState.value.copy(focusedSlotIndex = slotIndex)
            applyFocusAudio(slotIndex)
            showSelectionBorderTemporarily()
        }
    }

    private fun showSelectionBorderTemporarily() {
        borderHideJob?.cancel()
        _uiState.value = _uiState.value.copy(showSelectionBorder = true)
        borderHideJob = viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _uiState.value = _uiState.value.copy(showSelectionBorder = false)
        }
    }

    private fun applyFocusAudio(focusedIndex: Int) {
        playerEngines.forEach { (index, engine) ->
            val audibleIndex = pinnedAudioSlotIndex ?: focusedIndex
            if (index == audibleIndex) engine.setVolume(1f) else engine.setVolume(0f)
        }
    }

    /** Assign a channel to a specific slot (0–3). Called from dialog or AddToGroupDialog. */
    fun assignChannelToSlot(slotIndex: Int, channel: Channel) {
        multiViewManager.setChannel(slotIndex, channel)
    }

    /** Clear a specific slot */
    fun clearSlot(slotIndex: Int) {
        slotStartupJobs.remove(slotIndex)?.cancel()
        multiViewManager.clearSlot(slotIndex)
        if (pinnedAudioSlotIndex == slotIndex) {
            pinnedAudioSlotIndex = null
        }
        _uiState.value = _uiState.value.copy(pinnedAudioSlotIndex = pinnedAudioSlotIndex)
    }

    /** Clear all slots */
    fun clearAll() {
        cancelSlotStartupJobs()
        multiViewManager.clearAll()
        pinnedAudioSlotIndex = null
        _uiState.value = _uiState.value.copy(pinnedAudioSlotIndex = null)
    }

    fun replaceFocusedSlot(channel: Channel) {
        val slotIndex = _uiState.value.focusedSlotIndex
        multiViewManager.setChannel(slotIndex, channel)
        initSlots()
    }

    fun removeFocusedSlot() {
        clearSlot(_uiState.value.focusedSlotIndex)
        initSlots()
    }

    fun pinAudioToFocusedSlot() {
        pinnedAudioSlotIndex = _uiState.value.focusedSlotIndex
        _uiState.value = _uiState.value.copy(
            pinnedAudioSlotIndex = pinnedAudioSlotIndex,
            slots = _uiState.value.slots.mapIndexed { index, slot -> slot.copy(isAudioPinned = index == pinnedAudioSlotIndex) }
        )
        applyFocusAudio(_uiState.value.focusedSlotIndex)
    }

    fun clearPinnedAudio() {
        pinnedAudioSlotIndex = null
        _uiState.value = _uiState.value.copy(
            pinnedAudioSlotIndex = null,
            slots = _uiState.value.slots.map { it.copy(isAudioPinned = false) }
        )
        applyFocusAudio(_uiState.value.focusedSlotIndex)
    }

    fun saveCurrentAsPreset(presetIndex: Int) {
        viewModelScope.launch {
            val channelIds = multiViewManager.slots.value.mapNotNull { it?.id }
            preferencesRepository.setMultiViewPreset(presetIndex, channelIds)
        }
    }

    fun setPerformanceMode(mode: MultiViewPerformanceMode) {
        viewModelScope.launch {
            preferencesRepository.setMultiViewPerformanceMode(mode.name)
            initSlots()
        }
    }

    fun loadPreset(presetIndex: Int) {
        viewModelScope.launch {
            val channelIds = preferencesRepository.getMultiViewPreset(presetIndex).first()
            if (channelIds.isEmpty()) return@launch
            val channels = channelRepository.getChannelsByIds(channelIds).first()
                .associateBy { it.id }
            multiViewManager.clearAll()
            channelIds.forEachIndexed { index, channelId ->
                channels[channelId]?.let { channel ->
                    multiViewManager.setChannel(index, channel)
                }
            }
            initSlots()
        }
    }

    fun isQueued(channelId: Long): Boolean = multiViewManager.isQueued(channelId)

    private fun updateSlot(index: Int, transform: (MultiViewSlot) -> MultiViewSlot) {
        val updated = _uiState.value.slots.toMutableList()
        if (index < updated.size) {
            updated[index] = transform(updated[index])
            _uiState.value = _uiState.value.copy(slots = updated)
        }
    }

    private fun observeReplacementCandidates() {
        viewModelScope.launch {
            preferencesRepository.lastActiveProviderId.collect { providerId ->
                if (providerId == null || providerId <= 0L) {
                    _uiState.value = _uiState.value.copy(replacementCandidates = emptyList())
                    return@collect
                }
                combine(
                    favoriteRepository.getFavorites(com.streamvault.domain.model.ContentType.LIVE),
                    playbackHistoryRepository.getRecentlyWatchedByProvider(providerId, limit = 12)
                ) { favorites, history ->
                    val favoriteIds = favorites.map { it.contentId }
                    val historyIds = history.map { it.contentId }
                    (favoriteIds + historyIds)
                        .distinct()
                        .take(16)
                }.collect { candidateIds ->
                    if (candidateIds.isEmpty()) {
                        _uiState.value = _uiState.value.copy(replacementCandidates = emptyList())
                    } else {
                        val channels = channelRepository.getChannelsByIds(candidateIds).first()
                        val currentSlotIds = multiViewManager.slots.value.mapNotNull { it?.id }.toSet()
                        _uiState.value = _uiState.value.copy(
                            replacementCandidates = channels.filterNot { it.id in currentSlotIds }
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        telemetryJob?.cancel()
        borderHideJob?.cancel()
        cancelSlotStartupJobs()
        unregisterThermalListener()
        playerEngines.values.forEach { it.release() }
        playerEngines.clear()
    }

    private fun cancelSlotStartupJobs() {
        slotStartupJobs.values.forEach { job -> job.cancel() }
        slotStartupJobs.clear()
    }

    private fun detectDeviceTier(): DevicePerformanceTier {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryClass = activityManager?.memoryClass ?: 256
        val isLowRam = activityManager?.isLowRamDevice ?: false
        return when {
            isLowRam || memoryClass <= 192 -> DevicePerformanceTier.LOW
            memoryClass <= 320 -> DevicePerformanceTier.MID
            else -> DevicePerformanceTier.HIGH
        }
    }

    private fun resolvePolicy(
        mode: MultiViewPerformanceMode,
        tier: DevicePerformanceTier
    ): MultiViewPerformancePolicyUiModel {
        val (maxSlots, startupDelayMs) = when (mode) {
            MultiViewPerformanceMode.AUTO -> when (tier) {
                DevicePerformanceTier.LOW -> 2 to 550L
                DevicePerformanceTier.MID -> 3 to 350L
                DevicePerformanceTier.HIGH -> 4 to 220L
            }
            MultiViewPerformanceMode.CONSERVATIVE -> when (tier) {
                DevicePerformanceTier.LOW -> 1 to 700L
                DevicePerformanceTier.MID -> 2 to 500L
                DevicePerformanceTier.HIGH -> 3 to 320L
            }
            MultiViewPerformanceMode.BALANCED -> when (tier) {
                DevicePerformanceTier.LOW -> 2 to 550L
                DevicePerformanceTier.MID -> 3 to 350L
                DevicePerformanceTier.HIGH -> 4 to 240L
            }
            MultiViewPerformanceMode.MAXIMUM -> when (tier) {
                DevicePerformanceTier.LOW -> 2 to 450L
                DevicePerformanceTier.MID -> 4 to 260L
                DevicePerformanceTier.HIGH -> 4 to 160L
            }
        }
        return MultiViewPerformancePolicyUiModel(
            tier = tier,
            mode = mode,
            maxActiveSlots = maxSlots,
            startupDelayMs = startupDelayMs,
            summary = "Runs up to $maxSlots active slot(s) on ${tier.name.lowercase()} tier with ${startupDelayMs}ms startup staggering."
        )
    }

    private fun startTelemetryMonitoring() {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch {
            while (isActive) {
                evaluateTelemetry()
                kotlinx.coroutines.delay(2_500)
            }
        }
    }

    private fun evaluateTelemetry() {
        val policy = _uiState.value.performancePolicy
        val engines = playerEngines.toMap()
        val lowMemory = isLowMemoryDeviceState()
        var bufferingSlots = 0
        var errorSlots = 0
        var totalDroppedFrames = 0
        var droppedFramesDelta = 0

        engines.forEach { (index, engine) ->
            when (engine.playbackState.value) {
                com.streamvault.player.PlaybackState.BUFFERING -> bufferingSlots += 1
                com.streamvault.player.PlaybackState.ERROR -> errorSlots += 1
                else -> Unit
            }

            val stats = engine.playerStats.value
            totalDroppedFrames += stats.droppedFrames
            val previousDropped = lastDroppedFramesBySlot[index] ?: stats.droppedFrames
            droppedFramesDelta += (stats.droppedFrames - previousDropped).coerceAtLeast(0)
            lastDroppedFramesBySlot[index] = stats.droppedFrames
        }

        val thermalPenalty = when (thermalStatus) {
            MultiViewThermalStatus.MODERATE -> 2
            MultiViewThermalStatus.SEVERE -> 4
            MultiViewThermalStatus.CRITICAL -> 6
            else -> 0
        }
        val loadScore = (bufferingSlots * 3) +
            (errorSlots * 4) +
            when {
                droppedFramesDelta >= 24 -> 3
                droppedFramesDelta >= 10 -> 2
                droppedFramesDelta > 0 -> 1
                else -> 0
            } +
            if (lowMemory) 2 else 0 +
            thermalPenalty

        val underStress = thermalStatus == MultiViewThermalStatus.SEVERE ||
            thermalStatus == MultiViewThermalStatus.CRITICAL ||
            loadScore >= 8 ||
            (bufferingSlots >= 2 && droppedFramesDelta >= 10) ||
            errorSlots >= 2
        val stable = loadScore <= 2 &&
            !lowMemory &&
            (thermalStatus == MultiViewThermalStatus.NORMAL ||
                thermalStatus == MultiViewThermalStatus.LIGHT ||
                thermalStatus == MultiViewThermalStatus.UNKNOWN)

        when {
            underStress -> {
                sustainedStressSamples += 1
                stableSamples = 0
            }
            stable -> {
                stableSamples += 1
                sustainedStressSamples = 0
            }
            else -> {
                sustainedStressSamples = 0
                stableSamples = 0
            }
        }

        val activeSlots = _uiState.value.slots.count { !it.isEmpty && it.performanceBlockedReason == null }
        val standbySlots = _uiState.value.slots.count { !it.isEmpty && it.performanceBlockedReason != null }
        var throttledReason = _uiState.value.telemetry.throttledReason

        if (shouldThrottleDown()) {
            if (runtimeActiveSlotLimit > 1) {
                runtimeActiveSlotLimit -= 1
                throttledReason = if (thermalStatus == MultiViewThermalStatus.SEVERE || thermalStatus == MultiViewThermalStatus.CRITICAL) {
                    "Thermal pressure forced Split Screen to hold extra slots in standby."
                } else {
                    "Sustained load forced Split Screen to reduce active decoders on this device."
                }
                lastPolicyAdjustmentAt = System.currentTimeMillis()
                _uiState.value = _uiState.value.copy(
                    telemetry = _uiState.value.telemetry.copy(throttledReason = throttledReason)
                )
                initSlots()
                return
            }
        } else if (shouldRecover(policy)) {
            runtimeActiveSlotLimit = (runtimeActiveSlotLimit + 1).coerceAtMost(policy.maxActiveSlots)
            throttledReason = null
            lastPolicyAdjustmentAt = System.currentTimeMillis()
            _uiState.value = _uiState.value.copy(
                telemetry = _uiState.value.telemetry.copy(throttledReason = null)
            )
            initSlots()
            return
        }

        _uiState.value = _uiState.value.copy(
            telemetry = _uiState.value.telemetry.copy(
                activeSlotLimit = runtimeActiveSlotLimit,
                activeSlots = activeSlots,
                standbySlots = standbySlots,
                bufferingSlots = bufferingSlots,
                errorSlots = errorSlots,
                droppedFramesDelta = droppedFramesDelta,
                totalDroppedFrames = totalDroppedFrames,
                sustainedLoadScore = loadScore,
                thermalStatus = thermalStatus,
                isLowMemory = lowMemory,
                throttledReason = throttledReason,
                recommendation = buildTelemetryRecommendation(
                    activeSlotLimit = runtimeActiveSlotLimit,
                    thermalStatus = thermalStatus,
                    isLowMemory = lowMemory,
                    sustainedLoadScore = loadScore,
                    policy = policy
                )
            )
        )
    }

    private fun shouldThrottleDown(): Boolean {
        val coolingDown = System.currentTimeMillis() - lastPolicyAdjustmentAt < 5_000
        if (coolingDown) return false
        return sustainedStressSamples >= 2 ||
            thermalStatus == MultiViewThermalStatus.SEVERE ||
            thermalStatus == MultiViewThermalStatus.CRITICAL
    }

    private fun shouldRecover(policy: MultiViewPerformancePolicyUiModel): Boolean {
        val coolingDown = System.currentTimeMillis() - lastPolicyAdjustmentAt < 8_000
        if (coolingDown) return false
        return stableSamples >= 4 &&
            runtimeActiveSlotLimit < policy.maxActiveSlots
    }

    private fun isLowMemoryDeviceState(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        return memoryInfo.lowMemory || memoryInfo.availMem < 220L * 1024L * 1024L
    }

    private fun readThermalStatus(): MultiViewThermalStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return MultiViewThermalStatus.UNKNOWN
        val status = powerManager?.currentThermalStatus ?: return MultiViewThermalStatus.UNKNOWN
        return mapThermalStatus(status)
    }

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || thermalListener != null) return
        val manager = powerManager ?: return
        thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
            thermalStatus = mapThermalStatus(status)
        }
        manager.addThermalStatusListener(context.mainExecutor, thermalListener!!)
    }

    private fun unregisterThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val listener = thermalListener ?: return
        powerManager?.removeThermalStatusListener(listener)
        thermalListener = null
    }

    private fun mapThermalStatus(status: Int): MultiViewThermalStatus {
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> MultiViewThermalStatus.NORMAL
            PowerManager.THERMAL_STATUS_LIGHT -> MultiViewThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> MultiViewThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> MultiViewThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> MultiViewThermalStatus.CRITICAL
            else -> MultiViewThermalStatus.UNKNOWN
        }
    }

    private fun buildTelemetryRecommendation(
        activeSlotLimit: Int,
        thermalStatus: MultiViewThermalStatus,
        isLowMemory: Boolean,
        sustainedLoadScore: Int,
        policy: MultiViewPerformancePolicyUiModel
    ): String {
        return when {
            thermalStatus == MultiViewThermalStatus.SEVERE || thermalStatus == MultiViewThermalStatus.CRITICAL ->
                "Device is under thermal pressure. Keep Split Screen in ${policy.mode.name.lowercase()} mode and limit active playback to $activeSlotLimit slot(s)."
            isLowMemory ->
                "Available memory is tight. Extra slots stay in standby until playback stabilizes."
            sustainedLoadScore >= 8 ->
                "Sustained decode load is high. Standby slots will protect lower-end Android TV devices from stutter."
            activeSlotLimit < policy.maxActiveSlots ->
                "Playback is stabilizing. Split Screen will restore more active slots when dropped frames and buffering settle down."
            else ->
                "Runtime telemetry is healthy. This device can keep up with the current Split Screen load."
        }
    }
}
