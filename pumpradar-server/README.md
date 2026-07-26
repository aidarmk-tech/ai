# PumpRadar Measurement Server

This directory is the reviewable source of the server payload installed by
`server-bootstrap/install.sh`. The packaged `server-bootstrap/v433/*` chunks
must be generated from this tree and CI verifies that the unpacked payload
matches it.

Version 4.3.3 keeps the PR #10 TRADE_3 entry and paper-policy thresholds frozen.
It keeps the candidate websocket alive while subscriptions change, resets
partial measurements only after a real reconnect, guarantees depth coverage for
every decision candidate, reports per-policy daily PnL without double-counting,
and retains reproducible SQLite/CSV exports and the 1 GB VPS safeguards.

The service uses only public Binance market data. It has no order endpoint and
does not require Binance API keys.
