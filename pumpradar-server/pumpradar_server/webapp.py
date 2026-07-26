from __future__ import annotations

import asyncio
import json
from pathlib import Path

from aiohttp import web

from .config import Settings
from .storage import Storage


class WebApp:
    def __init__(self, settings: Settings, storage: Storage, health_provider) -> None:
        self.settings = settings
        self.storage = storage
        self.health_provider = health_provider

    @web.middleware
    async def auth(self, request: web.Request, handler):
        if request.path == "/healthz":
            return await handler(request)
        token = request.headers.get("Authorization", "").removeprefix("Bearer ").strip()
        if not self.settings.api_token or token != self.settings.api_token:
            raise web.HTTPUnauthorized(text="Bearer token required")
        return await handler(request)

    async def health(self, request: web.Request) -> web.Response:
        return web.json_response(self.health_provider())

    async def status(self, request: web.Request) -> web.Response:
        return web.json_response(self.storage.status() | self.health_provider())

    async def export(self, request: web.Request) -> web.Response:
        path = await asyncio.to_thread(self.storage.export_all)
        return web.json_response({"export_dir": str(path), "files": sorted(p.name for p in path.iterdir())})

    async def latest_file(self, request: web.Request) -> web.StreamResponse:
        name = request.match_info["name"]
        if "/" in name or ".." in name:
            raise web.HTTPBadRequest()
        latest = self.settings.data_dir / "exports" / "latest"
        path = latest.resolve() / name if latest.exists() else None
        if path is None or not path.exists() or not path.is_file():
            raise web.HTTPNotFound()
        return web.FileResponse(path)

    def make(self) -> web.Application:
        app = web.Application(middlewares=[self.auth])
        app.add_routes([
            web.get("/healthz", self.health),
            web.get("/api/status", self.status),
            web.post("/api/export", self.export),
            web.get("/api/export/latest/{name}", self.latest_file),
        ])
        return app
