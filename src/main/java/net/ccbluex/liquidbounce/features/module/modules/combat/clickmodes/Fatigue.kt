/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

import net.ccbluex.liquidbounce.utils.kotlin.RandomUtils
import java.util.ArrayDeque

/**
 * Token-bucket click scheduler that distributes a Poisson-style click
 * pattern across a 20-tick window. Each refill, we sample one clicks-per-
 * second target, then Bernoulli-sample the remaining ticks with decreasing
 * probability so the cycle's total converges to the target.
 *
 * Post-click bookkeeping is delegated to [markClicked] /
 * [setClicksPerSecond] so [ClickMode.isWithinPostClickWindow] works the
 * same for every pattern.
 */
class Fatigue : ClickMode("Fatigue") {

    private val patternLength = 20
    private val pattern = ArrayDeque<Int>(patternLength)
    private var patternUpdateTime = 0L
    private var cachedClicks = 0

    override fun cacheClick(active: Boolean, cps: IntRange) {
        if (!active) {
            cachedClicks = 0
            return
        }

        if (pattern.isEmpty() || System.currentTimeMillis() - patternUpdateTime >= 1000L) {
            generatePattern(cps)
        }

        if (pattern.isNotEmpty() && pattern.removeFirst() == 1) {
            cachedClicks++
            markClicked()
        }
    }

    override fun consumeClicks(): Int {
        val clicks = cachedClicks
        cachedClicks = 0
        return clicks
    }

    override fun reset() {
        pattern.clear()
        patternUpdateTime = 0L
        cachedClicks = 0
        clearClickTracking()
    }

    private fun generatePattern(cps: IntRange) {
        pattern.clear()

        val sampled = Math.round(RandomUtils.nextDouble(cps.first.toDouble(), cps.last + 1.0)).toInt()
        setClicksPerSecond(sampled)
        if (sampled <= 0) {
            patternUpdateTime = System.currentTimeMillis()
            return
        }

        var remaining = sampled
        for (tick in 0 until patternLength) {
            val probability = remaining.toDouble() / (patternLength - tick)
            if (RandomUtils.nextDouble() < probability) {
                pattern.addLast(1)
                remaining--
            } else {
                pattern.addLast(0)
            }
        }
        patternUpdateTime = System.currentTimeMillis()
    }
}
