package com.vrcosc.facetrack

import kotlin.math.PI
import kotlin.math.exp

/**
 * One Euro Filter (Casiez, Roussel, Vogel 2012 — "1€ Filter: A Simple Speed-based
 * Low-pass Filter for Noisy Input in Interactive Systems"). Public formula,
 * reimplemented from the algorithm description (not copied source).
 *
 * Tuned for: low jitter when the tracked point is nearly still (seated, resting
 * a hand on a desk), low lag when it's moving fast (a swung arm). That's the
 * exact trade-off full-body OSC tracking needs.
 *
 * minCutoff: lower = less jitter at rest, but more lag on fast motion.
 * beta: higher = cuts lag on fast motion faster, at the cost of a bit more
 *       jitter during that motion.
 */
class OneEuroFilter(
    private val minCutoff: Double = 1.0,
    private val beta: Double = 0.3,
    private val dCutoff: Double = 1.0
) {
    private var lastValue: Double? = null
    private var lastDerivative = 0.0
    private var lastTimestampNs: Long = 0L

    fun filter(value: Double, timestampNs: Long): Double {
        val prevValue = lastValue
        if (prevValue == null) {
            lastValue = value
            lastTimestampNs = timestampNs
            return value
        }

        val dt = ((timestampNs - lastTimestampNs).coerceAtLeast(1_000_000L)) / 1_000_000_000.0
        lastTimestampNs = timestampNs

        val dxRaw = (value - prevValue) / dt
        val dxSmoothed = lowPass(dxRaw, lastDerivative, alpha(dCutoff, dt))
        lastDerivative = dxSmoothed

        val cutoff = minCutoff + beta * kotlin.math.abs(dxSmoothed)
        val smoothed = lowPass(value, prevValue, alpha(cutoff, dt))
        lastValue = smoothed
        return smoothed
    }

    /** Resets filter memory — call when a joint reappears after being fully lost. */
    fun reset() {
        lastValue = null
        lastDerivative = 0.0
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    private fun lowPass(value: Double, prev: Double, a: Double): Double {
        return a * value + (1.0 - a) * prev
    }
}

/** Bundles 3 OneEuroFilters (x,y,z) for a single tracked 3D point. */
class Vec3Filter(minCutoff: Double = 1.0, beta: Double = 0.3) {
    private val fx = OneEuroFilter(minCutoff, beta)
    private val fy = OneEuroFilter(minCutoff, beta)
    private val fz = OneEuroFilter(minCutoff, beta)

    fun filter(x: Float, y: Float, z: Float, timestampNs: Long): FloatArray {
        return floatArrayOf(
            fx.filter(x.toDouble(), timestampNs).toFloat(),
            fy.filter(y.toDouble(), timestampNs).toFloat(),
            fz.filter(z.toDouble(), timestampNs).toFloat()
        )
    }

    fun reset() {
        fx.reset(); fy.reset(); fz.reset()
    }
}
