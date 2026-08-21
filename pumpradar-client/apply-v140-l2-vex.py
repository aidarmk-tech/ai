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
    'import android.content.ContentValues;\n',
    'import android.content.ContentValues;\nimport android.database.Cursor;\nimport android.database.sqlite.SQLiteDatabase;\n',
    'sqlite imports',
)
replace_once(
    'import java.io.File;\nimport java.io.FileOutputStream;\n',
    'import java.io.File;\nimport java.io.FileInputStream;\nimport java.io.FileOutputStream;\n',
    'file imports',
)
replace_once(
    'import java.util.concurrent.atomic.AtomicBoolean;\n',
    'import java.util.concurrent.atomic.AtomicBoolean;\nimport java.util.zip.GZIPInputStream;\n',
    'gzip import',
)
replace_once(
    '    private static final String CLIENT_VERSION = "1.3.2";\n',
    '    private static final String CLIENT_VERSION = "1.4.0";\n',
    'client version',
)
replace_once(
    '    private TextView metricsStatus;\n    private TextView diagnostics;\n',
    '    private TextView metricsStatus;\n    private TextView researchLabStatus;\n    private TextView diagnostics;\n',
    'research status field',
)
replace_once(
    '        metricsStatus = cardText("Каналы / outcomes: проверка…");\n        root.addView(metricsStatus, margins(gap));\n\n',
    '        metricsStatus = cardText("Каналы / outcomes: проверка…");\n        root.addView(metricsStatus, margins(gap));\n\n'
    '        researchLabStatus = cardText("Research Lab: скачай свежий snapshot для Control / H05 / VEX / L2.");\n'
    '        root.addView(researchLabStatus, margins(gap));\n\n',
    'research card',
)
replace_once(
    '                DownloadResult result = downloadVerified(target, metadata);\n                runOnUiThread(() -> {\n',
    '                DownloadResult result = downloadVerified(target, metadata);\n'
    '                String researchSummary;\n'
    '                try {\n'
    '                    researchSummary = analyzeResearchSnapshot(result.cacheFile);\n'
    '                } catch (Exception researchError) {\n'
    '                    researchSummary = "Research Lab: snapshot проверен, но локальный анализ не выполнен: " + describeError(researchError);\n'
    '                }\n'
    '                final String finalResearchSummary = researchSummary;\n'
    '                runOnUiThread(() -> {\n'
    '                    researchLabStatus.setText(finalResearchSummary);\n',
    'snapshot research analysis',
)
replace_once(
    '        metricsStatus.setText("Каналы / outcomes: данные недоступны");\n',
    '        metricsStatus.setText("Каналы / outcomes: данные недоступны");\n'
    '        researchLabStatus.setText("Research Lab: live /healthz недоступен; последний скачанный snapshot не изменён.");\n',
    'research error state',
)

