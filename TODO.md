# EasyGUI Roadmap

Living checklist. When a feature ships it gets checked (`[x]`) — never removed.

## Shipped

- [x] Rendering core (`Render2D`) — anti-aliased shapes, gradients, drop shadows, textures, scissor, global alpha
- [x] Widget set — Button, ToggleSwitch, Checkbox, Slider, TextField, Dropdown, ScrollPanel, Label, ProgressBar, Spinner, Panel
- [x] Animation system — `SmoothValue`, `Animation`, `Easing`
- [x] Screens (`EasyScreen`) — open/close transitions, focus, popup layer, tooltips
- [x] HUD overlays — `HudOverlay` + `OverlayManager`, 9-point anchoring
- [x] Theming — live-swappable dark/light palettes
- [x] Custom shader pipeline — `EasyShader`, `ShaderView`, `Panel.setShaderBackground`, `EasyScreen.setBackgroundShader`, aspect-ratio `ShaderFit`, built-in aurora + liquid
- [x] Real background blur — frosted glass panels, screen background blur, HUD frosting
- [x] Custom TTF fonts — STB-based `TrueTypeFont`/`Fonts`, bundled Inter, whole-UI font swap via `Text2D.setUiFont`
- [x] Config system — `EasyConfig`/`ConfigValue`, debounced auto-save, widget binding, UI-state persistence
- [x] HUD edit mode — drag with snap guides, right-click reset, arrow nudge, position persistence, chat-screen opt-in (`OverlayManager.setMoveInChat`)
- [x] Responsive demo screen (two-column ↔ compact scrolling layout)

## Input widgets

- [x] Range slider — two thumbs selecting a min/max pair, same theming as `Slider`
- [x] Color picker — HSV box + hue/alpha strips + hex field; popup and inline variants; binds to `defineColor`
- [x] Keybind button — click, press key to bind; conflict highlighting
- [x] Number stepper — text input with +/− buttons and drag-to-scrub
- [x] Segmented control / radio group — exclusive choice for 2–4 options
- [x] Cycle button — one-click enum cycling ("Mode: Fancy → Fast → Off")
- [x] Multiline text area
- [x] Searchable dropdown / combo box with filtering

## Layout & structure

- [ ] Layout containers — rows/columns with gap/padding/alignment, auto-positioning (kills the manual `rowY += 22` math)
- [ ] Tabs / pages — with last-open-tab persistence via config
- [ ] Collapsible sections / accordion for settings categories
- [ ] Modal dialogs — confirm/alert built on the popup layer
- [ ] Context menus (right-click)
- [x] Divider / separator widget

## Charts & statistics

Excel-grade primitives at GUI scale. Charts are regular `Widget`s — usable in screens
and, via `WidgetHostOverlay`, on the HUD. Building blocks only: no precoded HUD modules,
but a graph of blocks-per-second (or a module list, keystrokes display, armor HUD…)
must be expressible in a few lines of user code.

- [ ] Statistics layer — `Metric`/`TimeSeries` ring buffer with time/count windowing; rate counters (events/s → "blocks per second"), sampled suppliers, delta, cumulative sum; window aggregates (min/max/mean/median/sum/stdev/percentiles) and SMA/EMA smoothing; built-in metrics: FPS, ping, TPS, memory, speed, CPS
- [ ] Sparkline — tiny axis-less single-series chart; line/area/bar variants
- [ ] LineChart — multi-series, optional smoothing/area-fill/step mode; auto-scaling axes with nice 1-2-5 ticks and *animated* rescale; optional gridlines, labels, legend; per-pixel min/max downsampling so long windows stay cheap
- [ ] BarChart — vertical/horizontal, grouped/stacked
- [ ] Histogram — binned distribution (frame-time spike analysis)
- [ ] Donut / pie chart — donut doubles as a radial gauge with center text
- [ ] Radar chart (stretch — `fillPolygon` already exists)

## HUD element system

The configurability of the best HUD editors, shipped as primitives.

- [ ] `HudStyle` — uniform per-element styling: scale, opacity, padding, background (none / solid / gradient / frosted blur), corner radius, outline, shadow
- [ ] `WidgetHostOverlay` — host any widget tree on the HUD; makes charts and the whole widget set HUD-capable in one stroke
- [ ] `AnimatedListOverlay` — generic animated vertical stack: entries slide/fade in and out, reorders animate, sort modes (rendered width / alphabetical / custom), per-entry color hook. Module lists, event tickers, and potion-effect lists are all ~5-line uses of this
- [ ] `TextElement` — template strings with a placeholder registry (`{fps}`, `{ping}`, `{coords}`…) and per-character color modes: static, label/value two-tone, gradient, rainbow wave — wave phase from a global clock so every element pulses in sync
- [ ] Anchor-aware semantics — alignment flips and stacks grow toward screen center automatically based on the anchor zone
- [ ] Conditional visibility — `setVisibleWhen(BooleanSupplier)` with animated fade/slide; also replaces the current hard-cut `setVisible`
- [ ] Editor depth — element-to-element snapping and equal-spacing guides, scale handle on the bounding box, right-click per-element settings popup (`HudStyle` fields → widgets)
- [ ] Layout profiles — save/load named HUD arrangements (positions already persist via `EasyConfig`)

## Polish & premium feel

- [ ] Toast notifications — slide-in cards over the HUD (success/error/info; builds on `AnimatedListOverlay`)
- [ ] ItemStack / player model render widgets
- [ ] Tab-key focus traversal & keyboard navigation
- [ ] Drag-and-drop reordering in lists

## Media rendering

Constraint: GUI must stay lightning fast, and no heavyweight bundled libraries (no ffmpeg).
Prefer decoders Minecraft already ships (stb_image, stb_vorbis, OpenAL) or tiny pure-Java ones;
decode off-thread, upload frames as dynamic textures.

- [ ] Image widget — PNG/JPEG via `NativeImage` (stb_image, already bundled); resource, file, or URL sources with async load + rounded-corner clipping
- [ ] Animated GIF — small pure-Java decoder, frames pre-decoded off-thread into a texture atlas
- [ ] Video — no bundled H.264 decoder exists; evaluate pure-Java JCodec (~no natives, moderate speed) vs supporting MJPEG/frame-sequence formats only; strict off-thread decoding with frame-drop, never block the render thread
- [ ] Audio: WAV + OGG — effectively free via OpenAL + stb_vorbis (both ship with Minecraft)
- [ ] Audio: MP3 — needs a small pure-Java decoder (e.g. JLayer); optional, license-check first

## Capstone

- [ ] Auto-generated settings screen — `SettingsScreen.of(CONFIG)`: every `ConfigValue` becomes the right widget automatically (bool → toggle, clamped double → slider, color → picker, enum → segmented/dropdown)
