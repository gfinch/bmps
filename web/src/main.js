// Live-only frontend: consume candle events from core's embedded WS and apply to chart.
// Uses the global `LightweightCharts` UMD bundle loaded by index.html.

const rootChartEl = document.getElementById('chart');

// We will render two chart views inside the root element: the Plan chart (default visible)
// and an (initially empty) Trade chart. Most existing code expects `chartContainer` to
// refer to the visible chart container; set that to the Plan view so little else needs
// to change.
const planView = document.createElement('div');
planView.id = 'planChartView';
planView.style.position = 'relative';
planView.style.width = '100%';
planView.style.height = '100%';
// Trade view is created and kept hidden until the user clicks Trade
const tradeView = document.createElement('div');
tradeView.id = 'tradeChartView';
tradeView.style.position = 'relative';
tradeView.style.width = '100%';
tradeView.style.height = '100%';
tradeView.style.display = 'none';
// append plan then trade into the root container
if (rootChartEl) {
  rootChartEl.appendChild(planView);
  rootChartEl.appendChild(tradeView);
}

// Keep chartContainer referencing the Plan view to preserve existing logic
const chartContainer = planView;

function createChart(container = chartContainer) {
  const width = container.clientWidth;
  const height = Math.max(200, container.clientHeight);
  return LightweightCharts.createChart(container, {
    width,
    height,
    layout: { backgroundColor: '#ffffff', textColor: '#333' },
    grid: { vertLines: { color: '#f0f3f7' }, horzLines: { color: '#f0f3f7' } },
    rightPriceScale: { scaleMargins: { top: 0.1, bottom: 0.1 } },
    // force x-axis labels to show in US/Eastern (New York) timezone
    localization: {
      timeFormatter: (time) => {
        try {
          // time is seconds since epoch (number) for this app
          const ts = Number(time) * 1000;
          const d = new Date(ts);
          // show hours:minutes:seconds in 24-hour format
          return new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(d);
        } catch (e) {
          return String(time);
        }
      }
    },
    timeScale: { timeVisible: true, secondsVisible: true }
  });
}

let chart = createChart();
// create a second, empty Trade chart on the hidden tradeView. We'll show it when
// the user first clicks Trade. Keep the instance so we can toggle visibility.
let tradeChart = null;
try { tradeChart = createChart(tradeView); } catch (e) { tradeChart = null; }

// per-view contexts (plan and trade)
const planCtx = makeViewContext(planView, chart);
let tradeCtx = tradeChart ? makeViewContext(tradeView, tradeChart) : null;

// Create view-specific contexts so Plan and Trade can maintain separate series
// and overlays while sharing the same rendering logic.
function makeViewContext(container, chartInstance) {
  const ctx = {};
  ctx.container = container;
  ctx.chart = chartInstance;
  ctx.candleSeries = chartInstance.addCandlestickSeries({ upColor: '#26a69a', downColor: '#ef5350', wickUpColor: '#26a69a', wickDownColor: '#ef5350' });
  ctx.swingMarkers = [];
  ctx.planZones = new Map();
  ctx.orderBoxes = new Map();
  ctx.daytimeExtremes = new Map();
  ctx.overlayCanvas = null;
  ctx.overlayCtx = null;
  ctx.lastBarTime = null; // seconds
  return ctx;
}

function safeNumber(v) {
  if (v === undefined || v === null) return null;
  if (typeof v === 'number') return v;
  if (typeof v === 'string') return parseFloat(v);
  if (typeof v === 'object') {
    if ('value' in v) return safeNumber(v.value);
    if ('level' in v) return safeNumber(v.level);
  }
  return null;
}

// Detect PhaseComplete events robustly. The server may encode eventType as
// an object like { PhaseComplete: {} } or as a string; handle both shapes.
function isPhaseCompleteEvent(event) {
  try {
    if (!event || !event.eventType) return false;
    const et = event.eventType;
    if (typeof et === 'string') {
      return String(et).toLowerCase().includes('phasecomplete');
    }
    if (typeof et === 'object') {
      try {
        const keys = Object.keys(et || {});
        if (!keys || !keys.length) return false;
        return keys.some(k => String(k).toLowerCase().includes('phasecomplete'));
      } catch (_) { return false; }
    }
    return false;
  } catch (_) { return false; }
}

function toZoneKey(z) {
  const t = Math.floor(Number(z.startTime || z.timestamp || 0));
  const low = safeNumber(z.low || z.lowLevel || z.value || z.lowPrice) || 0;
  const high = safeNumber(z.high || z.highLevel || z.highPrice) || 0;
  const rawType = (z.planZoneType || z.zoneType || '').toString();
  const type = rawType.toLowerCase();
  return `${t}:${low}:${high}:${type}`;
}

function ensureZoneSeries(ctx, key, zone) {
  const zones = ctx.planZones;
  if (zones.has(key)) return zones.get(key);
  // determine coords
  const low = Number(safeNumber(zone.low));
  const high = Number(safeNumber(zone.high));
  const typeRaw = (zone.planZoneType || zone.zoneType || '').toString().toLowerCase();
  const isSupply = typeRaw.includes('supply');
  // area placeholder (we draw fills on canvas overlays)
  // area placeholder (we draw fills and edge lines on canvas overlays).
  // Historically we also added chart line series for the zone edges, which caused
  // duplicate rendering (lines on both chart and canvas). Stop creating those
  // series and keep top/bottom as null so the overlay is the single source of
  // truth for visual rendering of zones.
  const area = null;
  const top = null;
  const bottom = null;
  const entry = { area, top, bottom, meta: { low, high, startTime: Math.floor(Number(zone.startTime) / 1000), endTime: zone.endTime ? Math.floor(Number(zone.endTime) / 1000) : null, type: typeRaw } };
  zones.set(key, entry);
  return entry;
}

function setZoneSeriesData(ctx, entry) {
  const { top, bottom, meta } = entry;
  // We no longer maintain line series on the chart for zone edges. Update the
  // entry.meta (already set by callers) and trigger a canvas redraw so the
  // overlay becomes the single renderer for fills and edge lines.
  try {
    // ensure meta.startTime/endTime are numbers (seconds)
    meta.startTime = Math.floor(Number(meta.startTime));
    meta.endTime = meta.endTime ? Math.floor(Number(meta.endTime)) : meta.endTime;
    // trigger overlay redraw for this ctx
    drawZones(ctx);
  } catch (e) {
    console.error('failed setting zone series data', e);
  }
}

