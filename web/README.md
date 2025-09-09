BMPS web demo

This is a minimal static web UI that demonstrates the TradingView `lightweight-charts` library with generated sample candlestick data.

How to open

- Open directly in your browser (quickest):
  - Double-click `web/index.html` or open the file with your browser.

- Serve with Python's simple HTTP server (recommended when using local module imports or to avoid some browser restrictions):

```bash
# from the repo root
python3 -m http.server 5173 --directory web
# then open http://localhost:5173 in your browser
```

What you'll see

- A candlestick chart with generated sample data and two buttons to start/stop a simulated stream of updates.

Next steps

- Hook this UI to the `core` service: open a WebSocket or SSE connection and apply incoming events to the chart.
- Add UI controls to switch series, timeframe, and to replay historical data from the `core` library.

Connecting the core service (live events)

1) Start the WebSocket relay that will sit between the Scala `CoreService` and browser clients:

```bash
# from the repo root
cd web
npm install
npm run start-relay
```

This starts a relay on ws://localhost:9001 by default.

2) Configure `CoreService` to forward events to the relay by setting the config key `bmps.core.output-websocket-url` to `ws://localhost:9001` (for example in `application.conf` or environment-specific config).

3) Open the web UI (see above). The frontend will automatically connect to ws://localhost:9001 and apply incoming Candle events to the chart. Timestamps from the Scala core are epoch milliseconds and are converted to seconds for the chart.

Notes

- The relay is minimal: it broadcasts text messages received from any client to all other connected clients. In production you'd want a more robust auth/identification and backpressure handling.

