/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.clickgui.modern

import net.ccbluex.liquidbounce.utils.client.ClientUtils.LOGGER
import net.ccbluex.liquidbounce.utils.client.MinecraftInstance
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.shader.Framebuffer
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL20.*
import kotlin.math.max

/**
 * Shape-agnostic drop shadow. The caller renders whatever shape they want
 * into a small framebuffer via [draw]’s [onMask] block; this orchestrator
 * then runs a two-pass separable Gaussian blur (so the kernel approximates a
 * true 2D Gaussian at the cost of two 13-tap passes instead of 13²) and
 * composites the blurred shape underneath the caller's intended position on
 * MC's main framebuffer.
 *
 * This matches how browsers render CSS `box-shadow` — the GPU composites a
 * blurred α-mask of the shape. Because we don't bake any shape logic into
 * the renderer, the mask can be a rounded rect, text, an icon — anything the
 * caller can produce with the existing drawing primitives.
 *
 * Steps:
 *  1. Bind an internal FBO sized to (canvasW × canvasH, scaled by the GUI
 *     scale factor) and invoke the caller's [onMask]. Anything they draw is
 *     the source α-mask for the blur.
 *  2. Horizontal 1D blur: ping FBO → pong FBO via [ShadowBlurShader] with
 *     direction = 0.
 *  3. Vertical 1D blur: pong FBO → ping FBO with direction = 1.
 *  4. Composite ping FBO onto MC's main framebuffer at (destX, destY) using
 *     premultiplied alpha (GL_ONE, GL_ONE_MINUS_SRC_ALPHA), so the shadow
 *     correctly blends over previously-rendered UI underneath.
 *
 * Coordinate scaling: the shader's [ShadowBlurShader] interprets the blur
 * radius against [sourceSize], which matches the caller's canvas dimensions
 * (i.e. unscaled CV pixels), not the FBO pixel dimensions. The FBO is sized
 * canvasW*scale × canvasH*scale to keep the mask crisp at >1 GUI scales,
 * but [blurRadius] itself must stay in canvas-space. Multiplying it by
 * [scale] would over-shoot the halo by an extra factor of `scale` and the
 * outer ring would clamp to the FBO edge — effectively erasing the shadow at
 * any GUI scale above 1.
 */
object SmoothShadowRenderer : MinecraftInstance {
    private val blurShader = ShadowBlurShader()

    private var pingFbo: Framebuffer? = null
    private var pongFbo: Framebuffer? = null

