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
import kotlin.math.max
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
    SETTLE,
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
    private var lastPlanned = start
    private var previousPlanned = start
    private var tick = 0
    private var durationTicks = 1
    private var plannedYawSpeed = 180.0
    private var plannedPitchSpeed = 180.0
    private var drift = 0.0
    private var profile = HumanizationProfile.OFF

    fun reset() {
        active = false
        tick = 0
        drift = 0.0
    }

    fun step(
        current: AnglePoint,
        requestedTarget: AnglePoint,
        maxYawSpeed: Double,
        maxPitchSpeed: Double,
        requestedProfile: HumanizationProfile,
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

        if (!active || requestedProfile != profile ||
            tick >= durationTicks && distanceBetween(target, goal) > max(yawSpeed, pitchSpeed) * 0.25
        ) {
            begin(current, target, yawSpeed, pitchSpeed, requestedProfile)
        } else {
            retarget(target)
        }

        tick++
        val progress = (tick.toDouble() / durationTicks).coerceIn(0.0, 1.0)
        val time = minimumJerk(progress)
        var desired = cubic(start, control1, control2, goal, time)

        val direction = goal - start
        val length = hypot(direction.yaw, direction.pitch)
        if (length > 1.0e-9 && profile.driftScale > 0.0 && progress < 1.0) {
            drift = drift * 0.72 + random.nextGaussian() * profile.driftScale
            val envelope = sin(PI * progress)
            val normal = AnglePoint(-direction.pitch / length, direction.yaw / length)
            desired += normal * (drift * length * envelope)
        }

        desired = limitFrom(current, desired, plannedYawSpeed, plannedPitchSpeed)
        desired = AnglePoint(desired.yaw, desired.pitch.coerceIn(-90.0, 90.0))

        previousPlanned = lastPlanned
        lastPlanned = desired

        val remaining = target - desired
        val complete = progress >= 1.0 && abs(remaining.yaw) <= plannedYawSpeed && abs(remaining.pitch) <= plannedPitchSpeed
        val phase = when {
            complete -> MovementPhase.COMPLETE
            progress >= 1.0 -> MovementPhase.SETTLE
            else -> MovementPhase.PRIMARY
        }

        return HumanizationStep(desired, phase, movementId, complete)
    }

    private fun begin(
        current: AnglePoint,
        target: AnglePoint,
        maxYawSpeed: Double,
        maxPitchSpeed: Double,
        requestedProfile: HumanizationProfile,
    ) {
        val incomingVelocity = if (active) lastPlanned - previousPlanned else AnglePoint(0.0, 0.0)

        movementId++
        active = true
        profile = requestedProfile
        start = current
        goal = target
        lastPlanned = current
        previousPlanned = current
        tick = 0
        drift = 0.0
        plannedYawSpeed = maxYawSpeed
        plannedPitchSpeed = maxPitchSpeed

        val delta = goal - start
        val distance = hypot(delta.yaw, delta.pitch)
        val speedTicks = max(abs(delta.yaw) / maxYawSpeed, abs(delta.pitch) / maxPitchSpeed)
        durationTicks = max(
            requestedProfile.minimumCurveTicks,
            ceil(speedTicks * requestedProfile.responseScale).toInt(),
        ).coerceAtMost(40)

        if (distance <= 1.0e-9 || durationTicks < requestedProfile.minimumCurveTicks) {
            control1 = start + delta * (1.0 / 3.0)
            control2 = start + delta * (2.0 / 3.0)
            return
        }

        val normal = AnglePoint(-delta.pitch / distance, delta.yaw / distance)
        val side = if (random.nextBoolean()) 1.0 else -1.0
        val variation = distance * requestedProfile.pathVariation * (0.55 + random.nextDouble() * 0.45) * side
        control1 = start + delta * (1.0 / 3.0) + normal * variation + incomingVelocity
        control2 = start + delta * (2.0 / 3.0) + normal * (variation * (0.35 + random.nextDouble() * 0.25))
    }

    /** Update mostly the terminal part of an active curve to avoid a full path restart for moving targets. */
    private fun retarget(target: AnglePoint) {
        val delta = target - goal
        if (abs(delta.yaw) < 1.0e-6 && abs(delta.pitch) < 1.0e-6) return

        control1 += delta * 0.15
        control2 += delta * 0.65
        goal = target

        val requiredTicks = ceil(
            max(abs((goal - lastPlanned).yaw) / plannedYawSpeed, abs((goal - lastPlanned).pitch) / plannedPitchSpeed)
        ).toInt()
        durationTicks = max(durationTicks, tick + requiredTicks).coerceAtMost(tick + 40)
    }

    private fun limitFrom(current: AnglePoint, desired: AnglePoint, yawSpeed: Double, pitchSpeed: Double): AnglePoint {
        val delta = desired - current
        return AnglePoint(
            current.yaw + delta.yaw.coerceIn(-yawSpeed, yawSpeed),
            current.pitch + delta.pitch.coerceIn(-pitchSpeed, pitchSpeed),
        )
    }

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
        fun unwrapYaw(target: Double, reference: Double): Double {
            var difference = (target - reference) % 360.0
            if (difference <= -180.0) difference += 360.0
            if (difference > 180.0) difference -= 360.0
            return reference + difference
        }
    }
}
