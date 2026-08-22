# TradeLab 0.1

Clean restart of the crypto research/autotrading project. PumpRadar trading logic is intentionally not imported.

## Tournament

Four isolated paper participants start with their own equity ledger:

- `BTC_ALT_LAG` — BTC lead/lag into lagging altcoins.
- `REGIME_MOMENTUM` — trend continuation only in an allowed market regime.
- `FLOW_ABSORPTION` — trades + L2 pressure versus absorption/price response.
- `STAT_ARB` — relative-value / cointegration spread trading.

The research phase produces a ranking. Only `CHAMPION` may later be promoted to live trading; `RESERVE` remains paper/shadow and can replace it. The other two are frozen, not patched until they look good.

## Snapshot contract

The server exposes an analysis snapshot API. A snapshot is created from SQLite using the SQLite backup API, compressed to `.sqlite3.gz`, hashed with SHA-256 and described by a JSON manifest.

Android downloads automatically every 4 hours and also supports a manual **Download snapshot now** action. A snapshot is marked successful only after the SHA-256 check passes. Android keeps the newest 15 snapshots and posts a system notification after a successful download.

## Layout

- `server/` — FastAPI API, SQLite state, tournament registry and snapshot manager.
- `android-client/` — minimal Android client for automatic/manual snapshot download and notifications.

## Safety defaults

TradeLab 0.1 has no live-order endpoint. Exchange execution is deliberately absent until the research/replay/paper gates are implemented and passed.
