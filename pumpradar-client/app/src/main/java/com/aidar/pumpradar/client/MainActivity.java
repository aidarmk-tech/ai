package com.aidar.pumpradar.client;

import android.app.Activity;
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
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

public final class MainActivity extends Activity {
    private static final String DEFAULT_URL = "https://45.150.37.187";
    private static final long REFRESH_MS = 15_000L;
    private static final String CLIENT_VERSION = "1.1.0";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final AtomicBoolean snapshotDownloading = new AtomicBoolean(false);

    private EditText serverUrl;
    private TextView connectionBadge;
    private TextView summary;
    private TextView activeSlot;
    private TextView researchStatus;
    private TextView snapshotStatus;
    private TextView lastUpdate;
    private Button refreshButton;
    private Button freshSnapshotButton;
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
        root.setBackgroundColor(Color.rgb(248, 248, 248));

        TextView title = text("PumpRadar Server", 26, true);
        title.setTextColor(Color.rgb(30, 30, 30));
        root.addView(title);

        TextView subtitle = text(
            "Клиент " + CLIENT_VERSION + ". Анализ выполняется на VPS; телефон показывает состояние и проверяет свежие snapshots.",
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

        TextView serverLabel = text("Адрес сервера", 14, true);
        root.addView(serverLabel, margins(gap));

        serverUrl = new EditText(this);
        serverUrl.setSingleLine(true);
        serverUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverUrl.setText(preferences.getString("server_url", DEFAULT_URL));
        serverUrl.setSelectAllOnFocus(false);
        root.addView(serverUrl, fullWidth());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        Button saveButton = button("Сохранить адрес");
        saveButton.setOnClickListener(v -> {
            String normalized = normalizeUrl(serverUrl.getText().toString());
            serverUrl.setText(normalized);
            preferences.edit().putString("server_url", normalized).apply();
            refreshStatus(true);
        });
        actions.addView(saveButton, weighted());

        refreshButton = button("Обновить");
        refreshButton.setOnClickListener(v -> refreshStatus(true));
        LinearLayout.LayoutParams refreshParams = weighted();
        refreshParams.setMarginStart(gap);
        actions.addView(refreshButton, refreshParams);
        root.addView(actions, margins(gap));

        summary = cardText("Ожидание данных…");
        root.addView(summary, margins(gap));

        activeSlot = cardText("Активный paper-слот: проверка…");
        root.addView(activeSlot, margins(gap));

        researchStatus = cardText("Recorder/SHORT research: проверка…");
        root.addView(researchStatus, margins(gap));

        TextView snapshotTitle = text("Свежий snapshot базы", 17, true);
        root.addView(snapshotTitle, margins(gap));

        freshSnapshotButton = button("Создать, скачать и проверить SQLite.GZ");
        freshSnapshotButton.setOnClickListener(v -> requestFreshSnapshot());
        root.addView(freshSnapshotButton, margins(dp(6)));

        snapshotStatus = cardText(
            "Кнопка сначала запускает новый SQLite backup на сервере, затем скачивает manifest и базу без кэша, проверяет размер и SHA-256."
        );
        root.addView(snapshotStatus, margins(dp(6)));

        TextView downloadTitle = text("Файлы последнего экспорта", 17, true);
        root.addView(downloadTitle, margins(gap));

        root.addView(downloadButton("Манифест JSON", "manifest.json"), margins(dp(6)));
        root.addView(downloadButton("Paper-сделки CSV.GZ", "paper_slots.csv.gz"), margins(dp(6)));
        root.addView(downloadButton("Политики выходов CSV.GZ", "policy_runs.csv.gz"), margins(dp(6)));
        root.addView(downloadButton("Снимки рынка CSV.GZ", "snapshots.csv.gz"), margins(dp(6)));

        lastUpdate = text("", 12, false);
        lastUpdate.setTextColor(Color.GRAY);
        root.addView(lastUpdate, margins(gap));

        TextView note = text(
            "Клиент не содержит Binance API-ключей, не выставляет ордера и не запускает сканирование на телефоне.",
            12,
            false
        );
        note.setTextColor(Color.DKGRAY);
        root.addView(note, margins(gap));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private Button downloadButton(String label, String fileName) {
        Button button = button(label);
        button.setOnClickListener(v -> {
            String base = normalizeUrl(serverUrl.getText().toString());
            long nonce = System.currentTimeMillis();
            Uri uri = Uri.parse(base + "/api/export/latest/" + fileName + "?fresh=" + nonce);
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception error) {
                Toast.makeText(
                    this,
                    "Не удалось открыть загрузку: " + safeMessage(error),
                    Toast.LENGTH_LONG
                ).show();
            }
        });
        return button;
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
                JSONObject json = getJson(base + "/api/status");
                runOnUiThread(() -> renderStatus(json));
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
        freshSnapshotButton.setEnabled(false);
        snapshotStatus.setText("Создание нового backup на сервере…");

        executor.execute(() -> {
            try {
                JSONObject exportResult = postJson(base + "/api/export");
                long nonce = System.currentTimeMillis();
                JSONObject manifest = getJson(
                    base + "/api/export/latest/manifest.json?fresh=" + nonce
                );
                SnapshotMetadata metadata = SnapshotMetadata.from(exportResult, manifest);
                runOnUiThread(() -> snapshotStatus.setText(
                    "Snapshot создан: " + formatTime(metadata.exportedAtMs) + "\n" +
                    "Версия: " + metadata.algorithmVersion + "\n" +
                    "Config hash: " + metadata.configHash + "\n" +
                    "Размер: " + formatBytes(metadata.expectedBytes) + "\n" +
                    "SHA-256: " + metadata.expectedSha256 + "\n\n" +
                    "Скачивание без кэша…"
                ));

                String downloadUrl =
                    base + "/api/export/latest/pumpradar.sqlite3.gz?fresh=" +
                    metadata.exportedAtMs + "-" + nonce;
                DownloadResult result = downloadVerified(downloadUrl, metadata);

                runOnUiThread(() -> {
                    snapshotStatus.setText(
                        "✅ Свежая база скачана и проверена\n" +
                        "Создана: " + formatTime(metadata.exportedAtMs) + "\n" +
                        "Версия: " + metadata.algorithmVersion + "\n" +
                        "Run ID: " + emptyDash(metadata.runId) + "\n" +
                        "Размер: " + formatBytes(result.bytes) + "\n" +
                        "SHA-256 совпал: " + result.sha256 + "\n" +
                        "Сохранено: " + result.location
                    );
                    Toast.makeText(this, "Snapshot проверен и сохранён", Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> snapshotStatus.setText(
                    "❌ Не удалось создать или проверить snapshot\n\n" +
                    safeMessage(error) +
                    "\n\nСтарая база не считается свежей и не помечается успешной."
                ));
            } finally {
                snapshotDownloading.set(false);
                runOnUiThread(() -> freshSnapshotButton.setEnabled(true));
            }
        });
    }

    private JSONObject getJson(String target) throws Exception {
        HttpURLConnection connection = openConnection(target, "GET");
        connection.setRequestProperty("Accept", "application/json");
        int status = connection.getResponseCode();
        String body = readAll(responseStream(connection, status));
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP " + status + ": " + body);
        }
        return new JSONObject(body);
    }

    private JSONObject postJson(String target) throws Exception {
        HttpURLConnection connection = openConnection(target, "POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(0);
        connection.connect();
        try (OutputStream output = connection.getOutputStream()) {
            output.flush();
        }
        int status = connection.getResponseCode();
        String body = readAll(responseStream(connection, status));
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP " + status + ": " + body);
        }
        return new JSONObject(body);
    }

    private HttpURLConnection openConnection(String target, String method) throws Exception {
        URL url = new URL(target);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (!(connection instanceof HttpsURLConnection)) {
            throw new IllegalStateException("Разрешён только HTTPS");
        }
        connection.setUseCaches(false);
        connection.setDefaultUseCaches(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(120_000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("User-Agent", "PumpRadar-Server-Client/" + CLIENT_VERSION);
        connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
        connection.setRequestProperty("Pragma", "no-cache");
        connection.setRequestProperty("Expires", "0");
        return connection;
    }

    private static InputStream responseStream(HttpURLConnection connection, int status) throws Exception {
        return status >= 200 && status < 300
            ? connection.getInputStream()
            : connection.getErrorStream();
    }

    private DownloadResult downloadVerified(String target, SnapshotMetadata metadata) throws Exception {
        HttpURLConnection connection = openConnection(target, "GET");
        connection.setRequestProperty("Accept", "application/gzip, application/octet-stream");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            String body = readAll(connection.getErrorStream());
            connection.disconnect();
            throw new IllegalStateException("HTTP " + status + ": " + body);
        }

        String fileName =
            "pumpradar-" + metadata.exportedAtMs + "-" +
            metadata.algorithmVersion.replaceAll("[^A-Za-z0-9._-]", "_") +
            ".sqlite3.gz";
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long[] byteCount = new long[] {0L};
        String location;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/gzip");
            values.put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/PumpRadar"
            );
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                connection.disconnect();
                throw new IllegalStateException("Android не создал файл в Downloads");
            }
            try {
                try (
                    InputStream input = connection.getInputStream();
                    OutputStream output = resolver.openOutputStream(uri, "w")
                ) {
                    if (output == null) {
                        throw new IllegalStateException("Не удалось открыть файл назначения");
                    }
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
            if (directory == null) {
                connection.disconnect();
                throw new IllegalStateException("Android не предоставил каталог Downloads");
            }
            File pumpRadarDir = new File(directory, "PumpRadar");
            if (!pumpRadarDir.exists() && !pumpRadarDir.mkdirs()) {
                connection.disconnect();
                throw new IllegalStateException("Не удалось создать каталог PumpRadar");
            }
            File file = new File(pumpRadarDir, fileName);
            try {
                try (
                    InputStream input = connection.getInputStream();
                    OutputStream output = new FileOutputStream(file)
                ) {
                    copyAndDigest(input, output, digest, byteCount);
                }
                verifyDownloaded(metadata, byteCount[0], hex(digest.digest()));
                location = file.getAbsolutePath();
            } catch (Exception error) {
                if (file.exists()) file.delete();
                throw error;
            } finally {
                connection.disconnect();
            }
        }

        return new DownloadResult(
            byteCount[0],
            metadata.expectedSha256.toLowerCase(Locale.US),
            location
        );
    }

    private static void copyAndDigest(
        InputStream input,
        OutputStream output,
        MessageDigest digest,
        long[] byteCount
    ) throws Exception {
        byte[] buffer = new byte[128 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            digest.update(buffer, 0, read);
            byteCount[0] += read;
        }
        output.flush();
    }

    private static void verifyDownloaded(
        SnapshotMetadata metadata,
        long actualBytes,
        String actualSha256
    ) {
        if (metadata.expectedBytes > 0 && actualBytes != metadata.expectedBytes) {
            throw new IllegalStateException(
                "Размер не совпал: ожидалось " + metadata.expectedBytes +
                ", получено " + actualBytes
            );
        }
        if (metadata.expectedSha256 == null || metadata.expectedSha256.length() != 64) {
            throw new IllegalStateException("Manifest не содержит корректный SHA-256");
        }
        if (!actualSha256.equalsIgnoreCase(metadata.expectedSha256)) {
            throw new IllegalStateException("SHA-256 не совпал. Файл удалён как недостоверный.");
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
            )
        ) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private void renderStatus(JSONObject json) {
        boolean ok = json.optBoolean("ok", false);
        connectionBadge.setText(ok ? "СЕРВЕР РАБОТАЕТ" : "ПОТОК ДАННЫХ НЕ ГОТОВ");
        connectionBadge.setBackgroundColor(
            ok ? Color.rgb(21, 128, 61) : Color.rgb(194, 65, 12)
        );

        long uptime = json.optLong("uptime_seconds", 0L);
        long marketAge = nullableLong(json, "market_feed_age_ms");
        long candidateAge = nullableLong(json, "candidate_feed_age_ms");

        StringBuilder text = new StringBuilder();
        text.append("Версия: ").append(json.optString("algorithm_version", "—")).append('\n');
        text.append("Стратегия: ").append(json.optString("strategy_version", "—")).append('\n');
        text.append("Config hash: ").append(json.optString("config_hash", "—")).append('\n');
        text.append("Run ID: ").append(json.optString("run_id", "—")).append('\n');
        text.append("Работает: ").append(formatDuration(uptime)).append('\n');
        text.append("Рыночный поток: ").append(formatAge(marketAge)).append('\n');
        text.append("Поток кандидатов: ").append(formatAge(candidateAge)).append('\n');
        text.append("Монет во вселенной: ").append(json.optInt("universe_symbols", 0)).append('\n');
        text.append("Анализируется сейчас: ").append(json.optInt("evaluated_symbols", 0)).append('\n');
        text.append("Снимков в текущем запуске: ").append(json.optInt("snapshots", 0)).append('\n');
        text.append("Paper-слотов: ").append(json.optInt("slots", 0));
        summary.setText(text.toString());

        JSONObject slot = firstSlot(json);
        if (slot == null) {
            activeSlot.setText("Активный paper-слот: нет");
        } else {
            String symbol = slot.optString("symbol", "—");
            String channel = slot.optString("channel", slot.optString("signal_channel", "—"));
            String side = slot.optString("side", "—");
            double entry = slot.optDouble("entry_vwap", 0.0);
            double amount = slot.has("margin_usdt")
                ? slot.optDouble("margin_usdt", 0.0)
                : slot.optDouble("position_usdt", 0.0);
            double mfe = slot.has("max_directional_return_percent")
                ? slot.optDouble("max_directional_return_percent", 0.0)
                : slot.optDouble("max_executable_return_percent", 0.0);
            double mae = slot.has("min_directional_return_percent")
                ? slot.optDouble("min_directional_return_percent", 0.0)
                : slot.optDouble("min_executable_return_percent", 0.0);
            long opened = slot.optLong("opened_at_ms", 0L);
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

        researchStatus.setText(formatResearchStatus(json));
        lastUpdate.setText(
            "Обновлено: " + formatTime(System.currentTimeMillis()) +
            " · автообновление каждые 15 секунд"
        );
    }

    private static JSONObject firstSlot(JSONObject json) {
        JSONObject slot = json.optJSONObject("active_regime_slot");
        if (slot != null) return slot;
        JSONArray slots = json.optJSONArray("active_regime_slots");
        if (slots != null && slots.length() > 0) {
            return slots.optJSONObject(0);
        }
        return json.optJSONObject("active_slot");
    }

    private static String formatResearchStatus(JSONObject json) {
        JSONObject recorder = json.optJSONObject("recorder");
        JSONObject shortResearch = json.optJSONObject("short_research");
        if (recorder == null && shortResearch == null) {
            return "Recorder/SHORT research\nСервер этой версии не передаёт расширенную диагностику.";
        }

        StringBuilder out = new StringBuilder("Recorder/SHORT research");
        if (recorder != null) {
            out.append("\nSchema: ").append(recorder.optString("schema_version", "—"));
            out.append("\nСостояние: ").append(
                recorder.optBoolean("healthy", false)
                    ? "OK"
                    : recorder.optString("status", "не готов")
            );
            appendOptional(out, "Import p95", recorder, "signal_import_delay_p95_ms", " мс");
            appendOptional(out, "Pre-signal coverage", recorder, "pre_signal_coverage_percent", "%");
            appendOptional(out, "Timestamp skew p95", recorder, "timestamp_skew_p95_ms", " мс");
        }
        if (shortResearch != null) {
            appendOptional(out, "SHORT candidates", shortResearch, "candidates", "");
            appendOptional(out, "Complete outcomes", shortResearch, "completed_outcomes", "");
            appendOptional(out, "TARGET1 → stop", shortResearch, "target1_to_stop", "");
            appendOptional(out, "Data-quality blocked", shortResearch, "data_quality_blocked", "");
            out.append("\nChampion: ").append(
                shortResearch.optString("champion", "REV_MC5_SHORT_600_2X")
            );
        }
        return out.toString();
    }

    private static void appendOptional(
        StringBuilder out,
        String label,
        JSONObject source,
        String key,
        String suffix
    ) {
        if (!source.has(key) || source.isNull(key)) return;
        out.append("\n").append(label).append(": ")
            .append(source.optString(key, "—")).append(suffix);
    }

    private void renderError(Exception error) {
        connectionBadge.setText("СЕРВЕР НЕДОСТУПЕН");
        connectionBadge.setBackgroundColor(Color.rgb(185, 28, 28));
        summary.setText(
            "Не удалось подключиться к серверу.\n\n" +
            safeMessage(error) + "\n\n" +
            "Проверь HTTPS gateway и доступность порта 443."
        );
        activeSlot.setText("Активный paper-слот: данные недоступны");
        researchStatus.setText("Recorder/SHORT research: данные недоступны");
        lastUpdate.setText("Последняя попытка: " + formatTime(System.currentTimeMillis()));
    }

    private static long nullableLong(JSONObject json, String key) {
        return json.isNull(key) ? -1L : json.optLong(key, -1L);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }

    private String normalizeUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) value = DEFAULT_URL;
        if (!value.startsWith("https://")) {
            value = value.replaceFirst("^http://", "");
            value = "https://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String formatDuration(long seconds) {
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
        for (byte value : bytes) {
            out.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return out.toString();
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        if (bold) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
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
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
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

    private static final class SnapshotMetadata {
        final long exportedAtMs;
        final String runId;
        final String algorithmVersion;
        final String configHash;
        final long expectedBytes;
        final String expectedSha256;

        SnapshotMetadata(
            long exportedAtMs,
            String runId,
            String algorithmVersion,
            String configHash,
            long expectedBytes,
            String expectedSha256
        ) {
            this.exportedAtMs = exportedAtMs;
            this.runId = runId;
            this.algorithmVersion = algorithmVersion;
            this.configHash = configHash;
            this.expectedBytes = expectedBytes;
            this.expectedSha256 = expectedSha256;
        }

        static SnapshotMetadata from(JSONObject exportResult, JSONObject manifest) {
            long exportedAtMs = manifest.optLong("exported_at_ms", 0L);
            if (exportedAtMs <= 0L) {
                throw new IllegalStateException("Manifest не содержит exported_at_ms");
            }
            JSONObject files = manifest.optJSONObject("files");
            JSONObject database = files == null
                ? null
                : files.optJSONObject("pumpradar.sqlite3.gz");
            if (database == null) {
                throw new IllegalStateException("Manifest не содержит pumpradar.sqlite3.gz");
            }
            String sha256 = database.optString("sha256", "");
            long bytes = database.optLong("bytes", -1L);
            if (sha256.length() != 64 || bytes <= 0L) {
                throw new IllegalStateException("Manifest содержит неполные данные файла");
            }
            String exportDir = exportResult.optString("export_dir", "");
            if (exportDir.isEmpty()) {
                throw new IllegalStateException("Сервер не подтвердил создание нового export");
            }
            return new SnapshotMetadata(
                exportedAtMs,
                manifest.optString("run_id", ""),
                manifest.optString("algorithm_version", "unknown"),
                manifest.optString("config_hash", ""),
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
