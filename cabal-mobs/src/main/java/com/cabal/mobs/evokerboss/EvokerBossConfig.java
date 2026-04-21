package com.cabal.mobs.evokerboss;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EvokerBossConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalMobs/EvokerBossConfig");

    private EvokerBossConfig() {}

    public static final String BOSS_TAG = "cabal_evoker_boss";
    public static final String BOSS_PROXY_TAG = "cabal_evoker_boss_proxy";
    public static final String BOSS_PROXY_HEAD_TAG = "cabal_evoker_boss_proxy_head";
    public static final String BOSS_PROXY_TORSO_TAG = "cabal_evoker_boss_proxy_torso";
    public static final String BOSS_VEX_TAG = "cabal_evoker_boss_vex";
    public static final String BOSS_ATTRIBUTES_APPLIED_TAG = "cabal_evoker_boss_attributes_applied";

    /**
     * Boss spawn columns live in {@code config/cabal-mobs/evoker_boss_spawns.json} (up to 10 X/Z pairs, one random
     * column per scheduled spawn). When an entry omits {@code y}, Y is {@code world max Y} minus this offset.
     */
    public static final int SPAWN_FROM_TOP_OFFSET_BLOCKS = 1;

    /** Scale multiplier applied via Attributes.SCALE (10x normal evoker). */
    public static final double BOSS_SCALE = 10.0;
    /**
     * Extra collision inflation to better match the rendered giant model.
     * This fixes top-half misses from model/hitbox mismatch at large scales.
     */
    public static final double HITBOX_WIDTH_MULTIPLIER = 2.40;
    public static final double HITBOX_HEIGHT_MULTIPLIER = 2.90;
    /** Proxy multipart hurtboxes to make giant upper-body hits consistent. */
    public static final float PROXY_HEAD_WIDTH = 7.2f;
    public static final float PROXY_HEAD_HEIGHT = 9.0f;
    public static final float PROXY_TORSO_WIDTH = 5.0f;
    public static final float PROXY_TORSO_HEIGHT = 12.0f;
    public static final double PROXY_HEAD_Y_OFFSET = 14.0;
    public static final double PROXY_TORSO_Y_OFFSET = 2.0;
    public static final double PROXY_MAX_HEALTH = 2048.0;
    /** Keep vanilla movement speed multiplier for logging/config consistency. */
    public static final double BOSS_SPEED_MULTIPLIER = 1.0;
    /**
     * Step height attribute for the boss (blocks): how far it can auto-step when blocked horizontally.
     * Vanilla default is 0.6; game maximum is 10.0. Large hitboxes need more or they snag on terrain;
     * this does not add Spider-style vertical wall climbing.
     */
    public static final double BOSS_STEP_HEIGHT = 10.0;
    /** Max health multiplier relative to vanilla evoker defaults. */
    public static final double BOSS_HEALTH_MULTIPLIER = 70.0;

    /** Keep high-altitude spawn safe from fall damage while dropping in. */
    public static final double BOSS_FALL_DAMAGE_MULTIPLIER = 0.0;
    public static final double BOSS_SAFE_FALL_DISTANCE = 4096.0;
    /** Higher target acquisition range so boss can engage distant players. */
    public static final double BOSS_FOLLOW_RANGE = 160.0;

    /**
     * Minimum real-world seconds between automatic spawn <em>attempts</em> (rolling window).
     * One roll per {@link EvokerBossScheduler} tick batch (~1/s).
     * Default is hourly; override with:
     * - JVM property: {@code -Dcabal.evoker.spawnIntervalSeconds=<n>}
     * - env var: {@code CABAL_EVOKER_SPAWN_INTERVAL_SECONDS=<n>}
     */
    public static final int AUTOMATIC_SPAWN_INTERVAL_SECONDS = resolveAutomaticSpawnIntervalSeconds();

    /** Ticks the boss stays alive before forced despawn (15 minutes at 20 TPS). */
    public static final int DESPAWN_TICKS = 15 * 60 * 20;

    /** Boss phase system: every 10% health loss triggers a 20s intermission (AI remains enabled). */
    public static final int PHASE_HEALTH_STEP_PERCENT = 10;
    public static final int PHASE_INTERMISSION_DURATION_TICKS = 20 * 20;
    /** After intermission, enable wither aura around the boss. */
    public static final double WITHER_AURA_RADIUS_BLOCKS = 30.0;
    /** Temporary phase escalation window for wither aura after each intermission ends. */
    public static final int PHASE_WITHER_AURA_ESCALATION_DURATION_TICKS = 20 * 20;
    /** Enrage tuning: check on an interval (not every tick) and keep aura always on once enraged. */
    public static final int ENRAGE_CHECK_INTERVAL_TICKS = 10;
    public static final float ENRAGE_HEALTH_THRESHOLD_RATIO = 0.50f;
    public static final double ENRAGED_WITHER_AURA_RADIUS_BLOCKS = 36.0;
    public static final double ENRAGED_PHASE_WITHER_AURA_RADIUS_BLOCKS = 44.0;
    public static final int WITHER_AURA_APPLY_INTERVAL_TICKS = 20;
    public static final int WITHER_AURA_EFFECT_DURATION_TICKS = 60;
    public static final int WITHER_AURA_EFFECT_AMPLIFIER = 0;
    public static final int WITHER_AURA_BURN_FIRE_TICKS = 80;
    public static final float WITHER_AURA_BURN_DAMAGE = 2.0f;
    public static final int WITHER_AURA_PARTICLE_EVERY_TICKS = 10;
    public static final int WITHER_AURA_RING_POINTS = 48;

    /** Radius around the boss column for purging stray {@link #BOSS_VEX_TAG} vexes on clear/restart. */
    public static final double BOSS_VEX_PURGE_RADIUS_BLOCKS = 128.0;

    /** Movement speed multiplier for vexes owned by the tagged boss (vanilla summons). */
    public static final double BOSS_VEX_SPEED_MULTIPLIER = 2.35;
    /** Game ticks between boss vex summon casts (vanilla evoker uses 340). */
    public static final int BOSS_VEX_SUMMON_INTERVAL_TICKS = 280;
    /** Spawn one extra vex per cast on top of vanilla's three. */
    public static final boolean BOSS_VEX_SPAWN_ONE_EXTRA_PER_CAST = true;

    /** Lightweight server tick-pressure telemetry (production-safe periodic logging). */
    public static final int TICK_PRESSURE_LOG_INTERVAL_TICKS = 1200; // 60s at 20 TPS
    public static final double TICK_PRESSURE_WARN_MSPT = 45.0;

    /** Throttled `[BossInfra]` snapshot while a boss is active (0 disables). */
    public static final int BOSS_INFRA_LOG_INTERVAL_TICKS = 600;
    /** Horizontal radius around the boss AABB for counting non-spectator players in infra logs. */
    public static final double BOSS_INFRA_PLAYER_NEAR_RADIUS_BLOCKS = 128.0;

    /** Temporary diagnostics for server-side hit registration. */
    public static final boolean DEBUG_HIT_TRACE = false;
    public static final int HIT_TRACE_LOG_EVERY_TICKS = 1200;
    /**
     * Draw periodic particle wireframes for the evoker plus the two proxy hitboxes.
     * Proxies stay invisible regardless.
     */
    public static final boolean DEBUG_HITBOX_PARTICLES = false;
    public static final int DEBUG_HITBOX_PARTICLE_EVERY_TICKS = 40;
    /** Rare: show glowing named husk proxies (not needed for normal play). */
    public static final boolean DEBUG_HITBOX_VISUALS = false;
    /** Temporary diagnostic: keep boss fixed in place to isolate movement desync. */
    public static final boolean ROOTED_DIAGNOSTIC = false;

    /**
     * While a player overlaps the boss hitbox (evoker + proxy parts), refresh this many fire ticks each tick.
     * After they leave, vanilla fire countdown continues for a few seconds of burn.
     */
    public static final int CONTACT_BURN_FIRE_TICKS = 80;
    /** Extra mob-attack damage at most once per this many game ticks per player while overlapping the boss. */
    public static final int CONTACT_BURN_DAMAGE_INTERVAL_TICKS = 15;
    public static final float CONTACT_BURN_EXTRA_DAMAGE = 2.0f;
    /** Padding around boss+proxy AABB for the spatial query that finds players eligible for contact burn. */
    public static final double CONTACT_BURN_PLAYER_SEARCH_INFLATE_BLOCKS = 6.0;
    /**
     * Replenish proxy max-health / heal every N game ticks. Positions still snap to the boss every server tick
     * so clients do not desync.
     */
    public static final int PROXY_REPLENISH_EVERY_TICKS = 2;

    /**
     * Elemental damage multipliers for the evoker boss.
     * Multiplier is applied to the elemental portion of damage (equal to physical damage).
     * 0.5 = 50% resistance, 1.5 = 50% vulnerability, 1.0 = neutral.
     */
    public static final float ELEMENTAL_FIRE_MULTIPLIER = 0.5f;
    public static final float ELEMENTAL_LIGHTNING_MULTIPLIER = 1.5f;
    public static final boolean DEBUG_ELEMENTAL_DAMAGE = false;

    /** Evoker boss loot table drops. */
    public static final int LOOT_ARROWS_MIN = 8;
    public static final int LOOT_ARROWS_MAX = 16;
    public static final int LOOT_ARROWS_LOOTING_EXTRA = 4;
    public static final int LOOT_FIREWORKS_MIN = 4;
    public static final int LOOT_FIREWORKS_MAX = 8;
    public static final int LOOT_FIREWORKS_LOOTING_EXTRA = 2;
    /** Chance to drop exactly one Evoker Eye on boss death (independent of looting). */
    public static final float LOOT_EVOKER_EYE_DROP_CHANCE = 0.30f;

    /** CustomModelData for Evoker Eye item (see {@code cabal_elytra} for Evoker's Wing). */
    public static final int EVOKER_EYE_MODEL_ID = 930001;

    private static int resolveAutomaticSpawnIntervalSeconds() {
        final int defaultSeconds = 3600;
        final String propertyKey = "cabal.evoker.spawnIntervalSeconds";
        String property = System.getProperty("cabal.evoker.spawnIntervalSeconds");
        if (property != null && !property.isBlank()) {
            return parsePositiveIntOrDefault(property, defaultSeconds, propertyKey);
        }
        final String envKey = "CABAL_EVOKER_SPAWN_INTERVAL_SECONDS";
        String env = System.getenv("CABAL_EVOKER_SPAWN_INTERVAL_SECONDS");
        if (env != null && !env.isBlank()) {
            return parsePositiveIntOrDefault(env, defaultSeconds, envKey);
        }
        return defaultSeconds;
    }

    private static int parsePositiveIntOrDefault(String raw, int fallback, String sourceKey) {
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed > 0) {
                return parsed;
            }
            LOGGER.warn(
                    "[CabalMobs] Ignoring non-positive spawn interval override {}='{}'; using fallback {} seconds",
                    sourceKey,
                    raw,
                    fallback
            );
            return fallback;
        } catch (NumberFormatException ignored) {
            LOGGER.warn(
                    "[CabalMobs] Ignoring non-numeric spawn interval override {}='{}'; using fallback {} seconds",
                    sourceKey,
                    raw,
                    fallback
            );
            return fallback;
        }
    }
}
