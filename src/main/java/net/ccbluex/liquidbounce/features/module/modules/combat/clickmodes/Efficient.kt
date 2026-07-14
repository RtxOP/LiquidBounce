/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

import kotlin.math.max
import kotlin.random.Random

/**
 * Pace-clamped even spacing — click at every other tick (`i * 2 % 20`). At
 * CPS below 10 the spacing produces wide gaps; falls back to [Stabilized]'s
 * even-spacing fill in that case.
 *
 * Ported from nextgen's
 * `src/main/kotlin/.../utils/clicking/pattern/patterns/EfficientPattern.kt`.
 */
class Efficient : ClickMode("Efficient") {

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

        // Below 10 CPS, every-other-tick pacing opens wide gaps — fall back to
        // the stabilized spacing layout instead.
        if (clicks < 10) {
            stabilizedFill(cycle, clicks)
            return
        }

        for (i in 0 until clicks) {
            cycle[i * 2 % patternLength]++
        }
    }

    private fun stabilizedFill(target: IntArray, clicks: Int) {
        if (clicks <= 0) return
        val interval = target.size / clicks
        var remainder = target.size % clicks
        var currentIndex = 0
        repeat(clicks) {
            target[currentIndex % target.size]++
            currentIndex += max(interval, 1)
            if (remainder > 0) {
                currentIndex++
                remainder--
            }
        }
    }
}
