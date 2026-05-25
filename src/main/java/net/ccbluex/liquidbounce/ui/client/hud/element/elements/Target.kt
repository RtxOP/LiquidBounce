/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.hud.element.elements

import net.ccbluex.liquidbounce.features.module.modules.combat.KillAura
import net.ccbluex.liquidbounce.ui.client.hud.element.Border
import net.ccbluex.liquidbounce.ui.client.hud.element.Element
import net.ccbluex.liquidbounce.ui.client.hud.element.ElementInfo
import net.ccbluex.liquidbounce.ui.font.AWTFontRenderer.Companion.assumeNonVolatile
import net.ccbluex.liquidbounce.ui.font.Fonts
import net.ccbluex.liquidbounce.utils.attack.EntityUtils.getHealth
import net.ccbluex.liquidbounce.utils.extensions.lerpWith
import net.ccbluex.liquidbounce.utils.extensions.safeDiv
import net.ccbluex.liquidbounce.utils.render.ColorUtils
import net.ccbluex.liquidbounce.utils.render.ColorUtils.withAlpha
import net.ccbluex.liquidbounce.utils.render.RenderUtils
import net.ccbluex.liquidbounce.utils.render.RenderUtils.deltaTime
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawHead
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawRoundedGradientRect
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawRoundedRect
import net.ccbluex.liquidbounce.utils.render.animation.AnimationUtil
import net.ccbluex.liquidbounce.utils.render.shader.shaders.RainbowShader
import net.minecraft.client.gui.GuiChat
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import org.lwjgl.opengl.GL11.*
import java.awt.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * A Target HUD
 */
@ElementInfo(name = "Target")
class Target : Element("Target") {

    private val roundedRectRadius by float("Rounded-Radius", 3F, 0F..5F)

    private val backgroundMode by choices("Background-ColorMode", arrayOf("Custom", "Rainbow"), "Custom")
    private val backgroundColor by color("Background-Color", Color.BLACK.withAlpha(150)) { backgroundMode == "Custom" }

    private val healthBarColor1 by color("HealthBar-Gradient1", Color(3, 65, 252))
    private val healthBarColor2 by color("HealthBar-Gradient2", Color(3, 252, 236))

    private val roundHealthBarShape by boolean("RoundHealthBarShape", true)

    private val textColor by color("TextColor", Color.WHITE)

    private val rainbowX by float("Rainbow-X", -1000F, -2000F..2000F) { backgroundMode == "Rainbow" }
    private val rainbowY by float("Rainbow-Y", -1000F, -2000F..2000F) { backgroundMode == "Rainbow" }

    private val titleFont by font("TitleFont", Fonts.fontSemibold40)
    private val healthFont by font("HealthFont", Fonts.fontRegular30)
    private val textShadow by boolean("TextShadow", false)

    private val fadeSpeed by float("FadeSpeed", 2F, 1F..9F)
    private val absorption by boolean("Absorption", true)
    private val healthFromScoreboard by boolean("HealthFromScoreboard", true)

    private val animation by choices("Animation", arrayOf("Smooth", "Fade"), "Fade")
    private val animationSpeed by float("AnimationSpeed", 0.2F, 0.05F..1F)
    private val vanishDelay by int("VanishDelay", 300, 0..500)

    private var easingHealth = 0F
    private var lastTarget: EntityLivingBase? = null

    private var width = 0f
    private var height = 0f

    private val isRendered
        get() = width > 0f || height > 0f

    private var visibility = 0F

    private val isVisible
        get() = visibility > 0.01F

    private var delayCounter = 0
    private var easingHurtTime = 0F

