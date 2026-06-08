/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.common

data class UiRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    val right: Float
        get() = x + width

    val bottom: Float
        get() = y + height

    fun contains(mouseX: Int, mouseY: Int) =
        mouseX >= x && mouseX <= right && mouseY >= y && mouseY <= bottom
}
