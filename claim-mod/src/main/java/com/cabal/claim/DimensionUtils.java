package com.cabal.claim;

public final class DimensionUtils {
    private DimensionUtils() {}

    public static String shortDimension(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "The End";
            default -> dim;
        };
    }
}
