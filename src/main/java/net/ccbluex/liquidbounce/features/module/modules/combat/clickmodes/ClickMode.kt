/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

import kotlin.math.max
import kotlin.random.Random

/**
 * A click scheduling algorithm. Each implementation owns its tick-cycle state
 * (pattern buffer, counters, timers). Consumers look up by name through
 * [clickModeByName] and keep their own per-stream instance alive for as long
 * as the module is enabled.
 *
 * @param modeName  Display name used by both AutoClicker and KillAura to
 *                  pick this implementation from the user's `Method` choice.
 */
abstract class ClickMode(val modeName: String) {
    /** Called once per game tick when [active] the consumer wants to click. */
    abstract fun cacheClick(active: Boolean, cps: IntRange)

    /** Returns and clears the clicks the algorithm chose to fire this tick. */
    abstract fun consumeClicks(): Int

    /**
     * True if we are inside half a click-interval after the last fire.
     * Only consumed by KillAura for its post-click window (`OutBorder`).
     *
     * Default implementation answers from each subclass's per-click
     * [markClicked] / per-cycle [setClicksPerSecond] bookkeeping so the
     * window works regardless of which pattern is active.
     */
    open fun isWithinPostClickWindow(): Boolean {
        if (lastClickTime == 0L || clicksPerSecond <= 0) return false
        return System.currentTimeMillis() - lastClickTime < 1000L / clicksPerSecond / 2L
    }

    /** Drop all pattern/counter state (call on module disable). */
    abstract fun reset()

    /** Subclasses call this from [reset] to also clear the post-click window state. */
    protected fun clearClickTracking() {
        lastClickTime = 0L
        clicksPerSecond = 0
    }

    // ---- shared post-click bookkeeping ----
    protected var lastClickTime = 0L
        private set
    protected var clicksPerSecond = 0
        private set

    /** Mark that a click was emitted at [now]; subclasses call this on bursts. */
    protected fun markClicked(now: Long = System.currentTimeMillis()) {
        lastClickTime = now
    }

    /** Subclasses call this after sampling how many clicks the next cycle targets. */
    protected fun setClicksPerSecond(value: Int) {
        clicksPerSecond = value
    }
}

/**
 * Cycle-based click scheduler shared by the simpler patterns (Stabilized,
 * Spamming, Efficient, DoubleClick, Butterfly, Drag, NormalDistribution).
 *
 * Owns the 20-tick [cycle] buffer, its cursor, and the 1-second refill gate.
 * Subclasses only implement [refillCycle] to distribute [clicks] units
 * across the cycle.
 */
abstract class CycleClickMode(modeName: String) : ClickMode(modeName) {

    protected val patternLength = 20
    protected val cycle = IntArray(patternLength)
    protected var cycleCursor = 0
    protected var cycleUpdateTime = 0L
    protected var cachedClicks = 0

    override fun cacheClick(active: Boolean, cps: IntRange) {
        if (!active) {
            cachedClicks = 0
            return
        }

        val now = System.currentTimeMillis()
        if (cycleCursor == 0 || now - cycleUpdateTime >= 1000L) {
            cycle.fill(0)
            cycleCursor = 0
            val clicks = Random.Default.nextInt(cps.first, cps.last + 1)
            setClicksPerSecond(clicks)
            refillCycle(clicks)
            cycleUpdateTime = now
        }

        val pending = cycle[cycleCursor]
        cachedClicks = pending
        if (pending > 0) {
            cycle[cycleCursor] = 0
            markClicked(now)
        }
        cycleCursor = (cycleCursor + 1) % patternLength
    }

    override fun consumeClicks(): Int {
        val clicks = cachedClicks
        cachedClicks = 0
        return clicks
    }

    override fun reset() {
        cycle.fill(0)
        cycleCursor = 0
        cycleUpdateTime = 0L
        cachedClicks = 0
        clearClickTracking()
    }

    /**
     * Place [clicks] units across the 20-slot [cycle]. Called once per
     * refill (every full cycle, plus after a long pause that crosses the
     * 1-second gate).
     */
    protected abstract fun refillCycle(clicks: Int)
}

/**
 * Even-pacing layout (`interval = patternLength / clicks`, with the remainder
 * distributed as +1 gaps in turn). Used standalone by [Stabilized] and as the
 * "wide-gap fallback" by [Efficient].
 */
internal fun stabilizedFill(cycle: IntArray, clicks: Int) {
    if (clicks <= 0) return
    val size = cycle.size
    val interval = size / clicks
    var remainder = size % clicks
    var currentIndex = 0
    repeat(clicks) {
        cycle[currentIndex % size]++
        currentIndex += max(interval, 1)
        if (remainder > 0) {
            currentIndex++
            remainder--
        }
    }
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

/** The order in which the implementation names appear in the user-facing choice list. */
val CLICK_MODE_NAMES: Array<String> by lazy {
    CLICK_MODES.map { it().modeName }.toTypedArray()
}

private val clickModeFactoryByName: Map<String, () -> ClickMode> by lazy {
    CLICK_MODES.associateBy({ it().modeName }, { it })
}

/** Construct a fresh [ClickMode] for the given display [name]. */
fun clickModeByName(name: String): ClickMode =
    clickModeFactoryByName[name]?.invoke()
        ?: error("Unknown click mode: $name")
