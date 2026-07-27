# PumpRadar Measurement Server

Version 4.3.8 runs two independent paper cohorts from public Binance market data.
It has no order endpoint and does not require Binance API keys.

## Frozen control: TRADE3

The existing v4.3.6 strict entry and exit logic remains unchanged. It continues
as the control cohort so its results stay comparable with earlier runs.

## Momentum Continuation challenger

The challenger tests the hypothesis that confirmed large momentum has a larger
forward edge than early weak acceleration:

- MC3 shadow: return over 3 minutes is at least +3%;
- MC5 paper: return over 5 minutes is at least +5%;
- MC7 shadow: return over 10 minutes is at least +7%;
- technical safety still requires fresh executable bid/ask and acceptable
  spread, slippage and depth;
- `STRONG_BUT_LATE` is not a veto for the challenger;
- stop: -2.0%;
- trailing activation: +1.5%;
- trailing drawdown from peak: 1.0%;
- maximum horizon: 20 minutes;
- fixed +4% / -2% policy is stored as an independent control exit.

MC5 owns a separate paper slot and never blocks or replaces the frozen TRADE3
control slot. MC3 and MC7 are shadow measurements only.

## Measurement coverage

The server uses a wider liquid universe with a 1 million USDT minimum 24-hour
quote volume, ranks up to 30 candidates, keeps 60 symbols warm, evaluates 20 in
depth and maintains order books for up to 25. Existing snapshot outcomes and
new momentum outcomes are retained through the full 20-minute horizon.

## Safety and persistence

SQLite data and rclone/Google Drive configuration are preserved during updates.
The installer creates a backup before replacing the server, verifies the exact
payload SHA-256, checks the running API version and automatically rolls back if
the new service does not start correctly.
