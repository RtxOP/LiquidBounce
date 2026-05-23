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

object CircleShader : Shader("circle.frag") {
    private var loggedFailure = false
    private var color = FloatArray(4)
    private var innerRadius = 0f
    private var outerRadius = 1f
    private var startAngle = 0f
    private var endAngle = (Math.PI * 2.0).toFloat()

    override fun setupUniforms() {
        setupUniform("color")
        setupUniform("innerRadius")
        setupUniform("outerRadius")
        setupUniform("startAngle")
        setupUniform("endAngle")
    }

    override fun updateUniforms() {
        glUniform4f(getUniform("color"), color[0], color[1], color[2], color[3])
        glUniform1f(getUniform("innerRadius"), innerRadius)
        glUniform1f(getUniform("outerRadius"), outerRadius)
        glUniform1f(getUniform("startAngle"), startAngle)
        glUniform1f(getUniform("endAngle"), endAngle)
    }

    fun render(
        x: Float,
        y: Float,
        radius: Float,
        color: FloatArray,
        innerRadius: Float,
        startAngle: Float,
        endAngle: Float
    ): Boolean {
        if (!loaded || radius <= 0f) return false

        var attribPushed = false
        var shaderStarted = false
        var previousProgram = 0

        return try {
            previousProgram = glGetInteger(GL_CURRENT_PROGRAM)

            this.color = color
            this.innerRadius = innerRadius.coerceIn(0f, 1f)
            this.outerRadius = 1f
            this.startAngle = startAngle
            this.endAngle = endAngle

            glPushAttrib(GL_ALL_ATTRIB_BITS)
            attribPushed = true
            shaderStarted = true
            startShader()
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            glDisable(GL_TEXTURE_2D)
            drawQuad(x - radius, y - radius, x + radius, y + radius)
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
            LOGGER.error("${javaClass.name} failed; falling back to tessellator rendering.", e)
            loggedFailure = true
        }
    }
}
