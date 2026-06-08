/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.hud.designer

import net.ccbluex.liquidbounce.file.FileManager.hudConfig
import net.ccbluex.liquidbounce.file.FileManager.saveConfig
import net.ccbluex.liquidbounce.ui.client.common.UiRect
import net.ccbluex.liquidbounce.ui.client.common.UiSound
import net.ccbluex.liquidbounce.ui.client.common.UiTheme
import net.ccbluex.liquidbounce.ui.client.common.ValueControlState
import net.ccbluex.liquidbounce.ui.client.common.ValueControls
import net.ccbluex.liquidbounce.ui.client.common.trimToWidthWithEllipsis
import net.ccbluex.liquidbounce.ui.client.hud.HUD
import net.ccbluex.liquidbounce.ui.client.hud.HUD.ELEMENTS
import net.ccbluex.liquidbounce.ui.client.hud.element.Element
import net.ccbluex.liquidbounce.ui.client.hud.element.Side
import net.ccbluex.liquidbounce.ui.font.Fonts.fontSemibold35
import net.ccbluex.liquidbounce.utils.client.MinecraftInstance
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawRect
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawRoundedRect
import net.ccbluex.liquidbounce.utils.render.RenderUtils.makeScissorBox
import net.minecraft.client.gui.ScaledResolution
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11.*
import java.awt.Color
import kotlin.math.min
import kotlin.math.roundToInt

class EditorPanel(private val hudDesigner: GuiHudDesigner, var x: Int, var y: Int) : MinecraftInstance {

    var width = SELECTION_WIDTH
        private set
    var height = HEADER_HEIGHT
        private set
    var realHeight = HEADER_HEIGHT
        private set

    private var drag = false
    private var dragX = 0
    private var dragY = 0

    private var mouseDown = false
    private var rightMouseDown = false

    private var showConfirmation = false
    private var scroll = 0

    private val theme = UiTheme.MODERN
    private val valueControlState = ValueControlState()

    var create = false
    private var currentElement: Element? = null

    fun drawPanel(mouseX: Int, mouseY: Int, wheel: Int) {
        drag(mouseX, mouseY)

        if (currentElement != hudDesigner.selectedElement) {
            scroll = 0
            valueControlState.clearFocus()
        }
        currentElement = hudDesigner.selectedElement
        prepareWidth()

        val shouldScroll = realHeight > MAX_PANEL_HEIGHT
        if (shouldScroll && Mouse.hasWheel() && mouseX in x..x + width && mouseY in y..y + MAX_PANEL_HEIGHT) {
            scroll += when {
                wheel < 0 -> -SCROLL_STEP
                wheel > 0 -> SCROLL_STEP
                else -> 0
            }
        }

        drawPanelBackground()

        if (shouldScroll) {
            glPushMatrix()
            makeScissorBox(
                x.toFloat(),
                y + HEADER_HEIGHT.toFloat(),
                x + width.toFloat(),
                y + MAX_PANEL_HEIGHT.toFloat()
            )
            glEnable(GL_SCISSOR_TEST)
        }

        val title = when {
            create -> "Create Element"
            currentElement != null -> currentElement!!.name
            else -> "Element Editor"
        }
        val contentMouseY = if (mouseY in y + HEADER_HEIGHT..y + MAX_PANEL_HEIGHT) mouseY else Int.MIN_VALUE

        when {
            create -> drawCreate(mouseX, contentMouseY)
            currentElement != null -> drawEditor(mouseX, contentMouseY)
            else -> drawSelection(mouseX, contentMouseY)
        }

        clampScroll()

        if (shouldScroll) {
            glDisable(GL_SCISSOR_TEST)
            glPopMatrix()
            drawScrollBar()
        }

        drawHeader(title, mouseX, mouseY)
        updateMouseState()
    }

    fun isMouseInside(mouseX: Int, mouseY: Int): Boolean {
        val visibleHeight = min(realHeight, MAX_PANEL_HEIGHT).coerceAtLeast(HEADER_HEIGHT)
        return mouseX in x..x + width && mouseY in y..y + visibleHeight
    }

    fun mouseReleased() {
        valueControlState.release(::saveHudConfig)
    }

    fun keyTyped(typedChar: Char, keyCode: Int): Boolean {
        val element = currentElement ?: return false

        return ValueControls.keyTyped(typedChar, keyCode, valueControlState, ::saveHudConfig) {
            element.updateElement()
        }
    }

    fun clearFocus() {
        valueControlState.clearFocus()
    }

