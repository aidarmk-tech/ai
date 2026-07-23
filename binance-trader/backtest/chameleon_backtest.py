#!/usr/bin/env python3
"""
Бэктест стратегии «Хамелеон» на исторических данных Binance.

Это точный порт логики Android-бота (Strategy.kt + TradingService.kt):
детектор режима (Kaufman ER), два движка (пробой / выкуп паники),
ATR-трейлинг с переводом в безубыток, тайм-стопы, риск-паритет,
дневной лимит убытка, кулдаун, макро-фильтр BTC, контекст 1h.

Только стандартная библиотека Python 3.8+ — ничего ставить не нужно.

ЗАПУСК (на машине, где доступен Binance):
    python3 chameleon_backtest.py                 # год, дефолтные пары
    python3 chameleon_backtest.py --days 365 --max-positions 1 --pos-pct 90
    python3 chameleon_backtest.py --equity 30     # смоделировать мелкий депозит
    python3 chameleon_backtest.py --selftest      # проверка движка на синтетике (без сети)

Комиссия taker 0.1% с каждой стороны + проскальзывание учитываются.
"""

import argparse
import json
import math
import random
import statistics
import sys
import time
import urllib.request
from dataclasses import dataclass, field

# ── Параметры стратегии (совпадают с текущим ботом) ──
ER_PERIOD = 20
ER_TREND = 0.25
DONCHIAN = 10
SMA_PERIOD = 20
ATR_PERIOD = 14
MOM_STOP_ATR = 2.2
REV_STOP_ATR = 2.0
MOM_TIME_STOP = 32
REV_TIME_STOP = 16
RISK_PCT = 4.0
DAILY_LOSS_PCT = 6.0
COOLDOWN_BARS = 3
MIN_CANDLES = 60

DEFAULT_PAIRS = ["BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT",
                 "BNBUSDT", "DOGEUSDT", "ADAUSDT", "AVAXUSDT"]


# ─────────────────────────── Индикаторы ───────────────────────────

def ema(values, period):
    out = []
    k = 2.0 / (period + 1)
    prev = values[0]
    for v in values:
        prev = v * k + prev * (1 - k)
        out.append(prev)
    return out


def sma(values, period):
    return sum(values[-period:]) / period


def stdev(values, period):
    tail = values[-period:]
    m = sum(tail) / len(tail)
    return math.sqrt(sum((x - m) ** 2 for x in tail) / len(tail))


def rsi(closes, period):
    if len(closes) <= period:
        return 50.0
    gain = loss = 0.0
    for i in range(1, period + 1):
        d = closes[i] - closes[i - 1]
        if d > 0:
            gain += d
        else:
            loss -= d
    ag, al = gain / period, loss / period
    for i in range(period + 1, len(closes)):
        d = closes[i] - closes[i - 1]
        ag = (ag * (period - 1) + max(d, 0.0)) / period
        al = (al * (period - 1) + max(-d, 0.0)) / period
    if al == 0:
        return 100.0
    return 100.0 - 100.0 / (1.0 + ag / al)


def atr(candles, period):
    if len(candles) <= period:
        return 0.0
    trs = []
    for i in range(1, len(candles)):
        c = candles[i]
        pc = candles[i - 1]["c"]
        trs.append(max(c["h"] - c["l"], abs(c["h"] - pc), abs(c["l"] - pc)))
    a = sum(trs[:period]) / period
    for i in range(period, len(trs)):
        a = (a * (period - 1) + trs[i]) / period
    return a


def efficiency_ratio(closes, period):
    if len(closes) <= period:
        return 0.0
    last = len(closes) - 1
    net = abs(closes[last] - closes[last - period])
    path = sum(abs(closes[i] - closes[i - 1]) for i in range(last - period + 1, last + 1))
    return net / path if path > 0 else 0.0


def volume_z(candles):
    if len(candles) < 22:
        return 0.0
    prev = [c["v"] for c in candles[-21:-1]]
    m = sum(prev) / len(prev)
    sd = math.sqrt(sum((x - m) ** 2 for x in prev) / len(prev))
    if sd <= 0:
        return 0.0
    return (candles[-1]["v"] - m) / sd


# ─────────────────────── Логика входа/выхода ───────────────────────

