from __future__ import annotations

import math
import statistics
from collections.abc import Sequence

EPSILON = 1e-9


def safe_divide(a: float, b: float) -> float | None:
    if not math.isfinite(a) or not math.isfinite(b) or abs(b) < EPSILON:
        return None
    value = a / b
    return value if math.isfinite(value) else None


def return_percent(now: float, past: float) -> float | None:
    ratio = safe_divide(now, past)
    return None if ratio is None else (ratio - 1.0) * 100.0


def linear_score(value: float, zero_at: float, full_at: float, max_points: float) -> float:
    if not math.isfinite(value):
        return 0.0
    if full_at == zero_at:
        return max_points if value >= full_at else 0.0
    t = (value - zero_at) / (full_at - zero_at)
    return min(1.0, max(0.0, t)) * max_points


def clamp_score(total: float) -> int:
    return int(min(100.0, max(0.0, total)))


def median(values: Sequence[float]) -> float:
    return float(statistics.median(values)) if values else 0.0


def mad(values: Sequence[float]) -> float:
    if not values:
        return 0.0
    m = median(values)
    return median([abs(v - m) for v in values])


def robust_z(current: float, history: Sequence[float]) -> float:
    if len(history) < 2:
        return 0.0
    m = median(history)
    deviation = mad(history)
    if deviation < EPSILON:
        return 0.0
    return 0.6745 * (current - m) / deviation
