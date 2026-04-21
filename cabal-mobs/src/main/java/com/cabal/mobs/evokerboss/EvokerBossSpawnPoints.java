package com.cabal.mobs.evokerboss;

import com.cabal.mobs.CabalMobsMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads up to ten evoker boss spawn columns from {@code config/cabal-mobs/evoker_boss_spawns.json}.
 * The file is created with placeholder coordinates on first server start if missing.
 */
public final class EvokerBossSpawnPoints {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_SPAWNS = 10;
    private static final Path CONFIG_FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("cabal-mobs")
            .resolve("evoker_boss_spawns.json");

    private static List<BlockPos> columns = List.of();

    private EvokerBossSpawnPoints() {}

    /**
     * Reloads spawn columns from disk (creates default JSON if the file does not exist).
     * Call from server started so {@link ServerLevel#getMaxY()} is valid.
     */
    public static void reload(ServerLevel overworld) {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            if (!Files.isRegularFile(CONFIG_FILE)) {
                Files.writeString(CONFIG_FILE, defaultJson(), StandardCharsets.UTF_8);
                CabalMobsMod.LOGGER.info(
                        "[CabalMobs] Wrote default evoker boss spawn list to {} — edit up to {} X/Z pairs for production.",
                        CONFIG_FILE,
                        MAX_SPAWNS);
            }
            String raw = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
            SpawnFileRoot root = GSON.fromJson(raw, SpawnFileRoot.class);
            columns = normalizeAndBuild(overworld, root);
            if (columns.isEmpty()) {
                CabalMobsMod.LOGGER.warn("[CabalMobs] evoker_boss_spawns.json had no usable entries; using built-in placeholders.");
                columns = normalizeAndBuild(overworld, GSON.fromJson(defaultJson(), SpawnFileRoot.class));
            }
            CabalMobsMod.LOGGER.info("[CabalMobs] Loaded {} evoker boss spawn column(s) from {}", columns.size(), CONFIG_FILE);
        } catch (IOException | RuntimeException e) {
            CabalMobsMod.LOGGER.error("[CabalMobs] Failed to load {}; using built-in placeholders", CONFIG_FILE, e);
            columns = normalizeAndBuild(overworld, GSON.fromJson(defaultJson(), SpawnFileRoot.class));
        }
    }

    /** Random spawn among configured columns (uniform). */
    public static BlockPos pickRandomSpawn(ServerLevel level) {
        if (columns.isEmpty()) {
            reload(level);
        }
        if (columns.isEmpty()) {
            return fallbackSingleColumn(level);
        }
        int i = level.getRandom().nextInt(columns.size());
        return columns.get(i);
    }

    /**
     * Arena center for purge radii when the live boss is unavailable: prefer the scheduler's last spawn column
     * ({@link EvokerBossScheduler#activeBossSpawnPos}), then the first configured JSON column, then a safe default.
     */
    public static Vec3 fallbackArenaBottomCenter(ServerLevel level) {
        if (EvokerBossScheduler.activeBossSpawnPos != null) {
            return Vec3.atBottomCenterOf(EvokerBossScheduler.activeBossSpawnPos);
        }
        if (columns.isEmpty()) {
            reload(level);
        }
        if (!columns.isEmpty()) {
            return Vec3.atBottomCenterOf(columns.get(0));
        }
        return Vec3.atBottomCenterOf(fallbackSingleColumn(level));
    }

    private static BlockPos fallbackSingleColumn(ServerLevel level) {
        int y = level.getMaxY() - EvokerBossConfig.SPAWN_FROM_TOP_OFFSET_BLOCKS;
        return new BlockPos(0, y, 0);
    }

    private static List<BlockPos> normalizeAndBuild(ServerLevel level, SpawnFileRoot root) {
        if (root == null || root.spawns == null || root.spawns.isEmpty()) {
            return List.of();
        }
        int limit = Math.min(MAX_SPAWNS, root.spawns.size());
        int defaultY = level.getMaxY() - EvokerBossConfig.SPAWN_FROM_TOP_OFFSET_BLOCKS;
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        List<BlockPos> built = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            SpawnEntry e = root.spawns.get(i);
            if (e == null) {
                continue;
            }
            int y = e.y != null ? Mth.clamp(e.y, minY, maxY) : defaultY;
            built.add(new BlockPos(e.x, y, e.z));
        }
        return Collections.unmodifiableList(built);
    }

    private static String defaultJson() {
        // Placeholder arena columns — replace in production with real X/Z (optional per-entry Y).
        return """
                {
                  "comment": "Up to 10 entries. Omit y to use (world max Y - SPAWN_FROM_TOP_OFFSET from EvokerBossConfig).",
                  "spawns": [
                    { "x": 0, "z": 0 },
                    { "x": 64, "z": 0 },
                    { "x": -64, "z": 0 },
                    { "x": 0, "z": 64 },
                    { "x": 0, "z": -64 },
                    { "x": 128, "z": 128 },
                    { "x": -128, "z": 128 },
                    { "x": 128, "z": -128 },
                    { "x": -128, "z": -128 },
                    { "x": 96, "z": -96 }
                  ]
                }
                """;
    }

    private static final class SpawnFileRoot {
        @SerializedName("spawns")
        List<SpawnEntry> spawns;
    }

    private static final class SpawnEntry {
        int x;
        int z;
        Integer y;
    }
}
