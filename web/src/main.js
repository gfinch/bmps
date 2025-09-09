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
    timeScale: { timeVisible: true, secondsVisible: true }
  });
}

let chart = createChart();

const candleSeries = chart.addCandlestickSeries({ upColor: '#26a69a', downColor: '#ef5350', wickUpColor: '#26a69a', wickDownColor: '#ef5350' });
// markers for swing points (rendered on the candle series)
let swingMarkers = [];

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

  function flushBuffer() {
    while (msgBuffer.length) {
      const ev = msgBuffer.shift();
      try {
        const event = JSON.parse(ev);
        const isCandle = Boolean(event && (event.candle || (event.eventType && (String(event.eventType).includes('Candle') || event.eventType === 'Candle'))));
        if (isCandle) {
          const bar = toBarFromCandle(event);
          if (bar) candleSeries.update(bar);
        }
        const isSwing = Boolean(event && (event.swingPoint || (event.eventType && (String(event.eventType).toLowerCase().includes('swing') || String(event.eventType) === 'SwingPoint'))));
        if (isSwing) {
          handleSwingEvent(event);
        }
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
        if (bar) candleSeries.update(bar);
        }
        const isSwing = Boolean(event && (event.swingPoint || (event.eventType && (String(event.eventType).toLowerCase().includes('swing') || event.eventType === 'SwingPoint'))));
        if (isSwing) {
          handleSwingEvent(event);
      }
    } catch (e) { console.error('failed parsing core event', e); }
  });

  ws.addEventListener('close', () => { console.log('WS closed, reconnecting in 2s'); statusEl.textContent = 'Disconnected'; setTimeout(() => connectCoreWS(url), 2000); });
  ws.addEventListener('error', (e) => { console.error('WS error', e); statusEl.textContent = 'Error'; });

  startBtn.addEventListener('click', () => {
    if (started) return;
    started = true;
    // signal core that client is ready
    try { ws.send('READY'); } catch (e) { console.warn('failed send READY', e); }
  // Show selected simulation parameters in status
  const selectedDate = datePicker ? datePicker.value : '';
  statusEl.textContent = `Running ${selectedDate || ''} @ ${replaySpeed}x`;
    startBtn.disabled = true;
    // flush any buffered messages
    flushBuffer();
  });
}

connectCoreWS();
