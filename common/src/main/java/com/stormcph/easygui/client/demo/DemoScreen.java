package com.stormcph.easygui.client.demo;

import com.stormcph.easygui.client.EasyGuiClient;
import com.stormcph.easygui.client.font.Fonts;
import com.stormcph.easygui.client.font.TrueTypeFont;
import com.stormcph.easygui.client.overlay.HudEditScreen;
import com.stormcph.easygui.client.render.Icons;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.render.shader.Shaders;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.theme.Theme;
import com.stormcph.easygui.client.widget.Button;
import com.stormcph.easygui.client.widget.Checkbox;
import com.stormcph.easygui.client.widget.CycleButton;
import com.stormcph.easygui.client.widget.Divider;
import com.stormcph.easygui.client.widget.Dropdown;
import com.stormcph.easygui.client.widget.Label;
import com.stormcph.easygui.client.widget.NumberStepper;
import com.stormcph.easygui.client.widget.Panel;
import com.stormcph.easygui.client.widget.ProgressBar;
import com.stormcph.easygui.client.widget.RangeSlider;
import com.stormcph.easygui.client.widget.ScrollPanel;
import com.stormcph.easygui.client.widget.SegmentedControl;
import com.stormcph.easygui.client.widget.ShaderView;
import com.stormcph.easygui.client.widget.Slider;
import com.stormcph.easygui.client.widget.Spinner;
import com.stormcph.easygui.client.widget.TextField;
import com.stormcph.easygui.client.widget.ToggleSwitch;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Showcase screen for every EasyGUI widget. Open with F8 (rebindable under
 * Options &gt; Controls &gt; EasyGUI). Also serves as example code for the API.
 *
 * <p>The layout is responsive: on small GUI sizes (high GUI scale / small windows) the
 * two-column card collapses into a single scrolling column.</p>
 */
@Environment(EnvType.CLIENT)
public class DemoScreen extends EasyScreen {
    /** Exercises {@link CycleButton#ofEnum} ("HIGH" renders as "High"). */
    private enum DemoQuality {LOW, MEDIUM, HIGH, ULTRA}

    private final ProgressBar progressBar = new ProgressBar();
    private ScrollPanel persistedScroll;

    public DemoScreen() {
        super(Component.literal("EasyGUI Demo"));
    }

