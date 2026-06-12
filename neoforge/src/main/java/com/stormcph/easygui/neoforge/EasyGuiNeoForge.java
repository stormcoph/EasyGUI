package com.stormcph.easygui.neoforge;

import com.stormcph.easygui.EasyGui;
import net.neoforged.fml.common.Mod;

@Mod(EasyGui.MOD_ID)
public final class EasyGuiNeoForge {
    public EasyGuiNeoForge() {
        EasyGui.init();
    }
}
