/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation.humanization

import kotlin.math.abs

enum class DeadlineStrategy {
    NORMAL,
    DETERMINISTIC,
    DIRECT,
}

/** Chooses the least invasive fallback that can still satisfy an action deadline. */
object RotationDeadlinePolicy {

    fun choose(
        current: AnglePoint,
        target: AnglePoint,
        maxYawStep: Double,
        maxPitchStep: Double,
        sensitivityStep: Double,
        deadlineReached: Boolean,
    ): DeadlineStrategy {
        if (!deadlineReached) return DeadlineStrategy.NORMAL

        val yawDifference = abs(wrappedDifference(target.yaw, current.yaw))
        val pitchDifference = abs(target.pitch - current.pitch)
        val tolerance = sensitivityStep.coerceAtLeast(0.0)

        return if (yawDifference <= abs(maxYawStep) + tolerance &&
            pitchDifference <= abs(maxPitchStep) + tolerance
        ) {
            DeadlineStrategy.DIRECT
        } else {
            DeadlineStrategy.DETERMINISTIC
        }
    }

    private fun wrappedDifference(target: Double, current: Double): Double {
        var difference = (target - current) % 360.0
        if (difference <= -180.0) difference += 360.0
        if (difference > 180.0) difference -= 360.0
        return difference
    }
}
