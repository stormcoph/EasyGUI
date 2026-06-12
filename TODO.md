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

- [x] Layout containers — rows/columns with gap/padding/alignment, auto-positioning (kills the manual `rowY += 22` math)
- [x] Tabs / pages — with last-open-tab persistence via config
- [x] Collapsible sections / accordion for settings categories
- [x] Modal dialogs — confirm/alert built on the popup layer
- [x] Context menus (right-click)
- [x] Divider / separator widget

## Charts & statistics

Excel-grade primitives at GUI scale. Charts are regular `Widget`s — usable in screens
and, via `WidgetHostOverlay`, on the HUD. Building blocks only: no precoded HUD modules,
but a graph of blocks-per-second (or a module list, keystrokes display, armor HUD…)
must be expressible in a few lines of user code.

- [x] Statistics layer — `Metric`/`TimeSeries` ring buffer with time/count windowing; rate counters (events/s → "blocks per second"), sampled suppliers, delta, cumulative sum; window aggregates (min/max/mean/median/sum/stdev/percentiles) and SMA/EMA smoothing; built-in metrics: FPS, ping, TPS, memory, speed, CPS
- [x] Sparkline — tiny axis-less single-series chart; line/area/bar variants
- [x] LineChart — multi-series, optional smoothing/area-fill/step mode; auto-scaling axes with nice 1-2-5 ticks and *animated* rescale; optional gridlines, labels, legend; per-pixel min/max downsampling so long windows stay cheap
- [x] BarChart — vertical/horizontal, grouped/stacked
- [x] Histogram — binned distribution (frame-time spike analysis)
- [x] Donut / pie chart — donut doubles as a radial gauge with center text
- [x] Radar chart (stretch — `fillPolygon` already exists)

## HUD element system

The configurability of the best HUD editors, shipped as primitives.

- [x] `HudStyle` — uniform per-element styling: scale, opacity, padding, background (none / solid / frosted blur), corner radius, outline, shadow
- [x] `WidgetHostOverlay` — host any widget tree on the HUD; makes charts and the whole widget set HUD-capable in one stroke
- [x] `AnimatedListOverlay` — generic animated vertical stack: entries slide/fade in and out, reorders animate, sort modes (rendered width / alphabetical / custom), per-entry color hook. Module lists, event tickers, and potion-effect lists are all ~5-line uses of this
- [x] `TextElement` — template strings with a placeholder registry (`{fps}`, `{ping}`, `{coords}`…) and per-character color modes: static, label/value two-tone, gradient, rainbow wave — wave phase from a global clock so every element pulses in sync
- [x] Anchor-aware semantics — alignment flips and stacks grow toward screen center automatically based on the anchor zone
- [x] Conditional visibility — `setVisibleWhen(BooleanSupplier)` with animated fade/slide; also replaces the current hard-cut `setVisible`
- [x] Editor depth — element-to-element snapping guides, scale handle on the bounding box, right-click per-element settings popup (`HudStyle` fields → widgets)
- [x] Layout profiles — save/load named HUD arrangements (positions already persist via `EasyConfig`)

## Polish & premium feel

- [x] Toast notifications — slide-in cards over the HUD (success/error/info/warning; standalone stack — `AnimatedListOverlay` rows proved too text-shaped for full cards)
- [x] ItemStack / player model render widgets
- [x] Tab-key focus traversal & keyboard navigation
- [x] Drag-and-drop reordering in lists

## Media rendering

Constraint: GUI must stay lightning fast, and no heavyweight bundled libraries (no ffmpeg).
Prefer decoders Minecraft already ships (stb_image, stb_vorbis, OpenAL) or tiny pure-Java ones;
decode off-thread, upload frames as dynamic textures.

- [x] Image widget — PNG/JPEG via `NativeImage` (stb_image, already bundled); resource, file, or URL sources with async load + rounded-corner clipping
- [x] Animated GIF — small pure-Java decoder, frames pre-decoded off-thread into per-frame dynamic textures
- [x] Video — JCodec evaluated and rejected (too slow for a 60fps GUI promise, ~2MB dep); shipped MJPEG-AVI + raw MJPEG + frame sequences with strict off-thread decoding and two-sided frame-drop (verdict in `VideoView` javadoc; pre-convert H.264 with `ffmpeg -c:v mjpeg`)
- [x] Audio: WAV + OGG — effectively free via OpenAL + stb_vorbis (both ship with Minecraft)
- [x] Audio: MP3 — license check passed (LGPL-2.1, see `docs/MP3-LICENSE-NOTES.md`); JLayer 1.0.1 jar-in-jar nested on both loaders with a soft-dependency bridge

## Capstone

- [ ] Auto-generated settings screen — `SettingsScreen.of(CONFIG)`: every `ConfigValue` becomes the right widget automatically (bool → toggle, clamped double → slider, color → picker, enum → segmented/dropdown)
