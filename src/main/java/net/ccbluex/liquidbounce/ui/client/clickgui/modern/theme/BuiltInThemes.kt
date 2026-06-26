/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.clickgui.modern.theme

import net.ccbluex.liquidbounce.ui.client.common.UiTheme
import java.awt.Color

/**
 * A single built-in theme entry. Lives in a list (not an enum) so new entries can be
 * added freely without disturbing consumers.
 */
data class ThemeCatalogEntry(
    val id: String,
    val displayName: String,
    val description: String,
    val gradient: Boolean,
    val theme: UiTheme
)

/**
 * Catalog of built-in themes. The values below were eyeball-tuned for legibility
 * against the matching panel—see comments per palette.
 *
 * `DEFAULT_ID` is the first-run theme. The user picked **Pastel**.
 *
 * Intentionally does NOT include 'current colors should be discarded (pink)'.
 */
object BuiltInThemes {
    const val DEFAULT_ID = "pastel"

    val list: List<ThemeCatalogEntry> = listOf(
        ThemeCatalogEntry(
            id = "pastel",
            displayName = "Pastel",
            description = "Soft lavender on cream",
            gradient = false,
            theme = UiTheme(
                backgroundOverlay = Color(0, 0, 0, 64),
                panelBackground = Color(245, 240, 250, 255),
                panelHeader = Color(228, 220, 236, 255),
                rowBackground = Color(250, 246, 254, 255),
                settingsBackground = Color(238, 232, 244, 255),
                rowHover = Color(235, 225, 242, 255),
                accent = Color(181, 140, 235, 255),
                accentMuted = Color(210, 190, 225, 232),
                textPrimary = Color(62, 52, 76, 255),
                textMuted = Color(124, 108, 138, 255),
                border = Color(210, 198, 220, 140)
            )
        ),

        ThemeCatalogEntry(
            id = "modern",
            displayName = "Modern",
            description = "Default purple",
            gradient = false,
            theme = UiTheme.MODERN
        ),

        ThemeCatalogEntry(
            id = "azure",
            displayName = "Azure",
            description = "Cool blue on midnight",
            gradient = false,
            theme = UiTheme(
                backgroundOverlay = Color(0, 0, 0, 110),
                panelBackground = Color(10, 16, 28, 255),
                panelHeader = Color(4, 8, 18, 255),
                rowBackground = Color(14, 22, 38, 255),
                settingsBackground = Color(8, 14, 26, 255),
                rowHover = Color(24, 38, 62, 255),
                accent = Color(66, 148, 255, 255),
                accentMuted = Color(28, 60, 108, 232),
                textPrimary = Color(236, 242, 250, 255),
                textMuted = Color(138, 156, 180, 255),
                border = Color(52, 72, 108, 130)
            )
        ),

        ThemeCatalogEntry(
            id = "noir",
            displayName = "Noir",
            description = "Monochrome, gradient",
            gradient = true,
            theme = UiTheme(
                backgroundOverlay = Color(0, 0, 0, 130),
                panelBackground = Color(8, 8, 10, 255),
                panelHeader = Color(2, 2, 3, 255),
                rowBackground = Color(12, 12, 15, 255),
                settingsBackground = Color(8, 8, 11, 255),
                rowHover = Color(24, 24, 28, 255),
                accent = Color(218, 218, 224, 220),
                accentMuted = Color(60, 60, 66, 200),
                textPrimary = Color(244, 244, 244, 255),
                textMuted = Color(150, 150, 154, 255),
                border = Color(38, 38, 42, 130)
            )
        ),

        ThemeCatalogEntry(
            id = "forest",
            displayName = "Forest",
            description = "Greens on deep moss",
            gradient = false,
            theme = UiTheme(
                backgroundOverlay = Color(0, 8, 4, 110),
                panelBackground = Color(10, 18, 14, 255),
                panelHeader = Color(3, 8, 6, 255),
                rowBackground = Color(14, 26, 20, 255),
                settingsBackground = Color(8, 16, 12, 255),
                rowHover = Color(22, 42, 32, 255),
                accent = Color(90, 196, 138, 255),
                accentMuted = Color(32, 80, 58, 232),
                textPrimary = Color(232, 242, 236, 255),
                textMuted = Color(138, 168, 150, 255),
                border = Color(44, 72, 56, 130)
            )
        ),

        ThemeCatalogEntry(
            id = "sunset",
            displayName = "Sunset",
            description = "Warm orange, gradient",
            gradient = true,
            theme = UiTheme(
                backgroundOverlay = Color(20, 6, 2, 110),
                panelBackground = Color(28, 14, 10, 255),
                panelHeader = Color(14, 6, 4, 255),
                rowBackground = Color(40, 20, 14, 255),
                settingsBackground = Color(22, 10, 6, 255),
                rowHover = Color(60, 30, 22, 255),
                accent = Color(255, 126, 82, 255),
                accentMuted = Color(120, 56, 32, 232),
                textPrimary = Color(250, 238, 228, 255),
                textMuted = Color(180, 140, 124, 255),
                border = Color(88, 44, 28, 130)
            )
        ),
    )

    val MODERN_ENTRY: ThemeCatalogEntry = list.first { it.id == "modern" }

    val ALL_IDS: Set<String> = list.map { it.id }.toSet()

    fun byId(id: String): ThemeCatalogEntry? = list.firstOrNull { it.id == id }
}
