/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

/**
 * A click scheduling algorithm. Each implementation owns its tick-cycle state
 * (pattern buffer, counters, timers). Consumers look up by name through
 * [clickModeByName] and keep their own per-stream instance alive for as long
 * as the module is enabled.
 */
abstract class ClickMode(val modeName: String) {
    /** Called once per game tick when [active] the consumer wants to click. */
    abstract fun cacheClick(active: Boolean, cps: IntRange)

    /** Returns and clears the clicks the algorithm chose to fire this tick. */
    abstract fun consumeClicks(): Int

    /**
     * True if we are inside half a click-interval after the last fire.
     * Only consumed by KillAura for its post-click window. Defaults false.
     */
    open fun isWithinPostClickWindow(): Boolean = false

    /** Drop all pattern/counter state (call on module disable). */
    abstract fun reset()
}

/**
 * Registry of click scheduling algorithms. Each entry is a fresh-instance
 * factory rather than a singleton, because every consumer needs its own
 * per-stream state (one for left, one for right).
 */
val CLICK_MODES: Array<() -> ClickMode> = arrayOf(
    ::Fatigue,
    ::Stabilized,
    ::Spamming,
    ::Efficient,
    ::DoubleClick,
    ::Butterfly,
    ::Drag,
    ::NormalDistribution,
)

fun clickModeByName(name: String): ClickMode {
    CLICK_MODES.first { it().modeName == name }().also { return it }
}
