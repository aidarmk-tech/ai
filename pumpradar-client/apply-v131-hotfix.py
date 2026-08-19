#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).parent / "app/src/main/java/com/aidar/pumpradar/client/MainActivity.java"
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    text = text.replace(old, new, 1)


replace_once(
    '    private static final long REFRESH_MS = 15_000L;\n    private static final String CLIENT_VERSION = "1.3.0";\n',
    '    private static final long REFRESH_MS = 15_000L;\n'
    '    private static final int DEFAULT_READ_TIMEOUT_MS = 120_000;\n'
    '    private static final int EXPORT_READ_TIMEOUT_MS = 300_000;\n'
    '    private static final long MANIFEST_POLL_TIMEOUT_MS = 120_000L;\n'
    '    private static final long MANIFEST_POLL_INTERVAL_MS = 5_000L;\n'
    '    private static final String CLIENT_VERSION = "1.3.1";\n',
    "version/constants",
)

replace_once(
    '    private static final String[] EXPORT_PATHS = {\n        "/api/export", "/export"\n    };\n',
    '    private static final String PRIMARY_EXPORT_PATH = "/api/export";\n',
    "export path",
)

start = text.index('    private void requestFreshSnapshot() {')
end = text.index('    private EndpointResult getFirstJson', start)
new_snapshot = r'''    private void requestFreshSnapshot() {
        if (!snapshotDownloading.compareAndSet(false, true)) return;
        String base = normalizeUrl(serverUrl.getText().toString());
        snapshotButton.setEnabled(false);
        snapshotStatus.setText("Запуск нового export на сервере… Это может занять несколько минут.");

        executor.execute(() -> {
            try {
                long baselineExportedAtMs = latestManifestTimestamp(base);
                long requestedAtMs = System.currentTimeMillis();
                JSONObject exportJson = new JSONObject();
                boolean exportTimedOut = false;

                try {
                    EndpointResult export = requestJson(
                        base,
                        PRIMARY_EXPORT_PATH,
                        "POST",
                        EXPORT_READ_TIMEOUT_MS
                    );
                    if (export.status < 200 || export.status >= 300) {
                        throw new IllegalStateException(
                            PRIMARY_EXPORT_PATH + " → HTTP " + export.status + " " + compact(export.body)
                        );
                    }
                    exportJson = export.json;
                } catch (SocketTimeoutException timeout) {
                    exportTimedOut = true;
                    runOnUiThread(() -> snapshotStatus.setText(
                        "Сервер дольше 5 минут формирует export. Соединение завершилось по таймауту, " +
                        "но задача на сервере могла продолжиться. Жду свежий manifest…"
                    ));
                }

                EndpointResult manifestResult = waitForFreshManifest(
                    base,
                    baselineExportedAtMs,
                    requestedAtMs
                );
                SnapshotMetadata metadata = SnapshotMetadata.from(exportJson, manifestResult.json);
                String exportRoot = manifestResult.path.substring(
                    0,
                    manifestResult.path.length() - "/manifest.json".length()
                );
                long nonce = System.currentTimeMillis();
                final boolean recoveredAfterTimeout = exportTimedOut;

                runOnUiThread(() -> snapshotStatus.setText(
                    (recoveredAfterTimeout ? "✅ Export завершился после таймаута клиента\n" : "✅ Snapshot создан\n") +
                    "Создан: " + formatTime(metadata.exportedAtMs) + "\n" +
                    "Версия: " + metadata.algorithmVersion + "\n" +
                    "Config hash: " + emptyDash(metadata.configHash) + "\n" +
                    "Размер: " + formatBytes(metadata.expectedBytes) + "\n" +
                    "SHA-256: " + metadata.expectedSha256 + "\n\nСкачивание…"
                ));

                String target = base + exportRoot + "/pumpradar.sqlite3.gz?fresh=" +
                    metadata.exportedAtMs + "-" + nonce;
                DownloadResult result = downloadVerified(target, metadata);
                runOnUiThread(() -> {
                    snapshotStatus.setText(
                        "✅ База скачана и проверена\n" +
                        "Создана: " + formatTime(metadata.exportedAtMs) + "\n" +
                        "Версия: " + metadata.algorithmVersion + "\n" +
                        "Run ID: " + emptyDash(metadata.runId) + "\n" +
                        "Размер: " + formatBytes(result.bytes) + "\n" +
                        "SHA-256: " + result.sha256 + "\n" +
                        "Сохранено: " + result.location
                    );
                    Toast.makeText(this, "Snapshot проверен и сохранён", Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> snapshotStatus.setText(
                    "❌ Snapshot не получен\n\n" + describeError(error)
                ));
            } finally {
                snapshotDownloading.set(false);
                runOnUiThread(() -> snapshotButton.setEnabled(true));
            }
        });
    }

    private long latestManifestTimestamp(String base) {
        try {
            EndpointResult result = getFirstJson(
                base,
                appendNonce(MANIFEST_PATHS, System.currentTimeMillis())
            );
            return optLongAny(result.json, 0L, "exported_at_ms", "exportedAtMs");
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private EndpointResult waitForFreshManifest(
        String base,
        long baselineExportedAtMs,
        long requestedAtMs
    ) throws Exception {
        long deadline = System.currentTimeMillis() + MANIFEST_POLL_TIMEOUT_MS;
        Exception lastError = null;

        while (System.currentTimeMillis() <= deadline) {
            try {
                long nonce = System.currentTimeMillis();
                EndpointResult result = getFirstJson(base, appendNonce(MANIFEST_PATHS, nonce));
                long exportedAtMs = optLongAny(result.json, 0L, "exported_at_ms", "exportedAtMs");
                boolean newerThanBaseline = baselineExportedAtMs <= 0L || exportedAtMs > baselineExportedAtMs;
                boolean plausiblyFresh = exportedAtMs >= requestedAtMs - 60_000L;
                if (exportedAtMs > 0L && newerThanBaseline && plausiblyFresh) return result;

                lastError = new IllegalStateException(
                    "Manifest ещё старый: exported_at_ms=" + exportedAtMs +
                    ", baseline=" + baselineExportedAtMs
                );
            } catch (Exception error) {
                lastError = error;
            }

            runOnUiThread(() -> snapshotStatus.setText(
                "Export ещё формируется. Жду свежий manifest…"
            ));
            Thread.sleep(MANIFEST_POLL_INTERVAL_MS);
        }

        String detail = lastError == null ? "нет дополнительной диагностики" : describeError(lastError);
        throw new IllegalStateException(
            "Свежий manifest не появился в течение 2 минут после ожидания export. " + detail
        );
    }

'''
text = text[:start] + new_snapshot + text[end:]

