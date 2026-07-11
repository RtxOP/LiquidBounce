/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

/**
 * A click scheduling algorithm. Each implementation owns its tick-cycle state
 * (pattern buffer, counters, timers). Consumers create one independent
 * instance per click stream (e.g., one for left, one for right) via [create].
 *
 * Mirrors the project's `FlyMode` convention: an abstract base with a
 * [modeName] and overridable hooks. The hook surface is tuned to clicker
 * scheduling — `cacheClick` + `consumeClicks` + `reset` covers both
 * `AutoClicker` and `KillAura`.
 */
abstract class ClickMode(val modeName: String) {

    /** Returns a fresh, state-empty instance with the same algorithm. */
    abstract fun create(): ClickMode

    /**
     * Called once per game tick when the consumer wants this side to click.
     * Implementations may choose internally to fire zero, one or multiple
     * clicks per call; the consumer will read them out via [consumeClicks].
     */
    abstract fun cacheClick(active: Boolean, cps: IntRange)

    /** Returns and clears the clicks the algorithm chose to fire this tick. */
    abstract fun consumeClicks(): Int

    /**
     * True if we're inside half a click-interval after the last fire.
     * Only consulted by KillAura's post-click window. Defaults to false for
     * algorithms that don't model temporal pacing.
     */
    open fun isWithinPostClickWindow(): Boolean = false

    /**
     * Zero queued clicks but keep pattern/timing state. Used mid-flight
     * (e.g. dropping a pending attack without invalidating the cycle).
     * Default no-op; subclasses override to clear cached counts.
     */
    open fun clearClicks() {}

    /** Drop all pattern + counter state (call on module disable). */
    abstract fun reset()
}

/**
 * Registry of every click mode the user can pick.
 * Order here defines the order shown in the in-game choice setting.
 */
val CLICK_MODES: Array<ClickMode> = arrayOf(Fatigue(), Stabilized())

/** Looks up a click mode by its display name. Throws on a stale config string. */
fun clickModeByName(name: String): ClickMode =
    CLICK_MODES.first { it.modeName == name }
