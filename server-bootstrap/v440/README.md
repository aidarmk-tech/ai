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

## Verified prebuilt installation

`install-v440-ready.sh` downloads only the prebuilt `v440release` payload. It does not build or patch source code on the VPS. The payload passed 23 unit tests, Python compilation, a local API startup check, static real-order-code scanning, and SQLite migration against a copy of the latest production database (`integrity_check=ok`, no foreign-key violations).

The installer verifies payload SHA-256 `0f7c2aa771c5466fa433a933766cc55bb4741f4f0dd49a7428b6d43aaa863a87`, refuses to switch while any spot/futures paper record is open, preserves SQLite and environment settings, creates a backup, and automatically rolls back if v4.4.0 does not start.
