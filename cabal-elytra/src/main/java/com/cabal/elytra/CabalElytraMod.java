package com.cabal.elytra;

import com.cabal.elytra.command.ElytraAdminCommands;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CabalElytraMod implements ModInitializer {
    public static final String MOD_ID = "cabal_elytra";
    public static final Logger LOGGER = LoggerFactory.getLogger("CabalElytra");

    @Override
    public void onInitialize() {
        ElytraAdminCommands.register();
        LOGGER.info("[CabalElytra] Loaded — Evoker's Wing max level={}",
                ElytraBalanceConfig.EVOKERS_WING_MAX_LEVEL);
    }
}
