# PumpRadar v4.4.0 — MC7 + USD-M Futures Paper

Paper-only release. No API key, signed endpoint, leverage or real Binance order is added.

## Trading channels

- Spot MC5: existing gate unchanged.
- Spot MC7: the existing 10-minute +7% signal can open the same paper policy set.
- Futures MC5 and MC7: independent Binance USD-M perpetual market data and paper slots.
- One spot momentum position and one futures momentum position may run independently.

All channels use the same -2% emergency stop and the same three policies:
`MC_HOLD_120`, `MC_TRAIL_1P0`, and `MC_FIXED_TP4`.

## Futures accounting

Futures calculations use their own aggTrades, bookTicker, depth, mark price, funding audit, spread and executable VWAP. The default simulated futures fee is 0.05% per side and can be changed in `server.env`.

## Freeze rule

Do not change trading thresholds until 100 primary spot+futures momentum paper slots are closed. The API status reports the target, completed count and remaining count.

## Safe installation

`install-v440-safe.sh` pins the v4.3.9 payload and v4.4.0 patch to immutable Git commit SHAs. Before touching the active service it compiles the code, runs all tests, rejects real-order code, and migrates a copy of the live SQLite database. The service is switched only with zero open paper/policy records and rolls back automatically if v4.4.0 does not start.
