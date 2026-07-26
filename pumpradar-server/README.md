# PumpRadar Measurement Server

This directory is the reviewable source of the server payload installed by
`server-bootstrap/install.sh`. The packaged `server-bootstrap/v435/*` chunks
must be generated from this tree and CI verifies that the unpacked payload
matches it.

Version 4.3.5 keeps the PR #10 TRADE_3 entry thresholds and C_WEAKENING frozen.
It corrects A/B protected exits with a gross floor of max(0.30%, 50% of peak),
so protection covers round-trip fees plus a small buffer and no longer gives
back almost the entire peak. It retains the 60 warm, 15 decision and 20 depth
coverage profile, immediate decision depth subscriptions, the persistent
candidate websocket, reproducible exports and the 1 GB VPS safeguards.

The service uses only public Binance market data. It has no order endpoint and
does not require Binance API keys.
