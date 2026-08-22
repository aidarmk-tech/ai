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

CREATE TABLE IF NOT EXISTS participant_specs (
  participant_id TEXT PRIMARY KEY,
  spec_version TEXT NOT NULL,
  config_json TEXT NOT NULL,
  frozen_at_ms INTEGER NOT NULL,
  active_effect TEXT NOT NULL DEFAULT 'SHADOW_ONLY',
  FOREIGN KEY(participant_id) REFERENCES participants(participant_id)
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
CREATE INDEX IF NOT EXISTS idx_participant_events_pid_ts ON participant_events(participant_id, ts_ms);

CREATE TABLE IF NOT EXISTS market_samples (
  ts_ms INTEGER NOT NULL,
  symbol TEXT NOT NULL,
  last_price REAL NOT NULL,
  bid REAL,
  ask REAL,
  bid_qty REAL,
  ask_qty REAL,
  mark_price REAL,
  index_price REAL,
  funding_rate REAL,
  quote_volume_24h REAL,
  trade_count_24h INTEGER,
  PRIMARY KEY(ts_ms, symbol)
);
CREATE INDEX IF NOT EXISTS idx_market_samples_symbol_ts ON market_samples(symbol, ts_ms);

CREATE TABLE IF NOT EXISTS flow_samples (
  ts_ms INTEGER NOT NULL,
  symbol TEXT NOT NULL,
  buy_notional REAL NOT NULL DEFAULT 0,
  sell_notional REAL NOT NULL DEFAULT 0,
  buy_qty REAL NOT NULL DEFAULT 0,
  sell_qty REAL NOT NULL DEFAULT 0,
  agg_trades INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(ts_ms, symbol)
);
CREATE INDEX IF NOT EXISTS idx_flow_samples_symbol_ts ON flow_samples(symbol, ts_ms);

CREATE TABLE IF NOT EXISTS depth_samples (
  ts_ms INTEGER NOT NULL,
  symbol TEXT NOT NULL,
  spread_bps REAL,
  bid_notional_10 REAL,
  ask_notional_10 REAL,
  imbalance REAL,
  best_bid_qty REAL,
  best_ask_qty REAL,
  bid_replenishment REAL,
  ask_replenishment REAL,
  PRIMARY KEY(ts_ms, symbol)
);
CREATE INDEX IF NOT EXISTS idx_depth_samples_symbol_ts ON depth_samples(symbol, ts_ms);

CREATE TABLE IF NOT EXISTS open_interest_samples (
  ts_ms INTEGER NOT NULL,
  symbol TEXT NOT NULL,
  open_interest REAL NOT NULL,
  open_interest_value REAL,
  PRIMARY KEY(ts_ms, symbol)
);
CREATE INDEX IF NOT EXISTS idx_oi_symbol_ts ON open_interest_samples(symbol, ts_ms);

CREATE TABLE IF NOT EXISTS liquidations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts_ms INTEGER NOT NULL,
  symbol TEXT NOT NULL,
  side TEXT NOT NULL,
  quantity REAL,
  price REAL,
  notional REAL
);
CREATE INDEX IF NOT EXISTS idx_liquidations_symbol_ts ON liquidations(symbol, ts_ms);

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

CREATE TABLE IF NOT EXISTS paper_trades (
  trade_id TEXT PRIMARY KEY,
  participant_id TEXT NOT NULL,
  symbol_a TEXT NOT NULL,
  symbol_b TEXT,
  side_a TEXT NOT NULL,
  side_b TEXT,
  hedge_ratio REAL,
  opened_at_ms INTEGER NOT NULL,
  entry_a REAL NOT NULL,
  entry_b REAL,
  exit_due_ms INTEGER NOT NULL,
  closed_at_ms INTEGER,
  exit_a REAL,
  exit_b REAL,
  gross_return_pct REAL,
  net_return_pct REAL,
  notional_usdt REAL NOT NULL,
  pnl_usdt REAL,
  status TEXT NOT NULL DEFAULT 'OPEN',
  signal_json TEXT NOT NULL,
  FOREIGN KEY(participant_id) REFERENCES participants(participant_id)
);
CREATE INDEX IF NOT EXISTS idx_paper_trades_pid_status ON paper_trades(participant_id, status, opened_at_ms);

CREATE TABLE IF NOT EXISTS recorder_health (
  component TEXT PRIMARY KEY,
  status TEXT NOT NULL,
  last_event_ms INTEGER,
  detail TEXT,
  updated_at_ms INTEGER NOT NULL
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
