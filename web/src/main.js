// Live-only frontend: consume candle events from core's embedded WS and apply to chart.
// Uses the global `LightweightCharts` UMD bundle loaded by index.html.

const chartContainer = document.getElementById('chart');

function createChart() {
  const width = chartContainer.clientWidth;
  const height = Math.max(200, chartContainer.clientHeight);
  return LightweightCharts.createChart(chartContainer, {
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

// redraw overlay when visible time range changes (user scroll/zoom)
try {
  const ts = chart.timeScale();
  if (ts && typeof ts.subscribeVisibleTimeRangeChange === 'function') {
    ts.subscribeVisibleTimeRangeChange(() => { drawZones(); });
  }
} catch (e) { /* ignore if API not available */ }

const candleSeries = chart.addCandlestickSeries({ upColor: '#26a69a', downColor: '#ef5350', wickUpColor: '#26a69a', wickDownColor: '#ef5350' });
// markers for swing points (rendered on the candle series)
let swingMarkers = [];
// plan zones keyed by generated id -> { area, top, bottom, meta }
const planZones = new Map();
let lastBarTime = null; // seconds
// daytime extremes keyed by description -> { time, value, type, description }
const daytimeExtremes = new Map();

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

function toZoneKey(z) {
  const t = Math.floor(Number(z.startTime || z.timestamp || 0));
  const low = safeNumber(z.low || z.lowLevel || z.value || z.lowPrice) || 0;
  const high = safeNumber(z.high || z.highLevel || z.highPrice) || 0;
  const rawType = (z.planZoneType || z.zoneType || '').toString();
  const type = rawType.toLowerCase();
  return `${t}:${low}:${high}:${type}`;
}

function ensureZoneSeries(key, zone) {
  if (planZones.has(key)) return planZones.get(key);
  // determine coords
  const low = Number(safeNumber(zone.low));
  const high = Number(safeNumber(zone.high));
  const typeRaw = (zone.planZoneType || zone.zoneType || '').toString().toLowerCase();
  const isDemand = typeRaw.includes('demand');
  const isSupply = typeRaw.includes('supply');
  // We will render the zone fill on a canvas overlay instead of using an AreaSeries.
  // This avoids stacking multiple semi-opaque AreaSeries which accumulate opacity
  // and become visually solid. Keep area=null as placeholder.
  const area = null;

  // top/bottom line colors: supply zones should have red top and green bottom;
  // demand zones should have green top and red bottom. Default to demand styling.
  let topColor;
  let bottomColor;
  if (isSupply) {
    topColor = '#ff0000'; // red
    bottomColor = '#00b050'; // green
  } else {
    // demand or unknown -> green top, red bottom
    topColor = '#00b050';
    bottomColor = '#ff0000';
  }

  const top = chart.addLineSeries({ color: topColor, lineWidth: 2, lastValueVisible: false, priceLineVisible: false });
  const bottom = chart.addLineSeries({ color: bottomColor, lineWidth: 2, lastValueVisible: false, priceLineVisible: false });

  const entry = { area, top, bottom, meta: { low, high, startTime: Math.floor(Number(zone.startTime) / 1000), endTime: zone.endTime ? Math.floor(Number(zone.endTime) / 1000) : null, type: typeRaw } };
  planZones.set(key, entry);
  return entry;
}

function setZoneSeriesData(entry) {
  const { area, top, bottom, meta } = entry;
  const start = Math.floor(Number(meta.startTime));
  const end = meta.endTime ? Math.floor(Number(meta.endTime)) : lastBarTime || start;
  // ensure end >= start
  const safeEnd = Math.max(end, start);
  const topPoints = [ { time: start, value: meta.high }, { time: safeEnd, value: meta.high } ];
  const bottomPoints = [ { time: start, value: meta.low }, { time: safeEnd, value: meta.low } ];
  try {
    // set the top/bottom lines (we'll draw fills on the overlay canvas)
    // Ensure line series colors reflect closed/open state (closed -> greys)
    const isClosed = Boolean(meta.endTime);
    // derive colors consistent with drawZones: default colored lines for open supply/demand
    const isSupply = (meta.type || '').includes('supply');
    let seriesTopColor = isSupply ? '#ff0000' : '#00b050';
    let seriesBottomColor = isSupply ? '#00b050' : '#ff0000';
    if (isClosed) {
      seriesTopColor = '#bdbdbd';
      seriesBottomColor = '#9e9e9e';
    }
    try { if (typeof top.applyOptions === 'function') top.applyOptions({ color: seriesTopColor }); } catch (_) {}
    try { if (typeof bottom.applyOptions === 'function') bottom.applyOptions({ color: seriesBottomColor }); } catch (_) {}
    top.setData(topPoints);
    bottom.setData(bottomPoints);
    // trigger overlay redraw
    drawZones();
  } catch (e) {
    console.error('failed setting zone series data', e);
  }
}

// Overlay canvas for zone fills
let overlayCanvas = null;
let overlayCtx = null;

function initOverlay() {
  if (overlayCanvas) return;
  overlayCanvas = document.createElement('canvas');
  overlayCanvas.style.position = 'absolute';
  overlayCanvas.style.left = '0';
  overlayCanvas.style.top = '0';
  overlayCanvas.style.width = '100%';
  overlayCanvas.style.height = '100%';
  overlayCanvas.style.pointerEvents = 'none';
  overlayCanvas.style.zIndex = '5';
  chartContainer.appendChild(overlayCanvas);
  overlayCtx = overlayCanvas.getContext('2d');
  resizeOverlay();
}

function resizeOverlay() {
  if (!overlayCanvas) return;
  const dpr = window.devicePixelRatio || 1;
  const w = chartContainer.clientWidth;
  const h = chartContainer.clientHeight;
  overlayCanvas.width = Math.max(1, Math.floor(w * dpr));
  overlayCanvas.height = Math.max(1, Math.floor(h * dpr));
  overlayCanvas.style.width = w + 'px';
  overlayCanvas.style.height = h + 'px';
  overlayCtx.setTransform(dpr, 0, 0, dpr, 0, 0);
  drawZones();
}

function drawZones() {
  try {
    initOverlay();
    overlayCtx.clearRect(0, 0, overlayCanvas.width, overlayCanvas.height);
    // for coordinate conversions
    const timeScale = chart.timeScale();
  let total = 0, supplyCount = 0, demandCount = 0;
    planZones.forEach((entry) => {
      total++;
      const { meta, top, bottom } = entry;
      if ((meta.type || '').includes('supply')) supplyCount++;
      else if ((meta.type || '').includes('demand')) demandCount++;
      const start = meta.startTime;
      const end = meta.endTime || lastBarTime || start;
      const safeEnd = Math.max(end, start);
      const x0 = timeScale.timeToCoordinate(start);
      const x1 = timeScale.timeToCoordinate(safeEnd);
      if (x0 === null || x1 === null) return; // not visible
      // price to pixel
      const yTop = candleSeries.priceToCoordinate(meta.high);
      const yBottom = candleSeries.priceToCoordinate(meta.low);
      if (yTop === null || yBottom === null) return;
      const x = Math.min(x0, x1);
      const width = Math.abs(x1 - x0);
      const y = Math.min(yTop, yBottom);
      const height = Math.abs(yBottom - yTop);
      if (width <= 0 || height <= 0) return;
      // choose fill and stroke colors
      const isSupply = (meta.type || '').includes('supply');
      // closed zones (have endTime) should be drawn faintly in grey: opaque grey top/bottom lines
      // and a very light transparent grey fill so they remain visible but subtle.
      const isClosed = Boolean(meta.endTime);
      let topColor = '#00b050';
      let bottomColor = '#ff0000';
      if (isSupply) {
        topColor = '#ff0000';
        bottomColor = '#00b050';
      }
      // choose distinct translucent fills so supply/demand are visually different for open zones
      const supplyFill = 'rgba(180,130,240,0.18)'; // purple for supply (open)
      const demandFill = 'rgba(93, 146, 225, 0.18)'; // blue for demand (open)
      // styling for closed zones: faint grey lines + very light grey fill
      const closedColor = '#bdbdbd';   // light grey (opaque)
      const closedFill = 'rgba(160,160,160,0.1)'; // very faint grey fill
      const fillStyle = isClosed ? closedFill : (isSupply ? supplyFill : demandFill);
      if (isClosed) {
        topColor = closedColor;
        bottomColor = closedColor;
      }
      overlayCtx.save();
      overlayCtx.fillStyle = fillStyle;
      overlayCtx.fillRect(x, y, width, height);
      // draw top line
      overlayCtx.strokeStyle = topColor;
      overlayCtx.lineWidth = 2;
      overlayCtx.beginPath();
      overlayCtx.moveTo(x, y + 1);
      overlayCtx.lineTo(x + width, y + 1);
      overlayCtx.stroke();
      // draw bottom line
      overlayCtx.strokeStyle = bottomColor;
      overlayCtx.lineWidth = 2;
      overlayCtx.beginPath();
      overlayCtx.moveTo(x, y + height - 1);
      overlayCtx.lineTo(x + width, y + height - 1);
      overlayCtx.stroke();
      overlayCtx.restore();
    });

      // draw daytime extremes as solid horizontal lines from their timestamp to their endTime (if ended),
      // otherwise continue the line to the right edge of the chart.
      try {
        const chartWidth = chartContainer.clientWidth;
        daytimeExtremes.forEach((de) => {
          try {
            const t = de.time; // seconds
            const x0 = timeScale.timeToCoordinate(t);
            if (x0 === null) return; // not visible
            const y = candleSeries.priceToCoordinate(de.value);
            if (y === null) return;
            // force daytime extreme lines to black for both highs and lows
            const strokeColor = '#000000';

            // derive a short abbreviation for common descriptions
            const desc = String(de.description || '');
            let abbrev = '';
            try {
              if (desc.toLowerCase().includes('london')) abbrev = 'L';
              else if (desc.toLowerCase().includes('asia')) abbrev = 'A';
              else if (desc.toLowerCase().includes('new york') || desc.toLowerCase().includes('newyork') || desc.toUpperCase().includes('NY')) abbrev = 'NY';
              else if (desc.length) abbrev = desc.trim()[0].toUpperCase();
            } catch (_) { abbrev = '' }

            overlayCtx.save();
            // draw the horizontal line: to endTime if provided, otherwise to right edge
            let x1 = chartWidth;
            if (de.endTime) {
              try {
                const endCoord = timeScale.timeToCoordinate(de.endTime);
                if (endCoord !== null) x1 = endCoord;
              } catch (_) { /* fall back to chartWidth */ }
            }
            overlayCtx.strokeStyle = strokeColor;
            overlayCtx.lineWidth = 2;
            overlayCtx.beginPath();
            overlayCtx.moveTo(x0, y);
            overlayCtx.lineTo(x1, y);
            overlayCtx.stroke();

            // draw the abbreviation just to the left of the line start
            if (abbrev) {
              overlayCtx.fillStyle = '#000000';
              // choose a readable font size; uses CSS pixels
              overlayCtx.font = '12px Arial, Helvetica, sans-serif';
              overlayCtx.textBaseline = 'middle';
              overlayCtx.textAlign = 'right';
              // position a few pixels left of the line start, but keep inside canvas
              const textX = Math.max(4, x0 - 6);
              overlayCtx.fillText(abbrev, textX, y);
            }

            overlayCtx.restore();
          } catch (e) { /* ignore single de failures */ }
        });
      } catch (e) { /* ignore drawing extremes */ }
  // no-op: removed debug logging
  } catch (e) {
    console.error('failed drawing zone overlays', e);
  }
}

function updateOpenZones() {
  for (const [k, entry] of planZones.entries()) {
    if (!entry.meta.endTime) {
      // extend to lastBarTime if available
      if (lastBarTime) setZoneSeriesData(entry);
    }
  }
}

function handleSwingEvent(event) {
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
    swingMarkers.push(marker);
    // keep marker list bounded to avoid memory growth
    if (swingMarkers.length > 5000) swingMarkers = swingMarkers.slice(swingMarkers.length - 5000);
    candleSeries.setMarkers(swingMarkers);
  } catch (e) { console.error('failed handling swing event', e); }
}

function handlePlanZoneEvent(event) {
  try {
    const p = (event && (event.planZone || event.plan_zone || event.plan)) ? (event.planZone || event.plan_zone || event.plan) : event;
    if (!p) return;
  // incoming plan zone payload
    // core timestamps are milliseconds
    const startMs = (p.startTime !== undefined && p.startTime !== null) ? p.startTime : (event.timestamp || Date.now());
    const endMs = (p.endTime !== undefined && p.endTime !== null) ? p.endTime : null;
    const start = Math.floor(Number(startMs) / 1000);
    const end = endMs ? Math.floor(Number(endMs) / 1000) : null;
    const low = safeNumber(p.low || p.lowLevel || (p.range && p.range.low) || (p.low && p.low.value) );
    const high = safeNumber(p.high || p.highLevel || (p.range && p.range.high) || (p.high && p.high.value) );
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
    const entry = ensureZoneSeries(key, zoneObj);
    // update meta.endTime if provided
    if (end) entry.meta.endTime = end;
    // update values (and extend if open)
    setZoneSeriesData(entry);
    // keep map size bounded mildly
    if (planZones.size > 200) {
      // remove oldest
      const firstKey = planZones.keys().next().value;
      const old = planZones.get(firstKey);
      try { old.area.remove(); old.top.remove(); old.bottom.remove(); } catch (_) {}
      planZones.delete(firstKey);
    }
  } catch (e) { console.error('failed handling plan zone event', e); }
}

function handleDaytimeExtremeEvent(event) {
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
  daytimeExtremes.set(desc, { time, value: Number(level), type: typeRaw, description: desc, endTime: endSec });
    // trigger redraw
    drawZones();
  } catch (e) { console.error('failed handling daytime extreme event', e); }
}

function resizeChart() {
  const width = chartContainer.clientWidth;
  const height = Math.max(200, chartContainer.clientHeight);
  try {
    chart.resize(width, height);
  } catch (e) {
    // fallback: recreate chart if resize not supported
    try { chart.remove(); } catch (_) {}
    chart = createChart();
  }
  // resize overlay canvas when chart resizes
  resizeOverlay();
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

function connectCoreWS(url = 'ws://localhost:9001') {
  const ws = new WebSocket(url);
  const msgBuffer = [];
  let started = false;

  const statusEl = document.getElementById('status');
  const startBtn = document.getElementById('startBtn');
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
      speedSlider.addEventListener('input', (ev) => {
        replaySpeed = ev.target.value;
        speedValue.textContent = `${replaySpeed}x`;
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
        const event = JSON.parse(ev);
        const isCandle = Boolean(event && (event.candle || (event.eventType && (String(event.eventType).includes('Candle') || event.eventType === 'Candle'))));
        if (isCandle) {
          const bar = toBarFromCandle(event);
          if (bar) candleSeries.update(bar);
          // track last bar time (seconds) and extend open zones
          lastBarTime = bar.time;
          updateOpenZones();
        }
        const isSwing = Boolean(event && (event.swingPoint || (event.eventType && (String(event.eventType).toLowerCase().includes('swing') || String(event.eventType) === 'SwingPoint'))));
        if (isSwing) {
          handleSwingEvent(event);
        }
        const isPlan = Boolean(event && (event.planZone || (event.eventType && (String(event.eventType).toLowerCase().includes('planzone') || String(event.eventType) === 'PlanZone'))));
        if (isPlan) handlePlanZoneEvent(event);
  const isDaytime = Boolean(event && (event.daytimeExtreme || (event.eventType && (String(event.eventType).toLowerCase().includes('daytime') || String(event.eventType) === 'DaytimeExtreme'))));
  if (isDaytime) handleDaytimeExtremeEvent(event);
      } catch (e) { console.error('failed parsing buffered core event', e); }
    }
  }

  ws.addEventListener('open', () => {
    console.log('Connected to core WS');
    statusEl.textContent = 'Connected (waiting)';
    startBtn.disabled = false;
  });

  ws.addEventListener('message', (ev) => {
    console.debug('ws message raw:', ev.data);
    if (!started) {
      // buffer until user clicks Start
      msgBuffer.push(ev.data);
      return;
    }
    try {
      const event = JSON.parse(ev.data);
      const isCandle = Boolean(event && (event.candle || (event.eventType && (String(event.eventType).includes('Candle') || event.eventType === 'Candle'))));
      if (isCandle) {
        const bar = toBarFromCandle(event);
        if (bar) {
          candleSeries.update(bar);
          lastBarTime = bar.time;
          updateOpenZones();
          // redraw overlays for zones on candle updates
          drawZones();
        }
      }

      const isSwing = Boolean(event && (event.swingPoint || (event.eventType && (String(event.eventType).toLowerCase().includes('swing') || event.eventType === 'SwingPoint'))));
      if (isSwing) {
        handleSwingEvent(event);
      }

      const isPlan = Boolean(event && (event.planZone || (event.eventType && (String(event.eventType).toLowerCase().includes('planzone') || event.eventType === 'PlanZone'))));
      if (isPlan) handlePlanZoneEvent(event);
  const isDaytime = Boolean(event && (event.daytimeExtreme || (event.eventType && (String(event.eventType).toLowerCase().includes('daytime') || String(event.eventType) === 'DaytimeExtreme'))));
  if (isDaytime) handleDaytimeExtremeEvent(event);
    } catch (e) { console.error('failed parsing core event', e); }
  });

  ws.addEventListener('close', () => { console.log('WS closed, reconnecting in 2s'); statusEl.textContent = 'Disconnected'; setTimeout(() => connectCoreWS(url), 2000); });
  ws.addEventListener('error', (e) => { console.error('WS error', e); statusEl.textContent = 'Error'; });

  startBtn.addEventListener('click', () => {
    if (started) return;
    started = true;
    // signal core that client is ready
    const selectedDate = datePicker ? datePicker.value : '';
  const connectMsg = { cmd: 'CONNECT', date: selectedDate, days };
    try { ws.send(JSON.stringify(connectMsg)); } catch (e) { console.warn('failed send CONNECT', e); }
  // Show selected simulation parameters in status
  statusEl.textContent = `Running ${selectedDate || ''} days=${days} @ ${replaySpeed}x`;
    startBtn.disabled = true;
    // flush any buffered messages
    flushBuffer();
  });
}

connectCoreWS();
