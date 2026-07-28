# PumpRadar Server v4.3.8 — Momentum Continuation Challenger

Version 4.3.8 keeps the frozen TRADE3 v4.3.6 paper gate as the control group and
adds an independent momentum-continuation paper challenger. No Binance API
keys or order endpoints are used.

## Frozen control

The existing TRADE3 entry, A/B/C exits and C_WEAKENING primary policy are
unchanged. The corrected protected floor remains max(0.30% gross, 50% of peak).

## New challenger

Three first-crossing arms are measured once per stable episode:

- MC3 shadow: return over 3 minutes >= 3%;
- MC5 paper challenger: return over 5 minutes >= 5%;
- MC7 shadow: return over 10 minutes >= 7%.

The MC5 paper strategy has a separate one-position slot, so it does not block
or replace the frozen TRADE3 control. Only execution/data safety is shared:
fresh public data, continuous aggTrade flow, executable bid/ask depth, spread
<= 30 bps, buy slippage <= 0.15% and sell slippage <= 0.35%.
STRONG_BUT_LATE, entry-risk and exhaustion scores are not vetoes for this
challenger because the tested hypothesis is confirmed continuation.

MC5 evaluates two exits on the same executable entry:

- primary `MC_TRAIL_1P0`: hard stop -2%, arm at +1.5%, exit after a 1.0%
  drawdown from peak, maximum horizon 20 minutes;
- control `MC_FIXED_TP4`: target +4%, hard stop -2%, horizon 20 minutes.

## Measurement

Triggered, shadow, near-miss and MC snapshots receive executable outcomes at
5, 15, 30, 60, 120, 300, 600 and 1200 seconds. Pending symbols remain in depth
coverage until their 20-minute outcome is complete. One episode ID is retained
for 20 minutes to prevent repeated rows from being treated as independent
pumps.

The lightweight universe threshold is 1M USDT 24-hour quote volume. Up to 60
symbols receive warm flow coverage, 20 receive normal deep evaluation, up to
30 can be forced into decision coverage when an MC threshold is crossed, and
25 receive routine depth coverage plus all forced/pending symbols.

All paper slots, policies, snapshots and outcomes are exported to SQLite and
compressed CSV, including the new `momentum_slots` and
`momentum_policy_runs` tables.


## v4.3.9 profit challengers

- `MC_HOLD_120` is the MC5 primary paper policy: emergency -2% stop, otherwise
  full executable exit after 120 seconds.  `MC_TRAIL_1P0` and `MC_FIXED_TP4`
  remain parallel controls.
- `D_TARGET1_HOLD_300` is an additional TRADE3 policy.  It keeps the frozen
  entry and initial stop, protects at +0.30% after reaching +1%, and otherwise
  exits after 300 seconds.
- TRADE3 now defers thin signals observed on stale trade/book data and vetoes
  the composite very-thin + weak-money + bad-book pattern.  Deferred candidates
  remain in shadow telemetry.
- A dedicated 500 ms position watcher separates stop/exit execution from the
  one-second universe scan.
- Forward outcomes add 90, 150, 180 and 240 second checkpoints.
