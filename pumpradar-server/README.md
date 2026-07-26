# PumpRadar Measurement Server

This directory is the reviewable source of the server payload installed by
`server-bootstrap/install.sh`. The packaged `server-bootstrap/v434/*` chunks
must be generated from this tree and CI verifies that the unpacked payload
matches it.

Version 4.3.4 keeps the PR #10 TRADE_3 entry and paper-policy thresholds frozen.
It moderately expands the observation profile to 60 warm, 15 decision and 20
depth symbols, immediately subscribes every new decision symbol instead of
waiting for the periodic warm refresh, and records `DEPTH_WARMING` separately
from a genuinely stale feed. It retains the persistent candidate websocket,
per-policy daily PnL, reproducible SQLite/CSV exports and the 1 GB VPS safeguards.

The service uses only public Binance market data. It has no order endpoint and
does not require Binance API keys.

