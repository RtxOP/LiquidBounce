/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

import net.ccbluex.liquidbounce.utils.kotlin.RandomUtils
import java.util.ArrayDeque
import kotlin.math.max

/**
 * Token-bucket click scheduler that distributes a Poisson-style click pattern
 * across a 20-tick window. Each tick, [cacheClick] consumes a 0/1 cell from the
 * pre-generated deque; [consumeClicks] reports whether that cell was 1.
 *
 * Renamed from the legacy in-module `ClickPattern` to `Fatigue` so users can pick
 * it alongside [Stabilized] from the click-method choice in AutoClicker / KillAura.
 *
 * Includes last-click bookkeeping so [isWithinPostClickWindow] can be answered
 * (AutoClicker doesn't need it, but KillAura does — kept on the shared class).
 */
class Fatigue : ClickMode("Fatigue") {

    private val patternLength = 20
    private val pattern = ArrayDeque<Int>(patternLength)
    private var patternUpdateTime = 0L
    private var cachedClicks = 0
    private var lastClickTime = 0L
    private var clicksPerSecond = 0

    override fun cacheClick(active: Boolean, cps: IntRange) {
        if (!active) {
            cachedClicks = 0
            return
        }

        if (pattern.isEmpty() || System.currentTimeMillis() - patternUpdateTime >= 1000L) {
            generatePattern(cps)
        }

        if (!pattern.isEmpty() && pattern.removeFirst() == 1) {
            cachedClicks++
            lastClickTime = System.currentTimeMillis()
        }
    }

    override fun consumeClicks(): Int {
        val clicks = cachedClicks
        cachedClicks = 0
        return clicks
    }

    override fun isWithinPostClickWindow(): Boolean {
        if (lastClickTime == 0L || clicksPerSecond <= 0) return false
        return System.currentTimeMillis() - lastClickTime < 1000L / max(1, clicksPerSecond) / 2L
    }

    override fun reset() {
        pattern.clear()
        patternUpdateTime = 0L
        cachedClicks = 0
        lastClickTime = 0L
        clicksPerSecond = 0
    }

    private fun generatePattern(cps: IntRange) {
        pattern.clear()

        clicksPerSecond = Math.round(RandomUtils.nextDouble(cps.first.toDouble(), cps.last + 1.0)).toInt()
        if (clicksPerSecond <= 0) {
            patternUpdateTime = System.currentTimeMillis()
            return
        }

        var remaining = clicksPerSecond
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
