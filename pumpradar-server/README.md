# PumpRadar Measurement Server

This directory is the reviewable source of the server payload installed by
`server-bootstrap/install.sh`. The packaged `server-bootstrap/v437/*` chunks
must be generated from this tree and CI verifies that the unpacked payload
matches it.

Version 4.3.7 is a measurement-only extension of the 4.3.6 paper candidate.
The strict entry gate is frozen: impulse > 63, zero exhaustion and artificial
risk, 15-second taker buy ratio >= 0.90 and 5-second ratio >= 0.75. The existing
4.3.5 compatibility shadow is also unchanged.

The new experiment measures, but does not enforce:

- momentum persistence: `return_60s >= return_15s - 0.05 percentage points`;
- a three-tick strict streak with at most 2.5 seconds between ticks;
- one stable episode ID from first pre-candidate through slot and outcome;
- impulse age, episode extension and all local peak/retest features;
- executable counterfactual MFE/MAE at 5, 15, 30, 60, 120 and 300 seconds for
  triggered, shadow and near-miss snapshots, plus the first +1%/-0.75% barrier.
  Entry/exit VWAP, position size and fee rate are stored so net results after
  fees can be reconstructed without assuming the current configuration.

`experimental_shadow_passed` becomes true only when the frozen strict gate,
momentum persistence and the three-tick streak all pass. Paper entry still
occurs on the original frozen strict gate, so this version measures the new
hypothesis without changing the trade cohort.

`C_WEAKENING` is the primary candidate policy for notifications and reporting.
The A audit baseline still owns the slot lifecycle so A, B and C continue to
close and persist independently. The corrected protected floor remains
max(0.30% gross, 50% of peak), and the 60 warm, 15 decision and 20 depth
coverage profile is unchanged.

The service uses only public Binance market data. It has no order endpoint and
does not require Binance API keys.