# Конфиг стратегии. DEFAULT_CFG = текущие параметры бота (агрессивные).
# Пресеты и перебор задают отклонения от него.
DEFAULT_CFG = {
    "er_trend": 0.25,        # порог тренда (ER); режим=тренд если ER>=этого
    "donchian": 10,          # окно пробоя
    "vol_z_min": 0.4,        # минимальный z-score объёма для пробоя
    "mom_overext": 3.5,      # отсечка перегрева, ×ATR
    "mom_rsi_max": 82.0,     # верхний RSI14 для пробоя
    "mom_stop_atr": 2.2,     # стоп пробоя, ×ATR
    "rev_z": -1.3,           # порог просадки для выкупа, сигм
    "rev_rsi3": 30.0,        # верхний RSI3 для выкупа
    "rev_min_atr": 0.5,      # мин. расстояние до цели, ×ATR
    "rev_stop_atr": 2.0,     # стоп выкупа, ×ATR
    "use_mom": True,         # включён ли движок пробоя
    "use_rev": True,         # включён ли движок выкупа
    "risk_pct": 4.0,         # риск на сделку, % капитала
}


def regime_is_trend(closes, cfg):
    return efficiency_ratio(closes, ER_PERIOD) >= cfg["er_trend"]


def context_up(closes_1h):
    if len(closes_1h) < 55:
        return False
    e50 = ema(closes_1h, 50)
    e20 = ema(closes_1h, 20)
    last = len(closes_1h) - 1
    return closes_1h[last] > e50[last] or e20[last] > e20[last - 3]


def not_bear(btc_closes_1h):
    if len(btc_closes_1h) < 200:
        return True
    return btc_closes_1h[-1] > sma(btc_closes_1h, 200)


def consider_entry(candles, ctx_up, notbear, cfg):
    """Возвращает dict(type, stop_dist, target) или None."""
    if len(candles) < MIN_CANDLES or not notbear:
        return None
    closes = [c["c"] for c in candles]
    last = len(closes) - 1
    a = atr(candles, ATR_PERIOD)
    if a <= 0:
        return None
    sma20 = sma(closes, SMA_PERIOD)

    if regime_is_trend(closes, cfg):
        if not cfg["use_mom"]:
            return None
        if not ctx_up:
            return None
        donchian_high = max(c["h"] for c in candles[-cfg["donchian"] - 1:-1])
        if closes[last] <= donchian_high:
            return None
        if volume_z(candles) < cfg["vol_z_min"]:
            return None
        if closes[last] - sma20 > cfg["mom_overext"] * a:
            return None
        if rsi(closes[-80:], 14) >= cfg["mom_rsi_max"]:
            return None
        return {"type": "MOM", "stop_dist": cfg["mom_stop_atr"] * a, "target": 0.0}
    else:
        if not cfg["use_rev"]:
            return None
        sd = stdev(closes, SMA_PERIOD)
        if sd <= 0:
            return None
        z = (closes[last] - sma20) / sd
        if z > cfg["rev_z"]:
            return None
        if rsi(closes[-30:], 3) >= cfg["rev_rsi3"]:
            return None
        if candles[last]["c"] <= candles[last]["o"]:
            return None
        if sma20 - closes[last] < cfg["rev_min_atr"] * a:
            return None
        return {"type": "REV", "stop_dist": cfg["rev_stop_atr"] * a, "target": sma20}


def momentum_exit(candles):
    closes = [c["c"] for c in candles]
    return closes[-1] < sma(closes, SMA_PERIOD) - 0.5 * atr(candles, ATR_PERIOD)


def reversion_exit(candles):
    return rsi([c["c"] for c in candles][-30:], 3) >= 60.0


# ─────────────────────────── Загрузка данных ───────────────────────

def fetch_klines(symbol, interval, days):
    """Качает свечи с Binance постранично (max 1000/запрос)."""
    ms_per = {"15m": 900_000, "1h": 3_600_000}[interval]
    end = int(time.time() * 1000)
    start = end - days * 86_400_000
    out = []
    cur = start
    while cur < end:
        url = (f"https://api.binance.com/api/v3/klines?symbol={symbol}"
               f"&interval={interval}&startTime={cur}&limit=1000")
        with urllib.request.urlopen(url, timeout=30) as r:
            arr = json.load(r)
        if not arr:
            break
        for k in arr:
            out.append({"t": k[0], "o": float(k[1]), "h": float(k[2]),
                        "l": float(k[3]), "c": float(k[4]), "v": float(k[5]),
                        "ct": k[6]})
        cur = arr[-1][6] + 1
        if len(arr) < 1000:
            break
        time.sleep(0.25)
    return out


