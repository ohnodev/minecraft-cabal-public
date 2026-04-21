import { useRef, useEffect, useState, useId } from "react";
import type { MapData } from "./api";
import type { LivePlayerPosition } from "./api";
import type { LiveEvokerBoss } from "./api";

const GRAY = "#5c564c";
const SPAWN_RING = "#1d6b3a";
const CLAIM_RING = "#a65f2e";
const BOSS_RING = "#d7423a";
const LABEL_COLOR = "#ffffff";
const VIEWPORT_ASPECT = 1;
const ZOOM_OUT_PADDING_BLOCKS = 0;
const BASE_VIEW_PADDING_PX = 24;
const DEFAULT_ZOOM_MULTIPLIER = 5;
const MIN_ZOOM_MULTIPLIER = 1;
const MAX_ZOOM_MULTIPLIER = 14;
const ZOOM_STEP = 1.25;

type ViewState = {
  viewMinX: number;
  viewMinZ: number;
  pxPerBlock: number;
  viewportWorldW: number;
  viewportWorldH: number;
  contentMinX: number;
  contentMaxX: number;
  contentMinZ: number;
  contentMaxZ: number;
};

type LabelMetrics = {
  textWidthPx: number;
  boxWidthPx: number;
  boxHeightPx: number;
};

type DragState = {
  active: boolean;
  x: number;
  y: number;
  pointerId: number | null;
};

type BiomeLayerCache = {
  canvas: HTMLCanvasElement;
  minX: number;
  minZ: number;
  maxX: number;
  maxZ: number;
  pxPerBlock: number;
};

