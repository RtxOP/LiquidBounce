/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation.humanization

import kotlin.math.abs

data class RotationTelemetrySample(
    val tick: Int,
    val seed: Long,
    val owner: String,
    val purpose: String,
    val movementId: Long,
    val phase: MovementPhase,
    val deadlineStrategy: DeadlineStrategy,
    val source: AnglePoint,
    val target: AnglePoint,
    val planned: AnglePoint,
    val quantized: AnglePoint,
    val valid: Boolean,
    val yawVelocity: Double,
    val pitchVelocity: Double,
    val yawAcceleration: Double,
    val pitchAcceleration: Double,
    val yawJerk: Double,
    val pitchJerk: Double,
)

/** Bounded in-memory trace used only when the rotations debug option requests samples. */
class RotationTelemetry(private val capacity: Int = 512) {

    private val samples = ArrayDeque<RotationTelemetrySample>()
    private var previousRotation: AnglePoint? = null
    private var previousVelocity = AnglePoint(0.0, 0.0)
    private var previousAcceleration = AnglePoint(0.0, 0.0)

    init {
        require(capacity > 0) { "Telemetry capacity must be positive" }
    }

    fun record(
        tick: Int,
        seed: Long,
        owner: String,
        purpose: String,
        movementId: Long,
        phase: MovementPhase,
        deadlineStrategy: DeadlineStrategy,
        source: AnglePoint,
        target: AnglePoint,
        planned: AnglePoint,
        quantized: AnglePoint,
        valid: Boolean,
    ) {
        val velocity = previousRotation?.let {
            AnglePoint(wrappedDifference(quantized.yaw, it.yaw), quantized.pitch - it.pitch)
        } ?: AnglePoint(0.0, 0.0)
        val acceleration = velocity - previousVelocity
        val jerk = acceleration - previousAcceleration

        if (samples.size == capacity) samples.removeFirst()
        samples.addLast(
            RotationTelemetrySample(
                tick,
                seed,
                owner,
                purpose,
                movementId,
                phase,
                deadlineStrategy,
                source,
                target,
                planned,
                quantized,
                valid,
                velocity.yaw,
                velocity.pitch,
                acceleration.yaw,
                acceleration.pitch,
                jerk.yaw,
                jerk.pitch,
            )
        )

        previousRotation = quantized
        previousVelocity = velocity
        previousAcceleration = acceleration
    }

    fun snapshot(): List<RotationTelemetrySample> = samples.toList()

    fun latest(): RotationTelemetrySample? = samples.lastOrNull()

    fun clear() {
        samples.clear()
        previousRotation = null
        previousVelocity = AnglePoint(0.0, 0.0)
        previousAcceleration = AnglePoint(0.0, 0.0)
    }

    private fun wrappedDifference(target: Double, current: Double): Double {
        var difference = (target - current) % 360.0
        if (difference <= -180.0) difference += 360.0
        if (difference > 180.0) difference -= 360.0
        return if (abs(difference) <= 1.0e-12) 0.0 else difference
    }
}