// Overlay canvas for zone fills
function initOverlay(ctx) {
  if (ctx.overlayCanvas) return;
  const overlayCanvas = document.createElement('canvas');
  overlayCanvas.style.position = 'absolute';
  overlayCanvas.style.left = '0';
  overlayCanvas.style.top = '0';
  overlayCanvas.style.width = '100%';
  overlayCanvas.style.height = '100%';
  overlayCanvas.style.pointerEvents = 'none';
  overlayCanvas.style.zIndex = '5';
  ctx.container.appendChild(overlayCanvas);
  ctx.overlayCanvas = overlayCanvas;
  ctx.overlayCtx = overlayCanvas.getContext('2d');
  resizeOverlay(ctx);
  // If there are any existing line series on entries (older code), remove them
  // so the overlay becomes the single renderer and we avoid duplicate lines.
  try {
    for (const entry of ctx.planZones.values()) {
      try {
        if (entry.top && typeof entry.top.remove === 'function') {
          entry.top.remove();
          entry.top = null;
        }
      } catch (_) { }
      try {
        if (entry.bottom && typeof entry.bottom.remove === 'function') {
          entry.bottom.remove();
          entry.bottom = null;
        }
      } catch (_) { }
    }
  } catch (_) { }

  // Keep canvas synchronized with chart pan/zoom/price changes. Different
  // versions of the LightweightCharts API expose slightly different
  // subscription method names; subscribe defensively to whichever exists.
  try {
    const ts = ctx.chart.timeScale();
    if (ts) {
      if (typeof ts.subscribeVisibleTimeRangeChange === 'function') ts.subscribeVisibleTimeRangeChange(() => drawZones(ctx));
      if (typeof ts.subscribeVisibleLogicalRangeChange === 'function') ts.subscribeVisibleLogicalRangeChange(() => drawZones(ctx));
    }
  } catch (_) { }
  try {
    // price range/zoom changes should also trigger redraw. Attempt common
    // priceScale subscription points.
    if (typeof ctx.chart.subscribePriceScale === 'function') {
      ctx.chart.subscribePriceScale(() => drawZones(ctx));
    }
    // try subscribing via the series price scale if present
    if (ctx.candleSeries && typeof ctx.candleSeries.priceScale === 'function') {
      try {
        const ps = ctx.chart.priceScale ? ctx.chart.priceScale('right') : null;
        if (ps && typeof ps.subscribeVisibleRangeChange === 'function') ps.subscribeVisibleRangeChange(() => drawZones(ctx));
      } catch (_) { }
    }
  } catch (_) { }
}

function resizeOverlay(ctx) {
  if (!ctx.overlayCanvas) return;
  const dpr = window.devicePixelRatio || 1;
  const w = ctx.container.clientWidth;
  const h = ctx.container.clientHeight;
  ctx.overlayCanvas.width = Math.max(1, Math.floor(w * dpr));
  ctx.overlayCanvas.height = Math.max(1, Math.floor(h * dpr));
  ctx.overlayCanvas.style.width = w + 'px';
  ctx.overlayCanvas.style.height = h + 'px';
  ctx.overlayCtx.setTransform(dpr, 0, 0, dpr, 0, 0);
  drawZones(ctx);
}

function drawZonesBase(ctx) {
  try {
    if (!ctx) return;
  initOverlay(ctx);
  if (!ctx.overlayCtx || !ctx.overlayCanvas) return;
  const { overlayCtx } = ctx;
  // clear using CSS pixel dimensions to avoid scaling artifacts
  const cssW = ctx.container.clientWidth;
  const cssH = ctx.container.clientHeight;
  overlayCtx.clearRect(0, 0, cssW, cssH);
    const timeScale = ctx.chart.timeScale();
    const candleSeriesLocal = ctx.candleSeries;
    ctx.planZones.forEach((entry) => {
      const { meta } = entry;
      const start = meta.startTime;
      const end = meta.endTime || ctx.lastBarTime || start;
      const safeEnd = Math.max(end, start);
      const x0 = timeScale.timeToCoordinate(start);
      const x1 = timeScale.timeToCoordinate(safeEnd);
      if (x0 === null || x1 === null) return; // not visible
      const yTop = candleSeriesLocal.priceToCoordinate(meta.high);
      const yBottom = candleSeriesLocal.priceToCoordinate(meta.low);
      if (yTop === null || yBottom === null) return;
      const x = Math.min(x0, x1);
      const width = Math.abs(x1 - x0);
      const y = Math.min(yTop, yBottom);
  const height = Math.abs(yBottom - yTop);
      if (width <= 0 || height <= 0) return;
      const isSupply = (meta.type || '').includes('supply');
      const isClosed = Boolean(meta.endTime);
      let topColor = isSupply ? '#ff0000' : '#00b050';
      let bottomColor = isSupply ? '#00b050' : '#ff0000';
      const supplyFill = 'rgba(180,130,240,0.18)';
      const demandFill = 'rgba(93, 146, 225, 0.18)';
      const closedColor = '#bdbdbd';
      const closedFill = 'rgba(160,160,160,0.1)';
      const fillStyle = isClosed ? closedFill : (isSupply ? supplyFill : demandFill);
      if (isClosed) { topColor = closedColor; bottomColor = closedColor; }
  // draw rect and top/bottom edges on the canvas overlay
  overlayCtx.save();
  overlayCtx.fillStyle = fillStyle;
  overlayCtx.fillRect(x, y, width, height);
  overlayCtx.strokeStyle = topColor;
  overlayCtx.lineWidth = 2;
  overlayCtx.beginPath();
  overlayCtx.moveTo(x, Math.round(y) + 1);
  overlayCtx.lineTo(x + width, Math.round(y) + 1);
  overlayCtx.stroke();
  overlayCtx.strokeStyle = bottomColor;
  overlayCtx.lineWidth = 2;
  overlayCtx.beginPath();
  overlayCtx.moveTo(x, Math.round(y + height) - 1);
  overlayCtx.lineTo(x + width, Math.round(y + height) - 1);
  overlayCtx.stroke();
  overlayCtx.restore();
    });

    // draw daytime extremes for this ctx
    try {
      const chartWidth = ctx.container.clientWidth;
      ctx.daytimeExtremes.forEach((de) => {
        try {
          const t = de.time;
          const x0 = timeScale.timeToCoordinate(t);
          if (x0 === null) return;
          const y = ctx.candleSeries.priceToCoordinate(de.value);
          if (y === null) return;
          const strokeColor = '#000000';
          const desc = String(de.description || '');
          let abbrev = '';
          try {
            if (desc.toLowerCase().includes('london')) abbrev = 'L';
            else if (desc.toLowerCase().includes('asia')) abbrev = 'A';
            else if (desc.toLowerCase().includes('new york') || desc.toLowerCase().includes('newyork') || desc.toUpperCase().includes('NY')) abbrev = 'NY';
            else if (desc.length) abbrev = desc.trim()[0].toUpperCase();
          } catch (_) { abbrev = '' }
          overlayCtx.save();
          let x1 = chartWidth;
          if (de.endTime) {
            try { const endCoord = timeScale.timeToCoordinate(de.endTime); if (endCoord !== null) x1 = endCoord; } catch (_) { }
          }
          overlayCtx.strokeStyle = strokeColor;
          overlayCtx.lineWidth = 2;
          overlayCtx.beginPath();
          overlayCtx.moveTo(x0, y);
          overlayCtx.lineTo(x1, y);
          overlayCtx.stroke();
          if (abbrev) {
            overlayCtx.fillStyle = '#000000';
            overlayCtx.font = '12px Arial, Helvetica, sans-serif';
            overlayCtx.textBaseline = 'middle';
            overlayCtx.textAlign = 'right';
            const textX = Math.max(4, x0 - 6);
            overlayCtx.fillText(abbrev, textX, y);
          }
          overlayCtx.restore();
        } catch (e) { }
      });
    } catch (e) { }
  } catch (e) {
    console.error('failed drawing zone overlays', e);
  }
}

