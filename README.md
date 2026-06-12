# EasyGUI

A cross-platform (**Fabric + NeoForge** via [Architectury](https://docs.architectury.dev/)) library mod for building clean, animated, premium-feeling GUIs in Minecraft **1.21.1**.

Everything renders with plain `position_color` / `position_tex_color` shaders and tessellated triangle geometry with sub-pixel edge feathering — no custom shader pipeline — so visuals are identical on both loaders and resilient to rendering-stack changes.

## Features

- **Rendering core (`Render2D`)** — anti-aliased rounded rectangles (per-corner radii), circles, arcs/rings, lines with round caps, triangles, vertical/horizontal gradients, layered soft drop shadows, textured quads and **rounded-corner-clipped textures** (avatars!), nested scissor regions, global alpha fading for whole-tree transitions.
- **Icons** — `Icons.*` ships ~16 procedural vector icons (close, check, chevrons, search, gear, info, warning, menu, copy, user, folder, arrow…) that stay crisp at any scale and tint freely. `TextureIcon` wraps your own textures/atlas regions.
- **Animation system** — `SmoothValue` (frame-rate-independent exponential smoothing; powers hovers, scrolling, toggles), `Animation` (duration + easing one-shots), `Easing` (17 curves: cubic, expo, back, elastic, bounce…).
- **Widgets** — `Button` (variants, icons, ripple, press-scale), `ToggleSwitch`, `Checkbox` (draw-on check), `Slider`, `TextField` (selection, clipboard, word-jump, placeholder), `Dropdown` (animated popup layer), `ScrollPanel` (smooth scrolling, fading scrollbar), `Label`, `ProgressBar` (+ indeterminate), `Spinner`, `Panel` cards with shadows.
- **Screens (`EasyScreen`)** — open/close fade + scale animations, background dim + vanilla menu blur, focus management, popup routing, built-in tooltips.
- **HUD overlays** — `HudOverlay` + `OverlayManager` with 9-point `Anchor` positioning, rendered during the vanilla HUD pass on both loaders.
- **Theming** — live-swappable `Theme` palettes (`Theme.dark()` / `Theme.light()`), all widgets re-skin instantly.

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

- Background blur behind `EasyScreen` uses vanilla's menu blur (in-world). A custom Kawase blur pass would allow blur-behind-panels and is a natural v2 feature.
- Overlay drag-to-reposition edit mode, layout containers (rows/columns), multi-line text areas, and color pickers are good next widgets.
- Shapes are feather-anti-aliased; on GUI scale 1 the effect is subtle by nature of the 1px feather.
