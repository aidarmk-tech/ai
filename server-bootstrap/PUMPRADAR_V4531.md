# PumpRadar 4.5.3.1 — exit-window research patch

## Scope

Research-only update. The active champions are unchanged:

- SHORT: `REV_MC5_SHORT_600_2X`;
- DUMP: 600-second hold;
- ONSET: shadow-only;
- no Binance order endpoints or real-account actions.

## Added

- `exit_window_samples`: 5-second observations from 480–660 and 780–960 seconds;
- fixed shadow exits around 600 and 900 seconds;
- 5m/15m candle-close-minus-50/30/20-second challengers;
- adaptive pre-exit challengers after TARGET_1 and adverse giveback/microstructure;
- `regime_channel_health`: rolling 10/20 PnL, PF, win rate, stop rate and TARGET_1 giveback rate;
- live MFE/MAE and `last_updated_at_ms` persistence for open slots;
- coverage audit throttled to at most once per 60 seconds by default;
- new export/status diagnostics.

## Validation

- 46/46 tests;
- compile and real-order safety scan passed;
- migration against the latest 4.5.3 snapshot: integrity `ok`, foreign-key violations `0`;
- historical SHORT/DUMP/ONSET counts and PnL unchanged by migration.

## Install

```bash
curl -fsSL \
https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-v453-short-risk-client/server-bootstrap/install-pumpradar-v4531.sh \
| bash
```

The installer validates the live files, executes the 46 tests, waits up to 1800 seconds for open paper slots to close, creates a verified backup, installs atomically and rolls back automatically on failure.

## Rollback

```bash
python3 /root/install-pumpradar-v4531.py rollback
```
