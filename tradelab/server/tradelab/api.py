import asyncio
import time
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import FileResponse

from .config import settings
from .db import connect, initialize
from .market import participant_stats, recorder_gaps, recorder_health
from .market_runtime import StableMarketRecorder
from .participants import ensure_clean_research_epoch, list_participants, research_epoch, seed
from .snapshots import (
    create_full_snapshot,
    create_snapshot,
    get_snapshot,
    latest_snapshot,
    list_snapshots,
)
from .watchdog import EventLoopWatchdog


market_recorder = StableMarketRecorder(settings)
event_loop_watchdog = EventLoopWatchdog(settings.data_dir / "event-loop-watchdog.log")


def require_token(x_tradelab_token: str | None) -> None:
    if settings.read_token and x_tradelab_token != settings.read_token:
        raise HTTPException(status_code=401, detail="invalid token")


def build_snapshot():
    return create_snapshot(
        settings.db_path,
        settings.snapshot_dir,
        settings.snapshot_keep,
        settings.snapshot_raw_hours,
    )


def build_full_snapshot():
    return create_full_snapshot(
        settings.db_path,
        settings.snapshot_dir,
        settings.full_snapshot_keep,
        settings.raw_retention_hours,
    )


async def snapshot_loop(stop: asyncio.Event) -> None:
    """Keep routine snapshots on a true four-hour wall-clock cadence.

    Older versions waited a fresh four hours after every process restart even
    when the latest snapshot was already several hours old. That could create a
    >6h hole between compact snapshots. The loop now schedules from the latest
    snapshot timestamp, so service restarts cannot stretch the archive cadence.
    """
    interval_ms = max(1, settings.snapshot_interval_hours) * 3600_000
    while not stop.is_set():
        try:
            snap = latest_snapshot(settings.db_path)
            now = int(time.time() * 1000)
            if snap is None or now - snap.created_at_ms >= interval_ms:
                snap = await asyncio.to_thread(build_snapshot)
                market_recorder._health(
                    "snapshot_loop",
                    "OK",
                    f"created={snap.filename} bytes={snap.bytes}",
                    snap.created_at_ms,
                )
                now = int(time.time() * 1000)

            due_ms = snap.created_at_ms + interval_ms
            wait_seconds = max(1.0, (due_ms - now) / 1000.0)
            await asyncio.wait_for(stop.wait(), timeout=wait_seconds)
        except TimeoutError:
            pass
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            market_recorder._health("snapshot_loop", "DEGRADED", repr(exc))
            try:
                await asyncio.wait_for(stop.wait(), timeout=60)
            except TimeoutError:
                pass


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    initialize(settings.db_path)
    seed(settings.db_path)
    ensure_clean_research_epoch(settings.db_path)
    # recorder_health is an ephemeral status table, not research evidence.
    with connect(settings.db_path) as conn:
        conn.execute("DELETE FROM recorder_health")
    stop = asyncio.Event()
    event_loop_watchdog.start()
    tasks = [
        asyncio.create_task(event_loop_watchdog.heartbeat_loop(stop)),
        asyncio.create_task(snapshot_loop(stop)),
        asyncio.create_task(market_recorder.run(stop)),
    ]
    yield
    stop.set()
    event_loop_watchdog.stop()
    for task in tasks:
        task.cancel()
    for task in tasks:
        with suppress(asyncio.CancelledError):
            await task


app = FastAPI(title="TradeLab", version="0.2.7", lifespan=lifespan)


@app.get("/health")
def health():
    market = market_recorder.status()
    epoch = research_epoch(settings.db_path)
    return {
        "ok": True,
        "version": "0.2.7",
        "live_trading": False,
        "market_enabled": market["enabled"],
        "last_market_event_ms": market["last_market_event_ms"],
        "last_sample_ms": market["last_sample_ms"],
        "snapshot_kind": "analysis",
        "snapshot_raw_hours": settings.snapshot_raw_hours,
        "full_snapshot_raw_hours": settings.raw_retention_hours,
        "snapshot_interval_hours": settings.snapshot_interval_hours,
        "research_epoch_id": epoch["epoch_id"],
        "research_epoch_started_at_ms": epoch["started_at_ms"],
        "research_epoch_mode": epoch["mode"],
        "strict_continuity": True,
        "event_loop_watchdog": event_loop_watchdog.status(),
    }


@app.get("/api/v1/participants")
def participants(x_tradelab_token: str | None = Header(default=None)):
    require_token(x_tradelab_token)
    return {"participants": list_participants(settings.db_path)}


@app.get("/api/v1/tournament")
def tournament(x_tradelab_token: str | None = Header(default=None)):
    require_token(x_tradelab_token)
    return {
        "mode": "SHADOW_ONLY_FIXED_HORIZON_STRICT_CONTINUITY",
        "champion_assignment": "DISABLED_UNTIL_EVIDENCE_GATE",
        "epoch": research_epoch(settings.db_path),
        "participants": participant_stats(settings.db_path),
    }


@app.get("/api/v1/market/status")
def market_status(x_tradelab_token: str | None = Header(default=None)):
    require_token(x_tradelab_token)
    return {
        "recorder": market_recorder.status(),
        "components": recorder_health(settings.db_path),
        "gaps": recorder_gaps(settings.db_path),
    }


@app.post("/api/v1/snapshots/create")
def make_snapshot(x_tradelab_token: str | None = Header(default=None)):
    require_token(x_tradelab_token)
    snap = build_snapshot()
    return snap.as_dict()


@app.post("/api/v1/snapshots/full/create")
def make_full_snapshot(x_tradelab_token: str | None = Header(default=None)):
    """On-demand complete export of every row still retained by the VPS DB."""
    require_token(x_tradelab_token)
    snap = build_full_snapshot()
    data = snap.as_dict()
    data["download_url"] = f"/api/v1/snapshots/{snap.snapshot_id}/download"
    return data


@app.get("/api/v1/snapshots")
def snapshots(after_ms: int = 0, x_tradelab_token: str | None = Header(default=None)):
    require_token(x_tradelab_token)
    items = []
    for snap in list_snapshots(settings.db_path, after_ms):
        data = snap.as_dict()
        data["download_url"] = f"/api/v1/snapshots/{snap.snapshot_id}/download"
        items.append(data)
    return {"snapshots": items}


@app.get("/api/v1/snapshots/latest")
def latest(x_tradelab_token: str | None = Header(default=None)):
    require_token(x_tradelab_token)
    snap = latest_snapshot(settings.db_path)
    if snap is None:
        raise HTTPException(status_code=404, detail="no snapshot")
    data = snap.as_dict()
    data["download_url"] = f"/api/v1/snapshots/{snap.snapshot_id}/download"
    return data


@app.get("/api/v1/snapshots/{snapshot_id}/download")
def download(snapshot_id: str, x_tradelab_token: str | None = Header(default=None)):
    require_token(x_tradelab_token)
    snap = get_snapshot(settings.db_path, snapshot_id)
    if snap is None:
        raise HTTPException(status_code=404, detail="snapshot not found")
    path = settings.snapshot_dir / snap.filename
    if not path.exists():
        raise HTTPException(status_code=404, detail="snapshot file missing")
    return FileResponse(
        path,
        media_type="application/gzip",
        filename=snap.filename,
        headers={"Accept-Ranges": "bytes", "Cache-Control": "private, no-store"},
    )
