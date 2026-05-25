/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.render.shader.shaders

import net.ccbluex.liquidbounce.utils.render.RenderUtils.deltaTime
import net.ccbluex.liquidbounce.utils.render.drawWithTessellatorWorldRenderer
import net.ccbluex.liquidbounce.utils.render.shader.Shader
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import org.lwjgl.opengl.Display
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL20.*
import java.io.File
import java.io.IOException

class BackgroundShader : Shader {
    constructor() : super("background.frag")

    @Throws(IOException::class)
    constructor(fragmentShader: File) : super(fragmentShader)

    private var time = 0f

    override fun setupUniforms() {
        setupUniform("iResolution")
        setupUniform("iTime")
    }

    override fun updateUniforms() {
        val resolutionID = getUniform("iResolution")
        if (resolutionID > -1)
            glUniform2f(resolutionID, Display.getWidth().toFloat(), Display.getHeight().toFloat())

        val timeID = getUniform("iTime")
        if (timeID > -1) glUniform1f(timeID, time)

        time += 0.002f * deltaTime
    }

    fun render(width: Int, height: Int): Boolean {
        if (!loaded)
            return false

        var shaderStarted = false
        val previousProgram = glGetInteger(GL_CURRENT_PROGRAM)

        return try {
            setupBackgroundState()

            shaderStarted = true
            startShader()

            drawWithTessellatorWorldRenderer {
                begin(7, DefaultVertexFormats.POSITION)
                pos(0.0, height.toDouble(), 0.0).endVertex()
                pos(width.toDouble(), height.toDouble(), 0.0).endVertex()
                pos(width.toDouble(), 0.0, 0.0).endVertex()
                pos(0.0, 0.0, 0.0).endVertex()
            }

            stopShader()
            shaderStarted = false
            glUseProgram(previousProgram)

            setupGuiState()
            true
        } catch (e: Exception) {
            if (shaderStarted) {
                stopShader()
            }
            glUseProgram(previousProgram)
            setupGuiState()
            false
        }
    }

    private fun setupBackgroundState() {
        GlStateManager.resetColor()
        GlStateManager.disableTexture2D()
        GlStateManager.disableDepth()
        GlStateManager.depthMask(false)
        GlStateManager.disableBlend()
        GlStateManager.disableLighting()
        GlStateManager.disableFog()
    }

    private fun setupGuiState() {
        GlStateManager.resetColor()
        GlStateManager.enableTexture2D()
        GlStateManager.disableDepth()
        GlStateManager.depthMask(false)
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO)
        GlStateManager.enableAlpha()
        GlStateManager.disableLighting()
        GlStateManager.disableFog()
        GlStateManager.shadeModel(GL_FLAT)
    }

    companion object {
        private val backgroundShader by lazy { BackgroundShader() }

        @JvmStatic
        fun renderDefault(width: Int, height: Int) = backgroundShader.render(width, height)
    }
}
