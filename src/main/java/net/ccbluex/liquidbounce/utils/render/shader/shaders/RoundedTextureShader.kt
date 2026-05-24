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

object RoundedTextureShader : Shader("rounded_texture.frag") {
    private var loggedFailure = false
    private var rectWidth = 0f
    private var rectHeight = 0f
    private var radii = FloatArray(4)
    private var tintColor = floatArrayOf(1f, 1f, 1f, 1f)
    private var textureArea = floatArrayOf(0f, 0f, 1f, 1f)

    override fun setupUniforms() {
        setupUniform("textureSampler")
        setupUniform("rectSize")
        setupUniform("radii")
        setupUniform("tintColor")
        setupUniform("textureArea")
    }

    override fun updateUniforms() {
        glUniform1i(getUniform("textureSampler"), 0)
        glUniform2f(getUniform("rectSize"), rectWidth, rectHeight)
        glUniform4f(getUniform("radii"), radii[0], radii[1], radii[2], radii[3])
        glUniform4f(getUniform("tintColor"), tintColor[0], tintColor[1], tintColor[2], tintColor[3])
        glUniform4f(getUniform("textureArea"), textureArea[0], textureArea[1], textureArea[2], textureArea[3])
    }

    fun render(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radii: FloatArray,
        tintColor: FloatArray,
        textureArea: FloatArray = floatArrayOf(0f, 0f, 1f, 1f)
    ): Boolean {
        if (!loaded || x2 <= x1 || y2 <= y1) return false

        var shaderStarted = false
        var previousProgram = 0

        return try {
            previousProgram = glGetInteger(GL_CURRENT_PROGRAM)

            val scale = ScaledResolution(mc).scaleFactor.toFloat()

            this.rectWidth = (x2 - x1) * scale
            this.rectHeight = (y2 - y1) * scale
            this.radii = radii.map { it * scale }.toFloatArray()
            this.tintColor = tintColor
            this.textureArea = textureArea

            shaderStarted = true
            startShader()
            drawQuad(x1, y1, x2, y2)
            stopShader()
            shaderStarted = false
            glUseProgram(previousProgram)
            true
        } catch (e: Exception) {
            if (shaderStarted) {
                stopShader()
            }
            glUseProgram(previousProgram)
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
            LOGGER.error("${javaClass.name} failed; falling back to tessellator rendering.", e)
            loggedFailure = true
        }
    }
}
