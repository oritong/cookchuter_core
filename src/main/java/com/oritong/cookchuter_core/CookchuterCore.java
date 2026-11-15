package com.oritong.cookchuter_core;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(CookchuterCore.MOD_ID)
public final class CookchuterCore {
    public static final String MOD_ID = "cookchuter_core";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CookchuterCore() {
        LOGGER.info("Cookchuter Core initialized ({})", MOD_ID);
    }
}
