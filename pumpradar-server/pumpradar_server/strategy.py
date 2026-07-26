from __future__ import annotations

import math
from dataclasses import dataclass

from .config import Settings
from .mathutil import clamp_score, linear_score
from .models import (
    BookMetrics,
    Candidate,
    Decision,
    FlowMetrics,
    PeakFeatures,
    RiskAssessment,
)


def liquidity_tier(quote_volume_24h: float) -> str:
    if quote_volume_24h >= 100_000_000:
        return "A"
    if quote_volume_24h >= 20_000_000:
        return "B"
    if quote_volume_24h >= 5_000_000:
        return "C"
    return "D"


def buyer_pressure_declining(flow: FlowMetrics, eps: float = 0.01) -> bool:
    if flow.taker_buy_ratio_5s is None or flow.taker_buy_ratio_15s is None or flow.taker_buy_ratio_30s is None:
        return False
    fading = (
        flow.taker_buy_ratio_5s < flow.taker_buy_ratio_15s - eps
        and flow.taker_buy_ratio_15s < flow.taker_buy_ratio_30s - eps
    )
    cvd_fading = flow.cvd_30s > 0 and flow.cvd_5s < 0
    return fading or (flow.taker_buy_ratio_5s < flow.taker_buy_ratio_30s - eps and cvd_fading)


def artificial_risk(flow: FlowMetrics, return_60s: float) -> int:
    risk = 0.0
    if flow.trade_count_30s >= 30 and (flow.tiny_trade_share or 0) >= 0.80:
        risk += 35
    if (flow.largest_trade_share or 0) >= 0.45:
        risk += 30
    if (flow.top3_trade_share or 0) >= 0.75:
        risk += 25
    if return_60s >= 1.0 and flow.trade_count_30s <= 5:
        risk += 20
    return clamp_score(risk)


def market_wide_risk(return_60s: float, median_return_60s: float, breadth_positive: float) -> int:
    risk = 0.0
    if breadth_positive >= 0.80:
        risk += 35
    elif breadth_positive >= 0.65:
        risk += 20
    if median_return_60s >= 0.60:
        risk += 30
    elif median_return_60s >= 0.30:
        risk += 15
    if return_60s - median_return_60s < 0.20:
        risk += 20
    return clamp_score(risk)


def impulse_score(c: Candidate, flow: FlowMetrics, book: BookMetrics) -> int:
    r15 = c.return_15s or 0.0
    r60 = c.return_60s or 0.0
    accel = c.acceleration or 0.0
    r5m = c.return_5m or 0.0
    total = 0.0
    price = linear_score(r15, 0.20, 1.20, 8.0) + linear_score(r60, 0.50, 3.00, 8.0) + linear_score(accel, 0.10, 0.80, 4.0)
    total += min(20.0, price)
    if flow.volume_z_30s is not None:
        volume = linear_score(flow.volume_z_30s, 1.5, 6.0, 14.0)
        volume += linear_score(flow.quote_volume_30s, 5_000.0, 200_000.0, 6.0)
        total += min(20.0, volume)
    intensity = linear_score(flow.trades_per_second, 0.5, 5.0, 7.0) + linear_score(float(flow.trade_count_30s), 10, 120, 3.0)
    total += min(10.0, intensity)
    if flow.taker_buy_ratio_30s is not None:
        buyer_flow = linear_score(flow.taker_buy_ratio_30s, 0.52, 0.75, 10.0)
        if flow.cvd_slope > 0:
            buyer_flow += 5
        total += min(15.0, buyer_flow)
    if book.obi_10 is not None:
        orderbook = linear_score(book.obi_10, 0.0, 0.60, 7.0)
        orderbook += linear_score(book.spread_bps or 999, 40.0, 10.0, 4.0)
        if book.buy_slippage_percent is not None:
            orderbook += linear_score(book.buy_slippage_percent, 0.80, 0.10, 4.0)
        total += min(15.0, orderbook)
    if 1.0 <= r5m <= 8.0:
        total += linear_score(r5m, 1.0, 4.0, 4.0)
    if c.relative_strength_vs_btc is not None:
        total += linear_score(c.relative_strength_vs_btc, 0.20, 2.00, 3.0)
    total += 5.0 if flow.ready and book.obi_10 is not None else 3.0
    if flow.taker_buy_ratio_30s is not None and flow.taker_buy_ratio_30s < 0.52:
        total -= 10
    if book.spread_bps is not None and book.spread_bps >= 50:
        total -= 10
    if book.spread_bps is not None and book.spread_bps >= 100:
        total -= 15
    if r60 > 0 and flow.cvd_slope < 0:
        total -= 12
    if r5m >= 8:
        total -= 10
    if r5m >= 12:
        total -= 10
    if flow.volume_z_30s is None:
        total = min(total, 69)
    return clamp_score(total)