// integrate orders into the zone drawing pass so overlays are single source
// of truth
function drawZones(ctx) {
  try {
    drawZonesBase(ctx);
    // draw orders on top
    try { drawOrders(ctx); } catch (e) { console.error('failed drawing orders overlay', e); }
  } catch (e) { console.error('drawZones wrapper error', e); }
}

function toOrderKey(o) {
  try {
    // Key orders by normalized start timestamp in seconds. This ensures
    // that multiple messages for the same order (same timestamp) overwrite
    // previous versions. Optionally include orderType for minor disambiguation.
    const t = Math.floor(Number(o.timestamp || o.startTime || o.placedTimestamp || 0) / 1000);
    const type = (o.orderType || o.type || '').toString() || '';
    return `${t}:${type}`;
  } catch (_) { return String(Math.random()); }
}

function normalizeOrderPayload(raw) {
  // extract order object from event shapes
  const o = (raw && (raw.order || raw.Order || raw.payload)) ? (raw.order || raw.Order || raw.payload) : raw;
  if (!o) return null;
  try {
    const startMs = o.timestamp || o.startTime || o.placedTimestamp || Date.now();
    const placedMs = o.placedTimestamp || null;
    const filledMs = o.filledTimestamp || o.filled || null;
    const closedMs = o.closeTimestamp || o.closedTimestamp || o.closed || null;
    const low = safeNumber(o.low && o.low.value ? o.low.value : o.low) || safeNumber(o.lowLevel) || safeNumber(o.stopLoss) || 0;
    const high = safeNumber(o.high && o.high.value ? o.high.value : o.high) || safeNumber(o.highLevel) || safeNumber(o.entryPoint) || 0;
    const entryPoint = safeNumber(o.entryPoint) || null;
    const takeProfit = safeNumber(o.takeProfit) || null;
    const stopLoss = safeNumber(o.stopLoss) || null;
    const statusRaw = (o.status && typeof o.status === 'object') ? Object.keys(o.status)[0] : (o.status || '');
    const status = String(statusRaw).toLowerCase();
    return {
      meta: {
        start: Math.floor(Number(startMs) / 1000),
        placed: placedMs ? Math.floor(Number(placedMs) / 1000) : null,
        filled: filledMs ? Math.floor(Number(filledMs) / 1000) : null,
        closed: closedMs ? Math.floor(Number(closedMs) / 1000) : null,
        low: Number(low),
        high: Number(high),
        entryPoint: entryPoint !== null ? Number(entryPoint) : null,
        takeProfit: takeProfit !== null ? Number(takeProfit) : null,
        stopLoss: stopLoss !== null ? Number(stopLoss) : null,
        status: status,
        raw: o
      }
    };
  } catch (e) { return null; }
}

function handleOrderEvent(event, ctx) {
  try {
    if (!event) return;
    const ord = (event.order || event.Order || event.payload) ? (event.order || event.Order || event.payload) : event;
    if (!ord) return;
    const normalized = normalizeOrderPayload(ord);
    if (!normalized) return;
    const key = toOrderKey(ord);
    // store entry
    ctx.orderBoxes.set(key, normalized);
    // prune if too many
    if (ctx.orderBoxes.size > 500) {
      const k = ctx.orderBoxes.keys().next().value;
      ctx.orderBoxes.delete(k);
    }
    // redraw overlays
    drawZones(ctx);
  } catch (e) { console.error('failed handling order event', e); }
}

