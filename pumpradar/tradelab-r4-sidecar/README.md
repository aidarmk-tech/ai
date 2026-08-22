# TradeLab R4 — Five-Model Shadow Lab

R4 keeps five independent SHADOW participants in one TradeLab SQLite database:

1. `BTC_ALT_LAG` — unchanged existing participant.
2. `STAT_ARB` — unchanged existing participant.
3. `REGIME_MOMENTUM` — unchanged existing participant.
4. `HFT_GRID` — replaces `FLOW_ABSORPTION`.
5. `EXTREME_REVERSION` — new V-bottom / spike-exhaustion reversal model.

`FLOW_ABSORPTION` is marked `RETIRED` / `RETIRED_NO_SCORE`; its historical rows are preserved.

## Safety

No Binance account API, API keys, signing, order endpoints, or real-order code are used. The sidecar writes only SHADOW signals, market states, and paper trades into the existing research SQLite database. The installer takes a SQLite online backup before activating R4.

## HFT_GRID caveat

The current recorder stores aggregated depth samples, not event-by-event L2 queue evolution. Therefore R4 deliberately labels HFT fills `TOUCH_PROXY_V1_NO_QUEUE_MODEL`. A virtual maker quote must be touched by a later market sample before a paper position is opened, but queue position is unknown.

Paper PnL uses the lab's common conservative `0.14%` round-trip cost. A `0.04%` maker-cost counterfactual is recorded on HFT closes for diagnosis only. When full L2 event capture is available, replace the execution/fill model with hftbacktest/GLFT queue-aware simulation without changing the participant ID.

## EXTREME_REVERSION V1 (frozen)

Causal rule, both directions:

- at least $5M 24h quote volume;
- prior 60-second displacement ending 15 seconds ago: `>= 0.80%` absolute;
- rolling 5-minute price z-score reached `>= 2.5` absolute during the last 30 seconds;
- subsequent 15-second reversal confirmation: `>= 0.05%` in the opposite direction;
- spread <= 10 bps;
- fixed 300-second paper horizon;
- 300-second per-symbol cooldown;
- max two concurrent positions.

No parameter auto-tuning is performed.

## Install

```bash
sudo bash install.sh
```

If auto-detection does not find the database:

```bash
sudo TRADELAB_DB=/path/to/tradelab.sqlite3 bash install.sh
```

## R4 scoring

Activation stores `five_model_epoch_started_at_ms`. Compare only paper trades with `opened_at_ms >= five_model_epoch_started_at_ms`. This avoids mixing R3 history with the five-model R4 race. Existing participants are not destructively reset.

## Validation on the supplied six-hour snapshot

This is a code sanity replay, not an out-of-sample performance claim.

- `EXTREME_REVERSION`: 106 signals, 54 paper opens, 52 closed; mean gross `+0.2161%`, mean net after 0.14% common cost `+0.0761%`, paper PnL `+$0.3915` from a $20 participant account with two-slot sizing.
- `HFT_GRID TOUCH_PROXY_V1`: 35 touch signals, 32 closed; mean gross `+0.0125%`, mean common-cost net `-0.1275%`, maker-cost counterfactual mean `-0.0275%`.

The negative HFT sanity result is intentionally not tuned away: the slot remains SHADOW until queue-aware L2 data can validate or reject it honestly.
