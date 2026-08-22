import asyncio
from contextlib import asynccontextmanager, suppress
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import FileResponse
from .config import settings
from .db import connect, initialize
from .market import participant_stats, recorder_gaps, recorder_health
from .market_runtime import StableMarketRecorder
from .participants import ensure_clean_research_epoch, list_participants, research_epoch, seed
from .snapshots import create_snapshot, get_snapshot, latest_snapshot, list_snapshots


market_recorder = StableMarketRecorder(settings)


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


async def snapshot_loop(stop: asyncio.Event) -> None:
    interval = max(1, settings.snapshot_interval_hours) * 3600
    while not stop.is_set():
        try:
            if latest_snapshot(settings.db_path) is None:
                await asyncio.to_thread(build_snapshot)
            await asyncio.wait_for(stop.wait(), timeout=interval)
        except TimeoutError:
            await asyncio.to_thread(build_snapshot)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    initialize(settings.db_path)
    seed(settings.db_path)
    ensure_clean_research_epoch(settings.db_path)
    # recorder_health is an ephemeral status table, not research evidence.
    # Removing stale rows prevents a previous process shutdown from reporting
    # DEGRADED during the new process warm-up.
    with connect(settings.db_path) as conn:
        conn.execute("DELETE FROM recorder_health")
    stop = asyncio.Event()
    tasks = [
        asyncio.create_task(snapshot_loop(stop)),
        asyncio.create_task(market_recorder.run(stop)),
    ]
    yield
    stop.set()
    for task in tasks:
        task.cancel()
    for task in tasks:
        with suppress(asyncio.CancelledError):
            await task


app = FastAPI(title="TradeLab", version="0.2.3", lifespan=lifespan)


@app.get("/health")
def health():
    market = market_recorder.status()
    epoch = research_epoch(settings.db_path)
    return {
        "ok": True,
        "version": "0.2.3",
        "live_trading": False,
        "market_enabled": market["enabled"],
        "last_market_event_ms": market["last_market_event_ms"],
        "last_sample_ms": market["last_sample_ms"],
        "snapshot_kind": "analysis",
        "snapshot_raw_hours": settings.snapshot_raw_hours,
        "research_epoch_id": epoch["epoch_id"],
        "research_epoch_started_at_ms": epoch["started_at_ms"],
        "research_epoch_mode": epoch["mode"],
        "strict_continuity": True,
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
