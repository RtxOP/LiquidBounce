# Theme System — Open Issues

Resolved intent for the still-open issues in the LiquidBounce Modern ClickGUI +
HUD theme implementation. Coordinate and rationale captured here so a future pass
can apply them without re-asking.

The currently-tracked items (per `TaskList`) are 22 through 30. Issues 1, 4, 5, 6,
7, 8, 9, 10 are listed below alongside, mapped to their original IDs.

---

## Issue 22 / Issue 8 — Opaque `accentMuted` on all built-ins  **[DONE]**

**Decision**: All built-in themes (`Pastel`, `Modern`, `Azure`, `Noir`, `Forest`,
`Sunset`) store `accentMuted` at alpha `255`. Same for `accent`. `Modern` is the
all-zero-alpha exception because it's the shared default.

**Why**: An alpha < 255 on the muted accent of `Pastel` / `Azure` / `Sunset` made
gradient-driven chrome (sliders, switches, active module rectangles) show through
the panel background instead of resolving to the muted color. The card preview
graph also misread.

**Applied in**: `ui/client/clickgui/modern/theme/BuiltInThemes.kt` (each entry).

---

## Issue 23 / Issue 6 — Darken the Pastel palette  **[DONE]**

**Decision**: Pastel uses stronger, more saturated lavenders so the panel reads
as Pastel rather than as a near-white wash.

| Field | Old | New |
| --- | --- | --- |
| `backgroundOverlay` | `Color(0, 0, 0, 64)` | `Color(28, 18, 48, 96)` |
| `panelBackground`   | `(245, 240, 250, 255)` | `(232, 222, 244, 255)` |
| `panelHeader`       | `(228, 220, 236, 255)` | `(186, 162, 222, 255)` |
| `rowBackground`     | `(250, 246, 254, 255)` | `(244, 234, 252, 255)` |
| `settingsBackground`| `(238, 232, 244, 255)` | `(214, 198, 232, 255)` |
| `rowHover`          | `(235, 225, 242, 255)` | `(206, 184, 232, 255)` |
| `accent`            | `(181, 140, 235, 255)` | `(154,  96, 222, 255)` |
| `accentMuted`       | `(210, 190, 225, 255)` | `(112,  70, 178, 255)` |
| `textPrimary`       | `(62, 52, 76, 255)`    | `(48, 36, 68, 255)`    |
| `textMuted`         | `(124, 108, 138, 255)` | `(96, 80, 124, 255)`  |
| `border`            | `(210, 198, 220, 140)` | `(168, 144, 196, 160)` |

**Why**: Underlying cream was overpowering lavender accent; the eye didn't read
the theme as lavender. Darkening the panel stack + deepening accent gives it
identity while keeping the cream/lavender feel.

**Applied in**: `ui/client/clickgui/modern/theme/BuiltInThemes.kt`.

---

## Issue 27 / Issue 4 — Kill the card border & hover overlay; remove enabled-module ring in SidebarList mode

**Decision**:
- Theme cards: **no** outer rounded-rect outline (border) and **no** hover overlay
  rect. The body of `drawThemeCard` should render only: the multi-stop
  diagonal shader, the bottom matte label, the tick badge when active.
- Module cards in SidebarList mode: kill the colored ring that draws around
  enabled modules.

**Active state**: Tick badge in the top-right of the gradient body remains the
only indicator of activation.

**Hover animation**: a slight −2px Y-shift, no shadow, ~100ms ease-out. The
card simply lifts ~2px on hover.

**Why**: The static colored strip around every card made the entire grid look
busy and obscured the per-theme gradient. The hover overlay stamped a heavy
"saturated" rectangle on top of whatever card was being hovered, breaking the
look. Both are removed. Hover gets a tiny mechanical rise to confirm
responsiveness without color noise.

**To apply**:
- `ModernClickGuiScreen.kt` → `drawThemeCard` — drop `border.withAlpha(...)` rect
  and the `hoverAlpha > 0.05F` rect. Replace with: Y-shift via `hoverProgress`
  multiplied into the card translation.
- `ModernClickGuiScreen.kt` → module row in SidebarList — remove any ring / outline
  drawn when `module.isActive`.

---

