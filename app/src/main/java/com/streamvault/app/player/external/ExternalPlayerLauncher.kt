package com.streamvault.app.player.external

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import java.lang.RuntimeException

/**
 * Builds a ready-to-launch [Intent] for an external player.
 *
 * The helper is pure (no Android framework calls like `startActivity`) so it can
 * be unit-tested in isolation.  Callers are responsible for actually launching
 * the returned intent.
 *
 * Validation rules:
 * - Blank / empty URLs are rejected (returns `null`).
 * - Only externally playable schemes are accepted (non-whitelisted schemes return `null`).
 * - Valid URLs produce an [Intent] with:
 *   - action: `Intent.ACTION_VIEW`
 *   - data: the parsed [Uri]
 *   - type: MIME type inferred from the URL extension via [inferExternalPlayerMimeType]
 *   - category: `Intent.CATEGORY_BROWSABLE`
 */
object ExternalPlayerLauncher {
    private val allowedExternalPlayerSchemes = setOf(
        "http",
        "https",
        "rtsp",
        "rtmp",
        "rtsps",
        "mms",
        "content",
        "file",
    )

    fun isExternalPlayerLaunchUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return false
        return Uri.parse(trimmed).scheme?.lowercase() in allowedExternalPlayerSchemes
    }

    /**
     * Build a view [Intent] for the given URL or return `null` when the URL is
     * invalid, blank, or uses a non-whitelisted scheme.
     *
     * @param url the stream URL to play in an external player
     * @return a configured [Intent] or `null` if the URL is not acceptable
     */
    fun buildExternalPlayerIntent(url: String): Intent? {
        val trimmed = url.trim()

        // Reject blank / empty URLs
        if (trimmed.isBlank()) {
            return null
        }

        val parsed = Uri.parse(trimmed)
        if (parsed.scheme?.lowercase() !in allowedExternalPlayerSchemes) {
            return null
        }

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(parsed, inferExternalPlayerMimeType(trimmed))
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }

    /**
     * Launch the given URL in an external player.
     *
     * Flow:
     * 1. Build the external player [Intent] via [buildExternalPlayerIntent].
     *    Returns [ExternalPlayerLaunchResult.InvalidUrl] when the URL is
     *    invalid or unparseable.
     * 2. Verify that an activity can handle the intent via
     *    [PackageManager.resolveActivity].  Returns [ExternalPlayerLaunchResult.NoHandler]
     *    when no handler is available.
     * 3. Add [Intent.FLAG_ACTIVITY_NEW_TASK] when [context] is not an [Activity].
     * 4. Call [Context.startActivity] wrapped in try/catch blocks:
     *    - [ActivityNotFoundException] → [ExternalPlayerLaunchResult.NoHandler]
     *    - Other [RuntimeException] → [ExternalPlayerLaunchResult.Failed]
     *
     * @param context the application or activity context used to launch the intent
     * @param url     the stream URL to play in an external player
     * @return the launch result indicating success or the specific failure mode
     */
    fun launch(context: Context, url: String): ExternalPlayerLaunchResult {
        val trimmed = url.trim()

        // 1. Build the intent; reject invalid URLs
        val intent = buildExternalPlayerIntent(trimmed)
        if (intent == null) {
            return ExternalPlayerLaunchResult.InvalidUrl(
                rawUrl = url,
                reason = "Invalid or non-whitelisted URL scheme",
            )
        }

        // 2. Verify that an activity can handle this intent
        val packageManager: PackageManager = context.packageManager
        if (intent.resolveActivity(packageManager) == null) {
            return ExternalPlayerLaunchResult.NoHandler(url = url)
        }

        // 3. Add FLAG_ACTIVITY_NEW_TASK when context is not an Activity
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // 4. Launch the intent with error handling
        return try {
            context.startActivity(intent)
            val mimeType = inferExternalPlayerMimeType(trimmed)
            ExternalPlayerLaunchResult.Success(url = url, mimeType = mimeType)
        } catch (e: ActivityNotFoundException) {
            Log.w(
                "ExternalPlayerLauncher",
                "No activity found to handle external player for: $url",
                e,
            )
            ExternalPlayerLaunchResult.NoHandler(url = url)
        } catch (e: RuntimeException) {
            Log.e(
                "ExternalPlayerLauncher",
                "Failed to launch external player for: $url",
                e,
            )
            ExternalPlayerLaunchResult.Failed(url = url, errorMessage = e.message ?: "Unknown runtime error")
        }
    }
}
