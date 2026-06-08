/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.clickgui.modern

enum class ModernClickGuiPreset(val configName: String) {
    COLUMN_DECK("ColumnDeck"),
    SIDEBAR_LIST("SidebarList");

    companion object {
        fun fromConfigName(name: String) = entries.find { it.configName.equals(name, true) }
    }
}
