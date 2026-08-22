import time
from dataclasses import dataclass
from .db import connect


@dataclass(frozen=True)
class Participant:
    participant_id: str
    display_name: str


PARTICIPANTS = (
    Participant("BTC_ALT_LAG", "BTC → ALT Lead/Lag"),
    Participant("REGIME_MOMENTUM", "Regime Momentum"),
    Participant("FLOW_ABSORPTION", "Flow / Absorption"),
    Participant("STAT_ARB", "Statistical Arbitrage"),
)


def seed(db_path) -> None:
    now = int(time.time() * 1000)
    with connect(db_path) as conn:
        for p in PARTICIPANTS:
            conn.execute(
                """INSERT OR IGNORE INTO participants
                (participant_id, display_name, status, starting_equity, equity, created_at_ms)
                VALUES (?, ?, 'ACTIVE', 20.0, 20.0, ?)""",
                (p.participant_id, p.display_name, now),
            )


def list_participants(db_path) -> list[dict]:
    with connect(db_path) as conn:
        rows = conn.execute(
            "SELECT participant_id, display_name, status, starting_equity, equity, rank, role FROM participants ORDER BY participant_id"
        ).fetchall()
    return [dict(r) for r in rows]
