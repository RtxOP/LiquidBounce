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
                float trackLeft = xPosition + 7F;
                float trackRight = xPosition + width - 7F;
                float trackWidth = trackRight - trackLeft;
                float trackY = drawY + height / 2F - 2.5F;
                float trackBottom = trackY + 5F;
                float fillRight = MathHelper.clamp_float(trackLeft + trackWidth * sliderProgress, trackLeft, trackRight);
                float thumbX = MathHelper.clamp_float(fillRight, trackLeft + 2F, trackRight - 2F);
                float thumbHeight = 13F;
                float thumbY = drawY + height / 2F - thumbHeight / 2F;
                int thumbAlpha = (int) (178 + 45 * hoverProgress);

                RenderUtils.INSTANCE.drawRoundedRect(trackLeft, trackY, trackRight, trackBottom, new Color(255, 255, 255, 24).getRGB(), 2.5F, RenderUtils.RoundedCorners.ALL);

                if (fillRight > trackLeft + 0.5F) {
                    RenderUtils.INSTANCE.drawRoundedGradientRect(
                            trackLeft,
                            trackY,
                            fillRight,
                            trackBottom,
                            new Color(104, 86, 255, 145).getRGB(),
                            new Color(56, 189, 248, 145).getRGB(),
                            2.5F,
                            RenderUtils.RoundedCorners.ALL
                    );
                }

                RenderUtils.INSTANCE.drawRoundedRect(thumbX - 3F, thumbY, thumbX + 3F, thumbY + thumbHeight, new Color(236, 241, 255, thumbAlpha).getRGB(), 3F, RenderUtils.RoundedCorners.ALL);
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
