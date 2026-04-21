package com.cabal.claim.economy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class PlayerLocationSampler {
    private static final long SAMPLE_INTERVAL_TICKS = 40L; // ~2 seconds
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path outputPath;
    private int roundRobinCursor = 0;
    private long nextSampleTick = 0L;
    private final Map<UUID, Snapshot> latest = new HashMap<>();

    PlayerLocationSampler(Path serverDir) {
        this.outputPath = serverDir.resolve("player-positions.json");
    }

    void tick(MinecraftServer server) {
        long nowTick = server.getTickCount();
        if (nowTick < nextSampleTick) return;
        nextSampleTick = nowTick + SAMPLE_INTERVAL_TICKS;

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            clearAndWriteNow();
            roundRobinCursor = 0;
            return;
        }

        Set<UUID> online = new HashSet<>();
        for (ServerPlayer p : players) {
            online.add(p.getUUID());
        }
        latest.keySet().removeIf(uuid -> !online.contains(uuid));

        int index = Math.floorMod(roundRobinCursor, players.size());
        ServerPlayer sp = players.get(index);
        roundRobinCursor = (index + 1) % players.size();

        latest.put(sp.getUUID(), new Snapshot(
            sp.getUUID().toString(),
            sp.getName().getString(),
            sp.getX(),
            sp.getY(),
            sp.getZ(),
            sp.level().dimension().identifier().toString(),
            System.currentTimeMillis()
        ));
        writeSnapshot(System.currentTimeMillis());
    }

    void shutdown() {
        clearAndWriteNow();
        roundRobinCursor = 0;
    }

    private void clearAndWriteNow() {
        latest.clear();
        writeSnapshot(System.currentTimeMillis());
    }

    private void writeSnapshot(long generatedAtMs) {
        try {
            List<Snapshot> players = new ArrayList<>(latest.values());
            players.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            Payload payload = new Payload(generatedAtMs, players);
            String json = GSON.toJson(payload);

            Path parent = outputPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = outputPath.resolveSibling(outputPath.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            // Best-effort telemetry: never impact gameplay tick.
        }
    }

    private record Payload(long generatedAtMs, List<Snapshot> players) {}

    private record Snapshot(
        String uuid,
        String name,
        double x,
        double y,
        double z,
        String dimension,
        long sampledAtMs
    ) {}
}
