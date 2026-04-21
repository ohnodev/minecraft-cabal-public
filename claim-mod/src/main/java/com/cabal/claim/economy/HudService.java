package com.cabal.claim.economy;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Per-player sidebar HUD rendered via direct packet sends.
 *
 * Each player gets a virtual {@link Scoreboard} (not the server scoreboard) so
 * {@code team.setPlayerPrefix()} never broadcasts to other players. Packets are
 * sent exclusively through {@code player.connection}, giving each player their
 * own balance / K-D / playtime / ping values.
 */
public final class HudService {
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalEconomy/HudService");
    private static final int LINE_COUNT = 5;
    private static final String TITLE = "\u00A7b\u00A7lC\u00A73\u00A7lA\u00A79\u00A7lB\u00A7b\u00A7lA\u00A73\u00A7lL \u00A79\u00A7lS\u00A73\u00A7lM\u00A7b\u00A7lP";
    private static final String OBJECTIVE_NAME = "cabal_hud";
    private static final String[] TEAM_NAMES = {"ch_t1", "ch_t2", "ch_t3", "ch_t4", "ch_t5"};
    private static final String[] ENTRY_KEYS = {"\u00A71", "\u00A72", "\u00A73", "\u00A74", "\u00A75"};

    private final EconomyService economyService;
    private final PlayerStatsService statsService;
    private final Map<UUID, HudState> hudStates = new ConcurrentHashMap<>();
    private final Set<UUID> hiddenPlayers = ConcurrentHashMap.newKeySet();
    private long nextUpdateTick = 0;

    private static final class HudState {
        final Objective objective;
        final PlayerTeam[] teams;
        volatile List<String> lastLines;

        HudState(Objective objective, PlayerTeam[] teams, List<String> lastLines) {
            this.objective = objective;
            this.teams = teams;
            this.lastLines = lastLines;
        }
    }

    public HudService(EconomyService economyService, PlayerStatsService statsService) {
        this.economyService = economyService;
        this.statsService = statsService;
    }

    /**
     * Removes any HUD-related objectives/teams that leaked onto the server's
     * global scoreboard from earlier code revisions. The vanilla join flow syncs
     * the global scoreboard to every client, which collides with our per-player
     * virtual scoreboard packets.
     */
    public void purgeGlobalScoreboard(MinecraftServer server) {
        Scoreboard sb = server.getScoreboard();

        List<Objective> toRemove = new ArrayList<>();
        for (Objective obj : sb.getObjectives()) {
            String name = obj.getName();
            if (name.equals(OBJECTIVE_NAME) || name.startsWith("ch_")) {
                toRemove.add(obj);
            }
        }
        for (Objective obj : toRemove) {
            LOGGER.info("[HUD] purging stale global objective '{}'", obj.getName());
            sb.removeObjective(obj);
        }

        List<PlayerTeam> teamsToRemove = new ArrayList<>();
        for (PlayerTeam team : sb.getPlayerTeams()) {
            String name = team.getName();
            if (name.startsWith("cabal_t") || name.startsWith("ct")) {
                teamsToRemove.add(team);
            }
        }
        for (PlayerTeam team : teamsToRemove) {
            LOGGER.info("[HUD] purging stale global team '{}'", team.getName());
            sb.removePlayerTeam(team);
        }
    }

