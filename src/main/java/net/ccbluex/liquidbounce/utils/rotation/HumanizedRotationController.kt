/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation

import net.minecraft.util.MathHelper
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Generates intermediate rotations without changing the requested endpoint.
 *
 * The controller deliberately owns no game or target state. Callers provide the
 * actual rotation from the previous tick, allowing sensitivity quantization to
 * remain authoritative.
 */
internal class HumanizedRotationController(
    private val random: Random = Random.Default,
) {

    private enum class Phase {
        IDLE,
        PRIMARY,
        CORRECTION,
        TRACKING,
    }

    private var phase = Phase.IDLE
    private var previousRotation: Rotation? = null
    private var previousTarget: Rotation? = null

    private var overshootYaw = 0f
    private var overshootPitch = 0f

    private var sampledHorizontalSpeed = 0f
    private var sampledVerticalSpeed = 0f

    private var phaseTicks = 0
    private var primaryTimeout = 0
    private var lastUpdateTick = Int.MIN_VALUE

    fun step(
        current: Rotation,
        target: Rotation,
        horizontalSpeed: ClosedFloatingPointRange<Float>,
        verticalSpeed: ClosedFloatingPointRange<Float>,
        minDifference: Float,
        gcd: Float,
        tick: Int,
    ): Rotation {
        if (isUpdateGap(tick)) {
            reset()
        }

        val targetJump = previousTarget?.let { rotationDifference(target, it) } ?: Float.POSITIVE_INFINITY
        val targetDistance = rotationDifference(target, current)

        if (phase == Phase.IDLE || targetJump > TARGET_DISCONTINUITY ||
            phase == Phase.TRACKING && targetDistance > TRACKING_REACQUIRE_DISTANCE
        ) {
            beginMovement(current, target, horizontalSpeed, verticalSpeed, gcd)
        }

        if (phase == Phase.PRIMARY && hasOvershoot && phaseTicks >= primaryTimeout) {
            beginCorrection()
        }

        val actualVelocity = previousRotation?.let { angleDifferences(current, it) } ?: Rotation.ZERO
        val workingTarget = workingTarget(target)
        val error = angleDifferences(workingTarget, current)
        val distance = hypot(error.yaw, error.pitch)
        val measuredSpeed = hypot(actualVelocity.yaw, actualVelocity.pitch)

        val settleDistance = max(gcd * SETTLE_GCD_MULTIPLIER, minDifference)
        val settleSpeed = max(gcd, minDifference * SETTLE_SPEED_MULTIPLIER)

        if (distance <= settleDistance && measuredSpeed <= settleSpeed) {
            return reachWorkingTarget(current, target, workingTarget, tick)
        }

        val speedMultiplier = if (phase == Phase.CORRECTION) CORRECTION_SPEED_MULTIPLIER else 1f
        val maxYawSpeed = max(gcd, sampledHorizontalSpeed * speedMultiplier)
        val maxPitchSpeed = max(gcd, sampledVerticalSpeed * speedMultiplier)
        val maxYawAcceleration = max(gcd, maxYawSpeed * ACCELERATION_LIMIT_MULTIPLIER)
        val maxPitchAcceleration = max(gcd, maxPitchSpeed * ACCELERATION_LIMIT_MULTIPLIER)

        val yawAcceleration = (POSITION_GAIN * error.yaw - VELOCITY_DAMPING * actualVelocity.yaw)
            .coerceIn(-maxYawAcceleration, maxYawAcceleration)
        val pitchAcceleration = (POSITION_GAIN * error.pitch - VELOCITY_DAMPING * actualVelocity.pitch)
            .coerceIn(-maxPitchAcceleration, maxPitchAcceleration)

        var yawVelocity = (actualVelocity.yaw + yawAcceleration).coerceIn(-maxYawSpeed, maxYawSpeed)
        var pitchVelocity = (actualVelocity.pitch + pitchAcceleration).coerceIn(-maxPitchSpeed, maxPitchSpeed)

        // Preserve the two-dimensional movement direction while enforcing both axis limits.
        val velocityScale = max(1f, hypot(yawVelocity / maxYawSpeed, pitchVelocity / maxPitchSpeed))

        yawVelocity /= velocityScale
        pitchVelocity /= velocityScale

        val next = Rotation(
            current.yaw + yawVelocity,
            (current.pitch + pitchVelocity).coerceIn(-90f, 90f),
        )
        val nextError = angleDifferences(workingTarget, next)
        val passedWorkingTarget = error.yaw * nextError.yaw + error.pitch * nextError.pitch <= 0f

        if (passedWorkingTarget) {
            return reachWorkingTarget(current, target, workingTarget, tick)
        }

        finishTick(current, target, tick)
        phaseTicks++

        return next
    }

    fun hold(current: Rotation, tick: Int) {
        previousRotation = current.copyRotation()
        lastUpdateTick = tick
    }

    fun reset() {
        phase = Phase.IDLE
        previousRotation = null
        previousTarget = null
        overshootYaw = 0f
        overshootPitch = 0f
        sampledHorizontalSpeed = 0f
        sampledVerticalSpeed = 0f
        phaseTicks = 0
        primaryTimeout = 0
        lastUpdateTick = Int.MIN_VALUE
    }

    private fun beginMovement(
        current: Rotation,
        target: Rotation,
        horizontalSpeed: ClosedFloatingPointRange<Float>,
        verticalSpeed: ClosedFloatingPointRange<Float>,
        gcd: Float,
    ) {
        phase = Phase.PRIMARY
        phaseTicks = 0
        sampledHorizontalSpeed = sample(horizontalSpeed).coerceAtLeast(gcd)
        sampledVerticalSpeed = sample(verticalSpeed).coerceAtLeast(gcd)

        val error = angleDifferences(target, current)
        val distance = hypot(error.yaw, error.pitch)
        val fastestAxis = max(sampledHorizontalSpeed, sampledVerticalSpeed)

        primaryTimeout = (ceil(distance / fastestAxis * PRIMARY_TIMEOUT_SPEED_FACTOR).toInt() +
                PRIMARY_TIMEOUT_BASE_TICKS).coerceIn(PRIMARY_TIMEOUT_MIN_TICKS, PRIMARY_TIMEOUT_MAX_TICKS)

        overshootYaw = 0f
        overshootPitch = 0f

        if (distance < OVERSHOOT_MIN_DISTANCE) {
            return
        }

        val distanceFactor = ((distance - OVERSHOOT_MIN_DISTANCE) / OVERSHOOT_CHANCE_DISTANCE)
            .coerceIn(0f, 1f)
        val chance = OVERSHOOT_BASE_CHANCE + distanceFactor * OVERSHOOT_ADDITIONAL_CHANCE

        if (random.nextFloat() >= chance) {
            return
        }

        val maximumMagnitude = min(OVERSHOOT_MAX_MAGNITUDE, distance * OVERSHOOT_MAX_DISTANCE_RATIO)

        if (maximumMagnitude < gcd) {
            return
        }

        val minimumMagnitude = min(maximumMagnitude, max(gcd, distance * OVERSHOOT_MIN_DISTANCE_RATIO))
        val magnitude = minimumMagnitude + (maximumMagnitude - minimumMagnitude) * random.nextFloat()

        overshootYaw = error.yaw / distance * magnitude
        overshootPitch = error.pitch / distance * magnitude
    }

    private fun beginCorrection() {
        phase = Phase.CORRECTION
        phaseTicks = 0
        overshootYaw = 0f
        overshootPitch = 0f
    }

    private fun reachWorkingTarget(
        current: Rotation,
        target: Rotation,
        workingTarget: Rotation,
        tick: Int,
    ): Rotation {
        val result = Rotation(
            current.yaw + MathHelper.wrapAngleTo180_float(workingTarget.yaw - current.yaw),
            workingTarget.pitch,
        )

        if (phase == Phase.PRIMARY && hasOvershoot) {
            beginCorrection()
        } else {
            phase = Phase.TRACKING
            phaseTicks = 0
        }

        // Treat the endpoint as a stop. The next update derives zero velocity
        // from this result before beginning a correction or tracking movement.
        previousRotation = result.copyRotation()
        previousTarget = target.copyRotation()
        lastUpdateTick = tick

        return result
    }

    private fun workingTarget(target: Rotation): Rotation {
        if (phase != Phase.PRIMARY || !hasOvershoot) {
            return target
        }

        return Rotation(
            target.yaw + overshootYaw,
            (target.pitch + overshootPitch).coerceIn(-90f, 90f),
        )
    }

    private fun finishTick(current: Rotation, target: Rotation, tick: Int) {
        previousRotation = current.copyRotation()
        previousTarget = target.copyRotation()
        lastUpdateTick = tick
    }

    private fun isUpdateGap(tick: Int) = lastUpdateTick != Int.MIN_VALUE &&
            (tick < lastUpdateTick || tick - lastUpdateTick > MAX_UPDATE_GAP_TICKS)

    private fun sample(range: ClosedFloatingPointRange<Float>): Float {
        val minimum = min(range.start, range.endInclusive)
        val maximum = max(range.start, range.endInclusive)

        return minimum + (maximum - minimum) * random.nextFloat()
    }

    private fun angleDifferences(target: Rotation, current: Rotation) = Rotation(
        MathHelper.wrapAngleTo180_float(target.yaw - current.yaw),
        target.pitch - current.pitch,
    )

    private fun rotationDifference(target: Rotation, current: Rotation): Float {
        val difference = angleDifferences(target, current)
        return hypot(difference.yaw, difference.pitch)
    }

    private fun Rotation.copyRotation() = Rotation(yaw, pitch)

    private val hasOvershoot
        get() = overshootYaw != 0f || overshootPitch != 0f

    private companion object {
        const val POSITION_GAIN = 0.32f
        const val VELOCITY_DAMPING = 0.70f
        const val ACCELERATION_LIMIT_MULTIPLIER = 0.35f
        const val CORRECTION_SPEED_MULTIPLIER = 0.55f

        const val SETTLE_GCD_MULTIPLIER = 1.5f
        const val SETTLE_SPEED_MULTIPLIER = 0.25f

        const val TARGET_DISCONTINUITY = 25f
        const val TRACKING_REACQUIRE_DISTANCE = 10f
        const val MAX_UPDATE_GAP_TICKS = 1

        const val OVERSHOOT_MIN_DISTANCE = 12f
        const val OVERSHOOT_BASE_CHANCE = 0.18f
        const val OVERSHOOT_ADDITIONAL_CHANCE = 0.17f
        const val OVERSHOOT_CHANCE_DISTANCE = 90f
        const val OVERSHOOT_MIN_DISTANCE_RATIO = 0.015f
        const val OVERSHOOT_MAX_DISTANCE_RATIO = 0.04f
        const val OVERSHOOT_MAX_MAGNITUDE = 2.25f

        const val PRIMARY_TIMEOUT_SPEED_FACTOR = 3f
        const val PRIMARY_TIMEOUT_BASE_TICKS = 10
        const val PRIMARY_TIMEOUT_MIN_TICKS = 12
        const val PRIMARY_TIMEOUT_MAX_TICKS = 80
    }
}
