from __future__ import annotations

import logging
import time
import uuid
from typing import Optional

from .config import Settings
from .market import MarketState
from .models import EvaluatedCandidate
from .storage import Storage
LOG = logging.getLogger(__name__)


def weakening_confirmed(flow, drawdown_percent: float, settings: Settings) -> bool:
    """Frozen PR #10 confirmation: any weak flow signal plus a 0.35% drawdown."""
    if flow is None or drawdown_percent < settings.weakening_drawdown_percent:
        return False
    return any([
        flow.taker_buy_ratio_5s is not None
        and flow.taker_buy_ratio_5s < settings.min_hold_tbr_5s,
        flow.taker_buy_ratio_15s is not None
        and flow.taker_buy_ratio_15s < settings.min_hold_tbr_15s,
        flow.taker_buy_ratio_30s is not None
        and flow.taker_buy_ratio_30s < settings.min_hold_tbr_30s,
        flow.cvd_slope <= 0,
    ])


def protected_floor_percent(peak_return_percent: float, settings: Settings) -> float:
    """Never protect below fees + buffer; retain at least half of the peak."""
    return max(
        settings.protected_stop_percent,
        peak_return_percent * settings.protected_peak_fraction,
    )


class PaperManager:
    def __init__(self, settings: Settings, market: MarketState, storage: Storage, notify) -> None:
        self.settings = settings
        self.market = market
        self.storage = storage
        self.notify = notify
        self.last_symbol_slot_ms: dict[str, int] = {}

    def repeat_blocked(self, symbol: str, now_ms: int) -> bool:
        previous = self.storage.last_slot_for_symbol(symbol)
        return bool(previous and now_ms - int(previous["opened_at_ms"]) < self.settings.repeat_symbol_minutes * 60_000)

    async def consider(self, item: EvaluatedCandidate, snapshot_id: str, now_ms: int) -> None:
        if not item.decision.strict_passed:
            return
        active = self.storage.baseline_open_slot()
        if active:
            self.storage.add_skipped(snapshot_id, item.candidate.symbol, "ONE_BASELINE_SLOT_BUSY", active["id"], now_ms)
            return
        if item.book.buy_vwap is None or item.book.best_ask is None:
            self.storage.add_skipped(snapshot_id, item.candidate.symbol, "NO_EXECUTABLE_ENTRY", None, now_ms)
            return
        event_id = str(uuid.uuid4())
        slot_id = self.storage.create_slot(
            snapshot_id, item.candidate.symbol, event_id, now_ms, item.book.best_ask, item.book.buy_vwap
        )
        await self.notify(
            f"🟢 PumpRadar paper slot\n{item.candidate.symbol}\nImpulse {item.decision.risk.impulse} · "
            f"entry {item.book.buy_vwap:.8g} · {self.settings.position_usdt:.2f} USDT\n"
            f"strict frozen config {self.settings.config_hash()}"
        )
        LOG.info("Opened slot %s %s", slot_id, item.candidate.symbol)

    async def tick(self, evaluated_by_symbol: dict[str, EvaluatedCandidate], now_ms: int) -> None:
        for slot in self.storage.open_slots():
            symbol = slot["symbol"]
            sell_price = self.market.executable_sell_price(
                symbol,
                float(slot["quantity"]),
                now_ms,
                self.settings.max_feed_age_ms,
            )
            if sell_price is None:
                continue
            entry = float(slot["entry_vwap"])
            current_return = (sell_price / entry - 1) * 100
            self.storage.update_slot_extremes(slot["id"], current_return, now_ms)
            item = evaluated_by_symbol.get(symbol)
            flow = item.flow if item else None
            for policy in self.storage.policies_for_slot(slot["id"]):
                if policy["state"] != "OPEN":
                    continue
                await self._update_policy(slot, policy, sell_price, current_return, flow, now_ms)

    async def _update_policy(self, slot, policy, sell_price: float, current_return: float, flow, now_ms: int) -> None:
        policy_name = policy["policy"]
        entry = float(slot["entry_vwap"])
        total_qty = float(slot["quantity"])
        age = (now_ms - int(slot["opened_at_ms"])) / 1000
        activated = policy["activated_at_ms"] is not None
        peak_return = max(float(policy["peak_return_percent"] or 0), current_return)
        updates = {"peak_return_percent": peak_return}

        if not activated and current_return >= self.settings.protection_activation_percent:
            updates["activated_at_ms"] = now_ms
            activated = True
            if policy_name == "A_PARTIAL_20":
                partial_qty = total_qty * self.settings.partial_fraction
                partial_price = self.market.executable_sell_price(
                    slot["symbol"],
                    partial_qty,
                    now_ms,
                    self.settings.max_feed_age_ms,
                )
                if partial_price is not None:
                    partial_quote = partial_qty * partial_price
                    updates.update({
                        "partial_quantity": partial_qty,
                        "partial_exit_vwap": partial_price,
                        "partial_exit_fee_usdt": partial_quote * self.settings.fee_rate,
                    })
            if policy_name == "A_PARTIAL_20":
                await self.notify(f"🛡 {slot['symbol']} reached +1%: policy A protection activated")

        exit_reason: Optional[str] = None
        if not activated and current_return <= -self.settings.initial_stop_percent:
            exit_reason = "INITIAL_STOP"
        elif current_return >= self.settings.target_percent:
            exit_reason = "TARGET_3"
        elif activated and current_return <= protected_floor_percent(peak_return, self.settings):
            exit_reason = "PROTECTED_EXIT"
        elif policy_name == "C_WEAKENING" and flow is not None:
            drawdown = peak_return - current_return
            weak = weakening_confirmed(flow, drawdown, self.settings)
            ticks = int(policy["weakening_ticks"] or 0)
            ticks = ticks + 1 if weak else 0
            updates["weakening_ticks"] = ticks
            if ticks >= self.settings.weakening_confirm_ticks:
                exit_reason = "EXIT_WEAKENING"
        if age >= self.settings.horizon_seconds:
            exit_reason = exit_reason or "HORIZON_EXIT"

        self.storage.update_policy(policy["id"], **updates)
        if exit_reason:
            partial_qty = float(updates.get("partial_quantity", policy["partial_quantity"] or 0))
            partial_price = updates.get("partial_exit_vwap", policy["partial_exit_vwap"])
            partial_fee = float(updates.get("partial_exit_fee_usdt", policy["partial_exit_fee_usdt"] or 0))
            remaining_qty = max(0.0, total_qty - partial_qty)
            remaining_price = (
                self.market.executable_sell_price(
                    slot["symbol"],
                    remaining_qty,
                    now_ms,
                    self.settings.max_feed_age_ms,
                )
                if remaining_qty > 0
                else sell_price
            )
            if remaining_price is None:
                return
            entry_cost = float(slot["position_usdt"])
            entry_fee = float(slot["entry_fee_usdt"])
            exit_quote = remaining_qty * remaining_price
            if partial_qty > 0 and partial_price:
                exit_quote += partial_qty * float(partial_price)
            exit_fee = remaining_qty * remaining_price * self.settings.fee_rate
            gross = (exit_quote / entry_cost - 1) * 100
            net = ((exit_quote - entry_fee - partial_fee - exit_fee) / entry_cost - 1) * 100
            self.storage.update_policy(
                policy["id"],
                state="CLOSED",
                closed_at_ms=now_ms,
                exit_reason=exit_reason,
                exit_vwap=remaining_price,
                exit_fee_usdt=exit_fee,
                gross_return_percent=gross,
                net_return_percent=net,
            )
            if policy_name == "A_PARTIAL_20":
                self.storage.close_baseline(slot["id"], now_ms, exit_reason, remaining_price, gross, net)
                await self.notify(f"⚪ {slot['symbol']} {exit_reason}: net {net:+.3f}%")
