# TradeLab Grid Scalp Adaptive (GS1)

Standalone shadow sidecar: regime-gated grid scalper for Binance USD-M perps.
Runs as its own systemd service and writes to the shared TradeLab SQLite DB
under `participant_id = GRID_SCALP_ADAPTIVE` with dedicated statuses
`G_OPEN` / `G_CLOSED`, so it never interferes with the R4C epoch bookkeeping
(`paper_book` only owns `OPEN`; the R4 sidecar owns `R4_OPEN`).

## Why this design

A 33h event study on live recorder data (`market_samples`, `liquidations`)
showed:

- a discrete long-only ladder grid (step 25 bps, taker round trip 14 bps)
  earns ~+14 bps/h median in **choppy** hours and exactly **0 bps/h** in
  trending hours;
- the surviving symbols are volatile mid-caps (TUT, XPL, PUMP, TRUMP,
  FARTCOIN...), not majors — HFT_GRID died trading calm BTC/ETH where an
  hour yields at most one step crossing.

Therefore the candidate dynamically re-qualifies symbols instead of using a
static universe.

## Symbol qualification (every 15 min, rolling 60m window)

| Gate | Value |
|---|---|
| path length | >= 120 bps/h |
| Kaufman efficiency ratio | <= 0.06 |
| quote volume 24h | >= $5M |
| spread | <= 8 bps |
| liquidation blackout | no cascade >= $250K notional in trailing 5m |

Qualified symbols are ranked by expected grid income
`(path - displacement)/2 * (1 - cost/step)` and only the top
`max_qualified_symbols` (10) trade.

## Engine

- ladder step 25 bps both directions; buy dips / sell rips;
- fixed horizon 1800s per leg (TTL exit), target = +1 step beyond entry;
- max 3 concurrent legs globally, per-symbol cooldown 60s;
- notional $10 per leg, `common_cost_pct = 0.14`;
- positions persist across restarts (restored from `G_OPEN` rows).

## Install

```bash
sudo mkdir -p /opt/tradelab/grid-scalp
sudo cp tradelab_grid_scalp.py /opt/tradelab/grid-scalp/
sudo cp tradelab-grid-scalp.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now tradelab-grid-scalp
```

The script self-registers the participant/spec rows on first start.

## Monitoring

```sql
SELECT status, COUNT(*), SUM(pnl_usdt) FROM paper_trades
WHERE participant_id='GRID_SCALP_ADAPTIVE' GROUP BY status;

SELECT symbol_a, COUNT(*) n, SUM(pnl_usdt) pnl FROM paper_trades
WHERE participant_id='GRID_SCALP_ADAPTIVE' AND status='G_CLOSED'
GROUP BY symbol_a ORDER BY pnl DESC;
```

## Safety

SHADOW_ONLY. No exchange keys, no order API, read-only market ingestion +
local DB writes. Rollback: `systemctl disable --now tradelab-grid-scalp`
and delete the two registration rows.

## Validation caveats

Calibrated on a single 33h regime window; ER/path thresholds must be
re-checked after the first full day of shadow operation.
