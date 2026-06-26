/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.common

import java.awt.Color

enum class UiBackgroundEffect {
    DIM,
    NONE
}

data class UiTheme(
    val backgroundOverlay: Color = Color(0, 0, 0, 94),
    val panelBackground: Color = Color(9, 11, 17, 255),
    val panelHeader: Color = Color(3, 4, 8, 255),
    val rowBackground: Color = Color(13, 16, 24, 255),
    val settingsBackground: Color = Color(8, 10, 16, 255),
    val rowHover: Color = Color(27, 31, 43, 255),
    val accent: Color = Color(202, 0, 255, 255),
    val accentMuted: Color = Color(54, 34, 78, 232),
    val textPrimary: Color = Color(248, 248, 252, 255),
    val textMuted: Color = Color(150, 153, 166, 255),
    val border: Color = Color(49, 53, 66, 130)
) {
    companion object {
        val MODERN = UiTheme()
    }
}
