package com.aidar.pumpradar.client;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean loading = new AtomicBoolean(false);

    private EditText serverUrl;
    private TextView connectionBadge;
    private TextView summary;
    private TextView activeSlot;
    private TextView lastUpdate;
    private Button refreshButton;
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

        TextView subtitle = text("Лёгкий клиент. Анализ выполняется на VPS, телефон только показывает состояние.", 14, false);
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

        TextView downloadTitle = text("Последний экспорт", 17, true);
        root.addView(downloadTitle, margins(gap));

        root.addView(downloadButton("Манифест JSON", "manifest.json"), margins(dp(6)));
        root.addView(downloadButton("Paper-сделки CSV.GZ", "paper_slots.csv.gz"), margins(dp(6)));
        root.addView(downloadButton("Политики выходов CSV.GZ", "policy_runs.csv.gz"), margins(dp(6)));
        root.addView(downloadButton("Снимки рынка CSV.GZ", "snapshots.csv.gz"), margins(dp(6)));
        root.addView(downloadButton("Полная база SQLite.GZ", "pumpradar.sqlite3.gz"), margins(dp(6)));

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
        button.setAllCaps(false);
        button.setOnClickListener(v -> {
            String base = normalizeUrl(serverUrl.getText().toString());
            Uri uri = Uri.parse(base + "/api/export/latest/" + fileName);
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception error) {
                Toast.makeText(this, "Не удалось открыть загрузку: " + error.getMessage(), Toast.LENGTH_LONG).show();
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

    private JSONObject getJson(String target) throws Exception {
        URL url = new URL(target);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (!(connection instanceof HttpsURLConnection)) {
            throw new IllegalStateException("Разрешён только HTTPS");
        }
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "PumpRadar-Server-Client/1.0");
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
            ? connection.getInputStream()
            : connection.getErrorStream();
        String body = readAll(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP " + status + ": " + body);
        }
        return new JSONObject(body);
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private void renderStatus(JSONObject json) {
        boolean ok = json.optBoolean("ok", false);
        connectionBadge.setText(ok ? "СЕРВЕР РАБОТАЕТ" : "ПОТОК ДАННЫХ НЕ ГОТОВ");
        connectionBadge.setBackgroundColor(ok ? Color.rgb(21, 128, 61) : Color.rgb(194, 65, 12));

        long uptime = json.optLong("uptime_seconds", 0L);
        long marketAge = json.isNull("market_feed_age_ms") ? -1L : json.optLong("market_feed_age_ms", -1L);
        long candidateAge = json.isNull("candidate_feed_age_ms") ? -1L : json.optLong("candidate_feed_age_ms", -1L);

        StringBuilder text = new StringBuilder();
        text.append("Версия: ").append(json.optString("algorithm_version", "—")).append('\n');
        text.append("Стратегия: ").append(json.optString("strategy_version", "—")).append('\n');
        text.append("Config hash: ").append(json.optString("config_hash", "—")).append('\n');
        text.append("Работает: ").append(formatDuration(uptime)).append('\n');
        text.append("Рыночный поток: ").append(formatAge(marketAge)).append('\n');
        text.append("Поток кандидатов: ").append(formatAge(candidateAge)).append('\n');
        text.append("Монет во вселенной: ").append(json.optInt("universe_symbols", 0)).append('\n');
        text.append("Анализируется сейчас: ").append(json.optInt("evaluated_symbols", 0)).append('\n');
        text.append("Снимков в текущем запуске: ").append(json.optInt("snapshots", 0)).append('\n');
        text.append("Paper-слотов: ").append(json.optInt("slots", 0));
        summary.setText(text.toString());

        JSONObject slot = json.optJSONObject("active_slot");
        if (slot == null) {
            activeSlot.setText("Активный paper-слот: нет\nСтрогий TRADE_3 пока не открыл позицию.");
        } else {
            String symbol = slot.optString("symbol", "—");
            double entry = slot.optDouble("entry_vwap", 0.0);
            double amount = slot.optDouble("position_usdt", 0.0);
            double mfe = slot.optDouble("max_executable_return_percent", 0.0);
            double mae = slot.optDouble("min_executable_return_percent", 0.0);
            long opened = slot.optLong("opened_at_ms", 0L);
            activeSlot.setText(
                "Активный paper-слот\n" +
                "Монета: " + symbol + "\n" +
                "Вход: " + formatNumber(entry) + "\n" +
                "Размер: " + String.format(Locale.US, "%.2f USDT", amount) + "\n" +
                "Открыт: " + formatTime(opened) + "\n" +
                "MFE / MAE: " + String.format(Locale.US, "%+.3f%% / %+.3f%%", mfe, mae)
            );
        }

        lastUpdate.setText("Обновлено: " + formatTime(System.currentTimeMillis()) + " · автообновление каждые 15 секунд");
    }

    private void renderError(Exception error) {
        connectionBadge.setText("СЕРВЕР НЕДОСТУПЕН");
        connectionBadge.setBackgroundColor(Color.rgb(185, 28, 28));
        summary.setText(
            "Не удалось подключиться к серверу.\n\n" +
            safeMessage(error) + "\n\n" +
            "Проверь, завершена ли настройка HTTPS gateway и открыты ли порты 80/443 в панели VPS."
        );
        activeSlot.setText("Активный paper-слот: данные недоступны");
        lastUpdate.setText("Последняя попытка: " + formatTime(System.currentTimeMillis()));
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
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
        DateFormat format = new SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault());
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(epochMillis));
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
}
