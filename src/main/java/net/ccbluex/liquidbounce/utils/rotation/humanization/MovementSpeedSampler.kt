/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation.humanization

import kotlin.math.abs

data class AngularSpeedLimits(val yaw: Double, val pitch: Double)

/** Samples configured speed ranges once per movement while allowing explicit request overrides to refresh. */
class MovementSpeedSampler {

    private var sampledYaw: Double? = null
    private var sampledPitch: Double? = null

    fun reset() {
        sampledYaw = null
        sampledPitch = null
    }

    fun resolve(
        baseYaw: () -> Double,
        basePitch: () -> Double,
        overrideYaw: Double? = null,
        overridePitch: Double? = null,
        instant: Boolean = false,
    ): AngularSpeedLimits {
        if (instant) return AngularSpeedLimits(180.0, 180.0)

        val yaw = overrideYaw ?: sampledYaw ?: abs(baseYaw()).also { sampledYaw = it }
        val pitch = overridePitch ?: overrideYaw ?: sampledPitch ?: abs(basePitch()).also { sampledPitch = it }
        return AngularSpeedLimits(abs(yaw), abs(pitch))
    }
}
