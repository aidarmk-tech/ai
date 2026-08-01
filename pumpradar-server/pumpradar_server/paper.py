from __future__ import annotations

import logging
import time
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

    async def consider(
        self,
        item: EvaluatedCandidate,
        snapshot_id: str,
        now_ms: int,
        episode_id: str,
    ) -> None:
        if not item.decision.strict_passed:
            return
        active = self.storage.baseline_open_slot()
        if active:
            self.storage.add_skipped(snapshot_id, item.candidate.symbol, "ONE_BASELINE_SLOT_BUSY", active["id"], now_ms)
            return
        if item.book.buy_vwap is None or item.book.best_ask is None:
            self.storage.add_skipped(snapshot_id, item.candidate.symbol, "NO_EXECUTABLE_ENTRY", None, now_ms)
            return
        slot_id = self.storage.create_slot(
            snapshot_id,
            item.candidate.symbol,
            episode_id,
            now_ms,
            item.book.best_ask,
            item.book.buy_vwap,
            episode_id,
        )
        await self.notify(
            f"🟢 PumpRadar paper slot\n{item.candidate.symbol}\nImpulse {item.decision.risk.impulse} · "
            f"entry {item.book.buy_vwap:.8g} · {self.settings.position_usdt:.2f} USDT\n"
            f"strict frozen · {self.settings.algorithm_version} · "
            f"primary {self.settings.primary_policy} · "
            f"config {self.settings.config_hash()}"
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
        if policy_name == "D_TARGET1_HOLD_300":
            if not activated and current_return <= -self.settings.initial_stop_percent:
                exit_reason = "INITIAL_STOP"
            elif activated and current_return <= self.settings.protected_stop_percent:
                exit_reason = "TARGET1_PROTECTED_FLOOR"
            elif age >= self.settings.trade3_target1_hold_seconds:
                exit_reason = "TARGET1_HOLD_300"
        else:
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
            LOG.info(
                "Closed slot %s policy %s: %s net %+.3f%%",
                slot["id"],
                policy_name,
                exit_reason,
                net,
            )
            if policy_name == "A_PARTIAL_20":
                self.storage.close_baseline(slot["id"], now_ms, exit_reason, remaining_price, gross, net)
            if policy_name == self.settings.primary_policy:
                await self.notify(
                    f"🟣 {slot['symbol']} {policy_name} primary\n"
                    f"{exit_reason}: net {net:+.3f}%"
                )


class MomentumPaperManager:
    """Independent MC5 one-slot paper challenger.

    The frozen TRADE3 slot remains untouched.  This manager owns a separate
    sequential slot and evaluates both the primary trailing policy and a fixed
    +4%/-2% control on the same executable entry.
    """

    def __init__(self, settings: Settings, market: MarketState, storage: Storage, notify) -> None:
        self.settings = settings
        self.market = market
        self.storage = storage
        self.notify = notify

    def repeat_blocked(self, symbol: str, now_ms: int) -> bool:
        previous = self.storage.last_momentum_slot_for_symbol(symbol)
        return bool(
            previous
            and now_ms - int(previous["opened_at_ms"])
            < self.settings.momentum_repeat_symbol_minutes * 60_000
        )

    async def consider(
        self,
        item: EvaluatedCandidate,
        snapshot_id: str,
        now_ms: int,
        episode_id: str,
        channel: str = "MC5",
    ) -> None:
        active = self.storage.momentum_primary_open_slot()
        if active:
            self.storage.add_skipped(
                snapshot_id,
                item.candidate.symbol,
                f"{channel}_SLOT_BUSY",
                active["id"],
                now_ms,
            )
            return
        if self.repeat_blocked(item.candidate.symbol, now_ms):
            self.storage.add_skipped(
                snapshot_id,
                item.candidate.symbol,
                f"{channel}_REPEAT_SYMBOL_20M",
                None,
                now_ms,
            )
            return
        if item.book.buy_vwap is None or item.book.best_ask is None:
            self.storage.add_skipped(
                snapshot_id,
                item.candidate.symbol,
                f"{channel}_NO_EXECUTABLE_ENTRY",
                None,
                now_ms,
            )
            return
        slot_id = self.storage.create_momentum_slot(
            snapshot_id,
            item.candidate.symbol,
            episode_id,
            now_ms,
            item.book.best_ask,
            item.book.buy_vwap,
            channel,
        )
        await self.notify(
            f"🔵 PumpRadar {channel} spot paper\n{item.candidate.symbol}\n"
            f"signal {item.candidate.return_5m if channel == 'MC5' else item.candidate.return_10m:+.3f}% · entry {item.book.buy_vwap:.8g}\n"
            f"stop -{self.settings.momentum_stop_percent:.1f}% · "
            f"trail +{self.settings.momentum_trail_activation_percent:.1f}%/"
            f"-{self.settings.momentum_trail_drawdown_percent:.1f}% · "
            f"20m · {self.settings.algorithm_version}"
        )
        LOG.info("Opened %s spot momentum slot %s %s", channel, slot_id, item.candidate.symbol)

    async def tick(self, now_ms: int) -> None:
        for slot in self.storage.momentum_policy_slots():
            sell_price = self.market.executable_sell_price(
                slot["symbol"],
                float(slot["quantity"]),
                now_ms,
                self.settings.max_feed_age_ms,
            )
            if sell_price is None:
                continue
            entry = float(slot["entry_vwap"])
            current_return = (sell_price / entry - 1) * 100
            self.storage.update_momentum_slot_extremes(
                slot["id"], current_return, now_ms
            )
            for policy in self.storage.momentum_policies_for_slot(slot["id"]):
                if policy["state"] != "OPEN":
                    continue
                await self._update_policy(
                    slot, policy, sell_price, current_return, now_ms
                )

    async def _update_policy(
        self,
        slot,
        policy,
        sell_price: float,
        current_return: float,
        now_ms: int,
    ) -> None:
        policy_name = str(policy["policy"])
        age_seconds = (now_ms - int(slot["opened_at_ms"])) / 1000
        peak_return = max(float(policy["peak_return_percent"] or 0), current_return)
        activated = policy["activated_at_ms"] is not None
        updates = {"peak_return_percent": peak_return}
        if (
            policy_name == "MC_TRAIL_1P0"
            and not activated
            and current_return >= self.settings.momentum_trail_activation_percent
        ):
            updates["activated_at_ms"] = now_ms
            activated = True

        exit_reason: Optional[str] = None
        if current_return <= -self.settings.momentum_stop_percent:
            exit_reason = "MC_STOP_2"
        elif (
            policy_name == "MC_FIXED_TP4"
            and current_return >= self.settings.momentum_fixed_target_percent
        ):
            exit_reason = "MC_TARGET_4"
        elif (
            policy_name == "MC_TRAIL_1P0"
            and activated
            and peak_return - current_return
            >= self.settings.momentum_trail_drawdown_percent
        ):
            exit_reason = "MC_TRAIL_EXIT_1"
        elif (
            policy_name == "MC_HOLD_120"
            and age_seconds >= self.settings.momentum_hold_seconds
        ):
            exit_reason = "MC_HOLD_120_EXIT"
        elif age_seconds >= self.settings.momentum_horizon_seconds:
            exit_reason = "MC_HORIZON_20M"

        self.storage.update_momentum_policy(policy["id"], **updates)
        if exit_reason is None:
            return

        quantity = float(slot["quantity"])
        exit_quote = quantity * sell_price
        exit_fee = exit_quote * self.settings.fee_rate
        entry_cost = float(slot["position_usdt"])
        entry_fee = float(slot["entry_fee_usdt"])
        gross = (exit_quote / entry_cost - 1) * 100
        net = ((exit_quote - entry_fee - exit_fee) / entry_cost - 1) * 100
        self.storage.update_momentum_policy(
            policy["id"],
            state="CLOSED",
            closed_at_ms=now_ms,
            exit_reason=exit_reason,
            exit_vwap=sell_price,
            exit_fee_usdt=exit_fee,
            gross_return_percent=gross,
            net_return_percent=net,
        )
        if policy_name == self.settings.momentum_primary_policy:
            self.storage.close_momentum_primary(
                slot["id"], now_ms, exit_reason, sell_price, gross, net
            )
            await self.notify(
                f"🔷 {slot['symbol']} {slot['signal_channel']} {exit_reason}: net {net:+.3f}%"
            )
        LOG.info(
            "Closed momentum slot %s policy %s: %s net %+.3f%%",
            slot["id"],
            policy_name,
            exit_reason,
            net,
        )
