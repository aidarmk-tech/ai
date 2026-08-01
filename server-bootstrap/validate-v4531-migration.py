from __future__ import annotations

import argparse
import gzip
import shutil
import sqlite3
from pathlib import Path

from pumpradar_server.config import Settings
from pumpradar_server.futures import FuturesMarketState
from pumpradar_server.regime import RegimePaperManager
from pumpradar_server.storage import Storage


def baseline(connection: sqlite3.Connection) -> tuple[int, float]:
    row = connection.execute(
        """SELECT COUNT(*),ROUND(COALESCE(SUM(net_pnl_usdt),0),8)
           FROM regime_slots WHERE status='CLOSED'"""
    ).fetchone()
    return int(row[0]), float(row[1])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(args.source, "rb") as source, args.output.open("wb") as target:
        shutil.copyfileobj(source, target)

    before_connection = sqlite3.connect(args.output)
    before = baseline(before_connection)
    before_connection.close()

    settings = Settings(db_path=args.output, data_dir=args.output.parent / "migration-data")
    storage = Storage(settings)
    RegimePaperManager(settings, FuturesMarketState(), storage, lambda _: None)
    RegimePaperManager(settings, FuturesMarketState(), storage, lambda _: None)

    after = baseline(storage.conn)
    integrity = storage.conn.execute("PRAGMA integrity_check").fetchone()[0]
    foreign_keys = list(storage.conn.execute("PRAGMA foreign_key_check"))
    tables = {
        row[0]
        for row in storage.conn.execute(
            """SELECT name FROM sqlite_master WHERE name IN
               ('exit_window_samples','exit_policy_outcomes','dump_health_diagnostics')"""
        )
    }
    result = {
        "baseline_before": before,
        "baseline_after": after,
        "integrity": integrity,
        "foreign_key_errors": len(foreign_keys),
        "new_tables": sorted(tables),
    }
    print(result)
    if before != after or integrity != "ok" or foreign_keys or len(tables) != 3:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
