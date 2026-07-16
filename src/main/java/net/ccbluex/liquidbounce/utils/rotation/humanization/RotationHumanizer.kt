/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation.humanization

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.sin

/** A Minecraft-independent angle pair. Yaw is kept unwrapped internally. */
data class AnglePoint(val yaw: Double, val pitch: Double) {
    operator fun plus(other: AnglePoint) = AnglePoint(yaw + other.yaw, pitch + other.pitch)
    operator fun minus(other: AnglePoint) = AnglePoint(yaw - other.yaw, pitch - other.pitch)
    operator fun times(scale: Double) = AnglePoint(yaw * scale, pitch * scale)
}

data class HumanizationStep(
    val rotation: AnglePoint,
    val phase: MovementPhase,
    val movementId: Long,
    val complete: Boolean,
)

enum class MovementPhase {
    PRIMARY,
    OVERSHOOT,
    CORRECTION,
    SETTLE,
    TRACKING,
    COMPLETE,
}

/**
 * Stateful cubic trajectory generator sampled once per logical game tick.
 *
 * Geometry and timing are separate: a cubic defines the path while minimum-jerk progress defines motion along it.
 * Small goal changes update the terminal part of the curve instead of restarting the movement.
 */
class RotationHumanizer(seed: Long) {

    private val random = Random(seed)

    var seed: Long = seed
        private set

    private var movementId = 0L
    private var active = false
    private var start = AnglePoint(0.0, 0.0)
    private var control1 = start
    private var control2 = start
    private var goal = start
    private var finalGoal = start
    private var lastPlanned = start
    private var previousPlanned = start
    private var previousInput: AnglePoint? = null
    private var velocity = AnglePoint(0.0, 0.0)
    private var targetVelocity = AnglePoint(0.0, 0.0)
    private var lastTarget = start
    private var stableTargetTicks = 0
    private var tick = 0
    private var durationTicks = 1
    private var plannedYawSpeed = 180.0
    private var plannedPitchSpeed = 180.0
    private var preferredYawSpeed = 180.0
    private var preferredPitchSpeed = 180.0
    private var yawAcceleration = 180.0
    private var pitchAcceleration = 180.0
    private var drift = 0.0
    private var profile = HumanizationProfile.OFF
    private var phase = MovementPhase.COMPLETE
    private var correctionsAllowed = false

    fun reset() {
        active = false
        tick = 0
        drift = 0.0
        phase = MovementPhase.COMPLETE
        previousInput = null
        velocity = AnglePoint(0.0, 0.0)
        targetVelocity = AnglePoint(0.0, 0.0)
        stableTargetTicks = 0
    }

