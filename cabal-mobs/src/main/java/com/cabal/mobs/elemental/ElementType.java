package com.cabal.mobs.elemental;

import org.jetbrains.annotations.Nullable;

public enum ElementType {
    FIRE("fire", "\u00a76\u00a7lFire Arrow", "\u00a7eDeals fire elemental damage."),
    LIGHTNING("lightning", "\u00a7b\u00a7lLightning Arrow", "\u00a73Deals lightning elemental damage.");

    private final String id;
    private final String displayName;
    private final String loreText;

    ElementType(String id, String displayName, String loreText) {
        this.id = id;
        this.displayName = displayName;
        this.loreText = loreText;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public String loreText() { return loreText; }

    public static @Nullable ElementType fromId(String id) {
        for (ElementType type : values()) {
            if (type.id.equals(id)) return type;
        }
        return null;
    }
}
