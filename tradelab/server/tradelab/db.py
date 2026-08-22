import sqlite3
from pathlib import Path


SCHEMA = """
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS participants (
  participant_id TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  starting_equity REAL NOT NULL DEFAULT 20.0,
  equity REAL NOT NULL DEFAULT 20.0,
  rank INTEGER,
  role TEXT,
  created_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS participant_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts_ms INTEGER NOT NULL,
  participant_id TEXT NOT NULL,
  symbol TEXT,
  event_type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  FOREIGN KEY(participant_id) REFERENCES participants(participant_id)
);

CREATE TABLE IF NOT EXISTS market_states (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts_ms INTEGER NOT NULL,
  symbol TEXT NOT NULL,
  source TEXT NOT NULL,
  payload_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_market_states_ts_symbol ON market_states(ts_ms, symbol);

CREATE TABLE IF NOT EXISTS forward_labels (
  market_state_id INTEGER PRIMARY KEY,
  ret_5s REAL, ret_15s REAL, ret_30s REAL, ret_60s REAL,
  ret_120s REAL, ret_300s REAL,
  mfe_30s REAL, mae_30s REAL,
  mfe_60s REAL, mae_60s REAL,
  mfe_300s REAL, mae_300s REAL,
  FOREIGN KEY(market_state_id) REFERENCES market_states(id)
);

CREATE TABLE IF NOT EXISTS snapshots (
  snapshot_id TEXT PRIMARY KEY,
  created_at_ms INTEGER NOT NULL,
  filename TEXT NOT NULL,
  bytes INTEGER NOT NULL,
  sha256 TEXT NOT NULL
);
"""


def connect(path: Path) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=30000")
    return conn


def initialize(path: Path) -> None:
    with connect(path) as conn:
        conn.executescript(SCHEMA)