    /**
     * @param canvasW exact width of the framebuffer the caller's [onMask]
     *        draws into, in screen pixels.
     * @param canvasH exact height of the same framebuffer, in screen pixels.
     * @param destX MC screen X where the shadow's top-left lands after blur —
     *        typically `shapeX - blurRadius`.
     * @param destY MC screen Y where the shadow's top-left lands after blur.
     * @param blurRadius Gaussian blur radius in screen pixels. Should be sized
     *        so there's room for the blur halo around the masked shape.
     * @param onMask invoked with the mask-FBO bound at (0, 0). Whatever the
     *        caller draws is the source — the renderer does not assume any
     *        particular shape.
     */
    fun draw(
        canvasW: Float,
        canvasH: Float,
        destX: Float,
        destY: Float,
        blurRadius: Float,
        onMask: () -> Unit,
    ) {
        if (!blurShader.loaded || canvasW <= 0f || canvasH <= 0f || blurRadius <= 0f) return

        runCatching {
            val scale = ScaledResolution(mc).scaleFactor.toFloat()
            val fboW = max(1, (canvasW * scale).toInt())
            val fboH = max(1, (canvasH * scale).toInt())
            ensureSized(fboW, fboH)

            glPushAttrib(GL_ALL_ATTRIB_BITS)
            glPushMatrix()
            glDisable(GL_DEPTH_TEST)
            glDisable(GL_CULL_FACE)
            glDisable(GL_LIGHTING)
            glActiveTexture(GL_TEXTURE0)

            // 1. Mask into ping FBO.
            pingFbo!!.bindFramebuffer(true)
            pingFbo!!.framebufferClear()
            glViewport(0, 0, fboW, fboH)
            pushFboOrtho(canvasW.toDouble(), canvasH.toDouble())
            onMask()
            popOrthoAndModelview()

            // 2. Horizontal blur: ping → pong.
            pongFbo!!.bindFramebuffer(true)
            pongFbo!!.framebufferClear()
            glViewport(0, 0, fboW, fboH)
            pushFboOrtho(canvasW.toDouble(), canvasH.toDouble())
            runBlurPass(pingFbo!!, dir = 0, blurRadius, canvasW, canvasH)
            popOrthoAndModelview()

            // 3. Vertical blur: pong → ping.
            pingFbo!!.bindFramebuffer(true)
            pingFbo!!.framebufferClear()
            glViewport(0, 0, fboW, fboH)
            pushFboOrtho(canvasW.toDouble(), canvasH.toDouble())
            runBlurPass(pongFbo!!, dir = 1, blurRadius, canvasW, canvasH)
            popOrthoAndModelview()

            // 4. Composite onto MC framebuffer.
            mc.framebuffer.bindFramebuffer(true)
            mc.entityRenderer.setupOverlayRendering()

            glEnable(GL_TEXTURE_2D)
            glBindTexture(GL_TEXTURE_2D, pingFbo!!.framebufferTexture)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

            glEnable(GL_BLEND)
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            glBegin(GL_QUADS)
            // Y-flipped: FBO texture data is stored Y-up, MC GUI ortho is Y-down.
            glTexCoord2f(0f, 1f); glVertex2f(destX, destY)
            glTexCoord2f(0f, 0f); glVertex2f(destX, destY + canvasH)
            glTexCoord2f(1f, 0f); glVertex2f(destX + canvasW, destY + canvasH)
            glTexCoord2f(1f, 1f); glVertex2f(destX + canvasW, destY)
            glEnd()

            glPopMatrix()
            glPopAttrib()
        }.onFailure { LOGGER.error("SmoothShadowRenderer draw failed", it) }
    }

    private fun pushFboOrtho(w: Double, h: Double) {
        glMatrixMode(GL_PROJECTION); glPushMatrix(); glLoadIdentity()
        // Y from top (matching MC GUI) for consistent coordinate handling.
        glOrtho(0.0, w, h, 0.0, -1.0, 1.0)
        glMatrixMode(GL_MODELVIEW); glPushMatrix(); glLoadIdentity()
    }

    private fun popOrthoAndModelview() {
        glMatrixMode(GL_MODELVIEW); glPopMatrix()
        glMatrixMode(GL_PROJECTION); glPopMatrix()
    }

    private fun runBlurPass(
        source: Framebuffer,
        dir: Int,
        blurPixel: Float,
        canvasW: Float,
        canvasH: Float,
    ) {
        glDisable(GL_BLEND)
        glEnable(GL_TEXTURE_2D)
        glBindTexture(GL_TEXTURE_2D, source.framebufferTexture)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

        val previousProgram = glGetInteger(GL_CURRENT_PROGRAM)

        blurShader.startShader()
        blurShader.configure(blurRadiusPx = blurPixel, dir = dir, sourceW = canvasW, sourceH = canvasH)
        glBegin(GL_QUADS)
        glTexCoord2f(0f, 1f); glVertex2f(0f, 0f)
        glTexCoord2f(0f, 0f); glVertex2f(0f, canvasH)
        glTexCoord2f(1f, 0f); glVertex2f(canvasW, canvasH)
        glTexCoord2f(1f, 1f); glVertex2f(canvasW, 0f)
        glEnd()
        blurShader.stopShader()
        glUseProgram(previousProgram)
    }

    private fun ensureSized(w: Int, h: Int) {
        if (pingFbo == null || pongFbo == null ||
            pingFbo!!.framebufferWidth != w || pingFbo!!.framebufferHeight != h) {
            pingFbo?.deleteFramebuffer()
            pongFbo?.deleteFramebuffer()
            pingFbo = Framebuffer(w, h, false)
            pongFbo = Framebuffer(w, h, false)
        }
    }
}
