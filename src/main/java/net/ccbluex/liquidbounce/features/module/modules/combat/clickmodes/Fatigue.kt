/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

import net.ccbluex.liquidbounce.utils.kotlin.RandomUtils

/**
 * The legacy click-scheduling algorithm: a 20-tick rolling cycle in which
 * `clicksPerSecond` 0/1 flags are drawn for each slot using a random
 * proportional draw. Renamed from the previous inline `ClickPattern` to
 * expose it as an end-user mode alongside [Stabilized].
 *
 * Behaviour is identical to the pre-refactor implementation in:
 *   - `AutoClicker.kt` (token-bucket `ClickPattern`)
 *   - `KillAura.kt`  (same algorithm + `lastClickTime`/`clicksPerSecond`
 *     for the post-click window reported via [isWithinPostClickWindow])
 */
class Fatigue : ClickMode("Fatigue") {

    private val clickCycle: ArrayDeque<Int> = ArrayDeque()
    private var cycleUpdateTimeMs: Long = 0L
    private var cachedClicks: Int = 0
    private var lastClickTimeMs: Long = 0L
    private var clicksPerSecond: Int = 0

    override fun create(): ClickMode = Fatigue()

    override fun cacheClick(active: Boolean, cps: IntRange) {
        if (!active) {
            cachedClicks = 0
            return
        }

        if (shouldClickThisTick(cps)) {
            cachedClicks++
            lastClickTimeMs = System.currentTimeMillis()
        }
    }

    override fun consumeClicks(): Int {
        val clicks = cachedClicks
        cachedClicks = 0
        return clicks
    }

    override fun clearClicks() {
        cachedClicks = 0
    }

    override fun isWithinPostClickWindow(): Boolean {
        if (lastClickTimeMs == 0L || clicksPerSecond <= 0) {
            return false
        }
        return System.currentTimeMillis() - lastClickTimeMs <
            1000L / maxOf(1, clicksPerSecond) / 2L
    }

    override fun reset() {
        clickCycle.clear()
        cycleUpdateTimeMs = 0L
        cachedClicks = 0
        lastClickTimeMs = 0L
        clicksPerSecond = 0
    }

    private fun shouldClickThisTick(cps: IntRange): Boolean {
        if (clickCycle.isEmpty() ||
            System.currentTimeMillis() - cycleUpdateTimeMs >= 1000L
        ) {
            generateClickCycle(cps)
        }
        return clickCycle.removeFirst() == 1
    }

    private fun generateClickCycle(cps: IntRange) {
        clickCycle.clear()

        clicksPerSecond = Math.round(
            RandomUtils.nextDouble(cps.first.toDouble(), cps.last + 1.0)
        ).toInt()

        var clicksToDistribute = clicksPerSecond
        val totalTicks = TICKS_PER_SECOND

        for (tick in 0 until totalTicks) {
            val probability = clicksToDistribute.toDouble() / (totalTicks - tick)
            if (RandomUtils.nextDouble() < probability) {
                clickCycle.addLast(1)
                clicksToDistribute--
                continue
            }
            clickCycle.addLast(0)
        }

        cycleUpdateTimeMs = System.currentTimeMillis()
    }

    companion object {
        private const val TICKS_PER_SECOND = 20
    }
}
