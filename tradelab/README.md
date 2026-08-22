# TradeLab 0.2

Clean-room replacement for PumpRadar research infrastructure. PumpRadar trading logic is intentionally not imported.

## Tournament

TradeLab runs four isolated shadow participants:

- `BTC_ALT_LAG` — BTC → ALT delayed-response hypothesis.
- `REGIME_MOMENTUM` — continuation only when market regime and asset momentum align.
- `FLOW_ABSORPTION` — aggressive flow that stops moving price while opposite liquidity replenishes.
- `STAT_ARB` — rolling highly-correlated pair spread dislocation.

Every participant has a frozen `spec_version` and starts as `CANDIDATE`. Thresholds are not optimized while forward data is collecting. CHAMPION/RESERVE assignment is disabled until an evidence gate is defined and reached.

## Binance recorder

Inputs are public Binance USD-M data only; no API key is required for 0.2:

- all-market 24h futures ticker;
- all-market mark/index/funding stream;
- aggregate trades for the dynamic tracked universe;
- top-10 partial depth for the microstructure subset;
- all-market liquidation snapshots;
- periodic open interest for the microstructure subset.

The default dynamic universe is 40 USDT perpetual symbols ranked by 24h quote volume. The top 12 receive the heavier flow/L2/OI recorder.

Storage is aggregated rather than raw tick-for-tick: market state every 5s, flow/depth every 1s, with raw research rows retained for 72h by default. Participant events, paper trades and forward labels remain available for analysis.

Paper scoring uses each strategy's frozen holding horizon and a common conservative fee/slippage model. It is research accounting only; TradeLab 0.2 still has no live-order endpoint.

## Snapshots

The server creates atomic SQLite backups, gzips them and publishes a SHA-256 manifest. Android automatically checks every 4 hours, catches up missed snapshots and supports a manual fresh snapshot at any time. Verified files are saved to `Download/TradeLab` and the user receives a system notification.

## Useful API

- `GET /health`
- `GET /api/v1/market/status` — recorder/universe/component health
- `GET /api/v1/tournament` — A/B/C/D paper standings
- `GET /api/v1/participants`
- snapshot endpoints under `/api/v1/snapshots`

Authenticated API endpoints use the Android read token. No Binance credentials are stored in the Android client.
