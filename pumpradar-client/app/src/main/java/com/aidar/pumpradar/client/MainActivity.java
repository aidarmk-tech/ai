package com.aidar.pumpradar.client;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

public final class MainActivity extends Activity {
    private static final String DEFAULT_URL = "https://45.150.37.187";
    private static final long REFRESH_MS = 15_000L;
    private static final String CLIENT_VERSION = "1.2.0";
    private static final String[] STATUS_PATHS = {
        "/api/status", "/status", "/api/health", "/health"
    };
    private static final String[] EXPORT_PATHS = {
        "/api/export", "/export"
    };
    private static final String[] MANIFEST_PATHS = {
        "/api/export/latest/manifest.json", "/export/latest/manifest.json"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final AtomicBoolean snapshotDownloading = new AtomicBoolean(false);

    private EditText serverUrl;
    private TextView connectionBadge;
    private TextView summary;
    private TextView activeSlot;
    private TextView diagnostics;
    private TextView snapshotStatus;
    private TextView lastUpdate;
    private Button refreshButton;
    private Button snapshotButton;
    private String latestDiagnostics = "Диагностика ещё не выполнена.";
    private boolean resumed;

    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            refreshStatus(false);
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
        refreshStatus(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        handler.removeCallbacks(autoRefresh);
        handler.postDelayed(autoRefresh, REFRESH_MS);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(autoRefresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildContent() {
        SharedPreferences preferences = getSharedPreferences("client", MODE_PRIVATE);
        int pad = dp(16);
        int gap = dp(10);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(245, 247, 250));

        TextView title = text("PumpRadar Server", 26, true);
        title.setTextColor(Color.rgb(20, 25, 35));
        root.addView(title);

        TextView subtitle = text(
            "Клиент " + CLIENT_VERSION + ": совместим со старыми и новыми форматами API, не блокирует сервер по версии и показывает точную причину ошибки.",
            14,
            false
        );
        subtitle.setTextColor(Color.DKGRAY);
        root.addView(subtitle, margins(gap));

        connectionBadge = text("ПРОВЕРКА…", 16, true);
        connectionBadge.setGravity(Gravity.CENTER);
        connectionBadge.setTextColor(Color.WHITE);
        connectionBadge.setBackgroundColor(Color.rgb(100, 100, 100));
        connectionBadge.setPadding(gap, gap, gap, gap);
        root.addView(connectionBadge, margins(gap));

        root.addView(text("Адрес сервера", 14, true), margins(gap));

        serverUrl = new EditText(this);
        serverUrl.setSingleLine(true);
        serverUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverUrl.setText(preferences.getString("server_url", DEFAULT_URL));
        root.addView(serverUrl, fullWidth());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button saveButton = button("Сохранить");
        saveButton.setOnClickListener(v -> {
            String normalized = normalizeUrl(serverUrl.getText().toString());
            serverUrl.setText(normalized);
            preferences.edit().putString("server_url", normalized).apply();
            refreshStatus(true);
        });
        actions.addView(saveButton, weighted());

        refreshButton = button("Проверить");
        refreshButton.setOnClickListener(v -> refreshStatus(true));
        LinearLayout.LayoutParams refreshParams = weighted();
        refreshParams.setMarginStart(gap);
        actions.addView(refreshButton, refreshParams);
        root.addView(actions, margins(gap));

        summary = cardText("Ожидание ответа сервера…");
        root.addView(summary, margins(gap));

        activeSlot = cardText("Активный paper-слот: проверка…");
        root.addView(activeSlot, margins(gap));

        TextView diagnosticTitle = text("Диагностика соединения", 17, true);
        root.addView(diagnosticTitle, margins(gap));

        diagnostics = cardText("Проверяются /api/status и резервные endpoint…");
        diagnostics.setTextIsSelectable(true);
        root.addView(diagnostics, margins(dp(6)));

        Button copyButton = button("Копировать диагностику");
        copyButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("PumpRadar diagnostics", latestDiagnostics));
            Toast.makeText(this, "Диагностика скопирована", Toast.LENGTH_SHORT).show();
        });
        root.addView(copyButton, margins(dp(6)));

