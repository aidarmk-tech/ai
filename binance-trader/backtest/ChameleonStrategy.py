"""
Стратегия «Хамелеон» для freqtrade — порт логики Android-бота.

Приближённая (не 1:1) реализация под фреймворк freqtrade: даёт бэктест,
hyperopt и dry-run из коробки. Точный эталон — chameleon_backtest.py.

Установка и запуск (на машине с доступом к Binance):
    pip install freqtrade            # или официальный установщик
    freqtrade create-userdir --userdir user_data
    cp ChameleonStrategy.py user_data/strategies/
    freqtrade download-data --exchange binance -t 15m 1h --days 365 \
        -p BTC/USDT ETH/USDT SOL/USDT XRP/USDT BNB/USDT DOGE/USDT ADA/USDT AVAX/USDT
    freqtrade backtesting --strategy ChameleonStrategy --timeframe 15m \
        --timerange=20250101- -p BTC/USDT ETH/USDT SOL/USDT XRP/USDT

Индикаторы считаются на pandas/numpy — TA-Lib для самой стратегии не нужен
(хотя сам freqtrade его требует при установке).
"""
from datetime import datetime
import numpy as np
import pandas as pd
from freqtrade.strategy import IStrategy, informative
from pandas import DataFrame


def wilder_rsi(series: pd.Series, period: int) -> pd.Series:
    delta = series.diff()
    gain = delta.clip(lower=0).ewm(alpha=1 / period, adjust=False).mean()
    loss = (-delta.clip(upper=0)).ewm(alpha=1 / period, adjust=False).mean()
    rs = gain / loss.replace(0, np.nan)
    return (100 - 100 / (1 + rs)).fillna(100)


def wilder_atr(df: DataFrame, period: int) -> pd.Series:
    hl = df["high"] - df["low"]
    hc = (df["high"] - df["close"].shift()).abs()
    lc = (df["low"] - df["close"].shift()).abs()
    tr = pd.concat([hl, hc, lc], axis=1).max(axis=1)
    return tr.ewm(alpha=1 / period, adjust=False).mean()


def efficiency_ratio(series: pd.Series, period: int) -> pd.Series:
    net = (series - series.shift(period)).abs()
    path = series.diff().abs().rolling(period).sum()
    return (net / path.replace(0, np.nan)).fillna(0)


