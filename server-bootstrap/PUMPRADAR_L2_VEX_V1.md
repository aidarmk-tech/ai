# PumpRadar L2 State + VEX Research V1

Independent research sidecar for the existing PumpRadar `4.9.2-server` control and `4.9.4-RESEARCH-CHALLENGER-V1` H05 experiment.

## Safety boundary

- **No active trading effect.** `active_strategy_effect=NONE`.
- Does not edit `pumpradar.service` or `pumpradar-research-episodes.service`.
- Does not restart either service during installation.
- Public Binance USD-M futures market data only: aggTrades, bookTicker, partial depth20, mark/funding and open interest.
- No API keys, signatures, account endpoints, leverage setting or order endpoints.

## L2 State Engine V1

Based on the previously validated Recorder 2.0 depth accounting:

- visible top-10 depth split into **eaten / cancelled / refilled**;
- 15-second taker flow and buy ratio;
- ask/bid refill-to-eaten ratios;
- top-10 OBI, spread, book/trade freshness;
- OI 1m change, funding and premium;
- states: `BALANCED`, `BUY_PRESSURE`, `SELL_PRESSURE`, `BUY_SWEEP`, `SELL_SWEEP`, `ASK_ABSORPTION`, `BID_ABSORPTION`, `DATA_UNSAFE`.

Recent PumpRadar `regime_signals` are forced into the L2 depth universe for 15 minutes. A compact L2 context row is linked to every new main-server signal in `research_l2_signal_context`. This is observational only and is designed so L2 can later be evaluated against 4.9.2 PRICE_STOP/win outcomes without changing the strategy now.

Raw second frames are kept separately under `/var/lib/pumpradar/l2-vex/seconds`, default **1-day retention / 300 MB cap**. Main SQLite receives only compact state/current/event/VEX rows. The tighter cap is intentional because the current VPS has already experienced low-disk pressure.

## VEX1_CONFIRMED_BREAKOUT_V1

Frozen initial research model:

1. 60-second volatility range must have recently been at or below its rolling 25th percentile over the prior 20 minutes.
2. Within 180 seconds, price must break the stored 5-minute compression box by at least 5 bps.
3. Directional 15-second return must be at least 0.15%.
4. 15-second quote-volume robust z-score must be at least 2.0.
5. L2 must be supportive (`BUY_SWEEP/BUY_PRESSURE` for LONG, mirror for SHORT), with good freshness/spread quality.
6. Breakout must remain outside the range and keep supportive L2 for 5 seconds before the shadow entry is confirmed.
7. Shadow exit is fixed: 600 seconds or a 1.50% adverse price stop. No target/trailing optimization in V1.

Retest behavior is recorded but **does not change entry** in V1.

## Separate $20 accounting

Each confirmed VEX event records fixed-$20 x2 and x3 counterfactual PnL. In addition two realistic single-slot compounded shadow accounts are maintained:

- `VEX20_X2`: starts at $20, 2x, one open position at a time;
- `VEX20_X3`: starts at $20, 3x, one open position at a time.

Both use the same VEX entries/exits. Accounting deducts 0.04% taker fee on entry and exit plus a conservative 2 bps slippage per side. Funding is recorded as a feature but not charged in V1 because the fixed horizon is only ten minutes; this limitation is explicit in metadata.

## Main SQLite tables

- `research_l2_vex_meta`
- `research_l2_state_current`
- `research_l2_state_events`
- `research_l2_signal_context`
- `research_vex_events`
- `research_vex_portfolios`
- `research_vex_portfolio_trades`

These tables are included automatically in the existing PumpRadar SQLite export, so the **same Android client / same snapshot** can carry Control + H05 + VEX + L2 data. Client 1.4.0 reads these optional tables locally after the normal verified snapshot download.

## Verification

```bash
pumpradar-l2-vex-status
systemctl status pumpradar-l2-vex.service --no-pager -l
```

Expected invariant: main control remains `4.9.2-server`, 4.9.4 remains active with `active_strategy_effect=NONE`, and the L2/VEX sidecar also reports `active_strategy_effect=NONE`.
