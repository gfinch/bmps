# Backtest Agent

This agent runs backtests of the BMPS trading bot autonomously, monitors performance, and documents results.

## IMPORTANT: Autonomous Operation

**This agent should run with MINIMAL user interaction.** 
- Do NOT ask for confirmation before running commands
- Do NOT wait for user approval during monitoring
- Only stop if PnL drops below -$10,000 or an unrecoverable error occurs
- Use `get_terminal_output` to check background process status instead of asking the user

## Workflow

### 1. Check and Restart Trading Bot

Check if the trading bot is already running:

```bash
curl -s http://localhost:8081/health
```

**Interpreting Results:**
- Exit code 0 with `{"status":"ok"}` → Bot is running
- Exit code 7 (connection refused) → Bot is NOT running

**If bot is running:** The bot can be reused. No action needed unless user requested a restart.

**If bot is NOT running:** Start in a background terminal and save the terminal ID:

```bash
sbt core/run
```

Then wait for startup (30-60 seconds) with a simple sleep command:

```bash
sleep 30 && curl -s http://localhost:8081/health
```

If health check fails, wait another 30 seconds and retry. Do NOT use complex shell loops.

### 2. Run the Backtest

Start the backtest in a **background terminal** and **save the terminal ID** for later status checks:

```bash
sbt "console/run START_DATE END_DATE"
```

**Date Range:**
- Default: Full year 2025 (`2025-01-01` to `2025-12-31`)
- Use user-specified range if provided
- Do NOT prompt for a date range

**Calculate Expected Trading Days:**
- Approximately 21 trading days per month (excludes weekends and holidays)
- September 2025: ~21 days
- Full year 2025: ~252 days

### 3. Monitor the Backtest (Autonomous)

**Use the `/availableDates` endpoint to track progress:**

```bash
curl -s http://localhost:8081/availableDates
```

This returns `{"dates": [...]}` with one entry per completed trading day. Count the dates to measure progress.

**Check PnL periodically:**

```bash
curl -s http://localhost:8081/aggregateOrderReport
```

Extract key fields (avoid printing the full `orders` array which is very large):

```bash
curl -s http://localhost:8081/aggregateOrderReport | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(f'PnL: \${d[\"totalPnL\"]:.2f}')
print(f'Drawdown: \${d[\"maxDrawdownDollars\"]:.2f}')
print(f'Winning: {d[\"winning\"]}, Losing: {d[\"losing\"]}')
print(f'Win Rate: {100*d[\"winning\"]/(d[\"winning\"]+d[\"losing\"]):.1f}%')
"
```

**Detecting Completion:**

Use `get_terminal_output` with the console terminal ID to check for:
- "All trading days processed successfully!" → Backtest complete
- Process exit (terminal shows exit code) → Backtest finished

You can also compare the count from `/availableDates` to the expected number of trading days.

**Early Termination:**
- If `totalPnL` < -$10,000: Kill the console terminal and document partial results
- Otherwise: Let the backtest run to completion (~30-60 seconds per trading day)

**Monitoring Cadence:**
- Check every 2-3 minutes by comparing dates count
- Do NOT require user approval between checks

### 4. Assess Performance

After completion, get final metrics:

```bash
curl -s http://localhost:8081/aggregateOrderReport | python3 -c "
import sys, json
d = json.load(sys.stdin)
metrics = {k: v for k, v in d.items() if k != 'orders'}
print(json.dumps(metrics, indent=2))
"
```

**Response Fields:**
| Field | Description |
|-------|-------------|
| `totalPnL` | Net profit/loss in dollars |
| `maxDrawdownDollars` | Worst peak-to-trough decline |
| `winning` | Count of profitable trades |
| `losing` | Count of losing trades |
| `averageWinDollars` | Mean profit per win |
| `averageLossDollars` | Mean loss per loss |
| `totalFees` | Trading fees incurred |
| `winRates` | Breakdown by strategy type |

**Performance Evaluation:**
| Metric | Calculation | Target |
|--------|-------------|--------|
| Win Rate | winning / (winning + losing) × 100 | > 60% |
| Profit Factor | (winning × avgWin) / (losing × avgLoss) | > 1.5 |
| Risk-Adjusted Return | totalPnL / maxDrawdown | > 0.5 |

### 5. Document Results

Create a markdown file: `backtest-results/YYYY-MM-DD-HHMMSS.md`

```markdown
# Backtest Results - [Start Date] to [End Date]

**Run Date:** [Current Date/Time]
**Trading Days:** [Count from availableDates]

## Summary

| Metric | Value |
|--------|-------|
| Total PnL | $X,XXX.XX |
| Max Drawdown | $X,XXX.XX |
| Total Fees | $XXX.XX |
| Win Rate | XX.X% |
| Total Trades | XXX |
| Winning | XXX |
| Losing | XXX |
| Avg Win | $XXX.XX |
| Avg Loss | $XXX.XX |
| Profit Factor | X.XX |

## Strategy Breakdown

[Include winRates breakdown by strategy]

## Analysis

[Assessment of performance]
[Note any concerning patterns]

## Comparison to Previous

[Compare to best prior run if exists]

## Status

✅ SUCCESS / ❌ FAILURE / ⚠️ PARTIAL (stopped early)
```

### 6. Git Commit

```bash
mkdir -p backtest-results
git add backtest-results/
git commit -m "backtest: START to END - PnL: $X,XXX"
```

## API Reference

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Returns `{"status":"ok"}` if bot running |
| `/availableDates` | GET | List processed dates with profitability |
| `/aggregateOrderReport` | GET | Cumulative stats across all dates |
| `/orderReport?tradingDate=YYYY-MM-DD` | GET | Stats for single date |
| `/isTodayATradingDay` | GET | Check if current day is tradeable |

**TIP:** Use `/availableDates` to track progress without parsing console output.

## Shell Command Guidelines

**AVOID** complex shell constructs that may fail in zsh:
- No `until` loops with nested commands
- No `for i in {1..N}` with complex bodies
- No here-docs or multi-line strings

**PREFER** simple commands:
- `sleep N && command` for delays
- `curl ... | python3 -c "..."` for JSON parsing
- Multiple separate commands instead of chained loops

## Error Handling

| Issue | Detection | Action |
|-------|-----------|--------|
| Bot won't start | Health check fails after 90s | Check for port conflicts, kill stale processes |
| Backtest stuck | No new dates in 5 minutes | Check console output, may need restart |
| Early termination | PnL < -$10,000 | Kill console, document partial results |
| API unreachable | Connection refused | Ensure bot is running |

## Example Invocations

| Request | Date Range |
|---------|------------|
| "Run a backtest" | 2025-01-01 to 2025-12-31 |
| "Backtest Q1 2025" | 2025-01-01 to 2025-03-31 |
| "Backtest September 2025" | 2025-09-01 to 2025-09-30 |
| "Run backtest for last week" | [Calculate based on current date] |
