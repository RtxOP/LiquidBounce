/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.clickgui.modern

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.launch
import net.ccbluex.liquidbounce.LiquidBounce.CLIENT_NAME
import net.ccbluex.liquidbounce.LiquidBounce.clientVersionText
import net.ccbluex.liquidbounce.LiquidBounce.moduleManager
import net.ccbluex.liquidbounce.api.AutoSettings
import net.ccbluex.liquidbounce.api.ClientApi
import net.ccbluex.liquidbounce.api.autoSettingsList
import net.ccbluex.liquidbounce.api.loadSettings
import net.ccbluex.liquidbounce.config.SettingsUtils
import net.ccbluex.liquidbounce.config.Value
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.modules.render.ClickGUI
import net.ccbluex.liquidbounce.file.FileManager.clickGuiConfig
import net.ccbluex.liquidbounce.file.FileManager.saveConfig
import net.ccbluex.liquidbounce.file.FileManager.valuesConfig
import net.ccbluex.liquidbounce.ui.client.common.UiRect
import net.ccbluex.liquidbounce.ui.client.common.UiSound
import net.ccbluex.liquidbounce.ui.client.common.UiPerformanceProfile
import net.ccbluex.liquidbounce.ui.client.common.UiTextCache
import net.ccbluex.liquidbounce.ui.client.common.UiTheme
import net.ccbluex.liquidbounce.ui.client.common.ValueControlState
import net.ccbluex.liquidbounce.ui.client.common.ValueControls
import net.ccbluex.liquidbounce.ui.client.hud.HUD
import net.ccbluex.liquidbounce.ui.client.hud.designer.GuiHudDesigner
import net.ccbluex.liquidbounce.ui.client.hud.element.elements.Notification
import net.ccbluex.liquidbounce.ui.font.Fonts
import net.ccbluex.liquidbounce.utils.attack.EntityUtils.Targets
import net.ccbluex.liquidbounce.utils.client.ClientUtils
import net.ccbluex.liquidbounce.utils.client.asResourceLocation
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.playSound
import net.ccbluex.liquidbounce.utils.kotlin.SharedScopes
import net.ccbluex.liquidbounce.utils.render.ColorUtils
import net.ccbluex.liquidbounce.utils.render.ColorUtils.withAlpha
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawRect
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawImage
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawRoundedRect
import net.ccbluex.liquidbounce.utils.render.RenderUtils.makeScissorBox
import net.ccbluex.liquidbounce.utils.ui.isCtrlPressed
import net.minecraft.client.gui.FontRenderer
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.GuiScreen.getClipboardString
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11.GL_SCISSOR_TEST
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glEnable
import java.awt.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ModernClickGuiScreen : GuiScreen() {

    private const val TOP_MARGIN = 24F
    private const val SIDE_MARGIN = 14F
    private const val COLUMN_GAP = 10F
    private const val DEFAULT_COLUMN_WIDTH = 118F
    private const val MIN_COLUMN_WIDTH = 92F
    private const val HEADER_HEIGHT = 20F
    private const val ROW_HEIGHT = 18F
    private const val COLUMN_RADIUS = 6F
    private const val SIDEBAR_SHELL_WIDTH = 646F
    private const val SIDEBAR_SHELL_HEIGHT = 430F
    private const val SIDEBAR_WIDTH = 154F
    private const val SIDEBAR_PADDING = 14F
    private const val SIDEBAR_ROW_HEIGHT = 24F
    private const val SIDEBAR_MODULE_HEIGHT = 46F
    private const val SIDEBAR_MODULE_GAP = 5F
    private const val SIDEBAR_SYNTHETIC_ROW_HEIGHT = 46F
    private const val SIDEBAR_AUTO_SETTING_ROW_HEIGHT = 58F
    private const val SEARCH_HEIGHT = 24F
    private const val MAX_SEARCH_QUERY_LENGTH = 64

    private var theme = UiTheme.MODERN
    private var themeProfile = UiPerformanceProfile.BALANCED
    private val textCache = UiTextCache()
    private val columns = linkedMapOf<Category, ColumnState>()
    private val expandedModules = linkedSetOf<String>()
    private val expandedSyntheticSections = linkedSetOf<SyntheticSection>()
    private val moduleHitTargets = mutableListOf<ModuleHitTarget>()
    private val valueHitTargets = mutableListOf<ValueHitTarget>()
    private val categoryHitTargets = mutableListOf<CategoryHitTarget>()
    private val syntheticHitTargets = mutableListOf<SyntheticHitTarget>()
    private val syntheticActionHitTargets = mutableListOf<SyntheticActionHitTarget>()
    private val columnLayouts = mutableMapOf<Category, ColumnLayout>()
    private val valueControlState = ValueControlState()

    private var ignoreClosing = false
    private var draggingColumn: ColumnState? = null
    private var dragOffsetX = 0F
    private var dragOffsetY = 0F
    private var selectedCategory = Category.COMBAT
    private var sidebarContentScroll = 0F
    private var sidebarContentLayout: SidebarContentLayout? = null
    private var sidebarSearchRect: UiRect? = null
    private var searchFocused = false
    private var searchQuery = ""
    private var layoutDirty = false
    private var selectedSyntheticSection: SyntheticSection? = null
    private var autoSettingsLoading = false
    private var applyingAutoSettingId: String? = null
    private var cachedSidebarModulesKey: SidebarModuleCacheKey? = null
    private var cachedSidebarModules: List<SidebarModuleEntry> = emptyList()

    override fun initGui() {
        ignoreClosing = true
        Keyboard.enableRepeatEvents(true)
        ensureColumns()
        refreshPerformanceProfile()
        textCache.clear()
    }

    fun loadModernConfig(json: JsonObject?) {
        ensureColumns()

        if (json == null) {
            layoutDirty = false
            return
        }

        runCatching {
            expandedModules.clear()
            json["expandedModules"]?.asJsonArray?.forEach { element ->
                runCatching { element.asString }.getOrNull()?.let(expandedModules::add)
            }
        }

        runCatching {
            expandedSyntheticSections.clear()
            json["expandedSyntheticSections"]?.asJsonArray?.forEach { element ->
                val section = parseSyntheticSection(runCatching { element.asString }.getOrNull()) ?: return@forEach
                expandedSyntheticSections += section
            }
        }

        runCatching {
            val columnDeck = json.objectOrNull("ColumnDeck") ?: return@runCatching
            val columnObjects = columnDeck.objectOrNull("columns") ?: return@runCatching

            for ((key, element) in columnObjects.entrySet()) {
                val category = parseCategory(key) ?: continue
                val columnObject = runCatching { element.asJsonObject }.getOrNull() ?: continue
                val column = columns[category] ?: continue

                column.x = columnObject.floatOrNull("x") ?: column.x
                column.y = columnObject.floatOrNull("y") ?: column.y
                column.width = columnObject.floatOrNull("width") ?: column.width
                column.scroll = columnObject.floatOrNull("scroll") ?: column.scroll
                column.manualPosition = columnObject.booleanOrNull("manualPosition") ?: true
            }
        }

        runCatching {
            val sidebarList = json.objectOrNull("SidebarList") ?: return@runCatching
            selectedCategory = parseCategory(sidebarList.stringOrNull("selectedCategory")) ?: selectedCategory
            selectedSyntheticSection = parseSyntheticSection(sidebarList.stringOrNull("selectedSyntheticSection"))
            searchQuery = (sidebarList.stringOrNull("searchQuery") ?: searchQuery)
                .filter { ColorUtils.isAllowedCharacter(it) }
                .take(MAX_SEARCH_QUERY_LENGTH)
            sidebarContentScroll = sidebarList.floatOrNull("contentScroll") ?: sidebarContentScroll
        }

        invalidateSidebarModulesCache()
        layoutDirty = false
    }

    fun loadLegacyConfig(json: JsonObject) {
        ensureColumns()
        expandedModules.clear()
        expandedSyntheticSections.clear()
        selectedSyntheticSection = null

        for (category in Category.entries) {
            val panelObject = json.objectOrNull(category.displayName) ?: continue
            val column = columns[category] ?: continue

            column.x = panelObject.floatOrNull("posX") ?: column.x
            column.y = panelObject.floatOrNull("posY") ?: column.y
            column.manualPosition = true

            for (module in moduleManager[category]) {
                val elementObject = panelObject.objectOrNull(module.name) ?: continue
                if (elementObject.booleanOrNull("Settings") == true) {
                    expandedModules += module.name
                }
            }
        }

        invalidateSidebarModulesCache()
        layoutDirty = false
    }

    fun saveModernConfig(): JsonObject {
        ensureColumns()

        val root = JsonObject()
        root.addProperty("version", 1)
        root.addProperty("preset", ClickGUI.modernPreset?.configName ?: ModernClickGuiPreset.COLUMN_DECK.configName)

        val expanded = JsonArray()
        expandedModules.sorted().forEach { expanded.add(it) }
        root.add("expandedModules", expanded)

        val expandedSynthetic = JsonArray()
        expandedSyntheticSections.sortedBy(SyntheticSection::name).forEach { expandedSynthetic.add(it.name) }
        root.add("expandedSyntheticSections", expandedSynthetic)

        val columnDeck = JsonObject()
        val columnObjects = JsonObject()
        for ((category, column) in columns) {
            val columnObject = JsonObject()
            columnObject.addProperty("x", column.x)
            columnObject.addProperty("y", column.y)
            columnObject.addProperty("width", column.width)
            columnObject.addProperty("scroll", column.scroll)
            columnObject.addProperty("manualPosition", column.manualPosition)
            columnObjects.add(category.name, columnObject)
        }
        columnDeck.add("columns", columnObjects)
        root.add(ModernClickGuiPreset.COLUMN_DECK.configName, columnDeck)

        val sidebarList = JsonObject()
        sidebarList.addProperty("selectedCategory", selectedCategory.name)
        sidebarList.addProperty("selectedSyntheticSection", selectedSyntheticSection?.name)
        sidebarList.addProperty("searchQuery", searchQuery)
        sidebarList.addProperty("contentScroll", sidebarContentScroll)
        root.add(ModernClickGuiPreset.SIDEBAR_LIST.configName, sidebarList)

        layoutDirty = false

        return root
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        refreshPerformanceProfile()
        handleWheel(mouseX, mouseY)
        updateDragging(mouseX, mouseY)

        drawBackgroundOverlay()

        moduleHitTargets.clear()
        valueHitTargets.clear()
        categoryHitTargets.clear()
        syntheticHitTargets.clear()
        syntheticActionHitTargets.clear()
        columnLayouts.clear()
        sidebarContentLayout = null
        sidebarSearchRect = null

        when (ClickGUI.modernPreset ?: ModernClickGuiPreset.COLUMN_DECK) {
            ModernClickGuiPreset.COLUMN_DECK -> drawColumnDeck(mouseX, mouseY)
            ModernClickGuiPreset.SIDEBAR_LIST -> drawSidebarList(mouseX, mouseY)
        }

        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    private fun drawBackgroundOverlay() {
        Gui.drawRect(0, 0, width, height, theme.backgroundOverlay.rgb)
    }

    private fun drawShadow(rect: UiRect, radius: Float, strong: Boolean = false) {
        if (themeProfile == UiPerformanceProfile.FAST) {
            drawRoundedRect(rect.x + 1F, rect.y + 1F, rect.right + 1F, rect.bottom + 1F, Color(0, 0, 0, 72).rgb, radius)
            return
        }

        val alpha = if (strong) 118 else 82
        drawRoundedRect(rect.x + 3F, rect.y + 4F, rect.right + 3F, rect.bottom + 4F, Color(0, 0, 0, alpha).rgb, radius)
        drawRoundedRect(rect.x + 1F, rect.y + 2F, rect.right + 1F, rect.bottom + 2F, Color(0, 0, 0, alpha / 2).rgb, radius)
    }

    private fun drawSurface(rect: UiRect, color: Color, radius: Float, shadow: Boolean = false, borderAlpha: Int = 120) {
        if (shadow) {
            drawShadow(rect, radius)
        }

        drawRoundedRect(rect.x - 0.5F, rect.y - 0.5F, rect.right + 0.5F, rect.bottom + 0.5F, theme.border.withAlpha(borderAlpha).rgb, radius + 0.5F)
        drawRoundedRect(rect.x, rect.y, rect.right, rect.bottom, color.rgb, radius)
    }

    private fun drawAccentStrip(rect: UiRect, fullHeight: Boolean = false) {
        val top = if (fullHeight) rect.y + 1F else rect.y + 7F
        val bottom = if (fullHeight) rect.bottom - 1F else rect.bottom - 7F

        drawRoundedRect(rect.x, top, rect.x + 3F, bottom, theme.accent.rgb, 1.5F)
    }

    private fun drawSwitch(enabled: Boolean, x: Float, y: Float, width: Float, height: Float, knobSize: Float) {
        val inset = (height - knobSize) / 2F
        val knobX = if (enabled) x + width - knobSize - inset else x + inset
        val knobY = y + inset
        val trackColor = if (enabled) theme.accent else Color(70, 72, 82, 210)

        drawRoundedRect(x, y, x + width, y + height, trackColor.rgb, height / 2F)
        drawRoundedRect(knobX, knobY, knobX + knobSize, knobY + knobSize, theme.textPrimary.rgb, knobSize / 2F)
    }

    private fun drawColumnDeck(mouseX: Int, mouseY: Int) {
        val columnWidth = columnWidth()
        val viewportHeight = max(120F, height - TOP_MARGIN - 18F)

        for ((index, category) in Category.entries.withIndex()) {
            val column = columns[category] ?: continue
            column.width = columnWidth
            if (!column.manualPosition) {
                column.x = SIDE_MARGIN + index * (columnWidth + COLUMN_GAP)
                column.y = TOP_MARGIN
            }
            clampColumnIntoViewport(column, columnWidth)

            drawColumn(category, column, viewportHeight, mouseX, mouseY)
        }
    }

    private fun drawColumn(
        category: Category,
        column: ColumnState,
        viewportHeight: Float,
        mouseX: Int,
        mouseY: Int
    ) {
        val modules = moduleManager[category]
        val contentHeight = modules.sumOf { module ->
            ROW_HEIGHT.toDouble() + expandedHeight(module).toDouble()
        }.toFloat() + if (category == Category.MISC) syntheticColumnHeight() else 0F
        val bodyHeight = viewportHeight.coerceAtMost(HEADER_HEIGHT + contentHeight + 4F)
        val scrollMax = max(0F, contentHeight - (bodyHeight - HEADER_HEIGHT - 4F))

        column.scroll = column.scroll.coerceIn(0F, scrollMax)
        columnLayouts[category] = ColumnLayout(
            UiRect(column.x, column.y, column.width, bodyHeight),
            contentHeight,
            bodyHeight - HEADER_HEIGHT - 4F,
            scrollMax
        )

        val columnRect = UiRect(column.x, column.y, column.width, bodyHeight)
        drawSurface(columnRect, theme.panelBackground, COLUMN_RADIUS, shadow = true)
        drawRoundedRect(
            column.x,
            column.y,
            column.x + column.width,
            column.y + HEADER_HEIGHT,
            theme.panelHeader.rgb,
            COLUMN_RADIUS
        )
        drawRect(column.x, column.y + HEADER_HEIGHT - 5F, column.x + column.width, column.y + HEADER_HEIGHT, theme.panelHeader.rgb)

        Fonts.fontSemibold35.drawCenteredString(
            category.displayName,
            column.x + column.width / 2F,
            column.y + 6F,
            theme.textPrimary.rgb
        )

        glEnable(GL_SCISSOR_TEST)
        makeScissorBox(column.x, column.y + HEADER_HEIGHT, column.x + column.width, column.y + bodyHeight - 2F)

        var rowY = column.y + HEADER_HEIGHT + 2F - column.scroll
        for (module in modules) {
            val visibleValues = module.values.filter { it.shouldRender() }
            val expanded = module.name in expandedModules
            val rowRect = UiRect(column.x + 3F, rowY, column.width - 6F, ROW_HEIGHT)

            if (rowRect.bottom >= column.y + HEADER_HEIGHT && rowRect.y <= column.y + bodyHeight) {
                drawModuleRow(module, visibleValues, expanded, rowRect, mouseX, mouseY)
                moduleHitTargets += ModuleHitTarget(rowRect, module)
            }

            rowY += ROW_HEIGHT

            if (expanded) {
                rowY = drawExpandedValues(visibleValues, column, rowY, bodyHeight, mouseX, mouseY)
            }
        }

        if (category == Category.MISC) {
            drawColumnSyntheticSections(column, rowY, bodyHeight, mouseX, mouseY)
        }

        glDisable(GL_SCISSOR_TEST)

        if (scrollMax > 0F) {
            drawScrollbar(column, bodyHeight, scrollMax)
        }
    }

    private fun drawColumnSyntheticSections(
        column: ColumnState,
        startY: Float,
        bodyHeight: Float,
        mouseX: Int,
        mouseY: Int
    ) {
        var y = startY + 3F

        y = drawColumnSyntheticSection(SyntheticSection.TARGETS, column, y, bodyHeight, mouseX, mouseY)
        y = drawColumnSyntheticSection(SyntheticSection.AUTO_SETTINGS, column, y, bodyHeight, mouseX, mouseY)

        val hudRect = UiRect(column.x + 3F, y, column.width - 6F, ROW_HEIGHT)
        if (hudRect.bottom >= column.y + HEADER_HEIGHT && hudRect.y <= column.y + bodyHeight) {
            drawSyntheticRow(
                "HUD Editor",
                "",
                false,
                false,
                hudRect,
                mouseX,
                mouseY
            )
            syntheticActionHitTargets += SyntheticActionHitTarget(hudRect, SyntheticAction.OpenHudDesigner)
        }
    }

    private fun drawColumnSyntheticSection(
        section: SyntheticSection,
        column: ColumnState,
        startY: Float,
        bodyHeight: Float,
        mouseX: Int,
        mouseY: Int
    ): Float {
        var y = startY
        val expanded = section in expandedSyntheticSections
        val row = UiRect(column.x + 3F, y, column.width - 6F, ROW_HEIGHT)

        if (row.bottom >= column.y + HEADER_HEIGHT && row.y <= column.y + bodyHeight) {
            drawSyntheticRow(section.displayName, section.shortDescription, expanded, false, row, mouseX, mouseY)
            syntheticHitTargets += SyntheticHitTarget(row, section)
        }

        y += ROW_HEIGHT

        if (!expanded) {
            return y
        }

        y = when (section) {
            SyntheticSection.TARGETS -> drawColumnTargetOptions(column, y, bodyHeight, mouseX, mouseY)
            SyntheticSection.AUTO_SETTINGS -> drawColumnAutoSettings(column, y, bodyHeight, mouseX, mouseY)
        }

        return y + 2F
    }

    private fun drawColumnTargetOptions(
        column: ColumnState,
        startY: Float,
        bodyHeight: Float,
        mouseX: Int,
        mouseY: Int
    ): Float {
        var y = startY

        for (target in TargetOption.entries) {
            val rect = UiRect(column.x + 6F, y, column.width - 12F, ROW_HEIGHT)

            if (rect.bottom >= column.y + HEADER_HEIGHT && rect.y <= column.y + bodyHeight) {
                drawToggleActionRow(target.displayName, target.enabled(), rect, mouseX, mouseY)
                syntheticActionHitTargets += SyntheticActionHitTarget(rect, SyntheticAction.ToggleTarget(target))
            }

            y += ROW_HEIGHT
        }

        return y
    }

    private fun drawColumnAutoSettings(
        column: ColumnState,
        startY: Float,
        bodyHeight: Float,
        mouseX: Int,
        mouseY: Int
    ): Float {
        ensureAutoSettingsRequested()

        val settings = autoSettingsList
        var y = startY

        if (settings == null) {
            val rect = UiRect(column.x + 6F, y, column.width - 12F, ROW_HEIGHT)
            if (rect.bottom >= column.y + HEADER_HEIGHT && rect.y <= column.y + bodyHeight) {
                drawActionRow(if (autoSettingsLoading) "Loading..." else "Load settings", rect, mouseX, mouseY)
                syntheticActionHitTargets += SyntheticActionHitTarget(rect, SyntheticAction.RefreshAutoSettings)
            }
            return y + ROW_HEIGHT
        }

        if (settings.isEmpty()) {
            val rect = UiRect(column.x + 6F, y, column.width - 12F, ROW_HEIGHT)
            if (rect.bottom >= column.y + HEADER_HEIGHT && rect.y <= column.y + bodyHeight) {
                drawMutedRow("No settings", rect)
            }
            return y + ROW_HEIGHT
        }

        for (setting in settings) {
            val rect = UiRect(column.x + 6F, y, column.width - 12F, ROW_HEIGHT)

            if (rect.bottom >= column.y + HEADER_HEIGHT && rect.y <= column.y + bodyHeight) {
                val applying = applyingAutoSettingId == setting.settingId
                drawActionRow(if (applying) "Applying..." else setting.name, rect, mouseX, mouseY)
                syntheticActionHitTargets += SyntheticActionHitTarget(rect, SyntheticAction.ApplyAutoSetting(setting))
            }

            y += ROW_HEIGHT
        }

        return y
    }

    private fun drawSidebarList(mouseX: Int, mouseY: Int) {
        val shell = sidebarShellRect()
        val sidebarWidth = min(SIDEBAR_WIDTH, shell.width * 0.34F)
        val sidebar = UiRect(shell.x, shell.y, sidebarWidth, shell.height)
        val content = UiRect(sidebar.right, shell.y, shell.width - sidebarWidth, shell.height)

        drawSurface(shell, theme.panelBackground, 8F, shadow = true, borderAlpha = 150)
        drawRoundedRect(sidebar.x, sidebar.y, sidebar.right, sidebar.bottom, Color(8, 9, 14, 248).rgb, 8F)
        drawRect(sidebar.right, sidebar.y + 9F, sidebar.right + 1F, sidebar.bottom - 9F, theme.border.withAlpha(125).rgb)

        drawSidebarHeader(sidebar)
        val categoryBottom = drawSidebarCategories(sidebar, mouseX, mouseY)
        drawSidebarUtilities(sidebar, categoryBottom, mouseX, mouseY)
        drawSidebarContent(content, mouseX, mouseY)
    }

    private fun drawSidebarHeader(sidebar: UiRect) {
        val titleX = sidebar.x + SIDEBAR_PADDING
        val titleY = sidebar.y + 15F
        val version = clientVersionText.takeIf { it.isNotBlank() && it != "unknown" } ?: "legacy"
        val versionX = sidebar.right - SIDEBAR_PADDING - textWidth(Fonts.fontRegular30, version)

        Fonts.fontSemibold40.drawString(CLIENT_NAME, titleX, titleY, theme.textPrimary.rgb)
        Fonts.fontRegular30.drawString(version, versionX, titleY + 2F, theme.textMuted.withAlpha(220).rgb)

        val search = UiRect(
            sidebar.x + SIDEBAR_PADDING,
            sidebar.y + 47F,
            sidebar.width - SIDEBAR_PADDING * 2F,
            SEARCH_HEIGHT
        )
        sidebarSearchRect = search

        val searchColor = if (searchFocused) Color(15, 23, 52, 235) else Color(12, 15, 25, 226)
        val textColor = if (searchQuery.isBlank() && !searchFocused) theme.textMuted.withAlpha(180) else theme.textPrimary
        val text = if (searchQuery.isBlank() && !searchFocused) "Search" else searchQuery
        val display = trimText(Fonts.fontRegular30, text, search.width - 14F)

        drawSurface(search, searchColor, 5F, borderAlpha = if (searchFocused) 135 else 65)
        Fonts.fontRegular30.drawString(display, search.x + 7F, search.y + 7F, textColor.rgb)

        if (searchFocused) {
            val cursorX = (search.x + 7F + textWidth(Fonts.fontRegular30, searchQuery)).coerceAtMost(search.right - 7F)
            drawRect(cursorX, search.y + 5F, cursorX + 1F, search.bottom - 5F, theme.accent.rgb)
        }
    }

    private fun drawSidebarCategories(sidebar: UiRect, mouseX: Int, mouseY: Int): Float {
        var y = sidebar.y + 83F

        for (category in Category.entries) {
            val row = UiRect(sidebar.x + 9F, y, sidebar.width - 18F, SIDEBAR_ROW_HEIGHT)
            val selected = selectedSyntheticSection == null && category == selectedCategory
            val hovered = row.contains(mouseX, mouseY)
            val rowColor = when {
                selected -> Color(255, 255, 255, 46)
                hovered -> theme.rowHover.withAlpha(135)
                else -> Color(0, 0, 0, 0)
            }
            val textColor = if (selected) theme.textPrimary else theme.textMuted.withAlpha(225)

            if (rowColor.alpha > 0) {
                drawRoundedRect(row.x, row.y, row.right, row.bottom, rowColor.rgb, 5F)
            }

            if (selected) {
                drawAccentStrip(row)
            }

            drawImage(category.iconResourceLocation, row.x + 8F, row.y + 6F, 11, 11, textColor)
            Fonts.fontRegular30.drawString(category.displayName, row.x + 26F, row.y + 7F, textColor.rgb)
            categoryHitTargets += CategoryHitTarget(row, category)
            y += SIDEBAR_ROW_HEIGHT + 3F
        }

        return y
    }

    private fun drawSidebarUtilities(sidebar: UiRect, categoryBottom: Float, mouseX: Int, mouseY: Int) {
        val utilityRows = listOf(
            SyntheticSection.TARGETS to "Target filters",
            SyntheticSection.AUTO_SETTINGS to "Cloud presets"
        )
        val utilityHeight = (utilityRows.size + 1) * (SIDEBAR_ROW_HEIGHT + 3F) - 3F
        var y = max(categoryBottom + 12F, sidebar.bottom - utilityHeight - 16F)

        for ((section, description) in utilityRows) {
            val row = UiRect(sidebar.x + 9F, y, sidebar.width - 18F, SIDEBAR_ROW_HEIGHT)
            val selected = selectedSyntheticSection == section

            drawSidebarUtilityRow(section.displayName, description, selected, row, mouseX, mouseY)
            syntheticHitTargets += SyntheticHitTarget(row, section)
            y += SIDEBAR_ROW_HEIGHT + 3F
        }

        val hudRow = UiRect(sidebar.x + 9F, y, sidebar.width - 18F, SIDEBAR_ROW_HEIGHT)
        drawSidebarUtilityRow("HUD Editor", "Designer", false, hudRow, mouseX, mouseY)
        syntheticActionHitTargets += SyntheticActionHitTarget(hudRow, SyntheticAction.OpenHudDesigner)
    }

    private fun drawSidebarUtilityRow(
        title: String,
        description: String,
        selected: Boolean,
        rect: UiRect,
        mouseX: Int,
        mouseY: Int
    ) {
        val hovered = rect.contains(mouseX, mouseY)
        val rowColor = when {
            selected -> Color(255, 255, 255, 42)
            hovered -> theme.rowHover.withAlpha(135)
            else -> Color(0, 0, 0, 0)
        }
        val textColor = if (selected) theme.textPrimary else theme.textMuted.withAlpha(225)

        if (rowColor.alpha > 0) {
            drawRoundedRect(rect.x, rect.y, rect.right, rect.bottom, rowColor.rgb, 5F)
        }

        if (selected) {
            drawAccentStrip(rect)
        }

        Fonts.fontRegular30.drawString(
            trimText(Fonts.fontRegular30, title, rect.width - 10F),
            rect.x + 8F,
            rect.y + 5F,
            textColor.rgb
        )
        Fonts.fontRegular30.drawString(
            trimText(Fonts.fontRegular30, description, rect.width - 72F),
            rect.right - 62F,
            rect.y + 5F,
            theme.textMuted.withAlpha(150).rgb
        )
    }

    private fun drawSidebarContent(content: UiRect, mouseX: Int, mouseY: Int) {
        val selectedSynthetic = selectedSyntheticSection
        if (selectedSynthetic != null) {
            drawSidebarSyntheticContent(selectedSynthetic, content, mouseX, mouseY)
            return
        }

        val modules = filteredSidebarModules()
        val searchActive = searchQuery.isNotBlank()
        val headerX = content.x + 14F
        val headerY = content.y + 16F
        val viewport = UiRect(content.x + 14F, content.y + 49F, content.width - 23F, content.height - 62F)
        val contentHeight = modules.sumOf {
            (sidebarModuleHeight(it.module) + SIDEBAR_MODULE_GAP).toDouble()
        }.toFloat()
        val scrollMax = max(0F, contentHeight - viewport.height)

        sidebarContentScroll = sidebarContentScroll.coerceIn(0F, scrollMax)
        sidebarContentLayout = SidebarContentLayout(viewport, contentHeight, scrollMax)

        Fonts.fontSemibold40.drawString(
            if (searchActive) "Search" else selectedCategory.displayName,
            headerX,
            headerY,
            theme.textPrimary.rgb
        )
        Fonts.fontRegular30.drawString(
            if (searchActive) "${modules.size} results" else "${modules.size} modules",
            headerX,
            headerY + 17F,
            theme.textMuted.withAlpha(190).rgb
        )

        glEnable(GL_SCISSOR_TEST)
        makeScissorBox(viewport.x, viewport.y, viewport.right, viewport.bottom)

        var y = viewport.y - sidebarContentScroll

        if (modules.isEmpty()) {
            Fonts.fontRegular35.drawCenteredString(
                "No modules found",
                viewport.x + viewport.width / 2F,
                viewport.y + viewport.height / 2F - 4F,
                theme.textMuted.withAlpha(185).rgb
            )
        }

        for (entry in modules) {
            val module = entry.module
            val visibleValues = module.values.filter { it.shouldRender() }
            val expanded = module.name in expandedModules && visibleValues.isNotEmpty()
            val row = UiRect(viewport.x, y, viewport.width - 5F, SIDEBAR_MODULE_HEIGHT)

            if (row.bottom >= viewport.y && row.y <= viewport.bottom) {
                drawSidebarModuleRow(
                    module,
                    entry.category.takeIf { entry.searchResult },
                    visibleValues,
                    expanded,
                    row,
                    mouseX,
                    mouseY
                )
                moduleHitTargets += ModuleHitTarget(row, module)
            }

            y += SIDEBAR_MODULE_HEIGHT

            if (expanded) {
                y = drawSidebarExpandedValues(visibleValues, viewport, y, mouseX, mouseY)
            }

            y += SIDEBAR_MODULE_GAP
        }

        glDisable(GL_SCISSOR_TEST)

        if (scrollMax > 0F) {
            drawSidebarScrollbar(viewport, scrollMax)
        }
    }

    private fun drawSidebarSyntheticContent(
        section: SyntheticSection,
        content: UiRect,
        mouseX: Int,
        mouseY: Int
    ) {
        val headerX = content.x + 14F
        val headerY = content.y + 16F
        val viewport = UiRect(content.x + 14F, content.y + 49F, content.width - 23F, content.height - 62F)
        val contentHeight = sidebarSyntheticContentHeight(section)
        val scrollMax = max(0F, contentHeight - viewport.height)

        sidebarContentScroll = sidebarContentScroll.coerceIn(0F, scrollMax)
        sidebarContentLayout = SidebarContentLayout(viewport, contentHeight, scrollMax)

        Fonts.fontSemibold40.drawString(section.displayName, headerX, headerY, theme.textPrimary.rgb)
        Fonts.fontRegular30.drawString(section.description, headerX, headerY + 17F, theme.textMuted.withAlpha(190).rgb)

        glEnable(GL_SCISSOR_TEST)
        makeScissorBox(viewport.x, viewport.y, viewport.right, viewport.bottom)

        var y = viewport.y - sidebarContentScroll

        when (section) {
            SyntheticSection.TARGETS -> {
                for (target in TargetOption.entries) {
                    val row = UiRect(viewport.x, y, viewport.width - 5F, SIDEBAR_SYNTHETIC_ROW_HEIGHT)
                    if (row.bottom >= viewport.y && row.y <= viewport.bottom) {
                        drawSidebarTargetRow(target, row, mouseX, mouseY)
                        syntheticActionHitTargets += SyntheticActionHitTarget(row, SyntheticAction.ToggleTarget(target))
                    }
                    y += SIDEBAR_SYNTHETIC_ROW_HEIGHT + SIDEBAR_MODULE_GAP
                }
            }

            SyntheticSection.AUTO_SETTINGS -> {
                ensureAutoSettingsRequested()
                val settings = autoSettingsList

                if (settings == null) {
                    val row = UiRect(viewport.x, y, viewport.width - 5F, SIDEBAR_SYNTHETIC_ROW_HEIGHT)
                    if (row.bottom >= viewport.y && row.y <= viewport.bottom) {
                        drawSidebarStatusRow(if (autoSettingsLoading) "Loading Auto Settings" else "Load Auto Settings", row, mouseX, mouseY)
                        syntheticActionHitTargets += SyntheticActionHitTarget(row, SyntheticAction.RefreshAutoSettings)
                    }
                } else if (settings.isEmpty()) {
                    val row = UiRect(viewport.x, y, viewport.width - 5F, SIDEBAR_SYNTHETIC_ROW_HEIGHT)
                    if (row.bottom >= viewport.y && row.y <= viewport.bottom) {
                        drawSidebarStatusRow("No settings available", row, mouseX, mouseY)
                    }
                } else {
                    for (setting in settings) {
                        val row = UiRect(viewport.x, y, viewport.width - 5F, SIDEBAR_AUTO_SETTING_ROW_HEIGHT)
                        if (row.bottom >= viewport.y && row.y <= viewport.bottom) {
                            drawSidebarAutoSettingRow(setting, row, mouseX, mouseY)
                            syntheticActionHitTargets += SyntheticActionHitTarget(row, SyntheticAction.ApplyAutoSetting(setting))
                        }
                        y += SIDEBAR_AUTO_SETTING_ROW_HEIGHT + SIDEBAR_MODULE_GAP
                    }
                }
            }
        }

        glDisable(GL_SCISSOR_TEST)

        if (scrollMax > 0F) {
            drawSidebarScrollbar(viewport, scrollMax)
        }
    }

    private fun drawSidebarTargetRow(target: TargetOption, rect: UiRect, mouseX: Int, mouseY: Int) {
        val hovered = rect.contains(mouseX, mouseY)
        val rowColor = if (hovered) theme.rowHover.withAlpha(245) else theme.rowBackground
        val label = trimText(Fonts.fontSemibold35, target.displayName, rect.width - 62F)
        val description = trimText(Fonts.fontRegular30, target.description, rect.width - 62F)
        val enabled = target.enabled()
        val trackX = rect.right - 42F
        val trackY = rect.y + (rect.height - 12F) / 2F

        drawSurface(rect, rowColor, 5F, borderAlpha = 70)
        if (enabled) {
            drawAccentStrip(rect)
        }
        Fonts.fontSemibold35.drawString(label, rect.x + 10F, rect.y + 8F, theme.textPrimary.rgb)
        Fonts.fontRegular30.drawString(description, rect.x + 10F, rect.y + 25F, theme.textMuted.withAlpha(185).rgb)
        drawSwitch(enabled, trackX, trackY, 30F, 12F, 8F)
    }

    private fun drawSidebarAutoSettingRow(setting: AutoSettings, rect: UiRect, mouseX: Int, mouseY: Int) {
        val hovered = rect.contains(mouseX, mouseY)
        val applying = applyingAutoSettingId == setting.settingId
        val rowColor = when {
            applying -> Color(31, 25, 42, 226)
            hovered -> theme.rowHover.withAlpha(245)
            else -> theme.rowBackground
        }
        val title = trimText(
            Fonts.fontSemibold35,
            if (applying) "Applying ${setting.name}" else setting.name,
            rect.width - 20F
        )
        val description = trimText(
            Fonts.fontRegular30,
            setting.description.ifBlank { "No description available" },
            rect.width - 20F
        )
        val meta = trimText(
            Fonts.fontRegular30,
            "${setting.type.displayName} | ${setting.statusType.displayName}",
            rect.width - 20F
        )

        drawSurface(rect, rowColor, 5F, borderAlpha = 70)
        if (applying) {
            drawAccentStrip(rect)
        }
        Fonts.fontSemibold35.drawString(title, rect.x + 10F, rect.y + 8F, theme.textPrimary.rgb)
        Fonts.fontRegular30.drawString(description, rect.x + 10F, rect.y + 25F, theme.textMuted.withAlpha(185).rgb)
        Fonts.fontRegular30.drawString(meta, rect.x + 10F, rect.y + 40F, theme.textMuted.withAlpha(155).rgb)
    }

    private fun drawSidebarStatusRow(title: String, rect: UiRect, mouseX: Int, mouseY: Int) {
        val hovered = rect.contains(mouseX, mouseY)
        val rowColor = if (hovered) theme.rowHover.withAlpha(245) else theme.rowBackground
        val label = trimText(Fonts.fontSemibold35, title, rect.width - 20F)

        drawSurface(rect, rowColor, 5F, borderAlpha = 70)
        Fonts.fontSemibold35.drawString(label, rect.x + 10F, rect.y + 13F, theme.textPrimary.rgb)
    }

    private fun drawSidebarModuleRow(
        module: Module,
        contextCategory: Category?,
        visibleValues: List<Value<*>>,
        expanded: Boolean,
        rect: UiRect,
        mouseX: Int,
        mouseY: Int
    ) {
        val hovered = rect.contains(mouseX, mouseY)
        val active = module.state
        val rowColor = when {
            active -> Color(17, 19, 30, 248)
            hovered -> theme.rowHover.withAlpha(245)
            else -> theme.rowBackground
        }
        val nameWidth = (rect.width - if (visibleValues.isNotEmpty()) 39F else 20F).roundToInt()
        val name = trimText(Fonts.fontSemibold35, module.getName(), nameWidth)
        val descriptionText = if (contextCategory != null) {
            "${contextCategory.displayName} | ${module.description}"
        } else {
            module.description
        }
        val description = trimText(Fonts.fontRegular30, descriptionText, rect.width - 20F)

        drawSurface(rect, rowColor, 5F, borderAlpha = if (active) 105 else 66)

        if (active) {
            drawAccentStrip(rect)
        }

        Fonts.fontSemibold35.drawString(name, rect.x + 10F, rect.y + 8F, theme.textPrimary.rgb)
        Fonts.fontRegular30.drawString(description, rect.x + 10F, rect.y + 25F, theme.textMuted.withAlpha(185).rgb)

        if (visibleValues.isNotEmpty()) {
            Fonts.fontRegular35.drawString(
                if (expanded) "-" else "+",
                rect.right - 18F,
                rect.y + 12F,
                theme.textMuted.withAlpha(220).rgb
            )
        }
    }

    private fun drawSidebarExpandedValues(
        values: List<Value<*>>,
        viewport: UiRect,
        startY: Float,
        mouseX: Int,
        mouseY: Int
    ): Float {
        var y = startY + 3F

        for (value in values) {
            val valueHeight = ValueControls.height(value)
            val rect = UiRect(viewport.x + 9F, y, viewport.width - 23F, valueHeight)

            if (rect.bottom >= viewport.y && rect.y <= viewport.bottom) {
                ValueControls.drag(value, rect, mouseX, valueControlState) {
                    // Values own their change side effects. The dirty state saves on release.
                }
                ValueControls.draw(value, rect, theme, mouseX, mouseY, valueControlState)
                valueHitTargets += ValueHitTarget(rect, value)
            }

            y += valueHeight
        }

        return y + 3F
    }

    private fun drawSidebarScrollbar(viewport: UiRect, scrollMax: Float) {
        val trackX = viewport.right - 2F
        val trackTop = viewport.y + 4F
        val trackBottom = viewport.bottom - 4F
        val trackHeight = trackBottom - trackTop
        val thumbHeight = max(16F, trackHeight * (trackHeight / (trackHeight + scrollMax)))
        val thumbY = trackTop + (trackHeight - thumbHeight) * (sidebarContentScroll / scrollMax)

        drawRect(trackX, trackTop, trackX + 1.5F, trackBottom, Color(55, 55, 62, 130).rgb)
        drawRoundedRect(trackX - 0.5F, thumbY, trackX + 2F, thumbY + thumbHeight, theme.accent.rgb, 1.5F)
    }

    private fun drawSyntheticRow(
        title: String,
        description: String,
        expanded: Boolean,
        selected: Boolean,
        rect: UiRect,
        mouseX: Int,
        mouseY: Int
    ) {
        val hovered = rect.contains(mouseX, mouseY)
        val rowColor = when {
            selected -> theme.accentMuted
            hovered -> theme.rowHover.withAlpha(242)
            else -> theme.rowBackground.withAlpha(216)
        }
        val titleWidth = (rect.width - 18F).roundToInt()
        val titleText = trimText(Fonts.fontRegular35, title, titleWidth)

        drawRoundedRect(rect.x, rect.y, rect.right, rect.bottom - 1F, rowColor.rgb, 3.5F)
        if (selected) {
            drawAccentStrip(rect)
        }
        Fonts.fontRegular35.drawString(titleText, rect.x + 6F, rect.y + 5F, theme.textPrimary.rgb)

        if (description.isNotBlank() && rect.height > ROW_HEIGHT) {
            val descriptionText = trimText(Fonts.fontRegular30, description, rect.width - 12F)
            Fonts.fontRegular30.drawString(descriptionText, rect.x + 6F, rect.y + 23F, theme.textMuted.withAlpha(180).rgb)
        }

        if (description.isNotBlank() && rect.height <= ROW_HEIGHT) {
            Fonts.fontRegular30.drawString(
                if (expanded) "-" else "+",
                rect.right - 10F,
                rect.y + 5F,
                theme.textMuted.rgb
            )
        }
    }

    private fun drawToggleActionRow(title: String, enabled: Boolean, rect: UiRect, mouseX: Int, mouseY: Int) {
        val hovered = rect.contains(mouseX, mouseY)
        val rowColor = if (hovered) theme.rowHover.withAlpha(238) else theme.rowBackground.withAlpha(214)
        val label = trimText(Fonts.fontRegular30, title, rect.width - 31F)
        val trackX = rect.right - 24F
        val trackY = rect.y + (rect.height - 10F) / 2F

        drawRoundedRect(rect.x, rect.y, rect.right, rect.bottom - 1F, rowColor.rgb, 3F)
        Fonts.fontRegular30.drawString(label, rect.x + 5F, rect.y + 5F, theme.textPrimary.rgb)
        drawSwitch(enabled, trackX, trackY, 22F, 10F, 8F)
    }

    private fun drawActionRow(title: String, rect: UiRect, mouseX: Int, mouseY: Int) {
        val hovered = rect.contains(mouseX, mouseY)
        val rowColor = if (hovered) theme.rowHover.withAlpha(238) else theme.rowBackground.withAlpha(214)
        val label = trimText(Fonts.fontRegular30, title, rect.width - 10F)

        drawRoundedRect(rect.x, rect.y, rect.right, rect.bottom - 1F, rowColor.rgb, 3F)
        Fonts.fontRegular30.drawString(label, rect.x + 5F, rect.y + 5F, theme.textPrimary.rgb)
    }

    private fun drawMutedRow(title: String, rect: UiRect) {
        val label = trimText(Fonts.fontRegular30, title, rect.width - 10F)

        drawRoundedRect(rect.x, rect.y, rect.right, rect.bottom - 1F, theme.rowBackground.withAlpha(190).rgb, 3F)
        Fonts.fontRegular30.drawString(label, rect.x + 5F, rect.y + 5F, theme.textMuted.withAlpha(170).rgb)
    }

    private fun drawModuleRow(
        module: Module,
        visibleValues: List<Value<*>>,
        expanded: Boolean,
        rect: UiRect,
        mouseX: Int,
        mouseY: Int
    ) {
        val hovered = rect.contains(mouseX, mouseY)
        val active = module.state
        val rowColor = when {
            active && module.isActive -> theme.accent.withAlpha(246)
            active -> theme.accentMuted
            hovered -> theme.rowHover.withAlpha(244)
            else -> theme.rowBackground.withAlpha(220)
        }

        drawRoundedRect(rect.x, rect.y, rect.right, rect.bottom - 1F, rowColor.rgb, 3.5F)
        if (active && !module.isActive) {
            drawAccentStrip(rect, fullHeight = true)
        }

        val name = trimText(Fonts.fontRegular35, module.getName(), rect.width - 17F)
        Fonts.fontRegular35.drawString(name, rect.x + 6F, rect.y + 5F, theme.textPrimary.rgb)

        if (visibleValues.isNotEmpty()) {
            Fonts.fontRegular30.drawString(
                if (expanded) "-" else "+",
                rect.right - 10F,
                rect.y + 5F,
                theme.textMuted.rgb
            )
        }
    }

    private fun drawExpandedValues(
        values: List<Value<*>>,
        column: ColumnState,
        startY: Float,
        bodyHeight: Float,
        mouseX: Int,
        mouseY: Int
    ): Float {
        var y = startY

        if (values.isEmpty()) {
            return y
        }

        for (value in values) {
            val valueHeight = ValueControls.height(value)
            val rect = UiRect(column.x + 6F, y, column.width - 12F, valueHeight)

            if (rect.bottom >= column.y + HEADER_HEIGHT && rect.y <= column.y + bodyHeight) {
                ValueControls.drag(value, rect, mouseX, valueControlState) {
                    // Values own their change side effects. The dirty state saves on release.
                }
                ValueControls.draw(value, rect, theme, mouseX, mouseY, valueControlState)
                valueHitTargets += ValueHitTarget(rect, value)
            }
            y += valueHeight
        }

        return y + 2F
    }

    private fun drawScrollbar(column: ColumnState, bodyHeight: Float, scrollMax: Float) {
        val trackX = column.x + column.width - 4F
        val trackTop = column.y + HEADER_HEIGHT + 4F
        val trackBottom = column.y + bodyHeight - 6F
        val trackHeight = trackBottom - trackTop

        drawRect(trackX, trackTop, trackX + 1.5F, trackBottom, Color(55, 55, 62, 150).rgb)

        val thumbHeight = max(14F, trackHeight * ((trackHeight - 4F) / (trackHeight + scrollMax)))
        val thumbY = trackTop + (trackHeight - thumbHeight) * (column.scroll / scrollMax)
        drawRoundedRect(trackX - 0.5F, thumbY, trackX + 2F, thumbY + thumbHeight, theme.accent.rgb, 1.5F)
    }

    private fun handleWheel(mouseX: Int, mouseY: Int) {
        if (!Mouse.hasWheel()) {
            return
        }

        val wheel = Mouse.getDWheel()
        if (wheel == 0) {
            return
        }

        when (ClickGUI.modernPreset ?: ModernClickGuiPreset.COLUMN_DECK) {
            ModernClickGuiPreset.COLUMN_DECK -> {
                for ((category, layout) in columnLayouts) {
                    if (!layout.rect.contains(mouseX, mouseY) || layout.scrollMax <= 0F) {
                        continue
                    }

                    val column = columns[category] ?: continue
                    val nextScroll = (column.scroll - wheel / 6F).coerceIn(0F, layout.scrollMax)
                    if (nextScroll != column.scroll) {
                        column.scroll = nextScroll
                        markLayoutDirty()
                    }
                    return
                }
            }

            ModernClickGuiPreset.SIDEBAR_LIST -> {
                val layout = sidebarContentLayout ?: return
                if (!layout.rect.contains(mouseX, mouseY) || layout.scrollMax <= 0F) {
                    return
                }

                val nextScroll = (sidebarContentScroll - wheel / 6F).coerceIn(0F, layout.scrollMax)
                if (nextScroll != sidebarContentScroll) {
                    sidebarContentScroll = nextScroll
                    markLayoutDirty()
                }
            }
        }
    }

    public override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        for (target in valueHitTargets.asReversed()) {
            if (ValueControls.click(target.value, target.rect, mouseX, mouseY, mouseButton, valueControlState) {
                    // Values own their change side effects. The dirty state saves on release.
                }
            ) {
                searchFocused = false
                return
            }
        }

        if (mouseButton == 0) {
            for (target in syntheticActionHitTargets.asReversed()) {
                if (!target.rect.contains(mouseX, mouseY)) {
                    continue
                }

                searchFocused = false
                handleSyntheticAction(target.action)
                return
            }

            for (target in syntheticHitTargets.asReversed()) {
                if (!target.rect.contains(mouseX, mouseY)) {
                    continue
                }

                searchFocused = false
                handleSyntheticSectionClick(target.section)
                return
            }
        }

        if (ClickGUI.modernPreset == ModernClickGuiPreset.SIDEBAR_LIST) {
            val searchRect = sidebarSearchRect
            if (mouseButton == 0 && searchRect != null && searchRect.contains(mouseX, mouseY)) {
                searchFocused = true
                valueControlState.clearFocus()
                UiSound.click()
                return
            }

            searchFocused = false

            if (mouseButton == 0) {
                for (target in categoryHitTargets.asReversed()) {
                    if (!target.rect.contains(mouseX, mouseY)) {
                        continue
                    }

                    if (selectedCategory != target.category) {
                        selectedCategory = target.category
                        selectedSyntheticSection = null
                        sidebarContentScroll = 0F
                        invalidateSidebarModulesCache()
                        markLayoutDirty()
                    } else if (selectedSyntheticSection != null) {
                        selectedSyntheticSection = null
                        sidebarContentScroll = 0F
                        invalidateSidebarModulesCache()
                        markLayoutDirty()
                    }
                    UiSound.click()
                    return
                }
            }
        }

        valueControlState.clearFocus()

        if (mouseButton == 0 && ClickGUI.modernPreset != ModernClickGuiPreset.SIDEBAR_LIST) {
            for ((category, layout) in columnLayouts) {
                val header = UiRect(layout.rect.x, layout.rect.y, layout.rect.width, HEADER_HEIGHT)
                if (header.contains(mouseX, mouseY)) {
                    draggingColumn = columns[category]
                    draggingColumn?.manualPosition = true
                    dragOffsetX = mouseX - header.x
                    dragOffsetY = mouseY - header.y
                    return
                }
            }
        }

        for (target in moduleHitTargets.asReversed()) {
            if (!target.rect.contains(mouseX, mouseY)) {
                continue
            }

            when (mouseButton) {
                0 -> {
                    target.module.toggle()
                    UiSound.click()
                }

                1 -> {
                    if (target.module.values.any { it.shouldRender() }) {
                        if (!expandedModules.add(target.module.name)) {
                            expandedModules.remove(target.module.name)
                        }
                        markLayoutDirty()
                        UiSound.expand()
                    }
                }
            }

            return
        }

        super.mouseClicked(mouseX, mouseY, mouseButton)
    }

    public override fun mouseReleased(mouseX: Int, mouseY: Int, state: Int) {
        draggingColumn = null
        valueControlState.release { saveConfig(valuesConfig) }
        saveLayoutIfDirty()
        super.mouseReleased(mouseX, mouseY, state)
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (processSearchInput(typedChar, keyCode)) {
            return
        }

        if (ValueControls.keyTyped(
                typedChar,
                keyCode,
                valueControlState,
                save = { saveConfig(valuesConfig) },
                onChanged = {
                    // Values own their change side effects. The dirty state saves on release or commit.
                }
            )
        ) {
            return
        }

        if (processSidebarKeyboardNavigation(keyCode)) {
            return
        }

        if (keyCode in arrayOf(Keyboard.KEY_ESCAPE, ClickGUI.keyBind)) {
            if (keyCode != Keyboard.KEY_ESCAPE && ignoreClosing) {
                ignoreClosing = false
                return
            }

            mc.displayGuiScreen(null)
            return
        }

        super.keyTyped(typedChar, keyCode)
    }

    override fun onGuiClosed() {
        draggingColumn = null
        valueControlState.clearFocus()
        valueControlState.release { saveConfig(valuesConfig) }
        saveLayoutIfDirty()
        textCache.clear()
        Keyboard.enableRepeatEvents(false)
    }

    override fun doesGuiPauseGame() = false

    private fun refreshPerformanceProfile() {
        val profile = ClickGUI.performanceProfile
        if (profile == themeProfile) {
            return
        }

        themeProfile = profile
        theme = UiTheme.MODERN.forPerformanceProfile(profile)
        textCache.clear()
    }

    private fun trimText(font: FontRenderer, text: String, maxWidth: Number) =
        textCache.trim(font, text, maxWidth.toFloat().roundToInt().coerceAtLeast(0))

    private fun textWidth(font: FontRenderer, text: String) =
        textCache.width(font, text)

    private fun updateDragging(mouseX: Int, mouseY: Int) {
        val column = draggingColumn ?: return

        if (!Mouse.isButtonDown(0)) {
            draggingColumn = null
            return
        }

        val nextX = mouseX - dragOffsetX
        val nextY = mouseY - dragOffsetY
        val clampedX = nextX.coerceIn(4F, max(4F, width - column.width - 4F))
        val clampedY = nextY.coerceIn(4F, max(4F, height - HEADER_HEIGHT - 12F))

        if (column.x != clampedX || column.y != clampedY) {
            column.x = clampedX
            column.y = clampedY
            markLayoutDirty()
        }
    }

    private fun clampColumnIntoViewport(column: ColumnState, columnWidth: Float) {
        val clampedX = column.x.coerceIn(4F, max(4F, width - columnWidth - 4F))
        val clampedY = column.y.coerceIn(4F, max(4F, height - HEADER_HEIGHT - 12F))

        if (column.x == clampedX && column.y == clampedY) {
            return
        }

        column.x = clampedX
        column.y = clampedY

        if (column.manualPosition) {
            markLayoutDirty()
        }
    }

    private fun ensureColumns() {
        if (columns.isNotEmpty()) {
            return
        }

        var x = SIDE_MARGIN
        for (category in Category.entries) {
            columns[category] = ColumnState(x = x, y = TOP_MARGIN)
            x += DEFAULT_COLUMN_WIDTH + COLUMN_GAP
        }
    }

    private fun sidebarShellRect(): UiRect {
        val shellWidth = min(SIDEBAR_SHELL_WIDTH, (width - 18F).coerceAtLeast(260F))
        val shellHeight = min(SIDEBAR_SHELL_HEIGHT, (height - 18F).coerceAtLeast(240F))

        return UiRect(
            (width - shellWidth) / 2F,
            (height - shellHeight) / 2F,
            shellWidth,
            shellHeight
        )
    }

    private fun filteredSidebarModules(): List<SidebarModuleEntry> {
        val query = searchQuery.trim()
        val cacheKey = SidebarModuleCacheKey(selectedCategory, query)

        if (cacheKey == cachedSidebarModulesKey) {
            return cachedSidebarModules
        }

        val modules = if (query.isBlank()) {
            moduleManager[selectedCategory].map { module ->
                SidebarModuleEntry(module, selectedCategory, searchResult = false)
            }
        } else {
            Category.entries.flatMap { category ->
                moduleManager[category]
                    .asSequence()
                    .filter { module -> module.matchesSidebarSearch(query, category) }
                    .map { module -> SidebarModuleEntry(module, category, searchResult = true) }
                    .toList()
            }
        }

        cachedSidebarModulesKey = cacheKey
        cachedSidebarModules = modules

        return modules
    }

    private fun Module.matchesSidebarSearch(query: String, category: Category) =
        name.contains(query, true) ||
            spacedName.contains(query, true) ||
            description.contains(query, true) ||
            category.displayName.contains(query, true)

    private fun invalidateSidebarModulesCache() {
        cachedSidebarModulesKey = null
        cachedSidebarModules = emptyList()
    }

    private fun sidebarModuleHeight(module: Module): Float {
        val visibleValues = module.values.filter { it.shouldRender() }
        val valuesHeight = if (module.name in expandedModules && visibleValues.isNotEmpty()) {
            visibleValues.sumOf { ValueControls.height(it).toDouble() }.toFloat() + 6F
        } else {
            0F
        }

        return SIDEBAR_MODULE_HEIGHT + valuesHeight
    }

    private fun syntheticColumnHeight(): Float {
        var height = 3F

        for (section in SyntheticSection.entries) {
            height += ROW_HEIGHT
            if (section in expandedSyntheticSections) {
                height += syntheticColumnExpandedHeight(section) + 2F
            }
        }

        return height + ROW_HEIGHT
    }

    private fun syntheticColumnExpandedHeight(section: SyntheticSection) = when (section) {
        SyntheticSection.TARGETS -> TargetOption.entries.size * ROW_HEIGHT
        SyntheticSection.AUTO_SETTINGS -> max(1, autoSettingsList?.size ?: 1) * ROW_HEIGHT
    }

    private fun sidebarSyntheticContentHeight(section: SyntheticSection) = when (section) {
        SyntheticSection.TARGETS -> TargetOption.entries.size * (SIDEBAR_SYNTHETIC_ROW_HEIGHT + SIDEBAR_MODULE_GAP)
        SyntheticSection.AUTO_SETTINGS -> {
            val settings = autoSettingsList
            if (settings == null || settings.isEmpty()) {
                SIDEBAR_SYNTHETIC_ROW_HEIGHT + SIDEBAR_MODULE_GAP
            } else {
                settings.size * (SIDEBAR_AUTO_SETTING_ROW_HEIGHT + SIDEBAR_MODULE_GAP)
            }
        }
    }.toFloat()

    private fun columnWidth(): Float {
        val categoryCount = Category.entries.size
        val available = width - SIDE_MARGIN * 2F - COLUMN_GAP * (categoryCount - 1)
        return available.div(categoryCount).coerceIn(MIN_COLUMN_WIDTH, DEFAULT_COLUMN_WIDTH)
    }

    private fun expandedHeight(module: Module): Float {
        if (module.name !in expandedModules) {
            return 0F
        }

        val visibleValues = module.values.filter { it.shouldRender() }
        if (visibleValues.isEmpty()) {
            return 0F
        }

        val visibleHeight = visibleValues.sumOf {
            ValueControls.height(it).toDouble()
        }.toFloat()

        return visibleHeight + 2F
    }

    private fun processSidebarKeyboardNavigation(keyCode: Int): Boolean {
        if (ClickGUI.modernPreset != ModernClickGuiPreset.SIDEBAR_LIST || searchFocused) {
            return false
        }

        if (keyCode == Keyboard.KEY_F && isCtrlPressed()) {
            searchFocused = true
            valueControlState.clearFocus()
            UiSound.click()
            return true
        }

        if (keyCode == Keyboard.KEY_DELETE && searchQuery.isNotEmpty()) {
            setSearchQuery("")
            UiSound.click()
            return true
        }

        val entries = sidebarNavigationEntries()
        val currentIndex = entries.indexOfFirst { it.isSelected() }.coerceAtLeast(0)
        val nextIndex = when (keyCode) {
            Keyboard.KEY_UP -> (currentIndex - 1 + entries.size) % entries.size
            Keyboard.KEY_DOWN -> (currentIndex + 1) % entries.size
            Keyboard.KEY_HOME -> 0
            Keyboard.KEY_END -> entries.lastIndex
            else -> return false
        }

        selectSidebarNavigationEntry(entries[nextIndex])
        UiSound.click()
        return true
    }

    private fun sidebarNavigationEntries(): List<SidebarNavigationEntry> {
        val entries = mutableListOf<SidebarNavigationEntry>()

        Category.entries.forEach { entries += SidebarNavigationEntry.CategoryEntry(it) }
        SyntheticSection.entries.forEach { entries += SidebarNavigationEntry.SyntheticEntry(it) }

        return entries
    }

    private fun SidebarNavigationEntry.isSelected() = when (this) {
        is SidebarNavigationEntry.CategoryEntry -> selectedSyntheticSection == null && selectedCategory == category
        is SidebarNavigationEntry.SyntheticEntry -> selectedSyntheticSection == section
    }

    private fun selectSidebarNavigationEntry(entry: SidebarNavigationEntry) {
        when (entry) {
            is SidebarNavigationEntry.CategoryEntry -> {
                if (selectedCategory == entry.category && selectedSyntheticSection == null) {
                    return
                }

                selectedCategory = entry.category
                selectedSyntheticSection = null
            }

            is SidebarNavigationEntry.SyntheticEntry -> {
                if (selectedSyntheticSection == entry.section) {
                    return
                }

                selectedSyntheticSection = entry.section
                if (entry.section == SyntheticSection.AUTO_SETTINGS) {
                    ensureAutoSettingsRequested()
                }
            }
        }

        sidebarContentScroll = 0F
        invalidateSidebarModulesCache()
        markLayoutDirty()
    }

    private fun processSearchInput(typedChar: Char, keyCode: Int): Boolean {
        if (!searchFocused) {
            return false
        }

        when {
            keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER -> {
                searchFocused = false
            }

            keyCode == Keyboard.KEY_BACK -> {
                setSearchQuery(searchQuery.dropLast(1))
            }

            keyCode == Keyboard.KEY_DELETE -> {
                setSearchQuery("")
            }

            keyCode == Keyboard.KEY_V && isCtrlPressed() -> {
                val pasted = getClipboardString()
                    ?.filter { ColorUtils.isAllowedCharacter(it) }
                    ?.takeIf { it.isNotEmpty() }

                if (pasted != null) {
                    appendSearchText(pasted)
                }
            }

            ColorUtils.isAllowedCharacter(typedChar) -> {
                appendSearchText(typedChar.toString())
            }
        }

        return true
    }

    private fun appendSearchText(text: String) {
        setSearchQuery(searchQuery + text)
    }

    private fun setSearchQuery(query: String) {
        val nextQuery = query
            .filter { ColorUtils.isAllowedCharacter(it) }
            .take(MAX_SEARCH_QUERY_LENGTH)

        if (searchQuery == nextQuery) {
            return
        }

        searchQuery = nextQuery
        sidebarContentScroll = 0F
        invalidateSidebarModulesCache()
        markLayoutDirty()
    }

    private fun handleSyntheticSectionClick(section: SyntheticSection) {
        if (ClickGUI.modernPreset == ModernClickGuiPreset.SIDEBAR_LIST) {
            if (selectedSyntheticSection != section) {
                selectedSyntheticSection = section
                sidebarContentScroll = 0F
                invalidateSidebarModulesCache()
                markLayoutDirty()
            }
        } else if (!expandedSyntheticSections.add(section)) {
            expandedSyntheticSections.remove(section)
            markLayoutDirty()
        } else {
            markLayoutDirty()
        }

        if (section == SyntheticSection.AUTO_SETTINGS) {
            ensureAutoSettingsRequested()
        }

        UiSound.expand()
    }

    private fun handleSyntheticAction(action: SyntheticAction) {
        when (action) {
            is SyntheticAction.ToggleTarget -> {
                action.target.toggle()
                saveConfig(valuesConfig)
                UiSound.click()
            }

            is SyntheticAction.ApplyAutoSetting -> {
                applyAutoSetting(action.setting)
                UiSound.click()
            }

            SyntheticAction.RefreshAutoSettings -> {
                ensureAutoSettingsRequested(force = true)
                UiSound.click()
            }

            SyntheticAction.OpenHudDesigner -> {
                UiSound.click()
                mc.displayGuiScreen(GuiHudDesigner())
            }
        }
    }

    private fun ensureAutoSettingsRequested(force: Boolean = false) {
        if (!force && (autoSettingsList != null || autoSettingsLoading)) {
            return
        }

        autoSettingsLoading = true
        loadSettings(useCached = !force) {
            mc.addScheduledTask {
                autoSettingsLoading = false
            }
        }
    }

    private fun applyAutoSetting(setting: AutoSettings) {
        if (applyingAutoSettingId != null) {
            return
        }

        applyingAutoSettingId = setting.settingId

        SharedScopes.IO.launch {
            try {
                chat("Loading settings...")

                val settings = ClientApi.getSettingsScript(settingId = setting.settingId)

                chat("Applying settings...")
                SettingsUtils.applyScript(settings)

                chat("§6Settings applied successfully.")
                mc.addScheduledTask {
                    HUD.addNotification(Notification.informative("ClickGUI", "Updated Settings"))
                    mc.playSound("random.anvil_use".asResourceLocation())
                }
            } catch (e: Exception) {
                ClientUtils.LOGGER.error("Failed to load settings", e)
                chat("Failed to load settings: ${e.message}")
            } finally {
                mc.addScheduledTask {
                    if (applyingAutoSettingId == setting.settingId) {
                        applyingAutoSettingId = null
                    }
                }
            }
        }
    }

    private fun markLayoutDirty() {
        layoutDirty = true
    }

    private fun saveLayoutIfDirty() {
        if (!layoutDirty) {
            return
        }

        saveConfig(clickGuiConfig)
    }

    private fun parseCategory(name: String?) =
        name?.let { categoryName ->
            Category.entries.find {
                it.name.equals(categoryName, true) || it.displayName.equals(categoryName, true)
            }
        }

    private fun parseSyntheticSection(name: String?) =
        name?.let { sectionName ->
            SyntheticSection.entries.find {
                it.name.equals(sectionName, true) || it.displayName.equals(sectionName, true)
            }
        }

    private fun JsonObject.objectOrNull(key: String) =
        runCatching { this[key]?.asJsonObject }.getOrNull()

    private fun JsonObject.stringOrNull(key: String) =
        runCatching { this[key]?.asString }.getOrNull()

    private fun JsonObject.floatOrNull(key: String) =
        runCatching { this[key]?.asFloat }.getOrNull()

    private fun JsonObject.booleanOrNull(key: String) =
        runCatching { this[key]?.asBoolean }.getOrNull()

    private enum class SyntheticSection(
        val displayName: String,
        val shortDescription: String,
        val description: String
    ) {
        TARGETS("Targets", "Entity filters", "Choose which entities modules may target"),
        AUTO_SETTINGS("Auto Settings", "Cloud presets", "Browse and apply shared settings presets")
    }

    private enum class TargetOption(
        val displayName: String,
        val description: String
    ) {
        PLAYER("Players", "Include player entities"),
        MOB("Mobs", "Include hostile mobs"),
        ANIMAL("Animals", "Include passive animals"),
        INVISIBLE("Invisible", "Include invisible entities"),
        DEAD("Dead", "Include dead entities");

        fun enabled() = when (this) {
            PLAYER -> Targets.player
            MOB -> Targets.mob
            ANIMAL -> Targets.animal
            INVISIBLE -> Targets.invisible
            DEAD -> Targets.dead
        }

        fun toggle() {
            when (this) {
                PLAYER -> Targets.player = !Targets.player
                MOB -> Targets.mob = !Targets.mob
                ANIMAL -> Targets.animal = !Targets.animal
                INVISIBLE -> Targets.invisible = !Targets.invisible
                DEAD -> Targets.dead = !Targets.dead
            }
        }
    }

    private sealed class SyntheticAction {
        data class ToggleTarget(val target: TargetOption) : SyntheticAction()
        data class ApplyAutoSetting(val setting: AutoSettings) : SyntheticAction()
        object RefreshAutoSettings : SyntheticAction()
        object OpenHudDesigner : SyntheticAction()
    }

    private data class ColumnState(
        var x: Float,
        var y: Float,
        var width: Float = DEFAULT_COLUMN_WIDTH,
        var scroll: Float = 0F,
        var manualPosition: Boolean = false
    )

    private data class ColumnLayout(
        val rect: UiRect,
        val contentHeight: Float,
        val viewportHeight: Float,
        val scrollMax: Float
    )

    private data class ModuleHitTarget(
        val rect: UiRect,
        val module: Module
    )

    private data class ValueHitTarget(
        val rect: UiRect,
        val value: Value<*>
    )

    private data class SidebarModuleEntry(
        val module: Module,
        val category: Category,
        val searchResult: Boolean
    )

    private data class SidebarModuleCacheKey(
        val selectedCategory: Category,
        val query: String
    )

    private sealed class SidebarNavigationEntry {
        data class CategoryEntry(val category: Category) : SidebarNavigationEntry()
        data class SyntheticEntry(val section: SyntheticSection) : SidebarNavigationEntry()
    }

    private data class CategoryHitTarget(
        val rect: UiRect,
        val category: Category
    )

    private data class SyntheticHitTarget(
        val rect: UiRect,
        val section: SyntheticSection
    )

    private data class SyntheticActionHitTarget(
        val rect: UiRect,
        val action: SyntheticAction
    )

    private data class SidebarContentLayout(
        val rect: UiRect,
        val contentHeight: Float,
        val scrollMax: Float
    )
}
