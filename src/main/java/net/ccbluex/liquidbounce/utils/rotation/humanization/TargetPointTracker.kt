/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation.humanization

import java.util.Random

/** Identifies a target independently from its changing world-space bounding box. */
data class TargetPointKey(val owner: Any, val targetId: Int)

/** Coordinates normalized to a bounding box, where each component is normally in [0, 1]. */
data class NormalizedTargetPoint(val x: Double, val y: Double, val z: Double)

/**
 * Retains one normalized target point while the same owner tracks the same target.
 *
 * World-space movement of a hitbox therefore moves the point without selecting a new scan cell every tick. A small
 * seeded acquisition offset is allowed, but no per-tick random output is produced here.
 */
class TargetPointTracker(seed: Long) {

    private val random = Random(seed)
    private var activeKey: TargetPointKey? = null
    private var activePoint: NormalizedTargetPoint? = null

    fun reset() {
        activeKey = null
        activePoint = null
    }

    fun pointFor(
        key: TargetPointKey,
        fallback: NormalizedTargetPoint,
        horizontalRange: ClosedFloatingPointRange<Double>,
        verticalRange: ClosedFloatingPointRange<Double>,
        variation: Double,
    ): NormalizedTargetPoint {
        val horizontal = safeRange(horizontalRange)
        val vertical = safeRange(verticalRange)

        if (activeKey != key || activePoint == null) {
            val spread = variation.coerceIn(0.0, 0.2)
            activePoint = NormalizedTargetPoint(
                fallback.x + random.nextGaussian() * spread,
                fallback.y + random.nextGaussian() * spread * 0.6,
                fallback.z + random.nextGaussian() * spread,
            )
            activeKey = key
        }

        return activePoint!!.clamp(horizontal, vertical).also { activePoint = it }
    }

    private fun NormalizedTargetPoint.clamp(
        horizontal: ClosedFloatingPointRange<Double>,
        vertical: ClosedFloatingPointRange<Double>,
    ) = NormalizedTargetPoint(
        x.coerceIn(horizontal.start, horizontal.endInclusive),
        y.coerceIn(vertical.start, vertical.endInclusive),
        z.coerceIn(horizontal.start, horizontal.endInclusive),
    )

    private fun safeRange(range: ClosedFloatingPointRange<Double>): ClosedFloatingPointRange<Double> {
        val start = range.start.coerceIn(0.0, 1.0)
        val end = range.endInclusive.coerceIn(start, 1.0)
        return start..end
    }
}
