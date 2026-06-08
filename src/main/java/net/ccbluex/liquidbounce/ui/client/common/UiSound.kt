/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.common

import net.ccbluex.liquidbounce.utils.client.MinecraftInstance
import net.ccbluex.liquidbounce.utils.client.asResourceLocation
import net.ccbluex.liquidbounce.utils.client.playSound

object UiSound : MinecraftInstance {
    fun click() {
        mc.playSound("gui.button.press".asResourceLocation())
    }

    fun expand() {
        mc.playSound("random.bow".asResourceLocation())
    }
}