export default function WorldMap({ data, players, boss }: { data: MapData | null; players: LivePlayerPosition[]; boss: LiveEvokerBoss | null }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  const fitPxPerBlockRef = useRef(1);
  const dragRef = useRef<DragState | null>(null);
  const biomeLayerRef = useRef<BiomeLayerCache | null>(null);
  const labelMetricsCacheRef = useRef<Map<string, LabelMetrics>>(new Map());
  const rafRef = useRef<number | null>(null);
  const markerAnimResetRef = useRef<number | null>(null);
  const prevPlayerPositionsRef = useRef<Map<string, { x: number; z: number }>>(new Map());
  const [containerWidth, setContainerWidth] = useState(0);
  const [view, setView] = useState<ViewState | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [suppressMarkerAnimation, setSuppressMarkerAnimation] = useState(false);
  const [showLegendPanel, setShowLegendPanel] = useState(false);
  const [showPlayerNameLabels, setShowPlayerNameLabels] = useState(true);
  const [showLandOwnerLabels, setShowLandOwnerLabels] = useState(true);
  const legendPanelId = useId();
  const bossVisible = Boolean(boss?.present && boss?.x != null && boss?.z != null);

  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const ro = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width;
      if (w != null && w > 0) setContainerWidth(Math.floor(w));
    });
    ro.observe(el);
    setContainerWidth(Math.floor(el.clientWidth) || 640);
    return () => ro.disconnect();
  }, []);

  useEffect(() => {
    if (!data || !canvasRef.current) return;
    const canvas = canvasRef.current;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    const { bounds, spawn, claims } = data;
    const parentW = containerWidth > 0 ? containerWidth : (canvas.parentElement?.clientWidth ?? 640);
    const containerW = Math.max(280, Math.floor(parentW));
    const canvasW = Math.floor(containerW);
    const canvasH = Math.floor(containerW / VIEWPORT_ASPECT);
    canvas.width = canvasW;
    canvas.height = canvasH;

    const baseMinX = bounds.minX - ZOOM_OUT_PADDING_BLOCKS;
    const baseMaxX = bounds.maxX + ZOOM_OUT_PADDING_BLOCKS;
    const baseMinZ = bounds.minZ - ZOOM_OUT_PADDING_BLOCKS;
    const baseMaxZ = bounds.maxZ + ZOOM_OUT_PADDING_BLOCKS;
    const baseW = Math.max(1, baseMaxX - baseMinX);
    const baseH = Math.max(1, baseMaxZ - baseMinZ);

    const fitPxPerBlock = Math.min(canvasW / baseW, canvasH / baseH);
    fitPxPerBlockRef.current = fitPxPerBlock;
    // Start significantly closer for readability (requested ~5x zoom-in).
    const pxPerBlock = fitPxPerBlock * DEFAULT_ZOOM_MULTIPLIER;
    const viewportWorldW = canvasW / pxPerBlock;
    const viewportWorldH = canvasH / pxPerBlock;
    labelMetricsCacheRef.current.clear();
    const basePadBlocks = BASE_VIEW_PADDING_PX / pxPerBlock;

    let contentMinX = baseMinX - basePadBlocks;
    let contentMaxX = baseMaxX + basePadBlocks;
    let contentMinZ = baseMinZ - basePadBlocks;
    let contentMaxZ = baseMaxZ + basePadBlocks;

    const includeCircle = (cx: number, cz: number, radius: number, label: string) => {
      const metrics = getLabelMetrics(ctx, label, pxPerBlock, labelMetricsCacheRef.current);
      const labelHalfWidthBlocks = (metrics.boxWidthPx / 2 + BASE_VIEW_PADDING_PX) / pxPerBlock;
      const labelHalfHeightBlocks = (metrics.boxHeightPx / 2 + BASE_VIEW_PADDING_PX) / pxPerBlock;
      const horizontalPadBlocks = Math.max(labelHalfWidthBlocks, basePadBlocks);
      const verticalPadBlocks = Math.max(labelHalfHeightBlocks, basePadBlocks);
      contentMinX = Math.min(contentMinX, cx - radius - horizontalPadBlocks);
      contentMaxX = Math.max(contentMaxX, cx + radius + horizontalPadBlocks);
      contentMinZ = Math.min(contentMinZ, cz - radius - verticalPadBlocks);
      contentMaxZ = Math.max(contentMaxZ, cz + radius + verticalPadBlocks);
    };

    const showSpawn = data.dimension === "overworld";
    const bossX = boss?.x;
    const bossZ = boss?.z;
    const showBoss = boss?.present && bossX != null && bossZ != null;
    if (showSpawn) {
      includeCircle(spawn.x, spawn.z, spawn.protectionRadius, "SPAWN");
    }
    for (const claim of claims) includeCircle(claim.x, claim.z, claim.radius, claim.ownerName);
    if (showBoss) {
      includeCircle(bossX, bossZ, boss.markerRadius, "EVOKER BOSS");
    }

    const centerX = (baseMinX + baseMaxX) / 2;
    const centerZ = (baseMinZ + baseMaxZ) / 2;

    let nextMinX = centerX - viewportWorldW / 2;
    let nextMinZ = centerZ - viewportWorldH / 2;

    const maxViewMinX = Math.max(contentMinX, contentMaxX - viewportWorldW);
    const maxViewMinZ = Math.max(contentMinZ, contentMaxZ - viewportWorldH);
    nextMinX = clamp(nextMinX, contentMinX, maxViewMinX);
    nextMinZ = clamp(nextMinZ, contentMinZ, maxViewMinZ);

    setView({
      viewMinX: nextMinX,
      viewMinZ: nextMinZ,
      pxPerBlock,
      viewportWorldW,
      viewportWorldH,
      contentMinX,
      contentMaxX,
      contentMinZ,
      contentMaxZ,
    });
  }, [data, containerWidth, bossVisible]);

  useEffect(() => {
    if (!data || !canvasRef.current || !view) return;
    const { bounds, step, spawn, claims, gridWidth, gridHeight, paletteColors, palette, grid } = data;
    const { pxPerBlock, contentMinX, contentMaxX, contentMinZ, contentMaxZ } = view;
    const cellPx = Math.max(1, Math.ceil(step * pxPerBlock));
    const layerWidth = Math.max(1, Math.ceil((contentMaxX - contentMinX) * pxPerBlock));
    const layerHeight = Math.max(1, Math.ceil((contentMaxZ - contentMinZ) * pxPerBlock));
    const layerCanvas = document.createElement("canvas");
    layerCanvas.width = layerWidth;
    layerCanvas.height = layerHeight;
    const layerCtx = layerCanvas.getContext("2d");
    if (!layerCtx) return;

    layerCtx.fillStyle = dimensionBaseColor(data.dimension);
    layerCtx.fillRect(0, 0, layerWidth, layerHeight);

    const tintedPalette = paletteColors.map((color) => tintByDimension(color, data.dimension));
    const grayscalePalette = paletteColors.map((color) => tintByDimension(toGrayscale(color), data.dimension));
    const coverageGrid = new Uint8Array(gridWidth * gridHeight);

    const showSpawn = data.dimension === "overworld";
    // Precompute where spawn/claim circles apply so the raster loop can just index.
    for (let xi = 0; xi < gridWidth; xi++) {
      const wx = bounds.minX + xi * step;
      for (let zi = 0; zi < gridHeight; zi++) {
        const wz = bounds.minZ + zi * step;
        const i = xi * gridHeight + zi;
        let covered = showSpawn && isInsideCircle(wx, wz, spawn.x, spawn.z, spawn.protectionRadius);
        if (!covered) {
          for (const claim of claims) {
            if (isInsideCircle(wx, wz, claim.x, claim.z, claim.radius)) {
              covered = true;
              break;
            }
          }
        }
        coverageGrid[i] = covered ? 1 : 0;
      }
    }

    // wx outer, wz inner → i = xi * gridHeight + zi
    for (let xi = 0; xi < gridWidth; xi++) {
      const wx = bounds.minX + xi * step;
      for (let zi = 0; zi < gridHeight; zi++) {
        const wz = bounds.minZ + zi * step;
        const i = xi * gridHeight + zi;
        const paletteIdx = grid[i] ?? 0;
        const biomeName = (palette[paletteIdx] ?? "").toLowerCase();
        const isUnknownBiome = biomeName === "unknown" || biomeName.endsWith(":unknown");
        const color = isUnknownBiome
          ? dimensionUnknownGray(data.dimension)
          : (tintedPalette[paletteIdx] ?? dimensionBaseColor(data.dimension));
        const gray = isUnknownBiome
          ? dimensionUnknownGray(data.dimension)
          : (grayscalePalette[paletteIdx] ?? dimensionUnknownGray(data.dimension));
        const px = Math.floor((wx - contentMinX) * pxPerBlock);
        const py = Math.floor((wz - contentMinZ) * pxPerBlock);
        if (px + cellPx < 0 || py + cellPx < 0 || px > layerWidth || py > layerHeight) continue;
        layerCtx.fillStyle = coverageGrid[i] === 1 ? color : gray;
        layerCtx.fillRect(px, py, cellPx, cellPx);
      }
    }

    biomeLayerRef.current = {
      canvas: layerCanvas,
      minX: contentMinX,
      minZ: contentMinZ,
      maxX: contentMaxX,
      maxZ: contentMaxZ,
      pxPerBlock,
    };
  }, [data, view?.pxPerBlock, view?.contentMinX, view?.contentMaxX, view?.contentMinZ, view?.contentMaxZ]);

  useEffect(() => {
    if (!data || !canvasRef.current || !view) return;
    const canvas = canvasRef.current;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    const layer = biomeLayerRef.current;
    if (!layer) return;

    const showSpawn = data.dimension === "overworld";
    if (rafRef.current != null) cancelAnimationFrame(rafRef.current);
    rafRef.current = requestAnimationFrame(() => {
      ctx.fillStyle = dimensionBaseColor(data.dimension);
      ctx.fillRect(0, 0, canvas.width, canvas.height);

      const dx = Math.floor((layer.minX - view.viewMinX) * view.pxPerBlock);
      const dy = Math.floor((layer.minZ - view.viewMinZ) * view.pxPerBlock);
      ctx.drawImage(layer.canvas, dx, dy);

      if (showSpawn) {
        drawCircle(
          ctx,
          data.spawn.x,
          data.spawn.z,
          data.spawn.protectionRadius,
          view.viewMinX,
          view.viewMinZ,
          view.pxPerBlock,
          SPAWN_RING
        );
        drawLabel(
          ctx,
          data.spawn.x,
          data.spawn.z,
          "SPAWN",
          view.viewMinX,
          view.viewMinZ,
          view.pxPerBlock,
          SPAWN_RING,
          labelMetricsCacheRef.current
        );
      }

      for (const claim of data.claims) {
        drawCircle(ctx, claim.x, claim.z, claim.radius, view.viewMinX, view.viewMinZ, view.pxPerBlock, CLAIM_RING);
        if (showLandOwnerLabels) {
          drawLabel(
            ctx,
            claim.x,
            claim.z,
            claim.ownerName,
            view.viewMinX,
            view.viewMinZ,
            view.pxPerBlock,
            CLAIM_RING,
            labelMetricsCacheRef.current
          );
        }
      }

      if (boss?.present && boss.x != null && boss.z != null) {
        const markerX = boss.x;
        const markerZ = boss.z;
        drawCircle(
          ctx,
          markerX,
          markerZ,
          boss.markerRadius,
          view.viewMinX,
          view.viewMinZ,
          view.pxPerBlock,
          BOSS_RING
        );
        drawLabel(
          ctx,
          markerX,
          markerZ,
          "EVOKER BOSS",
          view.viewMinX,
          view.viewMinZ,
          view.pxPerBlock,
          BOSS_RING,
          labelMetricsCacheRef.current
        );
      }
      rafRef.current = null;
    });

    return () => {
      if (rafRef.current != null) {
        cancelAnimationFrame(rafRef.current);
        rafRef.current = null;
      }
    };
  }, [data, view, showLandOwnerLabels, boss]);

  useEffect(() => {
    return () => {
      if (rafRef.current != null) {
        cancelAnimationFrame(rafRef.current);
      }
      if (markerAnimResetRef.current != null) {
        window.clearTimeout(markerAnimResetRef.current);
      }
    };
  }, []);

  useEffect(() => {
    const next = new Map<string, { x: number; z: number }>();
    for (const p of players) {
      next.set(p.uuid, { x: p.x, z: p.z });
    }
    prevPlayerPositionsRef.current = next;
  }, [players]);

  const suppressMarkerAnimationTemporarily = (ms: number) => {
    setSuppressMarkerAnimation(true);
    if (markerAnimResetRef.current != null) {
      window.clearTimeout(markerAnimResetRef.current);
    }
    markerAnimResetRef.current = window.setTimeout(() => {
      setSuppressMarkerAnimation(false);
      markerAnimResetRef.current = null;
    }, ms);
  };

  const handlePointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (dragRef.current?.active) return;
    if (!view) return;
    if (!e.isPrimary) return;
    if (e.pointerType === "mouse" && e.button !== 0) return;
    dragRef.current = { active: true, x: e.clientX, y: e.clientY, pointerId: e.pointerId };
    setIsDragging(true);
    e.currentTarget.setPointerCapture(e.pointerId);
  };

  const handlePointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const drag = dragRef.current;
    if (!view || !drag?.active) return;
    if (drag.pointerId == null || e.pointerId !== drag.pointerId) return;
    const dxPx = e.clientX - drag.x;
    const dyPx = e.clientY - drag.y;
    drag.x = e.clientX;
    drag.y = e.clientY;

    setView((prev) => {
      if (!prev) return prev;
      const maxViewMinX = Math.max(prev.contentMinX, prev.contentMaxX - prev.viewportWorldW);
      const maxViewMinZ = Math.max(prev.contentMinZ, prev.contentMaxZ - prev.viewportWorldH);
      const nextMinX = clamp(prev.viewMinX - dxPx / prev.pxPerBlock, prev.contentMinX, maxViewMinX);
      const nextMinZ = clamp(prev.viewMinZ - dyPx / prev.pxPerBlock, prev.contentMinZ, maxViewMinZ);
      if (nextMinX === prev.viewMinX && nextMinZ === prev.viewMinZ) return prev;
      return { ...prev, viewMinX: nextMinX, viewMinZ: nextMinZ };
    });
  };

  const endDrag = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const drag = dragRef.current;
    if (!drag?.active) return;
    if (drag.pointerId == null || e.pointerId !== drag.pointerId) return;
    drag.active = false;
    drag.pointerId = null;
    setIsDragging(false);
    if (e.currentTarget.hasPointerCapture(e.pointerId)) {
      e.currentTarget.releasePointerCapture(e.pointerId);
    }
  };

  const applyZoom = (direction: "in" | "out") => {
    // Snap marker positions on viewport-scale changes; keep smooth animation for live position updates.
    suppressMarkerAnimationTemporarily(220);
    setView((prev) => {
      if (!prev) return prev;
      const fitPxPerBlock = fitPxPerBlockRef.current;
      const minPxPerBlock = fitPxPerBlock * MIN_ZOOM_MULTIPLIER;
      const maxPxPerBlock = fitPxPerBlock * MAX_ZOOM_MULTIPLIER;
      const factor = direction === "in" ? ZOOM_STEP : 1 / ZOOM_STEP;
      const nextPxPerBlock = clamp(prev.pxPerBlock * factor, minPxPerBlock, maxPxPerBlock);
      if (nextPxPerBlock === prev.pxPerBlock) return prev;

      const centerX = prev.viewMinX + prev.viewportWorldW / 2;
      const centerZ = prev.viewMinZ + prev.viewportWorldH / 2;
      const viewportWorldW = (canvasRef.current?.width ?? 0) / nextPxPerBlock;
      const viewportWorldH = (canvasRef.current?.height ?? 0) / nextPxPerBlock;
      const maxViewMinX = Math.max(prev.contentMinX, prev.contentMaxX - viewportWorldW);
      const maxViewMinZ = Math.max(prev.contentMinZ, prev.contentMaxZ - viewportWorldH);
      const viewMinX = clamp(centerX - viewportWorldW / 2, prev.contentMinX, maxViewMinX);
      const viewMinZ = clamp(centerZ - viewportWorldH / 2, prev.contentMinZ, maxViewMinZ);

      return {
        ...prev,
        pxPerBlock: nextPxPerBlock,
        viewportWorldW,
        viewportWorldH,
        viewMinX,
        viewMinZ,
      };
    });
  };

  if (!data) {
    return (
      <div
        ref={wrapRef}
        className="world-map-root"
        style={{
          width: "100%",
          minHeight: 0,
          aspectRatio: "1 / 1",
          overflow: "hidden",
          borderRadius: 8,
          border: "1px solid var(--color-border)",
          background: "var(--color-surface-secondary)",
          boxShadow: "inset 0 2px 8px rgba(42, 35, 25, 0.06)",
        }}
      >
        <div className="map-skeleton" role="status" aria-live="polite" aria-label="Loading map">
          <div className="map-skeleton-grid" />
          <div className="map-skeleton-pulse map-skeleton-pulse-a" />
          <div className="map-skeleton-pulse map-skeleton-pulse-b" />
          <div className="map-skeleton-label">Loading map...</div>
        </div>
      </div>
    );
  }

  return (
    <div
      ref={wrapRef}
      className="world-map-root"
      style={{
        width: "100%",
        minHeight: 0,
        aspectRatio: "1 / 1",
        overflow: "hidden",
        position: "relative",
        borderRadius: 8,
        border: "1px solid var(--color-border)",
        background: "var(--color-surface-secondary)",
        boxShadow: "inset 0 2px 8px rgba(42, 35, 25, 0.06)",
      }}
    >
      <canvas
        ref={canvasRef}
        style={{
          display: "block",
          width: "100%",
          height: "100%",
          imageRendering: "pixelated",
          cursor: isDragging ? "grabbing" : "grab",
          touchAction: "none",
        }}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
      />
      {view && (() => {
        // Intentionally ignore LivePlayerPosition.sampledAtMs on frontend:
        // keep rendering the latest known coordinates without stale gray-out.
        return players.map((p) => {
        const left = (p.x - view.viewMinX) * view.pxPerBlock;
        const top = (p.z - view.viewMinZ) * view.pxPerBlock;
        const prev = prevPlayerPositionsRef.current.get(p.uuid);
        const movedSinceLastSample = !prev || prev.x !== p.x || prev.z !== p.z;
        const animateMarker = !isDragging && !suppressMarkerAnimation && movedSinceLastSample;
        if (left < -24 || top < -24 || left > (canvasRef.current?.width ?? 0) + 24 || top > (canvasRef.current?.height ?? 0) + 24) {
          return null;
        }
        return (
          <div
            key={p.uuid}
            title={`${p.name} (${Math.round(p.x)}, ${Math.round(p.z)})`}
            style={{
              position: "absolute",
              left,
              top,
              transform: "translate(-50%, -50%)",
              pointerEvents: "none",
              transition: animateMarker ? "left 1.8s linear, top 1.8s linear" : "none",
              opacity: 1,
              zIndex: 5,
            }}
          >
            <div
              style={{
                width: 12,
                height: 12,
                borderRadius: "50%",
                background: "rgba(86, 181, 255, 0.95)",
                border: "2px solid rgba(255,255,255,0.9)",
                boxShadow: "0 0 10px rgba(86, 181, 255, 0.55)",
              }}
            />
            {showPlayerNameLabels && (
              <div
                style={{
                  position: "absolute",
                  top: 16,
                  left: "50%",
                  padding: "2px 6px",
                  borderRadius: 4,
                  background: "rgba(9, 15, 12, 0.85)",
                  border: "1px solid rgba(120, 170, 145, 0.45)",
                  color: "#dceee2",
                  fontSize: 11,
                  lineHeight: 1.2,
                  whiteSpace: "nowrap",
                  textAlign: "center",
                  transform: "translateX(-50%)",
                }}
              >
                {p.name}
              </div>
            )}
          </div>
        );
      });
      })()}
      <div
        style={{
          position: "absolute",
          top: 10,
          left: 10,
          zIndex: 6,
          display: "grid",
          gap: 6,
        }}
      >
        <button
          type="button"
          aria-label="Toggle map legend and labels"
          aria-expanded={showLegendPanel}
          aria-controls={legendPanelId}
          onClick={() => setShowLegendPanel((v) => !v)}
          style={infoButtonStyle}
        >
          ⓘ
        </button>
        {showLegendPanel && (
          <div
            id={legendPanelId}
            role="region"
            aria-label="Map legend and label visibility controls"
            style={{
              minWidth: 184,
              maxWidth: 220,
              padding: "8px 10px",
              borderRadius: 8,
              border: "1px solid rgba(120, 170, 145, 0.35)",
              background: "rgba(9, 15, 12, 0.92)",
              color: "#dceee2",
              fontSize: 12,
              lineHeight: 1.35,
              boxShadow: "0 6px 18px rgba(0,0,0,0.28)",
            }}
          >
            <div style={{ fontWeight: 700, marginBottom: 6 }}>Map Legend</div>
            <div style={{ display: "flex", alignItems: "center", gap: 6, marginBottom: 4 }}>
              <span style={{ width: 10, height: 10, borderRadius: "50%", background: "rgba(86, 181, 255, 0.95)", border: "1px solid rgba(255,255,255,0.85)" }} />
              <span>Player position</span>
            </div>
            <div style={{ display: "flex", alignItems: "center", gap: 6, marginBottom: 6 }}>
              <span style={{ width: 10, height: 10, borderRadius: 2, background: CLAIM_RING }} />
              <span>Claim boundary / owner</span>
            </div>
            <button
              type="button"
              onClick={() => setShowPlayerNameLabels((v) => !v)}
              aria-pressed={showPlayerNameLabels}
              style={legendToggleButtonStyle}
            >
              Player names: {showPlayerNameLabels ? "On" : "Off"}
            </button>
            <button
              type="button"
              onClick={() => setShowLandOwnerLabels((v) => !v)}
              aria-pressed={showLandOwnerLabels}
              style={{ ...legendToggleButtonStyle, marginTop: 6 }}
            >
              Land labels: {showLandOwnerLabels ? "On" : "Off"}
            </button>
          </div>
        )}
      </div>
      <div
        style={{
          position: "absolute",
          top: 10,
          right: 10,
          display: "grid",
          gap: 6,
          zIndex: 4,
        }}
      >
        <button
          type="button"
          aria-label="Zoom in map"
          onClick={() => applyZoom("in")}
          style={zoomButtonStyle}
        >
          +
        </button>
        <button
          type="button"
          aria-label="Zoom out map"
          onClick={() => applyZoom("out")}
          style={zoomButtonStyle}
        >
          -
        </button>
      </div>
    </div>
  );
}

