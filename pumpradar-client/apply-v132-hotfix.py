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
    '    private static final long MANIFEST_POLL_TIMEOUT_MS = 120_000L;\n'
    '    private static final long MANIFEST_POLL_INTERVAL_MS = 5_000L;\n'
    '    private static final String CLIENT_VERSION = "1.3.1";\n',
    '    private static final long MANIFEST_POLL_TIMEOUT_MS = 600_000L;\n'
    '    private static final long MANIFEST_POLL_INTERVAL_MS = 5_000L;\n'
    '    private static final String CLIENT_VERSION = "1.3.2";\n',
    "version/poll timeout",
)

old_catch = r'''                } catch (SocketTimeoutException timeout) {
                    exportTimedOut = true;
                    runOnUiThread(() -> snapshotStatus.setText(
                        "Сервер дольше 5 минут формирует export. Соединение завершилось по таймауту, " +
                        "но задача на сервере могла продолжиться. Жду свежий manifest…"
                    ));
                }
'''

new_catch = r'''                } catch (Exception exportError) {
                    if (!mayBeExportGatewayTimeout(exportError)) throw exportError;
                    exportTimedOut = true;
                    final String recoveryReason = describeError(exportError);
                    runOnUiThread(() -> snapshotStatus.setText(
                        "Export-запрос завершился таймаутом/ошибкой gateway, но сервер мог продолжить работу.\n" +
                        recoveryReason + "\n\nЖду новый manifest до 10 минут…"
                    ));
                }
'''
replace_once(old_catch, new_catch, "export recovery catch")

marker = '''    private EndpointResult waitForFreshManifest(
'''
helper = r'''    private static boolean mayBeExportGatewayTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException) return true;
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toUpperCase(Locale.US);
                if (normalized.contains("HTTP 502") ||
                    normalized.contains("HTTP 503") ||
                    normalized.contains("HTTP 504") ||
                    normalized.contains("502 BAD GATEWAY") ||
                    normalized.contains("503 SERVICE UNAVAILABLE") ||
                    normalized.contains("504 GATEWAY TIME-OUT") ||
                    normalized.contains("504 GATEWAY TIMEOUT")) {
                    return true;
                }
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

'''
if text.count(marker) != 1:
    raise SystemExit(f"helper marker: expected exactly one match, got {text.count(marker)}")
text = text.replace(marker, helper + marker, 1)

replace_once(
    '            "Свежий manifest не появился в течение 2 минут после ожидания export. " + detail\n',
    '            "Свежий manifest не появился в течение 10 минут после gateway/timeout export. " + detail\n',
    "manifest timeout message",
)

path.write_text(text, encoding="utf-8")
print("Applied PumpRadar Client 1.3.2 gateway-timeout recovery hotfix")
