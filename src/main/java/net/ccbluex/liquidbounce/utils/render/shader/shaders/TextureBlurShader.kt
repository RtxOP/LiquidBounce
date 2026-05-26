/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.render.shader.shaders

import net.ccbluex.liquidbounce.utils.render.shader.Shader
import org.lwjgl.opengl.GL20.*

object TextureBlurShader : Shader("texture_blur.frag") {
    private var directionX = 1f
    private var directionY = 0f
    private var radius = 8f

    override fun setupUniforms() {
        setupUniform("texture")
        setupUniform("texelSize")
        setupUniform("direction")
        setupUniform("radius")
    }

    override fun updateUniforms() {
        glUniform1i(getUniform("texture"), 0)
        glUniform2f(getUniform("texelSize"), 1f / mc.displayWidth, 1f / mc.displayHeight)
        glUniform2f(getUniform("direction"), directionX, directionY)
        glUniform1f(getUniform("radius"), radius)
    }

    fun configure(directionX: Float, directionY: Float, radius: Float) {
        this.directionX = directionX
        this.directionY = directionY
        this.radius = radius.coerceIn(1f, 12f)
    }
}