def entry_risk(c: Candidate, book: BookMetrics, peak: PeakFeatures, repeat_blocked: bool) -> int:
    r5m = c.return_5m or 0.0
    risk = 0.0 if r5m < 1.5 else 10.0 if r5m < 3.0 else 20.0 if r5m < 6.0 else 30.0
    risk += min(20.0, max(0.0, peak.distance_from_local_high_pct * 20.0))
    risk += min(15.0, max(0.0, peak.pullback_from_high_pct * 10.0))
    if 1 <= peak.seconds_since_local_high <= 300:
        risk += 5.0
    spread = book.spread_bps or 0.0
    risk += min(15.0, max(0.0, (spread - 15.0) / 105.0 * 15.0))
    risk += 15.0 if book.buy_slippage_percent is None else min(15.0, max(0.0, book.buy_slippage_percent * 15.0))
    if repeat_blocked:
        risk += 10
    return clamp_score(risk)


def exhaustion_risk(c: Candidate, flow: FlowMetrics, peak: PeakFeatures, settings: Settings) -> int:
    risk = 0.0
    declining = buyer_pressure_declining(flow)
    if declining:
        risk += 25
    if (c.return_60s or 0) > 0 and flow.cvd_slope < 0:
        risk += 20
    if peak.new_high_without_cvd_high:
        risk += 20
    if not peak.breakout_level_held:
        risk += 15
    if peak.lower_high_detected:
        risk += 15
    if (flow.spread_bps or 0) > 60:
        risk += 10
    if (c.return_5m or 0) >= 8:
        risk += 15
    if (flow.volume_z_30s or 0) > settings.extreme_volume_z and (c.return_15s or 0) < settings.min_return_15s:
        risk += 15
    return clamp_score(risk)


def confidence_score(c: Candidate, flow: FlowMetrics, book: BookMetrics, feed_age_ms: int) -> int:
    price = 15 if c.return_5m is not None else 8 if c.return_60s is not None else 0
    trades = 20 if flow.ready and flow.volume_z_30s is not None else 10 if flow.trade_count_30s >= 3 else 4 if flow.trade_count_30s else 0
    depth = 15 if book.buy_slippage_percent is not None else 8 if book.obi_10 is not None else 0
    freshness = 20 if feed_age_ms <= 3_000 else int(linear_score(float(feed_age_ms), 10_000, 3_000, 20)) if feed_age_ms <= 10_000 else 4
    no_gaps = 0 if flow.trade_gap else 10
    confirms = sum([
        (c.return_15s or 0) > 0 or (c.return_60s or 0) > 0 or (c.acceleration or 0) > 0,
        flow.ready and (flow.taker_buy_ratio_30s or 0) >= 0.52 and flow.volume_z_30s is not None,
        book.buy_slippage_percent is not None and (book.spread_bps or 999) < 60,
    ])
    return clamp_score(price + trades + depth + freshness + no_gaps + confirms / 3 * 10 + 3)


