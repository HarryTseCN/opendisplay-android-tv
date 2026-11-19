package io.github.mohithdas.opendisplay.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamStartPolicyTest {
    @Test
    fun aFreshConnectionWithNoStreamIsProbed() {
        assertTrue(
            StreamStartPolicy.shouldRequestKeyframe(
                attemptGeneration = 1,
                currentGeneration = 1,
                connected = true,
                videoHeadersSeen = false,
            ),
        )
    }

    @Test
    fun probingStopsAsSoonAsTheStreamAnnouncesItsParameterSets() {
        assertFalse(
            StreamStartPolicy.shouldRequestKeyframe(
                attemptGeneration = 1,
                currentGeneration = 1,
                connected = true,
                videoHeadersSeen = true,
            ),
        )
    }

    @Test
    fun probingStopsWhenThePeerIsGone() {
        assertFalse(
            StreamStartPolicy.shouldRequestKeyframe(
                attemptGeneration = 1,
                currentGeneration = 1,
                connected = false,
                videoHeadersSeen = false,
            ),
        )
    }

    @Test
    fun aReplacedConnectionCancelsThePendingProbesOfTheOldOne() {
        assertFalse(
            StreamStartPolicy.shouldRequestKeyframe(
                attemptGeneration = 1,
                currentGeneration = 2,
                connected = true,
                videoHeadersSeen = false,
            ),
        )
    }

    @Test
    fun probeScheduleIsBoundedAscendingAndShort() {
        val delays = StreamStartPolicy.keyframeRequestDelaysMs
        assertEquals(3, delays.size)
        assertTrue(delays.zipWithNext().all { (previous, next) -> next > previous })
        assertTrue(delays.first() >= 250L)
        assertTrue("the last probe must land well before a periodic keyframe", delays.last() <= 5_000L)
    }
}
