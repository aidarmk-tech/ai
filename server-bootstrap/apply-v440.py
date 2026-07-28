#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing source pattern: {label}")
    return text.replace(old, new, 1)


config = Path("pumpradar-server/pumpradar_server/config.py")
text = config.read_text()
text = replace_once(
    text,
    'algorithm_version: str = "4.3.9-server"',
    'algorithm_version: str = "4.4.0-server"',
    "algorithm version",
)
text = replace_once(
    text,
    'strategy_version: str = "TRADE3-QUALITY+TARGET1-HOLD+MC5-HOLD120-2026-07"',
    'strategy_version: str = "TRADE3-QUALITY+TARGET1-HOLD+MC5-MC7-HOLD120-FROZEN100-2026-07"',
    "strategy version",
)
text = replace_once(
    text,
    '    momentum_primary_policy: str = "MC_HOLD_120"\n',
    '    momentum_primary_policy: str = "MC_HOLD_120"\n'
    '    freeze_primary_trade_target: int = 100\n',
    "freeze target",
)
config.write_text(text)

init = Path("pumpradar-server/pumpradar_server/__init__.py")
init.write_text(init.read_text().replace("4.3.9", "4.4.0"))

main = Path("pumpradar-server/pumpradar_server/main.py")
text = main.read_text()
text = replace_once(
    text,
    "        now = int(time.time() * 1000)\n        return {",
    "        now = int(time.time() * 1000)\n"
    "        progress = self.storage.frozen_primary_trade_progress()\n"
    "        return {",
    "health progress call",
)
text = replace_once(
    text,
    '            "uptime_seconds": (now - self.started_at_ms) // 1000,\n',
    '            "uptime_seconds": (now - self.started_at_ms) // 1000,\n'
    '            "freeze_closed_primary_trades": progress["closed"],\n'
    '            "freeze_target_primary_trades": progress["target"],\n'
    '            "freeze_remaining_primary_trades": progress["remaining"],\n',
    "health progress fields",
)
text = replace_once(
    text,
    '                "MC7": "MC7_SHADOW",',
    '                "MC7": "MC7_CHALLENGER",',
    "MC7 snapshot type",
)
text = replace_once(
    text,
    '            if arm == "MC5":',
    '            if arm in ("MC5", "MC7"):',
    "momentum channel condition",
)
text = replace_once(
    text,
    """                await self.momentum.consider(
                    item,
                    sid,
                    now_ms,
                    telemetry.episode_id,
                )""",
    """                await self.momentum.consider(
                    item,
                    sid,
                    now_ms,
                    telemetry.episode_id,
                    channel=arm,
                )""",
    "momentum manager call",
)
main.write_text(text)

paper = Path("pumpradar-server/pumpradar_server/paper.py")
text = paper.read_text()
head, separator, tail = text.partition("class MomentumPaperManager:")
if not separator:
    raise SystemExit("MomentumPaperManager not found")
