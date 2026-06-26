/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.clickgui.modern.theme

import net.ccbluex.liquidbounce.ui.client.common.UiTheme

/**
 * Single source of truth for theme state across the modern clickgui and HUD elements.
 *
 * Reads from this object are cheap; new themes are rare. Persisted ID lives at top-level
 * in clickgui.json (see [setActive] / [refresh]).
 */
object ThemeResolver {
    private val customs: MutableList<CustomTheme> = mutableListOf()

    /** Persisted identifier. Either a [BuiltInThemes.id] or `"custom:<name>"`. */
    var activeId: String = BuiltInThemes.DEFAULT_ID
        private set

    /** The fully resolved [UiTheme] matching [activeId] and the current customs list. */
    var current: UiTheme = BuiltInThemes.byId(BuiltInThemes.DEFAULT_ID)!!.theme
        @JvmName("getCurrent")
        get

    var gradientOverride: Boolean? = null

    /** Two-stop gradient stops derived from [current]. */
    val accentGradient: Pair<java.awt.Color, java.awt.Color>
        get() = current.accent to current.accentMuted

    /** Card previews sample these stops top-to-bottom. */
    data class Stop(val offset: Float, val color: java.awt.Color)
    val cardStops: List<Stop>
        get() = listOf(
            Stop(0.0F, current.accent),
            Stop(0.35F, current.rowHover),
            Stop(0.70F, current.accentMuted),
            Stop(1.0F, current.panelBackground)
        )

    val gradientEnabled: Boolean
        get() = gradientOverride ?: (entryFor(activeId)?.gradient ?: false)

    /** Switch the active theme and refresh. Caller passes the current customs list. */
    fun setActive(id: String, customs: List<CustomTheme>) {
        applyCustoms(customs)
        activeId = id
        current = resolve(id)
    }

    /** Re-evaluate [current] without changing [activeId] (called after loading config). */
    fun refresh(customs: List<CustomTheme>) {
        applyCustoms(customs)
        current = resolve(activeId)
    }

    /** Resolve an id to a UiTheme without mutating state. */
    fun resolve(id: String): UiTheme {
        if (id.startsWith("custom:")) {
            val name = id.removePrefix("custom:")
            return customs.firstOrNull { it.name == name }?.toUiTheme()
                ?: BuiltInThemes.byId(BuiltInThemes.DEFAULT_ID)!!.theme
        }
        return BuiltInThemes.byId(id)?.theme
            ?: BuiltInThemes.byId(BuiltInThemes.DEFAULT_ID)!!.theme
    }

    fun entryFor(id: String): ThemeCatalogEntry? {
        if (id.startsWith("custom:")) return null
        return BuiltInThemes.byId(id)
    }

    /** True if the id points at a custom theme (not a built-in). */
    fun isCustom(id: String): Boolean = id.startsWith("custom:")

    /** Strip the `custom:` prefix to recover a bare name. */
    fun customName(id: String): String? = id.takeIf { isCustom(it) }?.removePrefix("custom:")

    /** Look up a saved custom theme by saved id. */
    fun customThemeFor(id: String): CustomTheme? {
        val name = customName(id) ?: return null
        return customs.firstOrNull { it.name == name }
    }

    private fun applyCustoms(list: List<CustomTheme>) {
        customs.clear()
        customs.addAll(list)
    }
}