    private fun drawCreate(mouseX: Int, mouseY: Int) {
        height = HEADER_HEIGHT + CONTENT_PADDING + scroll
        realHeight = HEADER_HEIGHT + CONTENT_PADDING

        for ((elementClass, info) in ELEMENTS) {
            if (info.single && HUD.elements.any { it.javaClass == elementClass }) {
                continue
            }

            val row = nextRow(CONTROL_ROW_HEIGHT)
            if (drawActionRow(info.name, row, mouseX, mouseY)) {
                try {
                    val newElement = elementClass.newInstance()

                    if (newElement.createElement()) {
                        HUD.addElement(newElement)
                        saveHudConfig()
                    }
                } catch (exception: InstantiationException) {
                    exception.printStackTrace()
                } catch (exception: IllegalAccessException) {
                    exception.printStackTrace()
                }

                create = false
                UiSound.click()
            }
        }
    }

    private fun drawSelection(mouseX: Int, mouseY: Int) {
        height = HEADER_HEIGHT + CONTENT_PADDING + scroll
        realHeight = HEADER_HEIGHT + CONTENT_PADDING

        if (drawActionRow("Create element", nextRow(CONTROL_ROW_HEIGHT), mouseX, mouseY)) {
            create = true
            showConfirmation = false
            UiSound.click()
        }

        if (drawActionRow("Reset layout", nextRow(CONTROL_ROW_HEIGHT), mouseX, mouseY, danger = showConfirmation)) {
            showConfirmation = true
            UiSound.click()
        }

        drawSectionLabel("Available Elements")

        for (element in HUD.elements) {
            val selected = element == hudDesigner.selectedElement
            if (drawActionRow(element.name, nextRow(CONTROL_ROW_HEIGHT), mouseX, mouseY, active = selected)) {
                hudDesigner.selectedElement = element
                create = false
                UiSound.click()
            }
        }

        if (showConfirmation) {
            drawResetConfirmation(mouseX, mouseY)
        }
    }

    private fun drawEditor(mouseX: Int, mouseY: Int) {
        height = HEADER_HEIGHT + CONTENT_PADDING + scroll
        realHeight = HEADER_HEIGHT + CONTENT_PADDING
        width = INSPECTOR_WIDTH

        val element = currentElement ?: return

        drawReadOnlyRow("X", "${formatNumber(element.renderX)} (${formatNumber(element.x)})")
        drawReadOnlyRow("Y", "${formatNumber(element.renderY)} (${formatNumber(element.y)})")
        drawReadOnlyRow("Scale", formatNumber(element.scale))

        drawCycleRow("Horizontal", element.side.horizontal.sideName, mouseX, mouseY) {
            cycleHorizontalSide(element)
        }

        drawCycleRow("Vertical", element.side.vertical.sideName, mouseX, mouseY) {
            cycleVerticalSide(element)
        }

        val values = element.values.filter { it.shouldRender() }
        if (values.isNotEmpty()) {
            drawSectionLabel("Properties")
        }

        val leftClickPressed = Mouse.isButtonDown(0) && !mouseDown
        val rightClickPressed = Mouse.isButtonDown(1) && !rightMouseDown

        for (value in values) {
            val controlHeight = ValueControls.height(value).roundToInt()
            val rect = nextRow(controlHeight)

            ValueControls.draw(value, rect, theme, mouseX, mouseY, valueControlState)

            if (leftClickPressed) {
                ValueControls.click(value, rect, mouseX, mouseY, 0, valueControlState) {
                    element.updateElement()
                }
            }

            if (rightClickPressed) {
                ValueControls.click(value, rect, mouseX, mouseY, 1, valueControlState) {
                    element.updateElement()
                }
            }

            if (Mouse.isButtonDown(0)) {
                ValueControls.drag(value, rect, mouseX, valueControlState) {
                    element.updateElement()
                }
            }
        }
    }

    private fun drawResetConfirmation(mouseX: Int, mouseY: Int) {
        drawSectionLabel("Confirm Reset")

        val yes = nextHalfRow(left = true)
        val no = nextHalfRow(left = false, advance = true)

        if (drawActionRow("Yes", yes, mouseX, mouseY, danger = true)) {
            HUD.setDefault()
            showConfirmation = false
            saveHudConfig()
            UiSound.click()
        }

        if (drawActionRow("No", no, mouseX, mouseY)) {
            showConfirmation = false
            UiSound.click()
        }
    }

