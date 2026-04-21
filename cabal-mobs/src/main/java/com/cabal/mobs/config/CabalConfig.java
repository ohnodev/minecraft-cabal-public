package com.cabal.mobs.config;

import com.cabal.mobs.CabalMobsMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * User-editable Cabal config at {@code <server>/cabal-config.json}. Shared across Cabal mods.
 *
 * <p>This class reads only the fields {@code cabal-mobs} cares about ({@code babyCreeper.spawnChance}
 * and {@code evoker.enabled}). Extra keys written by other mods are preserved on disk because we
 * only rewrite the file when it is missing.
 */
public final class CabalConfig {
    private static final String FILE_NAME = "cabal-config.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile CabalConfig instance;

    private final float babyCreeperSpawnChance;
    private final boolean evokerEnabled;

    private CabalConfig(float babyCreeperSpawnChance, boolean evokerEnabled) {
        this.babyCreeperSpawnChance = babyCreeperSpawnChance;
        this.evokerEnabled = evokerEnabled;
    }

    public float babyCreeperSpawnChance() {
        return babyCreeperSpawnChance;
    }

    public boolean evokerEnabled() {
        return evokerEnabled;
    }

    public static CabalConfig get() {
        CabalConfig local = instance;
        if (local == null) {
            throw new IllegalStateException("CabalConfig.load() must be called before get()");
        }
        return local;
    }

    /**
     * Loads the config from {@code <gameDir>/cabal-config.json}. Creates a default file if missing.
     * Safe to call multiple times; only the first call performs the read.
     */
    public static synchronized CabalConfig load() {
        if (instance != null) return instance;
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path path = gameDir.resolve(FILE_NAME);

        try {
            if (!Files.isRegularFile(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, CabalConfigDefaults.defaultJson(), StandardCharsets.UTF_8);
                CabalMobsMod.LOGGER.info("[CabalMobs] Wrote default cabal-config.json to {}", path);
            }
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            Root root = GSON.fromJson(raw, Root.class);
            instance = fromRoot(root);
            CabalMobsMod.LOGGER.info(
                    "[CabalMobs] Loaded cabal-config.json (babyCreeperSpawnChance={}, evokerEnabled={})",
                    instance.babyCreeperSpawnChance,
                    instance.evokerEnabled);
            return instance;
        } catch (IOException | JsonSyntaxException e) {
            CabalMobsMod.LOGGER.error("[CabalMobs] Failed to load {}; using built-in defaults", path, e);
            instance = fromRoot(null);
            return instance;
        }
    }

    private static CabalConfig fromRoot(Root root) {
        float chance = 0.30f;
        boolean evokerEnabled = true;
        if (root != null) {
            if (root.babyCreeper != null && root.babyCreeper.spawnChance != null) {
                chance = clamp01(root.babyCreeper.spawnChance.floatValue());
            }
            if (root.evoker != null && root.evoker.enabled != null) {
                evokerEnabled = root.evoker.enabled;
            }
        }
        return new CabalConfig(chance, evokerEnabled);
    }

    private static float clamp01(float v) {
        if (v < 0.0f) return 0.0f;
        if (v > 1.0f) return 1.0f;
        return v;
    }

    private static final class Root {
        BabyCreeperSection babyCreeper;
        EvokerSection evoker;
    }

    private static final class BabyCreeperSection {
        Double spawnChance;
    }

    private static final class EvokerSection {
        Boolean enabled;
    }
}
