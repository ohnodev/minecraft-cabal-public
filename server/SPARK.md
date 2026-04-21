# spark (Fabric) — server profiling

This repo ships the **spark** mod JAR under `server/mods/` (`spark-*-fabric.jar`). It depends on Minecraft `>= 26.1` and matches our Fabric API stack.

**Docs:** https://spark.lucko.me/docs/Command-Usage

## In-game (op / permission as configured)

- `/spark` — open the spark menu / help.
- `/spark profiler start` — begin **CPU sampling** (stack snapshots on an interval; not per-tick logging).
- `/spark profiler stop` — stop and get a **link** (or file) to the flame graph. Inspect the **Server thread** for tick work.
- `/spark tickmonitor` / `/spark health` — when available in your spark build, extra tick health views (see upstream docs).

## Relating to Cabal boss logs

- `[CabalMobs][TickPressure]` — wall gap between `END_SERVER_TICK` samples (coarse tripwire).
- `[CabalMobs][BossInfra]` — throttled boss context (health, phases, aura, tagged vex column count, nearby players, forced chunk).

Use **spark profiler** during a fight to see **which methods** dominate the server thread; use **BossInfra** to correlate time windows with boss state.
