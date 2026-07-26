# PumpRadar Measurement Server

This directory is the reviewable source of the server payload installed by
`server-bootstrap/install.sh`. The packaged `server-bootstrap/v432/*` chunks
must be generated from this tree and CI verifies that the unpacked payload
matches it.

Version 4.3.2 keeps the PR #10 TRADE_3 entry and paper-policy thresholds frozen.
It adds server-side persistence, per-symbol feed freshness, reproducible
SQLite/CSV exports, bounded export retention, and systemd memory limits.

The service uses only public Binance market data. It has no order endpoint and
does not require Binance API keys.
