/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

import kotlin.random.Random

/**
 * Each scheduled click fires twice at the same tick slot — mimics a hard-
 * jittery mouse-button "double click". A single tick slot may emit up to
 * `2 * N` clicks when `N` slots were scheduled.
 */
class DoubleClick : CycleClickMode("DoubleClick") {
    override fun refillCycle(clicks: Int) {
        repeat(clicks) {
            cycle[Random.Default.nextInt(cycle.size)] += 2
        }
    }
}
