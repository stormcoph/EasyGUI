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

- [ ] Range slider — two thumbs selecting a min/max pair, same theming as `Slider`
- [ ] Color picker — HSV box + hue/alpha strips + hex field; popup and inline variants; binds to `defineColor`
- [ ] Keybind button — click, press key to bind; conflict highlighting
- [ ] Number stepper — text input with +/− buttons and drag-to-scrub
- [ ] Segmented control / radio group — exclusive choice for 2–4 options
- [ ] Cycle button — one-click enum cycling ("Mode: Fancy → Fast → Off")
- [ ] Multiline text area
- [ ] Searchable dropdown / combo box with filtering

## Layout & structure

- [ ] Layout containers — rows/columns with gap/padding/alignment, auto-positioning (kills the manual `rowY += 22` math)
- [ ] Tabs / pages — with last-open-tab persistence via config
- [ ] Collapsible sections / accordion for settings categories
- [ ] Modal dialogs — confirm/alert built on the popup layer
- [ ] Context menus (right-click)
- [ ] Divider / separator widget

## Polish & premium feel

- [ ] Toast notifications — slide-in cards over the HUD (success/error/info)
- [ ] Graph / sparkline widget — live FPS/ping graphs for overlays
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
