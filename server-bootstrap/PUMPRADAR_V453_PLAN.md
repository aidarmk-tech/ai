# PumpRadar v4.5.3 — SHORT risk, Recorder quality and client reliability

## Control snapshot

The implementation is evaluated against the integrity-checked production snapshot:

- file: `pumpradar-before-v453-20260731-182921.sqlite3.gz`
- SHA-256: `a037c0149e7fea7fa95520d545fb6a18ab46b32c792cba864b54b160cf0c87f1`
- SQLite `PRAGMA integrity_check`: `ok`
- active run: `4.5.2-server`
- config hash: `a0f7f61f02e83e7a`

Verified closed `REV_MC5_SHORT_600_2X` baseline:

- 16 closed trades;
- 10 wins / 6 losses;
- net PnL: `+7.673520 USDT`;
- sum of net margin returns: `+38.367599%`;
- profit factor: `2.2737`.

The six losing symbols are `RLCUSDT`, `BANKUSDT`, `DEXEUSDT`, `COTIUSDT`, `TLMUSDT`, and `MMTUSDT`. Four of the six generated meaningful favourable excursion before the final stop, therefore entry-quality and exit-path failures must be measured separately.

## Safety boundary

- Paper/research only. No real Binance order endpoint, API key, request signature, leverage-setting request, or account endpoint.
- `DUMP_EXHAUSTION_LONG_SHADOW` remains unchanged.
- `ONSET_LONG_SHADOW` remains shadow-only.
- Existing `REV_MC5_SHORT_600_2X` remains the champion.
- No production threshold is replaced by a value fitted to the 16-trade sample.
- All new entry and exit variants run as parallel challengers until minimum evidence gates are met.

## Server work

### 1. Recorder 2.1 quality contract

Every signal capture must include at least 180 seconds before the signal and retain data after the signal through all configured outcomes.

Add quality fields:

- `signal_import_delay_ms`;
- `pre_signal_coverage_seconds`;
- `depth_stream_ok`;
- `trade_stream_ok`;
- `positioning_stream_ok`;
- `timestamp_skew_ms`;
- `matching_confidence`;
- `quality_blockers`.

Acceptance:

- signal-import delay p95 <= 2,000 ms;
- pre-signal coverage >= 95% for eligible captures;
- completed gzip files pass integrity checks;
- impossible liquidity decomposition rows are quarantined rather than used as evidence.

### 2. Liquidity-decomposition correction

Synchronize depth changes and aggregate trades by exchange event time. A removed ask quantity may be labelled `eaten` only up to the confirmed aggressive-buy quantity matched to that level and interval. The remainder is cancellation/unknown. Equivalent logic applies to bids.

Persist:

- raw book loss;
- confirmed eaten amount;
- cancelled amount;
- refill amount;
- unmatched amount;
- trade/depth matching window;
- matching confidence.

Rows where percentages are undefined or exceed the physically valid range are retained for diagnostics but excluded from signal calculations.

### 3. Coin-level derivatives collector

For each SHORT candidate collect, at minimum:

- open interest and changes over 5/15/30/60/180 seconds;
- funding rate;
- futures/spot basis and premium;
- futures and spot CVD;
- taker-buy ratios over 5/15/30 seconds;
- long and short liquidation volume;
- high-update frequency and time since high;
- relative strength versus BTC;
- market breadth and sector breadth where available.

### 4. Two independent scores

Add observation-only scores:

- `continuation_pressure`: probability that the impulse/squeeze is still active;
- `reversal_confirmation`: evidence that the high failed and aggressive buying weakened.

They must be stored with component contributions and missing-data flags. The score is not allowed to hide missing critical inputs.

### 5. Champion/challenger matrix

For every champion SHORT signal produce virtual outcomes for:

- `SHORT_CHAMPION_V452` — current entry and exit;
- `SHORT_CONTINUATION_VETO_V1` — champion entry rejected when continuation risk is high;
- `SHORT_RETEST_CONFIRMED_V1` — entry only after failed high retest and weakening derivatives;
- `SHORT_TARGET1_PROTECTED_V1` — champion entry with protected net-positive floor after target 1;
- `SHORT_TARGET1_PARTIAL_V1` — 50% target-1 realization plus protected remainder.

All variants use the same point-in-time market data and executable pricing assumptions.

### 6. Failure classification

Assign one or more post-trade labels:

- `SIGNAL_FAILURE`;
- `CONTINUATION_SQUEEZE`;
- `STOP_PATH_FAILURE`;
- `PROFIT_PROTECTION_FAILURE`;
- `EXECUTION_FAILURE`;
- `DATA_QUALITY_FAILURE`.

Do not train on labels calculated from information unavailable at entry.

### 7. BTC regime factors

Daily on-chain/derivatives metrics are slow regime priors, not second-level entry triggers. Use only completed, lagged values (`t-1`) and reduce the raw metrics to a small set of factors:

- valuation;
- exchange pressure;
- miner pressure;
- leverage;
- liquidation regime;
- regional premium;
- stablecoin liquidity.

Store source timestamp and data freshness. Missing or revised daily data must never silently reuse a future value.

## Client work

### Snapshot reliability

- create a new SQLite `.backup` on request;
- return immutable snapshot ID, creation time, byte size and SHA-256;
- disable HTTP/cache reuse for snapshot downloads;
- verify SHA-256 after download;
- display the database timestamp and active server version;
- prevent a 4.5.1 file from being presented as a fresh 4.5.2/4.5.3 snapshot.

### Research status

Display:

- server algorithm version, strategy version, config hash and run ID;
- Recorder schema version and health, determined from status/schema rather than a `-v2` service name;
- signal import delay and pre-signal coverage;
- stream-quality flags;
- champion versus challenger counts and outcomes;
- SHORT failure labels;
- target-1-to-stop cases;
- stop overshoot and execution latency.

### Compatibility

The client must tolerate older servers and show `not supported` instead of inventing zero values. New fields are nullable and version-gated.

## Promotion gates

No challenger replaces the champion before:

- at least 200 SHORT candidates;
- at least 100 complete executable outcomes;
- at least 7–14 days spanning more than one market regime;
- positive mean and median net outcome;
- profit factor >= 1.30 after costs;
- at least 25% reduction in aggregate loss versus champion on matched events;
- at least 50% reduction in target-1-to-full-loss cases;
- no single event contributing more than 20% of aggregate loss;
- data-quality acceptance gates passing.

## Required implementation inputs

The deployed server differs from the public v4.5.2 PR description. Before changing live code, capture the exact installed application source and service definitions. Do not patch the compressed historical payload blindly.
