// ESM module entry. Import the lightweight-charts ESM build from the global CDN.
// Use global `LightweightCharts` provided by the standalone UMD bundle loaded in index.html.
// Lightweight Charts demo with generated sample candlesticks and a simulated stream
const chartContainer = document.getElementById('chart');
const chart = LightweightCharts.createChart(chartContainer, {
    width: chartContainer.clientWidth,
    height: 520,
    layout: { backgroundColor: '#ffffff', textColor: '#333' },
    grid: { vertLines: { color: '#f0f3f7' }, horzLines: { color: '#f0f3f7' } },
    rightPriceScale: { scaleMargins: { top: 0.1, bottom: 0.1 } },
    timeScale: { timeVisible: true, secondsVisible: false }
  });

const candleSeries = chart.addCandlestickSeries({ upColor: '#26a69a', downColor: '#ef5350', wickUpColor: '#26a69a', wickDownColor: '#ef5350' });

  function generateSampleCandles(count) {
    const data = [];
    let base = 100;
    const daySeconds = 24 * 60 * 60;
    const start = Math.floor(Date.now() / 1000) - (count * daySeconds);
    for (let i = 0; i < count; i++) {
      const time = start + i * daySeconds;
      const open = base + (Math.random() - 0.5) * 2;
      const close = open + (Math.random() - 0.5) * 6;
      const high = Math.max(open, close) + Math.random() * 3;
      const low = Math.min(open, close) - Math.random() * 3;
      base = close; // walk the base so prices trend
      data.push({ time: time, open: round(open), high: round(high), low: round(low), close: round(close) });
    }
    return data;
  }
  function round(v) { return Math.round(v * 100) / 100; }

  const initialData = generateSampleCandles(60);
  candleSeries.setData(initialData);

  // Resize handling
  window.addEventListener('resize', () => {
    chart.applyOptions({ width: chartContainer.clientWidth });
  });

  // Simulated streaming: either update last bar or append a new bar every second
  let simTimer = null;
  let simIndex = 0;

  function startSim() {
    if (simTimer) return;
    simTimer = setInterval(() => {
      const last = initialData[initialData.length - 1];
      const time = last.time + (24 * 60 * 60) * (simIndex + 1);
      // Occasionally update the last bar instead of appending
      if (Math.random() < 0.3) {
        // update last
        const updated = {
          time: time - (24 * 60 * 60),
          open: round(last.open + (Math.random() - 0.5) * 1.5),
          high: round(Math.max(last.high, last.open) + Math.random() * 2),
          low: round(Math.min(last.low, last.open) - Math.random() * 2),
          close: round(last.close + (Math.random() - 0.5) * 1.5)
        };
        candleSeries.update(updated);
      } else {
        // append
        const prevClose = last.close;
        const open = round(prevClose + (Math.random() - 0.5) * 1.5);
        const close = round(open + (Math.random() - 0.5) * 3);
        const high = round(Math.max(open, close) + Math.random() * 2);
        const low = round(Math.min(open, close) - Math.random() * 2);
        const newBar = { time: time, open, high, low, close };
        candleSeries.update(newBar);
        // maintain sliding window
        initialData.push(newBar);
        if (initialData.length > 300) initialData.shift();
      }
      simIndex++;
    }, 1000);
  }

  function stopSim() {
    if (!simTimer) return;
    clearInterval(simTimer);
    simTimer = null;
  }

  // Wire UI
  const startBtn = document.getElementById('start-btn');
  const stopBtn = document.getElementById('stop-btn');
  startBtn.addEventListener('click', () => { startBtn.disabled = true; stopBtn.disabled = false; startSim(); });
  stopBtn.addEventListener('click', () => { startBtn.disabled = false; stopBtn.disabled = true; stopSim(); });

// Start paused; users can start simulation

// If you need to expose anything for debugging, attach to window
// window._bmps = { chart, candleSeries };

// WebSocket client: connect to the local relay and apply Candle events to the chart.
function connectCoreWebsocket(url = 'ws://localhost:9001') {
  try {
    const ws = new WebSocket(url);
    ws.addEventListener('open', () => {
      console.log('Connected to core WS relay at', url);
    });

    ws.addEventListener('message', (ev) => {
      try {
        const event = JSON.parse(ev.data);
        // Event shape: { eventType, timestamp, candle?, swingPoint? }
        if (!event) return;
        if (event.eventType && event.eventType.toString && event.eventType.toString().includes('Candle')) {
          const c = event.candle;
          if (!c) return;
          // The core uses epoch milliseconds for timestamps (see ParquetSource conversion).
          // lightweight-charts accepts `time` as either unix timestamp (seconds) or date string.
          const tsMs = c.timestamp || event.timestamp;
          const time = Math.floor(tsMs / 1000);
          const bar = { time: time, open: c.open.value, high: c.high.value, low: c.low.value, close: c.close.value };
          candleSeries.update(bar);
        }
      } catch (err) {
        console.error('Failed to handle incoming core event', err);
      }
    });

    ws.addEventListener('close', () => {
      console.log('Core WS connection closed, retrying in 2s');
      setTimeout(() => connectCoreWebsocket(url), 2000);
    });
    ws.addEventListener('error', (e) => console.error('Core WS error', e));
  } catch (e) {
    console.error('Failed to start WS client', e);
  }
}

// Connect automatically (you can disable auto-connect if you prefer to start manually)
connectCoreWebsocket();
