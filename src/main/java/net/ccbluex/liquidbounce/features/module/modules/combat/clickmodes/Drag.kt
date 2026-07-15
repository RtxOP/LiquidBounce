/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

import kotlin.random.Random

/**
 * Drag-clicking model. Each refill picks a `travelTime` (random 17..18)
 * that bounds which slots are eligible — the remaining slots stay 0 to
 * represent "lifting your finger back up." Within [0, travelTime), we
 * repeatedly increment the lowest-count slot until we've placed every
 * scheduled click.
 */
class Drag : CycleClickMode("Drag") {
    override fun refillCycle(clicks: Int) {
        val travelTime = Random.Default.nextInt(17, 19)
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
