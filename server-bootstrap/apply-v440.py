from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "server-bootstrap/v440-src"

# Assemble the split futures module. The first published split contained one
# stray closing parenthesis at the start of part 01; remove it deterministically.
futures_parts = [(SRC / f"futures.{n:02d}").read_text() for n in range(5)]
if futures_parts[1].startswith(")\n"):
    futures_parts[1] = futures_parts[1][2:]
futures = "".join(futures_parts)
(SRC / "futures.py").write_text(futures)

# Assemble the original patch and make its notification replacement tolerant
# of the literal "\\n" present in Python source.
apply_code = "".join((SRC / f"apply.{n:02d}").read_text() for n in range(4))
old_helper = """    if old not in text:
        raise SystemExit(f"missing patch anchor in {path}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))
"""
new_helper = """    if old not in text:
        if path.endswith("paper.py") and "PumpRadar MC5 challenger" in old:
            title_old = '            f"🔵 PumpRadar MC5 challenger\\\\n{item.candidate.symbol}\\\\n"'
            title_new = '            f"🔵 PumpRadar {channel} spot paper\\\\n{item.candidate.symbol}\\\\n"'
            signal_old = '            f"return5m {item.candidate.return_5m:+.3f}% · entry {item.book.buy_vwap:.8g}\\\\n"'
            signal_new = '            f"signal {item.candidate.return_5m if channel == \\'MC5\\' else item.candidate.return_10m:+.3f}% · entry {item.book.buy_vwap:.8g}\\\\n"'
            if title_old not in text or signal_old not in text:
                raise SystemExit("missing tolerant MC5 notification anchors")
            p.write_text(text.replace(title_old, title_new, 1).replace(signal_old, signal_new, 1))
            return
        raise SystemExit(f"missing patch anchor in {path}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))
"""
if old_helper not in apply_code:
    raise SystemExit("missing replace_once helper anchor")
apply_code = apply_code.replace(old_helper, new_helper, 1)
exec(compile(apply_code, str(SRC / "apply.generated.py"), "exec"), globals())

# Record the spot signal arm directly on each momentum slot so MC5 and MC7
# results remain separable without joining back through snapshots.
replace_once(
    "pumpradar-server/pumpradar_server/storage.py",
    '''PAPER_SLOT_AUDIT_COLUMNS = {
    "episode_id": "TEXT",
}

SNAPSHOT_OUTCOME_AUDIT_COLUMNS = {''',
    '''PAPER_SLOT_AUDIT_COLUMNS = {
    "episode_id": "TEXT",
}

MOMENTUM_SLOT_AUDIT_COLUMNS = {
    "signal_channel": "TEXT NOT NULL DEFAULT 'MC5'",
}

SNAPSHOT_OUTCOME_AUDIT_COLUMNS = {''',
)
replace_once(
    "pumpradar-server/pumpradar_server/storage.py",
    '''            existing_outcome_columns = {
                row["name"]
                for row in self.conn.execute(
                    "PRAGMA table_info(snapshot_outcomes)"
                )
            }''',
    '''            existing_momentum_slot_columns = {
                row["name"]
                for row in self.conn.execute("PRAGMA table_info(momentum_slots)")
            }
            for name, data_type in MOMENTUM_SLOT_AUDIT_COLUMNS.items():
                if name not in existing_momentum_slot_columns:
                    self.conn.execute(
                        f"ALTER TABLE momentum_slots ADD COLUMN {name} {data_type}"
                    )
            existing_outcome_columns = {
                row["name"]
                for row in self.conn.execute(
                    "PRAGMA table_info(snapshot_outcomes)"
                )
            }''',
)
replace_once(
    "pumpradar-server/pumpradar_server/storage.py",
    '''    def create_momentum_slot(
        self,
        source_snapshot_id: str,
        symbol: str,
        episode_id: str,
        now_ms: int,
        best_ask: float,
        entry_vwap: float,
    ) -> str:''',
    '''    def create_momentum_slot(
        self,
        source_snapshot_id: str,
        symbol: str,
        episode_id: str,
        now_ms: int,
        best_ask: float,
        entry_vwap: float,
        signal_channel: str = "MC5",
    ) -> str:''',
)
replace_once(
    "pumpradar-server/pumpradar_server/storage.py",
    '''                  id,run_id,source_snapshot_id,episode_id,symbol,opened_at_ms,
                  entry_best_ask,entry_vwap,position_usdt,quantity,entry_fee_usdt,
                  primary_status,last_updated_at_ms,algorithm_version,
                  strategy_version,config_hash
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,'OPEN',?,?,?,?)''',
    '''                  id,run_id,source_snapshot_id,signal_channel,episode_id,symbol,opened_at_ms,
                  entry_best_ask,entry_vwap,position_usdt,quantity,entry_fee_usdt,
                  primary_status,last_updated_at_ms,algorithm_version,
                  strategy_version,config_hash
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'OPEN',?,?,?,?)''',
)
replace_once(
    "pumpradar-server/pumpradar_server/storage.py",
    '''                    source_snapshot_id,
                    episode_id,
                    symbol,''',
    '''                    source_snapshot_id,
                    signal_channel,
                    episode_id,
                    symbol,''',
)
replace_once(
    "pumpradar-server/pumpradar_server/paper.py",
    '''            item.book.best_ask,
            item.book.buy_vwap,
        )''',
    '''            item.book.best_ask,
            item.book.buy_vwap,
            channel,
        )''',
)
replace_once(
    "pumpradar-server/pumpradar_server/paper.py",
    '''                f"🔷 {slot['symbol']} MC5 {exit_reason}: net {net:+.3f}%"''',
    '''                f"🔷 {slot['symbol']} {slot['signal_channel']} {exit_reason}: net {net:+.3f}%"''',
)

# Expose separate closed-trade statistics for spot MC5/MC7 alongside futures.
replace_once(
    "pumpradar-server/pumpradar_server/futures.py",
    '''            by_channel = {
                str(row["signal_channel"]): {''',
    '''            spot_by_channel = {
                str(row["signal_channel"]): {
                    "n": int(row["n"]),
                    "avg_net": float(row["avg_net"] or 0.0),
                }
                for row in self.conn.execute(
                    """SELECT signal_channel, COUNT(*) n,
                              AVG(primary_net_return_percent) avg_net
                       FROM momentum_slots
                       WHERE primary_status='CLOSED'
                       GROUP BY signal_channel"""
                )
            }
            by_channel = {
                str(row["signal_channel"]): {''',
)
replace_once(
    "pumpradar-server/pumpradar_server/futures.py",
    '''            "futures_paper_open": open_count,
            "futures_closed_by_channel": by_channel,''',
    '''            "futures_paper_open": open_count,
            "spot_closed_by_channel": spot_by_channel,
            "futures_closed_by_channel": by_channel,''',
)

replace_once(
    "pumpradar-server/pumpradar_server/__init__.py",
    '__version__ = "4.3.9"',
    '__version__ = "4.4.0"',
)

# The inherited audit remains useful, but its release-version assertion must
# follow the release under test rather than remain pinned to v4.3.9.
replace_once(
    "pumpradar-server/tests/test_audit.py",
    '        self.assertEqual("4.3.9-server", self.settings.algorithm_version)',
    '        self.assertEqual("4.4.0-server", self.settings.algorithm_version)',
)
