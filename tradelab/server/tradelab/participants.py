import json
import time
import uuid
from dataclasses import dataclass

from .db import connect


TARGET_RESEARCH_MODE = "CLEAN_SHADOW_V3"


@dataclass(frozen=True)
class Participant:
    participant_id: str
    display_name: str
    spec_version: str
    config: dict


PARTICIPANTS = (
    Participant(
        "BTC_ALT_LAG",
        "BTC → ALT Lead/Lag",
        "A1-20260822",
        {
            "warmup_seconds": 180,
            "btc_impulse_15s_pct": 0.20,
            "min_lag_gap_pct": 0.15,
            "beta_window_seconds": 180,
            "min_quote_volume_24h": 5_000_000,
            "horizon_seconds": 90,
            "cooldown_seconds": 120,
        },
    ),
    Participant(
        "REGIME_MOMENTUM",
        "Regime Momentum",
        "B1-20260822",
        {
            "warmup_seconds": 300,
            "btc_ret_60s_pct": 0.12,
            "btc_ret_300s_pct": 0.25,
            "asset_ret_60s_pct": 0.20,
            "asset_ret_15s_pct": 0.05,
            "horizon_seconds": 180,
            "cooldown_seconds": 240,
        },
    ),
    Participant(
        "FLOW_ABSORPTION",
        "Flow / Absorption",
        "C1-20260822",
        {
            "warmup_seconds": 60,
            "flow_window_seconds": 15,
            "min_flow_notional": 25_000,
            "flow_imbalance_abs": 0.35,
            "max_price_response_abs_pct": 0.10,
            "min_replenishment": 0.05,
            "horizon_seconds": 120,
            "cooldown_seconds": 180,
        },
    ),
    Participant(
        "STAT_ARB",
        "Statistical Arbitrage",
        "D1-20260822",
        {
            "warmup_seconds": 1800,
            "pair_window_seconds": 1800,
            "sample_seconds": 5,
            "min_correlation": 0.75,
            "entry_z_abs": 2.20,
            "top_pairs": 5,
            "horizon_seconds": 600,
            "cooldown_seconds": 900,
        },
    ),
)


def seed(db_path) -> None:
    now = int(time.time() * 1000)
    with connect(db_path) as conn:
        for p in PARTICIPANTS:
            conn.execute(
                """INSERT OR IGNORE INTO participants
                (participant_id, display_name, status, starting_equity, equity, role, created_at_ms)
                VALUES (?, ?, 'ACTIVE', 20.0, 20.0, 'CANDIDATE', ?)""",
                (p.participant_id, p.display_name, now),
            )
            conn.execute(
                "UPDATE participants SET role=COALESCE(role, 'CANDIDATE') WHERE participant_id=?",
                (p.participant_id,),
            )
            conn.execute(
                """INSERT OR IGNORE INTO participant_specs
                (participant_id, spec_version, config_json, frozen_at_ms, active_effect)
                VALUES (?, ?, ?, ?, 'SHADOW_ONLY')""",
                (p.participant_id, p.spec_version, json.dumps(p.config, sort_keys=True), now),
            )


def ensure_clean_research_epoch(db_path) -> dict:
    """Create the final accountable tournament epoch exactly once.

    V1/V2 were infrastructure preflight periods. V3 begins only after the FD
    leak, false sampler health, subscription churn and paper replay invariants
    were hardened. Raw market evidence is deliberately retained across the
    rotation; only tournament outputs and participant equity are reset.
    Ordinary restarts under V3 never clear results again.
    """
    now = int(time.time() * 1000)
    with connect(db_path) as conn:
        existing = conn.execute("SELECT value FROM meta WHERE key='research_epoch_id'").fetchone()
        mode_row = conn.execute("SELECT value FROM meta WHERE key='research_epoch_mode'").fetchone()
        current_mode = mode_row[0] if mode_row else None
        if existing and current_mode == TARGET_RESEARCH_MODE:
            started = conn.execute("SELECT value FROM meta WHERE key='research_epoch_started_at_ms'").fetchone()
            return {
                "epoch_id": existing[0],
                "started_at_ms": int(started[0]) if started else 0,
                "created": False,
            }

        counts = {}
        for table in (
            "paper_trades",
            "participant_events",
            "market_states",
            "forward_labels",
            "forward_label_quality",
        ):
            counts[table] = conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]

        conn.execute("DELETE FROM forward_label_quality")
        conn.execute("DELETE FROM forward_labels")
        conn.execute("DELETE FROM market_states")
        conn.execute("DELETE FROM participant_events")
        conn.execute("DELETE FROM paper_trades")
        conn.execute("UPDATE participants SET equity=starting_equity, rank=NULL, role='CANDIDATE'")

        previous_epoch = existing[0] if existing else None
        epoch = f"R3-{now}-{uuid.uuid4().hex[:8]}"
        meta = [
            ("research_epoch_id", epoch),
            ("research_epoch_started_at_ms", str(now)),
            ("research_epoch_mode", TARGET_RESEARCH_MODE),
            ("preflight_discarded_json", json.dumps(counts, sort_keys=True)),
        ]
        if previous_epoch:
            meta.append(("previous_research_epoch_id", previous_epoch))
        conn.executemany("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)", meta)
        return {
            "epoch_id": epoch,
            "started_at_ms": now,
            "created": True,
            "discarded": counts,
            "previous_epoch_id": previous_epoch,
        }


def research_epoch(db_path) -> dict:
    with connect(db_path) as conn:
        rows = dict(
            conn.execute(
                "SELECT key,value FROM meta WHERE key IN ('research_epoch_id','research_epoch_started_at_ms','research_epoch_mode','preflight_discarded_json','previous_research_epoch_id')"
            ).fetchall()
        )
    return {
        "epoch_id": rows.get("research_epoch_id"),
        "started_at_ms": int(rows.get("research_epoch_started_at_ms", 0) or 0),
        "mode": rows.get("research_epoch_mode"),
        "preflight_discarded": json.loads(rows.get("preflight_discarded_json", "{}")),
        "previous_epoch_id": rows.get("previous_research_epoch_id"),
    }


def list_participants(db_path) -> list[dict]:
    with connect(db_path) as conn:
        rows = conn.execute(
            """SELECT p.participant_id, p.display_name, p.status, p.starting_equity,
                      p.equity, p.rank, p.role, s.spec_version, s.active_effect
               FROM participants p LEFT JOIN participant_specs s USING(participant_id)
               ORDER BY COALESCE(p.rank, 999), p.participant_id"""
        ).fetchall()
    return [dict(r) for r in rows]
