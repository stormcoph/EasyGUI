# EasyGUI

A cross-platform (**Fabric + NeoForge** via [Architectury](https://docs.architectury.dev/)) library mod for building clean, animated, premium-feeling GUIs in Minecraft **1.21.1**.

Shapes render with plain `position_color` / `position_tex_color` shaders and tessellated triangle geometry with sub-pixel edge feathering, so visuals are identical on both loaders. On top of that sits an optional custom-shader pipeline (loaded through vanilla's core-shader format — still zero loader-specific code) powering real gaussian background blur, frosted-glass panels, and shader-driven widget surfaces.

## Features

- **Rendering core (`Render2D`)** — anti-aliased rounded rectangles (per-corner radii), circles, arcs/rings, lines with round caps, triangles, vertical/horizontal gradients, layered soft drop shadows, textured quads and **rounded-corner-clipped textures** (avatars!), nested scissor regions, global alpha fading for whole-tree transitions.
- **Custom shaders (`EasyShader`)** — load your own core shaders from `assets/<modid>/shaders/core/` with no loader-specific registration; standard uniforms (matrices, `ScreenSize`, samplers, an auto-fed `Time`) just work, sources hot-reload on F3+T, and compile failures log + degrade instead of crashing. Draw with `Render2D.shadedRect`/`shadedRoundedRect` (anti-aliased corners included), drop a `ShaderView` widget into any panel, make it a card background (`Panel.setShaderBackground`), or fill a whole screen (`EasyScreen.setBackgroundShader`). Built-ins: `Shaders.AURORA` (drifting gradient) and `Shaders.LIQUID` (domain-warped churning fluid with a tunable palette — water, lava, honey, slime…).
- **Real background blur (`Blur`)** — shader-based separable gaussian blur of whatever is behind an element (world, HUD, other GUI layers). `Render2D.fillRoundedRectBlurred` gives feathered frosted-glass shapes that respect pose transforms; `Panel.setFrosted(true)` makes any card glass; `EasyScreen` blurs its whole background with the open/close animation (radius ramps smoothly). Falls back to vanilla blur / solid fills if shaders are unavailable.
- **Custom fonts (`Fonts` / `TrueTypeFont`)** — load any TTF from mod assets, disk, or bytes (STB TrueType, ships with Minecraft). Free-form sizes at draw time, glyphs baked per GUI scale with oversampling for crisp small text, kerning, shadows, ellipsis trimming. `Text2D.setUiFont(font, 9f)` re-renders **every widget** with your font instantly. **Inter** is bundled (`Fonts.inter()`, SIL OFL 1.1).
- **Icons** — `Icons.*` ships ~16 procedural vector icons (close, check, chevrons, search, gear, info, warning, menu, copy, user, folder, arrow…) that stay crisp at any scale and tint freely. `TextureIcon` wraps your own textures/atlas regions.
- **Animation system** — `SmoothValue` (frame-rate-independent exponential smoothing; powers hovers, scrolling, toggles), `Animation` (duration + easing one-shots), `Easing` (17 curves: cubic, expo, back, elastic, bounce…).
- **Widgets** — `Button` (variants, icons, ripple, press-scale), `ToggleSwitch`, `Checkbox` (draw-on check), `Slider`, `RangeSlider` (two-thumb min/max), `NumberStepper` (+/− buttons, drag-to-scrub, type-to-edit), `SegmentedControl` (sliding accent pill), `CycleButton` (enum cycling, `CycleButton.ofEnum`), `TextField` (selection, clipboard, word-jump, placeholder), `Dropdown` (animated popup layer), `ScrollPanel` (smooth scrolling, fading scrollbar), `Label`, `Divider`, `ProgressBar` (+ indeterminate), `Spinner`, `ShaderView`, `Panel` cards with shadows or frosted glass.
- **Screens (`EasyScreen`)** — open/close fade + scale animations, background dim + animated real blur (panorama backdrop outside worlds), focus management, popup routing, built-in tooltips.
- **HUD overlays** — `HudOverlay` + `OverlayManager` with 9-point `Anchor` positioning, rendered during the vanilla HUD pass on both loaders (frosted glass works here too — see `DemoOverlay`).
- **HUD edit mode (`HudEditScreen`)** — drag overlays anywhere with design-tool **snap guides** (screen edges, center lines, and other overlays' edges/centers), right-click to reset, arrow keys to nudge (Shift = 5px). Overlays re-anchor to the nearest screen third when dropped, so layouts hold across resolutions; positions persist automatically for overlays with a `persistId`. Open the editor from a button, or `OverlayManager.setMoveInChat(true)` to drag overlays whenever the chat screen is open.
- **Theming** — live-swappable `Theme` palettes (`Theme.dark()` / `Theme.light()`), all widgets re-skin instantly.
- **Config system (`EasyConfig`)** — typed values with defaults (bool, int, double, string, color, enum; optional clamping) persisted as human-editable nested JSON in `config/`. Debounced auto-save + save-on-quit, defaults written on first run, corrupt files backed up instead of crashing, unknown keys preserved. `ConfigValue` implements `Supplier`/`Consumer` so widgets bind directly — settings, preferences, and UI state (last tab, scroll position, overlay placement) all persist with one line each.

## Try the demo

Run the dev client and press **F8** (rebindable under Options → Controls → EasyGUI):

```
./gradlew :fabric:runClient      # or :neoforge:runClient
```

The demo screen (`com.stormcph.easygui.client.demo.DemoScreen`) exercises every widget and doubles as example code. It can also toggle a sample HUD overlay (watermark + FPS card).

## Building

```
./gradlew build
```

Outputs: `fabric/build/libs/easygui-fabric-<version>.jar` and `neoforge/build/libs/easygui-neoforge-<version>.jar`.

## Using the library

### A screen

```java
public class MyScreen extends EasyScreen {
    public MyScreen() {
        super(Component.literal("My Screen"));
    }

    @Override
    protected void build(Panel root) {
        Panel card = root.add(new Panel().setCard(true));
        card.setBounds(width / 2f - 110, height / 2f - 70, 220, 140);

        card.add(new Label("Settings").setScale(1.2f))
            .setBounds(card.getX() + 14, card.getY() + 12, 120, 12);

        card.add(new ToggleSwitch("Enable thing", true, value -> {/* ... */}))
            .setBounds(card.getX() + 14, card.getY() + 34, 180, 16);

        card.add(new Button("Done", this::closeWithAnimation))
            .setBounds(card.getX() + 14, card.getY() + 104, 90, 22);
    }
}

// open it:
Minecraft.getInstance().setScreen(new MyScreen());
```

Widgets use absolute GUI coordinates. `build` re-runs on every resize, so deriving positions from `width`/`height` keeps layouts responsive.

### A HUD overlay

```java
public class ManaOverlay extends HudOverlay {
    public ManaOverlay() {
        setAnchor(Anchor.BOTTOM_RIGHT);
        setOffsets(8, 8);
    }

    @Override public float getWidth()  { return 90; }
    @Override public float getHeight() { return 22; }

    @Override
    public void render(GuiGraphics graphics, float x, float y, float partialTick) {
        Render2D.fillRoundedRect(graphics, x, y, getWidth(), getHeight(), 6, 0xC0151520);
        Text2D.drawVerticallyCentered(graphics, "Mana: 100", x + 8, y, getHeight(), 0xFFECECF1);
    }
}

// during client init:
OverlayManager.register(new ManaOverlay());
```

### HUD edit mode

```java
// Opt an overlay into position persistence (set before registering):
public ManaOverlay() {
    setAnchor(Anchor.BOTTOM_RIGHT);
    setOffsets(8, 8);
    setPersistId("mana_bar");          // saved to config/easygui.json when moved
}

// Option 1 — a button that opens the drag-and-drop editor:
card.add(new Button("Edit HUD", () -> minecraft.setScreen(new HudEditScreen(this))));

// Option 2 — a code-level opt-in (your mod decides, once, in client init):
// players can then drag overlays whenever the chat screen is open.
OverlayManager.setMoveInChat(true);
```

While dragging, overlays snap to screen edges, the center lines, and other overlays' edges/centers — with accent guide lines showing the alignment. Right-click resets an overlay to its code-defined position; in the editor, arrow keys nudge by 1px (Shift for 5). Dropped overlays re-anchor to the closest third of the screen, so something parked near the bottom-right stays glued to the bottom-right at any resolution. The demo wires both options up: the gear button in the demo header opens the editor, and EasyGUI's own client init opts into chat-screen dragging.

### Direct drawing

All of `Render2D`/`Text2D` works anywhere a `GuiGraphics` is available:

```java
Render2D.dropShadow(graphics, x, y, w, h, 10, 8, 0x66000000);
Render2D.fillRoundedRect(graphics, x, y, w, h, 10, 0xFF15151C);
Render2D.fillRoundedRectGradient(graphics, x, y, w, 3, 1.5f, 0xFF5B8CFF, 0xFF7AA2FF);
Render2D.drawArc(graphics, cx, cy, 9, 2.5f, 0, 270, 0xFF5B8CFF);   // radial progress
Render2D.texturedRoundedRect(graphics, skinTexture, x, y, 32, 32, 8, 0xFFFFFFFF); // round avatar
Icons.GEAR.render(graphics, x, y, 12, 0xFF9A9AA8);
```

### Frosted glass (real blur)

```java
// Any panel:
Panel card = root.add(new Panel().setCard(true).setFrosted(true));
card.setFrostRadius(9f);                       // blur radius in GUI px (default 7)
card.setFrostTint(0xB4151520);                 // tint alpha = how opaque the glass is

// Direct drawing (also works in HUD overlays — blurs the world behind):
Render2D.fillRoundedRectBlurred(graphics, x, y, w, h, 8f /*corners*/, 6f /*blur*/, 0x90101018);

// Screens blur their whole background by default, animated with the open transition:
myScreen.setBackgroundBlur(true).setBackgroundBlurRadius(10f);
```

The blur is a half-resolution separable gaussian over the current framebuffer, recaptured per fill, so stacked glass blurs correctly. If the shaders are unavailable everything falls back (vanilla menu blur / solid fills) — `fillRoundedRectBlurred` returns `false` so you can fall back yourself.

### Custom shaders

Put a vanilla-format core shader in your mod assets — `assets/mymod/shaders/core/mymod_glow.json` + `.vsh` + `.fsh` (prefix the name with your modid; core shader names are global). Then:

```java
public static final EasyShader GLOW = EasyShader.of(
        ResourceLocation.fromNamespaceAndPath("mymod", "mymod_glow"),
        DefaultVertexFormat.POSITION_TEX_COLOR);

// As a fill (UVs 0..1 across the rect, Time uniform auto-fed, AA corners):
Render2D.shadedRoundedRect(graphics, GLOW, x, y, w, h, 8f, 0xFFFFFFFF,
        shader -> shader.safeGetUniform("Intensity").set(1.5f));

// As a widget:
card.add(new ShaderView(Shaders.AURORA).setRadius(2f)).setBounds(x, y, 200, 4);
```

Standard uniforms (`ModelViewMat`, `ProjMat`, `ColorModulator`, `ScreenSize`, `GameTime`, `Sampler0`…) are filled by the vanilla pipeline; declare only what you use. Shaders hot-reload with F3+T, and a failed compile logs once and draws nothing instead of crashing. For fully custom geometry, pass an `EasyShader` straight to `RenderSystem.setShader(...)` — it's a `Supplier<ShaderInstance>`.

The built-in `Shaders.LIQUID` (animated fluid via domain-warped fbm noise) shows every integration point:

```java
// 1. Panel card background, recolored per-frame through the uniforms callback:
Panel tank = root.add(new Panel().setShaderBackground(Shaders.LIQUID,
        Shaders.liquidColors(0xFF3D0E02, 0xFFE25822, 0xFFFFC74D)));  // lava

// 2. Fullscreen animated backdrop for a menu screen (fades in with the transition):
public MyMenu() { super(Component.literal("Menu")); setBackgroundShader(Shaders.LIQUID); }

// 3. Standalone widget:
card.add(new ShaderView(Shaders.LIQUID).setRadius(6f)).setBounds(x, y, 200, 26);

// 4. Direct drawing, e.g. inside a HUD overlay (see DemoOverlay's accent strip):
Render2D.shadedRoundedRect(graphics, Shaders.LIQUID, x, y, w, 2f, 1f, 0xFFFFFFFF, null);
```

`Shaders.liquidColors(deep, body, highlight)` swaps the palette (water/lava/honey/slime presets in the javadoc); `Speed` and `Scale` uniforms tune the motion. The demo screen's "Liquid shader" bar and the HUD watermark's bottom strip both use it.

**Aspect ratio:** shader patterns are square in UV space, so a wide-thin fill would normally stretch them into streaks. Every shader fill takes a `ShaderFit` — `COVER` (default, "zoom to fill": uniform scale, overflow cropped), `TILE` (natural scale, the field continues along the long side), or `STRETCH` (raw 0..1 UVs). Available as the last parameter on `Render2D.shadedRect`/`shadedRoundedRect`, and via `ShaderView.setFit`, `Panel.setShaderBackgroundFit`, `EasyScreen.setBackgroundShaderFit`.

### Custom fonts

```java
TrueTypeFont inter = Fonts.inter();                              // bundled (SIL OFL 1.1)
TrueTypeFont brand = Fonts.fromResource(                          // your own TTF
        ResourceLocation.fromNamespaceAndPath("mymod", "fonts/brand.ttf"));
TrueTypeFont system = Fonts.fromFile(Path.of("C:/Windows/Fonts/segoeui.ttf"));

// Re-render every EasyGUI widget with it (vanilla text is 9 tall):
Text2D.setUiFont(inter, 9f);
Text2D.clearUiFont();                                             // back to vanilla

// Or draw directly at any size:
inter.draw(graphics, "Headline", x, y, 22f, 0xFFECECF1);
float w = inter.width("Headline", 22f);
```

Glyphs bake into an atlas per effective pixel size (GUI-scale aware, oversampled below 36px) so text stays crisp at any scale. Loaders return `null` instead of throwing when a font is missing or invalid.

### Config & persistence

Define values once (statics are the natural place), bind them to widgets, done — saving is automatic (debounced ~2 s after the last change, plus on game exit):

```java
public final class MyConfig {
    public static final EasyConfig CONFIG = EasyConfig.of("mymod");   // config/mymod.json

    public static final ConfigValue<Boolean>    SHOW_HUD  = CONFIG.defineBool("hud.show", true);
    public static final ConfigValue<Double>     HUD_SCALE = CONFIG.defineDouble("hud.scale", 1.0, 0.5, 2.0); // clamped
    public static final ConfigValue<Integer>    ACCENT    = CONFIG.defineColor("theme.accent", 0xFF5B8CFF);  // "#FF5B8CFF" in the file
    public static final ConfigValue<Mode>       MODE      = CONFIG.defineEnum("general.mode", Mode.SIMPLE);
    public static final ConfigValue<Integer>    LAST_TAB  = CONFIG.defineInt("state.last_tab", 0);           // UI state
}

// ConfigValue is a Supplier + Consumer, so binding is one line:
card.add(new ToggleSwitch("Show HUD", MyConfig.SHOW_HUD.get(), MyConfig.SHOW_HUD));
card.add(new Slider(50, 200, 5, MyConfig.HUD_SCALE.get() * 100, v -> MyConfig.HUD_SCALE.set(v / 100)));

// React to changes (fires on set and on CONFIG.reload() after external edits):
MyConfig.ACCENT.onChange(color -> theme.accent = color);
```

Dotted keys become nested JSON objects, so files stay organized and hand-editable. The demo persists its theme, frosted/font toggles, overlay visibility, slider value, *and the scroll position you left the list at* (`config/easygui-demo.json`, see `DemoConfig` + `DemoScreen.restoreScrollState`) — reopen the demo after a restart and it picks up exactly where you were.

### Depending on EasyGUI from another Architectury mod

In `common`: `modApi "com.stormcph:easygui-common:<version>"`, and in the platform modules the matching `easygui-fabric` / `easygui-neoforge` artifacts (publish to your maven with `./gradlew publish`, or use `mavenLocal`).

## Project layout

```
common/    library + demo (loader-agnostic, Architectury events)
fabric/    Fabric entrypoint + bundling
neoforge/  NeoForge entrypoint + bundling
```

| Stack | Version |
|---|---|
| Minecraft | 1.21.1 (Mojang mappings) |
| Architectury API | 13.0.8 |
| Fabric Loader / API | 0.19.3 / 0.116.12+1.21.1 |
| NeoForge | 21.1.233 |
| Java / Gradle | 21 / 8.10.2 |

## License

[PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0/) — free to use, modify, and share for any **noncommercial** purpose (hobby projects, personal use, charities, education, public research). Commercial use requires permission from the author (© 2026 stormcoph).

## Notes & roadmap ideas

- Layout containers (rows/columns), multi-line text areas, and color pickers are good next widgets — see `TODO.md` for the living roadmap.
- Shapes are feather-anti-aliased; on GUI scale 1 the effect is subtle by nature of the 1px feather.
- TTF rendering covers Latin/Latin-1/Latin-Extended-A + common punctuation by default; pass custom codepoint ranges to `new TrueTypeFont(bytes, name, ranges)` for more. No complex shaping (ligatures/RTL).
- Blur captures the framebuffer per frosted fill; dozens of stacked glass panels per frame will cost fillrate. A shared per-frame capture is a possible optimization.
- The bundled Inter font adds ~860 KB to the jar; its OFL license ships at `assets/easygui/fonts/OFL.txt`.
