package com.stormcph.easygui.client.config;

import com.google.gson.JsonElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A single typed, persisted setting inside an {@link EasyConfig}.
 *
 * <p>Implements {@link Supplier} and {@link Consumer}, so it binds straight to widgets —
 * the widget's change callback <em>is</em> the persistence:</p>
 *
 * <pre>{@code
 * static final ConfigValue<Boolean> SHOW_HUD = CONFIG.defineBool("hud.show", true);
 *
 * card.add(new ToggleSwitch("Show HUD", SHOW_HUD.get(), SHOW_HUD));
 * }</pre>
 *
 * <p>Writes mark the owning config dirty; it flushes to disk automatically (debounced)
 * and on game shutdown. {@link #onChange} listeners fire on every change, including
 * external-file reloads via {@link EasyConfig#reload()}.</p>
 */
@Environment(EnvType.CLIENT)
public final class ConfigValue<T> implements Supplier<T>, Consumer<T> {
    private final EasyConfig config;
    private final String key;
    private final T defaultValue;
    private final UnaryOperator<T> sanitizer;
    final Function<JsonElement, T> reader;
    final Function<T, JsonElement> writer;

    private T value;
    private List<Consumer<T>> listeners;

    ConfigValue(EasyConfig config, String key, T defaultValue, UnaryOperator<T> sanitizer,
                Function<JsonElement, T> reader, Function<T, JsonElement> writer) {
        this.config = config;
        this.key = key;
        this.defaultValue = defaultValue;
        this.sanitizer = sanitizer;
        this.reader = reader;
        this.writer = writer;
        this.value = defaultValue;
    }

    public String getKey() {
        return key;
    }

    public T getDefault() {
        return defaultValue;
    }

    @Override
    public T get() {
        return value;
    }

    /** Sets the value (sanitized/clamped) and schedules a save if it changed. */
    public void set(T newValue) {
        newValue = sanitize(newValue);
        if (Objects.equals(value, newValue)) {
            return;
        }
        value = newValue;
        config.markDirty();
        fireListeners();
    }

    /** {@link Consumer} hook — identical to {@link #set}. */
    @Override
    public void accept(T newValue) {
        set(newValue);
    }

    public void reset() {
        set(defaultValue);
    }

    /** Registers a change listener (fires after the value updates). Returns this for chaining. */
    public ConfigValue<T> onChange(Consumer<T> listener) {
        if (listeners == null) {
            listeners = new ArrayList<>(2);
        }
        listeners.add(listener);
        return this;
    }

    /** Initial assignment during define/load: no dirty-marking, no listeners yet. */
    void initialize(T loaded) {
        this.value = sanitize(loaded);
    }

    /** Assignment from {@link EasyConfig#reload()}: fires listeners but doesn't re-save. */
    void setLoaded(T loaded) {
        loaded = sanitize(loaded);
        if (Objects.equals(value, loaded)) {
            return;
        }
        value = loaded;
        fireListeners();
    }

    private T sanitize(T candidate) {
        return candidate == null ? defaultValue : sanitizer.apply(candidate);
    }

    private void fireListeners() {
        if (listeners != null) {
            for (Consumer<T> listener : listeners) {
                listener.accept(value);
            }
        }
    }
}
