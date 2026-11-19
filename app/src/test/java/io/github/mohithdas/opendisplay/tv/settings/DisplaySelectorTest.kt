package io.github.mohithdas.opendisplay.tv.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySelectorTest {
    @Test
    fun androidTvAutoCapsFourKAtFullHdAndUsesOneX() {
        val result = DisplaySelector.select(profile(isTv = true), ResolutionChoice.AUTO, UiScaleChoice.AUTO)
        assertEquals(PixelSize(1920, 1080), result.pixels)
        assertEquals(1.0, result.scale, 0.0)
    }

    @Test
    fun phoneAutoUsesWindowAndSensibleDensity() {
        val profile = profile(isTv = false).copy(
            windowBounds = PixelSize(2400, 1080),
            androidDensity = 2.5,
        )
        val result = DisplaySelector.select(profile, ResolutionChoice.AUTO, UiScaleChoice.AUTO)
        assertEquals(PixelSize(2400, 1080), result.pixels)
        assertEquals(2.0, result.scale, 0.0)
    }

    @Test
    fun requestedHdFollowsPortraitOrientation() {
        val profile = profile(isTv = false).copy(windowBounds = PixelSize(1080, 1920))
        assertEquals(
            PixelSize(720, 1280),
            DisplaySelector.select(profile, ResolutionChoice.HD, UiScaleChoice.ONE).pixels,
        )
    }

    @Test
    fun unsupportedResolutionIsNotOffered() {
        val profile = profile(isTv = true).copy(
            physicalMode = PixelSize(1280, 720),
            decoderMaximum = PixelSize(1280, 720),
        )
        val choices = DisplaySelector.supportedChoices(profile)
        assertTrue(ResolutionChoice.HD in choices)
        assertFalse(ResolutionChoice.FULL_HD in choices)
    }

    @Test
    fun uhdIsOfferedOnlyWhenPanelAndDecoderBothReachIt() {
        val profile = profile(isTv = true)
        assertTrue(ResolutionChoice.UHD in DisplaySelector.supportedChoices(profile))
        assertFalse(
            ResolutionChoice.UHD in DisplaySelector.supportedChoices(
                profile.copy(decoderMaximum = PixelSize(1920, 1080)),
            ),
        )
    }

    @Test
    fun explicitUhdAnnouncesPhysicalPixels() {
        val result = DisplaySelector.select(
            profile(isTv = true),
            ResolutionChoice.UHD,
            UiScaleChoice.AUTO,
        )
        assertEquals(PixelSize(3840, 2160), result.pixels)
    }

    @Test
    fun aPersistedUhdChoiceIsStillScaledToTheDecoderCeiling() {
        val profile = profile(isTv = true).copy(decoderMaximum = PixelSize(1920, 1080))
        val result = DisplaySelector.select(profile, ResolutionChoice.UHD, UiScaleChoice.AUTO)
        assertEquals(PixelSize(1920, 1080), result.pixels)
    }

    @Test
    fun televisionPanelFactsSeparatePhysicalPixelsFromTheDesktop() {
        val facts = DisplaySelector.televisionPanelFacts(profile(isTv = true))
        assertEquals(PixelSize(3840, 2160), facts.pixels)
        assertEquals(PixelSize(1920, 1080), facts.points)
        assertEquals(2.0, facts.scale, 0.0)
    }

    @Test
    fun panelScaleIsOneWhenTheWindowAlreadyLightsEveryPixel() {
        val facts = DisplaySelector.televisionPanelFacts(
            profile(isTv = true).copy(windowBounds = PixelSize(3840, 2160)),
        )
        assertEquals(PixelSize(3840, 2160), facts.pixels)
        assertEquals(1.0, facts.scale, 0.0)
    }

    @Test
    fun panelFactsFollowAPortraitTelevisionWindow() {
        val facts = DisplaySelector.televisionPanelFacts(
            profile(isTv = true).copy(
                physicalMode = PixelSize(2160, 3840),
                windowBounds = PixelSize(1080, 1920),
            ),
        )
        assertEquals(PixelSize(2160, 3840), facts.pixels)
        assertEquals(PixelSize(1080, 1920), facts.points)
        assertEquals(2.0, facts.scale, 0.0)
    }

    @Test
    fun theOverrideUnlocksFourKWhenAndroidReportsOnlyFullHd() {
        val source = fullHdModeProfile()
        assertFalse(ResolutionChoice.UHD in DisplaySelector.supportedChoices(source))
        assertTrue(ResolutionChoice.UHD in DisplaySelector.supportedChoices(source, forceUhd = true))
    }

    @Test
    fun theOverrideAnnouncesFourKWithHiDpiPanelFacts() {
        val forced = DisplaySelector.effectiveProfile(fullHdModeProfile(), forceUhd = true)
        val announced = DisplaySelector.select(forced, ResolutionChoice.UHD, UiScaleChoice.AUTO)
        assertEquals(PixelSize(3840, 2160), announced.pixels)
        val facts = DisplaySelector.televisionPanelFacts(forced)
        assertEquals(PixelSize(3840, 2160), facts.pixels)
        assertEquals(PixelSize(1920, 1080), facts.points)
        assertEquals(2.0, facts.scale, 0.0)
    }

    @Test
    fun theOverrideLeavesAProfileThatAlreadyReachesFourKUntouched() {
        val source = profile(isTv = true)
        assertEquals(source, DisplaySelector.effectiveProfile(source, forceUhd = true))
    }

    @Test
    fun theOverrideDoesNotPromoteTheConservativeAutoChoice() {
        // The switch unlocks a menu entry; it must not silently re-decide what Auto announces, or
        // turning it on would change a session the user never asked to move to 4K.
        val forced = DisplaySelector.effectiveProfile(fullHdModeProfile(), forceUhd = true)
        val result = DisplaySelector.select(forced, ResolutionChoice.AUTO, UiScaleChoice.AUTO)
        assertEquals(PixelSize(1920, 1080), result.pixels)
    }

    @Test
    fun theOverrideFollowsAPortraitAppWindow() {
        val source = fullHdModeProfile().copy(windowBounds = PixelSize(1080, 1920))
        assertEquals(PixelSize(2160, 3840), DisplaySelector.effectiveProfile(source, forceUhd = true).physicalMode)
    }

    @Test
    fun theOverrideKeepsTheDetectedHardwareFactsForDiagnostics() {
        val source = fullHdModeProfile().copy(avcHardMaximum = PixelSize(4096, 2304), avcUhdFrameRate = 60.0)
        val forced = DisplaySelector.effectiveProfile(source, forceUhd = true)
        assertEquals(PixelSize(4096, 2304), forced.avcHardMaximum)
        assertEquals(60.0, forced.avcUhdFrameRate ?: 0.0, 0.0)
    }

    private fun fullHdModeProfile() = profile(isTv = true).copy(
        physicalMode = PixelSize(1920, 1080),
        windowBounds = PixelSize(1920, 1080),
        decoderMaximum = PixelSize(1920, 1080),
    )

    private fun profile(isTv: Boolean) = DisplayProfile(
        physicalMode = PixelSize(3840, 2160),
        windowBounds = PixelSize(1920, 1080),
        decoderMaximum = PixelSize(3840, 2160),
        isTelevision = isTv,
        androidDensity = 2.0,
    )
}

