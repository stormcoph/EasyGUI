package com.stormcph.easygui.client.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.widget.Panel;
import com.stormcph.easygui.client.widget.Widget;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;

/**
 * Serializes a screen's live widget tree to JSON — the EasyGUI analog of the web's
 * accessibility tree. Agents and tools use it to verify layout numerically (bounds,
 * visibility, state) and to find click targets without needing vision.
 *
 * <p>Besides core {@link Widget} state, each node carries a {@code props} object of
 * values probed from conventional getters ({@code getText}, {@code getValue},
 * {@code getSelectedOption}, ...), so common widgets expose their content without
 * every class having to opt in.</p>
 */
@Environment(EnvType.CLIENT)
public final class WidgetTreeDump {
    /** Zero-arg getters probed on every widget; first non-null simple value per name wins. */
    private static final String[] PROBES = {
            "getText", "getLabel", "getValue", "getSelectedOption", "getSelected",
            "getSelectedIndex", "getTitle", "getMin", "getMax", "getProgress",
    };

    private WidgetTreeDump() {
    }

    /** Dumps {@code screen} (its widget tree if it is an {@link EasyScreen}) to JSON. */
    public static JsonObject dump(Screen screen) {
        JsonObject out = new JsonObject();
        if (screen == null) {
            out.addProperty("screen", (String) null);
            return out;
        }
        out.addProperty("screen", screen.getClass().getName());
        out.addProperty("title", screen.getTitle().getString());
        out.addProperty("width", screen.width);
        out.addProperty("height", screen.height);
        if (screen instanceof EasyScreen easy) {
            Widget focused = easy.getFocusedWidget();
            out.addProperty("focused", focused != null ? nodeName(focused) : null);
            out.addProperty("popupOpen", easy.isPopupOpen());
            out.add("root", dumpWidget(easy.getRoot()));
        }
        return out;
    }

    private static JsonObject dumpWidget(Widget widget) {
        JsonObject node = new JsonObject();
        node.addProperty("type", widget.getClass().getSimpleName());
        if (widget.getId() != null) {
            node.addProperty("id", widget.getId());
        }
        node.addProperty("x", widget.getX());
        node.addProperty("y", widget.getY());
        node.addProperty("width", widget.getWidth());
        node.addProperty("height", widget.getHeight());
        node.addProperty("visible", widget.isVisible());
        node.addProperty("enabled", widget.isEnabled());
        if (widget.isFocused()) {
            node.addProperty("focused", true);
        }
        if (widget.isHovered()) {
            node.addProperty("hovered", true);
        }
        JsonObject props = probeProps(widget);
        if (props.size() > 0) {
            node.add("props", props);
        }
        if (widget instanceof Panel panel && !panel.getChildren().isEmpty()) {
            JsonArray children = new JsonArray();
            for (Widget child : panel.getChildren()) {
                children.add(dumpWidget(child));
            }
            node.add("children", children);
        }
        return node;
    }

    /** Collects simple values (strings, numbers, booleans, enums) from conventional getters. */
    private static JsonObject probeProps(Widget widget) {
        JsonObject props = new JsonObject();
        for (String name : PROBES) {
            try {
                Method m = widget.getClass().getMethod(name);
                if (!isSimple(m.getReturnType())) {
                    continue;
                }
                Object value = m.invoke(widget);
                if (value == null) {
                    continue;
                }
                String key = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                if (value instanceof Number n) {
                    props.addProperty(key, n);
                } else if (value instanceof Boolean b) {
                    props.addProperty(key, b);
                } else {
                    props.addProperty(key, value.toString());
                }
            } catch (ReflectiveOperationException ignored) {
                // Widget doesn't have this getter - fine.
            }
        }
        return props;
    }

    private static boolean isSimple(Class<?> type) {
        return type == String.class || type.isPrimitive() || type.isEnum()
                || Number.class.isAssignableFrom(type) || type == Boolean.class;
    }

    private static String nodeName(Widget widget) {
        return widget.getId() != null ? widget.getId() : widget.getClass().getSimpleName();
    }

    /** Depth-first search for a widget by its {@link Widget#getId() id}. */
    public static Widget findById(Widget widget, String id) {
        if (id.equals(widget.getId())) {
            return widget;
        }
        if (widget instanceof Panel panel) {
            for (Widget child : panel.getChildren()) {
                Widget found = findById(child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
