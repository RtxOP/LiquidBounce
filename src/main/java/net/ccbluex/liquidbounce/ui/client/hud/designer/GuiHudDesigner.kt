/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.hud.designer

import net.ccbluex.liquidbounce.file.FileManager.hudConfig
import net.ccbluex.liquidbounce.file.FileManager.saveConfig
import net.ccbluex.liquidbounce.ui.client.hud.HUD
import net.ccbluex.liquidbounce.ui.client.hud.element.Element
import net.minecraft.client.gui.GuiScreen
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse

class GuiHudDesigner : GuiScreen() {

    private var editorPanel = EditorPanel(this, 2, 2)

    var selectedElement: Element? = null
        set(value) {
            if (field != value) {
                editorPanel.clearFocus()
            }
            field = value
        }

    override fun initGui() {
        Keyboard.enableRepeatEvents(true)
        editorPanel = EditorPanel(this, width / 2, height / 2)
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        HUD.render(true)
        HUD.handleMouseMove(mouseX, mouseY)

        if (selectedElement !in HUD.elements)
            selectedElement = null

        val wheel = Mouse.getDWheel()

        editorPanel.drawPanel(mouseX, mouseY, wheel)

        if (wheel != 0 && !editorPanel.isMouseInside(mouseX, mouseY)) {
            for (element in HUD.elements) {
                if (element.isInBorder(
                        mouseX / element.scale - element.renderX,
                        mouseY / element.scale - element.renderY
                    )
                ) {
                    element.scale += if (wheel > 0) 0.05f else -0.05f
                    break
                }
            }
        }
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        super.mouseClicked(mouseX, mouseY, mouseButton)

        if (editorPanel.isMouseInside(mouseX, mouseY)) {
            return
        }

        HUD.handleMouseClick(mouseX, mouseY, mouseButton)

        selectedElement = null
        editorPanel.create = false

        if (mouseButton == 0) {
            for (element in HUD.elements) {
                if (element.isInBorder(
                        mouseX / element.scale - element.renderX,
                        mouseY / element.scale - element.renderY
                    )
                ) {
                    selectedElement = element
                    break
                }
            }
        }
    }

    override fun mouseReleased(mouseX: Int, mouseY: Int, state: Int) {
        super.mouseReleased(mouseX, mouseY, state)

        editorPanel.mouseReleased()
        HUD.handleMouseReleased()
    }

    override fun onGuiClosed() {
        Keyboard.enableRepeatEvents(false)
        editorPanel.mouseReleased()
        saveConfig(hudConfig)

        super.onGuiClosed()
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (editorPanel.keyTyped(typedChar, keyCode)) {
            super.keyTyped(typedChar, keyCode)
            return
        }

        when (keyCode) {
            Keyboard.KEY_DELETE -> if (selectedElement != null) {
                HUD.removeElement(this, selectedElement!!)
            }

            Keyboard.KEY_ESCAPE -> {
                selectedElement = null
                editorPanel.create = false
            }

            else -> HUD.handleKey(typedChar, keyCode)
        }

        super.keyTyped(typedChar, keyCode)
    }
}
