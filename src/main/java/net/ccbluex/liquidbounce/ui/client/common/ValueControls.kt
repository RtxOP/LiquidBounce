/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.common

import net.ccbluex.liquidbounce.config.BlockValue
import net.ccbluex.liquidbounce.config.BoolValue
import net.ccbluex.liquidbounce.config.ColorValue
import net.ccbluex.liquidbounce.config.FloatRangeValue
import net.ccbluex.liquidbounce.config.FloatValue
import net.ccbluex.liquidbounce.config.FontValue
import net.ccbluex.liquidbounce.config.IntRangeValue
import net.ccbluex.liquidbounce.config.IntValue
import net.ccbluex.liquidbounce.config.ListValue
import net.ccbluex.liquidbounce.config.RangeSlider
import net.ccbluex.liquidbounce.config.TextValue
import net.ccbluex.liquidbounce.config.Value
import net.ccbluex.liquidbounce.ui.font.Fonts
import net.ccbluex.liquidbounce.utils.render.ColorUtils.withAlpha
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawRect
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawRoundedRect
import net.ccbluex.liquidbounce.utils.ui.EditableText
import org.lwjgl.input.Keyboard
import java.awt.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class ValueControlState {
    var draggingValue: Value<*>? = null
        private set

    var focusedText: EditableText? = null
        private set

    private var draggingColorChannel: Int? = null
    private var dirty = false

    fun startDragging(value: Value<*>, colorChannel: Int? = null) {
        if (focusedText?.value !== value) {
            focusedText = null
        }

        draggingValue = value
        draggingColorChannel = colorChannel
    }

    fun focusText(value: TextValue) {
        draggingValue = null
        draggingColorChannel = null
        focusedText = EditableText(
            value = value,
            string = value.get(),
            onUpdate = { value.set(it, false) }
        )
    }

    fun clearFocus() {
        focusedText = null
    }

    fun markDirty() {
        dirty = true
    }

    fun release(save: () -> Unit) {
        draggingValue = null
        draggingColorChannel = null
        flush(save)
    }

    fun processTextInput(typedChar: Char, keyCode: Int, save: () -> Unit, onChanged: () -> Unit): Boolean {
        val text = focusedText ?: return false

        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            focusedText = null
            flush(save)
            return true
        }

        val before = text.string
        text.processInput(typedChar, keyCode) {
            // Text values do not use indexed fields.
        }

        if (text.string != before) {
            markDirty()
            onChanged()
        }

        return true
    }

    fun colorChannelFor(value: Value<*>) = if (draggingValue === value) draggingColorChannel else null

    private fun flush(save: () -> Unit) {
        if (dirty) {
            save()
            dirty = false
        }
    }
}

object ValueControls {
    const val ROW_HEIGHT = 18F
    private const val SLIDER_ROW_HEIGHT = 24F
    private const val COLOR_CHANNEL_HEIGHT = 18F
    private const val COLOR_CHANNEL_COUNT = 4

    private val colorLabels = arrayOf("R", "G", "B", "A")

    fun height(value: Value<*>) = when (value) {
        is IntValue, is FloatValue, is BlockValue, is IntRangeValue, is FloatRangeValue -> SLIDER_ROW_HEIGHT
        is ColorValue -> ROW_HEIGHT + if (value.showPicker) COLOR_CHANNEL_HEIGHT * COLOR_CHANNEL_COUNT else 0F
        else -> ROW_HEIGHT
    }

