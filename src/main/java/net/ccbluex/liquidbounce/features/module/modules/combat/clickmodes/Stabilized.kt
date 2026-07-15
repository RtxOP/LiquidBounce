/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

/**
 * Even-paced click scheduler. The total clicks per cycle are picked once
 * uniformly across [cps], then distributed by [stabilizedFill].
 */
class Stabilized : CycleClickMode("Stabilized") {
    override fun refillCycle(clicks: Int) = stabilizedFill(cycle, clicks)
}