const baseControlButtonStyle: React.CSSProperties = {
  width: 28,
  height: 28,
  borderRadius: 6,
  border: "1px solid var(--color-primary-dark)",
  background: "var(--color-primary)",
  color: "var(--color-primary-contrast)",
  fontFamily: "var(--font-body)",
  fontSize: 14,
  fontWeight: 700,
  lineHeight: "26px",
  textAlign: "center",
  boxShadow: "var(--shadow-sm)",
};

const infoButtonStyle: React.CSSProperties = {
  ...baseControlButtonStyle,
};

const zoomButtonStyle: React.CSSProperties = {
  ...baseControlButtonStyle,
  fontSize: 16,
};

const legendToggleButtonStyle: React.CSSProperties = {
  width: "100%",
  textAlign: "left",
  padding: "5px 7px",
  borderRadius: 6,
  border: "1px solid rgba(120, 170, 145, 0.35)",
  background: "rgba(18, 28, 23, 0.95)",
  color: "#dceee2",
  fontSize: 12,
  lineHeight: 1.2,
};

function drawCircle(
  ctx: CanvasRenderingContext2D,
  cx: number,
  cz: number,
  r: number,
  minX: number,
  minZ: number,
  pxPerBlock: number,
  color: string
) {
  const px = (cx - minX) * pxPerBlock;
  const py = (cz - minZ) * pxPerBlock;
  const pr = r * pxPerBlock;
  ctx.strokeStyle = color;
  ctx.lineWidth = 1.5;
  ctx.setLineDash([5, 4]);
  ctx.beginPath();
  ctx.arc(px, py, pr, 0, Math.PI * 2);
  ctx.stroke();
  ctx.setLineDash([]);
}