    fun draw(value: Value<*>, rect: UiRect, theme: UiTheme, mouseX: Int, mouseY: Int, state: ValueControlState) {
        val hovered = rect.contains(mouseX, mouseY)
        val focused = state.focusedText?.value === value
        val background = when {
            focused -> theme.rowHover.withAlpha(190)
            hovered -> theme.rowHover.withAlpha(170)
            else -> theme.rowBackground.withAlpha(125)
        }

        drawRoundedRect(rect.x, rect.y, rect.right, rect.bottom - 1F, background.rgb, 3F)

        when (value) {
            is BoolValue -> drawBoolean(value, rect, theme)
            is IntValue -> drawSlider(
                value.name,
                value.get().toString(),
                value.suffix,
                value.minimum,
                value.maximum,
                value.get(),
                rect,
                theme
            )
            is FloatValue -> drawSlider(
                value.name,
                formatFloat(value.get()),
                value.suffix,
                value.minimum,
                value.maximum,
                value.get(),
                rect,
                theme
            )
            is BlockValue -> drawSlider(
                value.name,
                value.get().toString(),
                null,
                value.minimum,
                value.maximum,
                value.get(),
                rect,
                theme
            )
            is IntRangeValue -> drawRangeSlider(
                value.name,
                "${value.get().first} - ${value.get().last}",
                value.suffix,
                value.minimum.toFloat(),
                value.maximum.toFloat(),
                value.get().first.toFloat(),
                value.get().last.toFloat(),
                rect,
                theme
            )
            is FloatRangeValue -> drawRangeSlider(
                value.name,
                "${formatFloat(value.get().start)} - ${formatFloat(value.get().endInclusive)}",
                value.suffix,
                value.minimum,
                value.maximum,
                value.get().start,
                value.get().endInclusive,
                rect,
                theme
            )
            is ListValue -> drawChoice(value.name, value.get(), rect, theme)
            is FontValue -> drawLabel(value.displayName, rect, theme)
            is TextValue -> drawText(value, rect, theme, state.focusedText)
            is ColorValue -> drawColor(value, rect, theme)
            else -> drawLabel(value.name, rect, theme, theme.textMuted.withAlpha(155))
        }
    }

    fun click(
        value: Value<*>,
        rect: UiRect,
        mouseX: Int,
        mouseY: Int,
        button: Int,
        state: ValueControlState,
        onChanged: () -> Unit
    ): Boolean {
        if (!rect.contains(mouseX, mouseY)) {
            return false
        }

        when (value) {
            is BoolValue -> {
                if (button != 0) return false
                if (value.set(!value.get(), false)) {
                    state.markDirty()
                    onChanged()
                }
                UiSound.click()
            }

            is IntValue, is FloatValue, is BlockValue -> {
                if (button != 0) return false
                state.startDragging(value)
                updateSlider(value, rect, mouseX, state)
                UiSound.click()
            }

            is IntRangeValue, is FloatRangeValue -> {
                if (button != 0) return false
                state.startDragging(value)
                selectRangeHandle(value, rect, mouseX)
                updateRangeSlider(value, rect, mouseX, state)
                UiSound.click()
            }

            is ListValue -> {
                if (button !in 0..1 || value.values.isEmpty()) return false
                val current = value.values.indexOfFirst { it.equals(value.get(), true) }.coerceAtLeast(0)
                val delta = if (button == 0) 1 else -1
                val next = (current + delta + value.values.size) % value.values.size

                if (value.set(value.values[next], false)) {
                    state.markDirty()
                    onChanged()
                }
                UiSound.click()
            }

            is FontValue -> {
                if (button !in 0..1) return false
                if (button == 0) value.next() else value.previous()
                state.markDirty()
                onChanged()
                UiSound.click()
            }

            is TextValue -> {
                if (button != 0) return false
                state.focusText(value)
                UiSound.click()
            }

            is ColorValue -> {
                if (button == 1) {
                    value.showPicker = !value.showPicker
                    UiSound.expand()
                    return true
                }

                if (button != 0) return false

                if (value.showPicker && mouseY >= rect.y + ROW_HEIGHT) {
                    val channel = colorChannelAt(rect, mouseY) ?: return false
                    state.startDragging(value, channel)
                    updateColorChannel(value, rect, mouseX, state)
                } else {
                    value.rainbow = !value.rainbow
                    state.markDirty()
                    onChanged()
                }

                UiSound.click()
            }

            else -> return false
        }

        return true
    }

    fun drag(value: Value<*>, rect: UiRect, mouseX: Int, state: ValueControlState, onChanged: () -> Unit) {
        if (state.draggingValue !== value) {
            return
        }

        val changed = when (value) {
            is IntValue, is FloatValue, is BlockValue -> updateSlider(value, rect, mouseX, state)
            is IntRangeValue, is FloatRangeValue -> updateRangeSlider(value, rect, mouseX, state)
            is ColorValue -> updateColorChannel(value, rect, mouseX, state)
            else -> false
        }

        if (changed) {
            onChanged()
        }
    }

    fun keyTyped(
        typedChar: Char,
        keyCode: Int,
        state: ValueControlState,
        save: () -> Unit,
        onChanged: () -> Unit
    ) = state.processTextInput(typedChar, keyCode, save, onChanged)