start = text.index('    private DownloadResult downloadVerified(String target, SnapshotMetadata metadata) throws Exception {')
end = text.index('    private static void copyAndDigest(', start)
new_download = r'''    private DownloadResult downloadVerified(String target, SnapshotMetadata metadata) throws Exception {
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
        File cacheFile = new File(getCacheDir(), "latest-pumpradar.sqlite3.gz");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long[] byteCount = {0L};

        try {
            try (
                InputStream input = connection.getInputStream();
                OutputStream output = new FileOutputStream(cacheFile)
            ) {
                copyAndDigest(input, output, digest, byteCount);
            }
        } finally {
            connection.disconnect();
        }
        String actualSha = hex(digest.digest());
        try {
            verifyDownloaded(metadata, byteCount[0], actualSha);
        } catch (Exception error) {
            cacheFile.delete();
            throw error;
        }

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
                try (
                    InputStream input = new FileInputStream(cacheFile);
                    OutputStream output = resolver.openOutputStream(uri, "w")
                ) {
                    if (output == null) throw new IllegalStateException("Не удалось открыть файл назначения");
                    copyStream(input, output);
                }
                ContentValues complete = new ContentValues();
                complete.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, complete, null, null);
                location = "Downloads/PumpRadar/" + fileName;
            } catch (Exception error) {
                resolver.delete(uri, null, null);
                throw error;
            }
        } else {
            File directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (directory == null) throw new IllegalStateException("Android не предоставил каталог Downloads");
            File pumpRadarDir = new File(directory, "PumpRadar");
            if (!pumpRadarDir.exists() && !pumpRadarDir.mkdirs()) {
                throw new IllegalStateException("Не удалось создать каталог PumpRadar");
            }
            File targetFile = new File(pumpRadarDir, fileName);
            try (
                InputStream input = new FileInputStream(cacheFile);
                OutputStream output = new FileOutputStream(targetFile)
            ) {
                copyStream(input, output);
            }
            location = targetFile.getAbsolutePath();
        }
        return new DownloadResult(byteCount[0], actualSha, location, cacheFile);
    }

    private static void copyStream(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    private String analyzeResearchSnapshot(File gzipFile) throws Exception {
        File dbFile = new File(getCacheDir(), "latest-pumpradar-research.sqlite3");
        if (dbFile.exists() && !dbFile.delete()) {
            throw new IllegalStateException("Не удалось заменить локальную research SQLite");
        }
        try (
            InputStream input = new GZIPInputStream(new FileInputStream(gzipFile));
            OutputStream output = new FileOutputStream(dbFile)
        ) {
            copyStream(input, output);
        }

        SQLiteDatabase db = SQLiteDatabase.openDatabase(
            dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY
        );
        try {
            StringBuilder out = new StringBuilder("Research Lab · один snapshot\n");

            if (tableExists(db, "research_h05_challenger")) {
                try (Cursor c = db.rawQuery(
                    "SELECT COUNT(*)," +
                    "COALESCE(SUM(active_net_pnl_usdt),0)," +
                    "COALESCE(SUM(h05_net_pnl_usdt),0)," +
                    "COALESCE(SUM(paired_delta_usdt),0)," +
                    "SUM(CASE WHEN decision='PASS' THEN 1 ELSE 0 END)," +
                    "SUM(CASE WHEN decision='SKIP' THEN 1 ELSE 0 END) " +
                    "FROM research_h05_challenger WHERE outcome_status='PAIRED'", null
                )) {
                    if (c.moveToFirst()) {
                        out.append("H05 prospective: N=").append(c.getInt(0))
                            .append(" · PASS=").append(c.getInt(4))
                            .append(" · SKIP=").append(c.getInt(5))
                            .append("\nActive ").append(money(c.getDouble(1)))
                            .append(" · H05 ").append(money(c.getDouble(2)))
                            .append(" · delta ").append(money(c.getDouble(3))).append('\n');
                    }
                }
            } else {
                out.append("H05: таблица отсутствует\n");
            }

            if (tableExists(db, "research_vex_portfolios")) {
                try (Cursor c = db.rawQuery(
                    "SELECT portfolio,equity_usdt,closed_trades,wins,net_pnl_usdt,max_drawdown_pct,active_event_id " +
                    "FROM research_vex_portfolios ORDER BY leverage", null
                )) {
                    while (c.moveToNext()) {
                        out.append(c.getString(0)).append(": equity ").append(money(c.getDouble(1)))
                            .append(" · trades ").append(c.getInt(2))
                            .append(" · wins ").append(c.getInt(3))
                            .append(" · PnL ").append(money(c.getDouble(4)))
                            .append(" · DD ").append(String.format(Locale.US, "%+.2f%%", c.getDouble(5)));
                        if (!c.isNull(6)) out.append(" · OPEN");
                        out.append('\n');
                    }
                }
                try (Cursor c = db.rawQuery(
                    "SELECT status,COUNT(*) FROM research_vex_events GROUP BY status ORDER BY status", null
                )) {
                    out.append("VEX events: ");
                    boolean first = true;
                    while (c.moveToNext()) {
                        if (!first) out.append(" · ");
                        out.append(c.getString(0)).append('=').append(c.getInt(1));
                        first = false;
                    }
                    if (first) out.append("0");
                    out.append('\n');
                }
            } else {
                out.append("VEX: ещё не установлен\n");
            }

            if (tableExists(db, "research_l2_state_current")) {
                try (Cursor c = db.rawQuery(
                    "SELECT state,COUNT(*) FROM research_l2_state_current GROUP BY state ORDER BY COUNT(*) DESC,state", null
                )) {
                    out.append("L2 states: ");
                    boolean first = true;
                    while (c.moveToNext()) {
                        if (!first) out.append(" · ");
                        out.append(c.getString(0)).append('=').append(c.getInt(1));
                        first = false;
                    }
                    if (first) out.append("warming up");
                }
            } else {
                out.append("L2: ещё не установлен");
            }
            return out.toString().trim();
        } finally {
            db.close();
        }
    }

    private static boolean tableExists(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            new String[] {table}
        )) {
            return c.moveToFirst();
        }
    }

    private static String money(double value) {
        return String.format(Locale.US, "%+.3f USDT", value);
    }

'''
text = text[:start] + new_download + text[end:]

replace_once(
    '    private static final class DownloadResult {\n'
    '        final long bytes;\n'
    '        final String sha256;\n'
    '        final String location;\n\n'
    '        DownloadResult(long bytes, String sha256, String location) {\n'
    '            this.bytes = bytes;\n'
    '            this.sha256 = sha256;\n'
    '            this.location = location;\n'
    '        }\n'
    '    }\n',
    '    private static final class DownloadResult {\n'
    '        final long bytes;\n'
    '        final String sha256;\n'
    '        final String location;\n'
    '        final File cacheFile;\n\n'
    '        DownloadResult(long bytes, String sha256, String location, File cacheFile) {\n'
    '            this.bytes = bytes;\n'
    '            this.sha256 = sha256;\n'
    '            this.location = location;\n'
    '            this.cacheFile = cacheFile;\n'
    '        }\n'
    '    }\n',
    'download result cache',
)

replace_once(
    '            "Клиент " + CLIENT_VERSION + ": /healthz для 4.9.2, старые API как fallback, REGIME/feeds/каналы/outcomes и проверенный SQLite snapshot.",\n',
    '            "Клиент " + CLIENT_VERSION + ": один read-only экран для Control/H05/VEX/L2; /healthz live + проверенный SQLite snapshot.",\n',
    'subtitle',
)

path.write_text(text, encoding="utf-8")
print("Applied PumpRadar Client 1.4.0 Control/H05/VEX/L2 snapshot lab")
