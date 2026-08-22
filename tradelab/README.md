# TradeLab 0.2.2

Clean restart of the crypto research/autotrading project. PumpRadar trading logic is intentionally not imported.

## Tournament

Four isolated paper participants start with their own $20 equity ledger:

- `BTC_ALT_LAG` — BTC lead/lag into lagging altcoins.
- `REGIME_MOMENTUM` — trend continuation only in an allowed market regime.
- `FLOW_ABSORPTION` — trades + L2 pressure versus absorption/price response.
- `STAT_ARB` — relative-value / statistical spread trading.

The strategy specs are frozen for the research epoch. Only valid fixed-horizon paper outcomes count. CHAMPION/RESERVE assignment remains disabled until evidence gates are reached.

## Strict data-quality rules

TradeLab 0.2.2 treats recorder continuity as part of the experiment rather than an implementation detail:

- configured warm-up windows must actually be present and continuous before a participant can signal;
- 5-second market history rejects gaps larger than the configured tolerance;
- paper exits use the first recorded market sample at/after the exact strategy horizon, never the current price after a restart;
- if no horizon sample exists inside the grace window, the trade is `INVALID_GAP` and does not affect equity or PnL;
- 5/15/30/60/120/300s forward labels are created only when the entire 300s path is continuous and each target exists;
- invalid label windows are recorded in `forward_label_quality` instead of being silently approximated;
- recorder gaps are stored in `recorder_gaps`;
- universe changes immediately force WS resubscription so top-40 flow and top-12 microstructure coverage do not wait for the periodic refresh;
- component supervisors restart unexpectedly terminated recorder loops.

The first 0.2.2 deployment creates one clean research epoch. Earlier infrastructure/preflight paper trades, signals and labels are excluded once, while raw market data is preserved. Ordinary restarts do not reset the epoch.

## Recorder

Public Binance USD-M data only; no exchange credentials are needed:

- dynamic top-40 USDT universe by quote volume;
- top-12 microstructure subset;
- 5s market samples;
- 1s aggregated aggressive trade flow;
- top-10 L2 depth sampling;
- mark/index/funding;
- liquidations;
- periodic open interest;
- live raw retention currently 72h.

## Mobile analysis snapshots

Phone snapshots are intentionally not full copies of the ever-growing live recorder database. Cumulative participant state, specs, paper trades, signals, market states and valid forward labels stay in the snapshot. Only the high-volume raw tables are trimmed to the configured snapshot raw window (currently 6h).

Snapshots use SQLite backup, gzip and SHA-256. Android stores verified files under `Download/TradeLab`, supports interrupted-download resume via HTTP Range and keeps a foreground data-sync notification for manual downloads.

## Safety defaults

There is no live-order endpoint. TradeLab remains shadow/paper only until historical/OOS/replay/paper evidence justifies promotion.
