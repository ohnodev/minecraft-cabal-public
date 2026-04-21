package com.cabal.mobs;

import com.cabal.mobs.elemental.ElementalArrowCommand;
import com.cabal.mobs.evokerboss.EvokerBossConfig;
import com.cabal.mobs.evokerboss.EvokerBossScheduler;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CabalMobsMod implements ModInitializer {
    public static final String MOD_ID = "cabal-mobs";
    public static final Logger LOGGER = LoggerFactory.getLogger("CabalMobs");

    public static final float BABY_CREEPER_CHANCE = 0.30f;
    public static final double BABY_CREEPER_SCALE = 0.5;
    public static final double BABY_CREEPER_SPEED_MULTIPLIER = 1.0; // +100% = 2x base

    @Override
    public void onInitialize() {
        LOGGER.info("[CabalMobs] Loaded — baby creeper chance={}%, scale={}x, speed={}x",
                (int) (BABY_CREEPER_CHANCE * 100),
                BABY_CREEPER_SCALE,
                BABY_CREEPER_SPEED_MULTIPLIER + 1.0);

        EvokerBossScheduler.register();
        ElementalArrowCommand.register();
        LOGGER.info("[CabalMobs] Evoker boss scheduler registered (scale={}x, speed={}x, health={}x, despawn={}s)",
                String.format("%.2f", EvokerBossConfig.BOSS_SCALE),
                String.format("%.2f", EvokerBossConfig.BOSS_SPEED_MULTIPLIER),
                String.format("%.2f", EvokerBossConfig.BOSS_HEALTH_MULTIPLIER),
                EvokerBossConfig.DESPAWN_TICKS / 20);
        LOGGER.info("[CabalMobs] Elemental arrows registered (fire_mult={}, lightning_mult={})",
                String.format("%.2f", EvokerBossConfig.ELEMENTAL_FIRE_MULTIPLIER),
                String.format("%.2f", EvokerBossConfig.ELEMENTAL_LIGHTNING_MULTIPLIER));
    }
}