    public void tick(MinecraftServer server, int updateTicks, boolean showTps, boolean debugLogs) {
        long tick = server.getTickCount();
        if (tick < nextUpdateTick) return;
        int jitter = ThreadLocalRandom.current().nextInt(0, 4);
        nextUpdateTick = tick + updateTicks + jitter;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            updatePlayerHud(player, debugLogs);
        }
    }

    public void onPlayerJoin(ServerPlayer player) {
        initAndSendHud(player);
    }

    public boolean toggleForPlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        if (hiddenPlayers.remove(id)) {
            initAndSendHud(player);
            return true;
        }
        hiddenPlayers.add(id);
        hideSidebar(player);
        return false;
    }

    public void onPlayerLeave(UUID playerId) {
        hudStates.remove(playerId);
        hiddenPlayers.remove(playerId);
    }

    // ── lifecycle ───────────────────────────────────────────────────────

    private void initAndSendHud(ServerPlayer player) {
        Scoreboard sb = new Scoreboard();
        Objective obj = sb.addObjective(
            OBJECTIVE_NAME, ObjectiveCriteria.DUMMY,
            Component.literal(TITLE),
            ObjectiveCriteria.RenderType.INTEGER, false,
            BlankFormat.INSTANCE
        );

        List<String> lines = buildLiveLines(player);
        PlayerTeam[] teams = new PlayerTeam[LINE_COUNT];

        for (int i = 0; i < LINE_COUNT; i++) {
            PlayerTeam team = sb.addPlayerTeam(TEAM_NAMES[i]);
            sb.addPlayerToTeam(ENTRY_KEYS[i], team);
            sb.getOrCreatePlayerScore(ScoreHolder.forNameOnly(ENTRY_KEYS[i]), obj).set(LINE_COUNT - i);
            team.setPlayerPrefix(Component.literal(lines.get(i)));
            team.setPlayerSuffix(Component.empty());
            teams[i] = team;
        }

        hudStates.put(player.getUUID(), new HudState(obj, teams, lines));
        sendFullHud(player, obj, teams);
        LOGGER.info("[HUD] initialized for player={}", player.getUUID());
    }

    private void sendFullHud(ServerPlayer player, Objective obj, PlayerTeam[] teams) {
        var conn = player.connection;
        conn.send(new ClientboundSetObjectivePacket(obj, ClientboundSetObjectivePacket.METHOD_REMOVE));
        conn.send(new ClientboundSetObjectivePacket(obj, ClientboundSetObjectivePacket.METHOD_ADD));
        for (int i = 0; i < LINE_COUNT; i++) {
            conn.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(teams[i], true));
            conn.send(new ClientboundSetScorePacket(
                ENTRY_KEYS[i], OBJECTIVE_NAME, LINE_COUNT - i,
                Optional.empty(), Optional.empty()
            ));
        }
        conn.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, obj));
    }

    private void updatePlayerHud(ServerPlayer player, boolean debugLogs) {
        UUID playerId = player.getUUID();
        if (hiddenPlayers.contains(playerId)) return;

        HudState state = hudStates.get(playerId);
        if (state == null) {
            initAndSendHud(player);
            return;
        }

        List<String> nextLines = buildLiveLines(player);
        if (nextLines.equals(state.lastLines)) return;

        var conn = player.connection;
        for (int i = 0; i < nextLines.size(); i++) {
            if (state.lastLines != null && i < state.lastLines.size()
                && nextLines.get(i).equals(state.lastLines.get(i))) continue;
            state.teams[i].setPlayerPrefix(Component.literal(nextLines.get(i)));
            conn.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(state.teams[i], false));
        }
        state.lastLines = nextLines;

        if (debugLogs) {
            LOGGER.info("[HUD] player={} updated", player.getName().getString());
        }
    }

    private void hideSidebar(ServerPlayer player) {
        HudState state = hudStates.get(player.getUUID());
        if (state == null) return;
        player.connection.send(new ClientboundSetObjectivePacket(state.objective, ClientboundSetObjectivePacket.METHOD_REMOVE));
        hudStates.remove(player.getUUID());
    }

    // ── line formatting ─────────────────────────────────────────────────

    private List<String> buildLiveLines(ServerPlayer player) {
        PlayerStatsService.Snapshot stats = statsService.get(player.getUUID());
        double balance = economyService.getBalance(player.getUUID());
        int ping = player.connection.latency();
        return List.of(
            formatHudLine("$", "a", "Money", "$" + String.format("%.2f", balance)),
            formatHudLine("\u2726", "c", "Kills", String.valueOf(stats.kills())),
            formatHudLine("\u2620", "4", "Deaths", String.valueOf(stats.deaths())),
            formatHudLine("\u231B", "b", "Playtime", formatPlaytime(stats.playtimeSeconds())),
            formatHudLine("\u26A1", "e", "Ping", Math.max(0, ping) + "ms")
        );
    }

    private static String formatPlaytime(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        return String.format("%02dh %02dm", h, m);
    }

    private static String formatHudLine(String icon, String color, String label, String value) {
        return "\u00A7" + color + icon + " \u00A7f" + label + ": \u00A7" + color + value;
    }
}
