# EasyGUI — Web Parity Gap Analysis

Goal: bring EasyGUI close to feature parity with the essentials of modern web design, inside Minecraft.

This document covers **technical/architectural** gaps. For the visual side — what you literally cannot draw with the current rendering primitives — see [VISUAL_DESIGN_GAPS.md](VISUAL_DESIGN_GAPS.md).

Snapshot as of 2026-07-13, based on the current 91-class inventory across `client/widget`, `client/chart`, `client/media`, `client/font`, `client/render`, `client/theme`, `client/animation`, `client/overlay`, and `client/stat`.

## What is already covered

The widget catalog is competitive with a web component library and is **not** where the gaps are:

- **Inputs & controls:** Button, Checkbox, ToggleSwitch, Slider, RangeSlider, NumberStepper, TextField, TextArea (word wrap, selection, clipboard), Dropdown, SearchableDropdown, CycleButton, SegmentedControl, KeybindButton, ColorPicker
- **Surfaces & overlays:** Panel, ScrollPanel, Tabs, CollapsibleSection, ModalDialog, ContextMenu, Toasts, popup layer with input capture
- **Data & media:** full chart package (bar, line, donut, histogram, radar, sparkline, time series), images, GIF, MJPEG/frame-sequence video, WAV/OGG/MP3 audio, ItemView, PlayerView
- **Rendering:** feathered-triangle core (no shaders required), rounded rects, vertical/horizontal gradients, drop shadows, blur, ripples clipped to rounded silhouettes
- **Theming:** semantic dark/light tokens (surface, accent, text, outline, danger, success), radii, shadows, live re-skinning via `Theme.setDefault`
- **Motion & input:** Animation/Easing/SmoothValue, built-in smoothed hover per widget, Tab focus traversal with focus rings, tooltips
- **HUD:** overlay system with edit screen, layouts, styles, stats layer

## The gaps, ranked by impact

### 1. Layout: no grid, no wrapping, no responsiveness — *the biggest gap*

Modern web layout rests on three legs — flexbox, grid, and responsive units. EasyGUI has roughly one:

- `LinearLayout` is a single-axis flexbox (gap, cross-axis alignment, stretch, weighted spacers) but **cannot wrap**.
- There is **no grid container** at all.
- Everything else is absolute coordinates via `setBounds`.
- No relative units (percent/fraction of parent), no min/max size constraints, no breakpoint concept.

This matters more in Minecraft than on the web: GUI scale changes and window resizes swing the available space wildly.

**Closes the gap:** a `GridLayout` (column templates, spans), a wrap mode for `LinearLayout`, and percent/min/max sizing on `Widget`.

### 2. Rich text

The web is fundamentally styled hypertext; EasyGUI text is single-style runs. `TrueTypeFont` gives custom fonts, but there is no way to render a paragraph with bold/italic/colored spans, inline icons, or clickable links — and no markdown-ish convenience layer.

**Closes the gap:** a `RichLabel` that takes styled spans and wraps them. Unlocks changelogs, help screens, chat-like UIs — a category that currently can't be built.

### 3. Data display: no table, no tree, no virtualization

- No sortable/column-based data table.
- No tree view.
- `ScrollPanel` renders all children with no culling — a 5,000-row list pays full cost every frame.

**Closes the gap:** virtualized scrolling (only render the visible window) plus a `Table` widget with sortable columns.

### 4. Forms as a system, not just inputs

The input widgets exist, but not the glue the web takes for granted:

- No validation framework: inline error states, an `error` token in `Theme`, a message slot under inputs.
- No required-field semantics.
- Missing standard pickers: **date/time** and **file**.
- No **undo/redo** in `TextField`/`TextArea` (confirmed absent), which users expect from any text box.

### 5. Cursor feedback

No `glfwCreateStandardCursor` usage anywhere — the pointer never becomes a hand over buttons, an I-beam over text fields, or a resize arrow. Small amount of code, disproportionately responsible for whether a UI "feels like the web."

### 6. Accessibility and i18n

- No hookup to Minecraft's Narrator (the equivalent of ARIA/screen readers).
- All text is raw `String` — no `Component.translatable` support, so consuming mods can't use language files.
- No reduced-motion or high-contrast option.

For a library, **i18n is the more pressing half** — downstream mods will want language files to work.

### 7. Motion choreography

`Animation`/`Easing`/`SmoothValue` cover the imperative tier, but the declarative layer is missing:

- Enter/exit transitions when widgets appear/disappear.
- Animated layout changes (items sliding when a sibling is removed).
- Screen-to-screen transitions.

This is the CSS-transitions / FLIP-animation tier.

## Suggested sequencing

1. **Layout** (grid + wrap + relative sizing) — everything else sits on it.
2. **Rich text** — changes what's buildable.
3. **Virtualization + table** — changes what's buildable at scale.
4. Cursors and form validation — cheap wins to slot in between.
5. Narration and i18n — the "do it before 1.0" items.
