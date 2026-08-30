# TradeLab 0.2.3

Clean restart of the crypto research/autotrading project. PumpRadar trading logic is intentionally not imported.

## Tournament

Four isolated frozen-spec paper/shadow participants start with their own $20 equity ledger:

- `BTC_ALT_LAG` — BTC lead/lag into lagging altcoins.
- `REGIME_MOMENTUM` — trend continuation only in an allowed market regime.
- `FLOW_ABSORPTION` — trades + L2 pressure versus absorption/price response.
- `STAT_ARB` — relative-value spread trading.

The accountable tournament starts at research epoch mode `CLEAN_SHADOW_V2`. Earlier infrastructure/preflight outputs are excluded. Once V2 exists, ordinary server restarts do not reset tournament results.

`CHAMPION` / `RESERVE` assignment remains disabled until evidence gates are reached; losers are frozen rather than repeatedly patched.

## Recorder

TradeLab records public Binance USD-M market data for a dynamic liquid universe: ticker/mark/funding, aggTrades, top-10 depth for the microstructure subset, liquidations and periodic open interest.

Runtime rules:

- bootstrap six-symbol universe is held until the all-market ticker cache has enough symbols for one stable transition to top-40;
- top-12 receives the microstructure/L2 stream;
- WebSocket subscriptions refresh immediately after a real universe generation change;
- strategy windows require continuous 5s samples and their configured warm-up;
- paper exits use the first recorded sample at/after the exact fixed horizon within a strict grace period;
- absent horizon samples become `INVALID_GAP` and never alter equity;
- forward labels are accepted only with a continuous 300s path and receive an explicit quality record;
- recorder gaps are persisted for audit;
- live trading remains disabled.

## Mobile analysis snapshots

Phone snapshots are intentionally **not** full copies of the ever-growing live recorder database.

Each snapshot:

- is made from an atomic SQLite backup;
- keeps cumulative participant/spec/event/paper-trade/market-state/forward-label research records;
- keeps only the newest 6 hours of high-volume raw market/flow/depth/OI/liquidation rows;
- is vacuumed, compressed to `.sqlite3.gz`, and hashed with SHA-256;
- is stored on Android in `Download/TradeLab`;
- is downloaded automatically every 4 hours or manually on demand.

Interrupted Android downloads keep a persistent `.part` file and resume with HTTP byte ranges. Manual downloads use a `dataSync` foreground WorkManager service, so locking the screen does not tie the transfer to the Activity lifecycle.

## Safety defaults

TradeLab 0.2.3 has no live-order endpoint. All four participants remain shadow/paper only while evidence is collected.
