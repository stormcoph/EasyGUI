package com.stormcph.easygui.client.demo;

import com.stormcph.easygui.client.EasyGuiClient;
import com.stormcph.easygui.client.chart.BarChart;
import com.stormcph.easygui.client.chart.DonutChart;
import com.stormcph.easygui.client.chart.Histogram;
import com.stormcph.easygui.client.chart.LineChart;
import com.stormcph.easygui.client.chart.RadarChart;
import com.stormcph.easygui.client.font.Fonts;
import com.stormcph.easygui.client.font.StyledText;
import com.stormcph.easygui.client.font.TextStyle;
import com.stormcph.easygui.client.font.TrueTypeFont;
import com.stormcph.easygui.client.media.AudioClip;
import com.stormcph.easygui.client.media.EasyAudio;
import com.stormcph.easygui.client.overlay.HudEditScreen;
import com.stormcph.easygui.client.overlay.Toast;
import com.stormcph.easygui.client.overlay.Toasts;
import com.stormcph.easygui.client.render.Icons;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.render.shader.Shaders;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.stat.Metrics;
import com.stormcph.easygui.client.theme.Theme;
import com.stormcph.easygui.client.widget.Button;
import com.stormcph.easygui.client.widget.Checkbox;
import com.stormcph.easygui.client.widget.CollapsibleSection;
import com.stormcph.easygui.client.widget.ColorPickerButton;
import com.stormcph.easygui.client.widget.ContextMenu;
import com.stormcph.easygui.client.widget.CycleButton;
import com.stormcph.easygui.client.widget.Divider;
import com.stormcph.easygui.client.widget.Dropdown;
import com.stormcph.easygui.client.widget.ImageView;
import com.stormcph.easygui.client.widget.ItemView;
import com.stormcph.easygui.client.widget.KeybindButton;
import com.stormcph.easygui.client.widget.Label;
import com.stormcph.easygui.client.widget.LinearLayout;
import com.stormcph.easygui.client.widget.ModalDialog;
import com.stormcph.easygui.client.widget.NumberStepper;
import com.stormcph.easygui.client.widget.Panel;
import com.stormcph.easygui.client.widget.PlayerView;
import com.stormcph.easygui.client.widget.ProgressBar;
import com.stormcph.easygui.client.widget.RangeSlider;
import com.stormcph.easygui.client.widget.ReorderableList;
import com.stormcph.easygui.client.widget.ScrollPanel;
import com.stormcph.easygui.client.widget.SearchableDropdown;
import com.stormcph.easygui.client.widget.SegmentedControl;
import com.stormcph.easygui.client.widget.ShaderView;
import com.stormcph.easygui.client.widget.Slider;
import com.stormcph.easygui.client.widget.Spinner;
import com.stormcph.easygui.client.widget.Tabs;
import com.stormcph.easygui.client.widget.TextArea;
import com.stormcph.easygui.client.widget.TextField;
import com.stormcph.easygui.client.widget.ToggleSwitch;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Showcase screen for every EasyGUI feature, organized into tabs (a {@link Tabs} widget,
 * naturally). Open with F8 (rebindable under Options &gt; Controls &gt; EasyGUI). Also
 * serves as example code for the API; the last-open tab persists via {@link DemoConfig}.
 */
@Environment(EnvType.CLIENT)
public class DemoScreen extends EasyScreen {
    /** Exercises {@link CycleButton#ofEnum} ("HIGH" renders as "High"). */
    private enum DemoQuality {LOW, MEDIUM, HIGH, ULTRA}

    private final ProgressBar progressBar = new ProgressBar();
    private AudioClip clickClip;

    public DemoScreen() {
        super(Component.literal("EasyGUI Demo"));
    }

