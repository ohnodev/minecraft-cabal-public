import { Fragment, useEffect, useRef, useState } from "react";
import { fetchServer, fetchContent, fetchMap, fetchPlayerPositions, fetchEvokerBoss, fetchAuction, itemSpriteUrl } from "./api";
import type { ServerStatus, ContentData, MapData, MapClaim, MapDimension, LivePlayerPosition, LiveEvokerBoss, AuctionListing } from "./api";
import WorldMap from "./WorldMap";
import Navbar from "./components/Navbar";
import Footer from "./components/Footer";

const DISCORD_URL = "https://discord.gg/2NR3W7j4vP";
const UPDATES_INITIAL = 5;
const UPDATES_STEP = 5;
const JAVA_PORT = 25565;
const AUCTION_POLL_MS = 60_000;
const BOSS_POLL_MS = 5_000;
type InfoTab = "claims" | "auction" | "mechanics";

function timeAgo(epochSec: number): string {
  const diffSec = Math.floor(Date.now() / 1000) - epochSec;
  if (diffSec < 60) return "just now";
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ago`;
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}h ago`;
  return `${Math.floor(diffSec / 86400)}d ago`;
}

function formatPrice(price: number): string {
  if (price >= 1000) return `$${(price / 1000).toFixed(1)}k`;
  if (Number.isInteger(price)) return `$${price}`;
  return `$${price.toFixed(2)}`;
}

function mapLabel(d: MapDimension): string {
  if (d === "nether") return "Nether";
  if (d === "end") return "End";
  return "Overworld";
}

function mapHintText(dimension: MapDimension, spawnProtectionRadius?: number): string {
  if (dimension === "overworld") {
    return `Overworld biome map is shown in grayscale; spawn protection (${spawnProtectionRadius ?? "?"} blocks), claims, and live player positions are shown on top. Drag to pan.`;
  }
  return `${mapLabel(dimension)} biome map is shown in grayscale; claims and live player positions are shown on top. Drag to pan.`;
}

function positionDimension(raw: string): MapDimension {
  if (raw === "minecraft:the_nether" || raw === "nether") return "nether";
  if (raw === "minecraft:the_end" || raw === "end") return "end";
  return "overworld";
}

function sortedClaims(claims: MapClaim[]): MapClaim[] {
  return [...claims].sort((a, b) => {
    const aName = typeof a.ownerName === "string" ? a.ownerName : "";
    const bName = typeof b.ownerName === "string" ? b.ownerName : "";
    return aName.localeCompare(bName, undefined, { sensitivity: "base" });
  });
}

function renderUpdateBody(body: string) {
  // Split plain text + URL segments so update entries can safely render links inline.
  const urlRegex = /(https?:\/\/[^\s]+)/g;
  const parts = body.split(urlRegex);
  return (
    <>
      {parts.map((part, idx) => {
        if (/^https?:\/\//.test(part)) {
          // Keep trailing punctuation visible in text, but exclude it from href target.
          const cleanedHref = part.replace(/[.,)\]!?:]+$/, "");
          const trailing = part.slice(cleanedHref.length);
          return (
            <Fragment key={`${part}-${idx}`}>
              <a
                href={cleanedHref}
                target="_blank"
                rel="noopener noreferrer"
                style={styles.updateLink}
              >
                {cleanedHref}
              </a>
              {trailing}
            </Fragment>
          );
        }
        return <span key={`${part}-${idx}`}>{part}</span>;
      })}
    </>
  );
}

function ClaimsSidebar({ data }: { data: MapData }) {
  const list = sortedClaims(data.claims);
  return (
    <aside className="map-section-sidebar map-claims-sidebar" aria-label="Claimed lands">
      <h3>Claimed lands</h3>
      {list.length === 0 ? (
        <p className="map-claims-empty">No claims on the map yet. In-game, use /claim to register your base.</p>
      ) : (
        <ul className="map-claims-list">
          {list.map((c, i) => (
            <li key={`${c.ownerName}-${c.x}-${c.z}-${i}`} className="map-claim-row">
              <span className="map-claim-owner">{c.ownerName}</span>
              <span className="map-claim-coords">
                X {Math.round(c.x)} &middot; Z {Math.round(c.z)}
              </span>
              <span className="map-claim-radius">Radius {c.radius} blocks</span>
            </li>
          ))}
        </ul>
      )}
    </aside>
  );
}

