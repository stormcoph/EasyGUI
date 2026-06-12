package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.Animation;
import com.stormcph.easygui.client.animation.Easing;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * A modal confirm/alert dialog drawn on the screen's popup layer.
 *
 * <p>The dialog is a centered card with a title, a word-wrapped message, and a
 * right-aligned row of {@link Button}s. While open it owns the popup layer: everything
 * behind it is dimmed and blocked, clicks outside the card play a brief attention shake
 * instead of dismissing, and Escape (or a button with no action, like "Cancel") closes
 * the dialog without running anything. Build one fluently and call
 * {@link #show(EasyScreen)}, or use the {@link #alert}, {@link #confirm} and
 * {@link #confirmDanger} shortcuts.</p>
 *
 * <pre>{@code
 * ModalDialog.confirmDanger(screen, "Delete profile",
 *         "This will permanently delete \"Default\". This cannot be undone.",
 *         () -> profiles.delete("Default"));
 * }</pre>
 *
 * <p>Only one modal can be open at a time (the screen has a single popup slot), and an
 * instance is single-use — once it has closed, create a new one instead of re-showing
 * it. Configure title, message and buttons before calling {@code show}; the layout is
 * computed at that point.</p>
 */
@Environment(EnvType.CLIENT)
public class ModalDialog extends Panel {
    private static final float MAX_WIDTH = 300f;
    private static final float SCREEN_MARGIN = 40f;
    private static final float PAD = 14f;
    private static final float TITLE_GAP = 8f;
    private static final float LINE_GAP = 2f;
    private static final float BUTTON_GAP = 12f;
    private static final float BUTTON_HEIGHT = 20f;
    private static final float BUTTON_SPACING = 8f;
    private static final float MIN_BUTTON_WIDTH = 64f;
    private static final float DIM_STRENGTH = 0.6f;

    private final String title;
    private String message = "";

    private final List<Button> buttons = new ArrayList<>();
    private final List<String> buttonLabels = new ArrayList<>();
    private List<String> messageLines = new ArrayList<>();

    private final Animation openAnim = new Animation(180, Easing.CUBIC_OUT);
    private final Animation shakeAnim = new Animation(320, Easing.LINEAR);
    private boolean closing;
    private boolean removed;

    public ModalDialog(String title) {
        this.title = title;
        setCard(true);
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    /** Body text below the title; word-wrapped to the card width when shown. */
    public ModalDialog setMessage(String message) {
        this.message = message == null ? "" : message;
        return this;
    }

    /**
     * Adds a button to the bottom row. Buttons are laid out right-aligned in add-order,
     * so the last button added sits at the right edge (the conventional spot for the
     * primary action). Pressing any button runs its action (may be {@code null} for a
     * plain "Cancel"), then closes the dialog with the reverse pop animation.
     */
    public ModalDialog addButton(String label, Button.Variant variant, Runnable action) {
        Button button = new Button(label, () -> {
            if (action != null) {
                action.run();
            }
            requestClose();
        });
        button.setVariant(variant);
        add(button);
        buttons.add(button);
        buttonLabels.add(label);
        return this;
    }

    /** Adds the dialog to the screen's root, centers it, and opens it on the popup layer. */
    public ModalDialog show(EasyScreen screen) {
        screen.getRoot().add(this);
        layout(screen);
        screen.openPopup(this);
        openAnim.start(0f, 1f);
        return this;
    }

    // ------------------------------------------------------------------
    // Static conveniences
    // ------------------------------------------------------------------

    /** Information dialog with a single primary "OK" button. Escape = same as OK but runs no action. */
    public static ModalDialog alert(EasyScreen screen, String title, String message, Runnable onOk) {
        return new ModalDialog(title)
                .setMessage(message)
                .addButton("OK", Button.Variant.PRIMARY, onOk)
                .show(screen);
    }

    /** "Cancel" / "Confirm" dialog. Escape or Cancel closes without running anything. */
    public static ModalDialog confirm(EasyScreen screen, String title, String message, Runnable onConfirm) {
        return new ModalDialog(title)
                .setMessage(message)
                .addButton("Cancel", Button.Variant.SECONDARY, null)
                .addButton("Confirm", Button.Variant.PRIMARY, onConfirm)
                .show(screen);
    }

    /** {@link #confirm} with a danger-colored confirm button, for destructive actions. */
    public static ModalDialog confirmDanger(EasyScreen screen, String title, String message, Runnable onConfirm) {
        return new ModalDialog(title)
                .setMessage(message)
                .addButton("Cancel", Button.Variant.SECONDARY, null)
                .addButton("Confirm", Button.Variant.DANGER, onConfirm)
                .show(screen);
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private void layout(EasyScreen screen) {
        float w = Math.min(MAX_WIDTH, screen.width - SCREEN_MARGIN);
        messageLines = wrapMessage(w - PAD * 2);

        float lineH = Text2D.lineHeight();
        float h = PAD + lineH; // title row
        if (!messageLines.isEmpty()) {
            h += TITLE_GAP + messageLines.size() * (lineH + LINE_GAP) - LINE_GAP;
        }
        if (!buttons.isEmpty()) {
            h += BUTTON_GAP + BUTTON_HEIGHT;
        }
        h += PAD;

        setBounds((screen.width - w) / 2f, (screen.height - h) / 2f, w, h);

        // Button row, right-aligned, last-added at the right edge
        float bx = x + width - PAD;
        float by = y + height - PAD - BUTTON_HEIGHT;
        for (int i = buttons.size() - 1; i >= 0; i--) {
            float bw = Math.max(MIN_BUTTON_WIDTH, Text2D.width(buttonLabels.get(i)) + 28f);
            bx -= bw;
            buttons.get(i).setBounds(bx, by, bw, BUTTON_HEIGHT);
            bx -= BUTTON_SPACING;
        }
    }

    /** Greedy word wrap on spaces; words wider than the card are hard-broken. */
    private List<String> wrapMessage(float maxWidth) {
        List<String> lines = new ArrayList<>();
        if (message.isEmpty()) {
            return lines;
        }
        for (String paragraph : message.split("\n", -1)) {
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (word.isEmpty()) {
                    continue;
                }
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (Text2D.width(candidate) <= maxWidth) {
                    current = new StringBuilder(candidate);
                    continue;
                }
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                }
                while (Text2D.width(word) > maxWidth && word.length() > 1) {
                    int cut = word.length() - 1;
                    while (cut > 1 && Text2D.width(word.substring(0, cut)) > maxWidth) {
                        cut--;
                    }
                    lines.add(word.substring(0, cut));
                    word = word.substring(cut);
                }
                current = new StringBuilder(word);
            }
            lines.add(current.toString());
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Rendering (everything happens in the top pass, above the whole tree)
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        // Intentionally empty: the dim, the card, and the buttons all draw in
        // renderTop so the dialog sits above every widget in the main tree.
    }

    @Override
    public void renderTop(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        if (removed) {
            return;
        }
        if (closing && openAnim.isFinished()) {
            finishClose();
            return;
        }
        float t = openAnim.value();
        if (t <= 0.004f) {
            return;
        }
        Theme theme = theme();
        EasyScreen screen = getScreen();

        // Full-screen dim behind the card
        if (screen != null) {
            Render2D.fillRect(graphics, 0, 0, screen.width, screen.height,
                    ColorUtil.multiplyAlpha(theme.screenDim, DIM_STRENGTH * t));
        }

        // Pop-in: scale from the card center plus a fade; shake is a plain x offset
        var pose = graphics.pose();
        pose.pushPose();
        float scale = 0.92f + 0.08f * t;
        float cx = x + width / 2f;
        float cy = y + height / 2f;
        pose.translate(shakeOffset(), 0, 0);
        pose.translate(cx * (1 - scale), cy * (1 - scale), 0);
        pose.scale(scale, scale, 1f);
        Render2D.pushAlpha(t);

        drawBackground(graphics); // Panel's card: shadow, surface fill, outline

        Text2D.draw(graphics, title, x + PAD, y + PAD, theme.text);
        float lineH = Text2D.lineHeight();
        float ly = y + PAD + lineH + TITLE_GAP;
        for (String line : messageLines) {
            Text2D.draw(graphics, line, x + PAD, ly, theme.textMuted);
            ly += lineH + LINE_GAP;
        }

        // The buttons render here (not in the main pass) with the real mouse position,
        // so hover/press states work while the popup layer is open.
        for (Widget child : getChildren()) {
            child.render(graphics, mouseX, mouseY, delta);
        }

        Render2D.popAlpha();
        pose.popPose();
    }

    /** Damped sine used for the "you must answer this" shake on outside clicks. */
    private float shakeOffset() {
        if (!shakeAnim.isRunning()) {
            return 0f;
        }
        float p = shakeAnim.progress();
        return (float) Math.sin(p * Math.PI * 5) * 3f * (1f - p);
    }

    // ------------------------------------------------------------------
    // Closing
    // ------------------------------------------------------------------

    /** Plays the reverse pop animation, then removes the dialog and frees the popup layer. */
    private void requestClose() {
        if (closing) {
            return;
        }
        closing = true;
        openAnim.start(openAnim.value(), 0f);
    }

    private void finishClose() {
        if (removed) {
            return;
        }
        removed = true;
        visible = false;
        EasyScreen screen = getScreen();
        if (screen != null) {
            screen.closePopup(this);
        }
        // We are inside the parent's render iteration right now, so the actual
        // removal is deferred to the client task queue.
        Panel parentPanel = getParent();
        Minecraft.getInstance().tell(() -> {
            if (parentPanel != null) {
                parentPanel.remove(this);
            }
        });
    }

    // ------------------------------------------------------------------
    // Input (the modal owns the popup layer, so clicks arrive here first)
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Clicks route through popupMouseClicked while open; once closing, the card is
        // inert. This blocks the normal tree path from reaching the buttons directly.
        return false;
    }

    @Override
    public boolean popupMouseClicked(double mouseX, double mouseY, int button) {
        if (closing) {
            return true; // swallow everything while the close animation plays
        }
        if (contains(mouseX, mouseY)) {
            super.mouseClicked(mouseX, mouseY, button); // forward to the buttons
            return true;
        }
        // Outside the card: a modal refuses to be click-dismissed — shake instead.
        shakeAnim.start();
        return true;
    }

    @Override
    public boolean popupMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return true; // block scroll from reaching panels behind the modal
    }

    /** Escape: animated close without running any button action. */
    @Override
    public void dismissPopup() {
        requestClose();
    }
}
