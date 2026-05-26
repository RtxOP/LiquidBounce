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
            boolean slider = false;

            if ((Object) this instanceof GuiOptionSlider) {
                supposedWidth *= ((GuiOptionSlider) (Object) this).sliderValue;
                slider = true;
            }

            if ((Object) this instanceof GuiScreenOptionsSounds.Button) {
                supposedWidth *= ((GuiScreenOptionsSounds.Button) (Object) this).field_146156_o;
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
            int baseRed = (int) (18 + 8 * hoverProgress);
            int baseGreen = (int) (23 + 11 * hoverProgress);
            int baseBlue = (int) (29 + 14 * hoverProgress);
            int baseAlpha = (int) (150 + 42 * hoverProgress);
            int borderAlpha = (int) (95 * hoverProgress);
            int baseColor = enabled
                    ? new Color(baseRed, baseGreen, baseBlue, baseAlpha).getRGB()
                    : new Color(0.45F, 0.45F, 0.45F, 90 / 255F).getRGB();

            if (enabled && hoverProgress > 0.02F) {
                int borderColor = new Color(198, 214, 255, borderAlpha).getRGB();
                RenderUtils.INSTANCE.drawRoundedRectWithBorder(xPosition, drawY, xPosition + width, drawY + height, baseColor, borderColor, 1F, radius, RenderUtils.RoundedCorners.ALL);
            } else {
                RenderUtils.INSTANCE.drawRoundedRect(xPosition, drawY, xPosition + width, drawY + height, baseColor, radius, RenderUtils.RoundedCorners.ALL);
            }

            if (enabled && slider) {
                float sliderProgress = MathHelper.clamp_float(supposedWidth / width, 0F, 1F);
                float fillRight = MathHelper.clamp_float(xPosition + width * sliderProgress, xPosition + 3F, xPosition + width - 3F);
                float thumbX = MathHelper.clamp_float(fillRight, xPosition + 4F, xPosition + width - 4F);
                int fillAlpha = (int) (58 + 16 * hoverProgress);
                int thumbAlpha = (int) (150 + 45 * hoverProgress);

                if (fillRight > xPosition + 3.5F) {
                    RenderUtils.INSTANCE.drawRoundedGradientRect(
                            xPosition + 2F,
                            drawY + 2F,
                            fillRight,
                            drawY + height - 2F,
                            new Color(72, 68, 132, fillAlpha).getRGB(),
                            new Color(58, 92, 140, fillAlpha).getRGB(),
                            radius - 1F,
                            RenderUtils.RoundedCorners.ALL
                    );
                }

                RenderUtils.INSTANCE.drawRoundedRect(thumbX - 2F, drawY + 2.5F, thumbX + 2F, drawY + height - 2.5F, new Color(236, 241, 255, thumbAlpha).getRGB(), 2F, RenderUtils.RoundedCorners.ALL);
            }

            mc.getTextureManager().bindTexture(buttonTextures);
            mouseDragged(mc, mouseX, mouseY);

            AWTFontRenderer.Companion.setAssumeNonVolatile(true);

            final FontRenderer fontRenderer = Fonts.fontSemibold35;
            int textColor = new Color(
                    (int) (216 + 27 * hoverProgress),
                    (int) (222 + 24 * hoverProgress),
                    255
            ).getRGB();
            fontRenderer.drawStringWithShadow(displayString, (float) (xPosition + width / 2 - fontRenderer.getStringWidth(displayString) / 2), drawY + (height - 5) / 2F, textColor);

            AWTFontRenderer.Companion.setAssumeNonVolatile(false);

            resetColor();
        }
    }
}
