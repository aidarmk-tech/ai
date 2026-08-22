import asyncio
import faulthandler
import os
import threading
import time
from datetime import datetime, timezone
from pathlib import Path


class EventLoopWatchdog:
    """Detect a stalled asyncio loop from a native Python thread.

    The watchdog thread is deliberately outside the event loop. If the loop
    stops making progress for ``stall_seconds``, it writes all Python thread
    stacks to persistent storage and exits the process with a non-zero code.
    systemd then restarts TradeLab via Restart=on-failure.
    """

    def __init__(self, log_path: Path, stall_seconds: float = 20.0, check_seconds: float = 2.0):
        self.log_path = Path(log_path)
        self.stall_seconds = float(stall_seconds)
        self.check_seconds = float(check_seconds)
        self._last_beat = time.monotonic()
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def beat(self) -> None:
        with self._lock:
            self._last_beat = time.monotonic()

    def age_seconds(self) -> float:
        with self._lock:
            last = self._last_beat
        return max(0.0, time.monotonic() - last)

    def status(self) -> dict:
        return {
            "enabled": True,
            "heartbeat_age_ms": int(self.age_seconds() * 1000),
            "stall_seconds": self.stall_seconds,
            "log_path": str(self.log_path),
        }

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        self._stop.clear()
        self.beat()
        self._thread = threading.Thread(
            target=self._run,
            name="tradelab-event-loop-watchdog",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        thread = self._thread
        if thread and thread.is_alive():
            thread.join(timeout=max(1.0, self.check_seconds * 2))

    async def heartbeat_loop(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            self.beat()
            try:
                await asyncio.wait_for(stop.wait(), timeout=1.0)
            except TimeoutError:
                pass

    def _run(self) -> None:
        while not self._stop.wait(self.check_seconds):
            age = self.age_seconds()
            if age < self.stall_seconds:
                continue
            try:
                with self.log_path.open("a", buffering=1) as fh:
                    stamp = datetime.now(timezone.utc).isoformat()
                    fh.write(
                        f"\n=== EVENT LOOP STALL {stamp} age_seconds={age:.3f} pid={os.getpid()} ===\n"
                    )
                    faulthandler.dump_traceback(file=fh, all_threads=True)
                    fh.write("=== END STALL TRACE ===\n")
                    fh.flush()
                    os.fsync(fh.fileno())
            finally:
                # Hard exit is intentional: graceful asyncio shutdown cannot be
                # trusted while the event loop itself is stalled. SQLite WAL is
                # crash-safe and systemd Restart=on-failure brings the recorder
                # back instead of leaving an hour-long silent data gap.
                os._exit(70)
