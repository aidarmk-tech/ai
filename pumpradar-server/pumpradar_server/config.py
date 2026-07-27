from __future__ import annotations

import hashlib
import json
import os
from dataclasses import asdict, dataclass
from pathlib import Path


def _env_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    return default if raw in (None, "") else float(raw)


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    return default if raw in (None, "") else int(raw)


@dataclass(frozen=True)
class Settings:
    algorithm_version: str = "4.3.7-server"
    strategy_version: str = "TRADE3-V437-SHADOW-AUDIT-2026-07"
    rest_url: str = os.getenv("BINANCE_REST_URL", "https://api.binance.com")
    ws_url: str = os.getenv("BINANCE_WS_URL", "wss://stream.binance.com:9443")
    data_dir: Path = Path(os.getenv("PUMPRADAR_DATA_DIR", "/var/lib/pumpradar"))
    db_path: Path = Path(os.getenv("PUMPRADAR_DB_PATH", "/var/lib/pumpradar/pumpradar.sqlite3"))
    bind_host: str = os.getenv("PUMPRADAR_BIND_HOST", "127.0.0.1")
    bind_port: int = _env_int("PUMPRADAR_BIND_PORT", 8787)
    api_token: str = os.getenv("PUMPRADAR_API_TOKEN", "")
    position_usdt: float = _env_float("PUMPRADAR_POSITION_USDT", 20.0)
    fee_rate: float = _env_float("PUMPRADAR_FEE_RATE", 0.001)
    primary_policy: str = "C_WEAKENING"
    minimum_24h_quote_volume: float = _env_float("PUMPRADAR_MIN_24H_QUOTE_VOLUME", 5_000_000.0)
    max_candidates: int = _env_int("PUMPRADAR_MAX_CANDIDATES", 20)
    warm_pool_size: int = _env_int("PUMPRADAR_WARM_POOL_SIZE", 60)
    control_pool_size: int = _env_int("PUMPRADAR_CONTROL_POOL_SIZE", 5)
    control_rotation_seconds: int = _env_int("PUMPRADAR_CONTROL_ROTATION_SECONDS", 300)
    warm_refresh_seconds: int = _env_int("PUMPRADAR_WARM_REFRESH_SECONDS", 15)
    deep_candidates: int = _env_int("PUMPRADAR_DEEP_CANDIDATES", 15)
    depth_candidates: int = _env_int("PUMPRADAR_DEPTH_CANDIDATES", 20)
    export_interval_minutes: int = _env_int("PUMPRADAR_EXPORT_INTERVAL_MINUTES", 60)
    export_keep_count: int = _env_int("PUMPRADAR_EXPORT_KEEP_COUNT", 48)
    export_max_total_mb: int = _env_int("PUMPRADAR_EXPORT_MAX_TOTAL_MB", 2_048)
    snapshot_near_miss_seconds: int = _env_int("PUMPRADAR_NEAR_MISS_SECONDS", 45)
    snapshot_random_seconds: int = _env_int("PUMPRADAR_RANDOM_SECONDS", 30)
    report_timezone_offset_minutes: int = _env_int(
        "PUMPRADAR_REPORT_TIMEZONE_OFFSET_MINUTES", 300
    )
    telegram_bot_token: str = os.getenv("TELEGRAM_BOT_TOKEN", "")
    telegram_chat_id: str = os.getenv("TELEGRAM_CHAT_ID", "")

    # Frozen v4.3.6 paper gate retained unchanged in v4.3.7.
    min_taker_buy_ratio_30s: float = 0.875
    min_trade3_taker_buy_ratio_15s: float = 0.90
    min_trade3_taker_buy_ratio_5s: float = 0.75
    min_return_15s: float = 0.70
    min_return_60s: float = 0.80
    max_return_5m: float = 3.00
    min_relative_strength_vs_btc: float = 0.0
    min_retest_pullback_percent: float = 0.80
    max_retest_pullback_percent: float = 3.50
    min_retest_recovery_percent: float = 0.35
    min_retest_taker_buy_ratio_30s: float = 0.65
    min_retest_return_15s: float = 0.15
    max_retest_distance_from_high_percent: float = 2.50
    # Exclusive strict boundary: impulse must be greater than 63.
    min_trade3_impulse_score: int = 63
    max_trade3_entry_risk: int = 35
    max_trade3_exhaustion_risk: int = 0
    max_trade3_artificial_risk: int = 0
    max_trade3_spread_bps: float = 30.0
    max_trade3_slippage_percent: float = 0.15
    min_shadow_impulse_score: int = 60
    min_shadow_return_15s: float = 0.70
    min_shadow_return_60s: float = 0.50
    min_shadow_return_5m: float = 0.50
    max_shadow_return_5m: float = 2.50
    min_shadow_taker_buy_ratio_30s: float = 0.72
    max_shadow_distance_from_high_percent: float = 0.75
    max_shadow_entry_risk: int = 35
    max_shadow_exhaustion_risk: int = 20
    max_shadow_spread_bps: float = 30.0
    max_shadow_slippage_percent: float = 0.15
    # v4.3.7 experimental measurements. These never block the frozen strict gate.
    max_shadow_return_15s_excess_over_60s: float = 0.05
    min_shadow_strict_streak: int = 3
    strict_streak_max_gap_ms: int = 2_500
    episode_impulse_start_score: int = 40
    episode_reset_seconds: int = 60
    episode_retention_seconds: int = 15 * 60
    target_percent: float = 3.0
    initial_stop_percent: float = 0.75
    protection_activation_percent: float = 1.0
    # Gross executable floor: 0.20% round-trip fee + 0.10% safety buffer.
    protected_stop_percent: float = 0.30
    protected_peak_fraction: float = 0.50
    partial_fraction: float = 0.20
    horizon_seconds: int = 15 * 60
    # Must match the live TRADE_3 observer in PR #10.
    weakening_drawdown_percent: float = 0.35
    weakening_confirm_ticks: int = 2
    min_hold_tbr_30s: float = 0.65
    min_hold_tbr_15s: float = 0.60
    min_hold_tbr_5s: float = 0.55
    max_feed_age_ms: int = 10_000
    max_spread_bps: float = 40.0
    max_slippage_percent: float = 0.35
    extreme_volume_z: float = 25.0
    min_taker_buy_for_extreme_volume: float = 0.80
    max_distance_from_high_percent: float = 0.35
    max_failed_high_attempts: int = 2
    repeat_symbol_minutes: int = 30

    def config_hash(self) -> str:
        excluded = {
            "rest_url", "ws_url", "data_dir", "db_path", "bind_host", "bind_port",
            "api_token", "telegram_bot_token", "telegram_chat_id", "export_interval_minutes",
            "export_keep_count", "export_max_total_mb",
            "max_candidates", "warm_pool_size", "control_pool_size", "control_rotation_seconds",
            "warm_refresh_seconds", "deep_candidates", "depth_candidates",
            "report_timezone_offset_minutes",
        }
        payload = {k: v for k, v in asdict(self).items() if k not in excluded}
        payload = {k: str(v) if isinstance(v, Path) else v for k, v in payload.items()}
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(canonical.encode()).hexdigest()[:16]