function drawLabel(
  ctx: CanvasRenderingContext2D,
  cx: number,
  cz: number,
  label: string,
  minX: number,
  minZ: number,
  pxPerBlock: number,
  bgColor: string,
  cache?: Map<string, LabelMetrics>
) {
  const px = (cx - minX) * pxPerBlock;
  const py = (cz - minZ) * pxPerBlock;
  const metrics = getLabelMetrics(ctx, label, pxPerBlock, cache);
  ctx.fillStyle = bgColor;
  ctx.globalAlpha = 0.92;
  ctx.fillRect(px - metrics.boxWidthPx / 2, py - metrics.boxHeightPx / 2, metrics.boxWidthPx, metrics.boxHeightPx);
  ctx.globalAlpha = 1;
  ctx.fillStyle = LABEL_COLOR;
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.fillText(label, px, py);
}

function isInsideCircle(x: number, z: number, cx: number, cz: number, radius: number): boolean {
  const dx = x - cx;
  const dz = z - cz;
  return dx * dx + dz * dz <= radius * radius;
}

function toGrayscale(hex: string): string {
  const clean = hex.replace("#", "");
  if (clean.length !== 6) return "#3a3a3a";
  const r = parseInt(clean.slice(0, 2), 16);
  const g = parseInt(clean.slice(2, 4), 16);
  const b = parseInt(clean.slice(4, 6), 16);
  const lum = Math.round(0.299 * r + 0.587 * g + 0.114 * b);
  const toned = Math.max(72, Math.min(200, lum));
  return `rgb(${toned}, ${toned}, ${toned})`;
}