function ClaimsSidebarSkeleton() {
  return (
    <aside className="map-section-sidebar map-claims-sidebar map-claims-skeleton" aria-label="Loading claimed lands">
      <h3>Claimed lands</h3>
      <ul className="map-claims-list" aria-hidden="true">
        {Array.from({ length: 8 }).map((_, i) => (
          <li key={i} className="map-claim-row map-claim-row--skeleton">
            <span className="claims-skeleton-block claims-skeleton-block--owner" />
            <span className="claims-skeleton-block claims-skeleton-block--coords" />
            <span className="map-claim-radius claims-skeleton-block claims-skeleton-block--radius" />
          </li>
        ))}
      </ul>
    </aside>
  );
}

function ClaimsSidebarError({ err }: { err: string }) {
  return (
    <aside className="map-section-sidebar map-claims-sidebar" aria-label="Claimed lands unavailable">
      <h3>Claimed lands</h3>
      <p className="map-claims-empty">Unable to load claims right now: {err}</p>
    </aside>
  );
}

function useServerIpCopy(serverIp: string) {
  const [copiedServerIp, setCopiedServerIp] = useState(false);
  const copyResetTimerRef = useRef<number | null>(null);

  useEffect(
    () => () => {
      if (copyResetTimerRef.current !== null) {
        window.clearTimeout(copyResetTimerRef.current);
      }
    },
    []
  );

  const copyServerIp = async () => {
    // Legacy clipboard fallback for browsers/environments without navigator.clipboard.
    const fallbackCopy = (): boolean => {
      const previouslyFocused = document.activeElement;
      const ta = document.createElement("textarea");
      ta.value = serverIp;
      ta.setAttribute("readonly", "");
      ta.style.position = "absolute";
      ta.style.left = "-9999px";
      document.body.appendChild(ta);
      ta.select();
      try {
        return document.execCommand("copy");
      } catch {
        return false;
      } finally {
        document.body.removeChild(ta);
        if (
          previouslyFocused instanceof HTMLElement &&
          previouslyFocused.isConnected &&
          typeof previouslyFocused.focus === "function"
        ) {
          previouslyFocused.focus();
        }
      }
    };

    let copied = false;
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(serverIp);
        copied = true;
      } else {
        copied = fallbackCopy();
      }
    } catch {
      copied = fallbackCopy();
    }

    if (!copied) return;

    setCopiedServerIp(true);
    if (copyResetTimerRef.current !== null) {
      window.clearTimeout(copyResetTimerRef.current);
    }
    copyResetTimerRef.current = window.setTimeout(() => {
      setCopiedServerIp(false);
    }, 1400);
  };

  return { copiedServerIp, copyServerIp };
}

function ServerIpCopyButton({ serverIp }: { serverIp: string }) {
  const { copiedServerIp, copyServerIp } = useServerIpCopy(serverIp);

  return (
    <>
      <button
        type="button"
        onClick={copyServerIp}
        aria-label={copiedServerIp ? "Copied server IP" : "Copy server IP"}
        title={copiedServerIp ? "Copied" : "Copy server IP"}
        style={copiedServerIp ? { ...styles.copyBtn, ...styles.copyBtnActive } : styles.copyBtn}
      >
        {copiedServerIp ? (
          <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
            <path
              d="M20 6L9 17l-5-5"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.3"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        ) : (
          <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
            <rect x="9" y="9" width="10" height="10" rx="2" ry="2" fill="none" stroke="currentColor" strokeWidth="2" />
            <rect x="5" y="5" width="10" height="10" rx="2" ry="2" fill="none" stroke="currentColor" strokeWidth="2" />
          </svg>
        )}
      </button>
      <span role="status" aria-live="polite" aria-atomic="true" style={styles.srOnly}>
        {copiedServerIp ? "Server IP copied" : ""}
      </span>
    </>
  );
}

