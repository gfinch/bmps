document.addEventListener('DOMContentLoaded', () => {
    const form = document.getElementById('initForm');
    const status = document.getElementById('status');
    const mode = document.getElementById('mode');
    const date = document.getElementById('date');
    const speed = document.getElementById('speed');

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        status.textContent = 'Initializing...';
        const payload = { mode: mode.value, date: date.value, speed: parseFloat(speed.value) };
        try {
            const res = await fetch('/init', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
            if (!res.ok) throw new Error('Init failed');
            status.textContent = 'Core initialized';
            // open chart in a new tab
        } catch (err) {
            status.textContent = 'Initialization failed';
            console.error(err);
        }
    });
});

// Chart page client (loaded at /chart)
if (window.location.pathname === '/chart') {
    (async function() {
        const { createChart } = await import('https://unpkg.com/lightweight-charts@3.8.0/dist/lightweight-charts.standalone.production.js');
        const container = document.getElementById('chart');
        const chart = createChart(container, { width: container.clientWidth, height: 500, layout: { background: { color: '#ffffff' }, textColor: '#333' } });
        const candleSeries = chart.addCandlestickSeries();
        const swingSeries = chart.addSeries ? chart.addSeries : null; // placeholder

        // simple mapping helper
        function toCandle(d) {
            return { time: Math.floor(d.timestamp / 1000), open: d.open, high: d.high, low: d.low, close: d.close };
        }

        // open websocket
        const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws/events');
        ws.addEventListener('message', (ev) => {
            try {
                const e = JSON.parse(ev.data);
                if (e.eventType && e.eventType.Candle && e.candle) {
                    const c = e.candle;
                    candleSeries.update(toCandle({ timestamp: c.timestamp || Date.now(), open: c.open.value, high: c.high.value, low: c.low.value, close: c.close.value }));
                }
                if (e.eventType && e.eventType.SwingPoint && e.swingPoint) {
                    // draw a simple marker
                    const sp = e.swingPoint;
                    chart.applyOptions({});
                    // LightweightCharts supports price markers on series
                    candleSeries.setMarkers([{ time: Math.floor(sp.timestamp / 1000), position: sp.direction.Up ? 'belowBar' : 'aboveBar', color: sp.direction.Up ? 'green' : 'red', shape: 'arrowDown', text: '' }]);
                }
            } catch (err) { console.error('ws parse', err); }
        });
    })();
}