    private fun drawBoolean(value: BoolValue, rect: UiRect, theme: UiTheme) {
        val label = Fonts.fontRegular30.trimToWidthWithEllipsis(value.name, (rect.width - 31F).roundToInt())
        val enabled = value.get()
        val trackX = rect.right - 24F
        val trackY = rect.y + 5F
        val trackColor = if (enabled) theme.accent else Color(70, 72, 82, 190)
        val knobX = if (enabled) trackX + 11F else trackX + 2F

        Fonts.fontRegular30.drawString(label, rect.x + 5F, rect.y + 5F, theme.textPrimary.rgb)
        drawRoundedRect(trackX, trackY, trackX + 20F, trackY + 8F, trackColor.rgb, 4F)
        drawRoundedRect(knobX, trackY + 1F, knobX + 6F, trackY + 7F, theme.textPrimary.rgb, 3F)
    }

    private fun drawChoice(name: String, valueText: String, rect: UiRect, theme: UiTheme) {
        val label = Fonts.fontRegular30.trimToWidthWithEllipsis(
            "$name: $valueText",
            (rect.width - 10F).roundToInt()
        )

        Fonts.fontRegular30.drawString(label, rect.x + 5F, rect.y + 5F, theme.textPrimary.rgb)
    }

    private fun drawText(value: TextValue, rect: UiRect, theme: UiTheme, focusedText: EditableText?) {
        val focused = focusedText?.takeIf { it.value === value }
        val valueText = focused?.string ?: value.get()
        val display = Fonts.fontRegular30.trimToWidthWithEllipsis(
            "${value.name}: $valueText",
            (rect.width - 10F).roundToInt()
        )

        Fonts.fontRegular30.drawString(display, rect.x + 5F, rect.y + 5F, theme.textPrimary.rgb)

        if (focused != null) {
            drawRect(rect.x + 5F, rect.bottom - 3F, rect.right - 5F, rect.bottom - 2F, theme.accent.rgb)

            val cursorPrefix = "${value.name}: " + focused.string.take(focused.cursorIndex)
            val cursorX = (rect.x + 5F + Fonts.fontRegular30.getStringWidth(cursorPrefix)).coerceAtMost(rect.right - 7F)
            drawRect(cursorX, rect.y + 4F, cursorX + 1F, rect.bottom - 4F, theme.textPrimary.rgb)
        }
    }

    private fun drawColor(value: ColorValue, rect: UiRect, theme: UiTheme) {
        val color = value.selectedColor()
        val stateText = if (value.rainbow) "Rainbow" else rgbaText(value.get())
        val label = Fonts.fontRegular30.trimToWidthWithEllipsis(
            "${value.name}: $stateText",
            (rect.width - 36F).roundToInt()
        )
        val swatchX = rect.right - 20F
        val swatchY = rect.y + 4F

        Fonts.fontRegular30.drawString(label, rect.x + 5F, rect.y + 5F, theme.textPrimary.rgb)
        drawRoundedRect(swatchX - 1F, swatchY - 1F, swatchX + 15F, swatchY + 11F, theme.textMuted.withAlpha(155).rgb, 3F)
        drawRoundedRect(swatchX, swatchY, swatchX + 14F, swatchY + 10F, color.rgb, 3F)

        if (!value.showPicker) {
            return
        }

        val current = value.get()
        drawColorChannel(0, current.red, colorChannelRect(rect, 0), theme, Color(225, 78, 78))
        drawColorChannel(1, current.green, colorChannelRect(rect, 1), theme, Color(77, 196, 110))
        drawColorChannel(2, current.blue, colorChannelRect(rect, 2), theme, Color(88, 142, 242))
        drawColorChannel(3, current.alpha, colorChannelRect(rect, 3), theme, theme.accent)
    }

    private fun drawColorChannel(index: Int, current: Int, rect: UiRect, theme: UiTheme, fillColor: Color) {
        val label = "${colorLabels[index]}: $current"
        val track = UiRect(rect.x + 32F, rect.y + 11F, rect.width - 39F, 1F)
        val progress = (current / 255F).coerceIn(0F, 1F)
        val fill = track.x + track.width * progress

        Fonts.fontRegular30.drawString(label, rect.x, rect.y + 5F, theme.textMuted.withAlpha(210).rgb)
        drawRect(track.x, track.y, track.right, track.y + 1F, theme.textMuted.withAlpha(135).rgb)
        drawRoundedRect(track.x, track.y - 1F, max(track.x + 1F, fill), track.y + 2F, fillColor.rgb, 1.5F)
        drawRoundedRect(fill - 2F, track.y - 3F, fill + 2F, track.y + 4F, theme.textPrimary.rgb, 2F)
    }