    fun step(
        current: AnglePoint,
        requestedTarget: AnglePoint,
        maxYawSpeed: Double,
        maxPitchSpeed: Double,
        requestedProfile: HumanizationProfile,
        allowCorrections: Boolean = false,
    ): HumanizationStep {
        require(current.yaw.isFinite() && current.pitch.isFinite()) { "Current rotation must be finite" }
        require(requestedTarget.yaw.isFinite() && requestedTarget.pitch.isFinite()) { "Target rotation must be finite" }

        val target = AnglePoint(
            unwrapYaw(requestedTarget.yaw, current.yaw),
            requestedTarget.pitch.coerceIn(-90.0, 90.0),
        )

        val yawSpeed = max(abs(maxYawSpeed), 1.0e-6)
        val pitchSpeed = max(abs(maxPitchSpeed), 1.0e-6)

        if (!requestedProfile.enabled) {
            reset()
            return HumanizationStep(target, MovementPhase.COMPLETE, movementId, complete = true)
        }

        synchronizeVelocity(current)

        if (!active || requestedProfile != profile) {
            begin(current, target, yawSpeed, pitchSpeed, requestedProfile, allowCorrections)
        } else {
            updateLimits(yawSpeed, pitchSpeed, requestedProfile)
            correctionsAllowed = allowCorrections
            val targetDelta = updateTargetMotion(target)

            if (!allowCorrections && (phase == MovementPhase.OVERSHOOT || phase == MovementPhase.CORRECTION)) {
                phase = MovementPhase.TRACKING
            }

            if (phase == MovementPhase.COMPLETE || phase == MovementPhase.TRACKING) {
                if (phase == MovementPhase.COMPLETE && isStationary(targetDelta) &&
                    distanceBetween(current, target) <= ENDPOINT_EPSILON
                ) {
                    velocity = AnglePoint(0.0, 0.0)
                    lastPlanned = current
                    previousPlanned = current
                    return HumanizationStep(current, MovementPhase.COMPLETE, movementId, complete = true)
                }

                phase = MovementPhase.TRACKING
                return track(current, target)
            }

            retarget(target)
        }

        tick++
        val progress = (tick.toDouble() / durationTicks).coerceIn(0.0, 1.0)
        val time = minimumJerk(progress)
        var desired = cubic(start, control1, control2, goal, time)

        val direction = goal - start
        val length = hypot(direction.yaw, direction.pitch)
        if (length > 1.0e-9 && profile.driftScale > 0.0 && progress < 1.0) {
            val phaseDriftScale = if (phase == MovementPhase.CORRECTION) 0.25 else 1.0
            drift = drift * 0.72 + random.nextGaussian() * profile.driftScale * phaseDriftScale
            val envelope = sin(PI * progress)
            val normal = AnglePoint(-direction.pitch / length, direction.yaw / length)
            desired += normal * (drift * length * envelope)
        }

        desired = advanceDynamics(current, desired)
        desired = AnglePoint(desired.yaw, desired.pitch.coerceIn(-90.0, 90.0))
        velocity = desired - current

        previousPlanned = lastPlanned
        lastPlanned = desired

        val remainingToPhaseGoal = goal - desired
        val phaseGoalReached = progress >= 1.0 &&
            abs(remainingToPhaseGoal.yaw) <= 1.0e-6 &&
            abs(remainingToPhaseGoal.pitch) <= 1.0e-6

        if (phase == MovementPhase.OVERSHOOT && phaseGoalReached) {
            val emittedPhase = phase
            beginCorrection(desired)
            return HumanizationStep(desired, emittedPhase, movementId, complete = false)
        }

        val remaining = finalGoal - desired
        val complete = phaseGoalReached && abs(remaining.yaw) <= 1.0e-6 && abs(remaining.pitch) <= 1.0e-6
        val emittedPhase = when {
            complete && stableTargetTicks >= STABLE_TARGET_TICKS -> {
                phase = MovementPhase.COMPLETE
                MovementPhase.COMPLETE
            }
            complete -> {
                phase = MovementPhase.TRACKING
                MovementPhase.TRACKING
            }
            progress >= 1.0 && phase == MovementPhase.PRIMARY -> MovementPhase.SETTLE
            else -> phase
        }

        return HumanizationStep(desired, emittedPhase, movementId, complete)
    }

    private fun begin(
        current: AnglePoint,
        target: AnglePoint,
        maxYawSpeed: Double,
        maxPitchSpeed: Double,
        requestedProfile: HumanizationProfile,
        allowCorrections: Boolean,
    ) {
        val incomingVelocity = velocity

        movementId++
        active = true
        profile = requestedProfile
        correctionsAllowed = allowCorrections
        start = current
        finalGoal = target
        goal = target
        lastPlanned = current
        previousPlanned = current
        previousInput = current
        tick = 0
        drift = 0.0
        lastTarget = target
        targetVelocity = AnglePoint(0.0, 0.0)
        stableTargetTicks = 1
        updateLimits(maxYawSpeed, maxPitchSpeed, requestedProfile)
        phase = MovementPhase.PRIMARY

        var delta = goal - start
        val distance = hypot(delta.yaw, delta.pitch)
        durationTicks = planDuration(delta, requestedProfile.minimumCurveTicks, 40)

        val correctionProbability = if (requestedProfile.correctionTendency >= 1.0) {
            1.0
        } else {
            requestedProfile.correctionTendency * (distance / (distance + 30.0)) *
                ((durationTicks - 2) / 6.0).coerceIn(0.0, 1.0)
        }
        if (allowCorrections && distance >= 15.0 && durationTicks >= 4 &&
            random.nextDouble() < correctionProbability
        ) {
            val magnitude = minOf(2.0, distance * requestedProfile.overshootScale) *
                (0.6 + random.nextDouble() * 0.4)
            val direction = AnglePoint(delta.yaw / distance, delta.pitch / distance)
            goal = AnglePoint(
                target.yaw + direction.yaw * magnitude,
                (target.pitch + direction.pitch * magnitude).coerceIn(-90.0, 90.0),
            )
            delta = goal - start
            phase = MovementPhase.OVERSHOOT
        }

        if (distance <= 1.0e-9 || durationTicks < requestedProfile.minimumCurveTicks) {
            control1 = start + delta * (1.0 / 3.0)
            control2 = start + delta * (2.0 / 3.0)
            return
        }

        val pathDistance = hypot(delta.yaw, delta.pitch)
        val normal = AnglePoint(-delta.pitch / pathDistance, delta.yaw / pathDistance)
        val side = if (random.nextBoolean()) 1.0 else -1.0
        val variation = distance * requestedProfile.pathVariation * (0.55 + random.nextDouble() * 0.45) * side
        control1 = start + delta * (1.0 / 3.0) + normal * variation + incomingVelocity
        control2 = start + delta * (2.0 / 3.0) + normal * (variation * (0.35 + random.nextDouble() * 0.25))
    }

