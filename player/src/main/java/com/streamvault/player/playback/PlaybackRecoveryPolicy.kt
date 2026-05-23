package com.streamvault.player.playback

internal enum class AutomaticRecoveryAction {
    FULL_REPREPARE,
    SURFACE_FALLBACK,
    DECODER_FALLBACK
}

internal fun shouldAttemptAutomaticRecovery(
    action: AutomaticRecoveryAction,
    resolvedStreamType: ResolvedStreamType,
    playbackStarted: Boolean
): Boolean {
    if (!playbackStarted) return true
    if (!resolvedStreamType.isLiveProviderSession()) return true
    return action == AutomaticRecoveryAction.SURFACE_FALLBACK
}

private fun ResolvedStreamType.isLiveProviderSession(): Boolean {
    return this == ResolvedStreamType.HLS ||
        this == ResolvedStreamType.SMOOTH_STREAMING ||
        this == ResolvedStreamType.MPEG_TS_LIVE ||
        this == ResolvedStreamType.RTSP
}
