# PumpRadar v4.5.3 — SHORT risk research and verified client snapshots

## Control baseline

The release was migrated and validated against `pumpradar-before-v453-20260731-182921.sqlite3.gz`:

- SHA-256: `a037c0149e7fea7fa95520d545fb6a18ab46b32c792cba864b54b160cf0c87f1`;
- SQLite integrity: `ok`;
- 16 closed `REV_MC5_SHORT_600_2X` positions;
- 10 wins, 6 losses;
- net PnL preserved at `+7.6735198626 USDT`.

## Trading scope

The current SHORT champion, DUMP channel and ONSET shadow behavior are unchanged. v4.5.3 adds observation-only challengers:

- `continuation_pressure`;
- `reversal_confirmation`;
- continuation veto, failed-retest/reversal, and lagged BTC-regime entry variants;
- `TARGET1_PROTECTED` and `TARGET1_PARTIAL_50` exit variants;
- automatic SHORT loss labels.

No challenger opens, blocks or modifies a champion position in this release.

## Recorder 2.1

Recorder 2.1 remains at the actual deployed service and data paths:

- service: `pumpradar-research-recorder.service`;
- source: `/opt/pumpradar/research_recorder/recorder.py`;
- data: `/var/lib/pumpradar/research`.

It adds a 180-second feature ring, sub-second signal import, coverage/lag diagnostics, synchronized trade/depth quality flags, and public all-market liquidation observations. Invalid liquidity-mechanics rows are retained for audit but cannot trigger absorption research events.

## Lagged BTC regime

A daily updater derives seven economic factors from 29 prior-day BTC datasets. It never consumes the current UTC day and never selects the intraday entry time.

Data attribution: Crypto market data provided by [Crypto Market Data](https://github.com/ErcinDedeoglu/crypto-market-data) by Ercin Dedeoglu. License: CC BY 4.0.

## Client 1.1.0

The Android client creates a new export before download, sends no-cache requests, validates manifest size and SHA-256, removes invalid downloads, and displays server/run/config plus Recorder and SHORT research status.

## Validation

- 40 server unit tests;
- Recorder 2.1 self-test;
- BTC regime updater self-test;
- compile checks;
- real-order static scan;
- migration on the control database;
- `PRAGMA integrity_check=ok`;
- zero foreign-key violations;
- unchanged SHORT baseline;
- Android APK build.

## Safe installation bundle

The verified deployment bundle is distributed as `PumpRadar-v4.5.3-Server-Release.tar.gz` together with its SHA-256 file. After placing it in `/root`:

```bash
mkdir -p /root/pumpradar-v453-install
rm -rf /root/pumpradar-v453-install/*
tar -xzf /root/PumpRadar-v4.5.3-Server-Release.tar.gz -C /root/pumpradar-v453-install
bash /root/pumpradar-v453-install/PumpRadar-v4.5.3-Server-Release/install.sh
```

The installer waits for all open paper slots to close, rechecks after stopping the services, creates a verified database/code backup, installs atomically, verifies runtime versions and automatically restores the backup on failure.

## Rollback

```bash
bash /root/pumpradar-v453-install/PumpRadar-v4.5.3-Server-Release/rollback.sh
```
