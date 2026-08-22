# TradeLab Android client 0.1

Purpose: monitoring + snapshot transport only. It does not contain trading logic.

Build-time environment variables:

- `TRADELAB_SERVER_URL=https://your-server`
- `TRADELAB_READ_TOKEN=<read-only-token>`

Behavior:

- schedules WorkManager every 4 hours while network is connected;
- requests the latest snapshot manifest;
- downloads to app external storage;
- verifies exact byte length and SHA-256;
- atomically renames `.part` only after verification;
- keeps the newest 15 `.sqlite3.gz` snapshots;
- posts an Android notification after a verified download;
- `Download snapshot now` triggers the same worker manually.

WorkManager timing is Android best-effort: the OS can delay background work for battery/doze. Manual download remains available at any time.
