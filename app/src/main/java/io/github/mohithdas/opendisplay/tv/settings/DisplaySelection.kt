package io.github.mohithdas.opendisplay.tv.settings

import kotlin.math.min
import kotlin.math.roundToInt

data class PixelSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }

    val longEdge: Int get() = maxOf(width, height)
    val shortEdge: Int get() = minOf(width, height)
}

data class DisplayProfile(
    val physicalMode: PixelSize,
    val windowBounds: PixelSize,
    val decoderMaximum: PixelSize,
    val isTelevision: Boolean,
    val androidDensity: Double,
    /**
     * The H.264 decoder's own advertised bounds, before any clamp to the reported display mode.
     *
     * [decoderMaximum] can never exceed the mode Android reports, so on a television whose firmware
     * only ever exposes a 1080p mode for a 4K panel it is this number — not that one — that says
     * whether a 4K stream could be decoded at all. Null when the capability query is unavailable.
     */
    val avcHardMaximum: PixelSize? = null,
    /**
     * Highest frame rate the H.264 decoder claims to sustain at 3840x2160, or null if it does not
     * claim 4K at any of the probed rates. Paired with [avcHardMaximum] in the Diagnostics rows,
     * this is what separates "the firmware hides the panel" from "this box cannot decode 4K".
     */
    val avcUhdFrameRate: Double? = null,
)

data class DisplaySelection(val pixels: PixelSize, val scale: Double)

/**
 * Panel facts for `hello.panel` (PROTOCOL.md 6.7): the physical pixels the panel
 * lights 1:1, the real backing scale between those pixels and the window this app
 * draws into, and that window as the desktop the receiver itself runs.
 *
 * A 4K television whose Android window is 1080p reports 3840x2160 pixels at scale 2
 * with a 1920x1080 desktop, so the sender can build a genuinely HiDPI 4K desktop
 * instead of guessing a factor from the deprecated `hello.scale` it has to ignore.
 */
data class PanelFacts(val pixels: PixelSize, val scale: Double, val points: PixelSize)

object DisplaySelector {
    private val UHD = PixelSize(3840, 2160)
    private val FULL_HD = PixelSize(1920, 1080)
    private val HD = PixelSize(1280, 720)

    /**
     * The profile the receiver announces when the advanced 4K override is on.
     *
     * Television firmware commonly reports a 1080p display mode for a 4K panel: the interface is
     * rendered at 1080p and the television upscales it, so detection alone can never offer UHD even
     * when the H.264 decoder is happy to take 3840x2160. The override treats the panel as UHD for
     * the offered choices, the announced size, and the panel facts, which turns the unanswerable
     * question "is the pipeline really 1080p?" into one the user can test and revert. It leaves any
     * profile that already reaches UHD untouched, and it deliberately does not change what `Auto`
     * announces — the override unlocks a menu entry, it does not silently re-decide the default.
     */
    fun effectiveProfile(profile: DisplayProfile, forceUhd: Boolean): DisplayProfile {
        if (!forceUhd || profile.physicalMode.longEdge >= UHD.longEdge) return profile
        val pixels = oriented(UHD, profile.windowBounds)
        return profile.copy(
            physicalMode = pixels,
            decoderMaximum = if (profile.decoderMaximum.longEdge >= UHD.longEdge) {
                profile.decoderMaximum
            } else {
                pixels
            },
        )
    }

    fun supportedChoices(profile: DisplayProfile, forceUhd: Boolean = false): List<ResolutionChoice> =
        buildList {
            val source = effectiveProfile(profile, forceUhd)
            add(ResolutionChoice.AUTO)
            if (supports(UHD, source)) add(ResolutionChoice.UHD)
            if (supports(FULL_HD, source)) add(ResolutionChoice.FULL_HD)
            if (supports(HD, source)) add(ResolutionChoice.HD)
        }

    fun select(
        profile: DisplayProfile,
        resolution: ResolutionChoice,
        uiScale: UiScaleChoice,
        forceUhd: Boolean = false,
    ): DisplaySelection {
        val source = effectiveProfile(profile, forceUhd)
        val requested = when (resolution) {
            ResolutionChoice.AUTO -> automaticSize(source)
            ResolutionChoice.UHD -> oriented(UHD, source.windowBounds)
            ResolutionChoice.FULL_HD -> oriented(FULL_HD, source.windowBounds)
            ResolutionChoice.HD -> oriented(HD, source.windowBounds)
        }
        val safe = fitWithin(requested, source.physicalMode, source.decoderMaximum)
        val scale = when (uiScale) {
            UiScaleChoice.AUTO -> if (source.isTelevision || source.androidDensity < 1.5) 1.0 else 2.0
            UiScaleChoice.ONE -> 1.0
            UiScaleChoice.TWO -> 2.0
        }
        return DisplaySelection(safe.even(), scale)
    }

    /**
     * Television panel facts only. Handheld receivers keep the deprecated
     * `hello.pixelsWide/High` path until their density-to-backing-scale mapping
     * has been measured on real hardware.
     */
    fun televisionPanelFacts(profile: DisplayProfile): PanelFacts {
        val points = profile.windowBounds
        val pixels = oriented(profile.physicalMode, points)
        return PanelFacts(pixels, backingScale(pixels, points), points)
    }

    private fun backingScale(pixels: PixelSize, points: PixelSize): Double {
        val ratio = pixels.longEdge.toDouble() / points.longEdge
        val bounded = if (ratio.isFinite() && ratio >= 1.0) ratio.coerceAtMost(4.0) else 1.0
        return (bounded * 100).roundToInt() / 100.0
    }

    private fun automaticSize(profile: DisplayProfile): PixelSize {
        val native = if (profile.isTelevision) profile.physicalMode else profile.windowBounds
        // Auto stays conservative for televisions: inexpensive sticks commonly expose a 4K HDMI
        // mode while only sustaining low-latency AVC reliably at 1080p. 4K is now available as an
        // explicit ResolutionChoice.UHD selection, offered only when the panel and the reported
        // AVC ceiling both support it, so nothing changes for anyone who leaves Auto alone.
        return if (profile.isTelevision && native.longEdge > FULL_HD.longEdge) {
            oriented(FULL_HD, native)
        } else {
            native
        }
    }

    private fun supports(candidate: PixelSize, profile: DisplayProfile): Boolean =
        candidate.longEdge <= profile.physicalMode.longEdge &&
            candidate.shortEdge <= profile.physicalMode.shortEdge &&
            candidate.longEdge <= profile.decoderMaximum.longEdge &&
            candidate.shortEdge <= profile.decoderMaximum.shortEdge

    private fun oriented(size: PixelSize, orientation: PixelSize): PixelSize =
        if ((orientation.width >= orientation.height) == (size.width >= size.height)) size
        else PixelSize(size.height, size.width)

    private fun fitWithin(value: PixelSize, vararg limits: PixelSize): PixelSize {
        var scale = 1.0
        limits.forEach { limit ->
            val direct = min(
                limit.width.toDouble() / value.width,
                limit.height.toDouble() / value.height,
            )
            scale = min(scale, direct)
        }
        return if (scale >= 1.0) value else PixelSize(
            (value.width * scale).roundToInt().coerceAtLeast(2),
            (value.height * scale).roundToInt().coerceAtLeast(2),
        )
    }

    private fun PixelSize.even(): PixelSize = PixelSize(width and -2, height and -2)
}
