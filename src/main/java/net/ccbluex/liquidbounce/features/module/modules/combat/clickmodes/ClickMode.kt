/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

/**
 * A click scheduling algorithm. Each implementation owns its tick-cycle state
 * (pattern buffer, counters, timers). Consumers create one instance per click
 * stream (e.g., one for left, one for right) via [create].
 */
abstract class ClickMode(val modeName: String) {
    /** Returns a fresh, state-empty instance with the same algorithm. */
    abstract fun create(): ClickMode

    /** Called once per game tick when [active] the consumer wants to click. */
    abstract fun cacheClick(active: Boolean, cps: IntRange)

    /** Returns and clears the clicks the algorithm chose to fire this tick. */
    abstract fun consumeClicks(): Int

    /**
     * Clear any pending clicks without resetting the pattern (use when the
     * module decides to discard a tick's clicks). Default zeroes cached clicks.
     */
    open fun clearClicks() {
        // Default no-op; implementations with cached state should override.
    }

    /**
     * True if we are inside half a click-interval after the last fire.
     * Only consumed by KillAura for its post-click window. Defaults false.
     */
    open fun isWithinPostClickWindow(): Boolean = false

    /** Drop all pattern/counter state (call on module disable). */
    abstract fun reset()
}

/** Registry of user-selectable click scheduling algorithms. */
val CLICK_MODES: Array<ClickMode> = arrayOf(Fatigue(), Stabilized())

fun clickModeByName(name: String): ClickMode =
    CLICK_MODES.first { it.modeName == name }
