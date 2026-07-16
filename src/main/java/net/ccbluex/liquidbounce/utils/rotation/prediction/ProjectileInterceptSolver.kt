/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation.prediction

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

data class ProjectileIntercept(
    val yaw: Double,
    val pitch: Double,
    val flightTicks: Double,
    val relativeIntercept: MotionVector,
)

/**
 * Solves the low ballistic arc while iterating flight time against relative target motion.
 *
 * Minecraft drag is intentionally not modeled yet, matching the previous client formula. Failure is explicit so the
 * caller can fall back to a direct or current-position rotation without emitting NaN.
 */
object ProjectileInterceptSolver {

    fun solve(
        relativePosition: MotionVector,
        relativeVelocity: MotionVector,
        projectileSpeed: Double,
        gravity: Double,
        maximumIterations: Int = 6,
    ): ProjectileIntercept? {
        if (!projectileSpeed.isFinite() || !gravity.isFinite() || projectileSpeed <= 0.0 || gravity <= 0.0) {
            return null
        }

        var flightTicks = (hypot(relativePosition.x, relativePosition.z) / projectileSpeed).coerceIn(0.0, 100.0)

        for (iteration in 0 until maximumIterations.coerceIn(1, 12)) {
            val futurePosition = relativePosition + relativeVelocity * flightTicks
            val solution = solveStatic(futurePosition, projectileSpeed, gravity) ?: return null

            val nextFlightTicks = solution.flightTicks.coerceIn(0.0, 100.0)
            if (abs(nextFlightTicks - flightTicks) < 0.01) {
                flightTicks = nextFlightTicks
                break
            }

            flightTicks = (flightTicks + nextFlightTicks) * 0.5
        }

        val interceptPosition = relativePosition + relativeVelocity * flightTicks
        return solveStatic(interceptPosition, projectileSpeed, gravity)
    }

    private fun solveStatic(
        relativePosition: MotionVector,
        projectileSpeed: Double,
        gravity: Double,
    ): ProjectileIntercept? {
        val horizontalDistance = hypot(relativePosition.x, relativePosition.z)
        if (horizontalDistance <= 1.0e-9) return null

        val speedSquared = projectileSpeed * projectileSpeed
        val discriminant = speedSquared * speedSquared - gravity * (
            gravity * horizontalDistance * horizontalDistance + 2.0 * relativePosition.y * speedSquared
        )
        if (!discriminant.isFinite() || discriminant < 0.0) return null

        val pitchRadians = atan((speedSquared - sqrt(discriminant)) / (gravity * horizontalDistance))
        val horizontalSpeed = projectileSpeed * cos(pitchRadians)
        if (!horizontalSpeed.isFinite() || horizontalSpeed <= 1.0e-9) return null

        val flightTicks = horizontalDistance / horizontalSpeed
        if (!flightTicks.isFinite() || flightTicks !in 0.0..100.0) return null

        return ProjectileIntercept(
            yaw = Math.toDegrees(atan2(relativePosition.z, relativePosition.x)) - 90.0,
            pitch = -Math.toDegrees(pitchRadians),
            flightTicks = flightTicks,
            relativeIntercept = relativePosition,
        )
    }
}