// Draw order boxes on the same overlay canvas. Orders are stored in ctx.orderBoxes
// keyed by a deterministic key. Each entry.meta contains numeric times (seconds)
// and numeric price levels. This function follows the rendering rules supplied
// in the request and uses the chart timeScale and series to convert to canvas
// coordinates.
function drawOrders(ctx) {
  try {
    if (!ctx) return;
    initOverlay(ctx);
    if (!ctx.overlayCtx || !ctx.overlayCanvas) return;
    const overlayCtx = ctx.overlayCtx;
    const timeScale = ctx.chart.timeScale();
    const cs = ctx.candleSeries;
    const chartWidth = ctx.container.clientWidth;
    const chartHeight = ctx.container.clientHeight;

    for (const entry of ctx.orderBoxes.values()) {
      try {
        const m = entry.meta;
        // times in seconds
        const start = Math.floor(Number(m.start || m.timestamp || 0));
        const placed = m.placed ? Math.floor(Number(m.placed)) : null;
        const filled = m.filled ? Math.floor(Number(m.filled)) : null;
        const closed = m.closed ? Math.floor(Number(m.closed)) : null;
        const edgeTime = ctx.lastBarTime || start;
        const xStart = timeScale.timeToCoordinate(start);
        const xPlaced = placed ? timeScale.timeToCoordinate(placed) : null;
        const xFilled = filled ? timeScale.timeToCoordinate(filled) : null;
        const xClosed = closed ? timeScale.timeToCoordinate(closed) : null;
        const xEdge = timeScale.timeToCoordinate(edgeTime);
        // fallback to right edge if timeToCoordinate returns null
        const rightEdge = (xEdge === null || xEdge === undefined) ? chartWidth : xEdge;

        const low = Number(m.low || 0);
        const high = Number(m.high || 0);
        const entryPrice = (m.entryPoint !== undefined && m.entryPoint !== null) ? Number(m.entryPoint) : null;
        const takeProfit = (m.takeProfit !== undefined && m.takeProfit !== null) ? Number(m.takeProfit) : null;
        const stopLoss = (m.stopLoss !== undefined && m.stopLoss !== null) ? Number(m.stopLoss) : null;

        // helper to draw a horizontal-time span between two coords and vertical between two prices
        const drawPriceRect = (x0, x1, pA, pB, fillStyle, strokeStyle) => {
          if (x0 === null || x1 === null) return;
          const xa = Math.min(x0, x1);
          const xb = Math.max(x0, x1);
          const yA = cs.priceToCoordinate(pA);
          const yB = cs.priceToCoordinate(pB);
          if (yA === null || yB === null) return;
          const yTop = Math.min(yA, yB);
          const h = Math.abs(yB - yA);
          if (h <= 0 || xb - xa <= 0) return;
          overlayCtx.save();
          overlayCtx.fillStyle = fillStyle;
          overlayCtx.fillRect(xa, yTop, xb - xa, h);
          overlayCtx.strokeStyle = strokeStyle;
          overlayCtx.lineWidth = 2;
          overlayCtx.strokeRect(xa, yTop, xb - xa, h);
          overlayCtx.restore();
        };

        const status = (m.status || '').toString().toLowerCase();

  // Styles
  const redFill = 'rgba(255,0,0,0.18)';
  const greenFill = 'rgba(0,176,80,0.18)';
  const grayFill = 'rgba(160,160,160,0.12)';
  const redStroke = '#ff0000';
  const greenStroke = '#00b050';
  const grayStroke = '#bdbdbd';

        // Render according to rules — mutually exclusive handling so a single
        // order status doesn't produce conflicting overlays.
        const isCancelled = status.includes('cancel') || status.includes('cancelled') || status.includes('canceled');
        const isProfit = status.includes('profit') || status.includes('won');
        const isLoss = status.includes('loss');
        const isPlanned = status.includes('planned') || status.includes('plan');
        const isPlaced = status.includes('placed') || (placed && !filled && !closed && !isCancelled && !isProfit && !isLoss);

        if (isCancelled) {
          // Cancelled: draw gray box from fill->close if filled, otherwise
          // from placed/start -> close (or chart edge). Also render the
          // entry->stopLoss vertical range and a full high->low gray overlay
          const leftX = (xFilled !== null && xFilled !== undefined) ? xFilled : ((xPlaced !== null && xPlaced !== undefined) ? xPlaced : xStart);
          const rightX = (xClosed !== null && xClosed !== undefined) ? xClosed : rightEdge;
          if (leftX !== null && leftX !== undefined && rightX !== null && rightX !== undefined) {
            // full high-low gray overlay for the duration
            drawPriceRect(leftX, rightX, high, low, grayFill, grayStroke);
            // entry to stopLoss highlighted within that span
            if (entryPrice !== null && stopLoss !== null) {
              drawPriceRect(leftX, rightX, Math.max(entryPrice, stopLoss), Math.min(entryPrice, stopLoss), grayFill, grayStroke);
            }
          }
        } else if (isProfit) {
          // Profit: prioritize showing a green box. Prefer filled->closed time
          // span but fall back to placed/start->edge when timestamps are
          // missing. Draw the vertical range from entry->takeProfit when
          // available; otherwise fall back to high->low for visibility.
          // Ensure we have usable pixel coordinates. If timeToCoordinate
          // returned null (off-screen), fall back to chart edges so we can
          // still draw a visible span.
          const spanLeftRaw = (xFilled !== null && xFilled !== undefined) ? xFilled : ((xPlaced !== null && xPlaced !== undefined) ? xPlaced : xStart);
          const spanRightRaw = (xClosed !== null && xClosed !== undefined) ? xClosed : rightEdge;
          let spanLeft = (spanLeftRaw === null || spanLeftRaw === undefined) ? 0 : spanLeftRaw;
          let spanRight = (spanRightRaw === null || spanRightRaw === undefined) ? chartWidth : spanRightRaw;
          // compute pixel span for profit (no debug logging)
          // If the computed span has effectively no width, prefer a fallback
          // using the placed->closed timestamps (or start->edge) so we draw a
          // meaningful span instead of artificially expanding the pixels.
          try {
            if (Math.abs(spanRight - spanLeft) <= 0) {
              const fbLeft = (xPlaced !== null && xPlaced !== undefined) ? xPlaced : xStart;
              const fbRight = (xClosed !== null && xClosed !== undefined) ? xClosed : rightEdge;
              if (fbLeft !== null && fbRight !== null && Math.abs(fbRight - fbLeft) > 0) {
                spanLeft = fbLeft;
                spanRight = fbRight;
              }
            }
          } catch (_) { }
          if (spanLeft !== null && spanRight !== null && spanLeft !== undefined && spanRight !== undefined) {
            if (entryPrice !== null && takeProfit !== null) {
              // entry -> takeProfit within the chosen time span
              drawPriceRect(spanLeft, spanRight, Math.max(entryPrice, takeProfit), Math.min(entryPrice, takeProfit), greenFill, greenStroke);
            } else {
              // fallback: draw full high-low green box for the span. If price
              // coordinates are not available (off-screen), draw a full-height
              // solid rectangle so the profit is unmistakable during testing.
              const drew = (() => {
                try {
                  drawPriceRect(spanLeft, spanRight, high, low, greenFill, greenStroke);
                  return true;
                } catch (_) { return false; }
              })();
              if (!drew) {
                // full-height fallback (CSS pixels)
                try {
                  overlayCtx.save();
                  overlayCtx.fillStyle = greenFill;
                  overlayCtx.fillRect(Math.min(spanLeft, spanRight), 0, Math.abs(spanRight - spanLeft), chartHeight);
                  overlayCtx.strokeStyle = greenStroke;
                  overlayCtx.lineWidth = 2;
                  overlayCtx.strokeRect(Math.min(spanLeft, spanRight), 0, Math.abs(spanRight - spanLeft), chartHeight);
                  overlayCtx.restore();
                } catch (_) { }
              }
            }
          }
        } else if (isLoss) {
          // Loss: red box from filled->close, entry->stopLoss
          if (xFilled !== null && xClosed !== null && entryPrice !== null && stopLoss !== null) {
            drawPriceRect(xFilled, xClosed, Math.max(entryPrice, stopLoss), Math.min(entryPrice, stopLoss), redFill, redStroke);
          }
        } else if (filled) {
          // Filled: gray start->filled, red filled->edge (high-low), and
          // green entry->takeProfit from filled->edge (if not profit/loss/cancel)
          if (xFilled !== null) drawPriceRect(xStart, xFilled, high, low, grayFill, grayStroke);
          if (xFilled !== null) drawPriceRect(xFilled, rightEdge, high, low, redFill, redStroke);
          if (entryPrice !== null && takeProfit !== null) {
            drawPriceRect(xFilled || rightEdge, rightEdge, entryPrice, takeProfit, greenFill, greenStroke);
          }
        } else if (isPlaced) {
          // Placed: gray box start->placed, and green box start->edge
          if (placed && xPlaced !== null) drawPriceRect(xStart, xPlaced, high, low, grayFill, grayStroke);
          if (xStart !== null) drawPriceRect(xStart, rightEdge, high, low, greenFill, greenStroke);
        } else if (isPlanned) {
          // Planned: red box from start to edge, height high->low
          if (xStart !== null) drawPriceRect(xStart, rightEdge, high, low, redFill, redStroke);
        }

      } catch (e) { /* ignore per-entry errors */ }
    }
  } catch (e) { console.error('failed drawing orders', e); }
}

