/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.render.shader.shaders

import net.ccbluex.liquidbounce.ui.client.hud.element.Element.Companion.MAX_GRADIENT_COLORS
import net.ccbluex.liquidbounce.utils.client.ClientUtils.LOGGER
import net.ccbluex.liquidbounce.utils.render.shader.Shader
import org.lwjgl.opengl.GL20.*

object SdfFontShader : Shader("sdf_font.frag") {
    var textColor = floatArrayOf(1f, 1f, 1f, 1f)

    override fun setupUniforms() {
        setupUniform("font_texture")
        setupUniform("textColor")
    }

    override fun updateUniforms() {
        glUniform1i(getUniform("font_texture"), 0)
        glUniform4f(getUniform("textColor"), textColor[0], textColor[1], textColor[2], textColor[3])
    }
}

object SdfGradientFontShader : Shader("sdf_gradient_font.frag") {
    var textColor = floatArrayOf(1f, 1f, 1f, 1f)

    override fun setupUniforms() {
        setupUniform("font_texture")
        setupUniform("textColor")
        setupUniform("offset")
        setupUniform("strength")
        setupUniform("speed")
        setupUniform("maxColors")

        for (i in 0 until MAX_GRADIENT_COLORS) {
            try {
                setupUniform("colors[$i]")
            } catch (e: Exception) {
                LOGGER.error("${javaClass.name} setup uniforms error.", e)
            }
        }
    }

    override fun updateUniforms() {
        glUniform1i(getUniform("font_texture"), 0)
        glUniform4f(getUniform("textColor"), textColor[0], textColor[1], textColor[2], textColor[3])
        glUniform2f(getUniform("strength"), GradientFontShader.strengthX, GradientFontShader.strengthY)
        glUniform1f(getUniform("offset"), GradientFontShader.offset)
        glUniform1f(getUniform("speed"), GradientFontShader.speed)
        glUniform1i(getUniform("maxColors"), GradientFontShader.maxColors)

        for (i in 0 until GradientFontShader.maxColors) {
            try {
                val color = GradientFontShader.colors[i]
                glUniform4f(getUniform("colors[$i]"), color[0], color[1], color[2], color[3])
            } catch (e: Exception) {
                LOGGER.error("${javaClass.name} update uniforms error.", e)
            }
        }
    }
}

object SdfRainbowFontShader : Shader("sdf_rainbow_font.frag") {
    var textColor = floatArrayOf(1f, 1f, 1f, 1f)

    override fun setupUniforms() {
        setupUniform("font_texture")
        setupUniform("textColor")
        setupUniform("offset")
        setupUniform("strength")
    }

    override fun updateUniforms() {
        glUniform1i(getUniform("font_texture"), 0)
        glUniform4f(getUniform("textColor"), textColor[0], textColor[1], textColor[2], textColor[3])
        glUniform2f(getUniform("strength"), RainbowFontShader.strengthX, RainbowFontShader.strengthY)
        glUniform1f(getUniform("offset"), RainbowFontShader.offset)
    }
}
