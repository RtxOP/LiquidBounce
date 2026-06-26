/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.clickgui.modern.theme

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.ccbluex.liquidbounce.ui.client.common.UiTheme
import net.ccbluex.liquidbounce.utils.render.ColorUtils.interpolateColor
import net.ccbluex.liquidbounce.utils.render.ColorUtils.withAlpha
import java.awt.Color

/**
 * A user-defined theme. Custom themes use a small set of anchors and the rest is
 * derived in [toUiTheme].
 *
 * - `accent`         primary accent — picks track fills, switch tracks, on-fill sliders
 * - `background`     darkest UI surface — panel backgrounds
 * - `text`           reader colour for primary labels
 * - `gradient`       toggles gradient flavor for sliders/switches/active module rows
 */
data class CustomTheme(
    val name: String,
    val accent: Color,
    val background: Color,
    val text: Color,
    val gradient: Boolean
) {
    /**
     * Project the 3 anchors into a full [UiTheme]. The remaining fields follow the
     * Modern palette by default to keep contrast readable. See the plan file for
     * the full derivation recipe.
     */
    fun toUiTheme(): UiTheme {
        // Stabilise all anchor alphas at 255 so the renderer manages transparency.
        val anchorBg = background.withAlpha(255)
        val anchorAccent = accent.withAlpha(255)
        val anchorText = text.withAlpha(255)

        val panelBackground = anchorBg
        val panelHeader = anchorBg.mix(Color.BLACK, 0.25F)
        val rowBackground = anchorBg.mix(Color(245, 245, 252), 0.10F)
        val rowHover = rowBackground.mix(Color.WHITE, 0.18F)
        val accentMuted = anchorAccent.mix(rowBackground, 0.35F).withAlpha(220)
        val border = rowHover.mix(Color.BLACK, 0.20F).withAlpha(140)
        val textMuted = anchorText.mix(rowBackground, 0.45F)

        return UiTheme(
            backgroundOverlay = UiTheme.MODERN.backgroundOverlay,
            panelBackground = panelBackground,
            panelHeader = panelHeader,
            rowBackground = rowBackground,
            settingsBackground = UiTheme.MODERN.settingsBackground,
            rowHover = rowHover,
            accent = anchorAccent,
            accentMuted = accentMuted,
            textPrimary = anchorText,
            textMuted = textMuted,
            border = border
        )
    }

    /**
     * Returns a JsonObject suitable for persisting inside clickgui.json.
     */
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("accent", accent.rgb)
        addProperty("background", background.rgb)
        addProperty("text", text.rgb)
        addProperty("gradient", gradient)
    }

    companion object {
        fun fromJson(obj: JsonObject): CustomTheme? = runCatching {
            val name = obj.get("name")?.asString ?: return@runCatching null
            val accent = Color(obj.get("accent").asInt, true)
            val background = Color(obj.get("background").asInt, true)
            val textColor = Color(obj.get("text").asInt, true)
            val gradient = obj.get("gradient")?.asBoolean ?: false
            CustomTheme(name, accent, background, textColor, gradient)
        }.getOrNull()

        fun isReservedName(name: String): Boolean = name in BuiltInThemes.ALL_IDS
    }

    /** Self-contained linear mix so this data layer doesn't depend on the screen. */
    private fun Color.mix(other: Color, t: Float): Color = interpolateColor(this, other, t)
}

/**
 * Collection of [CustomTheme]s with round-trip JSON helpers.
 */
object CustomThemeCollection {
    fun toJsonArray(list: List<CustomTheme>): JsonArray = JsonArray().apply {
        list.forEach { add(it.toJson()) }
    }

    /**
     * Decode a JsonArray. Drops invalid entries silently and reserved-name entries
     * (warn) so the rest of the config still loads.
     */
    fun fromJsonArray(arr: JsonArray?): List<CustomTheme> {
        if (arr == null) return emptyList()
        val out = mutableListOf<CustomTheme>()
        for (element in arr) {
            val obj = runCatching { element.asJsonObject }.getOrNull() ?: continue
            val theme = CustomTheme.fromJson(obj) ?: continue
            if (CustomTheme.isReservedName(theme.name)) continue
            out += theme
        }
        return out
    }
}
