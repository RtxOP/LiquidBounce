/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

import kotlin.random.Random

/**
 * Butterfly-clicking model: fill empty per-tick slots with 1 or 2 clicks
 * each; once everything is occupied, bump any slot by 1. Mimics two-finger
 * butterfly clicking on a sensitive switch.
 */
class Butterfly : CycleClickMode("Butterfly") {
    override fun refillCycle(clicks: Int) {
        // Pick an unoccupied slot when any exists; otherwise bump any slot
        // by 1. `cycle.indices.filter` indexes the underlying IntArray.
        var placed = 0
        while (placed < clicks) {
            val empty = cycle.indices.filter { cycle[it] == 0 }
            if (empty.isNotEmpty()) {
                val idx = empty[Random.Default.nextInt(empty.size)]
                cycle[idx] = Random.Default.nextInt(1, 3)
                placed += cycle[idx]
            } else {
                val idx = Random.Default.nextInt(cycle.size)
                cycle[idx]++
                placed++
            }
        }
    }
}
