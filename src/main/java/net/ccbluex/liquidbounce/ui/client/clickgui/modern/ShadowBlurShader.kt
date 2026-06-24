/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.clickgui.modern

import net.ccbluex.liquidbounce.utils.render.shader.Shader
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL20.*
import java.nio.FloatBuffer

/**
 * One 13-tap 1D Gaussian — direction-controlled by a uniform so the same
 * program is reused for the horizontal and vertical blur passes.
 *
 * Weights are a pre-normalized Gaussian with σ ≈ 2 spread across the 13
 * taps (so the kernel spans ±σ·6). They sum to 1, so use without rescaling.
 */
class ShadowBlurShader : Shader("shadow_blur.frag") {
    companion object {
        // Allocate once. The shader reads these every frame.
        val weightsBuffer: FloatBuffer = BufferUtils.createFloatBuffer(13)
            .put(floatArrayOf(
                0.002218f, 0.008774f, 0.027023f, 0.064823f,
                0.121109f, 0.176214f, 0.199676f,
                0.176214f, 0.121109f, 0.064823f,
                0.027023f, 0.008774f, 0.002218f,
            ))
            .flip()
    }

    private var blurRadiusPixels = 0f
    private var direction = 0
    private var sourceWidth = 0f
    private var sourceHeight = 0f

    override fun setupUniforms() {
        setupUniform("source")
        setupUniform("sourceSize")
        setupUniform("blurRadiusPixels")
        setupUniform("direction")
        setupUniform("weights")
    }

    override fun updateUniforms() {
        glUniform1i(getUniform("source"), 0)
        glUniform2f(getUniform("sourceSize"), sourceWidth, sourceHeight)
        glUniform1f(getUniform("blurRadiusPixels"), blurRadiusPixels)
        glUniform1i(getUniform("direction"), direction)
        glUniform1fv(getUniform("weights"), weightsBuffer)
    }

    fun configure(blurRadiusPx: Float, dir: Int, sourceW: Float, sourceH: Float) {
        blurRadiusPixels = blurRadiusPx
        direction = dir
        sourceWidth = sourceW
        sourceHeight = sourceH
    }
}
