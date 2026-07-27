# PumpRadar Measurement Server

This directory is the reviewable source of the server payload installed by
`server-bootstrap/install.sh`. The packaged `server-bootstrap/v436/*` chunks
must be generated from this tree and CI verifies that the unpacked payload
matches it.

Version 4.3.6 is a paper-only candidate based on the 4.3.5 measurement run. A
strict entry now requires impulse > 63, zero exhaustion and artificial risk,
15-second taker buy ratio >= 0.90 and 5-second ratio >= 0.75. Every entry that
would have passed the 4.3.5 strict gate remains labelled as `TRADE3_SHADOW`,
with explicit `IMPULSE_LE_63`, `EXHAUSTION_NONZERO`, `ARTIFICIAL_NONZERO`,
`TBR15_LOW` and `TBR5_LOW` blockers for the next audit.

`C_WEAKENING` is the primary candidate policy for notifications and reporting.
The A audit baseline still owns the slot lifecycle so A, B and C continue to
close and persist independently. The corrected protected floor remains
max(0.30% gross, 50% of peak), and the 60 warm, 15 decision and 20 depth
coverage profile is unchanged.

The service uses only public Binance market data. It has no order endpoint and
does not require Binance API keys.
