/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

import kotlin.random.Random

/**
 * Drag clicking model — fits the cycle's click count into the first
 * `travelTime` ticks (17 in nextgen's default) by repeatedly picking the
 * lowest-count slot in that prefix and incrementing it. Mimics the
 * top-to-bottom finger drag that produces friction clicks.
 *
 * Ported from nextgen's
 * `src/main/kotlin/.../utils/clicking/pattern/patterns/DragPattern.kt`.
 */
class Drag : ClickMode("Drag") {

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

        // next-gen default: travel-time window of 17 ticks. The remaining
        // ticks in the 20-window are left at 0 (the "lift your finger back
        // up" gap).
        val travelTime = 17

        var placed = 0
        while (placed < clicks) {
            var lowestIndex = 0
            var lowestValue = cycle[0]
            for (i in 1 until travelTime) {
                if (cycle[i] < lowestValue) {
                    lowestValue = cycle[i]
                    lowestIndex = i
                }
            }
            cycle[lowestIndex]++
            placed++
        }
    }
}