## Issue 26 / Issue 5 — Theme "tick appears but chrome doesn't update"  *(covers former Issue 25)*

**Decision**: Same fix as Issue 25 — gate the `theme` getter on
`themeEditorOpen` and null the draft on close. The tick check that already
worked (`ThemeResolver.activeId == id` directly) keeps working; the
chrome-shading bug goes away because the getter finally returns
`ThemeResolver.current` whenever the editor is closed.

**Why**: The user-reported "check does appear on the selected theme, it just
doesn't apply it" maps exactly to the draft-shadow scenario. The tick badge
re-renders from the resolver — proven correct — but the chrome (panels,
sliders, switches, text colors) all read through the `theme` getter. With
`themeEditorDraft` set, the getter permanently returns the draft, so the chrome
does not change until the editor is re-opened (which re-seeds the draft from
`ThemeResolver.current`).

### Investigation notes (with documentation references)

`ThemeResolver.setActive(...)` does correctly mutate the resolver:

```kotlin
fun setActive(id: String, customs: List<CustomTheme>) {
    applyCustoms(customs)
    activeId = id
    current = resolve(id)
}
```

`current` is declared as:

```kotlin
var current: UiTheme = BuiltInThemes.byId(BuiltInThemes.DEFAULT_ID)!!.theme
    @JvmName("getCurrent")
    get
```