# ─────────────────────────── Симуляция ───────────────────────────

@dataclass
class Trade:
    symbol: str
    kind: str
    entry: float
    exit: float
    qty: float
    pnl: float
    ret_pct: float
    reason: str
    bars: int


@dataclass
class Pos:
    symbol: str
    kind: str
    entry: float
    qty: float
    spent: float
    stop: float
    target: float
    trail: float
    high: float
    bars: int = 0
    breakeven: bool = False


def backtest(data15, data1h, btc1h, equity0, max_positions, pos_pct,
             fee=0.001, slip=0.0005, verbose=False, cfg=None):
    if cfg is None:
        cfg = DEFAULT_CFG
    """
    data15: {symbol: [candles]}  — вход, 15m
    data1h: {symbol: [candles]}  — контекст, 1h
    btc1h:  [candles]            — BTC 1h для макро-фильтра
    """
    # Единая ось времени по 15m закрытиям.
    all_ct = sorted({c["ct"] for s in data15.values() for c in s})
    idx15 = {s: 0 for s in data15}
    idx1h = {s: 0 for s in data1h}
    idxbtc = 0

    cash = equity0
    positions = {}
    trades = []
    cooldown = {s: 0 for s in data15}
    equity_curve = []
    day_pnl = 0.0
    day = None
    daily_stop = False

    def cur_1h_closes(sym, ct):
        nonlocal_list = data1h.get(sym, [])
        j = idx1h.get(sym, 0)
        while j + 1 < len(nonlocal_list) and nonlocal_list[j + 1]["ct"] <= ct:
            j += 1
        idx1h[sym] = j
        return [c["c"] for c in nonlocal_list[max(0, j + 1 - 260):j + 1]]

    for ct in all_ct:
        # BTC 1h контекст на текущий момент.
        while idxbtc + 1 < len(btc1h) and btc1h[idxbtc + 1]["ct"] <= ct:
            idxbtc += 1
        btc_closes = [c["c"] for c in btc1h[max(0, idxbtc + 1 - 260):idxbtc + 1]]
        notbear = not_bear(btc_closes)

        # Новый день (UTC) — сброс дневного лимита.
        d = time.gmtime(ct / 1000).tm_yday
        if d != day:
            day = d
            day_pnl = 0.0
            daily_stop = False

        for sym, series in data15.items():
            j = idx15[sym]
            # Продвигаем указатель до свечи, закрывшейся на ct.
            while j < len(series) and series[j]["ct"] < ct:
                j += 1
            if j >= len(series) or series[j]["ct"] != ct:
                idx15[sym] = j
                continue
            idx15[sym] = j
            # Окно ограничено: стратегия смотрит только последние свечи
            # (бот в реале видит ~200). Это делает симуляцию O(n), а не O(n²).
            window = series[max(0, j + 1 - 260):j + 1]
            if len(window) < MIN_CANDLES:
                continue
            candle = series[j]
            price = candle["c"]

            if cooldown[sym] > 0:
                cooldown[sym] -= 1

            pos = positions.get(sym)
            if pos:
                pos.bars += 1
                pos.high = max(pos.high, candle["h"])
                if pos.kind == "MOM":
                    if not pos.breakeven and candle["h"] >= pos.entry + pos.trail:
                        pos.stop = max(pos.stop, pos.entry * 1.002)
                        pos.breakeven = True
                    pos.stop = max(pos.stop, pos.high - pos.trail)

                # Внутрисвечные стоп/цель: сначала стоп (консервативно).
                exit_price = None
                reason = None
                if candle["l"] <= pos.stop:
                    exit_price = min(pos.stop, candle["o"])
                    reason = "стоп"
                elif pos.target > 0 and candle["h"] >= pos.target:
                    exit_price = pos.target
                    reason = "цель"
                elif pos.kind == "MOM" and momentum_exit(window):
                    exit_price = price
                    reason = "слом импульса"
                elif pos.kind == "REV" and reversion_exit(window):
                    exit_price = price
                    reason = "перепроданность снята"
                elif pos.kind == "MOM" and pos.bars >= MOM_TIME_STOP and price < pos.entry:
                    exit_price = price
                    reason = "тайм-стоп"
                elif pos.kind == "REV" and pos.bars >= REV_TIME_STOP:
                    exit_price = price
                    reason = "тайм-стоп"

                if exit_price is not None:
                    proceeds = pos.qty * exit_price * (1 - slip) * (1 - fee)
                    pnl = proceeds - pos.spent
                    cash += proceeds
                    day_pnl += pnl
                    ret = (exit_price - pos.entry) / pos.entry * 100
                    trades.append(Trade(sym, pos.kind, pos.entry, exit_price,
                                        pos.qty, pnl, ret, reason, pos.bars))
                    del positions[sym]
                    if reason == "стоп" and pnl < 0:
                        cooldown[sym] = COOLDOWN_BARS
                    if day_pnl <= -equity0 * DAILY_LOSS_PCT / 100:
                        daily_stop = True
                continue

            # Нет позиции — рассматриваем вход.
            if cooldown[sym] > 0 or daily_stop:
                continue
            if len(positions) >= max_positions:
                continue
            ctx = context_up(cur_1h_closes(sym, ct))
            plan = consider_entry(window, ctx, notbear, cfg)
            if not plan:
                continue

            invested = sum(p.spent for p in positions.values())
            eq = cash + invested
            risk_amt = eq * cfg["risk_pct"] / 100
            notional_risk = risk_amt / plan["stop_dist"] * price
            cap = cash * pos_pct / 100
            spend = min(notional_risk, cap)
            min_entry = 5.0 * 2.0
            if spend < min_entry:
                spend = min_entry
            if spend > cash:
                continue

            eff = price * (1 + slip)
            qty = spend / eff * (1 - fee)
            positions[sym] = Pos(sym, plan["type"], eff, qty, spend,
                                 eff - plan["stop_dist"], plan["target"],
                                 plan["stop_dist"], eff)
            cash -= spend

        equity_curve.append(cash + sum(p.qty * p.entry for p in positions.values()))

    # Закрываем открытые позиции по последней цене.
    for sym, pos in list(positions.items()):
        last_price = data15[sym][-1]["c"]
        proceeds = pos.qty * last_price * (1 - slip) * (1 - fee)
        pnl = proceeds - pos.spent
        cash += proceeds
        trades.append(Trade(sym, pos.kind, pos.entry, last_price, pos.qty, pnl,
                            (last_price - pos.entry) / pos.entry * 100, "конец", pos.bars))

    return cash, trades, equity_curve


