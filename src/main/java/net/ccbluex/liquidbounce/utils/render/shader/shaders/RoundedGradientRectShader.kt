/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.render.shader.shaders

import net.ccbluex.liquidbounce.utils.render.shader.Shader
import net.minecraft.client.gui.ScaledResolution
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL20.*

object RoundedGradientRectShader : Shader("rounded_gradient_rect.frag") {
    private var rectWidth = 0f
    private var rectHeight = 0f
    private var radii = FloatArray(4)
    private var startColor = FloatArray(4)
    private var endColor = FloatArray(4)

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
    ) {
        if (x2 <= x1 || y2 <= y1) return
        check(loaded) { "${javaClass.name} is not loaded." }

        var shaderStarted = false
        var previousProgram = 0

        try {
            previousProgram = glGetInteger(GL_CURRENT_PROGRAM)
            clearGlErrors()

            val scale = ScaledResolution(mc).scaleFactor.toFloat()
            val width = x2 - x1
            val height = y2 - y1

            this.rectWidth = width * scale
            this.rectHeight = height * scale
            this.radii = radii.map { it * scale }.toFloatArray()
            this.startColor = startColor
            this.endColor = endColor

            glColor4f(1f, 1f, 1f, 1f)
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            shaderStarted = true
            startShader()
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            drawQuad(x1 - QUAD_PADDING, y1 - QUAD_PADDING, x2 + QUAD_PADDING, y2 + QUAD_PADDING)
            checkGlError("rounded gradient draw")
            stopShader()
            shaderStarted = false
            glUseProgram(previousProgram)
            glDisable(GL_BLEND)
        } catch (t: Throwable) {
            if (shaderStarted) {
                stopShader()
            }
            glUseProgram(previousProgram)
            glDisable(GL_BLEND)
            throw t
        }
    }

    private const val QUAD_PADDING = 1f

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

    private fun clearGlErrors() {
        while (glGetError() != GL_NO_ERROR) {
            // Clear stale errors so the draw check points at this render path.
        }
    }

    private fun checkGlError(stage: String) {
        val error = glGetError()
        check(error == GL_NO_ERROR) { "${javaClass.name} OpenGL error during $stage: 0x${error.toString(16)}" }
    }
}
