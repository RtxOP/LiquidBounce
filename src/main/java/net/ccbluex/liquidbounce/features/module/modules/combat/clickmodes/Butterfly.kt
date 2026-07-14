/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

import kotlin.random.Random

/**
 * Butterfly-clicking model: fill empty per-tick slots with 1-2 clicks each,
 * falling back to randomly incrementing any slot once all are occupied.
 * Mimics two-finger butterfly click on a sensitive switch.
 *
 * Ported from nextgen's
 * `src/main/kotlin/.../utils/clicking/pattern/patterns/ButterflyPattern.kt`.
 */
class Butterfly : ClickMode("Butterfly") {

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

        // Mirror nextgen: loop until cumulative count meets target. Pick an
        // unoccupied slot when any exists; otherwise bump any slot by 1.
        var placed = 0
        val indices = cycle.indices.toIntArray()
        while (placed < clicks) {
            val empty = indices.filter { cycle[it] == 0 }
            if (empty.isNotEmpty()) {
                val idx = empty[Random.Default.nextInt(empty.size)]
                cycle[idx] = Random.Default.nextInt(1, 3)
                placed += cycle[idx]
            } else {
                val idx = indices[Random.Default.nextInt(indices.size)]
                cycle[idx]++
                placed++
            }
        }
    }
}