    private fun drawReadOnlyRow(label: String, value: String) {
        val rect = nextRow(CONTROL_ROW_HEIGHT)
        val display = "$label: $value"

        drawRoundedRect(rect.x, rect.y, rect.right, rect.bottom - 1F, theme.rowBackground.rgb, 3F)
        fontSemibold35.drawString(
            fontSemibold35.trimToWidthWithEllipsis(display, (rect.width - 10F).roundToInt()),
            rect.x + 5F,
            rect.y + 5F,
            theme.textPrimary.rgb
        )
    }

    private fun drawCycleRow(label: String, value: String, mouseX: Int, mouseY: Int, action: () -> Unit) {
        if (drawActionRow("$label: $value", nextRow(CONTROL_ROW_HEIGHT), mouseX, mouseY)) {
            action()
            currentElement?.updateElement()
            saveHudConfig()
            UiSound.click()
        }
    }

    private fun drawActionRow(
        label: String,
        rect: UiRect,
        mouseX: Int,
        mouseY: Int,
        active: Boolean = false,
        danger: Boolean = false
    ): Boolean {
        val hovered = rect.contains(mouseX, mouseY)
        val color = when {
            danger -> Color(98, 35, 49, 225)
            active -> theme.accentMuted
            hovered -> theme.rowHover
            else -> theme.rowBackground
        }

        drawRoundedRect(rect.x, rect.y, rect.right, rect.bottom - 1F, color.rgb, 3F)
        fontSemibold35.drawString(
            fontSemibold35.trimToWidthWithEllipsis(label, (rect.width - 10F).roundToInt()),
            rect.x + 5F,
            rect.y + 5F,
            theme.textPrimary.rgb
        )

        return hovered && Mouse.isButtonDown(0) && !mouseDown
    }

    private fun drawSectionLabel(label: String) {
        val labelY = y + height + 4F
        fontSemibold35.drawString(label, x + CONTENT_PADDING.toFloat(), labelY, theme.textMuted.rgb)
        height += SECTION_HEIGHT
        realHeight += SECTION_HEIGHT
    }

    private fun drawHeader(title: String, mouseX: Int, mouseY: Int) {
        val headerBottom = y + HEADER_HEIGHT

        drawRoundedRect(x.toFloat(), y.toFloat(), (x + width).toFloat(), headerBottom.toFloat(), theme.panelHeader.rgb, 5F)
        drawRect(x.toFloat(), y + HEADER_HEIGHT - 4F, (x + width).toFloat(), headerBottom.toFloat(), theme.panelHeader.rgb)

        val element = currentElement
        val deleteWidth = if (element != null && !element.info.force) {
            fontSemibold35.getStringWidth("Delete") + 12
        } else {
            0
        }
        val titleWidth = width - deleteWidth - 14

        fontSemibold35.drawString(
            fontSemibold35.trimToWidthWithEllipsis(title, titleWidth.coerceAtLeast(24)),
            x + 6F,
            y + 4F,
            theme.textPrimary.rgb
        )

        if (element == null || element.info.force) {
            return
        }

        val deleteRect = UiRect(
            x + width - deleteWidth - 4F,
            y + 2F,
            deleteWidth.toFloat(),
            HEADER_HEIGHT - 4F
        )
        val hovered = deleteRect.contains(mouseX, mouseY)

        if (hovered) {
            drawRoundedRect(deleteRect.x, deleteRect.y, deleteRect.right, deleteRect.bottom, Color(98, 35, 49, 225).rgb, 3F)
        }

        fontSemibold35.drawString("Delete", deleteRect.x + 5F, y + 4F, theme.textPrimary.rgb)

        if (hovered && Mouse.isButtonDown(0) && !mouseDown) {
            HUD.removeElement(hudDesigner, element)
            saveHudConfig()
            UiSound.click()
        }
    }

    private fun drawPanelBackground() {
        val visibleHeight = min(realHeight, MAX_PANEL_HEIGHT).coerceAtLeast(HEADER_HEIGHT)

        drawRoundedRect(
            x.toFloat(),
            y.toFloat(),
            (x + width).toFloat(),
            (y + visibleHeight).toFloat(),
            theme.panelBackground.rgb,
            5F
        )
    }