function updateOpenZones(ctx) {
  if (!ctx) return;
  for (const [k, entry] of ctx.planZones.entries()) {
    if (!entry.meta.endTime) {
      if (ctx.lastBarTime) setZoneSeriesData(ctx, entry);
    }
  }
}

function handleSwingEvent(event, ctx) {
  try {
    const sp = (event && (event.swingPoint || event.swing || event.swing_point)) ? (event.swingPoint || event.swing || event.swing_point) : event;
    if (!sp) return;
    const tsMs = (event && event.timestamp) || sp.timestamp || sp.time;
    const time = Math.floor(Number(tsMs) / 1000);
    const level = safeNumber(sp.level || sp.value || sp.price);
    if (level === null || Number.isNaN(level)) return;
    const dirRaw = (sp.direction && (typeof sp.direction === 'string' ? sp.direction : Object.keys(sp.direction || {})[0])) || sp.dir || 'Up';
    const isUp = String(dirRaw).toLowerCase().includes('up');
    const marker = {
      time,
      position: isUp ? 'belowBar' : 'aboveBar',
      color: isUp ? '#26a69a' : '#ef5350',
      shape: isUp ? 'arrowUp' : 'arrowDown',
      text: ''
    };
  if (!ctx) return;
  ctx.swingMarkers.push(marker);
  if (ctx.swingMarkers.length > 5000) ctx.swingMarkers = ctx.swingMarkers.slice(ctx.swingMarkers.length - 5000);
  ctx.candleSeries.setMarkers(ctx.swingMarkers);
  } catch (e) { console.error('failed handling swing event', e); }
}

function handlePlanZoneEvent(event, ctx) {
  try {
    const p = (event && (event.planZone || event.plan_zone || event.plan)) ? (event.planZone || event.plan_zone || event.plan) : event;
    if (!p) return;
    // incoming plan zone payload
    // core timestamps are milliseconds
    const startMs = (p.startTime !== undefined && p.startTime !== null) ? p.startTime : (event.timestamp || Date.now());
    const endMs = (p.endTime !== undefined && p.endTime !== null) ? p.endTime : null;
    const start = Math.floor(Number(startMs) / 1000);
    const end = endMs ? Math.floor(Number(endMs) / 1000) : null;
    const low = safeNumber(p.low || p.lowLevel || (p.range && p.range.low) || (p.low && p.low.value));
    const high = safeNumber(p.high || p.highLevel || (p.range && p.range.high) || (p.high && p.high.value));
    if (low === null || high === null) return;
    // normalize planZoneType which may be an object like {Demand:{}} or a string
    let rawTypeVal = p.planZoneType || p.zoneType || p.type || '';
    let planZoneType = rawTypeVal;
    if (rawTypeVal && typeof rawTypeVal === 'object') {
      try {
        const keys = Object.keys(rawTypeVal);
        planZoneType = keys.length ? keys[0] : '';
      } catch (_) { planZoneType = '' }
    }
    const zoneObj = { planZoneType: planZoneType, low, high, startTime: start * 1000, endTime: end ? end * 1000 : null };
    // derived zoneObj
    const key = toZoneKey(zoneObj);
    const entry = ensureZoneSeries(ctx, key, zoneObj);
    // update meta.endTime if provided
    if (end) entry.meta.endTime = end;
    setZoneSeriesData(ctx, entry);
    if (ctx.planZones.size > 200) {
      const firstKey = ctx.planZones.keys().next().value;
      const old = ctx.planZones.get(firstKey);
      try { old.area.remove(); old.top.remove(); old.bottom.remove(); } catch (_) { }
      ctx.planZones.delete(firstKey);
    }
  } catch (e) { console.error('failed handling plan zone event', e); }
}

