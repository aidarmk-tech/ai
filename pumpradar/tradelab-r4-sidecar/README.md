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
- spread `<= 10 bps`;
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

## Infra hardening v2 (audit follow-up, branch fix/r4-g1-g7)

Live-data audit of the R4C epoch produced three infrastructure fixes (**no intentional strategy-parameter/signal-rule changes**):

- **G1 keyset pagination** — `step()` advances a composite cursor `(ts_ms, symbol)` instead of the watermark `ts_ms > last_seen`. Removes permanent row skips when a 5000-row page breaks inside one timestamp group, plus the startup off-by-one at `MAX(ts_ms)`. **Scope: continuity within one live process.** Cross-restart backlog replay is intentionally skipped and tracked in #37 — do not add a persistent cursor without an exactly-once/dedup design.
- **G2/G3 idempotent close** — base `_close_trade` guards the UPDATE with `status='OPEN'` and credits equity / emits `PAPER_CLOSE` only when exactly one row changed. Mirrors the isolation sidecar guard.
- **G4 hotfix guard** — base entrypoint refuses `--run` over an isolated DB (`meta.r4_isolation_hotfix_started_at_ms` present); operators must use `tradelab_r4_isolation.py --run` (R4_OPEN namespace).
- **CI** — `.github/workflows/r4-tests.yml` runs unit tests and a no-real-order-API scan.

Per-symbol gap detection (prototype G7) was **reverted during review**: live holes (e.g. COTIUSDT 115 min) stem from dynamic universe/watchlist rotation, not recorder outages. Writing universe absence into `recorder_gaps` would corrupt its semantics, and prod `UNIQUE(start_ms, end_ms)` forbids multi-symbol rows anyway. Classification design (`RECORDER_GAP` vs `UNIVERSE_ABSENCE`) is tracked in #38.

Label consistency can be checked any time against the live DB (read-only):

```bash
python3 audit_forward_labels.py --db /var/lib/tradelab/tradelab.sqlite3
```

## Label units

`forward_labels.ret_5s .. ret_300s` are stored as **plain percentages**: `ret_300s = (p_end / p_start - 1) * 100`, where `p_start` is the sample at the state ts and `p_end` is the first sample at `ts + 300 s`. Verified to machine precision during the R4C audit; `audit_forward_labels.py` re-checks continuously and exits 3 on drift.

## Candidate roadmap v2 (research queue, owner-approved)

Ordered; none of these change existing participants; each joins via `meta['join_started_at_ms_<PID>']` without resetting the R4C epoch:

1. `LIQ_CASCADE_REVERSION` — causal reversal after liquidation cascades (source: `liquidations`).
2. `BASIS_PREMIUM_REVERSION` — extreme perp premium vs index mean-reversion (source: `mark_price` vs `index_price`).
3. `STAT_ARB_V2` — pair logic A/B vs legacy STAT_ARB; FIFO opening (no score priority), z-corridor [2.0; 3.0].
4. `FLOW_TOXICITY` research track on `flow_samples`, then shadow (adverse-selection stress-tested first).
5. `OI_FLUSH_REVERSION` — open-interest flush + price dislocation reversion.
6. `XS_MOMENTUM_NEUTRAL` — cross-sectional long-top/short-bottom replacement candidate for REGIME_MOMENTUM.
7. HFT slot returns only via the hftbacktest/GLFT queue-aware track once event-level L2 capture exists.
8. Meta-labeling filter on existing `forward_labels` (MFE/MAE triple-barrier labels already computed) to size/filter EXTREME entries ex-ante.