    override fun drawElement(): Border {
        val smoothMode = animation == "Smooth"
        val fadeMode = animation == "Fade"

        val killAuraTarget = KillAura.target.takeIf { it is EntityPlayer }

        val shouldRender = KillAura.handleEvents() && killAuraTarget != null || mc.currentScreen is GuiChat
        val target = killAuraTarget ?: if (delayCounter >= vanishDelay && !isRendered) {
            mc.thePlayer
        } else {
            lastTarget ?: mc.thePlayer
        }

        val stringWidth = (40f + (target.name?.let(titleFont::getStringWidth) ?: 0)).coerceAtLeast(118F)

        assumeNonVolatile {
            if (shouldRender) {
                delayCounter = 0
            } else if (isRendered || isVisible) {
                delayCounter++
            }

            if (shouldRender || isRendered || isVisible) {
                val targetHealth = getHealth(target!!, healthFromScoreboard, absorption)
                val maxHealth = target.maxHealth + if (absorption) target.absorptionAmount else 0F

                easingHealth += (targetHealth - easingHealth) / 2f.pow(10f - fadeSpeed) * deltaTime
                easingHealth = easingHealth.coerceIn(0f, maxHealth)
                val targetHurtTime = if (target.isEntityAlive()) target.hurtTime.toFloat() else 0F
                easingHurtTime = (easingHurtTime..targetHurtTime).lerpWith(RenderUtils.deltaTimeNormalized())

                if (target != lastTarget || abs(easingHealth - targetHealth) < 0.01) {
                    easingHealth = targetHealth
                }

                if (smoothMode) {
                    val targetWidth = if (shouldRender || delayCounter < vanishDelay) stringWidth else 0f
                    width = AnimationUtil.base(width.toDouble(), targetWidth.toDouble(), animationSpeed.toDouble())
                        .toFloat().coerceAtLeast(0f)

                    if (targetWidth == 0f && width < 0.5f) {
                        width = 0f
                    }

                    height = if (width > 0f || targetWidth > 0f) 36f else 0f
                } else {
                    val targetVisibility = if (shouldRender || delayCounter < vanishDelay) 1F else 0F
                    visibility = AnimationUtil.base(
                        visibility.toDouble(), targetVisibility.toDouble(), animationSpeed.toDouble()
                    ).toFloat().coerceIn(0F, 1F)

                    if (targetVisibility == 0F && visibility < 0.01F) {
                        visibility = 0F
                    }

                    width = if (visibility > 0F || targetVisibility > 0F) stringWidth else 0F
                    height = if (visibility > 0F || targetVisibility > 0F) 36F else 0F
                }

                val easedVisibility = if (fadeMode) visibility * visibility * (3F - 2F * visibility) else 1F
                val backgroundCustomColor =
                    backgroundColor.withAlpha((backgroundColor.alpha * easedVisibility).toInt()).rgb
                val textCustomColor = textColor.withAlpha((textColor.alpha * easedVisibility).toInt()).rgb
                val healthColor1 = healthBarColor1.withAlpha((healthBarColor1.alpha * easedVisibility).toInt()).rgb
                val healthColor2 = healthBarColor2.withAlpha((healthBarColor2.alpha * easedVisibility).toInt()).rgb
                val healthBackgroundColor = Color.BLACK.withAlpha((255 * easedVisibility).toInt()).rgb
                val contentVisible = !fadeMode || easedVisibility > 0.04F

                val rainbowOffset = System.currentTimeMillis() % 10000 / 10000F
                val rainbowX = 1f safeDiv rainbowX
                val rainbowY = 1f safeDiv rainbowY

                glPushMatrix()

                glEnable(GL_BLEND)
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

                if (fadeMode && isVisible || smoothMode && isRendered || delayCounter < vanishDelay) {
                    val width = width.coerceAtLeast(0F)
                    val height = height.coerceAtLeast(0F)
                    val offsetY = if (fadeMode) (1F - easedVisibility) * 4F else 0F

                    glTranslatef(0F, offsetY, 0F)

                    val drawBackground = {
                        RainbowShader.begin(
                            backgroundMode == "Rainbow",
                            rainbowX,
                            rainbowY,
                            rainbowOffset,
                            easedVisibility
                        ).use {
                            drawRoundedRect(
                                0F,
                                0F,
                                width,
                                height,
                                if (backgroundMode == "Rainbow") 0 else backgroundCustomColor,
                                roundedRectRadius
                            )
                        }
                    }

                    val drawContent = {
                        val healthBarTop = 24F
                        val healthBarHeight = 8F
                        val healthBarStart = 36F
                        val healthBarTotal = (width - 39F).coerceAtLeast(0F)
                        val currentWidth = (easingHealth / maxHealth).coerceIn(0F, 1F) * healthBarTotal

                        // background bar
                        val backgroundBar = {
                            drawRoundedRect(
                                healthBarStart,
                                healthBarTop,
                                healthBarStart + healthBarTotal,
                                healthBarTop + healthBarHeight,
                                healthBackgroundColor,
                                6F,
                            )
                        }

                        if (roundHealthBarShape) {
                            backgroundBar()
                        }

                        drawRoundedGradientRect(
                            healthBarStart,
                            healthBarTop,
                            healthBarStart + currentWidth,
                            healthBarTop + healthBarHeight,
                            healthColor1,
                            healthColor2,
                            if (roundHealthBarShape) 6F else 0F
                        )

                        val healthPercentage = (easingHealth / maxHealth * 100).toInt()
                        val percentageText = "$healthPercentage%"
                        val textWidth = healthFont.getStringWidth(percentageText)
                        val calcX = healthBarStart + currentWidth - textWidth
                        val textX = max(healthBarStart, calcX)
                        val textY = healthBarTop - Fonts.fontRegular30.fontHeight / 2 - 2F
                        if (contentVisible) {
                            healthFont.drawString(percentageText, textX, textY, textCustomColor, textShadow)
                        }

                        val shouldRenderBody =
                            (fadeMode && contentVisible) || (smoothMode && width + height > 100)

                        if (shouldRenderBody) {
                            val renderer = mc.renderManager.getEntityRenderObject<Entity>(target)

                            if (renderer != null) {
                                val entityTexture = renderer.getEntityTexture(target)

                                glPushMatrix()
                                val scale = 1 - easingHurtTime / 10f
                                val f1 = (0.7F..1F).lerpWith(scale) * this.scale
                                val color = ColorUtils.interpolateColor(Color.RED, Color.WHITE, scale)
                                    .withAlpha((255 * easedVisibility).toInt())
                                val centerX1 = (4..32).lerpWith(0.5F)
                                val midY = (4f..28f).lerpWith(0.5F)

                                glTranslatef(centerX1, midY, 0f)
                                glScalef(f1, f1, f1)
                                glTranslatef(-centerX1, -midY, 0f)

                                if (entityTexture != null) {
                                    drawHead(
                                        entityTexture,
                                        4,
                                        4,
                                        8f,
                                        8f,
                                        8,
                                        8,
                                        28,
                                        28,
                                        64F,
                                        64F,
                                        color,
                                        roundedRectRadius
                                    )
                                }
                                glPopMatrix()
                            }

                            target.name?.let {
                                titleFont.drawString(it, healthBarStart, 6F, textCustomColor, textShadow)
                            }
                        }
                    }

                    if (smoothMode) {
                        RenderUtils.withClipping(main = drawBackground, toClip = drawContent)
                    } else {
                        drawBackground()
                        drawContent()
                    }
                }

                glPopMatrix()
            }
        }

        lastTarget = target
        return Border(0F, 0F, stringWidth, 36F)
    }
}
