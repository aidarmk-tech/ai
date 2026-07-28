from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def replace(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"missing patch anchor in {path}: {old[:80]!r}")
    p.write_text(text.replace(old, new, 1))

replace(
    "pumpradar-server/pumpradar_server/config.py",
    'algorithm_version: str = "4.3.9-server"\n    strategy_version: str = "TRADE3-QUALITY+TARGET1-HOLD+MC5-HOLD120-2026-07"',
    'algorithm_version: str = "4.4.0-server"\n    strategy_version: str = "FROZEN100+MC5-HOLD120+MC7-PAPER-2026-07"',
)

replace(
    "pumpradar-server/pumpradar_server/main.py",
    '            if arm == "MC5":\n                await self.momentum.consider(\n                    item,\n                    sid,\n                    now_ms,\n                    telemetry.episode_id,\n                )',
    '            if arm in ("MC5", "MC7"):\n                await self.momentum.consider(\n                    item,\n                    sid,\n                    now_ms,\n                    telemetry.episode_id,\n                    arm,\n                )',
)

replace(
    "pumpradar-server/pumpradar_server/paper.py",
    '        episode_id: str,\n    ) -> None:\n        active = self.storage.momentum_primary_open_slot()',
    '        episode_id: str,\n        channel: str = "MC5",\n    ) -> None:\n        active = self.storage.momentum_primary_open_slot()',
)
replace("pumpradar-server/pumpradar_server/paper.py", '"MC5_SLOT_BUSY"', 'f"{channel}_SLOT_BUSY"')
replace("pumpradar-server/pumpradar_server/paper.py", '"MC5_REPEAT_SYMBOL_20M"', 'f"{channel}_REPEAT_SYMBOL_20M"')
replace("pumpradar-server/pumpradar_server/paper.py", '"MC5_NO_EXECUTABLE_ENTRY"', 'f"{channel}_NO_EXECUTABLE_ENTRY"')
replace(
    "pumpradar-server/pumpradar_server/paper.py",
    '            f"🔵 PumpRadar MC5 challenger\\n{item.candidate.symbol}\\n"\n            f"return5m {item.candidate.return_5m:+.3f}% · entry {item.book.buy_vwap:.8g}\\n"',
    '            f"🔵 PumpRadar {channel} paper\\n{item.candidate.symbol}\\n"\n            f"signal {item.candidate.return_5m if channel == \'MC5\' else item.candidate.return_10m:+.3f}% · entry {item.book.buy_vwap:.8g}\\n"',
)
replace(
    "pumpradar-server/pumpradar_server/paper.py",
    '        LOG.info("Opened momentum slot %s %s", slot_id, item.candidate.symbol)',
    '        LOG.info("Opened %s momentum slot %s %s", channel, slot_id, item.candidate.symbol)',
)

readme = ROOT / "pumpradar-server/README.md"
readme.write_text(readme.read_text() + "\n\n## v4.4.0 freeze\nMC5 is unchanged. MC7 may open the same executable paper-policy set (stop 2%, hold 120s, trail and TP4 controls). The configuration is frozen until 100 closed primary momentum slots are collected.\n")