function dimensionBaseColor(dimension: MapData["dimension"]): string {
  if (dimension === "nether") return "#4b2b2b";
  if (dimension === "end") return "#3f3550";
  return GRAY;
}

function dimensionUnknownGray(dimension: MapData["dimension"]): string {
  if (dimension === "nether") return "rgb(118, 76, 68)";
  if (dimension === "end") return "rgb(98, 90, 128)";
  return "#3a3a3a";
}

function tintByDimension(color: string, dimension: MapData["dimension"]): string {
  if (dimension === "overworld") return color;
  const rgb = parseCssColorToRgb(color);
  if (!rgb) return color;
  const [r, g, b] = rgb;

  // Warm, lava-like tint for Nether.
  if (dimension === "nether") {
    return mixRgb(r, g, b, 124, 52, 36, 0.42);
  }
  // Soft void-purple tint for End.
  if (dimension === "end") {
    return mixRgb(r, g, b, 120, 98, 170, 0.4);
  }
  return color;
}

function mixRgb(r: number, g: number, b: number, tr: number, tg: number, tb: number, ratio: number): string {
  const rr = Math.round(r * (1 - ratio) + tr * ratio);
  const gg = Math.round(g * (1 - ratio) + tg * ratio);
  const bb = Math.round(b * (1 - ratio) + tb * ratio);
  return `rgb(${rr}, ${gg}, ${bb})`;
}

