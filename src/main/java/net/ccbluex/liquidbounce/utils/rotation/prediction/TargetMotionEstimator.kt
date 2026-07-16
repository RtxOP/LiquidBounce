/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation.prediction

import kotlin.math.sqrt

data class MotionVector(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: MotionVector) = MotionVector(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: MotionVector) = MotionVector(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = MotionVector(x * scale, y * scale, z * scale)
    operator fun div(scale: Double) = MotionVector(x / scale, y / scale, z / scale)

    val length: Double
        get() = sqrt(x * x + y * y + z * z)

    companion object {
        val ZERO = MotionVector(0.0, 0.0, 0.0)
    }
}

data class MotionPrediction(val offset: MotionVector, val velocity: MotionVector, val confidence: Double)

/**
 * Bounded per-entity motion history with confidence-aware extrapolation.
 *
 * Samples are accepted at most once per logical tick. Implausible displacement, stale history, or time reversal resets
 * the track rather than projecting a teleport. Prediction confidence ramps up over several consecutive samples.
 */
class TargetMotionEstimator(
    private val maximumTracks: Int = 128,
    private val teleportSpeed: Double = 6.0,
) {

    init {
        require(maximumTracks > 0) { "maximumTracks must be positive" }
        require(teleportSpeed > 0.0) { "teleportSpeed must be positive" }
    }

    private data class Track(
        var tick: Int,
        var position: MotionVector,
        var velocity: MotionVector,
        var samples: Int,
    )

    private val tracks = linkedMapOf<Int, Track>()

    fun clear() = tracks.clear()

    fun predict(
        entityId: Int,
        current: MotionVector,
        previous: MotionVector,
        tick: Int,
        horizonTicks: Double,
    ): MotionPrediction {
        val horizon = horizonTicks.coerceIn(0.0, 5.0)
        if (horizon == 0.0) {
            observe(entityId, current, previous, tick)
            return MotionPrediction(MotionVector.ZERO, MotionVector.ZERO, 1.0)
        }

        val track = observe(entityId, current, previous, tick)
        val confidence = (track.samples / 4.0).coerceIn(0.0, 1.0)
        val offset = track.velocity * (horizon * confidence)
        return MotionPrediction(offset, track.velocity, confidence)
    }

    private fun observe(entityId: Int, current: MotionVector, previous: MotionVector, tick: Int): Track {
        val existing = tracks[entityId]
        if (existing == null) {
            if (tracks.size >= maximumTracks) {
                tracks.remove(tracks.keys.first())
            }

            val initialVelocity = (current - previous).takeUnless { it.length > teleportSpeed } ?: MotionVector.ZERO
            return Track(tick, current, initialVelocity, samples = 1).also { tracks[entityId] = it }
        }

        val elapsed = tick - existing.tick
        if (elapsed == 0) return existing

        if (elapsed < 0 || elapsed > 5) {
            existing.tick = tick
            existing.position = current
            existing.velocity = MotionVector.ZERO
            existing.samples = 0
            return existing
        }

        val measuredVelocity = (current - existing.position) / elapsed.toDouble()
        if (measuredVelocity.length > teleportSpeed) {
            existing.tick = tick
            existing.position = current
            existing.velocity = MotionVector.ZERO
            existing.samples = 0
            return existing
        }

        val alpha = 0.45
        existing.velocity = existing.velocity * (1.0 - alpha) + measuredVelocity * alpha
        existing.position = current
        existing.tick = tick
        existing.samples = (existing.samples + 1).coerceAtMost(4)
        return existing
    }
}
