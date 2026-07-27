from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import Optional

from .config import Settings
from .models import EvaluatedCandidate


@dataclass
class EpisodeState:
    episode_id: str
    symbol: str
    first_pre_candidate_at_ms: int
    episode_start_price: float
    last_seen_at_ms: int
    impulse_started_at_ms: Optional[int] = None
    strict_first_at_ms: Optional[int] = None
    last_strict_at_ms: Optional[int] = None
    strict_streak: int = 0


@dataclass(frozen=True)
class EpisodeTelemetry:
    episode_id: str
    first_pre_candidate_at_ms: int
    impulse_started_at_ms: Optional[int]
    strict_first_at_ms: Optional[int]
    impulse_age_ms: Optional[int]
    strict_streak: int
    momentum_persistent: bool
    experimental_shadow_passed: bool
    experimental_blockers: tuple[str, ...]
    episode_start_price: float
    extension_from_episode_start_pct: float


class EpisodeTracker:
    """Keep one stable ID and experimental state for a continuous impulse."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._states: dict[str, EpisodeState] = {}

    def observe(
        self,
        item: EvaluatedCandidate,
        now_ms: int,
    ) -> EpisodeTelemetry:
        symbol = item.candidate.symbol
        state = self._states.get(symbol)
        if (
            state is None
            or now_ms - state.last_seen_at_ms
            > self.settings.episode_reset_seconds * 1_000
        ):
            state = EpisodeState(
                episode_id=str(uuid.uuid4()),
                symbol=symbol,
                first_pre_candidate_at_ms=now_ms,
                episode_start_price=item.candidate.price,
                last_seen_at_ms=now_ms,
            )
            self._states[symbol] = state

        state.last_seen_at_ms = now_ms
        if (
            state.impulse_started_at_ms is None
            and item.decision.risk.impulse
            >= self.settings.episode_impulse_start_score
        ):
            state.impulse_started_at_ms = now_ms

        if item.decision.strict_passed:
            if state.strict_first_at_ms is None:
                state.strict_first_at_ms = now_ms
            if (
                state.last_strict_at_ms is not None
                and now_ms - state.last_strict_at_ms
                <= self.settings.strict_streak_max_gap_ms
            ):
                state.strict_streak += 1
            else:
                state.strict_streak = 1
            state.last_strict_at_ms = now_ms
        else:
            state.strict_streak = 0
            state.last_strict_at_ms = None

        r15 = item.candidate.return_15s
        r60 = item.candidate.return_60s
        momentum_persistent = (
            r15 is not None
            and r60 is not None
            and r60
            >= r15 - self.settings.max_shadow_return_15s_excess_over_60s
        )
        experimental_blockers: list[str] = []
        if not item.decision.strict_passed:
            experimental_blockers.append("STRICT_NOT_PASSED")
        if not momentum_persistent:
            experimental_blockers.append("MOMENTUM_NOT_PERSISTENT")
        if state.strict_streak < self.settings.min_shadow_strict_streak:
            experimental_blockers.append("STRICT_STREAK_LT_3")

        experimental_shadow_passed = (
            item.decision.strict_passed
            and not experimental_blockers
        )
        impulse_age_ms = (
            now_ms - state.impulse_started_at_ms
            if state.impulse_started_at_ms is not None
            else None
        )
        extension = (
            (item.candidate.price / state.episode_start_price - 1) * 100
            if state.episode_start_price > 0
            else 0.0
        )
        return EpisodeTelemetry(
            episode_id=state.episode_id,
            first_pre_candidate_at_ms=state.first_pre_candidate_at_ms,
            impulse_started_at_ms=state.impulse_started_at_ms,
            strict_first_at_ms=state.strict_first_at_ms,
            impulse_age_ms=impulse_age_ms,
            strict_streak=state.strict_streak,
            momentum_persistent=momentum_persistent,
            experimental_shadow_passed=experimental_shadow_passed,
            experimental_blockers=tuple(experimental_blockers),
            episode_start_price=state.episode_start_price,
            extension_from_episode_start_pct=extension,
        )

    def prune(self, now_ms: int) -> None:
        cutoff = now_ms - self.settings.episode_retention_seconds * 1_000
        self._states = {
            symbol: state
            for symbol, state in self._states.items()
            if state.last_seen_at_ms >= cutoff
        }
