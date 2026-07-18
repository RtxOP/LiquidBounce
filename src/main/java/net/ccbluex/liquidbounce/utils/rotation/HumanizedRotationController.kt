/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation

import net.minecraft.util.MathHelper
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Generates stateful intermediate rotations without changing the requested endpoint.
 *
 * Randomness is scoped to a movement: configured speed samples, one overshoot
 * selection, and one magnitude sample when that overshoot is activated. The
 * per-tick response itself is deterministic and follows the live target.
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
    private var previousRawVelocity = zeroRotation()
    private var previousTarget: Rotation? = null

    private var sampledHorizontalSpeed = 0f
    private var sampledVerticalSpeed = 0f
    private var movementInitialDistance = 0f

    private var overshootArmed = false
    private var overshootUsed = false
    private var overshootOffset = zeroRotation()

    private var stabilityAnchor: Rotation? = null
    private var stableIntervals = 0
    private var targetStable = false

    private var lastAlpha: Float? = null
    private var lastStepHadOvershoot = false
    private var lastUpdateTick = Int.MIN_VALUE

    internal val telemetryPhase
        get() = phase.name

    internal val telemetryHasOvershoot
        get() = lastStepHadOvershoot

    internal val telemetryAlpha
        get() = lastAlpha

    internal val telemetryTargetStable
        get() = targetStable

    internal val telemetryOvershootArmed
        get() = overshootArmed

    fun step(
        current: Rotation,
        target: Rotation,
        horizontalSpeed: ClosedFloatingPointRange<Float>,
        verticalSpeed: ClosedFloatingPointRange<Float>,
        gcd: Float,
        tick: Int,
    ): Rotation {
        val effectiveGcd = if (gcd.isFinite()) max(abs(gcd), MIN_EFFECTIVE_GCD) else MIN_EFFECTIVE_GCD

        if (isUpdateGap(tick)) {
            reset()
        }

        lastAlpha = null
        lastStepHadOvershoot = false

        val targetDistance = rotationDifference(target, current)
        val targetJump = previousTarget?.let { rotationDifference(target, it) } ?: Float.POSITIVE_INFINITY
        val shouldBeginMovement = targetDistance > FLOAT_EPSILON && (
                phase == Phase.IDLE || targetJump > TARGET_DISCONTINUITY ||
                        phase == Phase.TRACKING && targetDistance > TRACKING_REACQUIRE_DISTANCE
                )

        if (shouldBeginMovement) {
            beginMovement(current, target, horizontalSpeed, verticalSpeed, effectiveGcd)
        } else if (phase == Phase.IDLE && targetDistance <= FLOAT_EPSILON) {
            return remainAtTarget(current, target, tick)
        } else {
            val stabilityBroken = updateTargetStability(target, effectiveGcd)

            if (stabilityBroken && phase == Phase.PRIMARY && !hasActiveOvershoot) {
                overshootArmed = false
                phase = Phase.TRACKING
            }

            if (stabilityBroken && (hasActiveOvershoot || phase == Phase.CORRECTION)) {
                cancelIntoTracking()
            }
        }

        val actualVelocity = previousRotation?.let { angleDifferences(current, it) } ?: zeroRotation()
        val measuredSpeed = magnitude(actualVelocity)

        var workingTarget = workingTarget(target)
        var movement = calculateMovement(current, workingTarget, actualVelocity)

        val realError = angleDifferences(target, current)
        if (!targetStable && !hasActiveOvershoot &&
            wouldReach(realError, target, movement.proposed, inclusive = true)
        ) {
            lastAlpha = movement.alpha
            val guarded = guardUnconfirmedEndpoint(current, target, realError, effectiveGcd)
            finishTick(current, target, movement.rawVelocity, tick)
            return guarded
        }

        if (phase == Phase.PRIMARY && overshootArmed && !overshootUsed && !hasActiveOvershoot &&
            targetStable && wouldReach(realError, target, movement.proposed, inclusive = true)
        ) {
            if (activateOvershoot(target, realError, movementInitialDistance, movement.speed, effectiveGcd)) {
                workingTarget = workingTarget(target)
                movement = calculateMovement(current, workingTarget, actualVelocity)
                lastStepHadOvershoot = true
            } else {
                overshootArmed = false
            }
        } else if (hasActiveOvershoot) {
            lastStepHadOvershoot = true
        }

        lastAlpha = movement.alpha

        val workingError = angleDifferences(workingTarget, current)
        if (hasActiveOvershoot && wouldReach(
                workingError,
                workingTarget,
                movement.proposed,
                inclusive = true,
            )
        ) {
            val waypoint = exactTarget(current, workingTarget)
            beginCorrection(waypoint, target, tick)
            return waypoint
        }

        if (!hasActiveOvershoot && targetStable) {
            if (wouldReach(realError, target, movement.proposed, inclusive = true)) {
                return finishMovement(current, target, tick)
            }

            if (magnitude(realError) <= SETTLE_GCD_MULTIPLIER * effectiveGcd &&
                measuredSpeed <= SETTLE_SPEED_GCD_MULTIPLIER * effectiveGcd
            ) {
                return finishMovement(current, target, tick)
            }
        }

        finishTick(current, target, movement.rawVelocity, tick)
        return movement.proposed
    }

    fun hold(current: Rotation, tick: Int) {
        previousRotation = current.copyRotation()
        previousRawVelocity = zeroRotation()
        lastAlpha = null
        lastStepHadOvershoot = false
        lastUpdateTick = tick
    }

    fun reset() {
        phase = Phase.IDLE
        previousRotation = null
        previousRawVelocity = zeroRotation()
        previousTarget = null
        sampledHorizontalSpeed = 0f
        sampledVerticalSpeed = 0f
        movementInitialDistance = 0f
        clearOvershoot()
        stabilityAnchor = null
        stableIntervals = 0
        targetStable = false
        lastAlpha = null
        lastStepHadOvershoot = false
        lastUpdateTick = Int.MIN_VALUE
    }

    private fun beginMovement(
        current: Rotation,
        target: Rotation,
        horizontalSpeed: ClosedFloatingPointRange<Float>,
        verticalSpeed: ClosedFloatingPointRange<Float>,
        effectiveGcd: Float,
    ) {
        phase = Phase.PRIMARY
        previousRotation = current.copyRotation()
        previousRawVelocity = zeroRotation()
        sampledHorizontalSpeed = sample(horizontalSpeed).coerceAtLeast(effectiveGcd)
        sampledVerticalSpeed = sample(verticalSpeed).coerceAtLeast(effectiveGcd)
        movementInitialDistance = rotationDifference(target, current)

        overshootOffset = zeroRotation()
        overshootUsed = false
        overshootArmed = shouldArmOvershoot(movementInitialDistance)

        stabilityAnchor = target.copyRotation()
        stableIntervals = 0
        targetStable = false
    }

    private fun calculateMovement(
        current: Rotation,
        workingTarget: Rotation,
        actualVelocity: Rotation,
    ): Movement {
        val error = angleDifferences(workingTarget, current)
        val rawVelocity = limitLikeOrdinaryRotation(
            error,
            sampledHorizontalSpeed,
            sampledVerticalSpeed,
        )
        val rawChange = magnitude(rawVelocity - previousRawVelocity)
        val x = rawChange * ALPHA_ACCEL_SENSITIVITY
        val alpha = ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * x / (1f + x)
        val candidateVelocity = actualVelocity + (rawVelocity - actualVelocity) * alpha
        val limitedVelocity = limitLikeOrdinaryRotation(
            candidateVelocity,
            sampledHorizontalSpeed,
            sampledVerticalSpeed,
        )
        val proposed = Rotation(
            current.yaw + limitedVelocity.yaw,
            (current.pitch + limitedVelocity.pitch).coerceIn(-90f, 90f),
        )

        return Movement(rawVelocity, proposed, alpha, magnitude(limitedVelocity))
    }

    private fun updateTargetStability(target: Rotation, effectiveGcd: Float): Boolean {
        val anchor = stabilityAnchor
        if (anchor == null) {
            stabilityAnchor = target.copyRotation()
            stableIntervals = 0
            targetStable = false
            return false
        }

        if (rotationDifference(target, anchor) <= effectiveGcd * TARGET_STABILITY_GCD_MULTIPLIER) {
            stableIntervals = (stableIntervals + 1).coerceAtMost(REQUIRED_STABLE_INTERVALS)
            targetStable = stableIntervals >= REQUIRED_STABLE_INTERVALS
            return false
        }

        stabilityAnchor = target.copyRotation()
        stableIntervals = 0
        targetStable = false
        return true
    }

    private fun shouldArmOvershoot(distance: Float): Boolean {
        if (distance < OVERSHOOT_MIN_DISTANCE) {
            return false
        }

        val distanceFactor = ((distance - OVERSHOOT_MIN_DISTANCE) / OVERSHOOT_CHANCE_DISTANCE)
            .coerceIn(0f, 1f)
        val chance = OVERSHOOT_BASE_CHANCE + OVERSHOOT_ADDITIONAL_CHANCE * distanceFactor
        return random.nextFloat() < chance
    }

    private fun activateOvershoot(
        target: Rotation,
        realError: Rotation,
        initialDistance: Float,
        approachSpeed: Float,
        effectiveGcd: Float,
    ): Boolean {
        val realDistance = magnitude(realError)
        if (realDistance <= FLOAT_EPSILON) {
            return false
        }

        val maximumMagnitude = min(
            OVERSHOOT_MAX_MAGNITUDE,
            min(initialDistance * OVERSHOOT_DISTANCE_RATIO, approachSpeed * OVERSHOOT_SPEED_RATIO),
        )
        if (maximumMagnitude < effectiveGcd) {
            return false
        }

        val magnitude = maximumMagnitude * (
                OVERSHOOT_MIN_MAGNITUDE_FACTOR +
                        (1f - OVERSHOOT_MIN_MAGNITUDE_FACTOR) * random.nextFloat()
                )
        val requestedOffset = Rotation(
            realError.yaw / realDistance * magnitude,
            realError.pitch / realDistance * magnitude,
        )
        val waypoint = Rotation(
            target.yaw + requestedOffset.yaw,
            (target.pitch + requestedOffset.pitch).coerceIn(-90f, 90f),
        )
        val actualOffset = angleDifferences(waypoint, target)
        if (magnitude(actualOffset) < effectiveGcd) {
            return false
        }

        overshootOffset = actualOffset
        overshootArmed = false
        overshootUsed = true
        return true
    }

    private fun beginCorrection(waypoint: Rotation, target: Rotation, tick: Int) {
        phase = Phase.CORRECTION
        overshootArmed = false
        overshootOffset = zeroRotation()
        previousRotation = waypoint.copyRotation()
        previousRawVelocity = zeroRotation()
        previousTarget = target.copyRotation()
        lastUpdateTick = tick
    }

    private fun cancelIntoTracking() {
        phase = Phase.TRACKING
        overshootArmed = false
        overshootOffset = zeroRotation()
    }

    private fun finishMovement(current: Rotation, target: Rotation, tick: Int): Rotation {
        val result = exactTarget(current, target)
        phase = Phase.IDLE
        previousRotation = result.copyRotation()
        previousRawVelocity = zeroRotation()
        previousTarget = target.copyRotation()
        clearOvershoot()
        stabilityAnchor = target.copyRotation()
        stableIntervals = REQUIRED_STABLE_INTERVALS
        targetStable = true
        lastUpdateTick = tick
        return result
    }

    private fun remainAtTarget(current: Rotation, target: Rotation, tick: Int): Rotation {
        val result = exactTarget(current, target)
        previousRotation = result.copyRotation()
        previousRawVelocity = zeroRotation()
        previousTarget = target.copyRotation()
        stabilityAnchor = target.copyRotation()
        stableIntervals = REQUIRED_STABLE_INTERVALS
        targetStable = true
        lastUpdateTick = tick
        return result
    }

    private fun finishTick(current: Rotation, target: Rotation, rawVelocity: Rotation, tick: Int) {
        previousRotation = current.copyRotation()
        previousRawVelocity = rawVelocity.copyRotation()
        previousTarget = target.copyRotation()
        lastUpdateTick = tick
    }

    private fun clearOvershoot() {
        overshootArmed = false
        overshootUsed = false
        overshootOffset = zeroRotation()
    }

    private fun workingTarget(target: Rotation): Rotation {
        if (!hasActiveOvershoot) {
            return target
        }

        return Rotation(
            target.yaw + overshootOffset.yaw,
            (target.pitch + overshootOffset.pitch).coerceIn(-90f, 90f),
        )
    }

    private fun wouldReach(
        errorBefore: Rotation,
        target: Rotation,
        proposed: Rotation,
        inclusive: Boolean,
    ): Boolean {
        val errorAfter = angleDifferences(target, proposed)
        val dot = errorBefore.yaw * errorAfter.yaw + errorBefore.pitch * errorAfter.pitch
        return if (inclusive) dot <= 0f else dot < 0f
    }

    /**
     * Prevents filter momentum from becoming an unbounded, unselected pass of
     * the live target. Moving targets retain at most a sensitivity-scale lag.
     */
    private fun guardUnconfirmedEndpoint(
        current: Rotation,
        target: Rotation,
        error: Rotation,
        effectiveGcd: Float,
    ): Rotation {
        val distance = magnitude(error)
        if (distance <= effectiveGcd) {
            return exactTarget(current, target)
        }

        val guardedDistance = distance - effectiveGcd
        return Rotation(
            current.yaw + error.yaw / distance * guardedDistance,
            (current.pitch + error.pitch / distance * guardedDistance).coerceIn(-90f, 90f),
        )
    }

    private fun limitLikeOrdinaryRotation(
        delta: Rotation,
        horizontalSpeed: Float,
        verticalSpeed: Float,
    ): Rotation {
        val distance = magnitude(delta)
        if (distance <= FLOAT_EPSILON) {
            return zeroRotation()
        }

        val yawLimit = abs(delta.yaw / distance) * horizontalSpeed
        val pitchLimit = abs(delta.pitch / distance) * verticalSpeed
        return Rotation(
            delta.yaw.coerceIn(-yawLimit, yawLimit),
            delta.pitch.coerceIn(-pitchLimit, pitchLimit),
        )
    }

    private fun exactTarget(current: Rotation, target: Rotation) = Rotation(
        current.yaw + MathHelper.wrapAngleTo180_float(target.yaw - current.yaw),
        target.pitch.coerceIn(-90f, 90f),
    )

    private fun sample(range: ClosedFloatingPointRange<Float>): Float {
        val minimum = min(range.start, range.endInclusive)
        val maximum = max(range.start, range.endInclusive)
        if (minimum == maximum) {
            return minimum
        }
        return minimum + (maximum - minimum) * random.nextFloat()
    }

    private fun angleDifferences(target: Rotation, current: Rotation) = Rotation(
        MathHelper.wrapAngleTo180_float(target.yaw - current.yaw),
        target.pitch - current.pitch,
    )

    private fun rotationDifference(target: Rotation, current: Rotation) =
        magnitude(angleDifferences(target, current))

    private fun magnitude(rotation: Rotation) = hypot(rotation.yaw, rotation.pitch)

    private fun isUpdateGap(tick: Int) = lastUpdateTick != Int.MIN_VALUE &&
            (tick < lastUpdateTick || tick - lastUpdateTick > MAX_UPDATE_GAP_TICKS)

    private fun Rotation.copyRotation() = Rotation(yaw, pitch)

    private fun zeroRotation() = Rotation(0f, 0f)

    private val hasActiveOvershoot
        get() = overshootOffset.yaw != 0f || overshootOffset.pitch != 0f

    private data class Movement(
        val rawVelocity: Rotation,
        val proposed: Rotation,
        val alpha: Float,
        val speed: Float,
    )

    private companion object {
        const val FLOAT_EPSILON = 1e-6f
        const val MIN_EFFECTIVE_GCD = 1e-4f

        const val ALPHA_MIN = 0.50f
        const val ALPHA_MAX = 0.90f
        const val ALPHA_ACCEL_SENSITIVITY = 0.25f

        const val SETTLE_GCD_MULTIPLIER = 1.5f
        const val SETTLE_SPEED_GCD_MULTIPLIER = 1.5f
        const val TARGET_STABILITY_GCD_MULTIPLIER = 1f
        const val REQUIRED_STABLE_INTERVALS = 2

        const val TARGET_DISCONTINUITY = 25f
        const val TRACKING_REACQUIRE_DISTANCE = 10f
        const val MAX_UPDATE_GAP_TICKS = 1

        const val OVERSHOOT_MIN_DISTANCE = 8f
        const val OVERSHOOT_BASE_CHANCE = 0.12f
        const val OVERSHOOT_ADDITIONAL_CHANCE = 0.18f
        const val OVERSHOOT_CHANCE_DISTANCE = 40f
        const val OVERSHOOT_MAX_MAGNITUDE = 1.5f
        const val OVERSHOOT_DISTANCE_RATIO = 0.035f
        const val OVERSHOOT_SPEED_RATIO = 0.20f
        const val OVERSHOOT_MIN_MAGNITUDE_FACTOR = 0.50f
    }
}
