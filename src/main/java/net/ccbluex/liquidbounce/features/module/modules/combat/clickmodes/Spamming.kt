/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

import kotlin.random.Random

/**
 * Random slot-fill — each click lands on a uniformly-picked tick in the 20-tick
 * window. Inject clicks one at a time at random positions; no uniformity
 * constraint.
 *
 * Ported from nextgen's
 * `src/main/kotlin/.../utils/clicking/pattern/patterns/SpammingPattern.kt`.
 *
 * Distinct from [Fatigue], which uses Bernoulli sampling with a decreasing
 * probability over the cycle window (Poisson-like). Spamming is just
 * "sprinkle `n` units uniformly — no time-correlated structure."
 */
class Spamming : ClickMode("Spamming") {

    private val patternLength = 20
    private val cycle = IntArray(patternLength)
    private var cycleCursor = 0
    private var cycleUpdateTime = 0L
    private var cachedClicks = 0

    override fun cacheClick(active: Boolean, cps: IntRange) {
        if (!active) {
            cachedClicks = 0
            return
        }

        val now = System.currentTimeMillis()
        if (cycleCursor == 0 || now - cycleUpdateTime >= 1000L) {
            refillCycle(cps)
            cycleUpdateTime = now
        }

        val pending = cycle[cycleCursor]
        cachedClicks = pending
        if (pending > 0) cycle[cycleCursor] = 0
        cycleCursor = (cycleCursor + 1) % patternLength
    }

    override fun consumeClicks(): Int {
        val clicks = cachedClicks
        cachedClicks = 0
        return clicks
    }

    override fun reset() {
        cycle.fill(0)
        cycleCursor = 0
        cycleUpdateTime = 0L
        cachedClicks = 0
    }

    private fun refillCycle(cps: IntRange) {
        cycle.fill(0)
        cycleCursor = 0

        val clicks = Random.Default.nextInt(cps.first, cps.last + 1)
        if (clicks <= 0) return

        repeat(clicks) {
            cycle[Random.Default.nextInt(cycle.size)]++
        }
    }
}
