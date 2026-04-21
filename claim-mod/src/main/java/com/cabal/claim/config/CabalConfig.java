package com.cabal.claim.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads HUD/server-branding fields from the shared {@code <server>/cabal-config.json}.
 *
 * <p>The file is created (with a friendly default set) by {@code cabal-mobs} on first run. If this
 * loader runs before {@code cabal-mobs}, or if the file is malformed, it falls back to built-in
 * defaults without writing, so the two loaders never fight over defaults.
 *
 * <p>Formatting codes may be written as either {@code §x} or {@code &x}; we translate {@code &}
 * to the Minecraft section sign at read time, matching common server-admin conventions.
 */
public final class CabalConfig {
    private static final String FILE_NAME = "cabal-config.json";
    private static final Gson GSON = new GsonBuilder().create();
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalEconomy/CabalConfig");

    private static final String DEFAULT_SERVER_NAME = "Cabal SMP";
    private static final String DEFAULT_SERVER_COLOR = "\u00A7b\u00A7l";

    private static final Map<String, String> DEFAULT_ICONS = defaultIcons();
    private static final Map<String, String> DEFAULT_COLORS = defaultColors();

    private final String serverName;
    private final String serverColorCodes;
    private final String hudTitle;
    private final Map<String, String> hudIcons;
    private final Map<String, String> hudColors;

    private CabalConfig(String serverName, String serverColorCodes, String hudTitle,
                        Map<String, String> hudIcons, Map<String, String> hudColors) {
        this.serverName = serverName;
        this.serverColorCodes = serverColorCodes;
        this.hudTitle = hudTitle;
        this.hudIcons = hudIcons;
        this.hudColors = hudColors;
    }

    public String serverName() {
        return serverName;
    }

    public String serverColorCodes() {
        return serverColorCodes;
    }

    /**
     * Fully-formatted HUD title. Respects {@code hud.titleOverride} when set; otherwise renders
     * as {@code server.colorCodes + server.name}.
     */
    public String hudTitle() {
        return hudTitle;
    }

    /** Icon glyph for a given HUD line key ({@code money}, {@code kills}, {@code deaths}, {@code playtime}, {@code ping}). */
    public String hudIcon(String key) {
        String v = hudIcons.get(key);
        return v != null ? v : DEFAULT_ICONS.getOrDefault(key, "");
    }

    /** Single-char Minecraft color code (e.g. {@code a}, {@code c}) for a given HUD line key. */
    public String hudColor(String key) {
        String v = hudColors.get(key);
        return v != null ? v : DEFAULT_COLORS.getOrDefault(key, "f");
    }

    /** Pretty server name for chat / welcome messages (with color codes applied). */
    public String serverDisplayName() {
        return serverColorCodes + serverName;
    }

    public static CabalConfig loadOrDefault(Path serverDir) {
        Path path = serverDir.resolve(FILE_NAME);
        try {
            if (!Files.isRegularFile(path)) {
                return defaults();
            }
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            Root root = GSON.fromJson(raw, Root.class);
            return fromRoot(root);
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.error("[CabalClaim] Failed to read {}, using HUD defaults", path, e);
            return defaults();
        }
    }

    private static CabalConfig fromRoot(Root root) {
        String name = DEFAULT_SERVER_NAME;
        String colorCodes = DEFAULT_SERVER_COLOR;
        String titleOverride = null;

        if (root != null) {
            if (root.server != null) {
                if (root.server.name != null && !root.server.name.isBlank()) {
                    name = root.server.name;
                }
                if (root.server.colorCodes != null) {
                    colorCodes = translateColors(root.server.colorCodes);
                }
            }
            if (root.hud != null && root.hud.titleOverride != null) {
                String tr = translateColors(root.hud.titleOverride);
                if (!tr.isBlank()) {
                    titleOverride = tr;
                }
            }
        }

        String title = titleOverride != null && !titleOverride.isBlank()
                ? titleOverride
                : colorCodes + name;

        Map<String, String> icons = new LinkedHashMap<>(DEFAULT_ICONS);
        Map<String, String> colors = new LinkedHashMap<>(DEFAULT_COLORS);
        if (root != null && root.hud != null) {
            if (root.hud.icons != null) {
                for (Map.Entry<String, String> entry : root.hud.icons.entrySet()) {
                    if (entry.getValue() != null) {
                        icons.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            if (root.hud.colors != null) {
                for (Map.Entry<String, String> entry : root.hud.colors.entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isBlank()) {
                        // Allow users to write "&a" or just "a"; normalize to one code char.
                        String v = entry.getValue().trim();
                        if (v.startsWith("&") || v.startsWith("\u00A7")) v = v.substring(1);
                        if (!v.isEmpty()) {
                            v = v.substring(0, 1);
                            colors.put(entry.getKey(), v);
                        }
                    }
                }
            }
        }

        return new CabalConfig(name, colorCodes, title, icons, colors);
    }

    private static CabalConfig defaults() {
        return new CabalConfig(
                DEFAULT_SERVER_NAME,
                DEFAULT_SERVER_COLOR,
                DEFAULT_SERVER_COLOR + DEFAULT_SERVER_NAME,
                new LinkedHashMap<>(DEFAULT_ICONS),
                new LinkedHashMap<>(DEFAULT_COLORS));
    }

    private static String translateColors(String input) {
        if (input == null) return "";
        return input.replace('&', '\u00A7');
    }

    private static Map<String, String> defaultIcons() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("money", "$");
        m.put("kills", "\u2726");
        m.put("deaths", "\u2620");
        m.put("playtime", "\u231B");
        m.put("ping", "\u26A1");
        return m;
    }

    private static Map<String, String> defaultColors() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("money", "a");
        m.put("kills", "c");
        m.put("deaths", "4");
        m.put("playtime", "b");
        m.put("ping", "e");
        return m;
    }

    private static final class Root {
        ServerSection server;
        HudSection hud;
    }

    private static final class ServerSection {
        String name;
        String colorCodes;
    }

    private static final class HudSection {
        String titleOverride;
        Map<String, String> icons;
        Map<String, String> colors;
    }
}
