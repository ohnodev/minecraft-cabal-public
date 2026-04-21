package com.cabal.claim.economy;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class DiagnosticsService {
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalEconomy/RAM");
    private static final Logger DB_LOGGER = LoggerFactory.getLogger("CabalEconomy/DB");
    private static final Path PROC_STATUS = Path.of("/proc/self/status");

    private final EconomyConfig config;
    private final EconomyDatabase db;
    private final EconomyDbWriter dbWriter;
    private long nextLogTick = 0;

    public DiagnosticsService(EconomyConfig config, EconomyDatabase db, EconomyDbWriter dbWriter) {
        this.config = config;
        this.db = db;
        this.dbWriter = dbWriter;
    }

    public void tick(MinecraftServer server) {
        if (!config.ramLoggingEnabled) return;
        long nowTick = server.getTickCount();
        if (nowTick < nextLogTick) return;

        Runtime rt = Runtime.getRuntime();
        long usedHeapBytes = rt.totalMemory() - rt.freeMemory();
        long committedHeapBytes = rt.totalMemory();
        long maxHeapBytes = rt.maxMemory();
        long rssBytes = readProcFieldBytes("VmRSS");
        long hwmBytes = readProcFieldBytes("VmHWM");

        LOGGER.info(
            "[RAM] heap_used_mib={} heap_committed_mib={} heap_max_mib={} rss_mib={} hwm_mib={} players_online={}",
            toMiB(usedHeapBytes),
            toMiB(committedHeapBytes),
            toMiB(maxHeapBytes),
            rssBytes > 0 ? toMiB(rssBytes) : -1,
            hwmBytes > 0 ? toMiB(hwmBytes) : -1,
            server.getPlayerList().getPlayerCount()
        );
        EconomyDatabase.DbMetrics dbMetrics = db.snapshotAndResetMetrics();
        EconomyDbWriter.WriterMetrics writerMetrics = dbWriter.snapshotAndResetMetrics();
        DB_LOGGER.info(
            "[DB] sqlite_open_attempts={} sqlite_open_success={} sqlite_open_failures={} writer_submitted={} writer_completed={} writer_failed={} writer_rejected={} writer_outstanding={} top_call_sites={}",
            dbMetrics.openAttempts(),
            dbMetrics.openSuccess(),
            dbMetrics.openFailure(),
            writerMetrics.submitted(),
            writerMetrics.completed(),
            writerMetrics.failed(),
            writerMetrics.rejected(),
            writerMetrics.outstanding(),
            formatTopCallSites(dbMetrics.openByLabel())
        );

        nextLogTick = nowTick + (Math.max(10, config.ramLoggingIntervalSeconds) * 20L);
    }

    private static long readProcFieldBytes(String fieldName) {
        try {
            List<String> lines = Files.readAllLines(PROC_STATUS);
            for (String line : lines) {
                if (!line.startsWith(fieldName + ":")) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) return -1;
                long value = Long.parseLong(parts[1]);
                // /proc/self/status reports these in kB.
                return value * 1024L;
            }
        } catch (IOException | NumberFormatException ignored) {
            return -1;
        }
        return -1;
    }

    private static long toMiB(long bytes) {
        return bytes / (1024L * 1024L);
    }

    private static String formatTopCallSites(Map<String, Long> byLabel) {
        if (byLabel == null || byLabel.isEmpty()) return "none";
        String joined = byLabel.entrySet().stream()
            .sorted(Comparator.comparingLong((Map.Entry<String, Long> e) -> e.getValue()).reversed())
            .limit(5)
            .map(e -> e.getKey() + ":" + e.getValue())
            .collect(Collectors.joining(","));
        return joined.isEmpty() ? "none" : joined;
    }
}
