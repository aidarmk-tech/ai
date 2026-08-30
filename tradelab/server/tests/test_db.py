import sqlite3

import pytest

from tradelab.db import connect, initialize


def test_connection_context_manager_closes_file_handle(tmp_path):
    db = tmp_path / "tradelab.sqlite3"
    initialize(db)

    conn = connect(db)
    with conn as active:
        active.execute("SELECT 1").fetchone()

    with pytest.raises(sqlite3.ProgrammingError, match="closed database"):
        conn.execute("SELECT 1")


def test_repeated_short_lived_connections_do_not_accumulate(tmp_path):
    db = tmp_path / "tradelab.sqlite3"
    initialize(db)

    # This mirrors the recorder's high-frequency access pattern. Every context
    # must be unusable immediately after exit rather than waiting for GC.
    closed = []
    for _ in range(500):
        conn = connect(db)
        with conn as active:
            active.execute("SELECT COUNT(*) FROM market_samples").fetchone()
        closed.append(conn)

    for conn in closed:
        with pytest.raises(sqlite3.ProgrammingError, match="closed database"):
            conn.execute("SELECT 1")
