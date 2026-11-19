package io.github.mohithdas.opendisplay.tv.net

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

internal object ListenerPortBinder {
    const val FIRST_PORT = 9010
    const val LAST_PORT = 9029

    @Throws(IOException::class)
    fun bindFirstAvailable(
        bindAddress: InetAddress,
        firstPort: Int = FIRST_PORT,
        lastPort: Int = LAST_PORT,
    ): ServerSocket {
        require(firstPort in 1..65535 && lastPort in firstPort..65535)
        var lastError: IOException? = null
        for (candidate in firstPort..lastPort) {
            val server = ServerSocket()
            server.reuseAddress = true
            try {
                server.bind(InetSocketAddress(bindAddress, candidate))
                return server
            } catch (error: IOException) {
                lastError = error
                try {
                    server.close()
                } catch (_: IOException) {
                }
            }
        }
        throw ListenerPortsUnavailableException(lastError)
    }
}

internal class ListenerPortsUnavailableException(cause: IOException?) :
    IOException("No listener port is available", cause)

internal object RetryBackoff {
    fun delayMillis(attempt: Int): Long {
        val exponent = attempt.coerceIn(0, 5)
        return (1_000L shl exponent).coerceAtMost(30_000L)
    }
}

internal object NsdRegistrationPolicy {
    fun canRegister(running: Boolean, activeListenerPort: Int?, requestedPort: Int): Boolean =
        running && activeListenerPort == requestedPort
}

/**
 * A sender sizes its display from `hello` and starts streaming as soon as the hello lands, so the
 * opening keyframe frequently arrives while the Activity is still binding the receiver service or
 * the SurfaceView decoder has not been attached yet. Frames before that point are dropped, and
 * because a receiver only rebuilds its decoder on a parameter-set change, a missed keyframe would
 * otherwise leave a black screen until the sender's own periodic IDR — up to a minute away.
 *
 * Bounded retries immediately after the hello close that gap without touching the steady-state
 * path: a stream that starts normally answers the first probe, and every probe is skipped as soon
 * as SPS/PPS have been seen.
 */
internal object StreamStartPolicy {
    val keyframeRequestDelaysMs = listOf(300L, 1_200L, 3_000L)

    fun shouldRequestKeyframe(
        attemptGeneration: Int,
        currentGeneration: Int,
        connected: Boolean,
        videoHeadersSeen: Boolean,
    ): Boolean = attemptGeneration == currentGeneration && connected && !videoHeadersSeen
}