        TextView snapshotTitle = text("Свежий snapshot базы", 17, true);
        root.addView(snapshotTitle, margins(gap));

        snapshotButton = button("Создать, скачать и проверить SQLite.GZ");
        snapshotButton.setOnClickListener(v -> requestFreshSnapshot());
        root.addView(snapshotButton, margins(dp(6)));

        snapshotStatus = cardText(
            "Клиент попробует новый и старый export endpoint, затем проверит размер и SHA-256 скачанного файла."
        );
        root.addView(snapshotStatus, margins(dp(6)));

        lastUpdate = text("", 12, false);
        lastUpdate.setTextColor(Color.GRAY);
        root.addView(lastUpdate, margins(gap));

        TextView note = text(
            "APK не содержит ключей Binance и не выставляет ордера. Если VPS или nginx остановлен, клиент покажет отказ соединения — APK не может запустить сервер сам.",
            12,
            false
        );
        note.setTextColor(Color.DKGRAY);
        root.addView(note, margins(gap));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void refreshStatus(boolean userInitiated) {
        if (!loading.compareAndSet(false, true)) return;
        String base = normalizeUrl(serverUrl.getText().toString());
        if (userInitiated) {
            connectionBadge.setText("ПРОВЕРКА…");
            connectionBadge.setBackgroundColor(Color.rgb(100, 100, 100));
        }
        refreshButton.setEnabled(false);

        executor.execute(() -> {
            try {
                EndpointResult result = getFirstJson(base, STATUS_PATHS);
                runOnUiThread(() -> renderStatus(result));
            } catch (Exception error) {
                runOnUiThread(() -> renderError(error));
            } finally {
                loading.set(false);
                runOnUiThread(() -> refreshButton.setEnabled(true));
            }
        });
    }

