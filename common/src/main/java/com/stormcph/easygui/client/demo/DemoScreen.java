package com.stormcph.easygui.client.demo;

import com.stormcph.easygui.client.EasyGuiClient;
import com.stormcph.easygui.client.render.Icons;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.theme.Theme;
import com.stormcph.easygui.client.widget.Button;
import com.stormcph.easygui.client.widget.Checkbox;
import com.stormcph.easygui.client.widget.Dropdown;
import com.stormcph.easygui.client.widget.Label;
import com.stormcph.easygui.client.widget.Panel;
import com.stormcph.easygui.client.widget.ProgressBar;
import com.stormcph.easygui.client.widget.ScrollPanel;
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
 */
@Environment(EnvType.CLIENT)
public class DemoScreen extends EasyScreen {
    private final ProgressBar progressBar = new ProgressBar();
    private float demoProgress = 0.65f;

    public DemoScreen() {
        super(Component.literal("EasyGUI Demo"));
    }

    @Override
    protected void build(Panel root) {
        float cardW = 470;
        float cardH = 300;
        float cx = (width - cardW) / 2f;
        float cy = (height - cardH) / 2f;

        Panel card = root.add(new Panel().setCard(true));
        card.setBounds(cx, cy, cardW, cardH);

        // Header
        card.add(new Label("EasyGUI").setScale(1.4f))
                .setBounds(cx + 18, cy + 14, 120, 14);
        card.add(new Label("Clean, animated GUIs for Fabric & NeoForge").setMuted(true))
                .setBounds(cx + 18, cy + 30, 280, 10);
        card.add(new Button(Icons.CLOSE, this::closeWithAnimation).setVariant(Button.Variant.GHOST))
                .setTooltip("Close")
                .setBounds(cx + cardW - 30, cy + 10, 20, 20);

        float leftX = cx + 18;
        float leftW = 200;
        float rowY = cy + 52;

        // Buttons
        card.add(new Button("Primary", () -> {}))
                .setBounds(leftX, rowY, 62, 20);
        card.add(new Button("Outline", () -> {}).setVariant(Button.Variant.SECONDARY))
                .setBounds(leftX + 68, rowY, 62, 20);
        card.add(new Button(Icons.WARNING, () -> {}).setVariant(Button.Variant.DANGER))
                .setTooltip("Danger!")
                .setBounds(leftX + 136, rowY, 24, 20);
        rowY += 28;

        // Toggle + checkbox
        card.add(new ToggleSwitch("HUD overlay", EasyGuiClient.DEMO_OVERLAY.isVisible(),
                        v -> EasyGuiClient.DEMO_OVERLAY.setVisible(v)))
                .setTooltip("Shows the EasyGUI watermark overlay in-game")
                .setBounds(leftX, rowY, leftW, 16);
        rowY += 22;
        card.add(new Checkbox("Animated check mark", true, v -> {}))
                .setBounds(leftX, rowY, leftW, 16);
        rowY += 24;

        // Slider driving the progress bar
        card.add(new Slider(0, 100, 1, demoProgress * 100, v -> {
                    demoProgress = (float) (v / 100.0);
                    progressBar.setProgress(demoProgress);
                }).setValueFormatter(v -> (int) v + "%", 28))
                .setBounds(leftX, rowY, leftW, 16);
        rowY += 22;
        card.add(progressBar.setProgress(demoProgress))
                .setBounds(leftX, rowY, leftW, 4);
        rowY += 14;

        // Text input
        card.add(new TextField("Type something…"))
                .setBounds(leftX, rowY, leftW, 20);
        rowY += 26;

        // Dropdown switching themes live
        boolean isLight = getTheme().surface == Theme.light().surface;
        card.add(new Dropdown(List.of("Dark theme", "Light theme"), isLight ? 1 : 0, index -> {
                    Theme picked = index == 1 ? Theme.light() : Theme.dark();
                    Theme.setDefault(picked);
                    setTheme(picked);
                }))
                .setBounds(leftX, rowY, leftW, 20);
        rowY += 28;

        // Icon strip + spinner
        float ix = leftX;
        for (var icon : List.of(Icons.SEARCH, Icons.GEAR, Icons.INFO, Icons.USER,
                Icons.FOLDER, Icons.COPY, Icons.MENU, Icons.ARROW_RIGHT)) {
            card.add(new Button(icon, () -> {}).setVariant(Button.Variant.GHOST).setPlaySound(false))
                    .setBounds(ix, rowY, 18, 18);
            ix += 21;
        }
        card.add(new Spinner()).setBounds(leftX + leftW - 18, rowY, 18, 18);

        // Right column: smooth-scrolling list
        float listX = cx + 240;
        float listW = cardW - (listX - cx) - 18;
        card.add(new Label("Scroll list").setMuted(true))
                .setBounds(listX, cy + 52, listW, 10);
        ScrollPanel list = card.add(new ScrollPanel());
        list.setCard(true);
        list.setShadow(false);
        list.setBackgroundColor(getTheme().surfaceVariant);
        list.setBounds(listX, cy + 66, listW, cardH - 84);
        float itemY = list.getY() + 6;
        for (int i = 1; i <= 25; i++) {
            final int n = i;
            list.add(new Label("List entry " + n))
                    .setBounds(listX + 10, itemY, listW - 70, 14);
            list.add(new Button("Run", () -> {}).setVariant(Button.Variant.SECONDARY).setPlaySound(false))
                    .setBounds(listX + listW - 48, itemY - 2, 36, 16);
            itemY += 22;
        }
    }
}