old_request = r'''    private EndpointResult requestJson(String base, String path, String method) throws Exception {
        HttpURLConnection connection = openConnection(base + path, method);
        connection.setRequestProperty("Accept", "application/json");
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
            connection.connect();
            try (OutputStream output = connection.getOutputStream()) {
                output.flush();
            }
        }
        int status = connection.getResponseCode();
        String body = readAll(responseStream(connection, status));
        connection.disconnect();
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (JSONException error) {
            throw new IllegalStateException(
                "HTTP " + status + " вернул не JSON: " + compact(body),
                error
            );
        }
        return new EndpointResult(pathOnly(path), status, body, json);
    }

    private HttpURLConnection openConnection(String target, String method) throws Exception {
        URL url = new URL(target);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (!(connection instanceof HttpsURLConnection)) {
            throw new IllegalStateException("Разрешён только HTTPS. Получено: " + url.getProtocol());
        }
        connection.setUseCaches(false);
        connection.setDefaultUseCaches(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(120_000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("User-Agent", "PumpRadar-Server-Client/" + CLIENT_VERSION);
        connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
        connection.setRequestProperty("Pragma", "no-cache");
        return connection;
    }
'''

new_request = r'''    private EndpointResult requestJson(String base, String path, String method) throws Exception {
        return requestJson(base, path, method, DEFAULT_READ_TIMEOUT_MS);
    }

    private EndpointResult requestJson(
        String base,
        String path,
        String method,
        int readTimeoutMs
    ) throws Exception {
        HttpURLConnection connection = openConnection(base + path, method, readTimeoutMs);
        connection.setRequestProperty("Accept", "application/json");
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
            connection.connect();
            try (OutputStream output = connection.getOutputStream()) {
                output.flush();
            }
        }
        int status = connection.getResponseCode();
        String body = readAll(responseStream(connection, status));
        connection.disconnect();
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.startsWith("<html") || trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<!doctype")) {
            throw new IllegalStateException(
                "HTTP " + status + " вернул HTML вместо JSON: " + compact(body)
            );
        }
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (JSONException error) {
            throw new IllegalStateException(
                "HTTP " + status + " вернул не JSON: " + compact(body)
            );
        }
        return new EndpointResult(pathOnly(path), status, body, json);
    }

    private HttpURLConnection openConnection(String target, String method) throws Exception {
        return openConnection(target, method, DEFAULT_READ_TIMEOUT_MS);
    }

    private HttpURLConnection openConnection(
        String target,
        String method,
        int readTimeoutMs
    ) throws Exception {
        URL url = new URL(target);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (!(connection instanceof HttpsURLConnection)) {
            throw new IllegalStateException("Разрешён только HTTPS. Получено: " + url.getProtocol());
        }
        connection.setUseCaches(false);
        connection.setDefaultUseCaches(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestMethod(method);
        connection.setRequestProperty("User-Agent", "PumpRadar-Server-Client/" + CLIENT_VERSION);
        connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
        connection.setRequestProperty("Pragma", "no-cache");
        return connection;
    }
'''
replace_once(old_request, new_request, "request/openConnection")

replace_once(
    '    private static String describeError(Throwable error) {\n'
    '        Throwable current = error;\n',
    '    private static String describeError(Throwable error) {\n'
    '        String topMessage = error.getMessage();\n'
    '        if (topMessage != null && (topMessage.contains("вернул HTML") || topMessage.contains("вернул не JSON") || topMessage.contains("Ни один endpoint") || topMessage.contains("Свежий manifest"))) {\n'
    '            return topMessage;\n'
    '        }\n'
    '        Throwable current = error;\n',
    "describeError",
)

replace_once(
    '            String exportDir = optStringAny(exportResult, "", "export_dir", "exportDir", "path");\n'
    '            if (exportDir.isEmpty()) throw new IllegalStateException("Сервер не подтвердил создание export");\n\n',
    '',
    "manifest export_dir requirement",
)

path.write_text(text, encoding="utf-8")
print("Applied PumpRadar Client 1.3.1 snapshot hotfix")
