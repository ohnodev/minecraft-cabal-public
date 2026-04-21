package com.cabal.elytra.config;

import com.cabal.elytra.CabalElytraMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads {@code <server>/cabal-config.json} for elytra-related toggles. The file is shared with
 * {@code cabal-mobs} and {@code cabal-claim}; if it does not yet exist, the mobs mod (loaded in
 * parallel) will create it. Missing file here falls back to defaults without writing, so we never
 * race against the mobs mod's default writer.
 */
public final class CabalConfig {
    private static final String FILE_NAME = "cabal-config.json";
    private static final Gson GSON = new GsonBuilder().create();

    private static volatile CabalConfig instance;

    private final boolean evokerEnabled;

    private CabalConfig(boolean evokerEnabled) {
        this.evokerEnabled = evokerEnabled;
    }

    public boolean evokerEnabled() {
        return evokerEnabled;
    }

    public static CabalConfig get() {
        CabalConfig local = instance;
        if (local == null) {
            synchronized (CabalConfig.class) {
                local = instance;
                if (local == null) {
                    local = load();
                }
            }
        }
        return local;
    }

    public static synchronized CabalConfig load() {
        if (instance != null) return instance;
        Path path = FabricLoader.getInstance().getGameDir().resolve(FILE_NAME);
        boolean evokerEnabled = true;
        try {
            if (Files.isRegularFile(path)) {
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                Root root = GSON.fromJson(raw, Root.class);
                if (root != null && root.evoker != null && root.evoker.enabled != null) {
                    evokerEnabled = root.evoker.enabled;
                }
            }
        } catch (IOException | JsonSyntaxException e) {
            CabalElytraMod.LOGGER.error("[CabalElytra] Failed to read {}; defaulting evoker.enabled=true", path, e);
        }
        instance = new CabalConfig(evokerEnabled);
        CabalElytraMod.LOGGER.info("[CabalElytra] cabal-config.json: evoker.enabled={}", evokerEnabled);
        return instance;
    }

    private static final class Root {
        EvokerSection evoker;
    }

    private static final class EvokerSection {
        Boolean enabled;
    }
}