# ─────────────────────────── Отчёт ───────────────────────────

def report(equity0, cash, trades, equity_curve, label):
    print(f"\n{'=' * 62}\n  БЭКТЕСТ «ХАМЕЛЕОН» — {label}\n{'=' * 62}")
    total_ret = (cash - equity0) / equity0 * 100
    wins = [t for t in trades if t.pnl > 0]
    losses = [t for t in trades if t.pnl <= 0]
    gross_win = sum(t.pnl for t in wins)
    gross_loss = -sum(t.pnl for t in losses)
    peak = -1e9
    max_dd = 0.0
    for v in equity_curve:
        peak = max(peak, v)
        if peak > 0:
            max_dd = max(max_dd, (peak - v) / peak * 100)

    print(f"Старт капитал:       {equity0:.2f} USDT")
    print(f"Итог капитал:        {cash:.2f} USDT")
    print(f"Доходность:          {total_ret:+.2f}%")
    print(f"Сделок:              {len(trades)}")
    if trades:
        wr = len(wins) / len(trades) * 100
        pf = (gross_win / gross_loss) if gross_loss > 0 else float('inf')
        print(f"Винрейт:             {wr:.1f}%  ({len(wins)}W / {len(losses)}L)")
        print(f"Profit factor:       {pf:.2f}")
        print(f"Средняя сделка:      {statistics.mean(t.ret_pct for t in trades):+.2f}%")
        print(f"Макс. просадка:      {max_dd:.1f}%")
        for kind, name in [("MOM", "Пробой"), ("REV", "Выкуп")]:
            sub = [t for t in trades if t.kind == kind]
            if sub:
                sw = sum(1 for t in sub if t.pnl > 0) / len(sub) * 100
                print(f"  {name:8}: {len(sub):4} сделок, винрейт {sw:.0f}%, "
                      f"итог {sum(t.pnl for t in sub):+.2f} USDT")
    print("=" * 62)


# ─────────────────────────── Перебор конфигураций ───────────────────────────

