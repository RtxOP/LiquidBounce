/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

import net.ccbluex.liquidbounce.utils.kotlin.RandomUtils
import kotlin.math.max

/**
 * Even-paced click cycle: `clicksPerSecond` 0..N clicks are placed into a
 * 20-tick window with deterministic equal spacing. Remainders are spread
 * one-by-one across the gaps so the cycle stays balanced under any CPS
 * value.
 *
 * Ported (interface adapted) from the nextgen branch's historical
 *   `src/main/kotlin/.../utils/clicking/pattern/patterns/StabilizedPattern.kt`
 * originally paired with the rolling-click-array ClickPattern API. Here it is
 * expressed via the [ClickMode.cacheClick] / [consumeClicks] interface so it
 * slots into the same pipeline as [Fatigue].
 */
class Stabilized : ClickMode("Stabilized") {

    private val cycle = IntArray(CYCLE_LENGTH_TICKS)
    private var cycleCursor: Int = 0
    private var cycleUpdateTimeMs: Long = 0L
    private var cachedClicks: Int = 0

    override fun create(): ClickMode = Stabilized()

    override fun cacheClick(active: Boolean, cps: IntRange) {
        if (!active) {
            cachedClicks = 0
            return
        }

        if (cycle.all { it == 0 } ||
            System.currentTimeMillis() - cycleUpdateTimeMs >= 1000L
        ) {
            refillCycle(cps)
            cycleUpdateTimeMs = System.currentTimeMillis()
            cycleCursor = 0
        }

        if (cycle[cycleCursor] > 0) {
            cycle[cycleCursor]--
            cachedClicks++
        }
        cycleCursor = (cycleCursor + 1) % CYCLE_LENGTH_TICKS
    }

    override fun consumeClicks(): Int {
        val clicks = cachedClicks
        cachedClicks = 0
        return clicks
    }

    override fun clearClicks() {
        cachedClicks = 0
    }

    override fun reset() {
        cycle.fill(0)
        cycleCursor = 0
        cycleUpdateTimeMs = 0L
        cachedClicks = 0
    }

    /**
     * Distributes `clicks` flags evenly across [cycle]. Steps forward by
     * `cycleLength / clicks`; the `cycleLength % clicks` remainder is
     * scattered one extra slot at a time so the distribution stays balanced.
     */
    private fun refillCycle(cps: IntRange) {
        cycle.fill(0)

        val clicks = RandomUtils.nextInt(cps.first, cps.last + 1)
        if (clicks <= 0) return

        val interval = CYCLE_LENGTH_TICKS / clicks
        var remainder = CYCLE_LENGTH_TICKS % clicks
        var index = 0

        repeat(clicks) {
            cycle[index % CYCLE_LENGTH_TICKS]++
            index += max(interval, 1)
            if (remainder > 0) {
                index++
                remainder--
            }
        }
    }

    companion object {
        private const val CYCLE_LENGTH_TICKS = 20
    }
}
