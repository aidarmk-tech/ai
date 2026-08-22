import asyncio
from contextlib import asynccontextmanager, suppress
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import FileResponse
from .config import settings
from .db import initialize
from .participants import list_participants, seed
from .snapshots import create_snapshot, get_snapshot, latest_snapshot


def require_token(x_tradelab_token: str | None) -> None:
    if settings.read_token and x_tradelab_token != settings.read_token:
        raise HTTPException(status_code=401, detail="invalid token")


async def snapshot_loop(stop: asyncio.Event) -> None:
    interval = max(1, settings.snapshot_interval_hours) * 3600
    while not stop.is_set():
        try:
            if latest_snapshot(settings.db_path) is None:
                await asyncio.to_thread(create_snapshot, settings.db_path, settings.snapshot_dir, settings.snapshot_keep)
            await asyncio.wait_for(stop.wait(), timeout=interval)
        except TimeoutError:
            await asyncio.to_thread(create_snapshot, settings.db_path, settings.snapshot_dir, settings.snapshot_keep)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    initialize(settings.db_path)
    seed(settings.db_path)
    stop = asyncio.Event()
    task = asyncio.create_task(snapshot_loop(stop))
    yield
    stop.set()
    task.cancel()
    with suppress(asyncio.CancelledError):
        await task


app = FastAPI(title="TradeLab", version="0.1.0", lifespan=lifespan)


@app.get("/health")
def health():
    return {"ok": True, "version": "0.1.0", "live_trading": False}


@app.get("/api/v1/participants")
def participants(x_tradelab_token: str | None = Header(default=None)):
    require_token(x_tradelab_token)
    return {"participants": list_participants(settings.db_path)}


@app.post("/api/v1/snapshots/create")
def make_snapshot(x_tradelab_token: str | None = Header(default=None)):
    require_token(x_tradelab_token)
    snap = create_snapshot(settings.db_path, settings.snapshot_dir, settings.snapshot_keep)
    return snap.as_dict()


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
    return FileResponse(path, media_type="application/gzip", filename=snap.filename)