function handleDaytimeExtremeEvent(event, ctx) {
  try {
    const de = (event && (event.daytimeExtreme || event.daytime_extreme)) ? (event.daytimeExtreme || event.daytime_extreme) : event;
    if (!de) return;
    const tsMs = de.timestamp || event.timestamp || Date.now();
    const time = Math.floor(Number(tsMs) / 1000);
    const level = safeNumber(de.level || de.value || (de.level && de.level.value));
    if (level === null || Number.isNaN(level)) return;
    const desc = de.description || (de.extremeType ? `${de.extremeType} - ${time}` : `DaytimeExtreme - ${time}`);
    const typeRaw = (de.extremeType || de.extreme || '').toString();
    // store keyed by description
    // capture optional endTime (ms) from payload; convert to seconds
    const endMs = (de.endTime !== undefined && de.endTime !== null) ? de.endTime : null;
    const endSec = endMs ? Math.floor(Number(endMs) / 1000) : null;
  if (!ctx) return;
  ctx.daytimeExtremes.set(desc, { time, value: Number(level), type: typeRaw, description: desc, endTime: endSec });
  drawZones(ctx);
  } catch (e) { console.error('failed handling daytime extreme event', e); }
}

function resizeChart() {
  try {
    // resize plan chart
    if (planCtx && planCtx.chart) {
      const w = planCtx.container.clientWidth;
      const h = Math.max(200, planCtx.container.clientHeight);
      try { planCtx.chart.resize(w, h); } catch (e) { try { planCtx.chart.remove(); planCtx.chart = createChart(planCtx.container); } catch (_) { } }
      resizeOverlay(planCtx);
    }
    // resize trade chart if present
    if (tradeCtx && tradeCtx.chart) {
      const w2 = tradeCtx.container.clientWidth;
      const h2 = Math.max(200, tradeCtx.container.clientHeight);
      try { tradeCtx.chart.resize(w2, h2); } catch (e) { try { tradeCtx.chart.remove(); tradeCtx.chart = createChart(tradeCtx.container); } catch (_) { } }
      resizeOverlay(tradeCtx);
    }
  } catch (e) { /* ignore resize errors */ }
}

window.addEventListener('resize', () => resizeChart());

function toBarFromCandle(obj) {
  const c = (obj && obj.candle) ? obj.candle : obj;
  if (!c) return null;
  // core converts parquet timestamps to epoch milliseconds
  const tsMs = (c.timestamp !== undefined && c.timestamp !== null) ? c.timestamp : (obj && obj.timestamp) || Date.now();
  const time = Math.floor(Number(tsMs) / 1000);
  const open = (c.open && c.open.value !== undefined) ? c.open.value : c.open;
  const high = (c.high && c.high.value !== undefined) ? c.high.value : c.high;
  const low = (c.low && c.low.value !== undefined) ? c.low.value : c.low;
  const close = (c.close && c.close.value !== undefined) ? c.close.value : c.close;
  if ([open, high, low, close].some(v => v === undefined || v === null)) return null;
  return { time, open: Number(open), high: Number(high), low: Number(low), close: Number(close) };
}