    private fun drawLabel(text: String, rect: UiRect, theme: UiTheme, color: Color = theme.textPrimary) {
        val label = Fonts.fontRegular30.trimToWidthWithEllipsis(text, (rect.width - 10F).roundToInt())
        Fonts.fontRegular30.drawString(label, rect.x + 5F, rect.y + 5F, color.rgb)
    }

    private fun drawSlider(
        name: String,
        valueText: String,
        suffix: String?,
        minimum: Number,
        maximum: Number,
        current: Number,
        rect: UiRect,
        theme: UiTheme
    ) {
        val suffixText = suffix?.let { " $it" } ?: ""
        val label = Fonts.fontRegular30.trimToWidthWithEllipsis(
            "$name: $valueText$suffixText",
            (rect.width - 10F).roundToInt()
        )
        val track = sliderTrack(rect)
        val progress = sliderProgress(minimum.toFloat(), maximum.toFloat(), current.toFloat())
        val fill = track.x + track.width * progress
        val fillEnd = max(track.x + 1F, fill)

        Fonts.fontRegular30.drawString(label, rect.x + 5F, rect.y + 4F, theme.textPrimary.rgb)
        drawRect(track.x, track.y, track.right, track.y + 1F, theme.textMuted.withAlpha(155).rgb)
        drawRoundedRect(track.x, track.y - 1.5F, fillEnd, track.y + 2.5F, theme.accent.rgb, 2F)
        drawRoundedRect(fill - 2F, track.y - 3F, fill + 2F, track.y + 4F, theme.textPrimary.rgb, 2F)
    }

    private fun drawRangeSlider(
        name: String,
        valueText: String,
        suffix: String?,
        minimum: Float,
        maximum: Float,
        first: Float,
        last: Float,
        rect: UiRect,
        theme: UiTheme
    ) {
        val suffixText = suffix?.let { " $it" } ?: ""
        val label = Fonts.fontRegular30.trimToWidthWithEllipsis(
            "$name: $valueText$suffixText",
            (rect.width - 10F).roundToInt()
        )
        val track = sliderTrack(rect)
        val firstProgress = sliderProgress(minimum, maximum, first)
        val lastProgress = sliderProgress(minimum, maximum, last)
        val firstX = track.x + track.width * firstProgress
        val lastX = track.x + track.width * lastProgress

        Fonts.fontRegular30.drawString(label, rect.x + 5F, rect.y + 4F, theme.textPrimary.rgb)
        drawRect(track.x, track.y, track.right, track.y + 1F, theme.textMuted.withAlpha(155).rgb)
        drawRoundedRect(firstX, track.y - 1.5F, max(firstX + 1F, lastX), track.y + 2.5F, theme.accent.rgb, 2F)
        drawRoundedRect(firstX - 2F, track.y - 3F, firstX + 2F, track.y + 4F, theme.textPrimary.rgb, 2F)
        drawRoundedRect(lastX - 2F, track.y - 3F, lastX + 2F, track.y + 4F, theme.textPrimary.rgb, 2F)
    }

    private fun updateSlider(value: Value<*>, rect: UiRect, mouseX: Int, state: ValueControlState): Boolean {
        val track = sliderTrack(rect)
        val percent = ((mouseX - track.x) / track.width).coerceIn(0F, 1F)
        val changed = when (value) {
            is IntValue -> {
                val next = (value.minimum + (value.maximum - value.minimum) * percent).roundToInt()
                value.set(next, false)
            }

            is FloatValue -> {
                val next = value.minimum + (value.maximum - value.minimum) * percent
                value.set(next, false)
            }

            is BlockValue -> {
                val next = (value.minimum + (value.maximum - value.minimum) * percent).roundToInt()
                value.set(next, false)
            }

            else -> false
        }

        if (changed) {
            state.markDirty()
        }

        return changed
    }

