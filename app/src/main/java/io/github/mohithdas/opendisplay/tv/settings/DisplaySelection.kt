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
     * Highest frame rate the H.264 decoder claims to sustain at 3840x2160, or null when it claims
     * no rate at that raster. Paired with [avcHardMaximum] in the Diagnostics rows, this is what
     * separates "the firmware hides the panel" from "this box cannot decode 4K".
     */
    val avcUhdFrameRate: Double? = null,
    /**
     * Highest frame rate the H.264 decoder claims to sustain at the announced [decoderMaximum]
     * raster, or null when the query is unavailable or the codec gives no rate for that size. This
     * is the number a sender needs alongside [avcHardMaximum]: a box can advertise 4096x2304 and
     * only sustain it at 30 FPS.
     */
    val avcMaxFrameRate: Double? = null,
    /** Refresh rate of the active display mode in Hz, or 0.0 when the query is unavailable. */
    val refreshRateHz: Double = 0.0,
    /**
     * Every mode the display enumerated, each with its own refresh rate. On a 4K television this is
     * the list that says whether the firmware exposes a 3840x2160 mode at all, and at what rate —
     * the difference between "this panel only does 1080p", "4K is 30 Hz here", and "4K at 60 Hz is
     * available but Android reports the active mode as 1080p". Empty when unavailable.
     */
    val supportedModes: List<ModeFact> = emptyList(),
)

data class DisplaySelection(val pixels: PixelSize, val scale: Double)

/** One mode the display enumerated: what it can light, and how fast it can do it. */
data class ModeFact(val pixels: PixelSize, val refreshRateHz: Double)

/**
 * The stream envelope a sender may build: the largest raster the receiver will take, and the
 * highest rate it can be presented at — the binding constraint of the decoder's claim and the
 * panel's own refresh rate, never more than either. Absent rate means the receiver measured none
 * and the sender's legacy default applies.
 */
data class DecodeEnvelope(val pixels: PixelSize, val maxFrameRateHz: Int?)

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

    /**
     * The highest rate the panel can present (`hello.displayMaxFrameRate`, PROTOCOL.md 6.1): the
     * fastest enumerated mode, or the active mode when a vendor exposes no list. Null means nothing
     * was measured, which is deliberately not the same as 0 — an absent field keeps the sender's
     * legacy 60 FPS default, while a claimed ceiling it has to honour would cap the stream instead.
     *
     * Rates round to the nearest whole number because Android reports nominal 60 and 24 Hz modes as
     * 59.94 and 23.976; announcing the rounded rate gives the sender the mode it expects rather than
     * one it would reject as unsupported.
     */
    fun displayMaxFrameRate(profile: DisplayProfile): Int? =
        (
            profile.supportedModes.maxOfOrNull { it.refreshRateHz }
                ?: profile.refreshRateHz.takeIf { it > 0.0 }
            )?.let { it.roundToInt().coerceAtLeast(1) }

    /**
     * The H.264 entry for `hello.videoCaps` (PROTOCOL.md 6.5).
     *
     * [avcHardMaximum] alone would let a sender build a stream the television cannot present: a
     * decoder that claims 4096x2304 can still be paired with a 30 Hz panel, and one that claims
     * 60 FPS at 4K can be paired with a firmware that only ever lights 1080p. So the raster is the
     * announced ceiling and the rate is the binding constraint of the decoder's own claim at that
     * raster and the panel's fastest mode. HEVC is never advertised — this receiver decodes H.264
     * only, and a sender that sees an `hevc` entry would switch codecs into a stream that cannot be
     * played at all.
     */
    fun h264Envelope(profile: DisplayProfile, forceUhd: Boolean = false): DecodeEnvelope {
        val raster = effectiveProfile(profile, forceUhd).decoderMaximum
        val decoderRate = if (raster.longEdge >= UHD.longEdge) {
            profile.avcUhdFrameRate
        } else {
            profile.avcMaxFrameRate
        }
        val bindingRate = listOfNotNull(decoderRate, panelRateFor(profile, raster)?.toDouble())
            .minOrNull()
            ?.roundToInt()
        return DecodeEnvelope(raster, bindingRate)
    }

    /**
     * The panel's fastest rate that actually covers [raster]. A firmware that enumerates
     * 3840x2160@30 alongside 1920x1080@60 must not have its 60 Hz mode quoted next to a 4K ceiling,
     * because the sender would read that as permission to stream 4K at 60 FPS. When no enumerated
     * mode reaches the raster — the case the 4K override exists for — the list's fastest rate is the
     * best evidence there is, and an empty list falls back to the active mode.
     */
    private fun panelRateFor(profile: DisplayProfile, raster: PixelSize): Int? {
        val covering = profile.supportedModes.filter {
            it.pixels.longEdge >= raster.longEdge && it.pixels.shortEdge >= raster.shortEdge
        }
        val hertz = covering.maxOfOrNull { it.refreshRateHz }
            ?: profile.supportedModes.maxOfOrNull { it.refreshRateHz }
            ?: profile.refreshRateHz.takeIf { it > 0.0 }
        return hertz?.let { it.roundToInt().coerceAtLeast(1) }
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