def metrics(equity0, cash, trades, curve):
    wins = [t for t in trades if t.pnl > 0]
    gw = sum(t.pnl for t in wins)
    gl = -sum(t.pnl for t in trades if t.pnl <= 0)
    peak, dd = -1e9, 0.0
    for v in curve:
        peak = max(peak, v)
        if peak > 0:
            dd = max(dd, (peak - v) / peak * 100)
    return {
        "ret": (cash - equity0) / equity0 * 100,
        "n": len(trades),
        "wr": (len(wins) / len(trades) * 100) if trades else 0.0,
        "pf": (gw / gl) if gl > 0 else float("inf"),
        "dd": dd,
    }


# Список конфигураций для перебора: (имя, отклонения от DEFAULT_CFG).
SWEEP = [
    ("текущий (агрессивный)", {}),
    ("консервативный", dict(er_trend=0.35, donchian=20, vol_z_min=1.5,
                            mom_overext=3.0, mom_rsi_max=75, rev_z=-2.0,
                            rev_rsi3=15, rev_min_atr=1.0, risk_pct=1.5)),
    ("только выкуп", dict(use_mom=False)),
    ("только выкуп (строгий)", dict(use_mom=False, rev_z=-2.0, rev_rsi3=15,
                                    rev_min_atr=1.0)),
    ("только пробой", dict(use_rev=False)),
    ("только пробой (строгий)", dict(use_rev=False, vol_z_min=2.0, donchian=20,
                                     er_trend=0.4, mom_rsi_max=72)),
    ("ER порог 0.35", dict(er_trend=0.35)),
    ("объём z>=1.5", dict(vol_z_min=1.5)),
    ("Donchian 20", dict(donchian=20)),
    ("просадка z<=-2", dict(rev_z=-2.0, rev_rsi3=15)),
    ("узкие стопы", dict(mom_stop_atr=1.5, rev_stop_atr=1.5)),
    ("широкие стопы", dict(mom_stop_atr=3.0, rev_stop_atr=2.5)),
    ("конс.+только выкуп", dict(use_mom=False, rev_z=-2.0, rev_rsi3=15,
                               rev_min_atr=1.0, risk_pct=1.5)),
    ("конс.+только пробой", dict(use_rev=False, vol_z_min=2.0, donchian=20,
                                er_trend=0.4, mom_rsi_max=72, risk_pct=1.5)),
]


def run_sweep(data15, data1h, btc1h, equity, maxpos, pospct, fee, slip):
    print(f"\nПеребор {len(SWEEP)} конфигураций (каждая — полный прогон, "
          f"минута-две суммарно)…\n")
    rows = []
    for i, (name, over) in enumerate(SWEEP, 1):
        cfg = {**DEFAULT_CFG, **over}
        cash, trades, curve = backtest(data15, data1h, btc1h, equity, maxpos,
                                       pospct, fee, slip, cfg=cfg)
        m = metrics(equity, cash, trades, curve)
        rows.append((name, m))
        print(f"  [{i:2}/{len(SWEEP)}] {name:26} → {m['ret']:+7.1f}%  "
              f"сделок {m['n']:4}  винрейт {m['wr']:4.0f}%  просадка {m['dd']:4.0f}%")
    rows.sort(key=lambda r: -r[1]["ret"])
    print(f"\n{'=' * 72}\n  ИТОГ — конфигурации от лучшей к худшей\n{'=' * 72}")
    print(f"  {'конфигурация':26} {'доход':>8} {'сделок':>7} {'винрейт':>8} "
          f"{'PF':>6} {'просадка':>9}")
    print("  " + "-" * 68)
    for name, m in rows:
        pf = "inf" if m["pf"] == float("inf") else f"{m['pf']:.2f}"
        print(f"  {name:26} {m['ret']:+7.1f}% {m['n']:7} {m['wr']:7.0f}% "
              f"{pf:>6} {m['dd']:8.0f}%")
    print("=" * 72)
    best = rows[0]
    if best[1]["ret"] > 0:
        print(f"\n✅ Лучшая прибыльная: «{best[0]}» ({best[1]['ret']:+.1f}%, "
              f"просадка {best[1]['dd']:.0f}%). Пришли таблицу — разберём.")
    else:
        print("\n⚠️  Ни одна конфигурация не прибыльна на этом периоде. "
              "Честный вывод: у стратегии нет преимущества на 15m после комиссий. "
              "Пришли таблицу — обсудим, что это значит и что делать.")