    private fun selectRangeHandle(value: Value<*>, rect: UiRect, mouseX: Int) {
        val track = sliderTrack(rect)

        when (value) {
            is IntRangeValue -> {
                val firstX = track.x + track.width * sliderProgress(
                    value.minimum.toFloat(),
                    value.maximum.toFloat(),
                    value.get().first.toFloat()
                )
                val lastX = track.x + track.width * sliderProgress(
                    value.minimum.toFloat(),
                    value.maximum.toFloat(),
                    value.get().last.toFloat()
                )
                value.lastChosenSlider = if (abs(mouseX - firstX) <= abs(mouseX - lastX)) RangeSlider.LEFT else RangeSlider.RIGHT
            }

            is FloatRangeValue -> {
                val firstX = track.x + track.width * sliderProgress(value.minimum, value.maximum, value.get().start)
                val lastX = track.x + track.width * sliderProgress(value.minimum, value.maximum, value.get().endInclusive)
                value.lastChosenSlider = if (abs(mouseX - firstX) <= abs(mouseX - lastX)) RangeSlider.LEFT else RangeSlider.RIGHT
            }

            else -> Unit
        }
    }

    private fun updateRangeSlider(value: Value<*>, rect: UiRect, mouseX: Int, state: ValueControlState): Boolean {
        val track = sliderTrack(rect)
        val percent = ((mouseX - track.x) / track.width).coerceIn(0F, 1F)
        val changed = when (value) {
            is IntRangeValue -> {
                val current = value.get()
                val next = (value.minimum + (value.maximum - value.minimum) * percent).roundToInt()
                when (value.lastChosenSlider ?: RangeSlider.LEFT) {
                    RangeSlider.LEFT -> value.setFirst(next.coerceIn(value.minimum, current.last), false)
                    RangeSlider.RIGHT -> value.setLast(next.coerceIn(current.first, value.maximum), false)
                }
            }

            is FloatRangeValue -> {
                val current = value.get()
                val next = value.minimum + (value.maximum - value.minimum) * percent
                when (value.lastChosenSlider ?: RangeSlider.LEFT) {
                    RangeSlider.LEFT -> value.setFirst(next.coerceIn(value.minimum, current.endInclusive), false)
                    RangeSlider.RIGHT -> value.setLast(next.coerceIn(current.start, value.maximum), false)
                }
            }

            else -> false
        }

        if (changed) {
            state.markDirty()
        }

        return changed
    }

    private fun updateColorChannel(value: ColorValue, rect: UiRect, mouseX: Int, state: ValueControlState): Boolean {
        val channel = state.colorChannelFor(value) ?: return false
        val channelRect = colorChannelRect(rect, channel)
        val track = UiRect(channelRect.x + 32F, channelRect.y + 11F, channelRect.width - 39F, 1F)
        val percent = ((mouseX - track.x) / track.width).coerceIn(0F, 1F)
        val component = (255F * percent).roundToInt().coerceIn(0, 255)
        val current = value.get()
        val next = when (channel) {
            0 -> Color(component, current.green, current.blue, current.alpha)
            1 -> Color(current.red, component, current.blue, current.alpha)
            2 -> Color(current.red, current.green, component, current.alpha)
            3 -> Color(current.red, current.green, current.blue, component)
            else -> current
        }

        if (next == current && !value.rainbow) {
            return false
        }

        value.rainbow = false
        value.changeValue(next)
        value.setupSliders(next)
        state.markDirty()

        return true
    }

    private fun colorChannelAt(rect: UiRect, mouseY: Int): Int? {
        val index = ((mouseY - (rect.y + ROW_HEIGHT)) / COLOR_CHANNEL_HEIGHT).toInt()
        return index.takeIf { it in 0 until COLOR_CHANNEL_COUNT }
    }

    private fun colorChannelRect(rect: UiRect, index: Int) =
        UiRect(rect.x + 5F, rect.y + ROW_HEIGHT + index * COLOR_CHANNEL_HEIGHT, rect.width - 10F, COLOR_CHANNEL_HEIGHT)

    private fun sliderTrack(rect: UiRect) = UiRect(rect.x + 7F, rect.bottom - 7F, rect.width - 14F, 1F)

    private fun sliderProgress(minimum: Float, maximum: Float, current: Float): Float {
        if (maximum <= minimum) {
            return 0F
        }

        return ((current - minimum) / (maximum - minimum)).coerceIn(0F, 1F)
    }

    private fun rgbaText(color: Color) =
        "${color.red},${color.green},${color.blue},${color.alpha}"

    private fun formatFloat(value: Float): String {
        val rounded = (value * 100F).roundToInt() / 100F
        return if (rounded % 1F == 0F) {
            rounded.toInt().toString()
        } else {
            rounded.toString()
        }
    }
}
