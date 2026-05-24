/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.render.shader.shaders

import net.ccbluex.liquidbounce.utils.client.ClientUtils.LOGGER
import net.ccbluex.liquidbounce.utils.render.shader.Shader
import net.minecraft.client.gui.ScaledResolution
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL20.*

object RoundedGradientRectShader : Shader("rounded_gradient_rect.frag") {
    private var loggedFailure = false
    private var rectWidth = 0f
    private var rectHeight = 0f
    private var radii = FloatArray(4)
    private var startColor = FloatArray(4)
    private var endColor = FloatArray(4)

    private const val QUAD_PADDING = 1f

    override fun setupUniforms() {
        setupUniform("rectSize")
        setupUniform("radii")
        setupUniform("startColor")
        setupUniform("endColor")
    }

    override fun updateUniforms() {
        glUniform2f(getUniform("rectSize"), rectWidth, rectHeight)
        glUniform4f(getUniform("radii"), radii[0], radii[1], radii[2], radii[3])
        glUniform4f(getUniform("startColor"), startColor[0], startColor[1], startColor[2], startColor[3])
        glUniform4f(getUniform("endColor"), endColor[0], endColor[1], endColor[2], endColor[3])
    }

    fun render(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radii: FloatArray,
        startColor: FloatArray,
        endColor: FloatArray
    ): Boolean {
        if (!loaded || x2 <= x1 || y2 <= y1) return false

        var attribPushed = false
        var shaderStarted = false
        var previousProgram = 0

        return try {
            previousProgram = glGetInteger(GL_CURRENT_PROGRAM)

            val scale = ScaledResolution(mc).scaleFactor.toFloat()
            val width = x2 - x1
            val height = y2 - y1

            this.rectWidth = width * scale
            this.rectHeight = height * scale
            this.radii = radii.map { it * scale }.toFloatArray()
            this.startColor = startColor
            this.endColor = endColor

            glPushAttrib(GL_ALL_ATTRIB_BITS)
            attribPushed = true
            shaderStarted = true
            startShader()
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            glDisable(GL_TEXTURE_2D)
            drawQuad(x1, y1, x2, y2, QUAD_PADDING)
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

    private fun drawQuad(x1: Float, y1: Float, x2: Float, y2: Float, padding: Float) {
        val width = x2 - x1
        val height = y2 - y1
        val u1 = -padding / width
        val v1 = -padding / height
        val u2 = 1f + padding / width
        val v2 = 1f + padding / height

        glBegin(GL_QUADS)
        glTexCoord2f(u2, v1)
        glVertex2f(x2 + padding, y1 - padding)
        glTexCoord2f(u1, v1)
        glVertex2f(x1 - padding, y1 - padding)
        glTexCoord2f(u1, v2)
        glVertex2f(x1 - padding, y2 + padding)
        glTexCoord2f(u2, v2)
        glVertex2f(x2 + padding, y2 + padding)
        glEnd()
    }

    private fun logFailure(e: Exception) {
        if (!loggedFailure) {
            LOGGER.error("${javaClass.name} failed; falling back to tessellator rendering.", e)
            loggedFailure = true
        }
    }
}
