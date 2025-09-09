// Live-only frontend: consume candle events from core's embedded WS and apply to chart.
// Uses the global `LightweightCharts` UMD bundle loaded by index.html.

const chartContainer = document.getElementById('chart');
const chart = LightweightCharts.createChart(chartContainer, {
  width: chartContainer.clientWidth,
  height: 520,
  layout: { backgroundColor: '#ffffff', textColor: '#333' },
  grid: { vertLines: { color: '#f0f3f7' }, horzLines: { color: '#f0f3f7' } },
  rightPriceScale: { scaleMargins: { top: 0.1, bottom: 0.1 } },
  timeScale: { timeVisible: true, secondsVisible: true }
});

const candleSeries = chart.addCandlestickSeries({ upColor: '#26a69a', downColor: '#ef5350', wickUpColor: '#26a69a', wickDownColor: '#ef5350' });

window.addEventListener('resize', () => chart.applyOptions({ width: chartContainer.clientWidth }));

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
    } catch (e) { console.error('failed parsing core event', e); }
  });

  ws.addEventListener('close', () => { console.log('WS closed, reconnecting in 2s'); statusEl.textContent = 'Disconnected'; setTimeout(() => connectCoreWS(url), 2000); });
  ws.addEventListener('error', (e) => { console.error('WS error', e); statusEl.textContent = 'Error'; });

  startBtn.addEventListener('click', () => {
    if (started) return;
    started = true;
    // signal core that client is ready
    try { ws.send('READY'); } catch (e) { console.warn('failed send READY', e); }
    statusEl.textContent = 'Running';
    startBtn.disabled = true;
    // flush any buffered messages
    flushBuffer();
  });
}

connectCoreWS();
