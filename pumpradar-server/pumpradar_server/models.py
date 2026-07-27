from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional


@dataclass
class Candidate:
    symbol: str
    price: float
    quote_volume_24h: float
    return_15s: Optional[float]
    return_60s: Optional[float]
    return_5m: Optional[float]
    acceleration: Optional[float]
    relative_strength_vs_btc: Optional[float]
    pre_score: float
    price_age_ms: Optional[int] = None


@dataclass
class FlowMetrics:
    ready: bool = False
    quote_volume_30s: float = 0.0
    trade_count_30s: int = 0
    trades_per_second: float = 0.0
    taker_buy_ratio_30s: Optional[float] = None
    taker_buy_ratio_15s: Optional[float] = None
    taker_buy_ratio_5s: Optional[float] = None
    cvd_30s: float = 0.0
    cvd_15s: float = 0.0
    cvd_5s: float = 0.0
    cvd_slope: float = 0.0
    volume_z_30s: Optional[float] = None
    trade_gap: bool = False
    largest_trade_share: Optional[float] = None
    top3_trade_share: Optional[float] = None
    tiny_trade_share: Optional[float] = None
    spread_bps: Optional[float] = None
    trade_age_ms: Optional[int] = None
    book_ticker_age_ms: Optional[int] = None


@dataclass
class BookMetrics:
    spread_bps: Optional[float] = None
    obi_10: Optional[float] = None
    obi_20: Optional[float] = None
    buy_slippage_percent: Optional[float] = None
    sell_slippage_percent: Optional[float] = None
    buy_vwap: Optional[float] = None
    sell_vwap_for_position: Optional[float] = None
    best_bid: Optional[float] = None
    best_ask: Optional[float] = None
    bid_notional_top10: Optional[float] = None
    ask_notional_top10: Optional[float] = None
    depth_age_ms: Optional[int] = None
    depth_update_id: Optional[int] = None


@dataclass
class PeakFeatures:
    local_high_price: float = 0.0
    peak_at_ms: Optional[int] = None
    distance_from_local_high_pct: float = 0.0
    seconds_since_local_high: int = 0
    pullback_from_high_pct: float = 0.0
    failed_high_attempts: int = 0
    lower_high_detected: bool = False
    breakout_level_held: bool = True
    new_high_without_cvd_high: bool = False


@dataclass
class RiskAssessment:
    impulse: int
    entry_risk: int
    exhaustion_risk: int
    confidence: int
    artificial_risk: int
    market_wide_risk: int
    hard_veto: bool
    veto_reasons: list[str] = field(default_factory=list)


@dataclass
class Decision:
    label: str
    strict_passed: bool
    shadow_passed: bool
    technical_passed: bool
    blockers: list[str]
    reasons: list[str]
    risk: RiskAssessment
    liquidity_tier: str


@dataclass
class EvaluatedCandidate:
    candidate: Candidate
    flow: FlowMetrics
    book: BookMetrics
    peak: PeakFeatures
    decision: Decision