    @Override
    protected void build(Panel root) {
        // build() reruns on resize; keep the scroll position alive across rebuilds too
        saveScrollState();

        float cardW = Math.min(470, width - 16);
        float cardH = Math.min(300, height - 16);
        boolean wide = cardW >= 460 && cardH >= 290;
        float cx = (width - cardW) / 2f;
        float cy = (height - cardH) / 2f;

        Panel card = root.add(new Panel().setCard(true).setFrosted(DemoConfig.FROSTED_CARD.get()));
        card.setBounds(cx, cy, cardW, cardH);

        // Header
        card.add(new Label("EasyGUI").setScale(1.4f))
                .setBounds(cx + 18, cy + (wide ? 14 : 12), 120, 14);
        if (wide) {
            card.add(new Label("Clean, animated GUIs for Fabric & NeoForge").setMuted(true))
                    .setBounds(cx + 18, cy + 30, 280, 10);
        }
        card.add(new Button(Icons.GEAR, () -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new HudEditScreen(this));
                    }
                }).setVariant(Button.Variant.GHOST))
                .setTooltip("Edit HUD layout")
                .setBounds(cx + cardW - 52, cy + 10, 20, 20);
        card.add(new Button(Icons.CLOSE, this::closeWithAnimation).setVariant(Button.Variant.GHOST))
                .setTooltip("Close")
                .setBounds(cx + cardW - 30, cy + 10, 20, 20);

        // Custom-shader accent strip (animated aurora gradient)
        card.add(new ShaderView(Shaders.AURORA).setRadius(1.5f))
                .setTooltip("Custom shader (EasyShader + ShaderView)")
                .setBounds(cx + 18, wide ? cy + 44 : cy + 32, cardW - 36, 3);

        if (wide) {
            // The widget set outgrew the card, so the left column scrolls too
            ScrollPanel controls = card.add(new ScrollPanel());
            controls.setBounds(cx + 8, cy + 48, 222, cardH - 58);
            addControls(controls, card, cx + 18, cy + 56, 200);

            // Right column: smooth-scrolling list + liquid shader bar
            float listX = cx + 240;
            float listW = cardW - (listX - cx) - 18;
            card.add(new Label("Scroll list").setMuted(true))
                    .setBounds(listX, cy + 52, listW, 10);
            ScrollPanel list = card.add(new ScrollPanel());
            list.setCard(true);
            list.setShadow(false);
            list.setBackgroundColor(getTheme().surfaceVariant);
            list.setBounds(listX, cy + 66, listW, cardH - 116);
            float itemY = list.getY() + 6;
            for (int i = 1; i <= 25; i++) {
                final int n = i;
                list.add(new Label("List entry " + n))
                        .setBounds(listX + 10, itemY, listW - 70, 14);
                list.add(new Button("Run", () -> {}).setVariant(Button.Variant.SECONDARY).setPlaySound(false))
                        .setBounds(listX + listW - 48, itemY - 2, 36, 16);
                itemY += 22;
            }
            addLiquidBar(card, listX, cy + cardH - 44, listW);
            restoreScrollState(list);
        } else {
            // Compact: everything in a single scrolling column, liquid bar up top so the
            // shader showcase is visible without scrolling
            ScrollPanel content = card.add(new ScrollPanel());
            content.setBounds(cx + 8, cy + 40, cardW - 16, cardH - 50);
            float colX = cx + 18;
            float colW = cardW - 36;
            addLiquidBar(content, colX, content.getY() + 6, colW);
            addControls(content, card, colX, content.getY() + 40, colW);
            restoreScrollState(content);
        }
    }

    /** Eases the scroll panel back to where it was last left (persisted UI state). */
    private void restoreScrollState(ScrollPanel panel) {
        persistedScroll = panel;
        panel.scrollTo((float) (double) DemoConfig.LIST_SCROLL.get());
    }

    private void saveScrollState() {
        if (persistedScroll != null) {
            DemoConfig.LIST_SCROLL.set((double) persistedScroll.getScrollAmount());
        }
    }

    @Override
    public void removed() {
        saveScrollState();
        super.removed();
    }

    /** Adds the shared control rows to {@code parent}; returns the y after the last row. */
    private float addControls(Panel parent, Panel card, float x, float y, float w) {
        float rowY = y;

        // Buttons
        parent.add(new Button("Primary", () -> {}))
                .setBounds(x, rowY, 62, 20);
        parent.add(new Button("Outline", () -> {}).setVariant(Button.Variant.SECONDARY))
                .setBounds(x + 68, rowY, 62, 20);
        parent.add(new Button(Icons.WARNING, () -> {}).setVariant(Button.Variant.DANGER))
                .setTooltip("Danger!")
                .setBounds(x + 136, rowY, 24, 20);
        rowY += 28;

        // Toggle + checkbox (the toggle persists via DemoConfig)
        parent.add(new ToggleSwitch("HUD overlay", EasyGuiClient.DEMO_OVERLAY.isVisible(), v -> {
                    EasyGuiClient.DEMO_OVERLAY.setVisible(v);
                    DemoConfig.HUD_OVERLAY.set(v);
                }))
                .setTooltip("Shows the EasyGUI watermark overlay in-game")
                .setBounds(x, rowY, w, 16);
        rowY += 22;
        parent.add(new Checkbox("Animated check mark", true, v -> {}))
                .setBounds(x, rowY, w, 16);
        rowY += 24;

        // Slider driving the progress bar; its value is remembered across sessions
        parent.add(new Slider(0, 100, 1, DemoConfig.DEMO_PROGRESS.get() * 100, v -> {
                    DemoConfig.DEMO_PROGRESS.set(v / 100.0);
                    progressBar.setProgress((float) (v / 100.0));
                }).setValueFormatter(v -> (int) v + "%", 28))
                .setBounds(x, rowY, w, 16);
        rowY += 22;
        parent.add(progressBar.setProgress((float) (double) DemoConfig.DEMO_PROGRESS.get()))
                .setBounds(x, rowY, w, 4);
        rowY += 14;

        // Text input
        parent.add(new TextField("Type something…"))
                .setBounds(x, rowY, w, 20);
        rowY += 26;

        // Dropdown switching themes live; the choice persists
        boolean isLight = DemoConfig.THEME.get() == DemoConfig.ThemeChoice.LIGHT;
        parent.add(new Dropdown(List.of("Dark theme", "Light theme"), isLight ? 1 : 0, index -> {
                    DemoConfig.THEME.set(index == 1
                            ? DemoConfig.ThemeChoice.LIGHT : DemoConfig.ThemeChoice.DARK);
                    Theme picked = index == 1 ? Theme.light() : Theme.dark();
                    Theme.setDefault(picked);
                    setTheme(picked);
                }))
                .setBounds(x, rowY, w, 20);
        rowY += 28;

        // Icon strip + spinner
        float ix = x;
        for (var icon : List.of(Icons.SEARCH, Icons.GEAR, Icons.INFO, Icons.USER,
                Icons.FOLDER, Icons.COPY, Icons.MENU, Icons.ARROW_RIGHT)) {
            parent.add(new Button(icon, () -> {}).setVariant(Button.Variant.GHOST).setPlaySound(false))
                    .setBounds(ix, rowY, 18, 18);
            ix += 21;
        }
        parent.add(new Spinner()).setBounds(x + w - 18, rowY, 18, 18);
        rowY += 24;

        // Frosted glass + custom font toggles (both persisted)
        parent.add(new ToggleSwitch("Frosted glass card", DemoConfig.FROSTED_CARD.get(), v -> {
                    card.setFrosted(v);
                    DemoConfig.FROSTED_CARD.set(v);
                }))
                .setTooltip("Real shader blur of everything behind the panel")
                .setBounds(x, rowY, w, 16);
        rowY += 22;
        parent.add(new ToggleSwitch("Inter UI font (TTF)", Text2D.getUiFont() != null, on -> {
                    DemoConfig.INTER_FONT.set(on);
                    if (on) {
                        TrueTypeFont inter = Fonts.inter();
                        if (inter != null) {
                            Text2D.setUiFont(inter, 9f);
                        }
                    } else {
                        Text2D.clearUiFont();
                    }
                }))
                .setTooltip("Re-renders every widget with the bundled Inter TrueType font")
                .setBounds(x, rowY, w, 16);
        rowY += 22;

        parent.add(new Divider("More widgets"))
                .setBounds(x, rowY, w, 10);
        rowY += 16;

        // Segmented control: exclusive choice with a sliding accent pill
        parent.add(new SegmentedControl(List.of("Fancy", "Fast", "Off"), 0, i -> {}))
                .setBounds(x, rowY, w, 18);
        rowY += 24;

        // Range slider: drag either thumb to pick a min/max pair
        parent.add(new RangeSlider(0, 100, 1, 20, 80, (lo, hi) -> {})
                        .setValueFormatter((lo, hi) -> lo.intValue() + "–" + hi.intValue(), 34))
                .setBounds(x, rowY, w, 16);
        rowY += 22;

        // Number stepper (click +/−, drag to scrub, click the number to type) + cycle button
        parent.add(new NumberStepper(0, 64, 1, 16, v -> {}))
                .setTooltip("Click +/−, drag the number to scrub, or click it to type")
                .setBounds(x, rowY, 70, 18);
        parent.add(CycleButton.ofEnum("Quality", DemoQuality.class, DemoQuality.HIGH, q -> {}))
                .setTooltip("Right-click cycles backwards")
                .setBounds(x + 76, rowY, w - 76, 18);
        rowY += 24;

        return rowY;
    }

    /** The animated liquid shader as a panel background (Panel.setShaderBackground). */
    private void addLiquidBar(Panel parent, float x, float y, float w) {
        Panel liquid = parent.add(new Panel()
                .setShaderBackground(Shaders.LIQUID,
                        Shaders.liquidColors(0xFF060D22, getTheme().accent, 0xFFD6ECFF)));
        liquid.setRadius(6f).setShadow(false);
        liquid.setTooltip("Animated liquid shader (Panel.setShaderBackground)");
        liquid.setBounds(x, y, w, 26);
        liquid.add(new Label("Liquid shader").setAlign(Label.Align.CENTER).setColor(0xFFFFFFFF))
                .setBounds(x, y + 8, w, 10);
    }
}
