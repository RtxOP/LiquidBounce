/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.render.shader.shaders

import net.ccbluex.liquidbounce.utils.render.shader.Shader
import net.minecraft.client.gui.ScaledResolution
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL20.*

/**
 * Renders a rounded rect whose four corners are independently colored and
 * bilinearly interpolated across the surface.
 *
 * The conceptual model exposed by Issues.md ("Multi-stop diagonal shader") is a
 * 1D N-stop gradient along an arbitrary diagonal direction. With four corners
 * this abstraction collapses: along the diagonal axis, the top-right and
 * bottom-left project to the same `t` value, so a single-axis gradient cannot
 * place distinct colors at all four corners. The Issues.md recommendation
 * ("compute per-vertex colors with bilinear interpolation") resolves this by
 * emitting one color per corner and interpolating bilinearly — the natural
 * implementation lives in the fragment shader and reuses the rounded-rect SDF
 * mask from `RoundedGradientRectShader`.
 */
object RoundedMultiStopGradientRectShader : Shader("rounded_multi_stop_gradient_rect.frag") {
    private var rectWidth = 0f
    private var rectHeight = 0f
    private var radii = FloatArray(4)
    private var topLeft = FloatArray(4)
    private var topRight = FloatArray(4)
    private var bottomLeft = FloatArray(4)
    private var bottomRight = FloatArray(4)

    override fun setupUniforms() {
        setupUniform("rectSize")
        setupUniform("radii")
        setupUniform("topLeft")
        setupUniform("topRight")
        setupUniform("bottomLeft")
        setupUniform("bottomRight")
    }

    override fun updateUniforms() {
        glUniform2f(getUniform("rectSize"), rectWidth, rectHeight)
        glUniform4f(getUniform("radii"), radii[0], radii[1], radii[2], radii[3])
        glUniform4f(getUniform("topLeft"), topLeft[0], topLeft[1], topLeft[2], topLeft[3])
        glUniform4f(getUniform("topRight"), topRight[0], topRight[1], topRight[2], topRight[3])
        glUniform4f(getUniform("bottomLeft"), bottomLeft[0], bottomLeft[1], bottomLeft[2], bottomLeft[3])
        glUniform4f(getUniform("bottomRight"), bottomRight[0], bottomRight[1], bottomRight[2], bottomRight[3])
    }

    fun render(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radii: FloatArray,
        topLeft: FloatArray,
        topRight: FloatArray,
        bottomLeft: FloatArray,
        bottomRight: FloatArray
    ) {
        if (x2 <= x1 || y2 <= y1) return
        check(loaded) { "${javaClass.name} is not loaded." }

        var attribPushed = false
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
            this.topLeft = topLeft
            this.topRight = topRight
            this.bottomLeft = bottomLeft
            this.bottomRight = bottomRight

            glPushAttrib(GL_ALL_ATTRIB_BITS)
            attribPushed = true
            glColor4f(1f, 1f, 1f, 1f)
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            shaderStarted = true
            startShader()
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            drawQuad(x1 - QUAD_PADDING, y1 - QUAD_PADDING, x2 + QUAD_PADDING, y2 + QUAD_PADDING)
            checkGlError("multi-stop rounded gradient draw")
            stopShader()
            shaderStarted = false
            glUseProgram(previousProgram)
            glPopAttrib()
            attribPushed = false
        } catch (t: Throwable) {
            if (shaderStarted) {
                stopShader()
            }
            glUseProgram(previousProgram)
            if (attribPushed) {
                glPopAttrib()
            }
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
