#!/usr/bin/env python3
"""
Прозрачный удалённый мониторинг экрана.

Захватывает экран компьютера и отдаёт видеопоток (MJPEG) на защищённую
паролем веб-страницу, которую можно открыть с телефона или другого
устройства.

Инструмент СПЕЦИАЛЬНО сделан прозрачным (не скрытным):
  * при запуске показывается баннер в консоли;
  * в системном трее висит видимый значок «Мониторинг ВКЛЮЧЕН»;
  * при каждом подключении зрителя пишется запись в лог и (если доступно)
    показывается уведомление на рабочем столе.

Ставьте только на компьютер, которым владеете, и предупреждайте людей,
которые им пользуются, что ведётся наблюдение.
"""

from __future__ import annotations

import argparse
import getpass
import io
import os
import secrets
import socket
import sys
import threading
import time
from datetime import datetime
from functools import wraps

try:
    import mss  # захват экрана
except ImportError:  # pragma: no cover
    print("Не найден модуль 'mss'. Установите зависимости: pip install -r requirements.txt")
    sys.exit(1)

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    print("Не найден модуль 'Pillow'. Установите зависимости: pip install -r requirements.txt")
    sys.exit(1)

from flask import Flask, Response, request, abort, render_template_string

app = Flask(__name__)

# --- Конфигурация (заполняется в main) ---------------------------------------
CONFIG = {
    "password": None,     # пароль для доступа к странице
    "fps": 5,             # частота кадров
    "quality": 60,        # качество JPEG (1..95)
    "monitor": 1,         # номер экрана (1 = основной; 0 = все экраны вместе)
    "scale": 1.0,         # масштаб кадра (0.5 = уменьшить вдвое)
    "no_auth": False,     # True = открыть без пароля (небезопасно, но удобно)
}

# Чтобы не спамить уведомлениями, запоминаем недавно подключавшиеся адреса.
_recent_viewers: dict[str, float] = {}
_viewers_lock = threading.Lock()


# --- Прозрачность: уведомления и лог ------------------------------------------
def log(msg: str) -> None:
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{ts}] {msg}", flush=True)


def desktop_notify(title: str, message: str) -> None:
    """Показать уведомление на рабочем столе (best-effort, кроссплатформенно)."""
    try:
        from plyer import notification  # type: ignore
        notification.notify(title=title, message=message, timeout=5)
        return
    except Exception:
        pass
    # Запасные варианты по платформам
    try:
        if sys.platform == "darwin":
            os.system(
                'osascript -e {!r}'.format(
                    f'display notification "{message}" with title "{title}"'
                )
            )
        elif sys.platform.startswith("linux"):
            os.system(f'notify-send {title!r} {message!r} >/dev/null 2>&1')
    except Exception:
        pass


def note_viewer(remote_addr: str) -> None:
    """Отметить подключение зрителя: лог + уведомление (не чаще раза в 60с на адрес)."""
    now = time.time()
    notify = False
    with _viewers_lock:
        last = _recent_viewers.get(remote_addr, 0)
        if now - last > 60:
            notify = True
        _recent_viewers[remote_addr] = now
    if notify:
        log(f"К мониторингу подключился зритель: {remote_addr}")
        desktop_notify("Мониторинг экрана", f"Подключился зритель: {remote_addr}")


# --- Авторизация --------------------------------------------------------------
def check_auth(pwd: str | None) -> bool:
    expected = CONFIG["password"]
    if not expected:
        return False
    if pwd is None:
        return False
    return secrets.compare_digest(pwd, expected)