    private void requestFreshSnapshot() {
        if (!snapshotDownloading.compareAndSet(false, true)) return;
        String base = normalizeUrl(serverUrl.getText().toString());
        snapshotButton.setEnabled(false);
        snapshotStatus.setText("Запуск нового export на сервере…");

        executor.execute(() -> {
            try {
                EndpointResult export = postFirstJson(base, EXPORT_PATHS);
                long nonce = System.currentTimeMillis();
                EndpointResult manifestResult = getFirstJson(base, appendNonce(MANIFEST_PATHS, nonce));
                SnapshotMetadata metadata = SnapshotMetadata.from(export.json, manifestResult.json);
                String exportRoot = manifestResult.path.substring(
                    0,
                    manifestResult.path.length() - "/manifest.json".length()
                );

                runOnUiThread(() -> snapshotStatus.setText(
                    "Snapshot создан: " + formatTime(metadata.exportedAtMs) + "\n" +
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

    private EndpointResult getFirstJson(String base, String[] paths) throws Exception {
        return firstJson(base, paths, "GET");
    }

    private EndpointResult postFirstJson(String base, String[] paths) throws Exception {
        return firstJson(base, paths, "POST");
    }

    private EndpointResult firstJson(String base, String[] paths, String method) throws Exception {
        List<String> attempts = new ArrayList<>();
        Throwable last = null;
        for (String pathWithQuery : paths) {
            String path = pathWithQuery;
            try {
                EndpointResult result = requestJson(base, path, method);
                if (result.status >= 200 && result.status < 300) return result;
                attempts.add(path + " → HTTP " + result.status + " " + compact(result.body));
            } catch (Exception error) {
                last = error;
                attempts.add(path + " → " + describeError(error));
            }
        }
        StringBuilder message = new StringBuilder("Ни один endpoint не ответил корректно:");
        for (String attempt : attempts) message.append("\n• ").append(attempt);
        IllegalStateException combined = new IllegalStateException(message.toString());
        if (last != null) combined.initCause(last);
        throw combined;
    }

    private EndpointResult requestJson(String base, String path, String method) throws Exception {
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

    private static InputStream responseStream(HttpURLConnection connection, int status) throws Exception {
        InputStream stream = status >= 200 && status < 300
            ? connection.getInputStream()
            : connection.getErrorStream();
        return stream == null ? new ByteArrayInputStream(new byte[0]) : stream;
    }

    private void renderStatus(EndpointResult result) {
        JSONObject json = result.json;
        boolean ok = optBooleanAny(json, true, "ok", "ready", "healthy");
        connectionBadge.setText(ok ? "СЕРВЕР ОТВЕЧАЕТ" : "СЕРВЕР ОТВЕЧАЕТ, НО НЕ ГОТОВ");
        connectionBadge.setBackgroundColor(
            ok ? Color.rgb(21, 128, 61) : Color.rgb(194, 65, 12)
        );

        String algorithm = optStringAny(json, "—", "algorithm_version", "algorithmVersion", "version");
        String strategy = optStringAny(json, "—", "strategy_version", "strategyVersion", "strategy");
        String configHash = optStringAny(json, "—", "config_hash", "configHash");
        String runId = optStringAny(json, "—", "run_id", "runId");
        long uptime = optLongAny(json, 0L, "uptime_seconds", "uptimeSeconds", "uptime");
        long marketAge = optLongAny(json, -1L, "market_feed_age_ms", "marketFeedAgeMs");
        long candidateAge = optLongAny(json, -1L, "candidate_feed_age_ms", "candidateFeedAgeMs");

        StringBuilder text = new StringBuilder();
        text.append("Версия: ").append(algorithm).append('\n');
        text.append("Стратегия: ").append(strategy).append('\n');
        text.append("Config hash: ").append(configHash).append('\n');
        text.append("Run ID: ").append(runId).append('\n');
        text.append("Работает: ").append(formatDuration(uptime)).append('\n');
        text.append("Рыночный поток: ").append(formatAge(marketAge)).append('\n');
        text.append("Поток кандидатов: ").append(formatAge(candidateAge)).append('\n');
        text.append("Монет: ").append(optIntAny(json, 0, "universe_symbols", "universeSymbols")).append('\n');
        text.append("Анализируется: ").append(optIntAny(json, 0, "evaluated_symbols", "evaluatedSymbols")).append('\n');
        text.append("Снимков: ").append(optIntAny(json, 0, "snapshots", "snapshot_count")).append('\n');
        text.append("Paper-слотов: ").append(optIntAny(json, 0, "slots", "slot_count"));
        summary.setText(text.toString());

        JSONObject slot = firstSlot(json);
        if (slot == null) {
            activeSlot.setText("Активный paper-слот: нет");
        } else {
            String symbol = optStringAny(slot, "—", "symbol", "ticker");
            String channel = optStringAny(slot, "—", "channel", "signal_channel", "signalChannel");
            String side = optStringAny(slot, "—", "side", "direction");
            double entry = optDoubleAny(slot, 0.0, "entry_vwap", "entryVwap", "entry_price");
            double amount = optDoubleAny(slot, 0.0, "margin_usdt", "position_usdt", "marginUsdt", "positionUsdt");
            double mfe = optDoubleAny(slot, 0.0, "max_directional_return_percent", "max_executable_return_percent", "mfe_percent");
            double mae = optDoubleAny(slot, 0.0, "min_directional_return_percent", "min_executable_return_percent", "mae_percent");
            long opened = optLongAny(slot, 0L, "opened_at_ms", "openedAtMs");
            activeSlot.setText(
                "Активный paper-слот\n" +
                "Канал: " + channel + "\n" +
                "Сторона: " + side + "\n" +
                "Монета: " + symbol + "\n" +
                "Вход: " + formatNumber(entry) + "\n" +
                "Маржа/размер: " + String.format(Locale.US, "%.2f USDT", amount) + "\n" +
                "Открыт: " + formatTime(opened) + "\n" +
                "MFE / MAE: " + String.format(Locale.US, "%+.3f%% / %+.3f%%", mfe, mae)
            );
        }

        String keys = json.names() == null ? "—" : json.names().toString();
        latestDiagnostics =
            "PumpRadar Client " + CLIENT_VERSION + "\n" +
            "Время: " + formatTime(System.currentTimeMillis()) + "\n" +
            "Сервер: " + normalizeUrl(serverUrl.getText().toString()) + "\n" +
            "Endpoint: " + result.path + "\n" +
            "HTTP: " + result.status + "\n" +
            "API ok: " + ok + "\n" +
            "Версия: " + algorithm + "\n" +
            "Стратегия: " + strategy + "\n" +
            "Config hash: " + configHash + "\n" +
            "JSON keys: " + keys;
        diagnostics.setText(latestDiagnostics);
        lastUpdate.setText(
            "Обновлено: " + formatTime(System.currentTimeMillis()) +
            " · автообновление каждые 15 секунд"
        );
    }

    private void renderError(Exception error) {
        connectionBadge.setText("СЕРВЕР НЕ ОТВЕЧАЕТ");
        connectionBadge.setBackgroundColor(Color.rgb(185, 28, 28));
        String description = describeError(error);
        summary.setText(
            "Не удалось получить состояние сервера.\n\n" + description +
            "\n\nПроверь службу pumpradar, nginx/HTTPS gateway и порт 443."
        );
        activeSlot.setText("Активный paper-слот: данные недоступны");
        latestDiagnostics =
            "PumpRadar Client " + CLIENT_VERSION + "\n" +
            "Время: " + formatTime(System.currentTimeMillis()) + "\n" +
            "Сервер: " + normalizeUrl(serverUrl.getText().toString()) + "\n" +
            "Ошибка: " + description;
        diagnostics.setText(latestDiagnostics);
        lastUpdate.setText("Последняя попытка: " + formatTime(System.currentTimeMillis()));
    }

    private DownloadResult downloadVerified(String target, SnapshotMetadata metadata) throws Exception {
        HttpURLConnection connection = openConnection(target, "GET");
        connection.setRequestProperty("Accept", "application/gzip, application/octet-stream");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            String body = readAll(connection.getErrorStream());
            connection.disconnect();
            throw new IllegalStateException("HTTP " + status + ": " + compact(body));
        }

        String fileName = "pumpradar-" + metadata.exportedAtMs + "-" +
            metadata.algorithmVersion.replaceAll("[^A-Za-z0-9._-]", "_") + ".sqlite3.gz";
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long[] byteCount = {0L};
        String location;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/gzip");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PumpRadar");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Android не создал файл в Downloads");
            try {
                try (InputStream input = connection.getInputStream(); OutputStream output = resolver.openOutputStream(uri, "w")) {
                    if (output == null) throw new IllegalStateException("Не удалось открыть файл назначения");
                    copyAndDigest(input, output, digest, byteCount);
                }
                verifyDownloaded(metadata, byteCount[0], hex(digest.digest()));
                ContentValues complete = new ContentValues();
                complete.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, complete, null, null);
                location = "Downloads/PumpRadar/" + fileName;
            } catch (Exception error) {
                resolver.delete(uri, null, null);
                throw error;
            } finally {
                connection.disconnect();
            }
        } else {
            File directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (directory == null) throw new IllegalStateException("Android не предоставил каталог Downloads");
            File pumpRadarDir = new File(directory, "PumpRadar");
            if (!pumpRadarDir.exists() && !pumpRadarDir.mkdirs()) {
                throw new IllegalStateException("Не удалось создать каталог PumpRadar");
            }
            File targetFile = new File(pumpRadarDir, fileName);
            try {
                try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(targetFile)) {
                    copyAndDigest(input, output, digest, byteCount);
                }
                verifyDownloaded(metadata, byteCount[0], hex(digest.digest()));
                location = targetFile.getAbsolutePath();
            } catch (Exception error) {
                targetFile.delete();
                throw error;
            } finally {
                connection.disconnect();
            }
        }
        return new DownloadResult(byteCount[0], metadata.expectedSha256, location);
    }

    private static void copyAndDigest(InputStream input, OutputStream output, MessageDigest digest, long[] byteCount) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            output.write(buffer, 0, read);
            digest.update(buffer, 0, read);
            byteCount[0] += read;
        }
        output.flush();
    }

