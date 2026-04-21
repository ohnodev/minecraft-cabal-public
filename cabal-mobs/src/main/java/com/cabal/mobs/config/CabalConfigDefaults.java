package com.cabal.mobs.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Canonical default {@code cabal-config.json} written on first run. Includes the full set of fields
 * consumed across Cabal mods (mobs, elytra, claim) so server owners can see everything they can tune.
 */
final class CabalConfigDefaults {
    private static final String TEMPLATE_RESOURCE = "/cabal-config-default.json";

    private CabalConfigDefaults() {}

    static String defaultJson() {
        try (InputStream in = CabalConfigDefaults.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing default config resource " + TEMPLATE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read default config resource " + TEMPLATE_RESOURCE, e);
        }
    }
}
