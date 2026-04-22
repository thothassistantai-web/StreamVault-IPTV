package com.streamvault.app.player

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.PlayerEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Singleton
class LivePreviewHandoffManager @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingReleaseJob: Job? = null
    private var session: LivePreviewHandoffSession? = null

    fun registerPreviewSession(
        channel: Channel,
        streamInfo: StreamInfo,
        engine: PlayerEngine
    ) {
        val previous = session
        pendingReleaseJob?.cancel()
        session = LivePreviewHandoffSession(
            engine = engine,
            channelId = channel.id,
            providerId = channel.providerId,
            streamInfo = streamInfo,
            pendingFullscreen = false
        )
        if (previous != null && previous.engine !== engine) {
            previous.engine.release()
        }
    }

    fun beginFullscreenHandoff(channelId: Long, engine: PlayerEngine?): Boolean {
        val current = session ?: return false
        if (engine == null || current.engine !== engine || current.channelId != channelId) return false
        pendingReleaseJob?.cancel()
        session = current.copy(
            pendingFullscreen = true,
            updatedAtMs = System.currentTimeMillis()
        )
        pendingReleaseJob = scope.launch {
            delay(PENDING_FULLSCREEN_TIMEOUT_MS)
            val stale = session
            if (stale?.pendingFullscreen == true) {
                session = null
                stale.engine.release()
            }
        }
        return true
    }

    fun consumeFullscreenHandoff(channelId: Long, providerId: Long?): LivePreviewHandoffSession? {
        val current = session ?: return null
        if (!current.pendingFullscreen) return null
        if (current.channelId != channelId) return null
        if (providerId != null && providerId > 0L && current.providerId != providerId) return null
        pendingReleaseJob?.cancel()
        session = null
        return current
    }

    fun clear(engine: PlayerEngine?) {
        val current = session ?: return
        if (engine != null && current.engine !== engine) return
        pendingReleaseJob?.cancel()
        session = null
    }

    data class LivePreviewHandoffSession(
        val engine: PlayerEngine,
        val channelId: Long,
        val providerId: Long,
        val streamInfo: StreamInfo,
        val pendingFullscreen: Boolean,
        val updatedAtMs: Long = System.currentTimeMillis()
    )

    private companion object {
        const val PENDING_FULLSCREEN_TIMEOUT_MS = 15_000L
    }
}
