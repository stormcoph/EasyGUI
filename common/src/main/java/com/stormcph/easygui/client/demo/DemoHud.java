package com.stormcph.easygui.client.demo;

import com.stormcph.easygui.client.chart.Sparkline;
import com.stormcph.easygui.client.overlay.AnimatedListOverlay;
import com.stormcph.easygui.client.overlay.Anchor;
import com.stormcph.easygui.client.overlay.HudStyle;
import com.stormcph.easygui.client.overlay.OverlayManager;
import com.stormcph.easygui.client.overlay.TextElement;
import com.stormcph.easygui.client.overlay.WidgetHostOverlay;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.stat.Metrics;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.List;

/**
 * The demo HUD elements beyond the watermark: a placeholder-template info line, an
 * animated module list, and a live FPS sparkline hosted on the HUD. All register at
 * client init with persist ids, so they can be dragged, restyled, and rescaled in the
 * HUD editor; visibility is toggled from the demo screen's HUD tab and persists via
 * {@link DemoConfig}.
 */
@Environment(EnvType.CLIENT)
public final class DemoHud {
    public static TextElement info;
    public static AnimatedListOverlay modules;
    public static WidgetHostOverlay fpsChart;

    private DemoHud() {
    }

    /** Called once from EasyGUI's client init, after the overlay manager is up. */
    public static void register() {
        if (info != null) {
            return;
        }

        // Template text element: two-tone label/value coloring, frosted plate
        info = new TextElement("FPS {fps} · {coords} · {facing} · {clock}");
        info.setColorMode(TextElement.ColorMode.TWO_TONE);
        info.getStyle()
                .setBackground(HudStyle.Background.FROSTED)
                .setPadding(4f)
                .setRadius(6f)
                .setTextShadow(true);
        info.setVisible(DemoConfig.HUD_TEXT.get());
        info.setAnchor(Anchor.TOP_CENTER).setOffsets(0, 6).setPersistId("demo_info");
        OverlayManager.register(info);

        // Classic module list: width-sorted, per-row backgrounds, hue-stepped colors
        modules = new AnimatedListOverlay();
        modules.setSortMode(AnimatedListOverlay.SortMode.WIDTH_DESC)
                .setRowBackground(true)
                .setColorProvider((index, label) -> ColorUtil.hsv(0.55f + index * 0.035f, 0.6f, 1f));
        modules.updateFromCollection(List.of("Sprint", "Fullbright", "Coordinates", "FPS Graph", "Keystrokes"));
        modules.setVisible(DemoConfig.HUD_MODULES.get());
        modules.setAnchor(Anchor.CENTER_RIGHT).setOffsets(6, 0).setPersistId("demo_modules");
        OverlayManager.register(modules);

        // A chart widget on the HUD, via WidgetHostOverlay
        fpsChart = new WidgetHostOverlay(90, 32);
        Sparkline spark = new Sparkline(Metrics.fps().series())
                .setVariant(Sparkline.Variant.AREA)
                .setTimeWindow(15)
                .setShowValue(true);
        spark.setBounds(0, 0, 90, 32);
        fpsChart.getRoot().add(spark);
        fpsChart.getStyle()
                .setBackground(HudStyle.Background.FROSTED)
                .setPadding(4f)
                .setRadius(6f)
                .setOutline(true);
        fpsChart.setVisible(DemoConfig.HUD_CHART.get());
        fpsChart.setAnchor(Anchor.BOTTOM_RIGHT).setOffsets(6, 6).setPersistId("demo_fps_chart");
        OverlayManager.register(fpsChart);
    }
}