def require_auth(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        if CONFIG.get("no_auth"):
            return fn(*args, **kwargs)
        auth = request.authorization
        if not auth or not check_auth(auth.password):
            return Response(
                "Требуется авторизация.",
                401,
                {"WWW-Authenticate": 'Basic realm="Remote Monitor"'},
            )
        return fn(*args, **kwargs)

    return wrapper


# --- Захват экрана ------------------------------------------------------------
def grab_jpeg() -> bytes:
    """Сделать один снимок экрана и вернуть JPEG-байты."""
    with mss.mss() as sct:
        monitors = sct.monitors  # [0]=все экраны, [1..]=отдельные
        idx = CONFIG["monitor"]
        if idx < 0 or idx >= len(monitors):
            idx = 1 if len(monitors) > 1 else 0
        shot = sct.grab(monitors[idx])
        img = Image.frombytes("RGB", shot.size, shot.rgb)

    scale = CONFIG["scale"]
    if scale and scale != 1.0 and 0 < scale < 1.0:
        new_size = (max(1, int(img.width * scale)), max(1, int(img.height * scale)))
        img = img.resize(new_size, Image.BILINEAR)

    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=int(CONFIG["quality"]))
    return buf.getvalue()


def mjpeg_stream():
    frame_interval = 1.0 / max(1, CONFIG["fps"])
    boundary = b"--frame"
    while True:
        start = time.time()
        try:
            frame = grab_jpeg()
        except Exception as exc:  # не роняем поток из-за одного плохого кадра
            log(f"Ошибка захвата экрана: {exc}")
            time.sleep(1)
            continue
        yield (
            boundary + b"\r\n"
            b"Content-Type: image/jpeg\r\n"
            b"Content-Length: " + str(len(frame)).encode() + b"\r\n\r\n"
            + frame + b"\r\n"
        )
        elapsed = time.time() - start
        sleep_for = frame_interval - elapsed
        if sleep_for > 0:
            time.sleep(sleep_for)


# --- Маршруты -----------------------------------------------------------------
PAGE = """
<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Мониторинг экрана</title>
  <style>
    :root { color-scheme: dark; }
    body { margin: 0; background: #0b0b0f; color: #e6e6e6;
           font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; }
    header { display: flex; align-items: center; gap: 10px;
             padding: 10px 14px; background: #14141c; border-bottom: 1px solid #23232f; }
    .dot { width: 10px; height: 10px; border-radius: 50%; background: #23c552;
           box-shadow: 0 0 8px #23c552; animation: pulse 2s infinite; }
    @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.4} }
    h1 { font-size: 15px; margin: 0; font-weight: 600; }
    .meta { margin-left: auto; font-size: 12px; color: #8a8a99; }
    .wrap { padding: 12px; }
    img { max-width: 100%; height: auto; display: block; margin: 0 auto;
          border-radius: 8px; border: 1px solid #23232f; }
  </style>
</head>
<body>
  <header>
    <span class="dot"></span>
    <h1>Мониторинг экрана — прямой эфир</h1>
    <span class="meta">{{ host }}</span>
  </header>
  <div class="wrap">
    <img src="/stream" alt="Экран">
  </div>
</body>
</html>
"""


@app.route("/")
@require_auth
def index():
    note_viewer(request.remote_addr or "неизвестно")
    return render_template_string(PAGE, host=socket.gethostname())


@app.route("/stream")
@require_auth
def stream():
    note_viewer(request.remote_addr or "неизвестно")
    return Response(
        mjpeg_stream(),
        mimetype="multipart/x-mixed-replace; boundary=frame",
    )


@app.route("/healthz")
def healthz():
    return "ok", 200