class ChameleonStrategy(IStrategy):
    INTERFACE_VERSION = 3
    timeframe = "15m"
    can_short = False
    startup_candle_count = 210
    process_only_new_candles = True

    # Управление выходами делаем сами (ATR-трейлинг в custom_stoploss).
    minimal_roi = {"0": 100}
    stoploss = -0.99
    use_custom_stoploss = True

    # Портфельные предохранители (аналоги наших фильтров).
    @property
    def protections(self):
        return [
            {"method": "CooldownPeriod", "stop_duration_candles": 3},
            {"method": "StoplossGuard", "lookback_period_candles": 96,
             "trade_limit": 4, "stop_duration_candles": 24, "only_per_pair": False},
            {"method": "LowProfitPairs", "lookback_period_candles": 288,
             "trade_limit": 3, "stop_duration_candles": 288, "required_profit": 0.0},
            {"method": "MaxDrawdown", "lookback_period_candles": 96,
             "trade_limit": 6, "stop_duration_candles": 48, "max_allowed_drawdown": 0.06},
        ]

    # Контекст 1h подтягиваем информативным таймфреймом.
    @informative("1h")
    def populate_indicators_1h(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        dataframe["ema50"] = dataframe["close"].ewm(span=50, adjust=False).mean()
        dataframe["ema20"] = dataframe["close"].ewm(span=20, adjust=False).mean()
        dataframe["sma200"] = dataframe["close"].rolling(200).mean()
        return dataframe

    def populate_indicators(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        dataframe["atr"] = wilder_atr(dataframe, 14)
        dataframe["sma20"] = dataframe["close"].rolling(20).mean()
        dataframe["std20"] = dataframe["close"].rolling(20).std(ddof=0)
        dataframe["er"] = efficiency_ratio(dataframe["close"], 20)
        dataframe["rsi14"] = wilder_rsi(dataframe["close"], 14)
        dataframe["rsi3"] = wilder_rsi(dataframe["close"], 3)
        dataframe["donchian10"] = dataframe["high"].rolling(10).max().shift(1)
        vmean = dataframe["volume"].rolling(20).mean().shift(1)
        vstd = dataframe["volume"].rolling(20).std(ddof=0).shift(1)
        dataframe["vol_z"] = (dataframe["volume"] - vmean) / vstd.replace(0, np.nan)
        dataframe["z"] = (dataframe["close"] - dataframe["sma20"]) / dataframe["std20"].replace(0, np.nan)
        return dataframe

    def populate_entry_trend(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        ctx_up = (dataframe["close"] > dataframe["ema50_1h"]) | \
                 (dataframe["ema20_1h"] > dataframe["ema20_1h"].shift(3))
        # Движок «Пробой» (режим тренда: ER >= 0.25)
        mom = (
            (dataframe["er"] >= 0.25) & ctx_up &
            (dataframe["close"] > dataframe["donchian10"]) &
            (dataframe["vol_z"] >= 0.4) &
            (dataframe["close"] - dataframe["sma20"] <= 3.5 * dataframe["atr"]) &
            (dataframe["rsi14"] < 82)
        )
        # Движок «Выкуп паники» (режим боковика: ER < 0.25)
        rev = (
            (dataframe["er"] < 0.25) &
            (dataframe["z"] <= -1.3) &
            (dataframe["rsi3"] < 30) &
            (dataframe["close"] > dataframe["open"]) &
            (dataframe["sma20"] - dataframe["close"] >= 0.5 * dataframe["atr"])
        )
        dataframe.loc[mom, ["enter_long", "enter_tag"]] = (1, "MOM")
        dataframe.loc[rev & ~mom, ["enter_long", "enter_tag"]] = (1, "REV")
        return dataframe

    def populate_exit_trend(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        # Сигнальные выходы; стоп/цель/трейлинг — в custom_stoploss/custom_exit.
        mom_exit = dataframe["close"] < dataframe["sma20"] - 0.5 * dataframe["atr"]
        rev_exit = dataframe["rsi3"] >= 60
        dataframe.loc[mom_exit | rev_exit, "exit_long"] = 1
        return dataframe

    def custom_stoploss(self, pair: str, trade, current_time: datetime,
                        current_rate: float, current_profit: float, **kwargs) -> float:
        """ATR-трейлинг «люстра» + перевод в безубыток (движок пробоя)."""
        df, _ = self.dp.get_analyzed_dataframe(pair, self.timeframe)
        if df.empty:
            return self.stoploss
        atr = df["atr"].iloc[-1]
        if not atr or atr <= 0:
            return self.stoploss
        stop_atr = 2.2 if (trade.enter_tag or "MOM") == "MOM" else 2.0
        # Расстояние стопа в долях от текущей цены.
        dist = stop_atr * atr / current_rate
        # После хода на +1 дистанцию — не даём стопу опуститься ниже входа.
        if current_profit > dist:
            return max(-dist, -current_profit + 0.002)
        return -dist

    def custom_exit(self, pair: str, trade, current_time: datetime,
                   current_rate: float, current_profit: float, **kwargs):
        df, _ = self.dp.get_analyzed_dataframe(pair, self.timeframe)
        if df.empty:
            return None
        tag = trade.enter_tag or "MOM"
        held = (current_time - trade.open_date_utc).total_seconds() / 900  # 15m баров
        if tag == "REV":
            # Цель — возврат к SMA20.
            if current_rate >= df["sma20"].iloc[-1]:
                return "target_sma20"
            if held >= 16:
                return "timestop_rev"
        else:
            if held >= 32 and current_profit < 0:
                return "timestop_mom"
        return None
