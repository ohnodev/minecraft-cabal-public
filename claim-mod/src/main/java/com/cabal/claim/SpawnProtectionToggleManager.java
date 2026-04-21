package com.cabal.claim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SpawnProtectionToggleManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpawnProtectionToggleManager.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path configPath;
    private volatile boolean disabled;

    public SpawnProtectionToggleManager(Path serverDir) {
        this.configPath = serverDir.resolve("spawn-protection-toggle.json");
        loadOrCreate();
    }

    public boolean isDisabled() {
        return disabled;
    }

    public boolean setDisabled(boolean disabled) {
        this.disabled = disabled;
        return save();
    }

    private void loadOrCreate() {
        if (!Files.exists(configPath)) {
            disabled = false;
            save();
            return;
        }
        try {
            ToggleConfig cfg = GSON.fromJson(Files.readString(configPath), ToggleConfig.class);
            disabled = cfg != null && cfg.disabled;
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.error("Failed to load {}: {}", configPath, e.getMessage(), e);
            disabled = false;
            save();
        }
    }

    private boolean save() {
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(new ToggleConfig(disabled)));
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to save {}: {}", configPath, e.getMessage(), e);
            return false;
        }
    }

    private record ToggleConfig(boolean disabled) {}
}