`var current` with only a no-body `get` accessor is legitimate Kotlin. The
initializer (`= ...!!`) forces Kotlin to generate a backing field
([Kotlin properties docs](https://kotlinlang.org/docs/properties.html),
[ZetCode Kotlin get keyword tutorial](https://zetcode.com/kotlin/get-keyword/) —
see "Getter with Backing Field"). The default setter writes the backing field,
so `current = resolve(id)` runs cleanly. **The resolver is not buggy — the
screen-side getter is bugged.**

`toUiTheme()` on the draft synthesizes a `UiTheme` from whatever the draft last
held (defaults from the first time the user opened the editor). That
synthesized theme is then handed back to every chrome-drawing call from
`theme.X` — always.

The drawer-side click handler, meanwhile, *does* `ThemeResolver.setActive(...)`
correctly:

```kotlin
for (target in themeHitTargets.asReversed()) {
    if (!target.rect.contains(inputMouseX, inputMouseY)) continue
    if (ThemeResolver.activeId != target.id) {
        ThemeResolver.setActive(target.id, customThemes)
        saveConfig(clickGuiConfig)
        UiSound.click()
    }
    return
}
```

That mutates `activeId` and `current` (with backing field) correctly. Side
effects: ① `drawThemeCard`'s tick badge — read directly from
`ThemeResolver.activeId == id` — flips over to the newly clicked card; ② every
other chrome drawable reads `theme.X` and is shadowed because
`themeEditorDraft != null` after the editor opens.

So **Issues 5 and 25 collapse to the same fix**. The "tick appears, chrome
doesn't apply" symptom is the visible mirror of the draft-shadow root cause.

### Bonus: also clear `themeHitTargets` per frame

While here — `themeHitTargets` is the only hit-target list that is **never**
`.clear()`-ed before the screen rebuilds it. As long as the themes grid does
not scroll, this is harmless; if scroll is ever added, stale rect targets
will swallow clicks. Add a defensive `themeHitTargets.clear()` next to the rest
of the per-frame clear block. Cheap insurance.

**To apply**: see Issue 25 fix block above; plus the optional defensive
`themeHitTargets.clear()` in the per-frame reset.

---

---

## Issue 24 / Issue 1 — Thematic sidebar background

**Decision**: Sidebar body uses `accentMuted` filled with `panelBackground` at
low alpha so the sidebar reads as the active theme's accent over its
panel-toned body.

**Why**: Right now the sidebar shell uses pure `panelBackground`, which makes the
sidebar feel detached from the active palette. Tinting toward `accentMuted`
makes the theme's identity carry into the chrome without overwhelming the rows.

**To apply**:
- `ModernClickGuiScreen.kt` → `drawSidebarList` shell draw — replace flat
  `panelBackground` rect with a layered fill
  (`accentMuted.withAlpha(N)` over `panelBackground`) where `N` is small,
  theme-appropriate (~22 for the Pastel/Sunset palette, ~34 for the darker ones).

---

## Issue 28 / Issue 9 — Multi-stop diagonal shader for the theme preview cards

**Decision**: Add a new renderer `drawRoundedMultiStopGradientRect` that
supports N color stops on a diagonal direction with rounded corners. Used by
`drawThemeCard` to render the card body as a single rounded rect with 4 stops:

```
top-left    = accent
top-right   = rowHover
bottom-right = accentMuted
bottom-left = panelBackground
```

**Why**: Currently `drawThemeCard` stacks three `drawGradientRect` calls, each
with sharp top edges, breaking the rounded-corner illusion on the top of the
card. A proper shader respects the corner radius.

**To apply**:
- `utils/render/` — new helper `drawRoundedMultiStopGradientRect(rect: UiRect,
  radius: Float, corners: RoundedCorners, stops: List<Pair<Float, Color>>,
  direction: Vector2f)` (or equivalent). It renders a non-axis-aligned gradient
  by computing per-vertex colors with bilinear interpolation.
- `ModernClickGuiScreen.kt` → `drawThemeCard` — replace the three
  `drawGradientRect` calls with one `drawRoundedMultiStopGradientRect` covering
  `rect.x/y` through `matteTop.y` with the 4 stops above.

---

## Issue 29 / Issue 10 — Small accent-tinted `+ New Theme` pill

**Decision**: Row body uses `theme.accent.withAlpha(36)` background, text color
= `theme.accent` (full alpha), no border, smaller in height than rows above
(e. g. `SIDEBAR_SYNTHETIC_ROW_HEIGHT * 0.8`).

**Why**: Currently the `+ New Theme` row uses `drawSidebarStatusRow`, which is
indistinguishable from any other status row. Tinting with accent creates a
clear "primary action" affordance without being a heavy button.

**To apply**:
- `ModernClickGuiScreen.kt` → `drawSidebarThemesContent` — replace
  `drawSidebarStatusRow("+ New Theme", …)` with a dedicated
  `drawSidebarActionPill("+ New Theme", accent = theme.accent, rect)` helper.

---

## Issue 30 — Editor live-edit wiring + Name row

**Decision**: Add a Name row at the top of the editor body (above Accent). The
row uses a `TextValue` for live editing. Validation mirrors the existing
`EditableText.validator` (no whitespace, no leading slashes, length ≤ some
above-water limit). On Enter / focus loss, `themeEditorDraft.name` updates.

**Why**: Without a Name row, the user can never give a custom theme a useful
identifier; the apply path uses the default `"Custom"` name and the
reserved-name collision flow renames it to `My (copy)`. A Name row restores
the planned UX.

**Investigation note**: the existing `processSearchInput` function in
`keyTyped` (line 1814) claims the typed character *before* the new Name field
would. The Name row needs a parallel input path that runs **only** while
`themeEditorOpen && namedFieldFocused`. Reusing `EditableText` is fine; the
separation is just a branch on the editor's focus state.

**To apply**:
- `ModernClickGuiScreen.kt`:
  - Add `themeEditorNameValue: TextValue` field on the screen.
  - In `drawThemeEditorBody`, render a 5th row above Accent with the `TextValue`
    and an `editingNameKey = "theme-editor:name"` for key handling.
  - In `valueHitTargets`, register the rect.
  - Wire `setShowName(...)` etc. so the editor's field reflects
    `themeEditorDraft.name` on open; `onChanged = { themeEditorDraft = themeEditorDraft.copy(name = sanitize(name)) }`.
  - In `keyTyped`, branch *before* `processSearchInput`: if
    `themeEditorOpen && themeEditorNameFocused`, route typed characters into the
    Name field editor; if it returns true, `return`. Otherwise continue the
    normal pipeline.

---

## Issue 14 — Polish: ESC closes editor first, chat-toast warnings, skip keyboard nav

**Decision**:
- **ESC**: When `themeEditorOpen`, the first ESC closes the editor; the second
  closes the ClickGUI as before. Currently ESC always closes the GUI.
- **Warnings**: When a saved `activeThemeId` references a deleted custom name,
  or a custom name collides with a built-in id, emit BOTH a chat warning (via
  `ClientUtils.chat(...)`) AND a HUD notification (via
  `HUD.addNotification(Notification.warn(...))`).
- **Keyboard nav**: skip. Mouse-only is sufficient for the themes grid.

**Investigation note on ESC**: the `keyTyped` switch at line 1835 currently
treats ESC the same as `ClickGUI.keyBind` (close GUI). Editor-first means:
```kotlin
if (themeEditorOpen) {
    closeThemeEditor()
    return
}
if (keyCode in arrayOf(Keyboard.KEY_ESCAPE, ClickGUI.keyBind)) { ... }
```
— Placement is critical: before the GUI-close arm, after other early returns
(`processSearchInput`, `ValueControls.keyTyped`, search/nav).

**Investigation note on warnings**: `HUD.addNotification` already exists in the
import set (e. g. line 38 `import net.ccbluex.liquidbounce.ui.client.hud.HUD`)
and is used elsewhere — see line 2554 (`HUD.addNotification(Notification.informative(...))`).
The `Notification.warn(...)` factory is presumed available; verify by
checking `Notification.kt`'s primary constructor's companion object. If not,
fall back to `Notification.informative("Theme", "Reserved name: ...")` and
suffix a color hint via the message.

**Why**: Editor users would otherwise lose their unsaved drafts on ESC.
HUD-toast duplication raises visibility — chat scrolls away; HUD toasts stay.

**To apply**:
- `ModernClickGuiScreen.kt` → `keyTyped` — branch on `themeEditorOpen` before
  the global ESC handler.
- `ModernClickGuiScreen.kt` → `loadModernConfig` chat warning — add
  `HUD.addNotification(Notification.warn("Theme", "Saved custom theme not found"))`.
- `ModernClickGuiScreen.kt` → `applyCustomTheme` reserved-name branch — add
  `HUD.addNotification(Notification.warn("Theme", "Reserved name, renamed"))`.

---

## File-touch summary

Single file changed for almost every fix:

- `src/main/java/net/ccbluex/liquidbounce/ui/client/clickgui/modern/ModernClickGuiScreen.kt`
  — Issues 4, 5, 25, 1, 9 (card shader call site), 10, 30, 14, ESC, warnings.
- `src/main/java/net/ccbluex/liquidbounce/ui/client/clickgui/modern/theme/BuiltInThemes.kt`
  — already has Issues 8 / 6 applied.
- `src/main/java/net/ccbluex/liquidbounce/utils/render/` (new helper)
  — Issue 9 shader.

---

## Status table

| Issue | Title                                  | State     |
|-------|----------------------------------------|-----------|
| 22/8  | Opaque `accentMuted`                   | Done      |
| 23/6  | Darken Pastel palette                  | Done      |
| 25 + 26/5 | Draft-shadow collapse (1 fix, 2 reports) | Done   |
| 27/4  | Kill card border + hover + module ring | Done      |
| 24/1  | Thematic sidebar background            | Done      |
| 28/9  | Multi-stop diagonal card shader        | Done      |
| 29/10 | Accent-tinted `+ New Theme` pill       | Done      |
| 30    | Editor name row                        | Done      |
| 14    | Polish (ESC, warnings, no kbd nav)     | Done      |

---

## Development order (incremental, each step is read-only or one-file edit)

The issues are enumerated above in the order they appear in `Issues.md`. The
development order is chosen so that the lowest-risk fixes ship first, each
build stage is verifiable by reading the surrounding code, and the heavier
work (`Issue 9`'s new shader, `Issue 30`'s Name row) lands last.

1. **25 + 26/5 — draft-shadow collapse**
   - One-line change in `theme` getter; null draft on close in
     `closeThemeEditor()`. Optional `themeHitTargets.clear()` next to the rest
     of the per-frame reset block.
   - Verifies by re-reading that the click handler's
     `ThemeResolver.setActive(...)` is the only path that needs to fire; the
     existing tick check confirms the resolver side works; the getter fix
     unblocks the chrome.

2. **27/4 — kill card border + hover overlay + module ring**
   - `drawThemeCard` — drop the two `drawRoundedRect`s (border + hover) and
     replace with: a slight Y-shift on hover, gated on `hoverProgress` and the
     existing 100ms ease-out existing animation speed (≈activeConstant
     `HOVER_ANIMATION_SPEED = 22F` or similar — match whichever
     ease-out-constant the file already uses).
   - SidebarList module row — kill any `drawRoundedRect(..., module.isActive...)`
     outline drawn only for active modules.

3. **14 — ESC closes editor first + HUD-toast warnings**
   - `keyTyped` early-arm `if (themeEditorOpen) { closeThemeEditor(); return }`.
   - `loadModernConfig`'s missing-custom-theme chat line: append a
     `HUD.addNotification(Notification.warn("Theme", "Saved custom theme not found"))`
     after the `chat(...)` call. (Use `Notification.informative` if `warn`
     isn't on the file.)
   - `applyCustomTheme`'s reserved-name rename branch: append a HUD toast
     after the chat line.

4. **29/10 — Accent-tinted `+ New Theme` pill**
   - New helper `drawSidebarActionPill(label, accent, rect, mouseX, mouseY)`
     that paints `accent.withAlpha(36)` body, `accent.rgb` text, no border,
     reduced row height (`SIDEBAR_SYNTHETIC_ROW_HEIGHT * 0.8F`).
   - Replace `drawSidebarStatusRow("+ New Theme", …)` with the new helper.

5. **24/1 — Thematic sidebar background**
   - `drawSidebarList` shell draw — replace the current `panelBackground`-only
     fill with a layered fill: first the existing `panelBackground`, then on
     top a `drawRoundedRect` of `accentMuted.withAlpha(22..34)` depending on
     how dark the built theme is. Use a small alpha constant keyed off
     `theme.panelBackground` brightness or a fixed 28 and tune later.

6. **28/9 — Multi-stop diagonal shader for cards**
   - Add `drawRoundedMultiStopGradientRect(rect, radius, corners, stops,
     direction)` to `utils/render/RenderUtils.kt`, modeled on the existing
     `RoundedGradientRectShader` (line 1149 in `RenderUtils.kt`) but with N
     stops and an arbitrary direction. Compute per-vertex colors with
     bilinear interpolation.
   - `drawThemeCard`: replace the three `drawGradientRect` calls with one
     `drawRoundedMultiStopGradientRect` covering the upper 80% of the card
     with stops `(0, accent)`, `(0, rowHover)` at the right edge, `(1,
     accentMuted)` at the bottom-right, `(1, panelBackground)` at the
     bottom-left, direction = down-right diagonal.

7. **30 — Editor Name row**
   - Add `themeEditorNameValue: TextValue` and `themeEditorNameFocused: Boolean`
     fields.
   - In `drawThemeEditorBody`, expand the layout from 4 rows to 5, insert a
     Name row at the top using `drawThemeEditorLabeledRow` style but with
     an `EditableText` rendering.
   - Wire click on the row → `themeEditorNameFocused = true`, register
     `valueHitTargets`, sanitize name with `EditableText.validator` rules.
   - In `keyTyped`, branch at the top: if `themeEditorOpen &&
     themeEditorNameFocused` and `EditableText.handleKey(...)` consumes the
     input, `return` early. Otherwise continue normal pipeline.

8. **(Step 13 from plan) — EditorPanel.kt**
   - Replace `private val theme = UiTheme.MODERN` with
     `private val theme: UiTheme get() = ThemeResolver.current`.

9. **(Steps 11, 12 from plan) — HUD Theme mode propagation**
   - Arraylist: append `"Theme"` to text/rect/background mode choices; add
     `*Slot` choices gated on `*ColorMode == "Theme"`. Wire the consumer
     branches at the gradient resolution sites (drawing already happens at
     `drawElement`).
   - Keystrokes, Notifications, Scoreboard, TabGUI: same pattern. Existing
     non-Theme modes stay unchanged.
   - Gradient integration in arraylist cycling: per-row `blendLinear(textGradColors[0],
     textGradColors[1], perModuleIndex / count)` already exists; when
     `ThemeMode` is selected it should pull from `theme.accent` and
     `theme.accentMuted` and skip the user's stops.

Each step ends with a re-read of the changed function bodies and the new
interactions on adjacent code. No `gradle` invocations are required.
