package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.Animation;
import com.stormcph.easygui.client.animation.Easing;
import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.config.ConfigValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A tabbed page container. A bar of text tabs runs along the top of the widget bounds;
 * each tab owns a content {@link Panel} ("page") that fills the area below. Only the
 * active page renders and receives input. The accent underline slides between tabs, and
 * page switches cross-fade with a small directional slide.
 *
 * <pre>{@code
 * Tabs tabs = card.add(new Tabs());
 * tabs.setBounds(card.getX() + 16, card.getY() + 16, 280, 200);
 *
 * Panel general = tabs.addTab("General");
 * general.add(new Button("Hello", () -> {}))
 *        .setBounds(tabs.getX() + 12, tabs.contentY() + 12, 120, 22);
 *
 * tabs.addTab("Advanced", page -> { ... });
 * tabs.persistTo(MyConfig.LAST_TAB); // reopen on the tab the user last had open
 * }</pre>
 *
 * <p>Pages span the full content area (the widget bounds minus the {@value #BAR_HEIGHT}px
 * bar) and are re-bounded automatically when the Tabs bounds change. Like everywhere else
 * in EasyGUI, the widgets <em>inside</em> a page are positioned in absolute GUI
 * coordinates by the caller ({@link #contentY()} helps with that).</p>
 */
@Environment(EnvType.CLIENT)
public class Tabs extends Panel {
    /** Height of the tab bar, in GUI pixels. */
    public static final float BAR_HEIGHT = 24f;

    private static final float TAB_PADDING = 10f;
    private static final float TAB_GAP = 2f;
    private static final float BAR_LEFT_PAD = 6f;
    private static final float UNDERLINE_HEIGHT = 2f;
    private static final float SLIDE_DISTANCE = 8f;

    private final List<String> titles = new ArrayList<>();
    private final List<Panel> pages = new ArrayList<>();
    private final List<SmoothValue> tabHover = new ArrayList<>();

    // Underline indicator glides in both position and width, so it stretches naturally
    // between tabs whose labels differ in length.
    private final SmoothValue indicatorX = new SmoothValue(0f, 16f);
    private final SmoothValue indicatorWidth = new SmoothValue(0f, 16f);
    private boolean indicatorInit;

    // One short transition shared by every switch: the outgoing page fades while the
    // incoming page fades in and slides from the direction of travel.
    private final Animation transition = new Animation(180, Easing.CUBIC_OUT);
    private Panel outgoingPage;
    private int direction = 1;

    private int selected;
    private IntConsumer onTabChange;
    private ConfigValue<Integer> binding;
    private boolean persistRestored;

    // ------------------------------------------------------------------
    // Tabs & pages
    // ------------------------------------------------------------------

    /**
     * Appends a tab and returns its content panel; add the page's widgets to it. The
     * panel is bounded to the content area automatically (now and on bounds changes).
     */
    public Panel addTab(String title) {
        Panel page = new Panel();
        add(page);
        titles.add(title);
        pages.add(page);
        tabHover.add(new SmoothValue(0f, 14f));
        page.setBounds(x, y + BAR_HEIGHT, width, Math.max(0f, height - BAR_HEIGHT));
        page.setVisible(pages.size() - 1 == selected);
        maybeRestorePersisted();
        return page;
    }

    /** Appends a tab and hands its content panel to {@code builder}; returns this for chaining. */
    public Tabs addTab(String title, Consumer<Panel> builder) {
        builder.accept(addTab(title));
        return this;
    }

    /** The content panel of tab {@code index}, or {@code null} when out of range. */
    public Panel getPage(int index) {
        return index >= 0 && index < pages.size() ? pages.get(index) : null;
    }

    public int getTabCount() {
        return pages.size();
    }

    public int getSelected() {
        return selected;
    }

    /**
     * Switches to tab {@code index} (clamped) with the usual transition. Fires the
     * {@link #setOnTabChange change callback} and writes the {@link #persistTo persisted}
     * value, exactly like a click on the bar. No-op if it is already the active tab.
     */
    public Tabs setSelected(int index) {
        select(index, true, true);
        return this;
    }

    /** Called with the new tab index after every switch (clicks and {@link #setSelected}). */
    public Tabs setOnTabChange(IntConsumer onTabChange) {
        this.onTabChange = onTabChange;
        return this;
    }

    /**
     * Binds the selected tab index to a config value: the initial tab is read from the
     * value (clamped to the available tabs) and every later switch is written back, so
     * the UI reopens on the tab the user last had open.
     *
     * <pre>{@code
     * public static final EasyConfig CONFIG = EasyConfig.of("mymod");
     * public static final ConfigValue<Integer> LAST_TAB = CONFIG.defineInt("ui.last_tab", 0);
     *
     * tabs.persistTo(MyConfig.LAST_TAB);
     * }</pre>
     *
     * <p>Best called after adding all tabs; if called earlier, a stored index beyond the
     * current tab count is re-applied as later {@link #addTab} calls make it reachable.
     * The restore itself never writes back, so a temporarily clamped index is not
     * destroyed before the remaining tabs exist.</p>
     */
    public Tabs persistTo(ConfigValue<Integer> value) {
        this.binding = value;
        this.persistRestored = false;
        maybeRestorePersisted();
        return this;
    }

    private void maybeRestorePersisted() {
        if (binding == null || persistRestored || pages.isEmpty()) {
            return;
        }
        Integer stored = binding.get();
        int target = stored == null ? 0 : stored;
        if (target < pages.size()) {
            persistRestored = true;
        }
        select(Mth.clamp(target, 0, pages.size() - 1), false, false);
    }

    private Panel activePage() {
        return pages.isEmpty() ? null : pages.get(selected);
    }

    /**
     * The single switching path. {@code animate} plays the fade+slide transition;
     * {@code notify} fires the change callback and the persistence write (false for the
     * initial config restore, which must not echo a clamped value back to disk).
     */
    private void select(int index, boolean animate, boolean notify) {
        if (pages.isEmpty()) {
            return;
        }
        index = Mth.clamp(index, 0, pages.size() - 1);
        if (index == selected) {
            return;
        }
        int old = selected;
        selected = index;
        direction = index > old ? 1 : -1;
        // Cut short any in-flight transition (unless its outgoing page is the one we are
        // returning to, in which case it must stay visible as the new active page).
        if (outgoingPage != null && outgoingPage != pages.get(selected)) {
            outgoingPage.setVisible(false);
        }
        Panel outgoing = pages.get(old);
        if (animate) {
            outgoingPage = outgoing;
            transition.start();
        } else {
            outgoing.setVisible(false);
            outgoingPage = null;
            transition.stop();
        }
        pages.get(selected).setVisible(true);
        if (notify) {
            if (binding != null) {
                binding.accept(selected);
            }
            if (onTabChange != null) {
                onTabChange.accept(selected);
            }
        }
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    @Override
    public Tabs setBounds(float x, float y, float width, float height) {
        super.setBounds(x, y, width, height);
        layoutPages();
        return this;
    }

    @Override
    public Tabs setPosition(float x, float y) {
        super.setPosition(x, y);
        layoutPages();
        return this;
    }

    @Override
    public Tabs setSize(float width, float height) {
        super.setSize(width, height);
        layoutPages();
        return this;
    }

    /** Top edge of the content area (just below the tab bar). */
    public float contentY() {
        return y + BAR_HEIGHT;
    }

    /** Height of the content area (the widget height minus the tab bar). */
    public float contentHeight() {
        return Math.max(0f, height - BAR_HEIGHT);
    }

    private void layoutPages() {
        for (Panel page : pages) {
            page.setBounds(x, contentY(), width, contentHeight());
        }
    }

    private float tabWidth(int index) {
        return Text2D.width(titles.get(index)) + TAB_PADDING * 2f;
    }

    /** Index of the tab label under the mouse, or -1 (also -1 outside the bar strip). */
    private int tabIndexAt(double mouseX, double mouseY) {
        if (mouseX < x || mouseX >= x + width || mouseY < y || mouseY >= y + BAR_HEIGHT) {
            return -1;
        }
        float tabX = x + BAR_LEFT_PAD;
        for (int i = 0; i < titles.size(); i++) {
            float tw = tabWidth(i);
            if (mouseX >= tabX && mouseX < tabX + tw) {
                return i;
            }
            tabX += tw + TAB_GAP;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        drawBackground(graphics);
        float t = transition.isStarted() ? transition.value() : 1f;
        if (t >= 1f && outgoingPage != null) {
            outgoingPage.setVisible(false);
            outgoingPage = null;
        }
        renderBar(graphics, mouseX, mouseY);
        renderPages(graphics, mouseX, mouseY, delta, t);
    }

    private void renderBar(GuiGraphics graphics, double mouseX, double mouseY) {
        Theme theme = theme();
        // Hairline separating the bar from the content area
        Render2D.fillRect(graphics, x, y + BAR_HEIGHT - 1f, width, 1f, theme.outline);

        int hoveredTab = enabled ? tabIndexAt(mouseX, mouseY) : -1;
        float tabX = x + BAR_LEFT_PAD;
        for (int i = 0; i < titles.size(); i++) {
            float tw = tabWidth(i);
            SmoothValue hover = tabHover.get(i);
            hover.setTarget(hoveredTab == i ? 1f : 0f);

            int color = i == selected ? theme.text
                    : ColorUtil.lerp(theme.textMuted, theme.text, hover.get() * 0.65f);
            if (!enabled) {
                color = ColorUtil.multiplyAlpha(color, 0.45f);
            }
            Text2D.drawVerticallyCentered(graphics, titles.get(i), tabX + TAB_PADDING, y,
                    BAR_HEIGHT - 1f, color);

            if (i == selected) {
                float uw = tw - TAB_PADDING;
                float ux = tabX + (tw - uw) / 2f;
                if (indicatorInit) {
                    indicatorX.setTarget(ux);
                    indicatorWidth.setTarget(uw);
                } else {
                    indicatorX.setInstant(ux);
                    indicatorWidth.setInstant(uw);
                    indicatorInit = true;
                }
            }
            tabX += tw + TAB_GAP;
        }

        if (indicatorInit && !pages.isEmpty()) {
            int accent = enabled ? theme.accent : ColorUtil.multiplyAlpha(theme.accent, 0.45f);
            Render2D.fillRoundedRect(graphics, indicatorX.get(), y + BAR_HEIGHT - UNDERLINE_HEIGHT - 0.5f,
                    indicatorWidth.get(), UNDERLINE_HEIGHT, UNDERLINE_HEIGHT / 2f, accent);
        }
    }

    private void renderPages(GuiGraphics graphics, double mouseX, double mouseY, float delta, float t) {
        Panel active = activePage();
        if (active == null) {
            return;
        }
        if (outgoingPage != null && t < 1f) {
            // Outgoing page only fades; an off-screen cursor suppresses its hover states.
            Render2D.pushAlpha(1f - t);
            outgoingPage.render(graphics, -1.0E7, -1.0E7, delta);
            Render2D.popAlpha();
        }
        if (t < 1f) {
            Render2D.pushAlpha(t);
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate((1f - t) * SLIDE_DISTANCE * direction, 0f, 0f);
            active.render(graphics, mouseX, mouseY, delta);
            pose.popPose();
            Render2D.popAlpha();
        } else {
            active.render(graphics, mouseX, mouseY, delta);
        }
    }

    // ------------------------------------------------------------------
    // Input (bar clicks switch tabs; everything else goes to the active page only)
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled) {
            return false;
        }
        if (button == 0) {
            int index = tabIndexAt(mouseX, mouseY);
            if (index >= 0) {
                select(index, true, true);
                return true;
            }
        }
        Panel active = activePage();
        return active != null && active.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!enabled) {
            return false;
        }
        Panel active = activePage();
        return active != null && active.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!enabled) {
            return false;
        }
        Panel active = activePage();
        return active != null && active.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!enabled) {
            return false;
        }
        Panel active = activePage();
        return active != null && active.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
