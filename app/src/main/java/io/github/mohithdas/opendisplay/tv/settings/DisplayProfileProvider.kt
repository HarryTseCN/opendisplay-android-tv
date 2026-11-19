package io.github.mohithdas.opendisplay.tv.settings

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import io.github.mohithdas.opendisplay.tv.util.Log

/**
 * Builds receiver display profiles without assuming every [Context] belongs to a display.
 *
 * A Service/Application context is deliberately restricted to DisplayManager and resource
 * metrics. Window metrics and Context.getDisplay() are visual APIs and are used only by the
 * Activity overload.
 */
object DisplayProfileProvider {
    private val HD = PixelSize(1280, 720)
    private val FULL_HD = PixelSize(1920, 1080)

    /** Safe during Application and foreground-service startup, before any Activity exists. */
    fun detectServiceSafe(context: Context): DisplayProfile {
        val appContext = context.applicationContext
        val metrics = appContext.resources.displayMetrics
        val fallback = if (isTelevision(appContext)) FULL_HD else {
            safePixelSize(metrics.widthPixels, metrics.heightPixels) ?: HD
        }
        val physical = try {
            val manager = appContext.getSystemService(DisplayManager::class.java)
            modeSize(manager?.getDisplay(Display.DEFAULT_DISPLAY)) ?: fallback
        } catch (failure: Exception) {
            Log.warn("physical display detection failed; using ${fallback.width}x${fallback.height}", failure)
            fallback
        }

        return profile(
            context = appContext,
            physical = physical,
            window = physical,
            density = metrics.density.toDouble(),
        )
    }

    /** Accurate visual detection. This API intentionally cannot accept a Service Context. */
    fun detectActivity(activity: Activity): DisplayProfile {
        val fallback = detectServiceSafe(activity.applicationContext)
        return try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay
            }
            val physical = modeSize(display) ?: fallback.physicalMode
            val window = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = activity.windowManager.currentWindowMetrics.bounds
                safePixelSize(bounds.width(), bounds.height()) ?: fallback.windowBounds
            } else {
                val metrics = activity.resources.displayMetrics
                safePixelSize(metrics.widthPixels, metrics.heightPixels) ?: fallback.windowBounds
            }
            profile(
                context = activity,
                physical = physical,
                window = window,
                density = activity.resources.displayMetrics.density.toDouble(),
            )
        } catch (failure: Exception) {
            Log.warn(
                "visual display detection failed; using service-safe " +
                    "${fallback.physicalMode.width}x${fallback.physicalMode.height} profile",
                failure,
            )
            fallback
        }
    }

    private fun profile(
        context: Context,
        physical: PixelSize,
        window: PixelSize,
        density: Double,
    ): DisplayProfile {
        val capabilities = bestAvcCapabilities()
        return DisplayProfile(
            physicalMode = physical,
            windowBounds = window,
            decoderMaximum = reliableAvcMaximum(physical, capabilities),
            isTelevision = isTelevision(context),
            androidDensity = density.takeIf { it.isFinite() && it > 0.0 } ?: 1.0,
            avcHardMaximum = avcHardMaximum(capabilities),
            avcUhdFrameRate = avcUhdFrameRate(capabilities),
        )
    }

    /**
     * The most capable H.264 decoder on the device rather than the first one enumerated. A
     * television typically ships a software decoder alongside the hardware one, and the software
     * entry advertises a far smaller ceiling, so picking by name order would understate the box
     * and hide the 4K option from a panel that can decode it.
     */
    private fun bestAvcCapabilities(): MediaCodecInfo.VideoCapabilities? = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info ->
                !info.isEncoder && info.supportedTypes.any { type ->
                    type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true)
                }
            }
            .mapNotNull { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities }
            .maxByOrNull { capabilities ->
                boundsProduct(capabilities)
            }
    } catch (failure: Exception) {
        Log.warn("AVC decoder enumeration failed; hardware ceiling unknown", failure)
        null
    }

    private fun boundsProduct(capabilities: MediaCodecInfo.VideoCapabilities): Long {
        val width = capabilities.supportedWidths?.upper?.toLong() ?: 0L
        val height = capabilities.supportedHeights?.upper?.toLong() ?: 0L
        return width * height
    }

    /** The decoder's advertised bounds, uncapped by the display mode — reported in Diagnostics. */
    private fun avcHardMaximum(capabilities: MediaCodecInfo.VideoCapabilities?): PixelSize? {
        val width: Int = capabilities?.supportedWidths?.upper ?: return null
        val height: Int = capabilities.supportedHeights?.upper ?: return null
        return if (width >= 2 && height >= 2) PixelSize(width, height) else null
    }

    private val uhdFrameRateProbe = listOf(60.0, 50.0, 30.0, 25.0, 24.0)

    /** Highest frame rate the decoder claims at 3840x2160, or null when it claims none. */
    private fun avcUhdFrameRate(capabilities: MediaCodecInfo.VideoCapabilities?): Double? {
        val caps = capabilities ?: return null
        return try {
            uhdFrameRateProbe.firstOrNull { rate ->
                try {
                    caps.isSizeSupportedForFrameRate(3840, 2160, rate.toFloat())
                } catch (failure: Exception) {
                    Log.warn("vendor AVC frame-rate query failed at ${rate}fps", failure)
                    false
                }
            }
        } catch (failure: Exception) {
            Log.warn("AVC 4K frame-rate probe failed", failure)
            null
        }
    }

    private fun modeSize(display: Display?): PixelSize? = try {
        val mode = display?.mode ?: return null
        safePixelSize(mode.physicalWidth, mode.physicalHeight)
    } catch (failure: Exception) {
        Log.warn("vendor display mode query failed", failure)
        null
    }

    private fun isTelevision(context: Context): Boolean = try {
        context.getSystemService(UiModeManager::class.java)?.currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION
    } catch (failure: Exception) {
        Log.warn("television mode detection failed; using handheld defaults", failure)
        false
    }

    private fun reliableAvcMaximum(physical: PixelSize, capabilities: MediaCodecInfo.VideoCapabilities?): PixelSize {
        if (capabilities == null) return HD
        val candidates = listOf(physical, FULL_HD, HD)
        return candidates.firstOrNull { candidate ->
            try {
                capabilities.isSizeSupported(candidate.width, candidate.height)
            } catch (failure: Exception) {
                Log.warn("vendor AVC capability query failed", failure)
                false
            }
        } ?: HD
    }

    private fun safePixelSize(width: Int, height: Int): PixelSize? =
        if (width >= 2 && height >= 2) PixelSize(width, height) else null
}
