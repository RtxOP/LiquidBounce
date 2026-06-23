/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.render.shader.shaders

import net.ccbluex.liquidbounce.utils.client.ClientUtils.LOGGER
import net.ccbluex.liquidbounce.utils.render.shader.Shader
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL20.*
import java.awt.Color

/**
 * Fragment shader used by the modern ClickGUI color picker. Renders one of four
 * gradient masks (HSB saturation/value square, hue strip, alpha gradient, or
 * 8-cell checker background) and applies a rounded-corner alpha mask so the
 * rendered quad respects the requested radius without needing a stencil pass.
 */
object SbPickerShader : Shader("sb_picker.frag") {

    private const val MODE_HSB = 0
    private const val MODE_HUE = 1
    private const val MODE_ALPHA = 2
    private const val MODE_CHECKER = 3

    private var mode = MODE_HSB
    private var hue = 0F
    private val baseColor = floatArrayOf(1F, 1F, 1F, 1F)
    private var rectWidth = 0F
    private var rectHeight = 0F
    private var cornerRadius = 0F
    private var loggedFailure = false

    override fun setupUniforms() {
        setupUniform("mode")
        setupUniform("hue")
        setupUniform("baseColor")
        setupUniform("rectSize")
        setupUniform("cornerRadius")
    }

    override fun updateUniforms() {
        glUniform1i(getUniform("mode"), mode)
        glUniform1f(getUniform("hue"), hue)
        glUniform4f(getUniform("baseColor"), baseColor[0], baseColor[1], baseColor[2], baseColor[3])
        glUniform2f(getUniform("rectSize"), rectWidth, rectHeight)
        glUniform1f(getUniform("cornerRadius"), cornerRadius)
    }

    /**
     * Render a single picker rectangle via the shader.
     *
     * @param x1, y1, x2, y2 screen coords of the rectangle.
     * @param passMode one of MODE_HSB / MODE_HUE / MODE_ALPHA / MODE_CHECKER.
     * @param passHue 0..1 hue used for the HSB square.
     * @param passBaseColor color to render in the alpha gradient; ignored for other modes.
     * @param passCornerRadius pixel radius of the rounded corners.
     * @return true when rendering succeeded.
     */
    fun renderPicker(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        passMode: Int,
        passHue: Float,
        passBaseColor: Color?,
        passCornerRadius: Float
    ): Boolean {
        if (!loaded || x2 <= x1 || y2 <= y1) {
            return false
        }

        var previousProgram = 0
        var attribPushed = false
        var shaderStarted = false

        return try {
            previousProgram = glGetInteger(GL_CURRENT_PROGRAM)

            mode = passMode
            hue = passHue.coerceIn(0F, 1F)
            if (passBaseColor != null) {
                baseColor[0] = passBaseColor.red / 255F
                baseColor[1] = passBaseColor.green / 255F
                baseColor[2] = passBaseColor.blue / 255F
                baseColor[3] = passBaseColor.alpha / 255F
            } else {
                baseColor[0] = 1F
                baseColor[1] = 1F
                baseColor[2] = 1F
                baseColor[3] = 1F
            }
            rectWidth = x2 - x1
            rectHeight = y2 - y1
            cornerRadius = passCornerRadius

            glPushAttrib(GL_ALL_ATTRIB_BITS)
            attribPushed = true
            startShader()
            shaderStarted = true
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            glDisable(GL_TEXTURE_2D)
            drawQuad(x1, y1, x2, y2)
            stopShader()
            shaderStarted = false
            glUseProgram(previousProgram)
            glPopAttrib()
            attribPushed = false
            true
        } catch (e: Exception) {
            if (shaderStarted) {
                stopShader()
            }
            if (attribPushed) {
                glPopAttrib()
            }
            glUseProgram(previousProgram)
            if (!loggedFailure) {
                LOGGER.error("SbPickerShader failed; falling back to CPU rendering.", e)
                loggedFailure = true
            }
            false
        }
    }

    private fun drawQuad(x1: Float, y1: Float, x2: Float, y2: Float) {
        glBegin(GL_QUADS)
        glTexCoord2f(1F, 1F); glVertex2f(x2, y2)
        glTexCoord2f(0F, 1F); glVertex2f(x1, y2)
        glTexCoord2f(0F, 0F); glVertex2f(x1, y1)
        glTexCoord2f(1F, 0F); glVertex2f(x2, y1)
        glEnd()
    }
}
