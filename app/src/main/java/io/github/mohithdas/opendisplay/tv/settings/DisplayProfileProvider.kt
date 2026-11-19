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
    private val UHD = PixelSize(3840, 2160)

    /** Safe during Application and foreground-service startup, before any Activity exists. */
    fun detectServiceSafe(context: Context): DisplayProfile {
        val appContext = context.applicationContext
        val metrics = appContext.resources.displayMetrics
        val fallback = if (isTelevision(appContext)) FULL_HD else {
            safePixelSize(metrics.widthPixels, metrics.heightPixels) ?: HD
        }
        val detected: Pair<Display?, PixelSize> = try {
            val manager = appContext.getSystemService(DisplayManager::class.java)
            val reference = manager?.getDisplay(Display.DEFAULT_DISPLAY)
            reference to (modeSize(reference) ?: fallback)
        } catch (failure: Exception) {
            Log.warn("physical display detection failed; using ${fallback.width}x${fallback.height}", failure)
            null to fallback
        }

        return profile(
            context = appContext,
            physical = detected.second,
            window = detected.second,
            density = metrics.density.toDouble(),
            display = detected.first,
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
                display = display,
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
        display: Display?,
    ): DisplayProfile {
        val capabilities = bestAvcCapabilities()
        val decoderMaximum = reliableAvcMaximum(physical, capabilities)
        return DisplayProfile(
            physicalMode = physical,
            windowBounds = window,
            decoderMaximum = decoderMaximum,
            isTelevision = isTelevision(context),
            androidDensity = density.takeIf { it.isFinite() && it > 0.0 } ?: 1.0,
            avcHardMaximum = avcHardMaximum(capabilities),
            avcUhdFrameRate = avcFrameRate(capabilities, UHD),
            avcMaxFrameRate = avcFrameRate(capabilities, decoderMaximum),
            refreshRateHz = activeRefreshRate(display),
            supportedModes = modeFacts(display),
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

    /**
     * The rate the H.264 decoder itself claims for one raster.
     *
     * `getSupportedFrameRatesFor` throws when the size is outside what the codec supports, which is
     * the answer to the 4K question rather than a failure to report: no rate at 3840x2160 means this
     * box cannot decode 4K whatever the panel says. Vendors also occasionally throw for other
     * reasons, so that distinction is logged rather than guessed at.
     */
    private fun avcFrameRate(capabilities: MediaCodecInfo.VideoCapabilities?, size: PixelSize): Double? {
        val caps = capabilities ?: return null
        return try {
            caps.getSupportedFrameRatesFor(size.width, size.height)?.upper
        } catch (failure: IllegalArgumentException) {
            Log.info("AVC decoder supports no frame rate at ${size.width}x${size.height}")
            null
        } catch (failure: Exception) {
            Log.warn("vendor AVC frame-rate query failed at ${size.width}x${size.height}", failure)
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

    private fun activeRefreshRate(display: Display?): Double = try {
        display?.mode?.refreshRate?.toDouble() ?: 0.0
    } catch (failure: Exception) {
        Log.warn("vendor refresh rate query failed", failure)
        0.0
    }

    /**
     * Every mode the display enumerates, with its rate — the list that shows whether a 4K panel is
     * offered at 60 Hz, only at 30 Hz, or not at all. A firmware that hides the active mode's
     * siblings leaves a one-entry list, which is still worth showing: it is the difference between
     * "the box is configured for 1080p output" and "4K never appears here".
     */
    private fun modeFacts(display: Display?): List<ModeFact> = try {
        display?.supportedModes?.mapNotNull { mode ->
            val pixels = safePixelSize(mode.physicalWidth, mode.physicalHeight)
            pixels?.let { ModeFact(it, mode.refreshRate.toDouble()) }
        } ?: emptyList()
    } catch (failure: Exception) {
        Log.warn("vendor display mode enumeration failed", failure)
        emptyList()
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