function connectCoreWS(url = 'ws://localhost:8080/ws') {
  const ws = new WebSocket(url);
  // buffer for raw incoming messages until user starts processing them
  const msgBuffer = [];
  let started = false;
  // pending speed message to send when WS opens if changed before connect
  let pendingSpeed = null;
  // whether we've already initiated the full Trading phase on the server
  let tradingPhaseInitiated = false;

  // Global app state: 'initial' | 'planning' | 'trading'
  const GlobalState = {
    INITIAL: 'initial',
    PLANNING: 'planning',
    TRADING: 'trading'
  };
  // current state (initialized to Initial on first load)
  let currentState = GlobalState.INITIAL;

  // store parsed events separated by state
  const stateEvents = {
    [GlobalState.PLANNING]: [],
    [GlobalState.TRADING]: []
  };

  const statusEl = document.getElementById('status');
  const planBtn = document.getElementById('planBtn');
  const tradeBtn = document.getElementById('tradeBtn');
  const datePicker = document.getElementById('datePicker');
  const speedSlider = document.getElementById('speedSlider');
  const speedValue = document.getElementById('speedValue');
  const daysInput = document.getElementById('daysInput');

  // Initialize date picker to today
  try {
    const today = new Date();
    const yyyy = today.getFullYear();
    const mm = String(today.getMonth() + 1).padStart(2, '0');
    const dd = String(today.getDate()).padStart(2, '0');
    if (datePicker) datePicker.value = `${yyyy}-${mm}-${dd}`;
  } catch (e) { /* ignore */ }

  // Update displayed speed when slider moves
  let replaySpeed = 1.0;
  if (speedSlider && speedValue) {
    speedValue.textContent = `${speedSlider.value}x`;
    // disable speed control while in INITIAL state
    if (currentState === GlobalState.INITIAL) speedSlider.disabled = true;
    speedSlider.addEventListener('input', (ev) => {
      replaySpeed = ev.target.value;
      speedValue.textContent = `${replaySpeed}x`;
      // send speed update to server/app
      try {
        const msg = { cmd: 'SPEED', speed: Number(replaySpeed) };
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify(msg));
        } else {
          // store the latest speed to send when connection opens
          pendingSpeed = msg;
        }
      } catch (e) { console.warn('failed send SPEED', e); }
    });
  }

  // Initialize days input default
  let days = 2;
  if (daysInput) {
    days = parseInt(daysInput.value || '2', 10) || 2;
    daysInput.addEventListener('input', (e) => { days = parseInt(e.target.value || '2', 10) || 2; });
  }

  function flushBuffer() {
    while (msgBuffer.length) {
      const ev = msgBuffer.shift();
        try {
          const raw = JSON.parse(ev);
          let event = raw;
          // Some servers wrap the payload as { type: 'event', event: {...} }
          // while others (or relays) send { phase: 'Planning', event: {...} }.
          // Accept either form: if there's an `event` property, unwrap it.
          try {
            if (raw && raw.event) event = raw.event;
          } catch (_) { event = raw }
        // store by current global state
        try { stateEvents[currentState].push(event); } catch (_) { }
        // detect order events
        try {
          const isOrder = Boolean(event && (event.order || (event.eventType && (typeof event.eventType === 'object' ? Object.keys(event.eventType || {})[0] === 'Order' : String(event.eventType).toLowerCase().includes('order')))));
          if (isOrder) {
            try { const ctx = (currentState === GlobalState.TRADING) ? tradeCtx : planCtx; if (ctx) handleOrderEvent(event, ctx); } catch (_) { }
          }
        } catch (_) { }
        // If this is a PhaseComplete event and we're in the TRADING UI state
        // (which is used while preparing), trigger the actual Trading phase
        // on the server exactly once.
        try {
          const isPhaseComplete = isPhaseCompleteEvent(event);
          if (isPhaseComplete && currentState === GlobalState.TRADING && !tradingPhaseInitiated) {
            // send StartPhase 'trading' to the server
            try {
              const selectedDate = datePicker ? datePicker.value : '';
              const options = { tradingDate: selectedDate || '', planningDays: String(days || 0) };
              const msg = { command: 'startPhase', phase: 'trading', options };
              if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify(msg));
                tradingPhaseInitiated = true;
                if (statusEl) statusEl.textContent = `Connected (state=${currentState}) - trading started`;
              }
            } catch (e) { console.warn('failed send trading StartPhase', e); }
          }
        } catch (_) { }
        const ctx = (currentState === GlobalState.TRADING) ? tradeCtx : planCtx;
        const isCandle = Boolean(event && (event.candle || (event.eventType && (String(event.eventType).includes('Candle') || event.eventType === 'Candle'))));
        if (isCandle) {
          const bar = toBarFromCandle(event);
          if (bar && ctx) ctx.candleSeries.update(bar);
          // track last bar time (seconds) and extend open zones for this ctx
          if (bar && ctx) { ctx.lastBarTime = bar.time; updateOpenZones(ctx); }
        }
        const isSwing = Boolean(event && (event.swingPoint || (event.eventType && (String(event.eventType).toLowerCase().includes('swing') || String(event.eventType) === 'SwingPoint'))));
        if (isSwing) {
          handleSwingEvent(event, ctx);
        }
        const isPlan = Boolean(event && (event.planZone || (event.eventType && (String(event.eventType).toLowerCase().includes('planzone') || String(event.eventType) === 'PlanZone'))));
        if (isPlan) handlePlanZoneEvent(event, ctx);
        const isDaytime = Boolean(event && (event.daytimeExtreme || (event.eventType && (String(event.eventType).toLowerCase().includes('daytime') || String(event.eventType) === 'DaytimeExtreme'))));
        if (isDaytime) handleDaytimeExtremeEvent(event, ctx);
      } catch (e) { console.error('failed parsing buffered core event', e); }
    }
  }

  ws.addEventListener('open', () => {
    console.log('Connected to core WS');
    statusEl.textContent = `Connected (state=${currentState})`;
    // initialize UI buttons based on currentState
    if (currentState === GlobalState.INITIAL) {
      // allow planning, but disable Trade and speed until Plan is clicked
      if (planBtn) planBtn.disabled = false;
      if (tradeBtn) tradeBtn.disabled = true;
      if (speedSlider) speedSlider.disabled = true;
    } else if (currentState === GlobalState.PLANNING) {
      if (planBtn) planBtn.disabled = true;
      if (tradeBtn) tradeBtn.disabled = false;
      if (speedSlider) speedSlider.disabled = false;
    } else if (currentState === GlobalState.TRADING) {
      // in trading state both buttons are enabled but their handlers are inert
      if (planBtn) planBtn.disabled = false;
      if (tradeBtn) tradeBtn.disabled = false;
      if (speedSlider) speedSlider.disabled = false;
    }
    // flush any pending speed change made before the socket opened
    if (pendingSpeed) {
      try { ws.send(JSON.stringify(pendingSpeed)); } catch (_) { }
      pendingSpeed = null;
    }
  });

  ws.addEventListener('message', (ev) => {
    console.debug('ws message raw:', ev.data);
    if (!started) {
      // buffer until user clicks Start
      msgBuffer.push(ev.data);
      return;
    }
    try {
      // server messages are wrapped as ServerMessage with a `type` field
      // parse the wrapper and extract the inner event when present
      const raw = JSON.parse(ev.data);
      let event = raw;
      try {
        // Accept wrapped messages that include an `event` property and unwrap
        try { if (raw && raw.event) event = raw.event; } catch (_) { event = raw }
      } catch (_) { event = raw }
      // store incoming events per current state
      try { stateEvents[currentState].push(event); } catch (_) { }
      // If this is a PhaseComplete event and we're in the TRADING UI state
      // (preparing), initiate the real Trading phase on the server once.
      try {
        const isPhaseComplete = isPhaseCompleteEvent(event);
        if (isPhaseComplete && currentState === GlobalState.TRADING && !tradingPhaseInitiated) {
          try {
            const selectedDate = datePicker ? datePicker.value : '';
            const options = { tradingDate: selectedDate || '', planningDays: String(days || 0) };
            const msg = { command: 'startPhase', phase: 'trading', options };
            if (ws && ws.readyState === WebSocket.OPEN) {
              ws.send(JSON.stringify(msg));
              tradingPhaseInitiated = true;
              if (statusEl) statusEl.textContent = `Connected (state=${currentState}) - trading started`;
            }
          } catch (e) { console.warn('failed send trading StartPhase', e); }
        }
      } catch (_) { }
      const ctx = (currentState === GlobalState.TRADING) ? tradeCtx : planCtx;
      // detect order events in live path
      try {
        const isOrder = Boolean(event && (event.order || (event.eventType && (typeof event.eventType === 'object' ? Object.keys(event.eventType || {})[0] === 'Order' : String(event.eventType).toLowerCase().includes('order')))));
        if (isOrder) {
          try { if (ctx) handleOrderEvent(event, ctx); } catch (_) { }
        }
      } catch (_) { }
      const isCandle = Boolean(event && (event.candle || (event.eventType && (String(event.eventType).includes('Candle') || event.eventType === 'Candle'))));
      if (isCandle) {
        const bar = toBarFromCandle(event);
        if (bar && ctx) {
          ctx.candleSeries.update(bar);
          ctx.lastBarTime = bar.time;
          updateOpenZones(ctx);
          // redraw overlays for zones on candle updates
          drawZones(ctx);
        }
      }

      const isSwing = Boolean(event && (event.swingPoint || (event.eventType && (String(event.eventType).toLowerCase().includes('swing') || event.eventType === 'SwingPoint'))));
      if (isSwing) {
        handleSwingEvent(event, ctx);
      }

      const isPlan = Boolean(event && (event.planZone || (event.eventType && (String(event.eventType).toLowerCase().includes('planzone') || String(event.eventType) === 'PlanZone'))));
      if (isPlan) handlePlanZoneEvent(event, ctx);
      const isDaytime = Boolean(event && (event.daytimeExtreme || (event.eventType && (String(event.eventType).toLowerCase().includes('daytime') || String(event.eventType) === 'DaytimeExtreme'))));
      if (isDaytime) handleDaytimeExtremeEvent(event, ctx);
    } catch (e) { console.error('failed parsing core event', e); }
  });

  ws.addEventListener('close', () => { console.log('WS closed, reconnecting in 2s'); statusEl.textContent = 'Disconnected'; setTimeout(() => connectCoreWS(url), 2000); });
  ws.addEventListener('error', (e) => { console.error('WS error', e); statusEl.textContent = 'Error'; });

  // Plan button: tell core to start planning and keep the state as Planning
  if (planBtn) planBtn.addEventListener('click', () => {
    // If we're already in trading state, in new behavior Plan should toggle to Plan chart
    if (currentState === GlobalState.TRADING) {
      // toggle to Plan view without sending any server messages
      showPlanView();
      return;
    }
    // if websocket not open yet, ignore
    try {
      const selectedDate = datePicker ? datePicker.value : '';
      // send StartPhase with options matching the new protocol
      const options = { tradingDate: selectedDate || '', planningDays: String(days || 0) };
      const msg = { command: 'startPhase', phase: 'planning', options };
      if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg));
      statusEl.textContent = `Planned ${selectedDate || ''} days=${days} @ ${replaySpeed}x`;
      // ensure state becomes planning and start processing incoming events
      currentState = GlobalState.PLANNING;
      started = true;
      // disable plan button to avoid duplicate sends
      if (planBtn) planBtn.disabled = true;
      // enable trade button and speed control now that planning started
      if (tradeBtn) tradeBtn.disabled = false;
      if (speedSlider) speedSlider.disabled = false;
      // flush any buffered messages
      flushBuffer();
      statusEl.textContent = `Connected (state=${currentState})`;
    } catch (e) { console.warn('failed send PLAN', e); }
  });

  // Trade button: tell core to start trading and switch global state to Trading
  if (tradeBtn) tradeBtn.addEventListener('click', () => {
    // allow the initial TRADE send when coming from Planning. Once in TRADING state
    // subsequent clicks should toggle between the Trade and Plan charts without
    // sending additional messages to the server.
    if (currentState === GlobalState.TRADING) {
      // toggle to Trade view
      showTradeView();
      return;
    }
    // If not yet in TRADING, the first Trade click should start the preparing phase
    try {
      const selectedDate = datePicker ? datePicker.value : '';
      const options = { tradingDate: selectedDate || '', planningDays: String(days || 0) };
      const msg = { command: 'startPhase', phase: 'preparing', options };
      if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg));
      // Ensure the trade chart/context exists before we switch state so
      // incoming Preparing-phase events (PlanZone, etc.) are applied to it.
      if (!tradeCtx) {
        try {
          tradeChart = createChart(tradeView);
          tradeCtx = makeViewContext(tradeView, tradeChart);
        } catch (e) { tradeCtx = null; console.warn('failed creating trade chart', e); }
      }
      // switch to trading-like UI state but do not start the actual Trading phase
      currentState = GlobalState.TRADING;
      started = true;
      if (planBtn) planBtn.disabled = false;
      if (tradeBtn) tradeBtn.disabled = false;
      if (speedSlider) speedSlider.disabled = false;
      // flush buffered messages now that tradeCtx may be available
      flushBuffer();
      // show the Trade chart when we first enter the trading view
      showTradeView();
      statusEl.textContent = `Connected (state=${currentState})`;
    } catch (e) { console.warn('failed send preparing StartPhase', e); }
  });

  // Helper to toggle views
  function showTradeView() {
    try {
      // hide plan view, show trade view
      if (planView) planView.style.display = 'none';
      if (tradeView) tradeView.style.display = 'block';
      // ensure trade overlay/chart are initialized and resized
      if (tradeCtx) {
        initOverlay(tradeCtx);
        resizeOverlay(tradeCtx);
        drawZones(tradeCtx);
        // schedule a frame refresh to ensure the chart paints correctly when
        // the container was hidden and is now shown. Resize on the next
        // animation frame and redraw overlays.
        try {
          window.requestAnimationFrame(() => {
            try {
              const w = tradeCtx.container.clientWidth;
              const h = Math.max(200, tradeCtx.container.clientHeight);
              try { tradeCtx.chart.resize(w, h); } catch (_) { }
              resizeOverlay(tradeCtx);
              drawZones(tradeCtx);
            } catch (_) { }
          });
        } catch (_) { }
      }
    } catch (e) { /* ignore */ }
  }

  function showPlanView() {
    try {
      if (tradeView) tradeView.style.display = 'none';
      if (planView) planView.style.display = 'block';
      if (planCtx) {
        initOverlay(planCtx);
        resizeOverlay(planCtx);
        drawZones(planCtx);
        // also refresh plan chart on the next frame to be consistent when
        // toggling views
        try {
          window.requestAnimationFrame(() => {
            try {
              const w = planCtx.container.clientWidth;
              const h = Math.max(200, planCtx.container.clientHeight);
              try { planCtx.chart.resize(w, h); } catch (_) { }
              resizeOverlay(planCtx);
              drawZones(planCtx);
            } catch (_) { }
          });
        } catch (_) { }
      }
    } catch (e) { /* ignore */ }
  }

  // expose a small API to query currentState and events for debugging
  window.__appState = {
    getState: () => currentState,
    getEvents: (state) => stateEvents[state || currentState]
  };
}

connectCoreWS();
