/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation.humanization

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/**
 * Stateful mouse-sensitivity quantizer with bounded error diffusion.
 *
 * Planning remains in continuous angle space. Sub-GCD movement is retained as residual error until enough commanded
 * motion exists to emit a representable mouse step. Residuals are discarded at a settled endpoint and on movement
 * handoff, preventing post-completion drift from stale error.
 */
class SensitivityQuantizer {

    private var yawResidual = 0.0
    private var pitchResidual = 0.0
    private var lastStep = Double.NaN
    private var lastDesired: AnglePoint? = null
    private var lastOutput: AnglePoint? = null

    fun reset() {
        yawResidual = 0.0
        pitchResidual = 0.0
        lastStep = Double.NaN
        lastDesired = null
        lastOutput = null
    }

    fun quantize(current: AnglePoint, desired: AnglePoint, step: Double): AnglePoint {
        require(current.yaw.isFinite() && current.pitch.isFinite()) { "Current rotation must be finite" }
        require(desired.yaw.isFinite() && desired.pitch.isFinite()) { "Desired rotation must be finite" }
        require(step.isFinite() && step > 0.0) { "Sensitivity step must be positive and finite" }

        if (!lastStep.isFinite() || abs(step - lastStep) > 1.0e-12) {
            resetFor(current, step)
        }

        val rebaseTolerance = max(1.0e-6, step * 1.0e-3)
        lastOutput?.let { output ->
            if (abs(wrappedDifference(current.yaw, output.yaw)) > rebaseTolerance ||
                abs(current.pitch - output.pitch) > rebaseTolerance
            ) {
                resetFor(current, step)
            }
        }

        val previousDesired = lastDesired ?: current
        val desiredPitch = desired.pitch.coerceIn(-90.0, 90.0)
        val yawCommand = wrappedDifference(desired.yaw, previousDesired.yaw)
        val pitchCommand = desiredPitch - previousDesired.pitch

        val yaw = quantizeAxis(yawCommand, yawResidual, step)
        val pitch = quantizeAxis(
            delta = pitchCommand,
            residual = pitchResidual,
            step = step,
            minimumApplied = -90.0 - current.pitch,
            maximumApplied = 90.0 - current.pitch,
        )
        yawResidual = yaw.residual
        pitchResidual = pitch.residual

        lastDesired = AnglePoint(desired.yaw, desiredPitch)
        return AnglePoint(
            current.yaw + yaw.applied,
            current.pitch + pitch.applied,
        ).also { lastOutput = it }
    }

    private fun resetFor(current: AnglePoint, step: Double) {
        yawResidual = 0.0
        pitchResidual = 0.0
        lastStep = step
        lastDesired = current
        lastOutput = current
    }

    private fun quantizeAxis(
        delta: Double,
        residual: Double,
        step: Double,
        minimumApplied: Double = Double.NEGATIVE_INFINITY,
        maximumApplied: Double = Double.POSITIVE_INFINITY,
    ): QuantizedAxis {
        if (abs(delta) <= 1.0e-12) return QuantizedAxis(0.0, 0.0)

        val accumulated = delta + residual
        val minimumSteps = if (minimumApplied.isFinite()) ceil(minimumApplied / step) else Double.NEGATIVE_INFINITY
        val maximumSteps = if (maximumApplied.isFinite()) floor(maximumApplied / step) else Double.POSITIVE_INFINITY
        val applied = round(accumulated / step).coerceIn(minimumSteps, maximumSteps) * step
        val nextResidual = (accumulated - applied).coerceIn(-step * 0.5, step * 0.5)
        return QuantizedAxis(applied, nextResidual)
    }

    private fun wrappedDifference(target: Double, current: Double): Double {
        var difference = (target - current) % 360.0
        if (difference <= -180.0) difference += 360.0
        if (difference > 180.0) difference -= 360.0
        return difference
    }

    private data class QuantizedAxis(val applied: Double, val residual: Double)
}