function parseCssColorToRgb(input: string): [number, number, number] | null {
  const v = input.trim().toLowerCase();
  if (v.startsWith("#")) {
    const clean = v.slice(1);
    if (clean.length === 3) {
      const r = parseInt(clean[0] + clean[0], 16);
      const g = parseInt(clean[1] + clean[1], 16);
      const b = parseInt(clean[2] + clean[2], 16);
      return Number.isFinite(r) && Number.isFinite(g) && Number.isFinite(b) ? [r, g, b] : null;
    }
    if (clean.length === 6) {
      const r = parseInt(clean.slice(0, 2), 16);
      const g = parseInt(clean.slice(2, 4), 16);
      const b = parseInt(clean.slice(4, 6), 16);
      return Number.isFinite(r) && Number.isFinite(g) && Number.isFinite(b) ? [r, g, b] : null;
    }
    return null;
  }
  const m = v.match(/^rgba?\(([^)]+)\)$/);
  if (!m) return null;
  const parts = m[1].split(",").map((p) => p.trim());
  if (parts.length < 3) return null;
  const r = Number(parts[0]);
  const g = Number(parts[1]);
  const b = Number(parts[2]);
  if (!Number.isFinite(r) || !Number.isFinite(g) || !Number.isFinite(b)) return null;
  return [Math.max(0, Math.min(255, Math.round(r))), Math.max(0, Math.min(255, Math.round(g))), Math.max(0, Math.min(255, Math.round(b)))];
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

function getLabelMetrics(
  ctx: CanvasRenderingContext2D,
  label: string,
  pxPerBlock: number,
  cache?: Map<string, LabelMetrics>
): LabelMetrics {
  const fontPx = getDerivedFontPx(pxPerBlock);
  const key = `${label}|${fontPx}`;
  if (cache?.has(key)) {
    return cache.get(key)!;
  }
  const pad = 3;
  ctx.font = `600 ${fontPx}px "JetBrains Mono", monospace`;
  const measured = ctx.measureText(label);
  const ascent = measured.actualBoundingBoxAscent || Math.round(fontPx * 0.75);
  const descent = measured.actualBoundingBoxDescent || Math.round(fontPx * 0.25);
  const textHeightPx = Math.max(1, ascent + descent);
  const textWidthPx = measured.width;
  const metrics = {
    textWidthPx,
    boxWidthPx: textWidthPx + pad * 2,
    boxHeightPx: textHeightPx + pad * 2,
  };
  if (cache) cache.set(key, metrics);
  return metrics;
}

function getDerivedFontPx(pxPerBlock: number): number {
  return Math.max(10, Math.floor(pxPerBlock * 3.2));
}
