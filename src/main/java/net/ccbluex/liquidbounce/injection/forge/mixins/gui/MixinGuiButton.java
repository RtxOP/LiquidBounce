/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.injection.forge.mixins.gui;

import net.ccbluex.liquidbounce.ui.font.AWTFontRenderer;
import net.ccbluex.liquidbounce.ui.font.Fonts;
import net.ccbluex.liquidbounce.utils.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.*;

import java.awt.*;

import static net.minecraft.client.renderer.GlStateManager.resetColor;

@Mixin(GuiButton.class)
@SideOnly(Side.CLIENT)
public abstract class MixinGuiButton extends Gui {

    @Shadow
    public boolean visible;

    @Shadow
    public int xPosition;

    @Shadow
    public int yPosition;

    @Shadow
    public int width;

    @Shadow
    public int height;

    @Shadow
    protected boolean hovered;

    @Shadow
    public boolean enabled;

    @Shadow
    protected abstract void mouseDragged(Minecraft mc, int mouseX, int mouseY);

    @Shadow
    public String displayString;

    @Shadow
    @Final
    protected static ResourceLocation buttonTextures;

    @Shadow
    public int id;

    @Unique
    private long startTime = -1L;

    @Unique
    private boolean lastHover = false;

    @Unique
    private float progress = Float.NaN;

    /**
     * @author CCBlueX
     */
    @Overwrite
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (visible) {
            if (Float.isNaN(progress)) {
                progress = xPosition;
            }

            hovered = mouseX >= xPosition && mouseY >= yPosition && mouseX < xPosition + width && mouseY < yPosition + height;

            float supposedWidth = width;

            if ((Object) this instanceof GuiOptionSlider) {
                supposedWidth *= ((GuiOptionSlider) (Object) this).sliderValue;
                hovered = true;
            }

            if ((Object) this instanceof GuiScreenOptionsSounds.Button) {
                supposedWidth *= ((GuiScreenOptionsSounds.Button) (Object) this).field_146156_o;
                hovered = true;
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
            int sheenAlpha = (int) (30 * hoverProgress);
            int baseColor = enabled
                    ? new Color(baseShade, baseShade, baseShade, baseAlpha).getRGB()
                    : new Color(0.5F, 0.5F, 0.5F, 0.5F).getRGB();

            RenderUtils.INSTANCE.drawRoundedRect(xPosition, drawY, xPosition + width, drawY + height, baseColor, radius, RenderUtils.RoundedCorners.ALL);

            if (enabled) {
                RenderUtils.INSTANCE.drawRoundedBorder(xPosition, drawY, xPosition + width, drawY + height, 1F, new Color(255, 255, 255, borderAlpha).getRGB(), radius);

                if (sheenAlpha > 0) {
                    float sheenCenter = MathHelper.clamp_float(mouseX, xPosition, xPosition + width);
                    float sheenHalfWidth = Math.max(18F, width * 0.32F);
                    float sheenLeft = Math.max(xPosition, sheenCenter - sheenHalfWidth);
                    float sheenRight = Math.min(xPosition + width, sheenCenter + sheenHalfWidth);
                    float sheenMid = (sheenLeft + sheenRight) * 0.5F;
                    int transparent = new Color(255, 255, 255, 0).getRGB();
                    int highlight = new Color(255, 255, 255, sheenAlpha).getRGB();

                    RenderUtils.drawRoundedGradientRect(sheenLeft, drawY + 1F, sheenMid, drawY + Math.max(3F, height * 0.45F), transparent, highlight, radius, RenderUtils.RoundedCorners.TOP_ONLY);
                    RenderUtils.drawRoundedGradientRect(sheenMid, drawY + 1F, sheenRight, drawY + Math.max(3F, height * 0.45F), highlight, transparent, radius, RenderUtils.RoundedCorners.TOP_ONLY);
                }
            }

            mc.getTextureManager().bindTexture(buttonTextures);
            mouseDragged(mc, mouseX, mouseY);

            AWTFontRenderer.Companion.setAssumeNonVolatile(true);

            final FontRenderer fontRenderer = Fonts.fontSemibold35;
            fontRenderer.drawStringWithShadow(displayString, (float) (xPosition + width / 2 - fontRenderer.getStringWidth(displayString) / 2), drawY + (height - 5) / 2F, 14737632);

            AWTFontRenderer.Companion.setAssumeNonVolatile(false);

            resetColor();
        }
    }
}
