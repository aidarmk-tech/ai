# PumpRadar Measurement Server

Version 4.3.8 runs two independent paper cohorts from public Binance market data.
It has no order endpoint and does not require Binance API keys.

## Frozen control: TRADE3

The existing v4.3.6 strict entry and exit logic remains unchanged. It continues
as the control cohort so its results stay comparable with earlier runs.

## Momentum Continuation challenger

The challenger tests confirmed large momentum:

- MC3 shadow: `return_3m >= +3%`;
- MC5 paper: `return_5m >= +5%`;
- MC7 shadow: `return_10m >= +7%`;
- fresh executable bid/ask, acceptable spread, slippage and depth are required;
- `STRONG_BUT_LATE` is not a veto for this challenger;
- stop: `-2.0%`;
- trailing activation: `+1.5%`;
- trailing drawdown from peak: `1.0%`;
- maximum horizon: `20 minutes`;
- fixed `+4% / -2%` is stored as an independent control exit.

MC5 owns a separate paper slot and never blocks or replaces the frozen TRADE3
control slot. MC3 and MC7 are shadow measurements only.

## Measurement coverage

The server uses a 1 million USDT minimum 24-hour quote volume, ranks up to 30
candidates, keeps 60 symbols warm, evaluates 20 deeply and maintains order
books for up to 25. Outcomes remain observed through the full 20-minute horizon.

## Safe update

The installer preserves SQLite and rclone/Google Drive configuration, creates a
backup, verifies SHA-256, checks the running API version and rolls back if the
new service does not start correctly.