    /** Update the terminal geometry without changing elapsed curve time or restarting velocity. */
    private fun retarget(target: AnglePoint) {
        val delta = target - finalGoal
        if (abs(delta.yaw) < 1.0e-6 && abs(delta.pitch) < 1.0e-6) return

        control1 += delta * 0.15
        control2 += delta * 0.65
        finalGoal = target
        goal += delta
    }

    private fun beginCorrection(current: AnglePoint) {
        val incomingVelocity = lastPlanned - previousPlanned
        start = current
        goal = finalGoal
        tick = 0
        drift = 0.0
        phase = MovementPhase.CORRECTION

        val delta = goal - start
        durationTicks = planDuration(delta, minimumTicks = 2, maximumTicks = 8)
        control1 = start + delta * (1.0 / 3.0) + incomingVelocity * 0.35
        control2 = start + delta * (2.0 / 3.0)
        previousPlanned = current
        lastPlanned = current
    }

    private fun track(current: AnglePoint, target: AnglePoint): HumanizationStep {
        val error = target - current
        val desiredVelocity = AnglePoint(
            trackingVelocity(error.yaw, targetVelocity.yaw, preferredYawSpeed),
            trackingVelocity(error.pitch, targetVelocity.pitch, preferredPitchSpeed),
        )
        val targetIsStable = stableTargetTicks >= STABLE_TARGET_TICKS &&
            abs(targetVelocity.yaw) <= TARGET_MOTION_EPSILON &&
            abs(targetVelocity.pitch) <= TARGET_MOTION_EPSILON
        val nextVelocity = AnglePoint(
            approachTrackingAxis(error.yaw, velocity.yaw, desiredVelocity.yaw, yawAcceleration, plannedYawSpeed,
                targetIsStable),
            approachTrackingAxis(error.pitch, velocity.pitch, desiredVelocity.pitch, pitchAcceleration,
                plannedPitchSpeed, targetIsStable),
        )
        var next = AnglePoint(current.yaw + nextVelocity.yaw, current.pitch + nextVelocity.pitch)
        next = AnglePoint(next.yaw, next.pitch.coerceIn(-90.0, 90.0))
        velocity = next - current

        previousPlanned = lastPlanned
        lastPlanned = next

        val complete = targetIsStable && distanceBetween(next, target) <= ENDPOINT_EPSILON &&
            abs(velocity.yaw) <= ENDPOINT_EPSILON && abs(velocity.pitch) <= ENDPOINT_EPSILON

        phase = if (complete) MovementPhase.COMPLETE else MovementPhase.TRACKING
        if (complete) velocity = AnglePoint(0.0, 0.0)
        return HumanizationStep(next, phase, movementId, complete)
    }

    private fun synchronizeVelocity(current: AnglePoint) {
        previousInput?.let { previous ->
            velocity = AnglePoint(
                unwrapYaw(current.yaw, previous.yaw) - previous.yaw,
                current.pitch - previous.pitch,
            )
        }
        previousInput = current
    }

    private fun updateTargetMotion(target: AnglePoint): AnglePoint {
        val delta = target - lastTarget
        targetVelocity = AnglePoint(
            targetVelocity.yaw * TARGET_VELOCITY_MEMORY + delta.yaw * (1.0 - TARGET_VELOCITY_MEMORY),
            targetVelocity.pitch * TARGET_VELOCITY_MEMORY + delta.pitch * (1.0 - TARGET_VELOCITY_MEMORY),
        )
        lastTarget = target

        if (isStationary(delta)) stableTargetTicks++ else stableTargetTicks = 0
        return delta
    }

    private fun updateLimits(maxYawSpeed: Double, maxPitchSpeed: Double, requestedProfile: HumanizationProfile) {
        plannedYawSpeed = maxYawSpeed
        plannedPitchSpeed = maxPitchSpeed

        val (profileYawSpeed, profilePitchSpeed, accelerationRatio) = when (requestedProfile.mode) {
            HumanizationMode.SUBTLE -> Triple(42.0, 30.0, 0.34)
            HumanizationMode.BALANCED -> Triple(28.0, 20.0, 0.26)
            HumanizationMode.CUSTOM -> Triple(28.0, 20.0, 0.26)
            HumanizationMode.OFF -> Triple(maxYawSpeed, maxPitchSpeed, 1.0)
        }
        preferredYawSpeed = minOf(maxYawSpeed, profileYawSpeed / requestedProfile.responseScale)
        preferredPitchSpeed = minOf(maxPitchSpeed, profilePitchSpeed / requestedProfile.responseScale)
        yawAcceleration = minOf(maxYawSpeed, max(0.25, preferredYawSpeed * accelerationRatio))
        pitchAcceleration = minOf(maxPitchSpeed, max(0.2, preferredPitchSpeed * accelerationRatio))
    }

