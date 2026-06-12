package com.stormcph.easygui.fabric;

import com.stormcph.easygui.EasyGui;
import net.fabricmc.api.ModInitializer;

public final class EasyGuiFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        EasyGui.init();
    }
}
