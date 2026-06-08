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
    val backgroundOverlay: Color = Color(0, 0, 0, 105),
    val panelBackground: Color = Color(12, 13, 18, 228),
    val panelHeader: Color = Color(3, 3, 5, 245),
    val rowBackground: Color = Color(21, 23, 30, 210),
    val rowHover: Color = Color(36, 38, 48, 230),
    val accent: Color = Color(205, 0, 255, 235),
    val accentMuted: Color = Color(123, 49, 190, 195),
    val textPrimary: Color = Color(245, 245, 248, 255),
    val textMuted: Color = Color(160, 160, 170, 255),
    val border: Color = Color(0, 0, 0, 130)
) {
    fun forPerformanceProfile(profile: UiPerformanceProfile) = when (profile) {
        UiPerformanceProfile.FAST -> copy(
            backgroundOverlay = Color(0, 0, 0, 88),
            panelBackground = Color(12, 13, 18, 238),
            rowBackground = Color(20, 22, 29, 225),
            rowHover = Color(34, 36, 45, 220)
        )

        UiPerformanceProfile.BALANCED -> this

        UiPerformanceProfile.FANCY -> copy(
            backgroundOverlay = Color(0, 0, 0, 118),
            panelBackground = Color(12, 13, 18, 222),
            rowBackground = Color(21, 23, 30, 198),
            rowHover = Color(40, 42, 54, 235)
        )
    }

    companion object {
        val MODERN = UiTheme()
    }
}
