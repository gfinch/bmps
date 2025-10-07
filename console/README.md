# BMPS Console Client

A command-line client for running BMPS trading phases across a date range in batch mode.

## Overview

The console client automates the process of running trading simulations across multiple trading days. It:

1. Takes a date range as input (start date and end date)
2. Filters for valid trading days (excludes weekends, holidays, and early close days)
3. For each trading day, orchestrates the three-phase workflow:
   - **Planning Phase**: Analyzes historical data to identify trading zones
   - **Preparing Phase**: Sets up trading parameters (auto-triggered after planning)
   - **Trading Phase**: Executes trades based on the plan (auto-triggered after preparing)
4. Polls each phase until completion before moving to the next
5. Proceeds to the next trading day automatically
6. Exits when all days are processed

## Prerequisites

- The BMPS core server must be running on `http://localhost:8081`
- Start the server with: `sbt core/run`

## Usage

### Using the Shell Script

```bash
./run-console.sh <start-date> <end-date>
```

**Example:**
```bash
./run-console.sh 2025-09-01 2025-09-20
```

### Using SBT Directly

```bash
sbt "console/run <start-date> <end-date>"
```

**Example:**
```bash
sbt "console/run 2025-09-01 2025-09-20"
```

### Date Format

Dates must be in ISO format: `YYYY-MM-DD`

## How It Works

### Phase Workflow

For each trading day, the console client:

1. **Starts Planning Phase**
   - Makes a REST API call to `PUT /phase/start` with phase="planning"
   - Polls `GET /phase/events?tradingDate=YYYY-MM-DD&phase=planning` until `isComplete=true`

2. **Waits for Preparing Phase**
   - The backend automatically triggers the preparing phase when planning completes
   - Waits 2 seconds for the phase to start
   - Polls `GET /phase/events?tradingDate=YYYY-MM-DD&phase=preparing` until `isComplete=true`

3. **Waits for Trading Phase**
   - The backend automatically triggers the trading phase when preparing completes
   - Waits 2 seconds for the phase to start
   - Polls `GET /phase/events?tradingDate=YYYY-MM-DD&phase=trading` until `isComplete=true`

4. **Moves to Next Trading Day**
   - Once all three phases complete, moves to the next valid trading day
   - Repeats until all days in the range are processed

### Trading Day Filtering

The client automatically filters out:
- **Weekends**: Saturday and Sunday
- **Market Holidays**: New Year's Day, MLK Day, Presidents Day, Good Friday, Memorial Day, Juneteenth, Independence Day, Labor Day, Thanksgiving, Christmas
- **Early Close Days**: Days before Independence Day, Thanksgiving, and Christmas (when markets close early)

## Output Example

```
Processing trading days from 2025-09-01 to 2025-09-20
============================================================
Found 14 trading days to process

[1/14] Processing 2025-09-02
------------------------------------------------------------
  Starting planning phase...
  Polling planning phase..........
  ✓ Planning phase completed
  Polling preparing phase......
  ✓ Preparing phase completed
  Polling trading phase............
  ✓ Trading phase completed
✓ Completed 2025-09-02

[2/14] Processing 2025-09-03
------------------------------------------------------------
...
```

## REST API Integration

The console client communicates with the BMPS core server via REST API:

- **Start Phase**: `PUT /phase/start`
  - Request body: `{"phase": "planning", "tradingDate": "YYYY-MM-DD", "options": {}}`
  - Response: `{"message": "Phase planning started", "phase": "planning"}`

- **Poll Events**: `GET /phase/events?tradingDate=YYYY-MM-DD&phase=<phase>`
  - Response: `{"events": [...], "isComplete": true/false}`
  - Polls every 1 second until `isComplete=true`

## Architecture

```
ConsoleClient
    ├── Date Range Input
    ├── Trading Day Filter (MarketCalendar logic)
    ├── Phase Orchestration
    │   ├── Start Planning → Poll until complete
    │   ├── Wait → Poll Preparing until complete
    │   └── Wait → Poll Trading until complete
    └── REST API Client (OkHttp)
        ├── PUT /phase/start
        └── GET /phase/events
```

## Dependencies

- **cats-effect**: Functional effects and IO
- **okhttp3**: HTTP client for REST API calls
- **circe**: JSON parsing and encoding

## Development

### Project Structure

```
console/
├── build.sbt                           # SBT build configuration
└── src/main/scala/bmps/console/
    └── ConsoleClient.scala             # Main application
```

### Building

```bash
sbt console/compile
```

### Running Tests

```bash
sbt console/test
```

## Error Handling

The client will exit with an error if:
- Invalid date format is provided
- The core server is not running
- A phase fails to complete
- REST API calls fail

Error messages will be printed to the console with details about the failure.

## Notes

- **Auto-Progression**: The preparing and trading phases are automatically triggered by the backend when the previous phase completes. The console client only needs to start the planning phase and then poll for the completion of all phases.

- **Polling Interval**: The client polls every 1 second for phase completion. A dot (`.`) is printed every 10 polling attempts to show progress.

- **Phase Caching**: The backend caches completed phases per trading date. If you re-run the same date, already-completed phases will be skipped.

- **Market Calendar**: The console client includes a simplified version of the market calendar logic. For production use, ensure it stays in sync with `core/src/main/scala/bmps/core/utils/MarketCalendar.scala`.
