package com.streamvault.app.diagnostics

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ChannelRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ExternalPlayerProbeActivity : ComponentActivity() {
    @Inject
    lateinit var channelRepository: ChannelRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            launchExternalPlayer()
            finish()
        }
    }

    private suspend fun launchExternalPlayer() {
        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
        if (channelId <= 0L) {
            Log.w(TAG, "external-player-probe missing channel id")
            return
        }

        val channel = channelRepository.getChannel(channelId)
        if (channel == null) {
            Log.w(TAG, "external-player-probe channel not found channelId=$channelId")
            return
        }

        val streamInfo = when (val result = channelRepository.getStreamInfo(channel)) {
            is Result.Success -> result.data
            is Result.Error -> {
                Log.w(TAG, "external-player-probe resolve failed channelId=$channelId")
                return
            }
            Result.Loading -> return
        }

        val url = if (intent.getBooleanExtra(EXTRA_FORCE_TS, false)) {
            toMpegTsUrl(streamInfo.url)
        } else {
            streamInfo.url
        }
        val playerPackage = intent.getStringExtra(EXTRA_PLAYER_PACKAGE)
            ?.takeIf(String::isNotBlank)
            ?: DEFAULT_PLAYER_PACKAGE
        val launchIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(url.toUri(), "video/*")
            .setPackage(playerPackage)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { startActivity(launchIntent) }
            .onSuccess {
                Log.i(
                    TAG,
                    "external-player-probe launched channelId=$channelId " +
                        "playerPackage=$playerPackage forceTs=${intent.getBooleanExtra(EXTRA_FORCE_TS, false)}"
                )
            }
            .onFailure { error ->
                Log.w(TAG, "external-player-probe launch failed channelId=$channelId error=${error::class.java.simpleName}")
            }
    }

    private fun toMpegTsUrl(url: String): String {
        val queryStart = url.indexOf('?')
        val base = if (queryStart >= 0) url.substring(0, queryStart) else url
        val query = if (queryStart >= 0) url.substring(queryStart) else ""
        return if (base.endsWith(HLS_EXTENSION, ignoreCase = true)) {
            base.dropLast(HLS_EXTENSION.length) + MPEG_TS_EXTENSION + query
        } else {
            url
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_PLAYER_PACKAGE = "player_package"
        const val EXTRA_FORCE_TS = "force_ts"
        private const val DEFAULT_PLAYER_PACKAGE = "org.videolan.vlc"
        private const val HLS_EXTENSION = ".m3u8"
        private const val MPEG_TS_EXTENSION = ".ts"
        private const val TAG = "ExternalPlayerProbe"
    }
}
