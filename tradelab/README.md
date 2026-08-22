# TradeLab 0.2.1

Clean restart of the crypto research/autotrading project. PumpRadar trading logic is intentionally not imported.

## Tournament

Four isolated frozen-spec paper/shadow participants start with their own equity ledger:

- `BTC_ALT_LAG` — BTC lead/lag into lagging altcoins.
- `REGIME_MOMENTUM` — trend continuation only in an allowed market regime.
- `FLOW_ABSORPTION` — trades + L2 pressure versus absorption/price response.
- `STAT_ARB` — relative-value / cointegration spread trading.

The research phase produces a ranking. `CHAMPION` / `RESERVE` assignment remains disabled until evidence gates are reached; the two losers are frozen rather than repeatedly patched.

## Recorder

TradeLab records public Binance USD-M market data for a dynamic liquid universe: ticker/mark/funding, aggTrades, top-10 depth for the microstructure subset, liquidations and periodic open interest. Raw market data stays on the VPS for the longer server retention window while strategy events, paper trades and forward labels accumulate for research.

## Mobile analysis snapshots

Phone snapshots are intentionally **not** full copies of the ever-growing live recorder database.

Each snapshot:

- is made from an atomic SQLite backup;
- keeps cumulative participant/spec/event/paper-trade/market-state/forward-label research records;
- keeps only the newest 6 hours of high-volume raw market/flow/depth/OI/liquidation rows;
- is vacuumed, compressed to `.sqlite3.gz`, and hashed with SHA-256;
- is stored on Android in `Download/TradeLab`;
- is downloaded automatically every 4 hours or manually on demand.

Interrupted Android downloads keep a persistent `.part` file and resume with HTTP byte ranges. Manual downloads use a `dataSync` foreground WorkManager service, so locking the screen does not tie the transfer to the Activity lifecycle. Repeated manual taps do not create duplicate large jobs.

## Safety defaults

TradeLab 0.2.1 has no live-order endpoint. All four participants are shadow/paper only while evidence is collected.
