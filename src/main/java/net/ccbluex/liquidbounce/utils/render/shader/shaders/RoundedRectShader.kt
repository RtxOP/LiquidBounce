/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.render.shader.shaders

import net.ccbluex.liquidbounce.utils.client.ClientUtils.LOGGER
import net.ccbluex.liquidbounce.utils.render.shader.Shader
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL20.*

object RoundedRectShader : Shader("rounded_rect.frag") {
    private var loggedFailure = false
    private var rectWidth = 0f
    private var rectHeight = 0f
    private var radii = FloatArray(4)
    private var fillColor = FloatArray(4)
    private var borderColor = FloatArray(4)
    private var borderWidth = 0f
    private var sideMask = floatArrayOf(1f, 1f, 1f, 1f)

    override fun setupUniforms() {
        setupUniform("rectSize")
        setupUniform("radii")
        setupUniform("fillColor")
        setupUniform("borderColor")
        setupUniform("borderWidth")
        setupUniform("sideMask")
    }

    override fun updateUniforms() {
        glUniform2f(getUniform("rectSize"), rectWidth, rectHeight)
        glUniform4f(getUniform("radii"), radii[0], radii[1], radii[2], radii[3])
        glUniform4f(getUniform("fillColor"), fillColor[0], fillColor[1], fillColor[2], fillColor[3])
        glUniform4f(getUniform("borderColor"), borderColor[0], borderColor[1], borderColor[2], borderColor[3])
        glUniform1f(getUniform("borderWidth"), borderWidth)
        glUniform4f(getUniform("sideMask"), sideMask[0], sideMask[1], sideMask[2], sideMask[3])
    }

    fun render(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radii: FloatArray,
        fillColor: FloatArray,
        borderColor: FloatArray,
        borderWidth: Float,
        sideMask: FloatArray
    ): Boolean {
        if (!loaded) return false

        var attribPushed = false
        var shaderStarted = false
        var previousProgram = 0

        return try {
            previousProgram = glGetInteger(GL_CURRENT_PROGRAM)

            this.rectWidth = x2 - x1
            this.rectHeight = y2 - y1
            this.radii = radii
            this.fillColor = fillColor
            this.borderColor = borderColor
            this.borderWidth = borderWidth
            this.sideMask = sideMask

            glPushAttrib(GL_ALL_ATTRIB_BITS)
            attribPushed = true
            shaderStarted = true
            startShader()
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
            glUseProgram(previousProgram)
            if (attribPushed) {
                glPopAttrib()
            }
            logFailure(e)
            false
        }
    }

    private fun drawQuad(x1: Float, y1: Float, x2: Float, y2: Float) {
        glBegin(GL_QUADS)
        glTexCoord2f(1f, 0f)
        glVertex2f(x2, y1)
        glTexCoord2f(0f, 0f)
        glVertex2f(x1, y1)
        glTexCoord2f(0f, 1f)
        glVertex2f(x1, y2)
        glTexCoord2f(1f, 1f)
        glVertex2f(x2, y2)
        glEnd()
    }

    private fun logFailure(e: Exception) {
        if (!loggedFailure) {
            LOGGER.error("${javaClass.name} failed; falling back to tessellator rendering.", e)
            loggedFailure = true
        }
    }
}
