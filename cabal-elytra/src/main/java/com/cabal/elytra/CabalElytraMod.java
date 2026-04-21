package com.cabal.elytra;

import com.cabal.elytra.command.ElytraAdminCommands;
import com.cabal.elytra.config.CabalConfig;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CabalElytraMod implements ModInitializer {
    public static final String MOD_ID = "cabal_elytra";
    public static final Logger LOGGER = LoggerFactory.getLogger("CabalElytra");

    @Override
    public void onInitialize() {
        CabalConfig config = CabalConfig.load();
        if (!config.evokerEnabled()) {
            LOGGER.info("[CabalElytra] Evoker system disabled via cabal-config.json — skipping Evoker's Wing commands and hooks");
            return;
        }
        ElytraAdminCommands.register();
        LOGGER.info("[CabalElytra] Loaded — Evoker's Wing max level={}",
                ElytraBalanceConfig.EVOKERS_WING_MAX_LEVEL);
    }
}