    @Override
    protected void build(Panel root) {
        float cardW = Math.min(470, width - 16);
        float cardH = Math.min(300, height - 16);
        float cx = (width - cardW) / 2f;
        float cy = (height - cardH) / 2f;

        Panel card = root.add(new Panel().setCard(true).setFrosted(DemoConfig.FROSTED_CARD.get()));
        card.setBounds(cx, cy, cardW, cardH);

        // Header
        card.add(new Label("EasyGUI").setScale(1.4f))
                .setBounds(cx + 18, cy + 12, 120, 14);
        if (cardW >= 430) {
            card.add(new Label("Clean, animated GUIs for Fabric & NeoForge").setMuted(true))
                    .setBounds(cx + 110, cy + 17, 260, 10);
        }
        card.add(new Button(Icons.GEAR, () -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new HudEditScreen(this));
                    }
                }).setVariant(Button.Variant.GHOST))
                .setTooltip("Edit HUD layout")
                .setBounds(cx + cardW - 52, cy + 8, 20, 20);
        card.add(new Button(Icons.CLOSE, this::closeWithAnimation).setVariant(Button.Variant.GHOST))
                .setTooltip("Close")
                .setBounds(cx + cardW - 30, cy + 8, 20, 20);

        // Custom-shader accent strip (animated aurora gradient)
        card.add(new ShaderView(Shaders.AURORA).setRadius(1.5f))
                .setTooltip("Custom shader (EasyShader + ShaderView)")
                .setBounds(cx + 18, cy + 32, cardW - 36, 3);

        Tabs tabs = card.add(new Tabs());
        tabs.setBounds(cx + 8, cy + 40, cardW - 16, cardH - 48);
        buildWidgetsTab(tabs.addTab("Widgets"), tabs, card);
        buildInputsTab(tabs.addTab("Inputs"), tabs);
        buildTypographyTab(tabs.addTab("Type"), tabs);
        buildLayoutTab(tabs.addTab("Layout"), tabs);
        buildChartsTab(tabs.addTab("Charts"), tabs);
        buildHudTab(tabs.addTab("HUD"), tabs);
        buildMediaTab(tabs.addTab("Media"), tabs);
        tabs.persistTo(DemoConfig.LAST_TAB); // reopens on the tab you left
    }

    /** Adds a scrolling content panel filling a tab page; content uses absolute coords. */
    private ScrollPanel pageScroll(Panel page, Tabs tabs) {
        ScrollPanel scroll = page.add(new ScrollPanel());
        scroll.setBounds(tabs.getX() + 2, tabs.contentY(), tabs.getWidth() - 4, tabs.contentHeight());
        return scroll;
    }

    // ------------------------------------------------------------------
    // Widgets — the classics, plus theming
    // ------------------------------------------------------------------

    private void buildWidgetsTab(Panel page, Tabs tabs, Panel card) {
        ScrollPanel content = pageScroll(page, tabs);
        float x = tabs.getX() + 12;
        float w = tabs.getWidth() - 24;
        float rowY = tabs.contentY() + 8;

        content.add(new Button("Primary", () -> {}))
                .setBounds(x, rowY, 62, 20);
        content.add(new Button("Outline", () -> {}).setVariant(Button.Variant.SECONDARY))
                .setBounds(x + 68, rowY, 62, 20);
        content.add(new Button(Icons.WARNING, () -> {}).setVariant(Button.Variant.DANGER))
                .setTooltip("Danger!")
                .setBounds(x + 136, rowY, 24, 20);
        content.add(new Spinner()).setBounds(x + w - 18, rowY + 1, 18, 18);
        rowY += 26;

        content.add(new Checkbox("Animated check mark", true, v -> {}))
                .setBounds(x, rowY, w, 16);
        rowY += 22;

        // Slider driving the progress bar; its value is remembered across sessions
        content.add(new Slider(0, 100, 1, DemoConfig.DEMO_PROGRESS.get() * 100, v -> {
                    DemoConfig.DEMO_PROGRESS.set(v / 100.0);
                    progressBar.setProgress((float) (v / 100.0));
                }).setValueFormatter(v -> (int) v + "%", 28))
                .setBounds(x, rowY, w, 16);
        rowY += 20;
        content.add(progressBar.setProgress((float) (double) DemoConfig.DEMO_PROGRESS.get()))
                .setBounds(x, rowY, w, 4);
        rowY += 12;

        content.add(new TextField("Type something…"))
                .setBounds(x, rowY, w, 20);
        rowY += 26;

        // Icon strip
        float ix = x;
        for (var icon : List.of(Icons.SEARCH, Icons.GEAR, Icons.INFO, Icons.USER,
                Icons.FOLDER, Icons.COPY, Icons.MENU, Icons.ARROW_RIGHT)) {
            content.add(new Button(icon, () -> {}).setVariant(Button.Variant.GHOST).setPlaySound(false))
                    .setBounds(ix, rowY, 18, 18);
            ix += 21;
        }
        rowY += 24;

        addLiquidBar(content, x, rowY, w);
        rowY += 32;

        content.add(new Divider("Theme"))
                .setBounds(x, rowY, w, 10);
        rowY += 16;

        // Dropdown switching themes live; the choice persists
        boolean isLight = DemoConfig.THEME.get() == DemoConfig.ThemeChoice.LIGHT;
        content.add(new Dropdown(List.of("Dark theme", "Light theme"), isLight ? 1 : 0, index -> {
                    DemoConfig.THEME.set(index == 1
                            ? DemoConfig.ThemeChoice.LIGHT : DemoConfig.ThemeChoice.DARK);
                    Theme picked = index == 1 ? Theme.light() : Theme.dark();
                    DemoConfig.applyAccent(picked);
                    Theme.setDefault(picked);
                    setTheme(picked);
                }))
                .setBounds(x, rowY, w, 20);
        rowY += 26;

        // Color picker driving the theme accent live; persists via defineColor
        content.add(new ColorPickerButton("Accent color", DemoConfig.ACCENT.get(), c -> {
                    DemoConfig.ACCENT.set(c);
                    DemoConfig.applyAccent(getTheme());
                }))
                .setBounds(x, rowY, w, 20);
        rowY += 26;

        content.add(new ToggleSwitch("Frosted glass card", DemoConfig.FROSTED_CARD.get(), v -> {
                    card.setFrosted(v);
                    DemoConfig.FROSTED_CARD.set(v);
                }))
                .setTooltip("Real shader blur of everything behind the panel")
                .setBounds(x, rowY, w, 16);
        rowY += 22;
        content.add(new ToggleSwitch("Inter UI font (TTF)", Text2D.getUiFont() != null, on -> {
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

    // ------------------------------------------------------------------
    // Inputs — the richer input widgets
    // ------------------------------------------------------------------

    private void buildInputsTab(Panel page, Tabs tabs) {
        ScrollPanel content = pageScroll(page, tabs);
        float x = tabs.getX() + 12;
        float w = tabs.getWidth() - 24;
        float rowY = tabs.contentY() + 8;

        content.add(new SegmentedControl(List.of("Fancy", "Fast", "Off"), 0, i -> {}))
                .setBounds(x, rowY, w, 18);
        rowY += 24;

        content.add(new RangeSlider(0, 100, 1, 20, 80, (lo, hi) -> {})
                        .setValueFormatter((lo, hi) -> lo.intValue() + "–" + hi.intValue(), 34))
                .setBounds(x, rowY, w, 16);
        rowY += 22;

        content.add(new NumberStepper(0, 64, 1, 16, v -> {}))
                .setTooltip("Click +/−, drag the number to scrub, or click it to type")
                .setBounds(x, rowY, 70, 18);
        content.add(CycleButton.ofEnum("Quality", DemoQuality.class, DemoQuality.HIGH, q -> {}))
                .setTooltip("Right-click cycles backwards")
                .setBounds(x + 76, rowY, w - 76, 18);
        rowY += 24;

        // Bind both to the same key to see conflict highlighting
        content.add(new KeybindButton("Bind A", GLFW.GLFW_KEY_G, k -> {}))
                .setTooltip("Click, press a key or mouse button — right-click clears")
                .setBounds(x, rowY, w / 2f - 4, 20);
        content.add(new KeybindButton("Bind B", GLFW.GLFW_KEY_H, k -> {}))
                .setTooltip("Bind both to the same key to see the conflict highlight")
                .setBounds(x + w / 2f + 4, rowY, w / 2f - 4, 20);
        rowY += 26;

        content.add(new SearchableDropdown(List.of("Andesite", "Basalt", "Calcite", "Deepslate",
                        "Diorite", "Granite", "Gravel", "Obsidian", "Sandstone", "Tuff"), 0, i -> {}))
                .setTooltip("Open it and type to filter")
                .setBounds(x, rowY, w, 20);
        rowY += 26;

        content.add(new TextArea("Multiline notes — wraps, scrolls, selects…"))
                .setBounds(x, rowY, w, 48);
    }

    // ------------------------------------------------------------------
    // Type — decorative typography (TextStyle + StyledText)
    // ------------------------------------------------------------------

    private void buildTypographyTab(Panel page, Tabs tabs) {
        ScrollPanel content = pageScroll(page, tabs);
        float x = tabs.getX() + 12;
        float w = tabs.getWidth() - 24;
        float rowY = tabs.contentY() + 8;
        int accent = getTheme().accent;
        int text = getTheme().text;

        // Gradient headline with tight-ish tracking (a hero title).
        content.add(new Label("DISPLAY")
                        .setStyle(new TextStyle().setGradient(0xFF9CC8FF, 0xFF6D4AFF).setTracking(3f))
                        .setScale(3f))
                .setTooltip("Vertical gradient fill + letter-spacing")
                .setBounds(x, rowY, w, 30);
        rowY += 34;

        // Outline and hollow lettering, side by side. Hollow needs display size: the
        // stroke is contour-centered, so at body sizes it eats the letter counters.
        content.add(new Label("Outline")
                        .setStyle(new TextStyle().setColor(0xFFFFFFFF).setOutline(0xFF10131A, 1.4f))
                        .setScale(2f))
                .setBounds(x, rowY, w / 2f - 6, 28);
        content.add(new Label("Hollow")
                        .setStyle(new TextStyle().setOutline(accent, 1.0f).setHollow(true))
                        .setScale(2.8f))
                .setTooltip("Outline with a transparent fill")
                .setBounds(x + w / 2f + 6, rowY, w / 2f - 6, 28);
        rowY += 34;

        // Soft blurred drop shadow.
        content.add(new Label("Soft shadow")
                        .setStyle(new TextStyle().setColor(text).setShadow(0xB0000000, 0f, 2f, 3f))
                        .setScale(2f))
                .setTooltip("Multi-draw blurred shadow — no shader")
                .setBounds(x, rowY, w, 22);
        rowY += 26;

        // Underline and strikethrough, from the font metrics.
        content.add(new Label("Underlined")
                        .setStyle(new TextStyle().setColor(text).setUnderline(true))
                        .setScale(1.4f))
                .setBounds(x, rowY, w / 2f - 6, 16);
        content.add(new Label("Struck out")
                        .setStyle(new TextStyle().setColor(getTheme().textMuted).setStrikethrough(true))
                        .setScale(1.4f))
                .setBounds(x + w / 2f + 6, rowY, w / 2f - 6, 16);
        rowY += 24;

        content.add(new Divider("Inline mixed styles"))
                .setBounds(x, rowY, w, 10);
        rowY += 16;

        // A bold word and a gradient word inside a regular sentence (StyledText runs).
        TrueTypeFont font = Text2D.getUiFont() != null ? Text2D.getUiFont() : Fonts.inter();
        if (font != null) {
            StyledText mixed = new StyledText()
                    .append("A ", font, 13f, TextStyle.of(text))
                    .append("bold", font, 13f, TextStyle.of(text).setBold(true))
                    .append(" word and a ", font, 13f, TextStyle.of(text))
                    .append("gradient", font, 13f,
                            new TextStyle().setGradientH(accent, 0xFFFF7AD9).setBold(true))
                    .append(" one, one baseline.", font, 13f, TextStyle.of(text));
            content.add(new Label().setStyledText(mixed))
                    .setTooltip("StyledText — per-run font, size, color/gradient, weight")
                    .setBounds(x, rowY, w, 16);
            rowY += 20;
        }

        content.add(new Label("All of this is one TextStyle/StyledText API — no raw draw calls.")
                        .setMuted(true))
                .setBounds(x, rowY, w, 10);
    }

    // ------------------------------------------------------------------
    // Layout — containers, dialogs, menus
    // ------------------------------------------------------------------

    private void buildLayoutTab(Panel page, Tabs tabs) {
        ScrollPanel content = pageScroll(page, tabs);
        float x = tabs.getX() + 12;
        float w = tabs.getWidth() - 24;
        float half = w / 2f - 6;
        float rowY = tabs.contentY() + 8;

        // LinearLayout: no manual row math — STRETCH width, auto gaps
        LinearLayout column = content.add(LinearLayout.vertical()
                .setGap(5f)
                .setPadding(8f)
                .setAlign(LinearLayout.Align.STRETCH));
        column.setCard(true);
        column.setShadow(false);
        column.setBounds(x, rowY, half, 96);
        column.add(new Label("LinearLayout").setMuted(true)).setSize(0, 10);
        column.add(new Slider(0, 100, 1, 40, v -> {})).setSize(0, 16);
        LinearLayout buttons = LinearLayout.horizontal().setGap(4f).setAutoSize(true);
        buttons.add(new Button("OK", () -> {})).setSize(44, 16);
        buttons.add(new Button("Cancel", () -> {}).setVariant(Button.Variant.SECONDARY)).setSize(52, 16);
        column.add(buttons);

        // CollapsibleSection: animated height reflows neighbors; state persists
        CollapsibleSection section = content.add(new CollapsibleSection("Collapsible section"));
        section.persistTo(DemoConfig.SECTION_OPEN);
        section.setBounds(x + half + 12, rowY, half, 0);
        float sy = section.contentTop() + 4;
        section.add(new ToggleSwitch("Nested toggle", true, v -> {}))
                .setBounds(section.getX() + 8, sy, half - 16, 16);
        section.add(new Checkbox("Nested checkbox", false, v -> {}))
                .setBounds(section.getX() + 8, sy + 22, half - 16, 16);
        rowY += 104;

        // ReorderableList: drag rows to reorder, Esc cancels
        ReorderableList list = content.add(new ReorderableList());
        list.setCard(true);
        list.setShadow(false);
        list.setRowGap(4f).setOnReorder((from, to) -> {});
        list.setBounds(x, rowY, half, 88);
        for (String task : new String[]{"Mine diamonds", "Build base", "Fight dragon"}) {
            Panel row = new Panel().setCard(true);
            row.setShadow(false);
            row.setBounds(0, 0, 0, 20);
            row.add(new Label(task)).setBounds(8, 6, half - 30, 9);
            list.addItem(row);
        }

        // Modal dialogs + context menu, on the popup layer
        content.add(new Button("Alert", () ->
                        ModalDialog.alert(this, "Heads up", "This is a modal alert on the popup layer.", null)))
                .setBounds(x + half + 12, rowY, half / 2f - 3, 20);
        content.add(new Button("Confirm", () ->
                        ModalDialog.confirmDanger(this, "Reset everything",
                                "This would reset all demo settings. It cannot be undone. Proceed?",
                                () -> Toasts.show(Toast.success("Reset!").withBody("(Not really — it's a demo.)"))))
                        .setVariant(Button.Variant.SECONDARY))
                .setBounds(x + half + 12 + half / 2f + 3, rowY, half / 2f - 3, 20);
        content.add(new Label("Right-click me for a context menu") {
                    @Override
                    public boolean mouseClicked(double mouseX, double mouseY, int button) {
                        if (button == 1 && contains(mouseX, mouseY)) {
                            new ContextMenu()
                                    .addItem("Copy", Icons.COPY, () -> {})
                                    .addItem("Rename", () -> {})
                                    .addDivider()
                                    .addDisabledItem("Share (soon)")
                                    .addDangerItem("Delete", () -> {})
                                    .open(getScreen(), (float) mouseX, (float) mouseY);
                            return true;
                        }
                        return false;
                    }
                }.setMuted(true))
                .setBounds(x + half + 12, rowY + 26, half, 12);
        rowY += 94;

        content.add(new Divider())
                .setBounds(x, rowY, w, 8);
    }

    // ------------------------------------------------------------------
    // Charts — live data from the stats layer
    // ------------------------------------------------------------------

    private void buildChartsTab(Panel page, Tabs tabs) {
        ScrollPanel content = pageScroll(page, tabs);
        float x = tabs.getX() + 12;
        float w = tabs.getWidth() - 24;
        float half = w / 2f - 6;
        float rowY = tabs.contentY() + 8;

        LineChart line = content.add(new LineChart()
                .addSeries("fps", Metrics.fps().series())
                .addSeries("ping", Metrics.ping().series())
                .setTimeWindow(30)
                .setShowLegend(true)
                .setShowAxisLabels(true)
                .setFill(true)
                .setSmooth(true));
        line.setBounds(x, rowY, w, 84);
        rowY += 90;

        BarChart bars = content.add(new BarChart());
        bars.setData(List.of("stone", "iron", "gold", "diam"), new float[]{120, 45, 12, 3});
        bars.setShowValues(true);
        bars.setBounds(x, rowY, half, 76);

        Histogram hist = content.add(new Histogram(Metrics.fps().series()));
        hist.setBins(0).setTimeWindow(30).setShowStats(true);
        hist.setBounds(x + half + 12, rowY, half, 76);
        rowY += 82;

        float maxHeapMb = Runtime.getRuntime().maxMemory() / (1024f * 1024f);
        DonutChart gauge = content.add(new DonutChart()
                .setGauge(0, maxHeapMb)
                .setValue(() -> Metrics.memory().value())
                .setDangerFrom(0.8f)
                .setCenterText(() -> Math.round(Metrics.memory().value()) + "M")
                .setCenterLabel("heap"));
        gauge.setBounds(x, rowY, 72, 72);

        RadarChart radar = content.add(new RadarChart()
                .setAxes(List.of("atk", "def", "spd", "hp", "luck"))
                .addSeries("player", new float[]{0.8f, 0.55f, 0.35f, 0.9f, 0.5f}));
        radar.setBounds(x + 96, rowY - 4, 120, 80);

        content.add(new Label("Everything animates: scales glide,").setMuted(true))
                .setBounds(x + 232, rowY + 22, w - 232, 10);
        content.add(new Label("bars grow, the gauge sweeps.").setMuted(true))
                .setBounds(x + 232, rowY + 34, w - 232, 10);
    }

    // ------------------------------------------------------------------
    // HUD — overlays, editor, toasts
    // ------------------------------------------------------------------

    private void buildHudTab(Panel page, Tabs tabs) {
        ScrollPanel content = pageScroll(page, tabs);
        float x = tabs.getX() + 12;
        float w = tabs.getWidth() - 24;
        float rowY = tabs.contentY() + 8;

        content.add(new Label("Overlays — drag, restyle, and rescale them in the HUD editor").setMuted(true))
                .setBounds(x, rowY, w, 10);
        rowY += 16;

        content.add(new ToggleSwitch("Watermark", EasyGuiClient.DEMO_OVERLAY.isVisible(), v -> {
                    EasyGuiClient.DEMO_OVERLAY.setVisible(v);
                    DemoConfig.HUD_OVERLAY.set(v);
                }))
                .setBounds(x, rowY, w, 16);
        rowY += 22;
        content.add(new ToggleSwitch("Info line — {fps} · {coords} · {clock}", DemoHud.info.isVisible(), v -> {
                    DemoHud.info.setVisible(v);
                    DemoConfig.HUD_TEXT.set(v);
                }))
                .setTooltip("TextElement with the placeholder registry, two-tone colors, frosted plate")
                .setBounds(x, rowY, w, 16);
        rowY += 22;
        content.add(new ToggleSwitch("Module list", DemoHud.modules.isVisible(), v -> {
                    DemoHud.modules.setVisible(v);
                    DemoConfig.HUD_MODULES.set(v);
                }))
                .setTooltip("AnimatedListOverlay: width-sorted, animated, per-row colors")
                .setBounds(x, rowY, w, 16);
        rowY += 22;
        content.add(new ToggleSwitch("FPS sparkline", DemoHud.fpsChart.isVisible(), v -> {
                    DemoHud.fpsChart.setVisible(v);
                    DemoConfig.HUD_CHART.set(v);
                }))
                .setTooltip("A chart widget on the HUD, via WidgetHostOverlay")
                .setBounds(x, rowY, w, 16);
        rowY += 24;

        content.add(new Divider())
                .setBounds(x, rowY, w, 8);
        rowY += 14;

        content.add(new Button("Edit HUD layout", () -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new HudEditScreen(this));
                    }
                }))
                .setBounds(x, rowY, 110, 20);
        content.add(new Button("Toast", () ->
                        Toasts.show(Toast.success("Config saved").withBody("Your changes were written to disk.")))
                        .setVariant(Button.Variant.SECONDARY))
                .setBounds(x + 116, rowY, 64, 20);
        content.add(new Button("Error toast", () ->
                        Toasts.show(Toast.error("Connection lost").withDuration(6)))
                        .setVariant(Button.Variant.SECONDARY))
                .setBounds(x + 186, rowY, 84, 20);
        rowY += 26;

        content.add(new Label("Toasts stack over the in-game HUD — close this screen to see them clearly.")
                        .setMuted(true))
                .setBounds(x, rowY, w, 10);
    }

    // ------------------------------------------------------------------
    // Media — items, entities, images, audio
    // ------------------------------------------------------------------

    private void buildMediaTab(Panel page, Tabs tabs) {
        ScrollPanel content = pageScroll(page, tabs);
        float x = tabs.getX() + 12;
        float w = tabs.getWidth() - 24;
        float rowY = tabs.contentY() + 8;

        content.add(new ItemView(() -> {
                    var player = Minecraft.getInstance().player;
                    return player != null ? player.getMainHandItem() : ItemStack.EMPTY;
                })
                        .setSlotBackground(true)
                        .setTooltipFromItem(true))
                .setBounds(x, rowY, 36, 36);
        content.add(new ItemView(new ItemStack(Items.DIAMOND_SWORD))
                        .setShowDecorations(false)
                        .setSlotBackground(true)
                        .setTooltipFromItem(true))
                .setBounds(x, rowY + 42, 36, 36);
        content.add(new Label("ItemView:").setMuted(true))
                .setBounds(x + 42, rowY + 8, 76, 10);
        content.add(new Label("live main hand").setMuted(true))
                .setBounds(x + 42, rowY + 20, 76, 10);
        content.add(new Label("+ fixed stack").setMuted(true))
                .setBounds(x + 42, rowY + 32, 76, 10);

        content.add(new PlayerView().setCardBackground(true))
                .setTooltip("PlayerView — follows the mouse")
                .setBounds(x + 124, rowY, 64, 96);

        content.add(new ImageView(ResourceLocation.withDefaultNamespace("textures/gui/title/minecraft.png"))
                        .setFit(ImageView.Fit.CONTAIN))
                .setTooltip("ImageView — resource, file, or URL sources, async, rounded clipping")
                .setBounds(x + 200, rowY, w - 200, 52);
        content.add(new Label("GifView and VideoView load animated").setMuted(true))
                .setBounds(x + 200, rowY + 58, w - 200, 10);
        content.add(new Label("media from files or URLs — see docs.").setMuted(true))
                .setBounds(x + 200, rowY + 70, w - 200, 10);
        rowY += 104;

        content.add(new Button("Play sound", () -> {
                    if (clickClip == null) {
                        clickClip = AudioClip.fromResource(
                                ResourceLocation.withDefaultNamespace("sounds/ui/button/click.ogg"));
                    }
                    EasyAudio.play(clickClip).setVolume(0.7f).setPitch(1.2f);
                }).setVariant(Button.Variant.SECONDARY))
                .setTooltip("EasyAudio: WAV + OGG via OpenAL/stb_vorbis, MP3 via bundled JLayer")
                .setBounds(x, rowY, 90, 20);
        content.add(new Label("AudioClip + EasyAudio — decoded off-thread, played through OpenAL").setMuted(true))
                .setBounds(x + 98, rowY + 5, w - 98, 10);
    }
}
