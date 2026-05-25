/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */

package net.ccbluex.liquidbounce.injection.forge.mixins.gui;

import net.ccbluex.liquidbounce.ui.font.Fonts;
import net.ccbluex.liquidbounce.utils.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.MathHelper;
import net.minecraftforge.fml.client.config.GuiButtonExt;
import net.minecraftforge.fml.client.config.GuiSlider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.awt.*;

import static net.minecraft.client.renderer.GlStateManager.resetColor;

@Mixin(GuiButtonExt.class)
@SideOnly(Side.CLIENT)
public abstract class MixinGuiButtonExt extends GuiButton {
    @Unique
    private long startTime = -1L;

    @Unique
    private boolean lastHover = false;

    @Unique
    private float progress = Float.NaN;

    public MixinGuiButtonExt(int p_i1020_1_, int p_i1020_2_, int p_i1020_3_, String p_i1020_4_) {
        super(p_i1020_1_, p_i1020_2_, p_i1020_3_, p_i1020_4_);
    }

    public MixinGuiButtonExt(int p_i46323_1_, int p_i46323_2_, int p_i46323_3_, int p_i46323_4_, int p_i46323_5_, String p_i46323_6_) {
        super(p_i46323_1_, p_i46323_2_, p_i46323_3_, p_i46323_4_, p_i46323_5_, p_i46323_6_);
    }

    /**
     * @author CCBlueX
     */
    @Overwrite
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        final FontRenderer fontRenderer = mc.getLanguageManager().isCurrentLocaleUnicode() ? mc.fontRendererObj : Fonts.fontSemibold35;

        if (Float.isNaN(progress)) {
            progress = xPosition;
        }

        hovered = mouseX >= xPosition && mouseY >= yPosition && mouseX < xPosition + width && mouseY < yPosition + height;

        float supposedWidth = width;
        boolean slider = false;

        if ((Object) this instanceof GuiSlider) {
            supposedWidth *= (float) ((GuiSlider) (Object) this).sliderValue;
            hovered = true;
            slider = true;
        }

        if (startTime < 0L) {
            startTime = System.currentTimeMillis();
        }

        if (hovered != lastHover) {
            if (System.currentTimeMillis() - startTime > 200L) {
                startTime = System.currentTimeMillis();
            }
            lastHover = hovered;
        }

        long elapsed = System.currentTimeMillis() - startTime;

        float startingPos = enabled && hovered ? xPosition : progress;
        float endingPos = enabled && hovered ? xPosition + supposedWidth : xPosition;

        progress = (int) (startingPos + (endingPos - startingPos) * MathHelper.clamp_float(elapsed / 200f, 0f, 1f));

        float radius = 2.5F;
        float hoverProgress = enabled && supposedWidth > 0F
                ? MathHelper.clamp_float((progress - xPosition) / supposedWidth, 0F, 1F)
                : 0F;
        float drawY = yPosition - hoverProgress;
        int baseAlpha = (int) (120 + 26 * hoverProgress);
        int baseShade = (int) (10 * hoverProgress);
        int borderAlpha = (int) (34 + 46 * hoverProgress);
        int baseColor = enabled
                ? new Color(baseShade, baseShade, baseShade, baseAlpha).getRGB()
                : new Color(0.5F, 0.5F, 0.5F, 0.5F).getRGB();

        RenderUtils.INSTANCE.drawRoundedRect(xPosition, drawY, xPosition + width, drawY + height, baseColor, radius, RenderUtils.RoundedCorners.ALL);

        if (enabled && slider) {
            float fillRight = MathHelper.clamp_float(xPosition + supposedWidth, xPosition + 3F, xPosition + width - 3F);
            float thumbX = MathHelper.clamp_float(xPosition + supposedWidth, xPosition + 4F, xPosition + width - 4F);

            RenderUtils.INSTANCE.drawRoundedRect(xPosition + 2F, drawY + 2F, fillRight, drawY + height - 2F, new Color(0, 111, 255, 90).getRGB(), radius - 1F, RenderUtils.RoundedCorners.ALL);
            RenderUtils.INSTANCE.drawRoundedRect(thumbX - 1.5F, drawY + 3F, thumbX + 1.5F, drawY + height - 3F, new Color(255, 255, 255, 155).getRGB(), 1.5F, RenderUtils.RoundedCorners.ALL);
        }

        if (enabled) {
            RenderUtils.INSTANCE.drawRoundedBorder(xPosition, drawY, xPosition + width, drawY + height, 1F, new Color(255, 255, 255, borderAlpha).getRGB(), radius);
        }

        mc.getTextureManager().bindTexture(buttonTextures);
        mouseDragged(mc, mouseX, mouseY);

        fontRenderer.drawStringWithShadow(displayString, (float) (xPosition + width / 2 - fontRenderer.getStringWidth(displayString) / 2), drawY + (height - 5) / 2F, 14737632);
        resetColor();
    }
}
