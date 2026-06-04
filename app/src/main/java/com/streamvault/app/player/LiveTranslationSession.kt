package com.streamvault.app.player

import androidx.media3.common.C
import androidx.media3.common.text.Cue
import com.streamvault.player.LiveAudioPcmBuffer
import com.streamvault.player.PlayerEngine
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TARGET_SAMPLE_RATE = 16_000
private const val TARGET_CHANNEL_COUNT = 1
private const val TARGET_BYTES_PER_SAMPLE = 2
// Upload small audio increments frequently; the service accumulates them into a
// phrase, emits in-progress partials, and finalises on a natural pause (VAD).
private const val UPLOAD_CHUNK_MS = 1_000L
private const val MAX_PENDING_AUDIO_BUFFERS = 120
// Minimum time a caption stays on screen before it can advance to newer text, so
// the subtitle doesn't change faster than a viewer can read. Trades a little
// latency for readability; intermediate updates during this window are coalesced.
private const val MIN_CAPTION_DISPLAY_MS = 2_200L
// Clear the caption after this much idle time with no new text (TV-caption feel).
private const val SUBTITLE_LINGER_MS = 20_000L

private data class CaptionTick(
    val text: String,
    val isFinal: Boolean,
    val chunkId: Long
)

class LiveTranslationSession(
    private val scope: CoroutineScope,
    private val playerEngine: PlayerEngine,
    private val client: LiveTranslationClient,
    private val logicalUrl: String,
    private val providerId: Long,
    private val contentId: Long,
    private val onSourceLanguageDetected: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var sessionId: String? = null
    private var pollingJob: Job? = null
    private var audioBuffers = newAudioChannel()
    // Latest pending caption text waiting to be displayed. Conflated: if several
    // updates arrive during a dwell, only the most recent is kept.
    private var captionUpdates = newCaptionChannel()
    private var hasVisibleCaption = false

    fun start() {
        stop()
        audioBuffers = newAudioChannel()
        captionUpdates = newCaptionChannel()
        playerEngine.setLiveAudioTap { buffer ->
            audioBuffers.trySend(buffer)
        }
        pollingJob = scope.launch {
            runCatching {
                sessionId = client.startSession(
                    logicalUrl = logicalUrl,
                    providerId = providerId,
                    contentId = contentId
                )
                coroutineScope {
                    launch { displayLoop() }
                    audioUploadLoop()
                }
            }.onFailure {
                if (it is CancellationException) {
                    clearCues()
                    return@onFailure
                }
                onError("Live translation unavailable: ${it.message.orEmpty().ifBlank { "service error" }}")
                clearCues()
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        playerEngine.clearLiveAudioTap()
        audioBuffers.close()
        captionUpdates.close()
        val currentSessionId = sessionId
        sessionId = null
        clearCues()
        if (currentSessionId != null) {
            scope.launch {
                client.stopSession(currentSessionId)
            }
        }
    }

    private suspend fun audioUploadLoop() {
        val chunk = ByteArrayOutputStream()
        var chunkStartMs: Long? = null
        var chunkEndMs = 0L
        var fallbackPositionMs = playerEngine.currentPosition.value
        for (buffer in audioBuffers) {
            if (!scope.isActive) return
            val activeSessionId = sessionId ?: return
            val converted = convertToPcm16Mono16k(buffer, fallbackPositionMs) ?: continue
            fallbackPositionMs = converted.endMs
            if (converted.data.isEmpty()) continue
            val activeChunkStartMs = chunkStartMs ?: converted.startMs
            chunkStartMs = activeChunkStartMs
            chunkEndMs = converted.endMs
            chunk.write(converted.data)
            if (chunkEndMs - activeChunkStartMs < UPLOAD_CHUNK_MS) {
                continue
            }
            uploadChunk(activeSessionId, chunk.toByteArray(), activeChunkStartMs, chunkEndMs)
            chunk.reset()
            chunkStartMs = null
            delay(50L)
        }
    }

    private suspend fun uploadChunk(sessionId: String, pcmChunk: ByteArray, startMs: Long, endMs: Long) {
        val update = client.uploadPcmChunk(
            sessionId = sessionId,
            pcm16Mono16k = pcmChunk,
            startMs = startMs,
            endMs = endMs
        )
        // Empty text means silence / no new transcription for this upload.
        if (update.text.isBlank()) {
            return
        }
        update.sourceLanguage?.let(onSourceLanguageDetected)
        // Hand the latest text to the display loop, which paces how fast captions
        // change. Conflated channel: if we're mid-dwell, this just replaces the
        // pending text so the viewer always advances to the most recent state.
        captionUpdates.trySend(
            CaptionTick(
                text = update.text,
                isFinal = update.isFinal,
                chunkId = update.chunkId
            )
        )
    }

    /**
     * Paces caption changes for readability. Partial lines update immediately and
     * any queued updates are drained in order so finals aren't skipped. Finalised
     * phrases dwell for [MIN_CAPTION_DISPLAY_MS]. Clears after [SUBTITLE_LINGER_MS]
     * of no updates.
     */
    private suspend fun displayLoop() {
        while (scope.isActive) {
            val result = withTimeoutOrNull(SUBTITLE_LINGER_MS) { captionUpdates.receiveCatching() }
            if (result == null) {
                if (hasVisibleCaption) clearCues()
                continue
            }
            val tick = result.getOrNull() ?: break // channel closed -> session ended
            showCaptionTick(tick)
            drainQueuedCaptionTicks()
        }
    }

    private suspend fun showCaptionTick(tick: CaptionTick) {
        if (tick.text.isBlank()) return
        renderCaption(tick.text)
        hasVisibleCaption = true
        if (tick.isFinal) {
            delay(MIN_CAPTION_DISPLAY_MS)
        }
    }

    /** Show any captions that arrived while the previous tick was on screen. */
    private suspend fun drainQueuedCaptionTicks() {
        while (scope.isActive) {
            val next = captionUpdates.tryReceive().getOrNull() ?: break
            showCaptionTick(next)
        }
    }

    private fun renderCaption(text: String) {
        playerEngine.setInjectedSubtitleCues(
            listOf(Cue.Builder().setText(text).build())
        )
    }

    private fun clearCues() {
        hasVisibleCaption = false
        playerEngine.clearInjectedSubtitleCues()
    }
}

private data class ConvertedPcmChunk(
    val data: ByteArray,
    val startMs: Long,
    val endMs: Long
)

private fun newAudioChannel(): Channel<LiveAudioPcmBuffer> = Channel(
    capacity = MAX_PENDING_AUDIO_BUFFERS,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)

private fun newCaptionChannel(): Channel<CaptionTick> = Channel(
    capacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)

private fun convertToPcm16Mono16k(buffer: LiveAudioPcmBuffer, fallbackStartMs: Long): ConvertedPcmChunk? {
    if (buffer.encoding != C.ENCODING_PCM_16BIT || buffer.sampleRate <= 0 || buffer.channelCount <= 0) {
        return null
    }
    val inputFrameSize = buffer.channelCount * TARGET_BYTES_PER_SAMPLE
    val inputFrames = buffer.data.size / inputFrameSize
    if (inputFrames <= 0) return null

    val outputFrames = ((inputFrames.toLong() * TARGET_SAMPLE_RATE) / buffer.sampleRate)
        .coerceAtLeast(1L)
        .toInt()
    val output = ByteArray(outputFrames * TARGET_CHANNEL_COUNT * TARGET_BYTES_PER_SAMPLE)
    for (outputFrame in 0 until outputFrames) {
        val inputFrame = ((outputFrame.toLong() * buffer.sampleRate) / TARGET_SAMPLE_RATE)
            .coerceIn(0L, (inputFrames - 1).toLong())
            .toInt()
        var mixed = 0
        for (channel in 0 until buffer.channelCount) {
            val byteIndex = inputFrame * inputFrameSize + channel * TARGET_BYTES_PER_SAMPLE
            val low = buffer.data[byteIndex].toInt() and 0xFF
            val high = buffer.data[byteIndex + 1].toInt()
            mixed += (high shl 8) or low
        }
        val sample = (mixed / buffer.channelCount).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val outputIndex = outputFrame * TARGET_BYTES_PER_SAMPLE
        output[outputIndex] = (sample and 0xFF).toByte()
        output[outputIndex + 1] = ((sample shr 8) and 0xFF).toByte()
    }
    val startMs = fallbackStartMs.coerceAtLeast(0L)
    val durationMs = (outputFrames * 1_000L) / TARGET_SAMPLE_RATE
    return ConvertedPcmChunk(
        data = output,
        startMs = startMs,
        endMs = startMs + durationMs
    )
}
