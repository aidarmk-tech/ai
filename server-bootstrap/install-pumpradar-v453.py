#!/usr/bin/env python3
from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
from pathlib import Path
from typing import Any

EXPECTED_PAYLOAD_SHA256 = "f99bc7577d346eb98b7d8f65186d021ba1f2221cca1c995020e5e92381301df6"
SERVER_SERVICE = "pumpradar.service"
RECORDER_SERVICE = "pumpradar-research-recorder.service"
SHORT_CHANNEL = "REV_MC5_SHORT_600_2X"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(args: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess[str]:
    print("+", " ".join(str(x) for x in args), flush=True)
    return subprocess.run(
        [str(x) for x in args], cwd=cwd, env=env, check=check,
        text=True, stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )


def atomic_copy(src: Path, dst: Path, mode: int) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(prefix=dst.name + ".", dir=dst.parent)
    temp = Path(temp_name)
    try:
        with os.fdopen(fd, "wb") as out, src.open("rb") as inp:
            shutil.copyfileobj(inp, out)
            out.flush(); os.fsync(out.fileno())
        os.chmod(temp, mode)
        os.replace(temp, dst)
    finally:
        temp.unlink(missing_ok=True)


def safe_extract(payload: Path, target: Path) -> None:
    with tarfile.open(payload, "r:gz") as archive:
        root = target.resolve()
        for member in archive.getmembers():
            destination = (target / member.name).resolve()
            if root not in destination.parents and destination != root:
                raise RuntimeError(f"unsafe payload member: {member.name}")
            if not member.isfile():
                raise RuntimeError(f"unexpected non-file payload member: {member.name}")
        archive.extractall(target)


def server_root() -> Path:
    return Path(os.environ.get("PUMPRADAR_SERVER_ROOT", "/opt/pumpradar/server"))


def recorder_path() -> Path:
    return Path(os.environ.get("PUMPRADAR_RECORDER_PATH", "/opt/pumpradar/research_recorder/recorder.py"))


def db_path() -> Path:
    return Path(os.environ.get("PUMPRADAR_DB", "/var/lib/pumpradar/pumpradar.sqlite3"))


def python_path() -> Path:
    return Path(os.environ.get("PUMPRADAR_PYTHON", "/opt/pumpradar/venv/bin/python"))


def resolve_destination(entry: dict[str, Any]) -> Path:
    original = str(entry["destination"])
    if original.startswith("/opt/pumpradar/server/"):
        return server_root() / original.removeprefix("/opt/pumpradar/server/")
    if original == "/opt/pumpradar/research_recorder/recorder.py":
        return recorder_path()
    prefix = os.environ.get("PUMPRADAR_DEST_PREFIX")
    return Path(prefix + original) if prefix else Path(original)


def verify_payload(payload: Path, extracted: Path) -> dict[str, Any]:
    actual = sha256(payload)
    if actual != EXPECTED_PAYLOAD_SHA256:
        raise RuntimeError(f"payload SHA-256 mismatch: {actual}")
    safe_extract(payload, extracted)
    manifest = json.loads((extracted / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("release") != "4.5.3":
        raise RuntimeError("unexpected release manifest")
    payload_files = {str(p.relative_to(extracted)) for p in extracted.rglob("*") if p.is_file()}
    allowed = {"manifest.json", "docs/DATA_SOURCE.md"}
    for entry in manifest["entries"]:
        rel = str(entry["payload"])
        allowed.add(rel)
        path = extracted / rel
        if not path.is_file() or sha256(path) != entry["final_sha256"]:
            raise RuntimeError(f"payload member mismatch: {rel}")
    if payload_files != allowed:
        raise RuntimeError(f"unexpected payload members: {sorted(payload_files - allowed)}")
    return manifest


def verify_live_inputs(manifest: dict[str, Any]) -> None:
    for entry in manifest["entries"]:
        dst = resolve_destination(entry)
        final_hash = str(entry["final_sha256"])
        base_hash = entry.get("base_sha256")
        if dst.is_file():
            actual = sha256(dst)
            permitted = {final_hash}
            if base_hash:
                permitted.add(str(base_hash))
            if actual not in permitted:
                raise RuntimeError(f"unexpected live file SHA-256: {dst}: {actual}")
        elif base_hash:
            raise RuntimeError(f"required live file missing: {dst}")


def safety_scan(candidate: Path) -> None:
    source = "\n".join(path.read_text(encoding="utf-8") for path in (candidate / "pumpradar_server").glob("*.py"))
    forbidden = ("/fapi/v1/order", "/api/v3/order", "create_order", "newOrder", "API_SECRET")
    found = [token for token in forbidden if token in source]
    if found:
        raise RuntimeError(f"real-order marker(s) found: {found}")


def baseline(db: Path) -> tuple[int, int, int, float]:
    connection = sqlite3.connect(db)
    try:
        row = connection.execute(
            """SELECT COUNT(*),
                      COALESCE(SUM(net_pnl_usdt>0),0),
                      COALESCE(SUM(net_pnl_usdt<=0),0),
                      COALESCE(SUM(net_pnl_usdt),0)
               FROM regime_slots
               WHERE channel=? AND status='CLOSED'""",
            (SHORT_CHANNEL,),
        ).fetchone()
        return int(row[0]), int(row[1]), int(row[2]), float(row[3])
    finally:
        connection.close()


def validate_candidate(extracted: Path) -> dict[str, Any]:
    live_server = server_root()
    live_db = db_path()
    py = python_path()
    if not live_server.is_dir() or not live_db.is_file() or not py.is_file():
        raise RuntimeError("server root, database, or virtualenv Python is missing")
    with tempfile.TemporaryDirectory(prefix="pumpradar-v453-validate-") as temp_name:
        temp = Path(temp_name)
        candidate = temp / "server"
        shutil.copytree(live_server, candidate, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
        for src in (extracted / "server").rglob("*"):
            if src.is_file():
                destination = candidate / src.relative_to(extracted / "server")
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, destination)
        safety_scan(candidate)
        env = dict(os.environ)
        env["PYTHONPATH"] = str(candidate)
        run([str(py), "-m", "compileall", "-q", "pumpradar_server", "tests"], cwd=candidate, env=env)
        tests = run([str(py), "-m", "unittest", "discover", "-s", "tests", "-v"], cwd=candidate, env=env, capture=True)
        print(tests.stdout, end="")
        if "Ran 40 tests" not in (tests.stdout or "") or "OK" not in (tests.stdout or ""):
            raise RuntimeError("unexpected server test result")
        run([str(py), str(extracted / "recorder/recorder.py"), "--self-test"])
        run([str(py), str(extracted / "bin/pumpradar-btc-regime-update"), "--self-test"])
        before = baseline(live_db)
        migrated = temp / "migration.sqlite3"
        shutil.copy2(live_db, migrated)
        code = r'''
import asyncio, os, sqlite3
from dataclasses import replace
from pathlib import Path
from pumpradar_server.config import Settings
from pumpradar_server.futures import FuturesMarketState
from pumpradar_server.regime import RegimePaperManager
from pumpradar_server.storage import Storage
p=Path(os.environ["V453_MIGRATION_DB"])
s=replace(Settings(), data_dir=p.parent, db_path=p, btc_regime_path=p.parent/"btc-regime.json")
st=Storage(s)
async def notify(_): return None
manager=RegimePaperManager(s,FuturesMarketState(),st,notify)
assert st.conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
assert not list(st.conn.execute("PRAGMA foreign_key_check"))
for name in ("short_signal_research","short_exit_challengers","short_failure_labels"):
    assert st.conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",(name,)).fetchone()
st.conn.close()
'''
        migration_env = dict(env); migration_env["V453_MIGRATION_DB"] = str(migrated)
        run([str(py), "-c", code], cwd=candidate, env=migration_env)
        after = baseline(migrated)
        if before != after:
            raise RuntimeError(f"SHORT baseline changed during migration: {before} -> {after}")
        connection = sqlite3.connect(migrated)
        try:
            integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
            fk = list(connection.execute("PRAGMA foreign_key_check"))
        finally:
            connection.close()
        if integrity != "ok" or fk:
            raise RuntimeError(f"migration validation failed: integrity={integrity}, foreign_keys={len(fk)}")
        return {"tests": 40, "short_baseline": before, "integrity": integrity, "foreign_keys": len(fk)}


def systemctl(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return run(["systemctl", *args], check=check, capture=not check)


def is_active(service: str) -> bool:
    return subprocess.run(["systemctl", "is-active", "--quiet", service]).returncode == 0


def open_slot_count() -> int:
    connection = sqlite3.connect(db_path(), timeout=30)
    try:
        return int(connection.execute("SELECT COUNT(*) FROM regime_slots WHERE status='OPEN'").fetchone()[0])
    finally:
        connection.close()


def wait_for_no_open_slots() -> None:
    timeout = int(os.environ.get("PUMPRADAR_INSTALL_WAIT_SECONDS", "3600"))
    deadline = time.monotonic() + timeout
    while True:
        count = open_slot_count()
        if count == 0:
            print("No open paper slots; proceeding.")
            return
        remaining = max(0, int(deadline - time.monotonic()))
        if remaining <= 0:
            raise RuntimeError(f"timed out waiting for {count} open paper slot(s)")
        print(f"Waiting for {count} open paper slot(s) to close; {remaining}s remaining...", flush=True)
        time.sleep(min(30, remaining))


def sqlite_backup(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    source = sqlite3.connect(f"file:{src}?mode=ro", uri=True, timeout=30)
    target = sqlite3.connect(dst)
    try:
        source.backup(target); target.commit()
        integrity = target.execute("PRAGMA integrity_check").fetchone()[0]
        fk = list(target.execute("PRAGMA foreign_key_check"))
        if integrity != "ok" or fk:
            raise RuntimeError(f"backup invalid: integrity={integrity}, foreign_keys={len(fk)}")
    finally:
        target.close(); source.close()


def backup_current(manifest: dict[str, Any]) -> Path:
    root = Path(os.environ.get("PUMPRADAR_V453_BACKUP_ROOT", "/var/lib/pumpradar/v453-backups"))
    stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime())
    backup = root / stamp
    backup.mkdir(parents=True, exist_ok=False)
    shutil.copytree(server_root(), backup / "server", ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    (backup / "recorder").mkdir()
    shutil.copy2(recorder_path(), backup / "recorder/recorder.py")
    sqlite_backup(db_path(), backup / "pumpradar.sqlite3")
    state: dict[str, Any] = {"entries": []}
    for entry in manifest["entries"]:
        dst = resolve_destination(entry)
        if str(dst).startswith(str(server_root())) or dst == recorder_path():
            continue
        item = {"destination": str(dst), "existed": dst.is_file()}
        if dst.is_file():
            saved = backup / "external" / dst.as_posix().lstrip("/")
            saved.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(dst, saved)
            item["backup"] = str(saved.relative_to(backup))
        state["entries"].append(item)
    (backup / "state.json").write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    latest = root / "latest"
    temporary = root / f".latest-{stamp}"
    temporary.symlink_to(backup.name)
    os.replace(temporary, latest)
    return backup


def restore_backup(backup: Path) -> None:
    print(f"Restoring {backup}")
    systemctl("stop", SERVER_SERVICE, check=False)
    systemctl("stop", RECORDER_SERVICE, check=False)
    live_server = server_root()
    replacement = live_server.with_name(live_server.name + ".rollback-tmp")
    shutil.rmtree(replacement, ignore_errors=True)
    shutil.copytree(backup / "server", replacement)
    old = live_server.with_name(live_server.name + ".failed-v453")
    shutil.rmtree(old, ignore_errors=True)
    if live_server.exists(): os.replace(live_server, old)
    os.replace(replacement, live_server)
    atomic_copy(backup / "recorder/recorder.py", recorder_path(), 0o644)
    sqlite_backup(backup / "pumpradar.sqlite3", db_path())
    state = json.loads((backup / "state.json").read_text(encoding="utf-8"))
    for item in state["entries"]:
        dst = Path(item["destination"])
        if item["existed"]:
            src = backup / item["backup"]
            atomic_copy(src, dst, src.stat().st_mode & 0o777)
        else:
            dst.unlink(missing_ok=True)
    systemctl("daemon-reload", check=False)
    systemctl("disable", "--now", "pumpradar-btc-regime.timer", check=False)
    systemctl("start", RECORDER_SERVICE, check=False)
    systemctl("start", SERVER_SERVICE, check=False)


def install_files(manifest: dict[str, Any], extracted: Path) -> None:
    for entry in manifest["entries"]:
        src = extracted / entry["payload"]
        dst = resolve_destination(entry)
        atomic_copy(src, dst, int(str(entry["mode"]), 8))
        if sha256(dst) != entry["final_sha256"]:
            raise RuntimeError(f"post-write hash mismatch: {dst}")


def verify_runtime() -> None:
    systemctl("is-active", "--quiet", SERVER_SERVICE)
    systemctl("is-active", "--quiet", RECORDER_SERVICE)
    deadline = time.monotonic() + 120
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen("http://127.0.0.1:8787/healthz", timeout=5) as response:
                payload = json.load(response)
            if payload.get("algorithm_version") == "4.5.3-server":
                break
            last_error = RuntimeError(f"unexpected health version: {payload.get('algorithm_version')}")
        except Exception as exc:
            last_error = exc
        time.sleep(3)
    else:
        raise RuntimeError(f"server health verification failed: {last_error}")
    status_path = Path("/var/lib/pumpradar/research/status.json")
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            payload = json.loads(status_path.read_text(encoding="utf-8"))
            if str(payload.get("version")) == "2.1.0":
                return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError("recorder 2.1.0 status did not appear")


def install(payload: Path) -> None:
    if os.geteuid() != 0:
        raise RuntimeError("installer must run as root")
    with tempfile.TemporaryDirectory(prefix="pumpradar-v453-") as temp_name:
        extracted = Path(temp_name) / "payload"
        extracted.mkdir()
        manifest = verify_payload(payload, extracted)
        verify_live_inputs(manifest)
        result = validate_candidate(extracted)
        print("Validation:", json.dumps(result, sort_keys=True))
        wait_for_no_open_slots()
        server_was_active = is_active(SERVER_SERVICE)
        recorder_was_active = is_active(RECORDER_SERVICE)
        systemctl("stop", SERVER_SERVICE)
        systemctl("stop", RECORDER_SERVICE)
        if open_slot_count() != 0:
            if recorder_was_active: systemctl("start", RECORDER_SERVICE, check=False)
            if server_was_active: systemctl("start", SERVER_SERVICE, check=False)
            raise RuntimeError("a paper slot opened during the install boundary; retry later")
        backup: Path | None = None
        try:
            backup = backup_current(manifest)
            install_files(manifest, extracted)
            systemctl("daemon-reload")
            systemctl("enable", "--now", "pumpradar-btc-regime.timer")
            run(["/usr/local/bin/pumpradar-btc-regime-update"], check=False)
            systemctl("start", RECORDER_SERVICE)
            systemctl("start", SERVER_SERVICE)
            verify_runtime()
            print(f"PumpRadar 4.5.3 installed successfully. Backup: {backup}")
        except Exception:
            if backup is not None:
                with contextlib.suppress(Exception): restore_backup(backup)
            else:
                if recorder_was_active: systemctl("start", RECORDER_SERVICE, check=False)
                if server_was_active: systemctl("start", SERVER_SERVICE, check=False)
            raise


def rollback() -> None:
    if os.geteuid() != 0:
        raise RuntimeError("rollback must run as root")
    root = Path(os.environ.get("PUMPRADAR_V453_BACKUP_ROOT", "/var/lib/pumpradar/v453-backups"))
    latest = root / "latest"
    if not latest.exists():
        raise RuntimeError("no v4.5.3 backup found")
    restore_backup(latest.resolve())
    print("PumpRadar rollback completed")


def main() -> int:
    parser = argparse.ArgumentParser(description="PumpRadar v4.5.3 safe installer")
    parser.add_argument("action", choices=("install", "validate", "rollback"), nargs="?", default="install")
    parser.add_argument("payload", type=Path, nargs="?")
    args = parser.parse_args()
    if args.action == "rollback":
        rollback(); return 0
    if args.payload is None or not args.payload.is_file():
        raise RuntimeError("payload path is required")
    if args.action == "install":
        install(args.payload)
        return 0
    with tempfile.TemporaryDirectory(prefix="pumpradar-v453-check-") as temp_name:
        extracted = Path(temp_name)
        manifest = verify_payload(args.payload, extracted)
        verify_live_inputs(manifest)
        result = validate_candidate(extracted)
        print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
