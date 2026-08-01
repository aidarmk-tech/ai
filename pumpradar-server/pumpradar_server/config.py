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
    algorithm_version: str = "4.5.3.1-server"
    strategy_version: str = "REGIME-MC5-SHORT2X-EXIT-WINDOWS+ONSET-SHADOW-ONLY+DUMP-LONG-RR21-2026-08"
    rest_url: str = os.getenv("BINANCE_REST_URL", "https://api.binance.com")
    ws_url: str = os.getenv("BINANCE_WS_URL", "wss://stream.binance.com:9443")
    futures_rest_url: str = os.getenv("BINANCE_FUTURES_REST_URL", "https://fapi.binance.com")
    futures_ws_url: str = os.getenv("BINANCE_FUTURES_WS_URL", "wss://fstream.binance.com")
    data_dir: Path = Path(os.getenv("PUMPRADAR_DATA_DIR", "/var/lib/pumpradar"))
    db_path: Path = Path(os.getenv("PUMPRADAR_DB_PATH", "/var/lib/pumpradar/pumpradar.sqlite3"))
    bind_host: str = os.getenv("PUMPRADAR_BIND_HOST", "127.0.0.1")
    bind_port: int = _env_int("PUMPRADAR_BIND_PORT", 8787)
    api_token: str = os.getenv("PUMPRADAR_API_TOKEN", "")
    position_usdt: float = _env_float("PUMPRADAR_POSITION_USDT", 20.0)
    fee_rate: float = _env_float("PUMPRADAR_FEE_RATE", 0.001)
    futures_fee_rate: float = _env_float("PUMPRADAR_FUTURES_FEE_RATE", 0.0005)
    primary_policy: str = "C_WEAKENING"
    minimum_24h_quote_volume: float = _env_float("PUMPRADAR_MIN_24H_QUOTE_VOLUME", 1_000_000.0)
    max_candidates: int = _env_int("PUMPRADAR_MAX_CANDIDATES", 30)
    warm_pool_size: int = _env_int("PUMPRADAR_WARM_POOL_SIZE", 45)
    control_pool_size: int = _env_int("PUMPRADAR_CONTROL_POOL_SIZE", 0)
    control_rotation_seconds: int = _env_int("PUMPRADAR_CONTROL_ROTATION_SECONDS", 300)
    warm_refresh_seconds: int = _env_int("PUMPRADAR_WARM_REFRESH_SECONDS", 15)
    deep_candidates: int = _env_int("PUMPRADAR_DEEP_CANDIDATES", 15)
    depth_candidates: int = _env_int("PUMPRADAR_DEPTH_CANDIDATES", 20)
    futures_minimum_24h_quote_volume: float = _env_float("PUMPRADAR_FUTURES_MIN_24H_QUOTE_VOLUME", 1_000_000.0)
    futures_max_candidates: int = _env_int("PUMPRADAR_FUTURES_MAX_CANDIDATES", 30)
    futures_warm_pool_size: int = _env_int("PUMPRADAR_FUTURES_WARM_POOL_SIZE", 80)
    futures_deep_candidates: int = _env_int("PUMPRADAR_FUTURES_DEEP_CANDIDATES", 25)
    futures_depth_candidates: int = _env_int("PUMPRADAR_FUTURES_DEPTH_CANDIDATES", 30)
    freeze_target_primary_trades: int = _env_int("PUMPRADAR_FREEZE_TARGET_PRIMARY_TRADES", 100)
    # v4.5 lightweight regime paper experiment.
    regime_margin_usdt: float = _env_float("PUMPRADAR_REGIME_MARGIN_USDT", 20.0)
    regime_short_leverage: float = _env_float("PUMPRADAR_REGIME_SHORT_LEVERAGE", 2.0)
    regime_short_stop_percent: float = _env_float("PUMPRADAR_REGIME_SHORT_STOP_PERCENT", 2.0)
    regime_short_hold_seconds: int = _env_int("PUMPRADAR_REGIME_SHORT_HOLD_SECONDS", 600)
    regime_short_entry_wait_seconds: int = _env_int("PUMPRADAR_REGIME_SHORT_ENTRY_WAIT_SECONDS", 20)
    regime_repeat_symbol_minutes: int = _env_int("PUMPRADAR_REGIME_REPEAT_SYMBOL_MINUTES", 20)
    regime_max_spread_bps: float = _env_float("PUMPRADAR_REGIME_MAX_SPREAD_BPS", 30.0)
    regime_max_slippage_percent: float = _env_float("PUMPRADAR_REGIME_MAX_SLIPPAGE_PERCENT", 0.20)
    regime_warm_pool_size: int = _env_int("PUMPRADAR_REGIME_WARM_POOL_SIZE", 60)
    regime_depth_pool_size: int = _env_int("PUMPRADAR_REGIME_DEPTH_POOL_SIZE", 32)
    regime_decision_side_size: int = _env_int("PUMPRADAR_REGIME_DECISION_SIDE_SIZE", 18)
    regime_slots_per_channel: int = _env_int("PUMPRADAR_REGIME_SLOTS_PER_CHANNEL", 4)
    # v4.5.3 observation-only SHORT entry/exit challengers.
    short_continuation_veto_score: float = _env_float("PUMPRADAR_SHORT_CONTINUATION_VETO_SCORE", 60.0)
    short_reversal_confirm_score: float = _env_float("PUMPRADAR_SHORT_REVERSAL_CONFIRM_SCORE", 55.0)
    short_target1_percent: float = _env_float("PUMPRADAR_SHORT_TARGET1_PERCENT", 1.0)
    short_protected_floor_percent: float = _env_float("PUMPRADAR_SHORT_PROTECTED_FLOOR_PERCENT", 0.25)
    short_execution_failure_slippage_points: float = _env_float("PUMPRADAR_SHORT_EXECUTION_FAILURE_SLIPPAGE_POINTS", 0.50)
    exit_window_sample_seconds: int = _env_int("PUMPRADAR_EXIT_WINDOW_SAMPLE_SECONDS", 5)
    adaptive_protection_score: float = _env_float("PUMPRADAR_ADAPTIVE_PROTECTION_SCORE", 55.0)
    adaptive_giveback_weight: float = _env_float("PUMPRADAR_ADAPTIVE_GIVEBACK_WEIGHT", 30.0)
    adaptive_cvd_weight: float = _env_float("PUMPRADAR_ADAPTIVE_CVD_WEIGHT", 20.0)
    adaptive_obi_weight: float = _env_float("PUMPRADAR_ADAPTIVE_OBI_WEIGHT", 15.0)
    adaptive_taker_weight: float = _env_float("PUMPRADAR_ADAPTIVE_TAKER_WEIGHT", 20.0)
    adaptive_momentum_weight: float = _env_float("PUMPRADAR_ADAPTIVE_MOMENTUM_WEIGHT", 15.0)
    coverage_log_interval_seconds: int = _env_int("PUMPRADAR_COVERAGE_LOG_INTERVAL_SECONDS", 60)
    btc_regime_path: Path = Path(os.getenv("PUMPRADAR_BTC_REGIME_PATH", "/var/lib/pumpradar/btc-regime.json"))
    btc_regime_max_age_hours: float = _env_float("PUMPRADAR_BTC_REGIME_MAX_AGE_HOURS", 72.0)
    research_status_path: Path = Path(os.getenv("PUMPRADAR_RESEARCH_STATUS_PATH", "/var/lib/pumpradar/research/status.json"))
    onset_min_return_15s: float = _env_float("PUMPRADAR_ONSET_MIN_RETURN_15S", 0.35)
    onset_min_return_60s: float = _env_float("PUMPRADAR_ONSET_MIN_RETURN_60S", 0.70)
    onset_max_return_5m: float = _env_float("PUMPRADAR_ONSET_MAX_RETURN_5M", 2.50)
    onset_min_acceleration: float = _env_float("PUMPRADAR_ONSET_MIN_ACCELERATION", 0.20)
    onset_min_relative_strength: float = _env_float("PUMPRADAR_ONSET_MIN_RELATIVE_STRENGTH", 0.20)
    onset_min_volume_z: float = _env_float("PUMPRADAR_ONSET_MIN_VOLUME_Z", 1.50)
    onset_min_quote_volume_30s: float = _env_float("PUMPRADAR_ONSET_MIN_QUOTE_VOLUME_30S", 100000.0)
    onset_min_tbr_5s: float = _env_float("PUMPRADAR_ONSET_MIN_TBR_5S", 0.62)
    onset_min_score: int = _env_int("PUMPRADAR_ONSET_MIN_SCORE", 7)
    onset_confirm_ticks: int = _env_int("PUMPRADAR_ONSET_CONFIRM_TICKS", 3)
    onset_tracker_ttl_seconds: int = _env_int("PUMPRADAR_ONSET_TRACKER_TTL_SECONDS", 30)
    onset_stop_percent: float = _env_float("PUMPRADAR_ONSET_STOP_PERCENT", 1.50)
    onset_hold_seconds: int = _env_int("PUMPRADAR_ONSET_HOLD_SECONDS", 300)
    dump_max_return_15s: float = _env_float("PUMPRADAR_DUMP_MAX_RETURN_15S", -0.60)
    dump_max_return_60s: float = _env_float("PUMPRADAR_DUMP_MAX_RETURN_60S", -1.50)
    dump_max_return_5m: float = _env_float("PUMPRADAR_DUMP_MAX_RETURN_5M", -2.50)
    dump_max_acceleration: float = _env_float("PUMPRADAR_DUMP_MAX_ACCELERATION", -0.30)
    dump_max_relative_strength: float = _env_float("PUMPRADAR_DUMP_MAX_RELATIVE_STRENGTH", -0.50)
    dump_max_tbr_30s: float = _env_float("PUMPRADAR_DUMP_MAX_TBR_30S", 0.35)
    dump_min_volume_z: float = _env_float("PUMPRADAR_DUMP_MIN_VOLUME_Z", 2.0)
    dump_min_quote_volume_30s: float = _env_float("PUMPRADAR_DUMP_MIN_QUOTE_VOLUME_30S", 100000.0)
    dump_reclaim_percent: float = _env_float("PUMPRADAR_DUMP_RECLAIM_PERCENT", 0.35)
    dump_low_hold_seconds: int = _env_int("PUMPRADAR_DUMP_LOW_HOLD_SECONDS", 3)
    dump_reclaim_tbr_5s: float = _env_float("PUMPRADAR_DUMP_RECLAIM_TBR_5S", 0.58)
    dump_min_tbr_reversal: float = _env_float("PUMPRADAR_DUMP_MIN_TBR_REVERSAL", 0.12)
    dump_confirm_ticks: int = _env_int("PUMPRADAR_DUMP_CONFIRM_TICKS", 2)
    dump_tracker_ttl_seconds: int = _env_int("PUMPRADAR_DUMP_TRACKER_TTL_SECONDS", 120)
    dump_stop_percent: float = _env_float("PUMPRADAR_DUMP_STOP_PERCENT", 1.50)
    dump_hold_seconds: int = _env_int("PUMPRADAR_DUMP_HOLD_SECONDS", 600)
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
    episode_reset_seconds: int = 20 * 60
    episode_retention_seconds: int = 30 * 60

    # v4.3.9 TRADE3 entry-quality and exit challenger.
    trade3_thin_quote_volume_30s: float = 20_000.0
    trade3_very_thin_quote_volume_30s: float = 10_000.0
    trade3_weak_cvd_30s: float = 10_000.0
    trade3_weak_cvd_slope: float = 10_000.0
    trade3_bad_book_spread_bps: float = 15.0
    trade3_stale_book_defer_ms: int = 200
    trade3_stale_trade_defer_ms: int = 750
    trade3_stale_book_veto_ms: int = 500
    trade3_stale_trade_veto_ms: int = 1_500
    trade3_target1_hold_seconds: int = 300
    stop_watch_interval_ms: int = _env_int("PUMPRADAR_STOP_WATCH_INTERVAL_MS", 500)

    # v4.3.8 independent momentum-continuation challenger.
    momentum_mc3_return_3m: float = 3.0
    momentum_mc5_return_5m: float = 5.0
    momentum_mc7_return_10m: float = 7.0
    momentum_max_spread_bps: float = 30.0
    momentum_max_buy_slippage_percent: float = 0.15
    momentum_max_sell_slippage_percent: float = 0.35
    futures_momentum_max_spread_bps: float = _env_float("PUMPRADAR_FUTURES_MAX_SPREAD_BPS", 30.0)
    futures_momentum_max_buy_slippage_percent: float = _env_float("PUMPRADAR_FUTURES_MAX_BUY_SLIPPAGE_PERCENT", 0.15)
    futures_momentum_max_sell_slippage_percent: float = _env_float("PUMPRADAR_FUTURES_MAX_SELL_SLIPPAGE_PERCENT", 0.35)
    momentum_stop_percent: float = 2.0
    momentum_trail_activation_percent: float = 1.5
    momentum_trail_drawdown_percent: float = 1.0
    momentum_fixed_target_percent: float = 4.0
    momentum_horizon_seconds: int = 20 * 60
    momentum_hold_seconds: int = 120
    momentum_repeat_symbol_minutes: int = 20
    momentum_primary_policy: str = "MC_HOLD_120"
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
            "rest_url", "ws_url", "futures_rest_url", "futures_ws_url", "data_dir", "db_path", "bind_host", "bind_port",
            "api_token", "telegram_bot_token", "telegram_chat_id", "export_interval_minutes",
            "export_keep_count", "export_max_total_mb",
            "max_candidates", "warm_pool_size", "control_pool_size", "control_rotation_seconds",
            "warm_refresh_seconds", "deep_candidates", "depth_candidates",
            "report_timezone_offset_minutes", "stop_watch_interval_ms",
        }
        payload = {k: v for k, v in asdict(self).items() if k not in excluded}
        payload = {k: str(v) if isinstance(v, Path) else v for k, v in payload.items()}
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(canonical.encode()).hexdigest()[:16]