# --- Значок в трее (видимый индикатор) ----------------------------------------
def start_tray_icon(url: str) -> None:
    """Запустить видимый значок в системном трее. Тихо пропускаем, если недоступно."""
    try:
        import pystray  # type: ignore
        from PIL import Image as PILImage, ImageDraw
    except Exception:
        log("Значок в трее недоступен (нет pystray). Индикатор — только в консоли и уведомлениях.")
        return

    def make_image():
        img = PILImage.new("RGB", (64, 64), (11, 11, 15))
        d = ImageDraw.Draw(img)
        d.ellipse((16, 16, 48, 48), fill=(35, 197, 82))  # зелёная точка = «идёт запись»
        return img

    def on_quit(icon, item):
        log("Мониторинг остановлен из трея.")
        icon.stop()
        os._exit(0)

    icon = pystray.Icon(
        "remote-monitor",
        make_image(),
        "Мониторинг экрана ВКЛЮЧЕН",
        menu=pystray.Menu(
            pystray.MenuItem(lambda item: f"Открыть: {url}", lambda icon, item: None, enabled=False),
            pystray.MenuItem("Остановить мониторинг", on_quit),
        ),
    )
    threading.Thread(target=icon.run, daemon=True).start()
    log("Значок в трее запущен (зелёная точка = мониторинг активен).")


# --- Точка входа --------------------------------------------------------------
def local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def main() -> None:
    parser = argparse.ArgumentParser(description="Прозрачный удалённый мониторинг экрана.")
    parser.add_argument("--host", default="0.0.0.0", help="Адрес для прослушивания (по умолчанию 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8000, help="Порт (по умолчанию 8000)")
    parser.add_argument("--password", default=os.environ.get("MONITOR_PASSWORD"),
                        help="Пароль доступа. Лучше задать через переменную окружения MONITOR_PASSWORD.")
    parser.add_argument("--fps", type=int, default=5, help="Кадров в секунду (по умолчанию 5)")
    parser.add_argument("--quality", type=int, default=60, help="Качество JPEG 1..95 (по умолчанию 60)")
    parser.add_argument("--monitor", type=int, default=1,
                        help="Номер экрана: 1 — основной, 0 — все экраны вместе (по умолчанию 1)")
    parser.add_argument("--scale", type=float, default=1.0,
                        help="Масштаб кадра, напр. 0.5 — вдвое меньше (по умолчанию 1.0)")
    parser.add_argument("--no-tray", action="store_true", help="Не показывать значок в трее")
    parser.add_argument("--no-auth", action="store_true",
                        help="Открыть БЕЗ ПАРОЛЯ (удобно, но небезопасно для публичной ссылки)")
    args = parser.parse_args()

    no_auth = args.no_auth or os.environ.get("MONITOR_NO_AUTH") == "1"

    password = args.password
    if not no_auth:
        if not password:
            try:
                password = getpass.getpass("Задайте пароль для доступа к мониторингу: ")
            except (EOFError, KeyboardInterrupt):
                password = ""
        if not password:
            print("Ошибка: пароль обязателен. Задайте --password, MONITOR_PASSWORD или флаг --no-auth.")
            sys.exit(1)

    CONFIG.update(
        password=password,
        no_auth=no_auth,
        fps=args.fps,
        quality=max(1, min(95, args.quality)),
        monitor=args.monitor,
        scale=args.scale,
    )

    ip = local_ip()
    url = f"http://{ip}:{args.port}/"

    print("=" * 60)
    print("  ПРОЗРАЧНЫЙ МОНИТОРИНГ ЭКРАНА ЗАПУЩЕН")
    print("=" * 60)
    print(f"  Открывайте в браузере:   {url}")
    if no_auth:
        print("  Пароль:  ОТКЛЮЧЁН — не показывайте ссылку посторонним!")
    else:
        print(f"  Логин:  любой   Пароль:  (заданный вами)")
    print("  Наблюдение видно: зелёный значок в трее + уведомления.")
    print("  Остановить: Ctrl+C в этом окне или пункт в меню трея.")
    print("=" * 60, flush=True)

    log("Мониторинг запущен.")
    desktop_notify("Мониторинг экрана", "Наблюдение за этим компьютером ВКЛЮЧЕНО.")

    if not args.no_tray:
        start_tray_icon(url)

    # threaded=True — чтобы поток видео не блокировал раздачу страницы.
    app.run(host=args.host, port=args.port, threaded=True, debug=False)


if __name__ == "__main__":
    main()
