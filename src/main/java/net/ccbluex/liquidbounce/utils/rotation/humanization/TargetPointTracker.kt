/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation.humanization

import java.util.Random
import kotlin.math.min

/** Identifies a target independently from its changing world-space bounding box. */
data class TargetPointKey(val owner: Any, val targetId: Int)

/** Separates target-point state created under incompatible humanization profiles. */
data class TargetPointEpoch(val profile: HumanizationProfile)

/** Coordinates normalized to a bounding box, where each component is normally in [0, 1]. */
data class NormalizedTargetPoint(val x: Double, val y: Double, val z: Double)

/**
 * Retains one normalized target point while the same owner tracks the same target.
 *
 * World-space movement of a hitbox therefore moves the point without selecting a new scan cell every tick. A small
 * seeded acquisition offset and slow correlated drift are retained per key; no iid perturbation is applied to output.
 */
class TargetPointTracker(seed: Long) {

    private val seeder = Random(seed)
    private val states = LinkedHashMap<TargetPointKey, PointState>()

    fun reset() {
        states.clear()
    }

    fun release(key: TargetPointKey) {
        states.remove(key)
    }

    /** Replace an infeasible point with a known feasible projection from the adapter. */
    fun replace(
        key: TargetPointKey,
        point: NormalizedTargetPoint,
        horizontalRange: ClosedFloatingPointRange<Double>,
        verticalRange: ClosedFloatingPointRange<Double>,
        variation: Double,
        tick: Int,
        epoch: TargetPointEpoch? = null,
    ) {
        val spread = variation.coerceIn(0.0, 0.2)
        val horizontal = safeRange(horizontalRange).inset(spread)
        val vertical = safeRange(verticalRange).inset(spread)
        val random = Random(seeder.nextLong())

        if (states.size >= MAX_TRACKED_POINTS && key !in states) states.remove(states.keys.first())
        states[key] = PointState(
            point = point.clamp(horizontal, vertical),
            lastTick = tick,
            random = random,
            epoch = epoch,
            horizontalRange = horizontal,
            verticalRange = vertical,
            spread = spread,
        )
    }

    fun pointFor(
        key: TargetPointKey,
        fallback: NormalizedTargetPoint,
        horizontalRange: ClosedFloatingPointRange<Double>,
        verticalRange: ClosedFloatingPointRange<Double>,
        variation: Double,
        tick: Int,
        epoch: TargetPointEpoch? = null,
    ): NormalizedTargetPoint {
        val spread = variation.coerceIn(0.0, 0.2)
        val horizontal = safeRange(horizontalRange).inset(spread)
        val vertical = safeRange(verticalRange).inset(spread)

        val state = states[key]?.takeIf {
            it.epoch == epoch && it.spread == spread &&
                sameRange(it.horizontalRange, horizontal) && sameRange(it.verticalRange, vertical)
        } ?: run {
            states.remove(key)
            val created = Random(seeder.nextLong()).let { random ->
                PointState(
                    point = NormalizedTargetPoint(
                        fallback.x + random.nextGaussian() * spread,
                        fallback.y + random.nextGaussian() * spread * 0.6,
                        fallback.z + random.nextGaussian() * spread,
                    ).clamp(horizontal, vertical),
                    lastTick = tick,
                    random = random,
                    epoch = epoch,
                    horizontalRange = horizontal,
                    verticalRange = vertical,
                    spread = spread,
                )
            }

            if (states.size >= MAX_TRACKED_POINTS) states.remove(states.keys.first())
            states[key] = created
            created
        }

        if (tick > state.lastTick && spread > 0.0) {
            repeat((tick - state.lastTick).coerceAtMost(5)) {
                advance(state, horizontal, vertical, spread)
            }

            state.lastTick = tick
        }

        return state.point.clamp(horizontal, vertical).also { state.point = it }
    }

    private fun advance(
        state: PointState,
        horizontal: ClosedFloatingPointRange<Double>,
        vertical: ClosedFloatingPointRange<Double>,
        spread: Double,
    ) {
        state.velocityX = state.velocityX * 0.82 + state.random.nextGaussian() * spread * 0.012
        state.velocityY = state.velocityY * 0.82 + state.random.nextGaussian() * spread * 0.007
        state.velocityZ = state.velocityZ * 0.82 + state.random.nextGaussian() * spread * 0.012

        val moved = NormalizedTargetPoint(
            state.point.x + state.velocityX,
            state.point.y + state.velocityY,
            state.point.z + state.velocityZ,
        )
        val clamped = moved.clamp(horizontal, vertical)

        if (moved.x != clamped.x) state.velocityX *= -0.35
        if (moved.y != clamped.y) state.velocityY *= -0.35
        if (moved.z != clamped.z) state.velocityZ *= -0.35
        state.point = clamped
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
        val start = range.start.takeIf { it.isFinite() }?.coerceIn(-0.5, 1.5) ?: 0.0
        val end = range.endInclusive.takeIf { it.isFinite() }?.coerceIn(start, 1.5) ?: start
        return start..end
    }

    private fun sameRange(
        first: ClosedFloatingPointRange<Double>,
        second: ClosedFloatingPointRange<Double>,
    ) = first.start == second.start && first.endInclusive == second.endInclusive

    private fun ClosedFloatingPointRange<Double>.inset(variation: Double): ClosedFloatingPointRange<Double> {
        if (variation <= 0.0) return this

        val inset = min(0.025, (endInclusive - start) * 0.15)
        return (start + inset)..(endInclusive - inset)
    }

    private data class PointState(
        var point: NormalizedTargetPoint,
        var lastTick: Int,
        val random: Random,
        val epoch: TargetPointEpoch?,
        val horizontalRange: ClosedFloatingPointRange<Double>,
        val verticalRange: ClosedFloatingPointRange<Double>,
        val spread: Double,
        var velocityX: Double = 0.0,
        var velocityY: Double = 0.0,
        var velocityZ: Double = 0.0,
    )

    private companion object {
        const val MAX_TRACKED_POINTS = 32
    }
}
