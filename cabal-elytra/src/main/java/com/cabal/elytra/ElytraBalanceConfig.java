package com.cabal.elytra;

/** Tunables for Evoker's Wing (server-side). */
public final class ElytraBalanceConfig {
    private ElytraBalanceConfig() {}

    /** Evoker's Wing — max upgrade tier (L10 = +50% with {@link #EVOKERS_WING_SPEED_PER_LEVEL}). */
    public static final int EVOKERS_WING_MAX_LEVEL = 10;
    /** ADD_MULTIPLIED_TOTAL movement speed per wing level while gliding. */
    public static final double EVOKERS_WING_SPEED_PER_LEVEL = 0.05;
    /** CustomModelData float list entry for Evoker's Wing elytra item. */
    public static final int EVOKERS_WING_MODEL_ID = 930002;
}