    private fun planDuration(delta: AnglePoint, minimumTicks: Int, maximumTicks: Int): Int {
        val distance = hypot(delta.yaw, delta.pitch)
        val speedTicks = max(
            abs(delta.yaw) * MINIMUM_JERK_PEAK / preferredYawSpeed,
            abs(delta.pitch) * MINIMUM_JERK_PEAK / preferredPitchSpeed,
        )
        val distanceTicks = 2.0 + ln(1.0 + distance / DURATION_DISTANCE_SCALE) / LN_2
        return max(minimumTicks, ceil(max(speedTicks, distanceTicks)).toInt()).coerceAtMost(maximumTicks)
    }

    private fun advanceDynamics(current: AnglePoint, desired: AnglePoint): AnglePoint {
        val error = desired - current
        val nextYawVelocity = approachAxis(
            error.yaw, velocity.yaw, preferredYawSpeed, plannedYawSpeed, yawAcceleration
        )
        val nextPitchVelocity = approachAxis(
            error.pitch, velocity.pitch, preferredPitchSpeed, plannedPitchSpeed, pitchAcceleration
        )

        return AnglePoint(
            current.yaw + nextYawVelocity,
            current.pitch + nextPitchVelocity,
        )
    }

    private fun approachAxis(
        error: Double,
        currentVelocity: Double,
        preferredSpeed: Double,
        hardSpeed: Double,
        acceleration: Double,
    ): Double {
        val brakingSpeed = sqrt(2.0 * acceleration * abs(error))
        val desiredVelocity = sign(error) * minOf(preferredSpeed, brakingSpeed)
        val nextVelocity = currentVelocity +
            (desiredVelocity - currentVelocity).coerceIn(-acceleration, acceleration)
        val boundedVelocity = nextVelocity.coerceIn(-hardSpeed, hardSpeed)

        return if (abs(error - currentVelocity) <= acceleration && abs(error) <= acceleration) {
            error
        } else {
            boundedVelocity
        }
    }

    private fun trackingVelocity(error: Double, targetMotion: Double, speed: Double): Double =
        (targetMotion + error * TRACKING_GAIN).coerceIn(-speed, speed)

    private fun approachTrackingAxis(
        error: Double,
        currentVelocity: Double,
        desiredVelocity: Double,
        acceleration: Double,
        hardSpeed: Double,
        targetIsStable: Boolean,
    ): Double {
        if (targetIsStable && abs(error) <= acceleration && abs(error - currentVelocity) <= acceleration) {
            return error
        }

        return (currentVelocity +
            (desiredVelocity - currentVelocity).coerceIn(-acceleration, acceleration))
            .coerceIn(-hardSpeed, hardSpeed)
    }

    private fun isStationary(delta: AnglePoint) =
        abs(delta.yaw) <= TARGET_MOTION_EPSILON && abs(delta.pitch) <= TARGET_MOTION_EPSILON

    private fun minimumJerk(value: Double): Double {
        val t2 = value * value
        val t3 = t2 * value
        return 10.0 * t3 - 15.0 * t3 * value + 6.0 * t3 * t2
    }

    private fun distanceBetween(first: AnglePoint, second: AnglePoint) =
        hypot(first.yaw - second.yaw, first.pitch - second.pitch)

    private fun cubic(p0: AnglePoint, p1: AnglePoint, p2: AnglePoint, p3: AnglePoint, t: Double): AnglePoint {
        val oneMinus = 1.0 - t
        val a = oneMinus * oneMinus * oneMinus
        val b = 3.0 * oneMinus * oneMinus * t
        val c = 3.0 * oneMinus * t * t
        val d = t * t * t
        return AnglePoint(
            a * p0.yaw + b * p1.yaw + c * p2.yaw + d * p3.yaw,
            a * p0.pitch + b * p1.pitch + c * p2.pitch + d * p3.pitch,
        )
    }

    companion object {
        private const val MINIMUM_JERK_PEAK = 1.875
        private const val DURATION_DISTANCE_SCALE = 8.0
        private const val LN_2 = 0.6931471805599453
        private const val TARGET_VELOCITY_MEMORY = 0.72
        private const val TRACKING_GAIN = 0.42
        private const val TARGET_MOTION_EPSILON = 0.02
        private const val ENDPOINT_EPSILON = 1.0e-6
        private const val STABLE_TARGET_TICKS = 3

        fun unwrapYaw(target: Double, reference: Double): Double {
            var difference = (target - reference) % 360.0
            if (difference <= -180.0) difference += 360.0
            if (difference > 180.0) difference -= 360.0
            return reference + difference
        }
    }
}