    private static void verifyDownloaded(SnapshotMetadata metadata, long bytes, String sha256) {
        if (bytes != metadata.expectedBytes) {
            throw new IllegalStateException("Размер не совпал: ожидалось " + metadata.expectedBytes + ", получено " + bytes);
        }
        if (!sha256.equalsIgnoreCase(metadata.expectedSha256)) {
            throw new IllegalStateException("SHA-256 не совпал. Файл удалён.");
        }
    }

    private static JSONObject firstSlot(JSONObject json) {
        String[] objectKeys = {"active_regime_slot", "active_slot", "open_slot"};
        for (String key : objectKeys) {
            JSONObject slot = json.optJSONObject(key);
            if (slot != null) return slot;
        }
        String[] arrayKeys = {"active_regime_slots", "active_slots", "open_slots", "regime_open_slots"};
        for (String key : arrayKeys) {
            JSONArray slots = json.optJSONArray(key);
            if (slots != null && slots.length() > 0) {
                JSONObject slot = slots.optJSONObject(0);
                if (slot != null) return slot;
            }
        }
        return null;
    }

    private static String[] appendNonce(String[] paths, long nonce) {
        String[] result = new String[paths.length];
        for (int i = 0; i < paths.length; i++) {
            result[i] = paths[i] + "?fresh=" + nonce;
        }
        return result;
    }