tail = replace_once(
    tail,
    """    \"\"\"Independent MC5 one-slot paper challenger.

    The frozen TRADE3 slot remains untouched.  This manager owns a separate
    sequential slot and evaluates both the primary trailing policy and a fixed
    +4%/-2% control on the same executable entry.
    \"\"\"""",
    """    \"\"\"Shared-risk MC5/MC7 momentum paper channel.

    MC5 and MC7 are independent signal channels, but deliberately share one
    sequential execution slot so the upgrade cannot double correlated exposure.
    Each entry retains its source snapshot type for channel-level analysis.
    \"\"\"""",
    "momentum manager docstring",
)
tail = replace_once(
    tail,
    '        episode_id: str,\n    ) -> None:',
    '        episode_id: str,\n        channel: str = "MC5",\n    ) -> None:',
    "momentum channel argument",
)
tail = replace_once(tail, '"MC5_SLOT_BUSY"', 'f"{channel}_SLOT_BUSY"', "busy reason")
tail = replace_once(
    tail,
    '"MC5_REPEAT_SYMBOL_20M"',
    'f"{channel}_REPEAT_SYMBOL_20M"',
    "repeat reason",
)
tail = replace_once(
    tail,
    '"MC5_NO_EXECUTABLE_ENTRY"',
    'f"{channel}_NO_EXECUTABLE_ENTRY"',
    "entry reason",
)
tail = replace_once(
    tail,
    "        slot_id = self.storage.create_momentum_slot(",
    "        signal_return = (\n"
    "            item.candidate.return_5m\n"
    "            if channel == \"MC5\"\n"
    "            else item.candidate.return_10m\n"
    "        )\n"
    "        signal_window = \"5m\" if channel == \"MC5\" else \"10m\"\n"
    "        slot_id = self.storage.create_momentum_slot(",
    "channel telemetry",
)
tail = replace_once(
    tail,
    '            f"🔵 PumpRadar MC5 challenger\\n{item.candidate.symbol}\\n"\n'
    '            f"return5m {item.candidate.return_5m:+.3f}% · entry {item.book.buy_vwap:.8g}\\n"',
    '            f"🔵 PumpRadar {channel} challenger\\n{item.candidate.symbol}\\n"\n'
    '            f"return{signal_window} {float(signal_return or 0):+.3f}% · entry {item.book.buy_vwap:.8g}\\n"',
    "open notification",
)
tail = replace_once(
    tail,
    '        LOG.info("Opened momentum slot %s %s", slot_id, item.candidate.symbol)',
    '        LOG.info("Opened momentum slot %s %s channel=%s", slot_id, item.candidate.symbol, channel)',
    "open log",
)
tail = replace_once(
    tail,
    '                f"🔷 {slot[\'symbol\']} MC5 {exit_reason}: net {net:+.3f}%"',
    '                f"🔷 {slot[\'symbol\']} momentum {exit_reason}: net {net:+.3f}%"',
    "close notification",
)
paper.write_text(head + separator + tail)

storage = Path("pumpradar-server/pumpradar_server/storage.py")
text = storage.read_text()
text = replace_once(
    text,
    '"MC3_SHADOW", "MC5_CHALLENGER", "MC7_SHADOW"}',
    '"MC3_SHADOW", "MC5_CHALLENGER", "MC7_SHADOW", "MC7_CHALLENGER"}',
    "outcome snapshot types",
)
progress_method = """

    def frozen_primary_trade_progress(self) -> dict[str, int]:
        \"\"\"Count closed primary paper trades for the current frozen version.\"\"\"
        with self.lock:
            trade3 = int(self.conn.execute(
                \"SELECT COUNT(*) FROM paper_slots \"
                \"WHERE baseline_status='CLOSED' AND algorithm_version=?\",
                (self.settings.algorithm_version,),
            ).fetchone()[0])
            momentum = int(self.conn.execute(
                \"SELECT COUNT(*) FROM momentum_slots \"
                \"WHERE primary_status='CLOSED' AND algorithm_version=?\",
                (self.settings.algorithm_version,),
            ).fetchone()[0])
        closed = trade3 + momentum
        target = int(self.settings.freeze_primary_trade_target)
        return {
            \"closed\": closed,
            \"target\": target,
            \"remaining\": max(0, target - closed),
        }
"""
if "def frozen_primary_trade_progress" not in text:
    text += progress_method
storage.write_text(text)

readme = Path("pumpradar-server/README.md")
readme.write_text(
    readme.read_text()
    + """

## v4.4.0 frozen experiment

- MC5 remains unchanged.
- MC7 is promoted from shadow to a second momentum signal channel.
- MC5 and MC7 share one momentum execution slot to avoid doubled correlated exposure.
- TRADE3 thresholds, TBR gates, stop logic, and MC_HOLD_120 remain unchanged.
- No strategy upgrade is allowed until 100 primary paper trades close under 4.4.0.
- Real orders remain disabled.
"""
)
