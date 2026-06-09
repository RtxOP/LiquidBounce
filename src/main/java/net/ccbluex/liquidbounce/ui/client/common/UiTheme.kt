/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.common

import java.awt.Color

enum class UiPerformanceProfile(val configName: String) {
    FAST("Fast"),
    BALANCED("Balanced"),
    FANCY("Fancy");

    companion object {
        fun fromConfigName(name: String?) =
            entries.find { it.configName.equals(name, true) || it.name.equals(name, true) } ?: BALANCED
    }
}

enum class UiBackgroundEffect {
    DIM,
    NONE
}

data class UiTheme(
    val backgroundOverlay: Color = Color(0, 0, 0, 82),
    val panelBackground: Color = Color(9, 11, 16, 246),
    val panelHeader: Color = Color(3, 4, 8, 252),
    val rowBackground: Color = Color(12, 15, 22, 236),
    val rowHover: Color = Color(24, 28, 38, 244),
    val accent: Color = Color(202, 0, 255, 245),
    val accentMuted: Color = Color(54, 34, 78, 232),
    val textPrimary: Color = Color(248, 248, 252, 255),
    val textMuted: Color = Color(150, 153, 166, 255),
    val border: Color = Color(49, 53, 66, 130)
) {
    fun forPerformanceProfile(profile: UiPerformanceProfile) = when (profile) {
        UiPerformanceProfile.FAST -> copy(
            backgroundOverlay = Color(0, 0, 0, 76),
            panelBackground = Color(9, 11, 16, 250),
            rowBackground = Color(12, 15, 22, 242),
            rowHover = Color(22, 26, 35, 242)
        )

        UiPerformanceProfile.BALANCED -> this

        UiPerformanceProfile.FANCY -> copy(
            backgroundOverlay = Color(0, 0, 0, 94),
            panelBackground = Color(9, 11, 17, 240),
            rowBackground = Color(13, 16, 24, 226),
            rowHover = Color(27, 31, 43, 246)
        )
    }

    companion object {
        val MODERN = UiTheme()
    }
}