    private static String pathOnly(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (read == 0) continue;
                out.append(buffer, 0, read);
                if (out.length() > 256_000) break;
            }
        }
        return out.toString();
    }

    private static String describeError(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        if (current instanceof ConnectException) {
            return "Соединение отклонено: на порту 443 никто не отвечает. Вероятно, nginx или VPS-служба остановлена.";
        }
        if (current instanceof SocketTimeoutException) {
            return "Таймаут: сервер принял соединение, но не ответил вовремя.";
        }
        if (current instanceof SSLException) {
            return "Ошибка SSL/TLS: " + emptyDash(message) + ". Проверь сертификат HTTPS gateway.";
        }
        if (error.getMessage() != null && error.getMessage().contains("Ни один endpoint")) {
            return error.getMessage();
        }
        return current.getClass().getSimpleName() + ": " + emptyDash(message);
    }

    private static String compact(String value) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 240 ? compact : compact.substring(0, 240) + "…";
    }

    private String normalizeUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) value = DEFAULT_URL;
        if (!value.startsWith("https://")) {
            value = value.replaceFirst("^http://", "");
            value = "https://" + value;
        }
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String optStringAny(JSONObject json, String fallback, String... keys) {
        for (String key : keys) {
            if (json.has(key) && !json.isNull(key)) {
                String value = json.optString(key, "");
                if (!value.trim().isEmpty()) return value;
            }
        }
        return fallback;
    }

    private static long optLongAny(JSONObject json, long fallback, String... keys) {
        for (String key : keys) if (json.has(key) && !json.isNull(key)) return json.optLong(key, fallback);
        return fallback;
    }

    private static int optIntAny(JSONObject json, int fallback, String... keys) {
        for (String key : keys) if (json.has(key) && !json.isNull(key)) return json.optInt(key, fallback);
        return fallback;
    }

    private static double optDoubleAny(JSONObject json, double fallback, String... keys) {
        for (String key : keys) if (json.has(key) && !json.isNull(key)) return json.optDouble(key, fallback);
        return fallback;
    }

    private static boolean optBooleanAny(JSONObject json, boolean fallback, String... keys) {
        for (String key : keys) if (json.has(key) && !json.isNull(key)) return json.optBoolean(key, fallback);
        return fallback;
    }

    private static String formatDuration(long seconds) {
        if (seconds <= 0) return "—";
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        if (days > 0) return days + "д " + hours + "ч " + minutes + "м";
        if (hours > 0) return hours + "ч " + minutes + "м";
        return minutes + "м " + (seconds % 60) + "с";
    }

    private static String formatAge(long millis) {
        if (millis < 0) return "нет данных";
        if (millis < 1_000) return millis + " мс назад";
        return String.format(Locale.US, "%.1f с назад", millis / 1000.0);
    }

    private static String formatNumber(double value) {
        if (value == 0.0) return "—";
        return String.format(Locale.US, value >= 1.0 ? "%.6f" : "%.10f", value);
    }

    private static String formatTime(long epochMillis) {
        if (epochMillis <= 0L) return "—";
        DateFormat format = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault());
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(epochMillis));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024) return bytes + " B";
        double kib = bytes / 1024.0;
        if (kib < 1024) return String.format(Locale.US, "%.1f KiB", kib);
        double mib = kib / 1024.0;
        if (mib < 1024) return String.format(Locale.US, "%.1f MiB", mib);
        return String.format(Locale.US, "%.2f GiB", mib / 1024.0);
    }

    private static String emptyDash(String value) {
        return value == null || value.trim().isEmpty() ? "—" : value;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.US, "%02x", value & 0xff));
        return out.toString();
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private TextView cardText(String value) {
        TextView view = text(value, 15, false);
        view.setTextColor(Color.rgb(30, 30, 30));
        view.setBackgroundColor(Color.WHITE);
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams margins(int top) {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = top;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class EndpointResult {
        final String path;
        final int status;
        final String body;
        final JSONObject json;

        EndpointResult(String path, int status, String body, JSONObject json) {
            this.path = path;
            this.status = status;
            this.body = body;
            this.json = json;
        }
    }

    private static final class SnapshotMetadata {
        final long exportedAtMs;
        final String runId;
        final String algorithmVersion;
        final String configHash;
        final long expectedBytes;
        final String expectedSha256;

        SnapshotMetadata(long exportedAtMs, String runId, String algorithmVersion, String configHash, long expectedBytes, String expectedSha256) {
            this.exportedAtMs = exportedAtMs;
            this.runId = runId;
            this.algorithmVersion = algorithmVersion;
            this.configHash = configHash;
            this.expectedBytes = expectedBytes;
            this.expectedSha256 = expectedSha256;
        }

        static SnapshotMetadata from(JSONObject exportResult, JSONObject manifest) {
            long exportedAtMs = optLongAny(manifest, 0L, "exported_at_ms", "exportedAtMs");
            if (exportedAtMs <= 0L) throw new IllegalStateException("Manifest не содержит exported_at_ms");
            JSONObject files = manifest.optJSONObject("files");
            JSONObject database = files == null ? null : files.optJSONObject("pumpradar.sqlite3.gz");
            if (database == null) throw new IllegalStateException("Manifest не содержит pumpradar.sqlite3.gz");
            String sha256 = optStringAny(database, "", "sha256", "sha_256");
            long bytes = optLongAny(database, -1L, "bytes", "size");
            if (sha256.length() != 64 || bytes <= 0L) {
                throw new IllegalStateException("Manifest содержит неполные данные файла");
            }
            String exportDir = optStringAny(exportResult, "", "export_dir", "exportDir", "path");
            if (exportDir.isEmpty()) throw new IllegalStateException("Сервер не подтвердил создание export");
            return new SnapshotMetadata(
                exportedAtMs,
                optStringAny(manifest, "", "run_id", "runId"),
                optStringAny(manifest, "unknown", "algorithm_version", "algorithmVersion", "version"),
                optStringAny(manifest, "", "config_hash", "configHash"),
                bytes,
                sha256
            );
        }
    }

    private static final class DownloadResult {
        final long bytes;
        final String sha256;
        final String location;

        DownloadResult(long bytes, String sha256, String location) {
            this.bytes = bytes;
            this.sha256 = sha256;
            this.location = location;
        }
    }
}
