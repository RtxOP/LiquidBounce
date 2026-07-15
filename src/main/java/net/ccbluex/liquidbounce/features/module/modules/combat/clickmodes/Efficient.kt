/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

/**
 * Pace-clamped even spacing — click at every other tick (`i * 2 % 20`).
 * Below 10 CPS the spacing produces wide gaps, so we fall back to the
 * [stabilizedFill] layout in that case.
 */
class Efficient : CycleClickMode("Efficient") {
    override fun refillCycle(clicks: Int) {
        if (clicks < 10) {
            stabilizedFill(cycle, clicks)
            return
        }
        for (i in 0 until clicks) {
            cycle[i * 2 % patternLength]++
        }
    }
}
