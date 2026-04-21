package com.cabal.mobs.blazeboss;

public final class BlazeBossConfig {
    private BlazeBossConfig() {}

    public static final String BOSS_TAG = "cabal_blaze_boss";
    public static final String BOSS_HEALTH_SCALED_TAG = "cabal_blaze_boss_health_scaled";

    /** Random boss spawn radius around world origin (X/Z). */
    public static final int SPAWN_RADIUS_BLOCKS = 5000;
    /** Max random attempts to find a valid generated-chunk spawn spot. */
    public static final int SPAWN_PICK_ATTEMPTS = 64;
    /** Spawn near world top so the boss starts high in the sky. */
    public static final int SPAWN_FROM_TOP_OFFSET_BLOCKS = 2;

    /** Scale multiplier applied via Attributes.SCALE (10x normal blaze). */
    public static final double BOSS_SCALE = 10.0;

    /** Keep vanilla movement speed multiplier for logging/config consistency. */
    public static final double BOSS_SPEED_MULTIPLIER = 1.0;

    /** Max health multiplier relative to vanilla blaze defaults. */
    public static final double BOSS_HEALTH_MULTIPLIER = 10.0;

    /** Boss should take no fall damage while descending from high-altitude spawn. */
    public static final double BOSS_FALL_DAMAGE_MULTIPLIER = 0.0;
    public static final double BOSS_SAFE_FALL_DISTANCE = 4096.0;

    /** Higher target acquisition range so the boss can engage distant players. */
    public static final double BOSS_FOLLOW_RANGE = 160.0;

    /** Ticks the boss stays alive before forced despawn (15 minutes at 20 TPS). */
    public static final int DESPAWN_TICKS = 15 * 60 * 20;
}
