# TradeLab Android client 0.1

Purpose: monitoring + snapshot transport only. It does not contain trading logic.

Build-time environment variables:

- `TRADELAB_SERVER_URL=https://your-server`
- `TRADELAB_READ_TOKEN=<read-only-token>`

Behavior:

- schedules WorkManager every 4 hours while network is connected;
- asks the server for every retained snapshot newer than the last verified local snapshot, so Android Doze/delays do not create gaps while the server retention window still contains them;
- downloads to app external storage;
- verifies exact byte length and SHA-256;
- atomically renames `.part` only after verification;
- keeps the newest 15 `.sqlite3.gz` snapshots;
- posts an Android system notification after verified download; catch-up downloads are aggregated into one notification;
- `Download fresh snapshot now` first asks the server to make an atomic current SQLite snapshot and then downloads/verifies it immediately.

WorkManager timing is Android best-effort: the OS can delay background work for battery/doze. The catch-up API preserves missed four-hour files within server retention, and the fresh manual action remains available at any time.
