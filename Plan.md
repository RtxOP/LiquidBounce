# Modern ClickGUI Revamp Plan

## Goal

Replace the legacy ClickGUI with a modern, style-flexible interface. The first style target is the compact floating-column layout from the original reference: dark translucent panels, black headers, magenta/purple active states, smooth expanded module settings, rounded corners, per-column scrolling, and a dimmed Minecraft background.

The architecture must also support full-window sidebar/list styles like the Rise reference: a rounded centered shell, branded sidebar, search entry, icon/category navigation, large module list rows, descriptions, inline expanded settings, and a themed but inexpensive background treatment. These should be layout presets over the same data, input, widget, animation, and persistence systems rather than separate ClickGUI rewrites.

Other UI surfaces, especially the HUD editor, must share the same control language without being forced into a module ClickGUI layout. `GuiHudDesigner` is a canvas/editor workflow, while ClickGUI is a module browser. They should share controls, theme tokens, sounds, value adapters, focus handling, and rendering primitives, but keep separate screen shells.

This is an architecture revamp, not a skin swap. The current ClickGUI couples state, layout, rendering, input, and config persistence in the same objects, so the migration should build a new implementation path and then retire the old one once feature parity is proven.

## Implementation Status

Current branch: `visuals`, based on `origin/legacy`.

Completed so far:

- Added the modern ClickGUI opening path and made `ColumnDeck` the default `ClickGUI` style while keeping `SidebarList` and legacy styles selectable for fallback.
- Added shared UI primitives for rectangles, theme tokens, sounds, text trimming, and value controls under `ui/client/common`.
- Added `ModernClickGuiScreen` as an initial `ColumnDeck` implementation with dim background overlay, rounded category columns, draggable headers, per-column scrolling, module toggles, inline expansion, and packet-close protection.
- Added shared value-control coverage for `BoolValue`, `ListValue`, `IntValue`, `FloatValue`, `BlockValue`, `IntRangeValue`, `FloatRangeValue`, `TextValue`, `FontValue`, and `ColorValue`.
- Moved value mutation out of render-only drawing paths in the modern screen. Click/drag/key input now routes through shared controls, and slider/text/color edits save through caller-provided save hooks on release, commit, or close.
- Removed the temporary six-setting preview limit in expanded module rows. Expanded modules now render all visible values and rely on column scrolling.
- Kept the default background effect cheap: plain dim overlay plus rounded UI chrome, with no blur or full-screen post-processing.
- Added the first `SidebarList` preset pass: centered rounded shell, brand/version sidebar, icon category rows, scoped search, large module rows with descriptions, content scrolling, and shared inline value controls.
- Added modern layout persistence under a top-level `Modern` object in `clickgui.json`, while preserving legacy panel config data in the same file.
- Added first-pass migration from legacy panel state into modern state when no `Modern` object exists: category panel positions and expanded module settings are imported.
- Ported synthetic ClickGUI integrations into the modern screen: Targets, Auto Settings, and HUD Editor are reachable in both `ColumnDeck` and `SidebarList`.
- Targets render as modern toggle rows and save through `valuesConfig`; Auto Settings renders cloud preset rows and applies scripts through the existing async settings path.
- Modernized the HUD editor panel first pass: selected-element values now use the shared `ValueControls`, create/select/reset rows use modern rounded panel styling, HUD editor sounds use `UiSound`, and panel click/wheel/key focus no longer leaks into the HUD canvas.
- Added first-pass `SidebarList` polish: search now scans all categories, search results show their source category, search results are cached by query/category, Ctrl+F focuses search, keyboard up/down/home/end navigate sidebar categories and utility sections, and dragged `ColumnDeck` columns clamp inside the viewport.
- Added first-pass performance profile support: `ClickGUI` exposes `Fast`, `Balanced`, and `Fancy`; the modern screen applies cheap profile-specific theme variants without blur or framebuffer effects.
- Added a bounded `UiTextCache` and switched stable modern ClickGUI row/header labels to cached trimming/width lookups; the base ellipsis trimmer now uses binary search instead of a character-by-character loop.
- Added a visual refinement pass after screenshot review: darker and more opaque surfaces, soft rounded-rect shadows/borders, denser `SidebarList` module rows, restrained active rows with accent strips, cleaner `ColumnDeck` title bars, more solid shared value-control rows, centered switch knob/capsule geometry, circular slider handles, and robust range-slider fill segments.
- Added a second visual correction pass from in-game screenshots: restored rounded slider tracks without knob halos, fixed range-slider overlap/tie handle selection, removed ColumnDeck scrollbars, switched ColumnDeck headers to top-only rounding, made ColumnDeck module rows plain rectangles, removed module expansion symbols, removed per-setting row backgrounds, removed the `SidebarList` module/result count line, removed accent-strip rendering, tightened the `SidebarList` search field with larger text and shared `EditableText` input handling, added a distinct settings-area surface, and replaced `SidebarList` enabled bars with compact switches.
- Added a first adaptive layout pass: `ColumnDeck` now uses legacy-style GL scaling with transformed mouse input, scaled scissor boxes, viewport-aware drag/clamp math, user `ClickGUI` scale support, and width/height fit checks for saved column layouts; `SidebarList` now keeps the main shell readable while the left navigation scrolls independently when categories/utilities overflow.

