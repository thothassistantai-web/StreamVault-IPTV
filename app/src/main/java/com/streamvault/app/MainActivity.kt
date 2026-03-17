package com.streamvault.app

import android.app.PictureInPictureParams
import android.os.Bundle
import android.os.Build
import android.os.StrictMode
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.streamvault.app.navigation.AppNavigation
import com.streamvault.app.ui.theme.StreamVaultTheme
import dagger.hilt.android.AndroidEntryPoint

import javax.inject.Inject
import com.streamvault.data.preferences.PreferencesRepository
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import android.content.res.Configuration
import android.text.TextUtils
import android.view.View
import java.util.Locale
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private data class PlayerPictureInPictureState(
        val enabled: Boolean = false,
        val isPlaying: Boolean = false,
        val aspectRatio: Rational? = null
    )

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    private val _pictureInPictureModeFlow = MutableStateFlow(false)
    val pictureInPictureModeFlow: StateFlow<Boolean> = _pictureInPictureModeFlow.asStateFlow()

    private var playerPictureInPictureState = PlayerPictureInPictureState()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build()
            )
        }
        super.onCreate(savedInstanceState)
        _pictureInPictureModeFlow.value = isInPictureInPictureMode
        setContent {
            val appLanguage by preferencesRepository.appLanguage.collectAsState(initial = "system")
            val currentContext = LocalContext.current
            
            val configuration = remember(appLanguage) {
                val conf = Configuration(currentContext.resources.configuration)
                if (appLanguage != "system") {
                    val locale = Locale(appLanguage)
                    Locale.setDefault(locale)
                    conf.setLocale(locale)
                    conf.setLayoutDirection(locale)
                } else {
                    conf.setLocale(Locale.getDefault())
                    conf.setLayoutDirection(Locale.getDefault())
                }
                conf
            }
            val localizedContext = remember(configuration, currentContext) {
                val configurationContext = currentContext.createConfigurationContext(configuration)
                object : ContextWrapper(currentContext) {
                    override fun getResources(): Resources = configurationContext.resources
                    override fun getAssets(): AssetManager = configurationContext.assets
                    override fun getSystemService(name: String): Any? {
                        return if (name == Context.LAYOUT_INFLATER_SERVICE) {
                            configurationContext.getSystemService(name)
                        } else {
                            super.getSystemService(name)
                        }
                    }
                }
            }

            val layoutDirection = remember(configuration) {
                if (TextUtils.getLayoutDirectionFromLocale(configuration.locales[0]) == View.LAYOUT_DIRECTION_RTL) {
                    LayoutDirection.Rtl
                } else {
                    LayoutDirection.Ltr
                }
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalLayoutDirection provides layoutDirection
            ) {
                StreamVaultTheme {
                    AppNavigation()
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPlayerPictureInPictureModeIfEligible()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        _pictureInPictureModeFlow.value = isInPictureInPictureMode
    }

    fun updatePlayerPictureInPictureState(
        enabled: Boolean,
        isPlaying: Boolean,
        videoWidth: Int,
        videoHeight: Int
    ) {
        playerPictureInPictureState = PlayerPictureInPictureState(
            enabled = enabled,
            isPlaying = isPlaying,
            aspectRatio = videoAspectRatioOrNull(videoWidth, videoHeight)
        )
        applyPlayerPictureInPictureParams()
    }

    fun clearPlayerPictureInPictureState() {
        playerPictureInPictureState = PlayerPictureInPictureState()
        applyPlayerPictureInPictureParams()
    }

    private fun enterPlayerPictureInPictureModeIfEligible(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isInPictureInPictureMode) {
            return false
        }
        val state = playerPictureInPictureState
        if (!state.enabled || !state.isPlaying) {
            return false
        }
        val params = buildPlayerPictureInPictureParams(state)
        setPictureInPictureParams(params)
        return enterPictureInPictureMode(params)
    }

    private fun applyPlayerPictureInPictureParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        setPictureInPictureParams(buildPlayerPictureInPictureParams(playerPictureInPictureState))
    }

    private fun buildPlayerPictureInPictureParams(
        state: PlayerPictureInPictureState
    ): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        state.aspectRatio?.let { builder.setAspectRatio(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(state.enabled && state.isPlaying)
        }
        return builder.build()
    }

    private fun videoAspectRatioOrNull(videoWidth: Int, videoHeight: Int): Rational? {
        if (videoWidth <= 0 || videoHeight <= 0) return null
        return Rational(videoWidth, videoHeight)
    }
}