def assess(
    c: Candidate,
    flow: FlowMetrics,
    book: BookMetrics,
    peak: PeakFeatures,
    settings: Settings,
    feed_age_ms: int,
    median_return_60s: float,
    breadth_positive: float,
    repeat_blocked: bool,
) -> Decision:
    impulse = impulse_score(c, flow, book)
    entry = entry_risk(c, book, peak, repeat_blocked)
    exhaustion = exhaustion_risk(c, flow, peak, settings)
    confidence = confidence_score(c, flow, book, feed_age_ms)
    artificial = artificial_risk(flow, c.return_60s or 0.0)
    market_risk = market_wide_risk(c.return_60s or 0.0, median_return_60s, breadth_positive)
    blockers: list[str] = []
    reasons: list[str] = []
    r15, r60, r5m = c.return_15s, c.return_60s, c.return_5m
    tbr = flow.taker_buy_ratio_30s
    declining = buyer_pressure_declining(flow)
    data_fresh = feed_age_ms <= settings.max_feed_age_ms
    absorption = (flow.volume_z_30s or 0) > settings.extreme_volume_z and (
        (r15 or 0) < settings.min_return_15s or (tbr or 0) < settings.min_taker_buy_for_extreme_volume
    )
    veto_reasons: list[str] = []
    if not data_fresh:
        veto_reasons.append("STALE_FEED")
    if flow.trade_gap:
        veto_reasons.append("TRADE_GAP")
    if artificial >= 70:
        veto_reasons.append("ARTIFICIAL_RISK_GE_70")
    if market_risk >= 70:
        veto_reasons.append("MARKET_WIDE_RISK_GE_70")
    if book.buy_slippage_percent is None:
        veto_reasons.append("INSUFFICIENT_BUY_DEPTH")
    if book.sell_slippage_percent is None:
        veto_reasons.append("INSUFFICIENT_SELL_DEPTH")
    hard_veto = bool(veto_reasons)

    liquidity_ok = data_fresh and book.spread_bps is not None and book.spread_bps <= settings.max_spread_bps and book.buy_slippage_percent is not None and book.buy_slippage_percent <= settings.max_slippage_percent
    strict_gate = all([
        r15 is not None and r15 >= settings.min_return_15s,
        r60 is not None and r60 >= settings.min_return_60s,
        r5m is not None and r5m <= settings.max_return_5m,
        tbr is not None and tbr >= settings.min_taker_buy_ratio_30s,
        flow.cvd_30s > 0,
        flow.cvd_slope > 0,
        c.relative_strength_vs_btc is not None and c.relative_strength_vs_btc > settings.min_relative_strength_vs_btc,
        liquidity_ok,
        not absorption,
        peak.distance_from_local_high_pct <= settings.max_distance_from_high_percent,
        not peak.lower_high_detected,
        peak.breakout_level_held,
        peak.failed_high_attempts < settings.max_failed_high_attempts,
        not hard_veto,
    ])
    recovered = peak.pullback_from_high_pct - peak.distance_from_local_high_pct
    retest_gate = all([
        impulse >= 55,
        settings.min_retest_pullback_percent <= peak.pullback_from_high_pct <= settings.max_retest_pullback_percent,
        0.15 <= peak.distance_from_local_high_pct <= settings.max_retest_distance_from_high_percent,
        recovered >= settings.min_retest_recovery_percent,
        r15 is not None and r15 >= settings.min_retest_return_15s,
        r5m is not None and r5m <= settings.max_return_5m,
        tbr is not None and tbr >= settings.min_retest_taker_buy_ratio_30s,
        flow.cvd_30s > 0,
        flow.cvd_slope > 0,
        c.relative_strength_vs_btc is not None and c.relative_strength_vs_btc > settings.min_relative_strength_vs_btc,
        liquidity_ok,
        not declining,
        not peak.new_high_without_cvd_high,
        not peak.lower_high_detected,
        peak.breakout_level_held,
        peak.failed_high_attempts < settings.max_failed_high_attempts,
        not hard_veto,
    ])
    technical = strict_gate or retest_gate

    quality_checks = {
        "IMPULSE_LT_60": impulse >= settings.min_trade3_impulse_score,
        "ENTRY_RISK_GT_35": entry <= settings.max_trade3_entry_risk,
        "EXHAUSTION_GT_20": exhaustion <= settings.max_trade3_exhaustion_risk,
        "SPREAD_MISSING": book.spread_bps is not None,
        "SPREAD_GT_30": book.spread_bps is not None and book.spread_bps <= settings.max_trade3_spread_bps,
        "SLIPPAGE_MISSING": book.buy_slippage_percent is not None,
        "SLIPPAGE_GT_0_15": book.buy_slippage_percent is not None and book.buy_slippage_percent <= settings.max_trade3_slippage_percent,
    }
    blockers.extend([name for name, passed in quality_checks.items() if not passed])
    blockers.extend(veto_reasons)
    strict_passed = technical and all(quality_checks.values()) and not hard_veto and not repeat_blocked

    shadow_passed = all([
        not hard_veto,
        not repeat_blocked,
        impulse >= settings.min_shadow_impulse_score,
        entry <= settings.max_shadow_entry_risk,
        exhaustion <= settings.max_shadow_exhaustion_risk,
        r15 is not None and r15 >= settings.min_shadow_return_15s,
        r60 is not None and r60 >= settings.min_shadow_return_60s,
        r5m is not None and settings.min_shadow_return_5m <= r5m <= settings.max_shadow_return_5m,
        tbr is not None and tbr >= settings.min_shadow_taker_buy_ratio_30s,
        flow.cvd_30s > 0,
        flow.cvd_slope > 0,
        c.relative_strength_vs_btc is not None and c.relative_strength_vs_btc > settings.min_relative_strength_vs_btc,
        data_fresh,
        book.spread_bps is not None and book.spread_bps <= settings.max_shadow_spread_bps,
        book.buy_slippage_percent is not None and book.buy_slippage_percent <= settings.max_shadow_slippage_percent,
        not declining,
        not peak.new_high_without_cvd_high,
        peak.distance_from_local_high_pct <= settings.max_shadow_distance_from_high_percent,
        not peak.lower_high_detected,
        peak.breakout_level_held,
        peak.failed_high_attempts < settings.max_failed_high_attempts,
    ])
    if strict_passed:
        label = "LONG_CONTINUATION"
        reasons.append("FROZEN_STRICT_TRADE3")
    elif shadow_passed:
        label = "TRADE3_SHADOW"
        reasons.append("FROZEN_SHADOW_ONLY")
    elif exhaustion >= 70:
        label = "EXHAUSTION_RISK"
    elif impulse >= 70:
        label = "STRONG_BUT_LATE"
    else:
        label = "NO_TRADE"
    risk = RiskAssessment(impulse, entry, exhaustion, confidence, artificial, market_risk, hard_veto, veto_reasons)
    return Decision(label, strict_passed, shadow_passed, technical, sorted(set(blockers)), reasons, risk, liquidity_tier(c.quote_volume_24h))
