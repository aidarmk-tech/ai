# PumpRadar 4.5.3.1 — exit-window research

This patch keeps the champion SHORT entry, the 600-second production DUMP hold,
and ONSET shadow-only behavior unchanged. It adds observation-only exit-window
telemetry and challengers for SHORT and DUMP.

## Research additions

- five-second samples during ages 480–660 and 780–960 seconds;
- fixed shadow exits at 540–600 and 840–900 seconds;
- UTC-aligned 5m/15m candle-close-minus-50/30/20 exits;
- configurable `ADAPTIVE_TARGET1_PROTECTION`, shadow-only;
- rolling DUMP health diagnostics;
- live OPEN-slot directional return, MFE, MAE and update time;
- throttled coverage events;
- export/status support for all new research tables.

No authenticated Binance endpoint, order route, API key, signing code, or real
trading command is included.

## Release bundle

The deliverable archive contains this installer plus `payload/server`, populated
from the reviewed `pumpradar-server` tree. Installation waits for paper slots to
close, validates tests and migration on a database copy, backs up the live source,
and rolls back automatically on failure.

```bash
tar -xzf /root/PumpRadar-v4.5.3.1-Server-Release.tar.gz -C /root
bash /root/PumpRadar-v4.5.3.1-Server-Release/install.sh
```

Rollback:

```bash
bash /root/PumpRadar-v4.5.3.1-Server-Release/rollback.sh
```
