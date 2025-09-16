#!/usr/bin/env python3
"""Small websocket smoke client for the bmps server.

Note: the server expects messages shaped like
  {"command": "startPhase", "phase": "planning"}
and
  {"command": "status"}

Install the `websockets` package (e.g. `python3 -m pip install websockets`) and
then run this script.
"""

import asyncio
import json

try:
    import websockets
except Exception as e:
    print("Missing dependency 'websockets'. Install with: python3 -m pip install websockets")
    raise


async def run():
    uri = "ws://localhost:8080/ws"
    # Disable permessage-deflate compression to avoid sending the
    # Sec-WebSocket-Extensions header which some servers reject.
    async with websockets.connect(uri, compression=None) as ws:
        print("connected")
        # start planning phase using the protocol expected by the server
        await ws.send(json.dumps({"command": "startPhase", "phase": "planning"}))
        print("sent StartPhase planning")
        # request status
        await ws.send(json.dumps({"command": "status"}))
        print("sent Status")
        # receive messages for a short while
        try:
            for _ in range(10):
                msg = await asyncio.wait_for(ws.recv(), timeout=5)
                print("RECV:", msg)
        except asyncio.TimeoutError:
            print("timeout waiting for messages, exiting")


if __name__ == '__main__':
    asyncio.run(run())
