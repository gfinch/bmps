# BMPS

A Scala project with three components:

- **Core**: A library that reads from a websocket, performs computations, and writes events via websocket using fs2.
- **Web**: A web app that reads events from core and displays them.
- **Console**: A console app that reads events from core and prints them to stdout.

## Setup

1. Ensure Scala and sbt are installed.
2. Clone the repo and run `sbt compile`.

## Running

- Core: `sbt core/run` (if needed)
- Web: `sbt web/run`
- Console: `sbt console/run`

## Modules

- `core/`: fs2-based streaming library.
- `web/`: http4s-based web server.
- `console/`: Simple console client.

## Where to find most relevant contracts (most volume):
- https://www.cmegroup.com/markets/equities/sp/e-mini-sandp500.volume.html


