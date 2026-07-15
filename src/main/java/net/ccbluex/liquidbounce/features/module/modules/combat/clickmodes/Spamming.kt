/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

import kotlin.random.Random

/**
 * Random slot-fill — each click lands on a uniformly-picked tick in the
 * 20-tick window. Distinct from [Fatigue], which uses Bernoulli sampling
 * with a decreasing probability over the cycle.
 */
class Spamming : CycleClickMode("Spamming") {
    override fun refillCycle(clicks: Int) {
        repeat(clicks) {
            cycle[Random.Default.nextInt(cycle.size)]++
        }
    }
}
