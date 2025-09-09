// Simple WebSocket relay: accepts a single client (CoreService) sending events
// and broadcasts incoming messages to connected browser clients.
const WebSocket = require('ws');

const PORT = process.env.BMPS_WS_RELAY_PORT || 9001;

const wss = new WebSocket.Server({ port: PORT });

console.log(`BMPS WS relay listening on ws://localhost:${PORT}`);

// Track connected browser clients separately from the core client(s).
const browserClients = new Set();

wss.on('connection', function connection(ws, req) {
  // Heuristic: treat connections that send a "hello:core" message as core clients
  // but for simplicity we'll accept JSON events and broadcast to all others.
  console.log('Client connected');

  ws.on('message', function incoming(message) {
    try {
      const txt = message.toString();
      // Broadcast the raw text to all connected clients except the sender
      wss.clients.forEach(function each(client) {
        if (client !== ws && client.readyState === WebSocket.OPEN) {
          client.send(txt);
        }
      });
    } catch (e) {
      console.error('Failed to process incoming ws message', e);
    }
  });

  ws.on('close', () => console.log('Client disconnected'));
});
