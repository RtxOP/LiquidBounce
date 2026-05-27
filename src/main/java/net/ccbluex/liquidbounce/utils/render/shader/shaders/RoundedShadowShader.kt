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

object RoundedShadowShader : Shader("rounded_shadow.frag") {
    private var rectWidth = 0f
    private var rectHeight = 0f
    private var shapeWidth = 0f
    private var shapeHeight = 0f
    private var radii = FloatArray(4)
    private var shadowColor = FloatArray(4)
    private var spread = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var loggedFailure = false

    override fun setupUniforms() {
        setupUniform("rectSize")
        setupUniform("shapeSize")
        setupUniform("radii")
        setupUniform("shadowColor")
        setupUniform("spread")
        setupUniform("offset")
    }

    override fun updateUniforms() {
        glUniform2f(getUniform("rectSize"), rectWidth, rectHeight)
        glUniform2f(getUniform("shapeSize"), shapeWidth, shapeHeight)
        glUniform4f(getUniform("radii"), radii[0], radii[1], radii[2], radii[3])
        glUniform4f(getUniform("shadowColor"), shadowColor[0], shadowColor[1], shadowColor[2], shadowColor[3])
        glUniform1f(getUniform("spread"), spread)
        glUniform2f(getUniform("offset"), offsetX, offsetY)
    }

    fun render(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radii: FloatArray,
        color: FloatArray,
        spread: Float,
        offsetX: Float,
        offsetY: Float
    ): Boolean {
        if (!loaded || x2 <= x1 || y2 <= y1 || spread <= 0f) return false

        var attribPushed = false
        var shaderStarted = false
        var previousProgram = 0

        return try {
            previousProgram = glGetInteger(GL_CURRENT_PROGRAM)

            val scale = ScaledResolution(mc).scaleFactor.toFloat()
            val padding = spread + maxOf(kotlin.math.abs(offsetX), kotlin.math.abs(offsetY))
            val width = x2 - x1 + padding * 2f
            val height = y2 - y1 + padding * 2f

            this.rectWidth = width * scale
            this.rectHeight = height * scale
            this.shapeWidth = (x2 - x1) * scale
            this.shapeHeight = (y2 - y1) * scale
            this.radii = radii.map { it * scale }.toFloatArray()
            this.shadowColor = color
            this.spread = spread * scale
            this.offsetX = (padding + offsetX) * scale
            this.offsetY = (padding + offsetY) * scale

            glPushAttrib(GL_ALL_ATTRIB_BITS)
            attribPushed = true
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            glDisable(GL_TEXTURE_2D)
            shaderStarted = true
            startShader()
            drawQuad(x1 - padding, y1 - padding, x2 + padding, y2 + padding)
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
        glTexCoord2f(0f, 0f)
        glVertex2f(x1, y1)
        glTexCoord2f(0f, 1f)
        glVertex2f(x1, y2)
        glTexCoord2f(1f, 1f)
        glVertex2f(x2, y2)
        glTexCoord2f(1f, 0f)
        glVertex2f(x2, y1)
        glEnd()
    }

    private fun logFailure(e: Exception) {
        if (!loggedFailure) {
            LOGGER.error("${javaClass.name} failed; skipping rounded shadow.", e)
            loggedFailure = true
        }
    }
}
