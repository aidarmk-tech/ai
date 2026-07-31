# PumpRadar v4.5.2-research

This is a deliberately narrow evidence-collection release layered on the existing v4.5.1 paper model.

## Decision encoded by this release

- `ONSET_LONG_SHADOW` no longer creates new paper slots.
- Existing open ONSET paper slots are allowed to finish naturally.
- `DUMP_EXHAUSTION_LONG_SHADOW` is unchanged.
- `REV_MC5_SHORT_600_2X` is unchanged.
- No real orders, API keys, signed requests, or Binance order endpoints are added.
- `ABSORPTION_SHORT_SHADOW` is observation-only.

The ONSET block is implemented as two reversible SQLite triggers because the currently deployed v4.5.1 server source is payload-pinned. One trigger ignores new ONSET slot inserts; the second marks matching signals as `SHADOW_ONLY_V452`. Rollback removes both triggers.

## Research Recorder 2.0

Recorder v2 writes to `/var/lib/pumpradar/research-v2`; it does not overwrite the v1 archive in `/var/lib/pumpradar/research`.

### Liquidity mechanics

Every top-10 depth change is split into:

- `*_eaten_quote`: visible quantity explained by aggressive trades at that price;
- `*_cancelled_quote`: disappearing visible quantity not explained by trades;
- `*_refilled_quote`: quantity restored or added after executions;
- percentages relative to the preceding visible book;
- cancellation/refill velocity;
- weighted displayed-level persistence.

Levels that move outside the next visible top-20 band are not labelled as cancellations.

### Positioning

Recorder v2 stores:

- open interest;
- OI change over 1, 5, and 15 minutes;
- funding rate and next funding time;
- mark price, index price, and premium/basis in basis points.

### Shadow hypothesis

`ABSORPTION_SHORT_SHADOW` requires active aggressive buying, significant ask refill relative to eaten ask, and weak 15-second price response. It never opens a paper or real position. Outcomes are evaluated directionally at:

- 900 seconds;
- 1,800 seconds;
- 3,600 seconds.

### Main-server linkage

The recorder polls `regime_signals`, imports `signal_id`, `channel`, `symbol`, `disposition`, and `created_at_ms`, forces eligible signal symbols into its universe, and opens a raw capture around each server signal.

### Storage controls

- hourly compressed second frames;
- separate compressed positioning data;
- raw captures only around research/server events;
- default 7-day retention;
- 2.5 GB total data cap;
- 600 MB raw-capture cap.

## Installation

```bash
curl -fsSL https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-v452-liquidity-research/server-bootstrap/install-v452-research.sh | bash
```

The installer:

1. materializes the embedded source into a temporary directory;
2. compiles it and runs nine unit tests;
3. statically rejects Binance order endpoints and API-key/signature markers;
4. creates an integrity-checked SQLite backup of the main database;
5. preserves recorder v1 source, unit, and data;
6. applies the reversible ONSET evidence guard;
7. installs and starts `pumpradar-research-recorder-v2.service`;
8. automatically rolls back service state and triggers if startup fails.

## Verification

```bash
systemctl status pumpradar-research-recorder-v2.service --no-pager -l
pumpradar-onset-guard status
pumpradar-research-v2-status
```

## Snapshot

```bash
pumpradar-research-v2-snapshot
```

The command prints the path to a complete `.tar.gz` archive in `/root`.

## Rollback

```bash
curl -fsSL https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-v452-liquidity-research/server-bootstrap/install-v452-research.sh | bash -s -- rollback
```

Rollback stops recorder v2, removes the ONSET triggers, and restores recorder v1. Recorder v2 data is retained.