    private fun drawScrollBar() {
        val viewport = (MAX_PANEL_HEIGHT - HEADER_HEIGHT - 8F).coerceAtLeast(1F)
        val maxScroll = (realHeight - MAX_PANEL_HEIGHT).coerceAtLeast(1)
        val thumbHeight = (viewport * (MAX_PANEL_HEIGHT / realHeight.toFloat())).coerceIn(16F, viewport)
        val progress = (-scroll / maxScroll.toFloat()).coerceIn(0F, 1F)
        val trackX = x + width - 4F
        val thumbY = y + HEADER_HEIGHT + 4F + (viewport - thumbHeight) * progress

        drawRoundedRect(trackX, thumbY, trackX + 2F, thumbY + thumbHeight, theme.accent.rgb, 1F)
    }

    private fun nextRow(rowHeight: Int): UiRect {
        val rect = UiRect(
            x + CONTENT_PADDING.toFloat(),
            y + height.toFloat(),
            width - CONTENT_PADDING * 2F,
            rowHeight.toFloat()
        )

        height += rowHeight + ROW_GAP
        realHeight += rowHeight + ROW_GAP

        return rect
    }

    private fun nextHalfRow(left: Boolean, advance: Boolean = false): UiRect {
        val gap = ROW_GAP.toFloat()
        val rowWidth = (width - CONTENT_PADDING * 2F - gap) / 2F
        val rect = UiRect(
            x + CONTENT_PADDING + if (left) 0F else rowWidth + gap,
            y + height.toFloat(),
            rowWidth,
            CONTROL_ROW_HEIGHT.toFloat()
        )

        if (advance) {
            height += CONTROL_ROW_HEIGHT + ROW_GAP
            realHeight += CONTROL_ROW_HEIGHT + ROW_GAP
        }

        return rect
    }

    private fun cycleHorizontalSide(element: Element) {
        val values = Side.Horizontal.entries.toTypedArray()
        val currentIndex = values.indexOf(element.side.horizontal)
        val renderX = element.renderX

        element.side.horizontal = values[(currentIndex + 1) % values.size]
        element.x = when (element.side.horizontal) {
            Side.Horizontal.LEFT -> renderX
            Side.Horizontal.MIDDLE -> ScaledResolution(mc).scaledWidth / 2F - renderX
            Side.Horizontal.RIGHT -> ScaledResolution(mc).scaledWidth - renderX
        }
    }

    private fun cycleVerticalSide(element: Element) {
        val values = Side.Vertical.entries.toTypedArray()
        val currentIndex = values.indexOf(element.side.vertical)
        val renderY = element.renderY

        element.side.vertical = values[(currentIndex + 1) % values.size]
        element.y = when (element.side.vertical) {
            Side.Vertical.UP -> renderY
            Side.Vertical.MIDDLE -> ScaledResolution(mc).scaledHeight / 2F - renderY
            Side.Vertical.DOWN -> ScaledResolution(mc).scaledHeight - renderY
        }
    }

    private fun drag(mouseX: Int, mouseY: Int) {
        if (Mouse.isButtonDown(0) && !mouseDown && mouseX in x..x + width && mouseY in y..y + HEADER_HEIGHT) {
            drag = true
            dragX = mouseX - x
            dragY = mouseY - y
        }

        if (Mouse.isButtonDown(0) && drag) {
            x = mouseX - dragX
            y = mouseY - dragY
        } else {
            drag = false
        }
    }

    private fun prepareWidth() {
        width = when {
            currentElement != null -> INSPECTOR_WIDTH
            showConfirmation -> CONFIRMATION_WIDTH
            else -> SELECTION_WIDTH
        }
    }

    private fun clampScroll() {
        val minScroll = minOf(0, MAX_PANEL_HEIGHT - realHeight)
        scroll = scroll.coerceIn(minScroll, 0)
    }

    private fun updateMouseState() {
        val leftDown = Mouse.isButtonDown(0)
        val rightDown = Mouse.isButtonDown(1)

        if ((mouseDown && !leftDown) || (rightMouseDown && !rightDown)) {
            valueControlState.release(::saveHudConfig)
        }

        mouseDown = leftDown
        rightMouseDown = rightDown
    }

    private fun saveHudConfig() {
        saveConfig(hudConfig)
    }

    private fun formatNumber(number: Number) = "%.2f".format(number.toFloat())

    private companion object {
        const val HEADER_HEIGHT = 14
        const val CONTENT_PADDING = 5
        const val ROW_GAP = 3
        const val CONTROL_ROW_HEIGHT = 18
        const val SECTION_HEIGHT = 14
        const val MAX_PANEL_HEIGHT = 200
        const val SCROLL_STEP = 14
        const val SELECTION_WIDTH = 150
        const val CONFIRMATION_WIDTH = 190
        const val INSPECTOR_WIDTH = 194
    }
}
