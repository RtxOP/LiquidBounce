/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.common

import net.minecraft.client.gui.FontRenderer
import net.minecraft.util.StringUtils

class UiTextCache(private val maxEntries: Int = 768) {
    private val widthCache = object : LinkedHashMap<TextKey, Int>(maxEntries, 0.75F, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextKey, Int>) = size > maxEntries
    }

    private val trimCache = object : LinkedHashMap<TrimKey, String>(maxEntries, 0.75F, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TrimKey, String>) = size > maxEntries
    }

    fun width(font: FontRenderer, text: String): Int {
        val key = TextKey(font.cacheId(), text)
        return widthCache.getOrPut(key) { font.getStringWidth(text) }
    }

    fun trim(font: FontRenderer, text: String, maxWidth: Int): String {
        val key = TrimKey(font.cacheId(), text, maxWidth)
        return trimCache.getOrPut(key) { font.trimToWidthWithEllipsis(text, maxWidth) }
    }

    fun clear() {
        widthCache.clear()
        trimCache.clear()
    }

    private fun FontRenderer.cacheId() = System.identityHashCode(this)

    private data class TextKey(val fontId: Int, val text: String)

    private data class TrimKey(val fontId: Int, val text: String, val maxWidth: Int)
}

fun FontRenderer.trimToWidthWithEllipsis(text: String, maxWidth: Int): String {
    if (getStringWidth(text) <= maxWidth) {
        return text
    }

    val ellipsis = "..."
    val ellipsisWidth = getStringWidth(ellipsis)
    val cleanText = StringUtils.stripControlCodes(text)

    if (maxWidth <= ellipsisWidth) {
        return ellipsis
    }

    var low = 0
    var high = cleanText.length

    while (low < high) {
        val middle = (low + high + 1) / 2
        if (getStringWidth(cleanText.substring(0, middle)) + ellipsisWidth <= maxWidth) {
            low = middle
        } else {
            high = middle - 1
        }
    }

    return cleanText.substring(0, low) + ellipsis
}
