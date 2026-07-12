/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

import kotlin.math.max
import kotlin.random.Random

/**
 * Even-paced click scheduler. Distributes a random sample of clicks uniformly
 * across a 20-tick window: `interval = window / clicks`, with each gap growing
 * by 1 in turn until the remainder is consumed.
 *
 * Ported from the historical nextgen
 * `src/main/kotlin/.../utils/clicking/pattern/patterns/StabilizedPattern.kt`.
 */
class Stabilized : ClickMode("Stabilized") {

    private val patternLength = 20
    private val cycle = IntArray(patternLength)
    private var cycleCursor = 0
    private var cycleUpdateTime = 0L
    private var cachedClicks = 0

    override fun create() = Stabilized()

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

    override fun clearClicks() {
        cachedClicks = 0
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

        val clicks = Random.nextInt(cps.first, cps.last + 1)
        if (clicks <= 0) return

        val interval = patternLength / clicks
        var remainder = patternLength % clicks

        var currentIndex = 0
        repeat(clicks) {
            cycle[currentIndex % patternLength]++
            currentIndex += max(interval, 1)
            if (remainder > 0) {
                currentIndex++
                remainder--
            }
        }
    }
}