export default function App() {
  const [server, setServer] = useState<ServerStatus | null>(null);
  const [content, setContent] = useState<ContentData | null>(null);
  const [maps, setMaps] = useState<Partial<Record<MapDimension, MapData>>>({});
  const [mapTab, setMapTab] = useState<MapDimension>("overworld");
  const [playerPositions, setPlayerPositions] = useState<LivePlayerPosition[]>([]);
  const [evokerBoss, setEvokerBoss] = useState<LiveEvokerBoss | null>(null);
  const [mapErr, setMapErr] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [updatesVisible, setUpdatesVisible] = useState(UPDATES_INITIAL);
  const [auctionListings, setAuctionListings] = useState<AuctionListing[]>([]);
  const [auctionLoading, setAuctionLoading] = useState(true);
  const [activeInfoTab, setActiveInfoTab] = useState<InfoTab>("claims");
  // Reset "Load more" pagination only when update content identity actually changes.
  const prevContentKeyRef = useRef<string | undefined>(undefined);
  const infoTabRefs = useRef<Record<InfoTab, HTMLButtonElement | null>>({
    claims: null,
    auction: null,
    mechanics: null,
  });
  const infoTabs: InfoTab[] = ["claims", "auction", "mechanics"];

  const handleInfoTabKeyDown = (e: React.KeyboardEvent<HTMLButtonElement>) => {
    if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
    e.preventDefault();
    const currentIndex = infoTabs.indexOf(activeInfoTab);
    const offset = e.key === "ArrowRight" ? 1 : -1;
    const nextIndex = (currentIndex + offset + infoTabs.length) % infoTabs.length;
    const nextTab = infoTabs[nextIndex];
    setActiveInfoTab(nextTab);
    infoTabRefs.current[nextTab]?.focus();
  };

  useEffect(() => {
    if (!content?.updates) return;
    const key =
      content.id ??
      content.updates.map((u) => `${u.date}\t${u.title}`).join("\n");
    if (prevContentKeyRef.current === key) return;
    prevContentKeyRef.current = key;
    setUpdatesVisible(Math.min(UPDATES_INITIAL, content.updates.length));
  }, [content]);

  useEffect(() => {
    const ac = new AbortController();
    const { signal } = ac;

    Promise.all([fetchServer(signal), fetchContent(signal)])
      .then(([s, c]) => {
        setServer(s);
        setContent(c);
      })
      .catch((e: Error) => {
        if (e.name === "AbortError") return;
        setErr(e.message);
      });
    Promise.all([
      fetchMap("overworld", signal),
      fetchMap("nether", signal),
      fetchMap("end", signal),
      fetchPlayerPositions(signal),
    ])
      .then(([overworld, nether, end, live]) => {
        setMaps({ overworld, nether, end });
        setPlayerPositions(live.players);
        setMapErr(null);
      })
      .catch((e: Error) => {
        if (e.name === "AbortError") return;
        setMapErr(e.message);
      });
    fetchEvokerBoss(signal)
      .then(setEvokerBoss)
      .catch((e: Error) => {
        if (e.name !== "AbortError") {
          setEvokerBoss(null);
        }
      });

    const interval = setInterval(() => {
      fetchServer(signal)
        .then(setServer)
        .catch((e: Error) => {
          if (e.name !== "AbortError") {
            /* ignore transient poll errors */
          }
        });
    }, 30_000);

    const positionsInterval = setInterval(() => {
      fetchPlayerPositions(signal)
        .then((live) => setPlayerPositions(live.players))
        .catch((e: Error) => {
          if (e.name !== "AbortError") {
            /* ignore transient poll errors */
          }
        });
    }, 2_000);

    const bossInterval = setInterval(() => {
      fetchEvokerBoss(signal)
        .then(setEvokerBoss)
        .catch((e: Error) => {
          if (e.name !== "AbortError") {
            /* ignore transient poll errors */
          }
        });
    }, BOSS_POLL_MS);

    return () => {
      ac.abort();
      clearInterval(interval);
      clearInterval(positionsInterval);
      clearInterval(bossInterval);
    };
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    const load = () =>
      fetchAuction(ac.signal)
        .then((data) => {
          setAuctionListings(data.listings);
          setAuctionLoading(false);
        })
        .catch((e: Error) => {
          if (e.name !== "AbortError") setAuctionLoading(false);
        });
    load();
    const interval = setInterval(load, AUCTION_POLL_MS);
    return () => {
      ac.abort();
      clearInterval(interval);
    };
  }, []);

  const serverIp = content?.connectAddress ?? "minecraft.thecabal.app";

  const mapData = maps[mapTab] ?? null;
  const mapPlayers = playerPositions.filter((p) => positionDimension(p.dimension) === mapTab);
  const mapBoss =
    evokerBoss?.present && evokerBoss.dimension && positionDimension(evokerBoss.dimension) === mapTab
      ? evokerBoss
      : null;
  const updatesLen = content ? content.updates.length : 0;

  return (
    <>
      <Navbar />

      <main className="main-with-fixed-navbar">
      <header style={styles.header}>
        <h1 style={styles.title}>⛏ Cabal SMP</h1>
        <p style={styles.subtitle}>Minecraft 26.2 Snapshot 2 &middot; Vanilla + Fabric</p>
        <a
          href={DISCORD_URL}
          target="_blank"
          rel="noopener noreferrer"
          className="dashboard-discord-cta"
          style={styles.discordLink}
        >
          Join Discord
        </a>
      </header>

      {err && <div style={styles.error}>API error: {err}</div>}

      <section style={styles.grid}>
        {/* Server info card */}
        <div style={styles.card}>
          <h2 style={styles.cardTitle}>Server</h2>
          {content && <p style={styles.desc}>{content.description}</p>}
          {content && (
            <div style={styles.connect}>
              <span style={styles.label}>Server IP</span>
              <code style={styles.code}>{serverIp}</code>
              <ServerIpCopyButton serverIp={serverIp} />
            </div>
          )}
          <a href={DISCORD_URL} target="_blank" rel="noopener noreferrer" style={styles.discordSecondary}>
            Discord &rarr; community &amp; updates
          </a>
        </div>

        {/* Status card */}
        <div style={styles.card}>
          <h2 style={styles.cardTitle}>Status</h2>
          {server ? (
            <>
              <div style={styles.stat}>
                <span style={styles.bigNum}>{server.onlinePlayers}</span>
                <span style={styles.label}> / {server.maxPlayers} online</span>
              </div>
              {server.sampleNames.length > 0 && (
                <p style={styles.players}>
                  {server.sampleNames.join(", ")}
                </p>
              )}
              <p style={{ ...styles.label, marginTop: 8 }}>v{server.version}</p>
            </>
          ) : (
            <p style={styles.label}>Loading...</p>
          )}
        </div>
      </section>

      {/* Map + claims list (desktop: map left, list right) */}
      <section style={styles.section}>
        <h2 style={styles.sectionTitle}>World Map</h2>
        <div style={styles.tabRow} role="tablist" aria-label="World map tabs">
          {(["overworld", "nether", "end"] as MapDimension[]).map((d) => (
            <button
              key={d}
              type="button"
              role="tab"
              aria-selected={mapTab === d}
              style={mapTab === d ? { ...styles.tabBtn, ...styles.tabBtnActive } : styles.tabBtn}
              onClick={() => setMapTab(d)}
            >
              {mapLabel(d)}
            </button>
          ))}
        </div>
        <p style={styles.mapHint}>
          {mapHintText(mapTab, mapData?.spawn.protectionRadius)}
        </p>
        <div className="map-section-row">
          <div className="map-section-mapcol">
            <WorldMap data={mapData} players={mapPlayers} boss={mapBoss} />
          </div>
          {mapData ? (
            <ClaimsSidebar data={mapData} />
          ) : mapErr ? (
            <ClaimsSidebarError err={mapErr} />
          ) : (
            <ClaimsSidebarSkeleton />
          )}
        </div>
        {mapData && (
          <div className="map-section-legend-wrap" style={styles.legend}>
            {mapTab === "overworld" && (
              <>
                <span style={{ ...styles.legendDot, background: "#1d6b3a" }} /> Spawn
              </>
            )}
            <span
              style={{
                ...styles.legendDot,
                background: "#a65f2e",
                marginLeft: mapTab === "overworld" ? 16 : 0,
              }}
            /> Claims
            <span style={{ ...styles.legendDot, background: "#56b5ff", marginLeft: 16 }} /> Players ({mapPlayers.length})
            <span style={{ ...styles.legendDot, background: "#d7423a", marginLeft: 16 }} /> Evoker boss
            <span style={{ ...styles.legendDot, background: "#6b6358", marginLeft: 16 }} /> Wilderness
          </div>
        )}
      </section>

      {/* Auction House — live listings table */}
      <section style={styles.section} aria-labelledby="auction-heading">
        <h2 id="auction-heading" style={styles.sectionTitle}>
          Auction House
        </h2>
        <p style={styles.auctionHint}>
          Live market listings pulled from the server every 60 seconds.
          Use <code style={styles.mechanicsCode}>/sell</code> in-game to list items.
        </p>
        <div className="auction-table-wrap">
          {auctionLoading ? (
            <table className="auction-table" aria-label="Loading auction data">
              <thead>
                <tr>
                  <th className="auction-th auction-th-sprite" />
                  <th className="auction-th">Item</th>
                  <th className="auction-th auction-th-right">Qty</th>
                  <th className="auction-th auction-th-right">Price</th>
                  <th className="auction-th">Seller</th>
                  <th className="auction-th auction-th-right">Listed</th>
                </tr>
              </thead>
              <tbody>
                {Array.from({ length: 5 }).map((_, i) => (
                  <tr key={i} className="auction-row auction-skeleton-row">
                    <td className="auction-td auction-td-sprite">
                      <div className="auction-skeleton-cell auction-skeleton-sprite" />
                    </td>
                    <td className="auction-td">
                      <div className="auction-skeleton-cell auction-skeleton-name" />
                    </td>
                    <td className="auction-td auction-td-right">
                      <div className="auction-skeleton-cell auction-skeleton-short" />
                    </td>
                    <td className="auction-td auction-td-right">
                      <div className="auction-skeleton-cell auction-skeleton-short" />
                    </td>
                    <td className="auction-td">
                      <div className="auction-skeleton-cell auction-skeleton-name" />
                    </td>
                    <td className="auction-td auction-td-right">
                      <div className="auction-skeleton-cell auction-skeleton-short" />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : auctionListings.length === 0 ? (
            <div className="auction-empty">
              <p>No active listings right now. Be the first to sell something!</p>
            </div>
          ) : (
            <table className="auction-table">
              <thead>
                <tr>
                  <th className="auction-th auction-th-sprite" />
                  <th className="auction-th">Item</th>
                  <th className="auction-th auction-th-right">Qty</th>
                  <th className="auction-th auction-th-right">Price</th>
                  <th className="auction-th">Seller</th>
                  <th className="auction-th auction-th-right">Listed</th>
                </tr>
              </thead>
              <tbody>
                {auctionListings.map((l) => (
                  <tr key={l.id} className="auction-row">
                    <td className="auction-td auction-td-sprite">
                      <img
                        src={itemSpriteUrl(l.spriteItemId ?? l.itemId)}
                        alt=""
                        className="auction-sprite"
                        loading="lazy"
                        onError={(e) => {
                          const img = e.target as HTMLImageElement;
                          const fallback = itemSpriteUrl("minecraft:barrier");
                          if (img.src.endsWith("/barrier.png")) {
                            img.style.display = "none";
                            return;
                          }
                          img.src = fallback;
                        }}
                      />
                    </td>
                    <td className="auction-td auction-td-name">{l.itemName}</td>
                    <td className="auction-td auction-td-right">{l.itemCount}</td>
                    <td className="auction-td auction-td-right auction-td-price">
                      {formatPrice(l.price)}
                    </td>
                    <td className="auction-td auction-td-seller">{l.sellerName}</td>
                    <td className="auction-td auction-td-right auction-td-time">
                      {timeAgo(l.createdAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </section>

      {/* Commands & mechanics — between map and news */}
      <section style={styles.section} className="mechanics-section" aria-labelledby="mechanics-heading">
        <h2 id="mechanics-heading" style={styles.sectionTitle}>
          Commands &amp; mechanics
        </h2>
        <div style={styles.tabRow} role="tablist" aria-label="Commands and mechanics tabs">
          <button
            type="button"
            id="tab-claims"
            role="tab"
            aria-selected={activeInfoTab === "claims"}
            aria-controls="panel-claims"
            tabIndex={activeInfoTab === "claims" ? 0 : -1}
            ref={(el) => {
              infoTabRefs.current.claims = el;
            }}
            style={activeInfoTab === "claims" ? { ...styles.tabBtn, ...styles.tabBtnActive } : styles.tabBtn}
            onClick={() => setActiveInfoTab("claims")}
            onKeyDown={handleInfoTabKeyDown}
          >
            Land Claims
          </button>
          <button
            type="button"
            id="tab-auction"
            role="tab"
            aria-selected={activeInfoTab === "auction"}
            aria-controls="panel-auction"
            tabIndex={activeInfoTab === "auction" ? 0 : -1}
            ref={(el) => {
              infoTabRefs.current.auction = el;
            }}
            style={activeInfoTab === "auction" ? { ...styles.tabBtn, ...styles.tabBtnActive } : styles.tabBtn}
            onClick={() => setActiveInfoTab("auction")}
            onKeyDown={handleInfoTabKeyDown}
          >
            Auction
          </button>
          <button
            type="button"
            id="tab-mechanics"
            role="tab"
            aria-selected={activeInfoTab === "mechanics"}
            aria-controls="panel-mechanics"
            tabIndex={activeInfoTab === "mechanics" ? 0 : -1}
            ref={(el) => {
              infoTabRefs.current.mechanics = el;
            }}
            style={activeInfoTab === "mechanics" ? { ...styles.tabBtn, ...styles.tabBtnActive } : styles.tabBtn}
            onClick={() => setActiveInfoTab("mechanics")}
            onKeyDown={handleInfoTabKeyDown}
          >
            Mechanics
          </button>
        </div>
        <div style={styles.mechanicsBox}>
          <div
            role="tabpanel"
            id="panel-claims"
            aria-labelledby="tab-claims"
            hidden={activeInfoTab !== "claims"}
            aria-hidden={activeInfoTab !== "claims"}
          >
            <>
              <p style={styles.mechanicsLead}>
                Each player can create <strong style={{ color: "var(--color-text-primary)" }}>one land claim</strong>.
                A claim protects a <strong style={{ color: "var(--color-text-primary)" }}>100-block radius</strong>{" "}
                around your chosen spot and gives anti-grief protection for your base.
              </p>
              <ul style={styles.mechanicsList}>
                <li style={styles.mechanicsItem}>
                  <code style={styles.mechanicsCode}>/claim</code> — Create your one claim centered on where you stand.
                </li>
                <li style={styles.mechanicsItem}>
                  <code style={styles.mechanicsCode}>/home</code> — Teleport back to your claim center (with combat and
                  cooldown protection).
                </li>
                <li style={styles.mechanicsItem}>
                  <code style={styles.mechanicsCode}>/landtrust &lt;player&gt;</code>,{" "}
                  <code style={styles.mechanicsCode}>/landuntrust &lt;player&gt;</code>,{" "}
                  <code style={styles.mechanicsCode}>/landlist</code> — Manage trusted builders on your land.
                </li>
              </ul>
            </>
          </div>

          <div
            role="tabpanel"
            id="panel-auction"
            aria-labelledby="tab-auction"
            hidden={activeInfoTab !== "auction"}
            aria-hidden={activeInfoTab !== "auction"}
          >
            <>
              <p style={styles.mechanicsLead}>
                Use the auction flow to sell items directly to players or check server floor pricing before listing.
              </p>
              <ul style={styles.mechanicsList}>
                <li style={styles.mechanicsItem}>
                  Hold the item you want to sell, then use{" "}
                  <code style={styles.mechanicsCode}>/sell &lt;amount&gt; &lt;price&gt;</code> to create a listing.
                </li>
                <li style={styles.mechanicsItem}>
                  Use <code style={styles.mechanicsCode}>/auction</code> to view current market offers and price levels.
                </li>
                <li style={styles.mechanicsItem}>
                  If you prefer guaranteed floor value, use{" "}
                  <code style={styles.mechanicsCode}>/sellserver &lt;amount&gt;</code> for server pricing.
                </li>
                <li style={styles.mechanicsItem}>
                  Use <code style={styles.mechanicsCode}>/mylistings</code> to review active listings and cancel them any
                  time.
                </li>
              </ul>
            </>
          </div>

          <div
            role="tabpanel"
            id="panel-mechanics"
            aria-labelledby="tab-mechanics"
            hidden={activeInfoTab !== "mechanics"}
            aria-hidden={activeInfoTab !== "mechanics"}
          >
            <>
              <p style={styles.mechanicsLead}>
                Cabal SMP runs <strong style={{ color: "var(--color-text-primary)" }}>Minecraft 26.2 Snapshot 2</strong> as{" "}
                <strong style={{ color: "var(--color-text-primary)" }}>vanilla-style survival</strong> with light
                Fabric server-side mods (no client mods needed).
              </p>
              <ul style={styles.mechanicsList}>
                <li style={styles.mechanicsItem}>
                  <span style={styles.mechanicsLabel}>Java Edition</span> —{" "}
                  <code style={styles.mechanicsCode}>
                    {content?.connectAddress ?? "minecraft.thecabal.app"}:{JAVA_PORT}
                  </code>{" "}
                  (TCP). 26.2 Snapshot 2 clients only.
                </li>
                <li style={styles.mechanicsItem}>
                  Overworld border is roughly <strong style={{ color: "var(--color-text-primary)" }}>10,000 blocks edge
                  to edge</strong>; Nether and End are scaled to keep travel sensible.
                </li>
              </ul>
            </>
          </div>
        </div>
      </section>

      {/* Updates (paginated: 5 at a time + load more) */}
      {content && updatesLen > 0 && (
        <section style={styles.section}>
          <h2 style={styles.sectionTitle}>Latest Updates</h2>
          <div style={styles.updates}>
            {content.updates.slice(0, updatesVisible).map((u, i) => (
              <div key={`${u.date}-${u.title}-${i}`} style={styles.updateItem}>
                <div style={styles.updateHeader}>
                  <span style={styles.updateDate}>{u.date}</span>
                  <span style={styles.updateTitle}>{u.title}</span>
                </div>
                <p style={styles.updateBody}>{renderUpdateBody(u.body)}</p>
              </div>
            ))}
          </div>
          {updatesVisible < updatesLen && (
            <button
              type="button"
              className="updates-load-more"
              style={styles.loadMoreBtn}
              onClick={() => {
                if (!content) return;
                setUpdatesVisible((v) =>
                  Math.min(v + UPDATES_STEP, content.updates.length)
                );
              }}
            >
              Load more
              <span style={styles.loadMoreHint}>
                {" "}
                ({updatesLen - updatesVisible} older)
              </span>
            </button>
          )}
        </section>
      )}
      </main>

      <Footer />
    </>
  );
}

const styles: Record<string, React.CSSProperties> = {
  header: {
    textAlign: "center",
    marginBottom: 32,
    paddingTop: 16,
  },
  title: {
    fontSize: 34,
    color: "var(--color-primary)",
    letterSpacing: 1.5,
  },
  subtitle: {
    fontSize: 13,
    color: "var(--color-text-secondary)",
    marginTop: 4,
    fontFamily: "var(--font-body)",
  },
  discordLink: {
    display: "inline-block",
    marginTop: 16,
    padding: "10px 20px",
    background: "var(--color-primary)",
    color: "#faf8f3",
    textDecoration: "none",
    borderRadius: 6,
    fontSize: 12,
    fontFamily: "var(--font-body)",
    fontWeight: 600,
    border: "1px solid rgba(42, 35, 25, 0.12)",
    boxShadow: "0 2px 0 rgba(42, 35, 25, 0.15)",
  },
  discordSecondary: {
    display: "inline-block",
    marginTop: 14,
    fontSize: 13,
    color: "var(--color-primary)",
    fontWeight: 600,
    textDecoration: "underline",
    textUnderlineOffset: 3,
  },
  error: {
    background: "rgba(180, 83, 9, 0.12)",
    border: "1px solid #b45309",
    borderRadius: 6,
    padding: "12px 16px",
    marginBottom: 20,
    color: "#92400e",
  },
  grid: {
    display: "grid",
    gridTemplateColumns: "repeat(auto-fit, minmax(300px, 1fr))",
    gap: 20,
    marginBottom: 32,
  },
  card: {
    background: "var(--color-surface)",
    borderRadius: 6,
    padding: 24,
    border: "1px solid var(--color-border)",
  },
  cardTitle: {
    fontSize: 16,
    marginBottom: 12,
    color: "var(--color-primary)",
  },
  desc: {
    color: "var(--color-text-secondary)",
    lineHeight: 1.6,
    marginBottom: 16,
    fontSize: 14,
  },
  connect: {
    display: "flex",
    alignItems: "center",
    gap: 10,
    flexWrap: "wrap",
  },
  label: {
    color: "var(--color-text-muted)",
    fontSize: 13,
  },
  code: {
    background: "var(--color-code-bg)",
    padding: "6px 12px",
    borderRadius: 4,
    fontSize: 15,
    fontFamily: "var(--font-body)",
    color: "var(--color-accent-brown)",
    border: "1px solid var(--color-border)",
  },
  copyBtn: {
    display: "inline-flex",
    alignItems: "center",
    justifyContent: "center",
    width: 32,
    height: 32,
    borderRadius: 6,
    border: "1px solid var(--color-border)",
    color: "var(--color-text-secondary)",
    background: "var(--color-surface)",
    cursor: "pointer",
  },
  copyBtnActive: {
    color: "var(--color-primary)",
    borderColor: "var(--color-primary)",
    boxShadow: "0 0 0 1px rgba(76, 175, 80, 0.12) inset",
  },
  srOnly: {
    position: "absolute",
    width: 1,
    height: 1,
    padding: 0,
    margin: -1,
    overflow: "hidden",
    clip: "rect(0, 0, 0, 0)",
    whiteSpace: "nowrap",
    border: 0,
  },
  stat: {
    display: "flex",
    alignItems: "baseline",
    gap: 6,
    marginBottom: 8,
  },
  bigNum: {
    fontSize: 36,
    fontFamily: "var(--font-heading)",
    color: "var(--color-primary)",
  },
  players: {
    color: "var(--color-text-secondary)",
    fontSize: 14,
  },
  section: {
    marginBottom: 32,
  },
  sectionTitle: {
    fontSize: 18,
    marginBottom: 12,
    color: "var(--color-primary)",
  },
  mapHint: {
    color: "var(--color-text-muted)",
    fontSize: 13,
    marginBottom: 10,
  },
  auctionHint: {
    color: "var(--color-text-muted)",
    fontSize: 13,
    marginBottom: 12,
  },
  tabRow: {
    display: "flex",
    gap: 8,
    flexWrap: "wrap",
    marginBottom: 10,
  },
  tabBtn: {
    padding: "8px 12px",
    borderRadius: 6,
    border: "1px solid var(--color-border)",
    background: "var(--color-surface)",
    color: "var(--color-text-secondary)",
    fontSize: 12,
    fontFamily: "var(--font-body)",
    fontWeight: 600,
    cursor: "pointer",
  },
  tabBtnActive: {
    color: "var(--color-primary)",
    borderColor: "var(--color-primary)",
    boxShadow: "0 0 0 1px rgba(76, 175, 80, 0.12) inset",
  },
  legend: {
    display: "flex",
    alignItems: "center",
    gap: 6,
    marginTop: 10,
    fontSize: 13,
    color: "var(--color-text-muted)",
  },
  legendDot: {
    display: "inline-block",
    width: 12,
    height: 12,
    borderRadius: 3,
  },
  mechanicsBox: {
    background: "var(--color-surface)",
    border: "1px solid var(--color-border)",
    borderRadius: 8,
    padding: "18px 20px 16px",
  },
  mechanicsLead: {
    color: "var(--color-text-secondary)",
    fontSize: 14,
    lineHeight: 1.65,
    marginBottom: 16,
  },
  mechanicsConnect: {
    marginTop: 14,
    marginBottom: 8,
    fontSize: 13,
  },
  mechanicsList: {
    listStyle: "none",
    padding: 0,
    margin: "0 0 4px 0",
  },
  mechanicsItem: {
    color: "var(--color-text-secondary)",
    fontSize: 14,
    lineHeight: 1.6,
    marginBottom: 12,
    paddingLeft: 0,
  },
  mechanicsLabel: {
    fontWeight: 700,
    color: "var(--color-primary)",
    fontSize: 12,
    textTransform: "uppercase",
    letterSpacing: "0.06em",
  },
  mechanicsCode: {
    background: "var(--color-code-bg)",
    padding: "2px 7px",
    borderRadius: 4,
    fontSize: 13,
    fontFamily: "var(--font-body)",
    color: "var(--color-accent-brown)",
    border: "1px solid var(--color-border)",
  },
  updates: {
    display: "flex",
    flexDirection: "column",
    gap: 12,
  },
  updateItem: {
    background: "var(--color-surface)",
    borderRadius: 6,
    padding: "16px 20px",
    border: "1px solid var(--color-border)",
  },
  updateHeader: {
    display: "flex",
    alignItems: "center",
    gap: 12,
    marginBottom: 6,
  },
  updateDate: {
    fontSize: 12,
    color: "var(--color-text-muted)",
    background: "var(--color-code-bg)",
    padding: "2px 8px",
    borderRadius: 4,
  },
  updateTitle: {
    fontWeight: 600,
    fontSize: 15,
  },
  updateBody: {
    color: "var(--color-text-secondary)",
    fontSize: 14,
    lineHeight: 1.5,
  },
  updateLink: {
    color: "var(--color-primary)",
    textDecoration: "underline",
    textUnderlineOffset: 3,
    fontWeight: 600,
  },
  loadMoreBtn: {
    marginTop: 14,
    padding: "10px 18px",
    borderRadius: 6,
    fontSize: 13,
    fontFamily: "var(--font-body)",
    fontWeight: 600,
    cursor: "pointer",
    background: "var(--color-surface)",
    color: "var(--color-primary)",
    border: "1px solid var(--color-border)",
    boxShadow: "0 1px 0 rgba(42, 35, 25, 0.08)",
  },
  loadMoreHint: {
    fontWeight: 400,
    color: "var(--color-text-muted)",
    fontSize: 12,
  },
};
