package com.stormcph.easygui.client.overlay;

import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.widget.Button;
import com.stormcph.easygui.client.widget.ContextMenu;
import com.stormcph.easygui.client.widget.Dropdown;
import com.stormcph.easygui.client.widget.Label;
import com.stormcph.easygui.client.widget.ModalDialog;
import com.stormcph.easygui.client.widget.NumberStepper;
import com.stormcph.easygui.client.widget.Panel;
import com.stormcph.easygui.client.widget.SegmentedControl;
import com.stormcph.easygui.client.widget.Slider;
import com.stormcph.easygui.client.widget.TextField;
import com.stormcph.easygui.client.widget.ToggleSwitch;
import com.stormcph.easygui.client.widget.Widget;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The HUD layout editor: every registered {@link HudOverlay} is shown in place (hidden
 * ones ghosted) and can be dragged around with snap guides — against the screen and
 * against other overlays — rescaled with the corner grip, or nudged pixel-by-pixel with
 * the arrow keys. Right-clicking an overlay opens a context menu with a live style
 * settings popup (scale, opacity, background, padding, shadow/outline/text shadow),
 * position reset, and hide/show. Positions <em>and styles</em> persist automatically for
 * overlays with a {@link HudOverlay#setPersistId persist id}.
 *
 * <p>The top toolbar also manages {@link HudLayouts named layout profiles}: pick one
 * from the dropdown to apply it, "Save as…" to snapshot the current layout, "Delete" to
 * remove the selected profile.</p>
 *
 * <p>Open it from anywhere — e.g. an "Edit HUD" button in your settings screen:</p>
 * <pre>{@code
 * card.add(new Button("Edit HUD", () -> minecraft.setScreen(new HudEditScreen(this))));
 * }</pre>
 */
@Environment(EnvType.CLIENT)
public class HudEditScreen extends EasyScreen {
    private static final float BAR_WIDTH = 360f;
    private static final float BAR_HEIGHT = 62f;

    /** Sentinel first dropdown row; real profile names start at index 1. */
    private final List<String> profileOptions = new ArrayList<>();
    private Dropdown profileDropdown;
    private StylePopup stylePopup;
    private float barX;
    private float barY;

    public HudEditScreen() {
        this(null);
    }

    /** @param parent screen to return to when the editor closes */
    public HudEditScreen(Screen parent) {
        super(Component.literal("HUD Editor"));
        setParentScreen(parent);
        setBackgroundBlur(false);
        setBackgroundDim(false);
    }

    @Override
    protected void build(Panel root) {
        barX = (width - BAR_WIDTH) / 2f;
        barY = 8f;
        Panel bar = root.add(new Panel().setCard(true).setFrosted(true));
        bar.setBounds(barX, barY, BAR_WIDTH, BAR_HEIGHT);
        bar.add(new Label("HUD editor").setScale(1.1f))
                .setBounds(barX + 14, barY + 7, 100, 11);
        bar.add(new Label("Drag to move • Right-click for options").setMuted(true))
                .setBounds(barX + 14, barY + 19, BAR_WIDTH - 90, 10);
        bar.add(new Button("Done", this::closeWithAnimation))
                .setBounds(barX + BAR_WIDTH - 58, barY + 6, 44, 18);

        refreshProfileOptions();
        profileDropdown = bar.add(new Dropdown(profileOptions, 0, this::onProfileSelected));
        profileDropdown.setBounds(barX + 14, barY + 32, 150, 18);
        bar.add(new Button("Save as…", this::openSaveProfileDialog).setVariant(Button.Variant.SECONDARY))
                .setBounds(barX + 170, barY + 32, 70, 18);
        bar.add(new Button("Delete", this::confirmDeleteProfile).setVariant(Button.Variant.SECONDARY))
                .setBounds(barX + 246, barY + 32, 56, 18);

        // Added last so it renders above the toolbar and gets input first
        stylePopup = root.add(new StylePopup());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The edit canvas: all overlays at their real positions (manager rendering is
        // suspended while this screen is open), hidden ones ghosted so they're placeable.
        for (HudOverlay overlay : OverlayManager.overlays()) {
            float x = OverlayEditor.resolveX(overlay);
            float y = OverlayEditor.resolveY(overlay);
            if (!overlay.isVisible()) {
                Render2D.pushAlpha(0.35f);
                overlay.renderStyledForEditor(graphics, x, y, partialTick);
                Render2D.popAlpha();
            } else {
                overlay.renderStyledForEditor(graphics, x, y, partialTick);
            }
        }
        // No hover chrome under the toolbar, the settings popup, or an open menu/dialog
        double chromeX = mouseX;
        double chromeY = mouseY;
        if (isPopupOpen() || insideToolbar(mouseX, mouseY)
                || (stylePopup.isVisible() && stylePopup.contains(mouseX, mouseY))) {
            chromeX = -1.0E7;
            chromeY = -1.0E7;
        }
        OverlayEditor.renderChrome(graphics, chromeX, chromeY, true);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (insideToolbar(mouseX, mouseY)) {
            return true; // keep the toolbar clear of canvas dragging
        }
        if (button == 1) {
            HudOverlay hit = OverlayEditor.pickOverlay(mouseX, mouseY, true);
            if (hit != null) {
                OverlayEditor.setSelected(hit);
                openOverlayMenu(hit, (float) mouseX, (float) mouseY);
                return true;
            }
            return false;
        }
        return OverlayEditor.mouseClicked(mouseX, mouseY, button, true);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (OverlayEditor.mouseDragged(mouseX, mouseY, true)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean dragged = OverlayEditor.mouseReleased(button);
        return super.mouseReleased(mouseX, mouseY, button) || dragged;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        Widget focused = getFocusedWidget();
        if (focused != null && focused.getScreen() == null) {
            setFocusedWidget(null); // e.g. the text field of a dialog that has since closed
            focused = null;
        }
        if (isPopupOpen() || focused != null) {
            // An open menu/dialog or a focused widget (text field, dropdown, …) owns the
            // keyboard; arrow keys must not nudge overlays underneath it.
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        float step = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? 5f : 1f;
        boolean handled = switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> OverlayEditor.nudge(-step, 0);
            case GLFW.GLFW_KEY_RIGHT -> OverlayEditor.nudge(step, 0);
            case GLFW.GLFW_KEY_UP -> OverlayEditor.nudge(0, -step);
            case GLFW.GLFW_KEY_DOWN -> OverlayEditor.nudge(0, step);
            default -> false;
        };
        return handled || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        OverlayEditor.finishDrag();
        super.removed();
    }

    // ------------------------------------------------------------------
    // Context menu
    // ------------------------------------------------------------------

    private void openOverlayMenu(HudOverlay overlay, float mouseX, float mouseY) {
        new ContextMenu()
                .addItem("Settings…", () -> stylePopup.openFor(overlay))
                .addItem("Reset position", () -> OverlayEditor.reset(overlay))
                .addDivider()
                .addItem(overlay.isVisible() ? "Hide" : "Show", () -> {
                    overlay.setVisible(!overlay.isVisible());
                    OverlayEditor.persistAll(overlay);
                })
                .open(this, mouseX, mouseY);
    }

    // ------------------------------------------------------------------
    // Layout profiles
    // ------------------------------------------------------------------

    private void refreshProfileOptions() {
        profileOptions.clear();
        profileOptions.add("Profiles…");
        profileOptions.addAll(HudLayouts.listProfiles());
    }

    private void onProfileSelected(int index) {
        if (index > 0 && index < profileOptions.size()) {
            HudLayouts.loadProfile(profileOptions.get(index));
        }
    }

    private void openSaveProfileDialog() {
        TextField nameField = new TextField("Profile name");
        ModalDialog dialog = new ModalDialog("Save layout profile")
                .setMessage("\n"); // reserves two blank lines the name field sits on
        dialog.addButton("Cancel", Button.Variant.SECONDARY, () -> setFocusedWidget(null));
        dialog.addButton("Save", Button.Variant.PRIMARY, () -> {
            String name = nameField.getText().trim();
            if (!name.isEmpty() && HudLayouts.saveProfile(name)) {
                refreshProfileOptions();
                profileDropdown.setSelectedIndex(profileOptions.indexOf(name));
            }
            setFocusedWidget(null);
        });
        dialog.show(this);
        // Place the field over the reserved message area (14 = card pad, 8 = title gap)
        nameField.setBounds(dialog.getX() + 14, dialog.getY() + 14 + Text2D.lineHeight() + 8,
                dialog.getWidth() - 28, 18);
        dialog.add(nameField);
        nameField.requestFocus();
    }

    private void confirmDeleteProfile() {
        int index = profileDropdown.getSelectedIndex();
        if (index <= 0 || index >= profileOptions.size()) {
            ModalDialog.alert(this, "No profile selected",
                    "Choose a profile in the dropdown first.", null);
            return;
        }
        String name = profileOptions.get(index);
        ModalDialog.confirmDanger(this, "Delete profile",
                "\"" + name + "\" will be deleted. This cannot be undone.", () -> {
                    HudLayouts.deleteProfile(name);
                    refreshProfileOptions();
                    profileDropdown.setSelectedIndex(0);
                });
    }

    private boolean insideToolbar(double mouseX, double mouseY) {
        return mouseX >= barX && mouseX < barX + BAR_WIDTH && mouseY >= barY && mouseY < barY + BAR_HEIGHT;
    }

    // ------------------------------------------------------------------
    // Style settings popup
    // ------------------------------------------------------------------

    /**
     * The compact per-overlay style editor opened via "Settings…": a floating card next
     * to the overlay whose widgets are bound straight to the overlay's live
     * {@link HudStyle}, so every change shows on the HUD immediately and is persisted.
     * It stays open until its Done button closes it (clicks on the card never fall
     * through to the canvas).
     */
    @Environment(EnvType.CLIENT)
    private final class StylePopup extends Panel {
        private static final float POPUP_WIDTH = 200f;
        private static final float POPUP_HEIGHT = 204f;

        StylePopup() {
            setCard(true);
            setVisible(false);
        }

        void openFor(HudOverlay overlay) {
            float screenW = HudEditScreen.this.width;
            float screenH = HudEditScreen.this.height;
            float overlayX = OverlayEditor.resolveX(overlay);
            float overlayY = OverlayEditor.resolveY(overlay);
            float px = overlayX + overlay.styledWidth() + 10f;
            if (px + POPUP_WIDTH > screenW - 4f) {
                px = overlayX - POPUP_WIDTH - 10f;
            }
            px = Mth.clamp(px, 4f, Math.max(4f, screenW - POPUP_WIDTH - 4f));
            float py = Mth.clamp(overlayY, 4f, Math.max(4f, screenH - POPUP_HEIGHT - 4f));
            setBounds(px, py, POPUP_WIDTH, POPUP_HEIGHT);
            rebuild(overlay);
            setVisible(true);
        }

        private void rebuild(HudOverlay overlay) {
            clearChildren();
            HudStyle style = overlay.getStyle();
            float left = x + 12f;
            float innerW = width - 24f;
            String title = overlay.getPersistId() != null
                    ? overlay.getPersistId() : overlay.getClass().getSimpleName();
            add(new Label(title)).setBounds(left, y + 10, innerW, 10);

            add(new Label("Scale").setMuted(true)).setBounds(left, y + 27, 48, 10);
            add(new Slider(0.5, 3.0, 0.05, style.getScale(), v -> {
                style.setScale(v.floatValue());
                OverlayEditor.persistAll(overlay);
            }).setValueFormatter(v -> String.format(Locale.ROOT, "%.2fx", v), 30f))
                    .setBounds(left + 52, y + 24, innerW - 52, 16);

            add(new Label("Opacity").setMuted(true)).setBounds(left, y + 45, 48, 10);
            add(new Slider(0.0, 1.0, 0.05, style.getOpacity(), v -> {
                style.setOpacity(v.floatValue());
                OverlayEditor.persistAll(overlay);
            }).setValueFormatter(v -> Math.round(v * 100) + "%", 30f))
                    .setBounds(left + 52, y + 42, innerW - 52, 16);

            add(new Label("Background").setMuted(true)).setBounds(left, y + 63, innerW, 10);
            add(new SegmentedControl(List.of("None", "Solid", "Frosted"),
                    style.getBackground().ordinal(), i -> {
                style.setBackground(HudStyle.Background.values()[i]);
                OverlayEditor.persistAll(overlay);
            })).setBounds(left, y + 75, innerW, 18);

            add(new Label("Padding").setMuted(true)).setBounds(left, y + 101, 48, 10);
            add(new NumberStepper(0, 20, 1, style.getPadding(), v -> {
                style.setPadding(v.floatValue());
                OverlayEditor.persistAll(overlay);
            })).setBounds(left + 52, y + 97, 80, 18);

            add(new ToggleSwitch("Shadow", style.isShadow(), v -> {
                style.setShadow(v);
                OverlayEditor.persistAll(overlay);
            })).setBounds(left, y + 121, innerW, 16);
            add(new ToggleSwitch("Outline", style.isOutline(), v -> {
                style.setOutline(v);
                OverlayEditor.persistAll(overlay);
            })).setBounds(left, y + 139, innerW, 16);
            add(new ToggleSwitch("Text shadow", style.isTextShadow(), v -> {
                style.setTextShadow(v);
                OverlayEditor.persistAll(overlay);
            })).setBounds(left, y + 157, innerW, 16);

            add(new Button("Done", () -> setVisible(false)).setVariant(Button.Variant.SECONDARY))
                    .setBounds(x + width - 62, y + height - 28, 50, 18);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (super.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            // Swallow clicks on the card body so they don't start a drag underneath
            return isVisible() && contains(mouseX, mouseY);
        }
    }
}
