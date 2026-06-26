/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.clickgui.modern.theme

import net.ccbluex.liquidbounce.ui.client.common.UiTheme
import java.awt.Color

/**
 * Theme tokens HUD elements can resolve to when their color mode is set to "Theme".
 *
 * Single source of truth so consumer code (Arraylist, Keystrokes, etc.) boxes onto
 * one of these rather than naming theme fields directly. Add a slot here and the
 * HUD-side dropdown can grow without touching the resolver.
 */
enum class ThemeSlot {
    ACCENT,
    ACCENT_MUTED,
    TEXT_PRIMARY,
    TEXT_MUTED,
    BACKGROUND,
    BACKGROUND_OVERLAY,
    ROW_HOVER
}

/** Resolve a slot to a concrete color from a [UiTheme]. */
fun UiTheme.colorAt(slot: ThemeSlot): Color = when (slot) {
    ThemeSlot.ACCENT -> accent
    ThemeSlot.ACCENT_MUTED -> accentMuted
    ThemeSlot.TEXT_PRIMARY -> textPrimary
    ThemeSlot.TEXT_MUTED -> textMuted
    ThemeSlot.BACKGROUND -> panelBackground
    ThemeSlot.BACKGROUND_OVERLAY -> backgroundOverlay
    ThemeSlot.ROW_HOVER -> rowHover
}
