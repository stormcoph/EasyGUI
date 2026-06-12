package com.stormcph.easygui;

import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import com.stormcph.easygui.client.EasyGuiClient;

/**
 * Common entrypoint for the EasyGUI library.
 *
 * <p>EasyGUI is almost entirely a client-side library; {@link #init()} defers all real
 * initialization to {@link EasyGuiClient} on the physical client and does nothing on
 * dedicated servers (the client classes are never classloaded there).</p>
 */
public final class EasyGui {
    public static final String MOD_ID = "easygui";

    private EasyGui() {
    }

    public static void init() {
        EnvExecutor.runInEnv(Env.CLIENT, () -> EasyGuiClient::init);
    }
}