Not completed yet:

- The modern implementation is still a compact first pass, not the final separated controller/layout/renderer architecture.
- `SidebarList` still needs manual visual validation and deeper reference-matching polish after the new scrollable-navigation pass.
- Modern ClickGUI persistence still needs compatibility tests with old `clickgui.json` files, migration logging/backups, and final schema review before legacy removal.
- Synthetic sections still need loading/error-state polish and manual validation against the legacy behavior.
- HUD editor modernization still needs manual visual validation, responsive positioning polish, and follow-up work on the broader editor shell/toolbar model.
- Bounded animations and deeper performance profiling still need dedicated implementation.
- Manual visual validation and screenshot comparison are still pending. Build verification was intentionally not run.

## Current Architecture Snapshot

Primary files:

- `src/main/java/net/ccbluex/liquidbounce/ui/client/clickgui/ClickGui.kt`
- `src/main/java/net/ccbluex/liquidbounce/ui/client/clickgui/Panel.kt`
- `src/main/java/net/ccbluex/liquidbounce/ui/client/clickgui/elements/ButtonElement.kt`
- `src/main/java/net/ccbluex/liquidbounce/ui/client/clickgui/elements/ModuleElement.kt`
- `src/main/java/net/ccbluex/liquidbounce/ui/client/clickgui/style/Style.kt`
- `src/main/java/net/ccbluex/liquidbounce/ui/client/clickgui/style/styles/*Style.kt`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/render/ClickGUI.kt`
- `src/main/java/net/ccbluex/liquidbounce/file/configs/ClickGuiConfig.kt`
- `src/main/java/net/ccbluex/liquidbounce/ui/client/hud/designer/GuiHudDesigner.kt`
- `src/main/java/net/ccbluex/liquidbounce/ui/client/hud/designer/EditorPanel.kt`
- `src/main/java/net/ccbluex/liquidbounce/ui/client/hud/HUD.kt`
- `src/main/java/net/ccbluex/liquidbounce/ui/client/hud/element/Element.kt`
- `src/main/java/net/ccbluex/liquidbounce/file/configs/HudConfig.kt`

Main issues to address:

- `ClickGui` is a global `GuiScreen` singleton that owns panels, handles draw/input, mutates scale/scroll, opens HUD designer, loads auto settings, and saves config.
- `Panel` combines layout, dragging, scrolling, animation, visibility, hit testing, and element positioning.
- `Style.drawModuleElementAndClick` mixes rendering and interaction. Value controls mutate settings during draw traversal.
- `ClickGuiConfig` persists concrete panel/module-element state directly, making future layout changes hard.
- Multiple legacy styles exist, but all share the same panel/element architecture.
- Expanded settings are rendered as side popouts instead of inline rows like the reference.
- `GuiHudDesigner` is a canvas workflow: it renders live HUD elements, selects elements by border hit tests, drags elements through `HUD.handleMouseMove`, scales elements with the mouse wheel, deletes with Delete, and saves through `HudConfig`.
- `EditorPanel` is a separate floating property panel with create, selection, reset confirmation, and selected-element edit modes. It hand-renders controls for `element.values`.
- `EditorPanel` currently imports `ClickGui` only for `ClickGui.style.clickSound()` and imports `LiquidBounceStyle.rgbaLabels`; those are shared UI concerns leaking through the legacy ClickGUI style layer.
- HUD elements extend `Configurable`, so editor properties and module settings already share the same `Value<*>` model. The shared abstraction should be `Configurable`/`Value`, not `Module`.
- `EditorPanel` supports `BoolValue`, `FloatValue`, `IntValue`, `ListValue`, `FontValue`, and `ColorValue`, but has a `TODO` branch for unsupported value types. ClickGUI supports more, so a shared value-control layer would close this gap.

## Target Experience

The first production target should match the compact floating-column reference closely:

- Background: gameplay remains visible behind a dim overlay. Real-time blur is not part of the default target.
- Layout: horizontal category columns such as Combat, Render, Movement, Player, and Misc.
- Panels: compact rounded columns with black headers, subtle shadows, and a dark body.
- Module rows: active modules use a magenta/purple fill or accent strip; inactive modules remain dark with light text.
- Expanded module settings: render inline under the selected module inside the same column, with stable row heights.
- Controls: toggles, sliders, range sliders, list/choice rows, text fields, color pickers, font selectors, and block/id sliders.
- Motion: smooth open/close, hover, scroll, and expand animations without layout jitter.
- Input: left click toggles modules/controls, right click expands settings, drag moves columns, wheel scrolls the hovered column, Escape/keybind closes.
- Scaling: responsive to `ScaledResolution`, with saved positions clamped inside the viewport.

The second supported style family should match the Rise-like sidebar/list reference:

- Shell: one centered rounded window with a dark translucent body and an inexpensive themed/dimmed background.
- Sidebar: client name/version, search field, icon-backed category navigation, and utility entries such as Script, Themes, and Language if those features exist or become available.
- Content: selected category renders a vertical module list with large rows, module names, muted descriptions, and inline expanded settings.
- Selection: active category has a light pill/highlight; active modules use an accent state that works with the darker list surface.
- Scrolling: sidebar and content scroll independently only when needed, with thin unobtrusive scrollbars.
- Search: filters modules by name and optionally description while preserving category context.
- Layout: no draggable columns; window position, size, selected category, search text, and scroll state are the persisted style-specific state.

Supported style families should be treated as named presets:

- `ColumnDeck`: compact floating category columns matching the first reference.
- `SidebarList`: centered sidebar plus module list matching the Rise-like reference.
- Future presets may reuse the same model/widgets, but arbitrary third-party skin support is not part of the first pass.

HUD editor target:

- Keep a canvas-first editor shell for `GuiHudDesigner`.
- Preserve live HUD rendering, element border selection, dragging, wheel scaling, Delete removal, and `HudConfig` persistence.
- Replace the old `EditorPanel` control drawing with shared value controls.
- Use a modern inspector panel for selected element properties and a separate create/select panel for HUD elements.
- Do not represent HUD elements as module rows or category columns.

## Performance Budget

Performance is a primary requirement. The GUI should look modern through layout, typography, color, and restrained animation, not through expensive full-screen post-processing.

Default performance rules:

- No real-time full-screen blur in the default preset.
- No per-frame framebuffer captures for background effects.
- No full-screen post-processing dependency for the default look.
- Rounded rectangle shaders are acceptable for normal UI chrome and should be used where they materially improve the visual quality.
- Prefer solid translucent rectangles over animated gradients.
- Use gradients only for small accents or cached/static surfaces.
- Keep rounded corners modest and avoid deeply nested rounded clipping.
- Use rounded rectangle draws for shells, panels, selected pills, module rows, and controls when visible.
- Avoid pathological overdraw and nested clipping, but do not discard rounded UI just to avoid shader use.
- Recompute layout only when screen size, style, search query, expanded state, or visible values change.
- Cache text widths for stable module/value labels.
- Avoid allocating model/layout lists inside hot render paths when dirty-state caching is practical.
- Keep animation counts bounded to visible rows and active controls.
- Disable or reduce animations automatically under a low-performance profile.
- Do not regenerate color picker textures every frame unless hue/alpha state changes.

Performance profiles:

- `Fast`: solid dim overlay, no blur, minimal animations, simple scrollbars, no animated gradients, rounded UI retained.
- `Balanced`: default profile; solid dim overlay, rounded UI retained, bounded row animations, cached layout and text metrics.
- `Fancy`: optional cosmetic profile; may enable heavier effects, but blur remains opt-in and should be disabled on shader failure or low FPS.

Acceptance criteria:

- `Fast` and `Balanced` do not rely on blur or full-screen post-processing.
- Opening the GUI should not cause a major FPS drop on modest hardware.
- Scrolling long module lists should not allocate heavily or rebuild unrelated layout.
- Cosmetic effects must fail closed: if a shader or expensive effect is unavailable, the UI falls back to the normal dim overlay.

## Phase 0: Requirements, Audit, and Visual Spec

Deliverables:

- Capture both reference styles as small specs:
  - `ColumnDeck`: column width, header height, row height, radius, spacing, palette, typography, and bounded animation timings.
  - `SidebarList`: shell size, sidebar width, category row height, module list row height, search field geometry, radius, spacing, palette, typography, and bounded animation timings.
  - Performance constraints: default effects, optional effects, dirty-state triggers, and the expected behavior under `Fast`, `Balanced`, and `Fancy`.
- Inventory all current ClickGUI features that must survive the rewrite:
  - Category module panels.
  - Targets panel.
  - Auto Settings panel and async settings loading.
  - HUD designer icon/entry point.
  - Module toggle and active/inactive states.
  - Module setting expansion.
  - Config save/load.
  - Keybind close behavior and server close-window packet cancellation.
- Inventory all current HUD editor workflows that must survive:
  - Live element canvas rendering.
  - Border selection and selection clearing.
  - Element dragging and z-order promotion.
  - Mouse-wheel element scaling.
  - Create element list with single-element filtering.
  - Selected-element inspector.
  - Reset confirmation.
  - Delete key and delete button behavior.
  - Element side/origin switching.
  - `HudConfig` load/save of position, scale, side, and element values.
- Inventory all value types from `Values.kt`: `BoolValue`, `IntValue`, `IntRangeValue`, `FloatValue`, `FloatRangeValue`, `TextValue`, `FontValue`, `BlockValue`, `ListValue`, and `ColorValue`.
- Decide whether legacy styles remain temporarily selectable or whether the new presets replace them after migration.

Acceptance criteria:

- Written target specs exist for `ColumnDeck` and `SidebarList` before code changes begin.
- All current behaviors have an owner in the new architecture or are explicitly marked out of scope.
- The first migration path preserves user settings and avoids deleting existing `clickgui.json` data.
- The HUD editor plan preserves `hud.json`/`HudConfig` behavior and does not couple the editor to module-specific layout.

## Phase 1: New Architecture Skeleton

Create a new implementation package, tentatively:

- `net.ccbluex.liquidbounce.ui.client.clickgui.modern`

Core classes:

- `ModernClickGuiScreen : GuiScreen`
- `ModernClickGuiController`
- `ModernClickGuiState`
- `ClickGuiLayoutEngine`
- `ClickGuiRenderer`
- `ClickGuiInputRouter`
- `ClickGuiTheme`
- `ClickGuiStylePreset`
- `ClickGuiLayoutStrategy`
- `ClickGuiAnimationStore`
- `ClickGuiPersistenceModel`

Shared UI foundation classes, tentatively in `net.ccbluex.liquidbounce.ui.client.common` or `net.ccbluex.liquidbounce.ui.client.components`:

- `UiTheme`
- `UiSound`
- `UiRect`
- `UiInputState`
- `UiFocusState`
- `UiScrollState`
- `UiAnimationState`
- `UiPerformanceProfile`
- `UiTextMetricsCache`
- `ValueControl`
- `ValueControlRenderer`
- `ValueControlController`
- `ConfigurableInspectorModel`

Design rules:

- Keep module/value APIs unchanged.
- Treat module/category/value data as source data, not layout objects.
- Separate draw, layout, hit testing, input, and persistence.
- Never mutate settings from render-only functions.
- Keep style presets declarative where possible: palette, spacing, sizing, typography, chrome behavior, and layout strategy.
- Share value widgets across presets unless a preset needs a different widget renderer for the same semantic control.
- Share value widgets with the HUD editor through `Configurable`/`Value<*>` adapters. ClickGUI can wrap modules as configurables, and the HUD editor can wrap selected `Element` instances as configurables.
- Move shared sound helpers and RGBA labels out of legacy `Style`/`LiquidBounceStyle` before the modern editor or ClickGUI depends on them.
- Use stable IDs for UI state:
  - Category columns: `category:<name>`
  - Sidebar categories: `category:<name>`
  - Modules: `module:<module.name>`
  - Values: `module:<module.name>/value:<value.name>`
  - Synthetic sections: `targets`, `auto-settings`, `hud`

Implementation steps:

- Add `ModernClickGuiScreen` while keeping old `ClickGui` available for rollback.
- Update `ClickGUI.onEnable()` to open modern screen behind a temporary flag or style option.
- Keep packet cancellation working by checking both legacy and modern screen classes.
- Add a style value in `ClickGUI.kt`, for example `Legacy`, `ColumnDeck`, and `SidebarList`, until the legacy path can be removed.

Acceptance criteria:

- Modern screen opens and closes with the existing ClickGUI keybind.
- No legacy panel/style class is required by the modern screen.
- Switching between `ColumnDeck` and `SidebarList` reuses the same module/value data and widget behavior.
- The legacy screen can still be opened during the transition if the temporary fallback is kept.

## Phase 1A: Shared UI Foundation

This phase should happen before deep ClickGUI implementation so the HUD editor does not inherit another ClickGUI-specific control layer.

Deliverables:

- Create a shared UI package for theme tokens, rectangles, hit testing, clipping helpers, focus state, scroll state, animation state, and sounds.
- Create a `ValueControl` abstraction that maps each `Value<*>` type to renderable rows and input actions.
- Create a `ConfigurableInspectorModel` that can inspect any `Configurable`, including modules and HUD elements.
- Move RGBA labels and color editing focus helpers out of `Style`.
- Move `clickSound()` and `showSettingsSound()` into shared UI sound helpers.
- Provide shared save/debounce hooks so value controls can save `valuesConfig`, `hudConfig`, or another owner-specific config through the caller.

Shared value control coverage:

- `BoolValue`: switch/toggle.
- `ListValue`: dropdown or option selector.
- `IntValue`, `FloatValue`, `BlockValue`: slider.
- `IntRangeValue`, `FloatRangeValue`: dual-handle range slider.
- `TextValue`: focused text field.
- `FontValue`: previous/next selector or dropdown.
- `ColorValue`: swatch, rainbow toggle, RGBA editing, hue/alpha picker.

Acceptance criteria:

- Shared value controls can render and mutate a module's values and a HUD element's values without importing ClickGUI style classes.
- Value-control input is separated from render-only code.
- The HUD editor no longer needs `ClickGui.style.clickSound()` or `LiquidBounceStyle.rgbaLabels`.
- Unsupported editor value types are eliminated or explicitly disabled with a visible reason.

## Phase 2: State Model and Layout Engine

State model:

- `ColumnState`: id, title, x, y, width, scrollOffset, collapsed/open, pinned/visible.
- `ShellState`: x, y, width, height, selected category, search query, sidebar scroll, content scroll.
- `ModuleRowState`: module id, expanded, hover progress, active progress.
- `ValueRowState`: value id, focused, open dropdown, dragging slider, text cursor/editing state.
- `DragState`: active column id, offset, start position.
- `FocusState`: currently focused text field or color picker.

Layout model:

- Build a list of `ColumnModel` from `Category.entries` and synthetic panels.
- Each column emits a flat list of layout nodes:
  - Header.
  - Module row.
  - Expanded value rows.
  - Synthetic button rows.
  - Optional footer/status row.
- Layout returns immutable rectangles for this frame. Input uses those rectangles for hit testing.
- Per-column clipping must be applied so expanded settings and scroll content never draw outside panel bounds.

Sidebar/list layout model:

- Build a `ShellModel` with sidebar, search box, navigation rows, content viewport, and optional footer/utility rows.
- Selected category controls the module list shown in the content area.
- Search filters module rows and can optionally show cross-category results with category labels.
- Expanded value rows render inline under their module row in the content viewport.
- The window should clamp to the viewport and scale down gracefully at small resolutions.

Reference layout defaults:

- Column width: 112 to 126 scaled pixels.
- Header height: 20 pixels.
- Module row height: 17 to 19 pixels.
- Value row height: 18 to 28 pixels depending on control type.
- Panel radius: 5 to 7 pixels.
- Header/body gap: 0 to 1 pixel.
- Inter-column gap: 12 to 18 pixels.
- Accent color: magenta/purple, with a secondary darker purple for hover/pressed states.

Sidebar/list layout defaults:

- Shell width: 560 to 680 scaled pixels, clamped to screen width minus margins.
- Shell height: 380 to 470 scaled pixels, clamped to screen height minus margins.
- Sidebar width: 140 to 170 pixels.
- Search field height: 22 pixels.
- Category row height: 24 pixels.
- Module row height: 54 to 64 pixels when collapsed.
- Expanded value row height: 18 to 28 pixels depending on control type.
- Shell radius: 8 to 10 pixels.
- Accent style: light selected-category pill with darker content rows and muted descriptions.

Acceptance criteria:

- Categories lay out horizontally and remain visible at common resolutions.
- `SidebarList` keeps the shell centered and readable at common resolutions.
- Column dragging and saved positions do not allow permanent off-screen placement.
- Expanding a module changes only that column's content height and scroll range.
- In `SidebarList`, expanding a module changes only the content scroll range and not the sidebar geometry.
- Layout can be recomputed every frame without mutating module values.

## Phase 3: Rendering Foundation

Use existing rendering primitives first:

- `RenderUtils.drawRect` or `Gui.drawRect` for the full-screen dim overlay.
- `RenderUtils.drawRoundedRect` for panels, rows, selected states, switches, and controls.
- `RenderUtils.drawRoundedGradientRect` for selected prominent surfaces or accents, but not as the default for every row/control.
- `RenderUtils.drawRoundedBorder`
- `RenderUtils.withClipping` where suitable
- `Fonts.fontRegular30`, `fontRegular35`, `fontSemibold35`, and `fontSemibold40`
- Existing blur shader path from `HUD` only as an optional `Fancy` profile experiment, never as a default requirement.

Renderer responsibilities:

- Draw a cheap dim overlay behind the GUI using a plain rectangle.
- Draw the active style chrome:
  - `ColumnDeck`: each column shell and black centered category header.
  - `SidebarList`: centered rounded shell, brand/version text, sidebar, search field, and content viewport.
- Draw module rows with inactive, hovered, active, selected, and inactive-active visual states.
- Draw expanded setting controls inline.
- Draw scrollbars only when content exceeds visible height.
- Draw tooltips after all columns, above clipped content.

Theme tokens:

- `backgroundOverlay`: black with alpha around 90 to 140.
- `panelBackground`: near-black with alpha around 210 to 235.
- `panelHeader`: black with alpha around 235 to 255.
- `rowBackground`: dark gray with alpha around 180 to 220.
- `accent`: magenta/purple.
- `accentHover`: brighter magenta.
- `selectionPill`: light translucent sidebar selection color for `SidebarList`.
- `textPrimary`: white.
- `textMuted`: medium gray.
- `border`: black or low-alpha gray.

Acceptance criteria:

- The static `ColumnDeck` view visually matches the first reference at 1280x720 and 854x480.
- The static `SidebarList` view visually matches the Rise-like reference at 1280x720 and 854x480.
- Rounded corners, header/body alignment, text centering, and clipping are clean.
- The screen remains readable over bright and dark gameplay backgrounds.
- The dim overlay alone looks intentional; blur is not needed for acceptance.

## Phase 4: Input Router and Interaction Model

Input should be processed in this order:

1. Focused widget interactions, such as text editing or slider dragging.
2. Foremost hovered expanded value control.
3. Foremost hovered module row.
4. Foremost hovered column header/body.
5. Global shortcuts.

Required interactions:

- Left click module row: toggle module.
- Right click module row: expand/collapse values if values are renderable.
- Left click sidebar category in `SidebarList`: select category and reset content scroll if needed.
- Typing in the `SidebarList` search field: filter visible modules without changing module state.
- Left click boolean: toggle value.
- Left click/drag numeric slider: update value and save after release or debounce.
- Left click list row: open/close dropdown or cycle value based on final widget design.
- Mouse wheel over column: scroll that column.
- Mouse wheel over `SidebarList` content: scroll module list; over sidebar: scroll navigation only if it overflows.
- Mouse wheel while holding control: optional GUI scale change, if retained.
- Drag header: move column.
- Drag shell header/background in `SidebarList`: optional window movement, if enabled by spec.
- Escape or ClickGUI keybind: close screen.
- Middle click/drag autoscroll: decide during spec phase whether to keep or remove.

Design rules:

- Draw functions may read interaction state but must not dispatch clicks.
- Value writes go through one controller path, making save/debounce behavior consistent.
- Text fields and color RGBA editing must handle repeat keys only while focused.

Acceptance criteria:

- No setting changes occur from render-only code paths.
- Click targets match rendered rectangles.
- Dragging, scrolling, and focused controls do not fight each other.
- Search focus, text value focus, and global close key handling are unambiguous.
- Closing the GUI saves layout and clears transient focus/drag state.

## Phase 5: Module and Value Widgets

Value widgets should be implemented as shared controls from Phase 1A, then rendered inside ClickGUI module rows by adapters. They should not live inside a ClickGUI style class.

Implement widgets in this order:

1. Module row: name, active fill, inactive-active alpha, expand affordance.
2. `BoolValue`: compact toggle/switch or highlighted row.
3. `ListValue`: dropdown or compact option row.
4. `IntValue`, `FloatValue`, `BlockValue`: single slider with value label and suffix.
5. `IntRangeValue`, `FloatRangeValue`: dual-handle range slider.
6. `TextValue`: focused text input row.
7. `FontValue`: previous/next selector or dropdown.
8. `ColorValue`: collapsed swatch row, expandable picker, hue/alpha sliders, rainbow toggle.

Widget design targets:

- Rows remain compact, aligned, and readable.
- Widgets can render in both compact column rows and wider sidebar-list rows.
- Sliders use visible tracks and high-contrast handles.
- Toggles use a clear on/off state, not only text color.
- Color values show an actual swatch.
- List and font controls avoid drawing outside the column unless a deliberate overlay is added.

Acceptance criteria:

- Every `Value` type currently supported by the legacy style can be viewed and changed.
- `value.shouldRender()` is respected.
- Existing value persistence through `ValuesConfig` remains unchanged.
- Slider dragging saves once after release/debounce, not every render call.
- The same controls can be reused by `EditorPanel` or its replacement inspector for HUD element properties.

## Phase 6: Persistence and Migration

Current `ClickGuiConfig` stores panel state by panel name and module settings expansion under each panel. The modern config should store semantic layout state instead of concrete UI objects.

Proposed modern schema:

```json
{
  "version": 2,
  "style": "ColumnDeck",
  "performanceProfile": "Balanced",
  "backgroundEffect": "Dim",
  "scale": 1.0,
  "styles": {
    "ColumnDeck": {
      "columns": {
        "category:combat": {
          "x": 28,
          "y": 24,
          "width": 118,
          "visible": true,
          "scroll": 0
        }
      }
    },
    "SidebarList": {
      "shell": {
        "x": 60,
        "y": 36,
        "width": 620,
        "height": 420,
        "selectedCategory": "combat",
        "contentScroll": 0,
        "sidebarScroll": 0
      },
      "search": {
        "query": ""
      }
    }
  },
  "expandedModules": {
    "module:InventoryMove": true
  },
  "syntheticSections": {
    "targets": { "visible": true, "expanded": false },
    "auto-settings": { "visible": true, "expanded": false }
  }
}
```

Migration steps:

- Extend `ClickGuiConfig` to detect missing version or legacy object shape.
- On legacy config load, map panel names to category IDs where possible.
- Preserve module expanded state by module name.
- Use default positions for new columns when old coordinates do not map cleanly.
- Initialize `SidebarList` shell state from defaults because legacy floating panel positions do not map directly to a centered shell.
- Write modern schema only after a successful modern close/save.
- Keep a backup path or log message for failed migration instead of throwing away old config.

Acceptance criteria:

- Existing users can open the modern GUI without deleting `clickgui.json`.
- A malformed or partial config falls back to default layout.
- Modern saves do not depend on legacy `Panel` or `ModuleElement`.

## Phase 7: Synthetic Sections and Integrations

Targets:

- Replace legacy `setupTargetsPanel` with a synthetic column or footer section backed by `EntityUtils.Targets`.
- Use the same boolean widget as normal settings.

Auto Settings:

- Replace legacy `setupSettingsPanel` with a synthetic column.
- Preserve cached loading behavior and async settings application.
- Render loading, empty, and error states.
- Keep hover/description text or convert it into a compact tooltip.

HUD designer:

- Keep the HUD designer entry point visible but modernize it.
- Use a small icon button or row instead of the current bottom-left image hotspot.
- Make the hit rectangle explicit in the input router.

ClickGUI module settings:

- Update `ClickGUI.kt` values to support modern-only options:
  - Style preset: `ColumnDeck` or `SidebarList`.
  - Performance profile: `Fast`, `Balanced`, or `Fancy`.
  - Accent color.
  - Sidebar selection color.
  - Background opacity.
  - Optional background effect, defaulting to `Dim`.
  - GUI scale.
  - Animation speed.
  - Column width.
  - Legacy fallback mode while transition is active.

Acceptance criteria:

- Targets, Auto Settings, and HUD designer remain reachable.
- Async settings loading cannot mutate layout from a background thread.
- ClickGUI visual options are reflected live or on reopen, consistently.

## Phase 7A: HUD Editor Modernization

The HUD editor should use the same visual language and controls as the modern ClickGUI, but not the same module browsing layouts.

Keep from current code:

- `GuiHudDesigner` as a `GuiScreen`-level canvas/editor surface.
- `HUD.render(true)` with element borders.
- Element hit testing through `Element.isInBorder`.
- Element dragging through `HUD.handleMouseClick`, `HUD.handleMouseMove`, and `HUD.handleMouseReleased`.
- Mouse-wheel scaling of the element under the cursor.
- Delete-key removal and forced-element protection.
- `HudConfig` persistence of element type, x/y, scale, side, and values.

Replace or refactor:

- Replace `EditorPanel.drawEditor` value-control branches with the shared `ValueControl` system.
- Replace create/selection/reset UI with modern panels using shared buttons, scrollbars, confirmation dialogs, and typography.
- Replace `ClickGui.style.clickSound()` calls with shared `UiSound`.
- Replace `LiquidBounceStyle.rgbaLabels` import with shared color-control constants.
- Add missing controls for `TextValue`, `BlockValue`, `IntRangeValue`, and `FloatRangeValue`, or explicitly mark unsupported values in the inspector.

Suggested editor shell:

- Canvas fills the screen and keeps live HUD elements interactive.
- A compact floating toolbar handles create, reset, alignment/snap options, and editor mode.
- A selected-element inspector panel shows position, scale, side/origin, and `element.values`.
- A create-elements panel lists `HUD.ELEMENTS`, respecting `ElementInfo.single` and `ElementInfo.force`.
- Confirmation dialogs use the shared modal/popup primitives.

Acceptance criteria:

- The HUD editor no longer imports ClickGUI implementation or style classes.
- All currently editable HUD element properties remain editable.
- Newly shared value controls behave the same in ClickGUI and the HUD editor.
- `hudConfig` save/load output remains compatible unless an explicit migration is added.
- The editor remains a canvas tool, not a module list or category-column UI.

Implementation status:

- First pass complete: `EditorPanel` now renders selected-element values through shared `ValueControls` instead of duplicating per-value ClickGUI branches.
- First pass complete: `EditorPanel` no longer imports the legacy ClickGUI implementation, legacy style classes, RGBA label helpers, or `valuesConfig`.
- First pass complete: `GuiHudDesigner` treats the panel as an input boundary, so panel clicks do not select HUD elements underneath and panel wheel scrolling does not scale HUD elements.
- Remaining: manual HUD editor validation, screenshot comparison, viewport clamping, and final editor-shell polish.

## Phase 8: Animation, Polish, and Performance

Animation:

- Hover progress per row.
- Active progress per module.
- Expand/collapse height easing per module.
- Scroll smoothing per column.
- Optional opening fade/scale for the whole GUI.

Performance safeguards:

- Avoid allocating large layout/model objects inside tight render loops where practical.
- Cache measured text widths for stable labels.
- Clip per column to avoid overdraw.
- Clip sidebar and content viewports independently in `SidebarList`.
- Keep shader use optional, off by default, and disabled automatically when unavailable.
- Never require full-screen blur for the default visual style.
- Avoid per-frame texture generation for color pickers; regenerate only when hue/preview state changes.
- Debounce settings saves and avoid disk writes during slider drag loops.
- Keep hover/expand animations bounded to visible rows, not all modules.
- Do not run async settings refresh every frame.

Visual polish checklist:

- Text never overlaps toggles, sliders, or scrollbars.
- Long module/value names are clipped or ellipsized.
- Active but currently inactive modules have a distinct lower-alpha state.
- Scrollbars align with rounded panel bounds.
- Expanded value controls do not resize unrelated columns.
- The `SidebarList` shell keeps brand text, search, navigation, and module rows aligned without overlap.
- HUD editor inspector controls fit within the inspector panel, and the canvas remains usable behind/around panels.
- The reference palette is preserved without turning every surface purple.

Implementation status:

- First pass complete: `SidebarList` search is global across categories when a query is present and remains category-scoped when empty.
- First pass complete: sidebar search results are cached by selected category and query, and invalidated on search/category/synthetic-section transitions.
- First pass complete: Ctrl+F focuses search; Up/Down/Home/End navigate categories and synthetic utility sections without mouse input.
- First pass complete: manually positioned `ColumnDeck` columns are clamped into the current viewport during draw and drag.
- First pass complete: `Fast`, `Balanced`, and `Fancy` profile switches affect only cheap theme/background choices, with no blur or full-screen post-processing path.
- First pass complete: stable modern ClickGUI labels use a bounded text cache, and ellipsis trimming uses binary search.
- First pass complete: screenshot feedback addressed with stronger contrast, less background bleed-through, denser sidebar row rhythm, soft shadows/borders, slimmer active-state accents, and aligned switch controls.
- Remaining: bounded row/expand animations, deeper performance profiling, screenshot validation, and small-viewport sidebar navigation polish.

Acceptance criteria:

- Smooth interaction at normal Minecraft GUI scales.
- No obvious flicker or GL state leakage after closing the GUI.
- Text remains readable in all supported GUI scale settings.

## Phase 9: Rollout, Validation, and Legacy Removal

Validation matrix:

- Resolutions: 854x480, 1280x720, 1920x1080.
- Minecraft GUI scales: Auto, Small, Normal, Large where supported.
- Module categories with few and many modules.
- Modules with no values, simple values, and long nested value lists.
- Long translated names/descriptions where available.
- Bright world background and dark world background.
- `Fast`, `Balanced`, and `Fancy` performance profiles.
- Shader unavailable fallback, with no visual or functional breakage.
- Both modern style presets: `ColumnDeck` and `SidebarList`.
- HUD editor canvas plus inspector after shared-control migration.

Manual test cases:

- Open/close with Right Shift and Escape.
- Toggle modules across all categories.
- Expand/collapse modules with values.
- Change every supported value type.
- Drag columns and restart client to confirm saved layout.
- Move or resize the `SidebarList` shell if that behavior is enabled, then restart client to confirm saved layout.
- Scroll long columns and expanded settings.
- Search modules in `SidebarList`, clear the search, and confirm category selection remains correct.
- Apply an Auto Settings entry.
- Open HUD designer from the modern GUI.
- In the HUD editor, create an element, select it, drag it, scale it, change its side/origin, edit its values, delete it, and confirm `HudConfig` persists the result.
- Confirm server close-window packets do not close the ClickGUI.

Removal path:

- Keep legacy implementation until modern GUI passes feature parity.
- Once modern is default and stable, remove or quarantine:
  - Legacy `Style` subclasses.
  - Legacy `Panel`, `Element`, `ButtonElement`, and `ModuleElement` if unused.
  - Legacy style choices in `ClickGUI.kt`.
  - Legacy config write path after migration has existed for at least one release window.

Acceptance criteria:

- Modern ClickGUI is the default opening path.
- Both `ColumnDeck` and `SidebarList` are usable defaults, with one selected as the shipped default.
- No known feature regressions from the legacy ClickGUI.
- Legacy config is migrated or safely ignored with defaults.
- Obsolete legacy classes are removed only after the modern path is stable.

## Suggested Implementation Order

1. Add modern screen skeleton and open it from `ClickGUI.kt` behind a temporary mode.
2. Build the shared UI foundation and `ValueControl` system.
3. Build shared state, widget model, and static `ColumnDeck` layout/rendering.
4. Add module toggling and expansion with inline empty setting containers.
5. Implement value widgets from simplest to most complex.
6. Add `SidebarList` layout/rendering using the same module/value/widget model.
7. Add style switching, config persistence, and migration.
8. Add synthetic Targets, Auto Settings, and HUD designer entry.
9. Modernize the HUD editor inspector with the shared controls. First pass complete; visual validation and shell polish remain.
10. Add polish, bounded animations, responsive clamping, and search.
11. Run the validation matrix and remove legacy code after parity.

## Non-Goals for the First Pass

- Rewriting the module/value configuration system.
- Changing how module values are saved in `values.json`.
- Adding a search system unless it becomes necessary for usability.
- Adding a completely new font system.
- Supporting arbitrary third-party skins before the built-in `ColumnDeck` and `SidebarList` presets are stable.
