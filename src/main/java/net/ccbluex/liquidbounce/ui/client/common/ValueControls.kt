/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.common

import net.ccbluex.liquidbounce.config.BlockValue
import net.ccbluex.liquidbounce.config.BoolValue
import net.ccbluex.liquidbounce.config.ColorValue
import net.ccbluex.liquidbounce.config.ColorValue.SliderType
import net.ccbluex.liquidbounce.config.FloatRangeValue
import net.ccbluex.liquidbounce.config.FloatValue
import net.ccbluex.liquidbounce.config.FontValue
import net.ccbluex.liquidbounce.config.IntRangeValue
import net.ccbluex.liquidbounce.config.IntValue
import net.ccbluex.liquidbounce.config.ListValue
import net.ccbluex.liquidbounce.config.RangeSlider
import net.ccbluex.liquidbounce.config.TextValue
import net.ccbluex.liquidbounce.config.Value
import net.ccbluex.liquidbounce.ui.client.clickgui.modern.theme.ThemeResolver
import net.ccbluex.liquidbounce.ui.font.Fonts
import net.ccbluex.liquidbounce.utils.render.ColorUtils.withAlpha
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawRect
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawRoundedGradientRect
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawRoundedRect
import net.ccbluex.liquidbounce.utils.render.shader.shaders.SbPickerShader
import net.ccbluex.liquidbounce.utils.ui.EditableText
import org.lwjgl.input.Keyboard
import java.awt.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ValueControlState {
    var draggingValue: Value<*>? = null
        private set

    var focusedText: EditableText? = null
        private set

    private val animations = UiAnimationStore()
    private var animationsEnabled = true
    private var draggingColorComponent: SliderType? = null
    private var draggingRangeHandle: RangeSlider? = null
    private var dirty = false

    fun beginFrame(animationsEnabled: Boolean) {
        this.animationsEnabled = animationsEnabled
        animations.beginFrame()
    }

    fun animatedFloat(key: String, target: Float, speed: Float) =
        animations.float(key, target, speed, animationsEnabled)

    fun pruneAnimations() {
        animations.prune()
    }

    fun startDragging(value: Value<*>, colorComponent: SliderType? = null, rangeHandle: RangeSlider? = null) {
        if (focusedText?.value !== value) {
            focusedText = null
        }

        draggingValue = value
        draggingColorComponent = colorComponent
        draggingRangeHandle = rangeHandle
    }

    fun focusText(value: TextValue) {
        draggingValue = null
        draggingColorComponent = null
        draggingRangeHandle = null
        focusedText = EditableText(
            value = value,
            string = value.get(),
            onUpdate = { value.set(it, false) }
        )
    }

    /**
     * Open the side-panel HEX entry for a color value. Reuses the existing
     * [focusedText] slot so key processing, Enter/Escape semantics and
     * "did the user click a different value?" guards in [startDragging] apply
     * uniformly. Each keystroke is validated by the [EditableText.validator]
     * and routed through [EditableText.onUpdate], which maps the current
     * hex string to an ARGB [Color] and pushes it into [ColorValue] without
     * immediately saving to disk (the existing flush path on release/Enter
     * persists it).
     */
    fun focusHexText(value: ColorValue) {
        draggingValue = null
        draggingColorComponent = null
        draggingRangeHandle = null
        val hex = "#%08X".format(value.get().rgb)
        focusedText = EditableText(
            value = value,
            string = hex,
            cursorIndex = hex.length,
            validator = { input ->
                val raw = input.removePrefix("#")
                raw.length <= 8 &&
                    raw.all { c ->
                        c.isDigit() || c in 'a'..'f' || c in 'A'..'F'
                    }
            },
            onUpdate = { input ->
                val raw = input.removePrefix("#")
                // Pad with zeros so partial input still parses to a valid color
                // (e.g. "77aa" -> "77aa0000"). The validator only lets through
                // hex characters, so Long.parseLong won't throw on those.
                try {
                    val padded = raw.padStart(8, '0')
                    val argb = java.lang.Long.parseLong(padded, 16).toInt()
                    val next = Color(argb, true)
                    if (next != value.get()) {
                        value.set(next, saveImmediately = false)
                        // Keep the HSB square + strip markers in lock-step with
                        // the new RGB so the picker doesn't lie about the color.
                        value.setupSliders(next)
                    }
                } catch (_: NumberFormatException) {
                    // Mid-typing state — ignore until input is a valid hex.
                }
            }
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
        draggingColorComponent = null
        draggingRangeHandle = null
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

    fun colorComponentFor(value: Value<*>) = if (draggingValue === value) draggingColorComponent else null

    fun rangeHandleFor(value: Value<*>) = if (draggingValue === value) draggingRangeHandle else null

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
    private const val SLIDER_TRACK_HEIGHT = 2F
    private const val SLIDER_FILL_HEIGHT = 3F
    private const val SLIDER_KNOB_RADIUS = 4F

    // Modern color-picker geometry. The square is sized to the row width so it
    // scales gracefully between narrow column-deck rows and wide sidebar modules.
    // height() assumes the design width PICKER_DESIGN_SQUARE; rendering clamps the
    // square to whatever the row actually allows.
    private const val PICKER_INSET_TOP = 2F
    private const val PICKER_INSET_BOTTOM = 2F
    private const val PICKER_INSET_SIDE = 6F
    private const val PICKER_GAP = 6F
    private const val PICKER_STRIP_HEIGHT = 8F
    private const val PICKER_CORNER_RADIUS = 3F
    private const val PICKER_KNOB_RADIUS = 4F
    // Public so out-of-row callers (e.g. the custom-theme editor in
    // ModernClickGuiScreen) can size the picker's anchor rectangle to match
    // the layout the picker would claim if it were a real ColorValue row.
    const val PICKER_DESIGN_SQUARE = 110F
    const val PICKER_DESIGN_TOTAL =
        PICKER_INSET_TOP + PICKER_DESIGN_SQUARE + (PICKER_GAP + PICKER_STRIP_HEIGHT) * 2F + PICKER_INSET_BOTTOM

    // Side panel (HEX entry + R/G/B/A mini-sliders) appears beside the picker when
    // the row is wide enough to host it. Sized so column-deck rows (~134 px) stay
    // tightly packed while sidebar-list rows (~300+ px) gain a precision entry.
    private const val PICKER_SIDE_PANEL_WIDTH = 150F
    private const val PICKER_SIDE_PANEL_ROW_HEIGHT = 22F
    private const val PICKER_SIDE_PANEL_ROWS = 5 // HEX + R + G + B + A
    private const val PICKER_SIDE_PANEL_HEADER_GAP = 4F
    private const val PICKER_MIN_WIDTH_FOR_PANEL =
        PICKER_INSET_SIDE + PICKER_DESIGN_SQUARE + PICKER_GAP + PICKER_SIDE_PANEL_WIDTH + PICKER_INSET_SIDE

    fun height(value: Value<*>) = when (value) {
        is IntValue, is FloatValue, is BlockValue, is IntRangeValue, is FloatRangeValue -> SLIDER_ROW_HEIGHT
        is ColorValue -> ROW_HEIGHT + if (value.showPicker) PICKER_DESIGN_TOTAL else 0F
        else -> ROW_HEIGHT
    }

    fun draw(value: Value<*>, rect: UiRect, theme: UiTheme, mouseX: Int, mouseY: Int, state: ValueControlState) {
        when (value) {
            is BoolValue -> drawBoolean(value, rect, theme, state)
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
            is ColorValue -> drawColor(value, rect, theme, state)
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
                state.startDragging(value, rangeHandle = selectRangeHandle(value, rect, mouseX))
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

                // Hits inside the expanded picker take precedence over toggling rainbow.
                // HEX field is its own path: it does not engage the slider-drag flow
                // and instead opens a text-input focus keyed to this value.
                if (value.showPicker && colorPickerHexHit(value, rect, mouseX, mouseY)) {
                    state.focusHexText(value)
                    state.markDirty()
                    UiSound.click()
                    return true
                }

                val zone = if (value.showPicker) colorPickerZoneAt(value, rect, mouseX, mouseY) else null
                if (zone != null) {
                    state.startDragging(value, colorComponent = zone)
                    updateColorPicker(value, rect, mouseX, mouseY, zone)
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

    fun drag(value: Value<*>, rect: UiRect, mouseX: Int, mouseY: Int, state: ValueControlState, onChanged: () -> Unit) {
        if (state.draggingValue !== value) {
            return
        }

        val changed = when (value) {
            is IntValue, is FloatValue, is BlockValue -> updateSlider(value, rect, mouseX, state)
            is IntRangeValue, is FloatRangeValue -> updateRangeSlider(value, rect, mouseX, state)
            is ColorValue -> updateColorPicker(value, rect, mouseX, mouseY, state.colorComponentFor(value))
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

    private fun drawBoolean(value: BoolValue, rect: UiRect, theme: UiTheme, state: ValueControlState) {
        val label = Fonts.fontRegular30.trimToWidthWithEllipsis(value.name, (rect.width - 31F).roundToInt())
        val enabled = value.get()

        Fonts.fontRegular30.drawString(label, rect.x + 5F, rect.y + 5F, theme.textPrimary.rgb)
        drawSwitch(
            enabled,
            rect.right - 27F,
            rect.y + (rect.height - 10F) / 2F,
            22F,
            10F,
            8F,
            theme,
            state,
            "value:${System.identityHashCode(value)}"
        )
    }

    private fun drawSwitch(
        enabled: Boolean,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        knobSize: Float,
        theme: UiTheme,
        state: ValueControlState,
        key: String
    ) {
        val progress = state.animatedFloat("switch:$key", if (enabled) 1F else 0F, 18F)
        val inset = (height - knobSize) / 2F
        val offX = x + inset
        val onX = x + width - knobSize - inset
        val knobX = offX + (onX - offX) * progress
        val knobY = y + inset
        val trackColor = mixColor(Color(70, 72, 82, 210), theme.accent, progress)

        drawRoundedRect(x, y, x + width, y + height, trackColor.rgb, height / 2F)
        drawRoundedRect(knobX, knobY, knobX + knobSize, knobY + knobSize, theme.textPrimary.rgb, knobSize / 2F)
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

    private fun drawColor(value: ColorValue, rect: UiRect, theme: UiTheme, state: ValueControlState) {
        // Header row: name + R/G/B/A preview, or "Rainbow" when in rainbow mode.
        val colorStateText = if (value.rainbow) "Rainbow" else rgbaText(value.get())
        val label = Fonts.fontRegular30.trimToWidthWithEllipsis(
            "${value.name}: $colorStateText",
            (rect.width - 32F).roundToInt()
        )
        Fonts.fontRegular30.drawString(label, rect.x + 5F, rect.y + 5F, theme.textPrimary.rgb)

        // Trailing live swatch. In rainbow mode the swatch animates with the rainbow palette.
        val swatchColor = value.selectedColor()
        val swatchX = rect.right - 22F
        val swatchY = rect.y + 4F
        drawRoundedRect(
            swatchX - 1F,
            swatchY - 1F,
            swatchX + 17F,
            swatchY + 11F,
            theme.textMuted.withAlpha(140).rgb,
            3F
        )
        drawRoundedRect(
            swatchX,
            swatchY,
            swatchX + 16F,
            swatchY + 10F,
            swatchColor.rgb,
            3F
        )

        if (!value.showPicker) {
            return
        }

        drawColorPicker(value, rect, theme, state)
    }

    /**
     * Layout produced by [colorPickerLayout]. Public so external renderers
     * (e.g. the theme editor) can mirror the picker's geometry for hit-testing
     * or for sizing sibling UI without re-deriving the geometry constants.
     */
    data class ColorPickerLayout(
        val square: UiRect,
        val hueStrip: UiRect,
        val alphaStrip: UiRect,
        val sidePanel: UiRect?,
        val sidePanelRows: SidePanelRows?,
    )

    /**
     * Layout for the optional side panel (HEX + RGB/Alpha sliders) that
     * appears next to the HSB square on wide rows. See
     * [colorPickerLayout] for the conditions under which it is rendered.
     */
    data class SidePanelRows(
        val hexField: UiRect,
        val redRow: UiRect,
        val greenRow: UiRect,
        val blueRow: UiRect,
        val alphaRow: UiRect,
    )

    fun colorPickerLayout(value: ColorValue, rect: UiRect): ColorPickerLayout {
        // Sized to the design square, but clamped so the picker never exceeds the
        // row's actual width (which can be narrow inside column-deck modules).
        val maxWidth = max(40F, rect.width - PICKER_INSET_SIDE * 2F)
        val squareSize = min(PICKER_DESIGN_SQUARE, maxWidth)
        val squareY = rect.y + ROW_HEIGHT + PICKER_INSET_TOP
        val stripY = squareY + squareSize + PICKER_GAP
        val alphaY = stripY + PICKER_STRIP_HEIGHT + PICKER_GAP

        // Picker is left-aligned to the row inset rather than centered, so it stops
        // floating when the row has lots of horizontal room (e.g. sidebar modules).
        val pickerLeft = rect.x + PICKER_INSET_SIDE
        val square = UiRect(pickerLeft, squareY, squareSize, squareSize)
        val hue = UiRect(pickerLeft, stripY, squareSize, PICKER_STRIP_HEIGHT)
        val alpha = UiRect(pickerLeft, alphaY, squareSize, PICKER_STRIP_HEIGHT)

        // Side panel only renders when the row is wide enough to host it. Tight
        // rows (column-deck) skip it and keep the existing stacked layout.
        val canShowPanel = rect.width >= PICKER_MIN_WIDTH_FOR_PANEL && squareSize >= PICKER_DESIGN_SQUARE * 0.7F
        if (!canShowPanel) {
            return ColorPickerLayout(square, hue, alpha, null, null)
        }

        val panelLeft = square.right + PICKER_GAP
        val panelRightConstraint = rect.right - PICKER_INSET_SIDE
        val panelWidth = max(60F, panelRightConstraint - panelLeft)
        // Match the square's vertical extent so the panel aligns visually with the
        // picker's center axis.
        val panelHeight = squareSize
        val panel = UiRect(panelLeft, square.y, panelWidth, panelHeight)
        val rowH = (panelHeight - PICKER_SIDE_PANEL_HEADER_GAP) / PICKER_SIDE_PANEL_ROWS
        val rows = SidePanelRows(
            hexField  = UiRect(panel.x, panel.y,                                 panel.width, rowH),
            redRow    = UiRect(panel.x, panel.y + rowH,                          panel.width, rowH),
            greenRow  = UiRect(panel.x, panel.y + rowH * 2F,                     panel.width, rowH),
            blueRow   = UiRect(panel.x, panel.y + rowH * 3F,                     panel.width, rowH),
            alphaRow  = UiRect(panel.x, panel.y + rowH * 4F,                     panel.width, rowH),
        )
        return ColorPickerLayout(square, hue, alpha, panel, rows)
    }

    fun drawColorPicker(value: ColorValue, rect: UiRect, theme: UiTheme, state: ValueControlState) {
        val layout = colorPickerLayout(value, rect)
        val radius = PICKER_CORNER_RADIUS

        val square = layout.square
        // 1. The HSB square (left/right = saturation, top/bottom = brightness/y).
        //    Hue is the slider value; rendered via the shader so it remains a stable
        //    GPU-updated gradient rather than a per-pixel CPU repaint.
        SbPickerShader.renderPicker(
            square.x,
            square.y,
            square.right,
            square.bottom,
            passMode = 0, // MODE_HSB
            passHue = value.hueSliderY,
            passBaseColor = null,
            passCornerRadius = radius,
        )

        // Overlay a marker indicating the current saturation/value selection so
        // users can scrub back to the same point. The marker is a small white ring.
        val markerX = square.x + square.width * value.colorPickerPos.x
        val markerY = square.y + square.height * value.colorPickerPos.y
        drawColorPickerMarker(markerX, markerY)

        // 2. The hue strip — vertical rainbow regardless of mode. Border accent so
        //    the strip looks like a discrete control next to the square.
        val hue = layout.hueStrip
        SbPickerShader.renderPicker(
            hue.x,
            hue.y,
            hue.right,
            hue.bottom,
            passMode = 1, // MODE_HUE
            passHue = 0F,
            passBaseColor = null,
            passCornerRadius = radius,
        )
        // 3. The alpha strip. Draw a checker pattern first so the user's alpha
        //    selection is visible against a transparent-friendly background.
        val alpha = layout.alphaStrip
        SbPickerShader.renderPicker(
            alpha.x,
            alpha.y,
            alpha.right,
            alpha.bottom,
            passMode = 3, // MODE_CHECKER
            passHue = 0F,
            passBaseColor = null,
            passCornerRadius = radius,
        )
        SbPickerShader.renderPicker(
            alpha.x,
            alpha.y,
            alpha.right,
            alpha.bottom,
            passMode = 2, // MODE_ALPHA
            passHue = 0F,
            passBaseColor = value.selectedColor(),
            passCornerRadius = radius,
        )

        // Hue and alpha knobs are drawn on top so their position is always
        // readable against the gradient. Both strips are horizontal, so the
        // knob travels along the strip's width and stays on the mid-line.
        drawColorPickerKnob(
            hue.x + hue.width * value.hueSliderY,
            hue.y + hue.height / 2F,
        )
        drawColorPickerKnob(
            alpha.x + alpha.width * value.opacitySliderY,
            alpha.y + alpha.height / 2F,
        )

        // 4. (Wide rows only) Side panel: HEX entry + R/G/B/A compact bars.
        layout.sidePanelRows?.let { rows ->
            drawColorSidePanel(rows, value, theme, state)
        }

        // When rainbow is on, the picker is read-only — let the user know with a
        // subtle tint spanning the full picker region (and the side panel, where
        // present), and avoid drawing click affordances.
        if (value.rainbow) {
            val tintBottom = layout.sidePanel?.bottom ?: alpha.bottom
            val tintRight = layout.sidePanel?.right ?: rect.right
            drawRect(rect.x, rect.y + ROW_HEIGHT, tintRight, tintBottom, theme.textMuted.withAlpha(60).rgb)
        }
    }

    private fun drawColorSidePanel(
        rows: SidePanelRows,
        value: ColorValue,
        theme: UiTheme,
        state: ValueControlState
    ) {
        // Surface so the panel reads as a discrete control rather than floating
        // chrome; muted so the picker gradient stays the visual center of mass.
        val panel = UiRect(
            rows.hexField.x,
            rows.hexField.y,
            rows.hexField.width,
            rows.alphaRow.bottom - rows.hexField.y
        )
        drawRoundedRect(
            panel.x,
            panel.y,
            panel.right,
            panel.bottom,
            Color(15, 18, 28, 200).rgb,
            PICKER_CORNER_RADIUS
        )

        val color = value.get()
        drawColorSidePanelHex(rows.hexField, color, value, state, theme)
        drawColorSidePanelChannel(rows.redRow,   "R", color.red,   Color(225, 78, 78), theme)
        drawColorSidePanelChannel(rows.greenRow, "G", color.green, Color(77, 196, 110), theme)
        drawColorSidePanelChannel(rows.blueRow,  "B", color.blue,  Color(88, 142, 242), theme)
        drawColorSidePanelChannel(rows.alphaRow, "A", color.alpha, theme.accent,         theme)
    }

    private fun drawColorSidePanelHex(
        field: UiRect,
        color: Color,
        value: ColorValue,
        state: ValueControlState,
        theme: UiTheme,
    ) {
        // focusedText is shared across all value rows. Identify the focused
        // instance by reference against this row's ColorValue — startDragging
        // clears it only when the user clicks a different Value<*>.
        val focused = state.focusedText?.takeIf { it.value === value }
        val hex = focused?.string ?: "#%08X".format(color.rgb)

        // Pill background so the field reads as an input slot.
        drawRoundedRect(
            field.x + 2F,
            field.y + 2F,
            field.right - 2F,
            field.bottom - 2F,
            Color(8, 10, 16, 220).rgb,
            (field.height - 4F) / 2F
        )

        val labelX = field.x + 8F
        val textY = field.y + (field.height - Fonts.fontRegular30.fontHeight) / 2F
        Fonts.fontRegular30.drawString(
            "HEX",
            labelX,
            textY,
            theme.textMuted.withAlpha(200).rgb
        )
        val labelEnd = labelX + Fonts.fontRegular30.getStringWidth("HEX") + 6F

        // Trim text width to fit the remaining pill width.
        val maxTextWidth = (field.right - labelEnd - 8F).toInt().coerceAtLeast(0)
        val displayText =
            if (Fonts.fontRegular30.getStringWidth(hex) <= maxTextWidth) hex
            else Fonts.fontRegular30.trimToWidthWithEllipsis(hex, maxTextWidth)
        Fonts.fontRegular30.drawString(displayText, labelEnd, textY, theme.textPrimary.rgb)

        if (focused != null) {
            val cursorX = (labelEnd + Fonts.fontRegular30.getStringWidth(focused.string.take(focused.cursorIndex)))
                .coerceAtMost(field.right - 8F)
            drawRect(cursorX, field.y + 4F, cursorX + 1F, field.bottom - 4F, theme.accent.rgb)
            drawRect(field.x + 4F, field.bottom - 4F, field.right - 4F, field.bottom - 2.5F, theme.accent.withAlpha(160).rgb)
        }
    }

    private fun drawColorSidePanelChannel(
        row: UiRect,
        label: String,
        current: Int,
        trackTint: Color,
        theme: UiTheme,
    ) {
        val rowHeight = row.height
        val trackHeight = SLIDER_TRACK_HEIGHT
        val trackY = row.y + (rowHeight - trackHeight) / 2F
        val trackX = row.x + 28F
        val trackRight = row.right - 60F
        val trackWidth = max(1F, trackRight - trackX)
        val progress = (current / 255F).coerceIn(0F, 1F)
        val fillEnd = trackX + trackWidth * progress

        val textY = row.y + (rowHeight - Fonts.fontRegular30.fontHeight) / 2F
        val labelText = "$label: $current"
        val valueX = trackRight + 6F
        Fonts.fontRegular30.drawString(
            labelText,
            valueX,
            textY,
            theme.textMuted.withAlpha(190).rgb
        )

        // Compact track: muted background plus accent fill up to the channel value.
        drawRoundedRect(
            trackX,
            trackY,
            trackRight,
            trackY + trackHeight,
            theme.textMuted.withAlpha(120).rgb,
            trackHeight / 2F
        )
        if (fillEnd > trackX) {
            drawRoundedRect(
                trackX,
                trackY,
                fillEnd,
                trackY + trackHeight,
                trackTint.withAlpha(220).rgb,
                trackHeight / 2F
            )
        }
        // No knob at this scale — a 22 px row can't host a knob effectively, and
        // the filled track already conveys the value.
    }

    private fun drawColorPickerMarker(centerX: Float, centerY: Float) {
        // A 4 px white ring centered on the cursor's stored HSB position. White is
        // used so it stays visible against any saturation/value combination.
        val r = 4F
        drawRoundedRect(centerX - r, centerY - r, centerX + r, centerY + r, Color(255, 255, 255, 220).rgb, r)
    }

    private fun drawColorPickerKnob(centerX: Float, centerY: Float) {
        drawRoundedRect(
            centerX - PICKER_KNOB_RADIUS,
            centerY - PICKER_KNOB_RADIUS,
            centerX + PICKER_KNOB_RADIUS,
            centerY + PICKER_KNOB_RADIUS,
            Color(255, 255, 255, 230).rgb,
            PICKER_KNOB_RADIUS
        )
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

        Fonts.fontRegular30.drawString(label, rect.x + 5F, rect.y + 4F, theme.textPrimary.rgb)
        drawSliderTrack(track, theme.textMuted.withAlpha(155))
        drawSliderFill(track, track.x, fill, theme.accent)
        drawSliderKnob(fill, track, theme.textPrimary)
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
        drawSliderTrack(track, theme.textMuted.withAlpha(155))
        drawSliderFill(track, firstX, lastX, theme.accent)
        drawSliderKnob(firstX, track, theme.textPrimary)
        drawSliderKnob(lastX, track, theme.textPrimary)
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

    private fun selectRangeHandle(value: Value<*>, rect: UiRect, mouseX: Int): RangeSlider {
        val track = sliderTrack(rect)

        return when (value) {
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
                chooseRangeHandle(mouseX, firstX, lastX, null)
            }

            is FloatRangeValue -> {
                val firstX = track.x + track.width * sliderProgress(value.minimum, value.maximum, value.get().start)
                val lastX = track.x + track.width * sliderProgress(value.minimum, value.maximum, value.get().endInclusive)
                chooseRangeHandle(mouseX, firstX, lastX, null)
            }

            else -> RangeSlider.LEFT
        }
    }

    private fun updateRangeSlider(value: Value<*>, rect: UiRect, mouseX: Int, state: ValueControlState): Boolean {
        val handle = state.rangeHandleFor(value) ?: return false
        val track = sliderTrack(rect)
        val percent = ((mouseX - track.x) / track.width).coerceIn(0F, 1F)
        val changed = when (value) {
            is IntRangeValue -> {
                val current = value.get()
                val next = (value.minimum + (value.maximum - value.minimum) * percent).roundToInt()
                when (handle) {
                    RangeSlider.LEFT -> value.setFirst(next.coerceIn(value.minimum, current.last), false)
                    RangeSlider.RIGHT -> value.setLast(next.coerceIn(current.first, value.maximum), false)
                }
            }

            is FloatRangeValue -> {
                val current = value.get()
                val next = value.minimum + (value.maximum - value.minimum) * percent
                when (handle) {
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

    fun colorPickerZoneAt(value: ColorValue, rect: UiRect, mouseX: Int, mouseY: Int): SliderType? {
        if (!value.showPicker) return null
        val layout = colorPickerLayout(value, rect)
        val padX = PICKER_KNOB_RADIUS

        // Side panel rows come first so the side area fully absorbs clicks before
        // we fall back to the picker square / strips. If a hit is found here it
        // wins; otherwise we keep checking.
        layout.sidePanelRows?.let { rows ->
            fun inRow(row: UiRect, type: SliderType) =
                if (mouseX.toFloat() in row.x..row.right && mouseY.toFloat() in row.y..row.bottom) type else null
            inRow(rows.redRow, SliderType.RED)?.let { return it }
            inRow(rows.greenRow, SliderType.GREEN)?.let { return it }
            inRow(rows.blueRow, SliderType.BLUE)?.let { return it }
            inRow(rows.alphaRow, SliderType.OPACITY)?.let { return it }
        }

        // Hue strip first — it's drawn over the square's right edge in some layouts.
        if (mouseY.toFloat() in layout.hueStrip.y..layout.hueStrip.bottom &&
            mouseX.toFloat() in layout.hueStrip.x - padX..layout.hueStrip.right + padX
        ) {
            return SliderType.HUE
        }
        if (mouseY.toFloat() in layout.alphaStrip.y..layout.alphaStrip.bottom &&
            mouseX.toFloat() in layout.alphaStrip.x - padX..layout.alphaStrip.right + padX
        ) {
            return SliderType.OPACITY
        }
        if (mouseY.toFloat() in layout.square.y..layout.square.bottom &&
            mouseX.toFloat() in layout.square.x..layout.square.right
        ) {
            return SliderType.COLOR
        }
        return null
    }

    /** True iff the cursor hits the side panel's HEX field (for click routing). */
    fun colorPickerHexHit(value: ColorValue, rect: UiRect, mouseX: Int, mouseY: Int): Boolean {
        if (!value.showPicker) return false
        val rows = colorPickerLayout(value, rect).sidePanelRows ?: return false
        val fx = rows.hexField.x.toFloat()
        val fy = rows.hexField.y.toFloat()
        val fr = rows.hexField.right.toFloat()
        val fb = rows.hexField.bottom.toFloat()
        return mouseX.toFloat() in fx..fr && mouseY.toFloat() in fy..fb
    }

    fun updateColorPicker(
        value: ColorValue,
        rect: UiRect,
        mouseX: Int,
        mouseY: Int,
        component: SliderType?
    ): Boolean {
        if (component == null) return false
        val layout = colorPickerLayout(value, rect)
        if (layout.square.width <= 0F) return false

        when (component) {
            SliderType.COLOR -> {
                // Convert local pointer position to saturation/value fractions.
                // Clamp to the square so dragging off-edge doesn't jump to the
                // opposite side of the gradient.
                val fx = ((mouseX - layout.square.x) / layout.square.width).coerceIn(0F, 1F)
                val fy = ((mouseY - layout.square.y) / layout.square.height).coerceIn(0F, 1F)
                value.colorPickerPos.set(fx, fy)
            }
            SliderType.HUE -> {
                // Hue strip is horizontal (long x, short y). Map the cursor's
                // horizontal fraction to hue so the user can slide smoothly across
                // the strip; converting from vertical mouse position would squash
                // every change into the strip's 8px height and produce jumps.
                val fx = ((mouseX - layout.hueStrip.x) / layout.hueStrip.width).coerceIn(0F, 1F)
                value.hueSliderY = fx
            }
            SliderType.OPACITY -> {
                val fx = ((mouseX - layout.alphaStrip.x) / layout.alphaStrip.width).coerceIn(0F, 1F)
                value.opacitySliderY = fx
            }
            SliderType.RED, SliderType.GREEN, SliderType.BLUE -> {
                // RGB drags bypass the HSB reconstruction step below: we modify
                // exactly the touched channel and resync the HSB state to keep
                // the picker square consistent with the new color.
                val row = layout.sidePanelRows ?: return false
                val targetRow = when (component) {
                    SliderType.RED -> row.redRow
                    SliderType.GREEN -> row.greenRow
                    SliderType.BLUE -> row.blueRow
                    else -> return false
                }
                val fx = ((mouseX - targetRow.x) / targetRow.width).coerceIn(0F, 1F)
                val intValue = (fx * 255F).roundToInt()
                val cur = value.get()
                val next = when (component) {
                    SliderType.RED -> Color(intValue, cur.green, cur.blue, cur.alpha)
                    SliderType.GREEN -> Color(cur.red, intValue, cur.blue, cur.alpha)
                    SliderType.BLUE -> Color(cur.red, cur.green, intValue, cur.alpha)
                    else -> cur
                }
                if (value.rainbow) value.rainbow = false
                value.changeValue(next)
                value.setupSliders(next)
                return true
            }
        }

        val newColor = Color(
            Color.HSBtoRGB(value.hueSliderY, value.colorPickerPos.x, 1 - value.colorPickerPos.y),
            true,
        ).withAlpha((value.opacitySliderY * 255F).roundToInt())

        val previous = value.get()
        if (value.rainbow || newColor == previous) {
            // Disabling rainbow is enough; user already saw the change in sliders.
            if (value.rainbow) {
                value.rainbow = false
            } else {
                return false
            }
        }
        value.changeValue(newColor)
        return true
    }

    private fun sliderTrack(rect: UiRect) = UiRect(rect.x + 7F, rect.bottom - 7F, rect.width - 14F, 1F)

    private fun drawSliderTrack(track: UiRect, color: Color) {
        drawSliderSegment(track, track.x, track.right, SLIDER_TRACK_HEIGHT, color)
    }

    private fun drawSliderFill(track: UiRect, fromX: Float, toX: Float, color: Color) {
        val start = min(fromX, toX).coerceIn(track.x, track.right)
        val end = max(fromX, toX).coerceIn(track.x, track.right)

        if (end <= start) {
            return
        }

        if (ThemeResolver.gradientEnabled) {
            // Horizontal accent → accentMuted gradient tracks the active theme.
            // Rounded ends preserved via drawRoundedGradientRect so the slider
            // keeps the same pill geometry as the solid fallback.
            val centerY = sliderCenterY(track)
            val halfHeight = SLIDER_FILL_HEIGHT / 2F
            drawRoundedGradientRect(
                start, centerY - halfHeight, end, centerY + halfHeight,
                color.rgb, ThemeResolver.current.accentMuted.rgb, halfHeight
            )
            return
        }

        drawSliderSegment(track, start, end, SLIDER_FILL_HEIGHT, color)
    }

    private fun drawSliderSegment(track: UiRect, start: Float, end: Float, height: Float, color: Color) {
        if (end <= start) {
            return
        }

        val centerY = sliderCenterY(track)
        val halfHeight = height / 2F
        val width = end - start

        if (width <= height) {
            drawRoundedRect(start, centerY - halfHeight, end, centerY + halfHeight, color.rgb, width / 2F)
            return
        }

        drawRoundedRect(start, centerY - halfHeight, start + height, centerY + halfHeight, color.rgb, halfHeight)
        drawRect(start + halfHeight, centerY - halfHeight, end - halfHeight, centerY + halfHeight, color.rgb)
        drawRoundedRect(end - height, centerY - halfHeight, end, centerY + halfHeight, color.rgb, halfHeight)
    }

    private fun drawSliderKnob(
        centerX: Float,
        track: UiRect,
        color: Color,
        radius: Float = SLIDER_KNOB_RADIUS
    ) {
        val centerY = sliderCenterY(track)

        drawRoundedRect(centerX - radius, centerY - radius, centerX + radius, centerY + radius, color.rgb, radius)
    }

    private fun sliderCenterY(track: UiRect) = track.y + track.height / 2F

    private fun chooseRangeHandle(
        mouseX: Int,
        firstX: Float,
        lastX: Float,
        previous: RangeSlider?
    ): RangeSlider {
        val distanceToFirst = abs(mouseX - firstX)
        val distanceToLast = abs(mouseX - lastX)
        val midpoint = (firstX + lastX) / 2F

        if (abs(firstX - lastX) <= SLIDER_KNOB_RADIUS) {
            return if (mouseX >= midpoint) RangeSlider.RIGHT else RangeSlider.LEFT
        }

        return when {
            abs(distanceToFirst - distanceToLast) <= 0.5F -> previous ?: if (mouseX >= midpoint) {
                RangeSlider.RIGHT
            } else {
                RangeSlider.LEFT
            }

            distanceToFirst < distanceToLast -> RangeSlider.LEFT
            else -> RangeSlider.RIGHT
        }
    }

    private fun sliderProgress(minimum: Float, maximum: Float, current: Float): Float {
        if (maximum <= minimum) {
            return 0F
        }

        return ((current - minimum) / (maximum - minimum)).coerceIn(0F, 1F)
    }

    private fun rgbaText(color: Color) =
        "${color.red},${color.green},${color.blue},${color.alpha}"

    private fun mixColor(from: Color, to: Color, progress: Float): Color {
        val amount = progress.coerceIn(0F, 1F)

        return Color(
            (from.red + (to.red - from.red) * amount).roundToInt().coerceIn(0, 255),
            (from.green + (to.green - from.green) * amount).roundToInt().coerceIn(0, 255),
            (from.blue + (to.blue - from.blue) * amount).roundToInt().coerceIn(0, 255),
            (from.alpha + (to.alpha - from.alpha) * amount).roundToInt().coerceIn(0, 255)
        )
    }

    private fun formatFloat(value: Float): String {
        val rounded = (value * 100F).roundToInt() / 100F
        return if (rounded % 1F == 0F) {
            rounded.toInt().toString()
        } else {
            rounded.toString()
        }
    }
}