# ─────────────────────────── Self-test ───────────────────────────

def synth(days, seed, mu=0.0, vol=0.02, start=100.0):
    """Синтетический ряд (геом. броуновское движение) — только для проверки движка."""
    random.seed(seed)
    n = days * 96  # 15m свечей в дне
    candles = []
    p = start
    t0 = int(time.time() * 1000) - n * 900_000
    for i in range(n):
        drift = mu / 96
        shock = random.gauss(0, vol / math.sqrt(96))
        p2 = p * math.exp(drift + shock)
        hi = max(p, p2) * (1 + abs(random.gauss(0, 0.002)))
        lo = min(p, p2) * (1 - abs(random.gauss(0, 0.002)))
        ct = t0 + (i + 1) * 900_000 - 1
        candles.append({"t": t0 + i * 900_000, "o": p, "h": hi, "l": lo,
                        "c": p2, "v": abs(random.gauss(1000, 300)), "ct": ct})
        p = p2
    return candles


def to_1h(c15):
    out = []
    for i in range(0, len(c15) - 4, 4):
        grp = c15[i:i + 4]
        out.append({"t": grp[0]["t"], "o": grp[0]["o"],
                    "h": max(x["h"] for x in grp), "l": min(x["l"] for x in grp),
                    "c": grp[-1]["c"], "v": sum(x["v"] for x in grp),
                    "ct": grp[-1]["ct"]})
    return out


def run_selftest():
    print("SELF-TEST: прогон движка на синтетических данных (НЕ показатель доходности!)")
    pairs = ["AAAUSDT", "BBBUSDT", "CCCUSDT"]
    data15, data1h = {}, {}
    for i, s in enumerate(pairs):
        c = synth(120, seed=i + 1, mu=0.1, vol=0.03)
        data15[s] = c
        data1h[s] = to_1h(c)
    btc1h = to_1h(synth(120, seed=99, mu=0.2, vol=0.02))
    cash, trades, curve = backtest(data15, data1h, btc1h, 1000.0, 3, 20)
    report(1000.0, cash, trades, curve, "SELF-TEST (синтетика)")
    assert len(curve) > 0, "движок не отработал"
    print("\n✅ Движок работает корректно. На реальных данных запусти без --selftest.")


# ─────────────────────────── main ───────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=365)
    ap.add_argument("--pairs", default=",".join(DEFAULT_PAIRS))
    ap.add_argument("--equity", type=float, default=1000.0)
    ap.add_argument("--max-positions", type=int, default=3)
    ap.add_argument("--pos-pct", type=float, default=20.0)
    ap.add_argument("--fee", type=float, default=0.001)
    ap.add_argument("--slip", type=float, default=0.0005)
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--sweep", action="store_true",
                    help="перебрать десятки конфигураций и вывести таблицу")
    args = ap.parse_args()

    if args.selftest:
        run_selftest()
        return

    pairs = [p.strip().upper() for p in args.pairs.split(",") if p.strip()]
    print(f"Качаю {args.days} дней истории Binance для {len(pairs)} пар "
          f"(15m + 1h)… это займёт минуту.")
    data15, data1h = {}, {}
    for s in pairs:
        try:
            data15[s] = fetch_klines(s, "15m", args.days)
            data1h[s] = fetch_klines(s, "1h", args.days)
            print(f"  {s}: {len(data15[s])} свечей 15m")
        except Exception as e:
            print(f"  {s}: ошибка загрузки — {e}")
    if not data15:
        print("Не удалось загрузить данные. Проверь доступ к api.binance.com.")
        sys.exit(1)
    btc1h = data1h.get("BTCUSDT") or fetch_klines("BTCUSDT", "1h", args.days)

    if args.sweep:
        run_sweep(data15, data1h, btc1h, args.equity, args.max_positions,
                  args.pos_pct, args.fee, args.slip)
        return

    cash, trades, curve = backtest(data15, data1h, btc1h, args.equity,
                                   args.max_positions, args.pos_pct,
                                   args.fee, args.slip)
    label = (f"{args.days}д, {len(pairs)} пар, старт {args.equity:.0f} USDT, "
             f"макс.поз {args.max_positions}, на сделку {args.pos_pct:.0f}%")
    report(args.equity, cash, trades, curve, label)


if __name__ == "__main__":
    main()
