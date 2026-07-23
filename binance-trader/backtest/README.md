# Бэктест стратегии «Хамелеон»

Два способа проверить стратегию на реальной истории Binance. Оба нужно
запускать **на своём ПК** (или любой машине с доступом к api.binance.com):
облачное окружение, где собирается APK, к биржам не пускают, поэтому
скачать котировки там невозможно.

## Вариант 1 — быстрый, без зависимостей (рекомендую)

Точный порт логики бота. Нужен только Python 3.8+, ничего ставить не надо.

```bash
cd binance-trader/backtest

# Год истории, дефолтные 8 пар, старт 1000 USDT, до 3 позиций (как в приложении)
python3 chameleon_backtest.py

# Смоделировать твой реальный сценарий: мелкий депозит, 1 позиция, 90% на сделку
python3 chameleon_backtest.py --equity 30 --max-positions 1 --pos-pct 90

# Свои пары и период
python3 chameleon_backtest.py --days 180 --pairs BTCUSDT,ETHUSDT,SOLUSDT

# Проверить, что движок вообще работает (синтетика, без сети)
python3 chameleon_backtest.py --selftest
```

Скрипт сам скачает свечи 15m + 1h, прогонит стратегию и выведет отчёт:
доходность, число сделок, винрейт, profit factor, макс. просадку и
разбивку по движкам (Пробой / Выкуп). Комиссия 0.1%/сторону и
проскальзывание учитываются (меняются флагами `--fee`, `--slip`).

Пришли мне вывод — разберём цифры вместе.

## Вариант 2 — полноценный freqtrade (hyperopt, графики)

Даёт оптимизацию параметров и визуализацию, но тяжелее в установке
(freqtrade тянет TA-Lib на C).

```bash
pip install freqtrade
freqtrade create-userdir --userdir user_data
cp ChameleonStrategy.py user_data/strategies/
freqtrade download-data --exchange binance -t 15m 1h --days 365 \
    -p BTC/USDT ETH/USDT SOL/USDT XRP/USDT BNB/USDT DOGE/USDT ADA/USDT AVAX/USDT
freqtrade backtesting --strategy ChameleonStrategy --timeframe 15m \
    -p BTC/USDT ETH/USDT SOL/USDT XRP/USDT BNB/USDT DOGE/USDT ADA/USDT AVAX/USDT

# Подбор оптимальных порогов (ER, ATR-множители и т.п.)
freqtrade hyperopt --strategy ChameleonStrategy --hyperopt-loss SharpeHyperOptLoss \
    --spaces buy sell --epochs 200
```

## Важно про интерпретацию

- **Бэктест — не гарантия будущего.** Он показывает, как стратегия вела
  бы себя в прошлом; рынок меняется. Но это несравнимо честнее, чем
  крутить параметры вслепую на живом счёте.
- Смотри в первую очередь на **макс. просадку** и **profit factor**, а не
  на итоговый процент. Просадка 40% ради +50% годовых — это плохо.
- Реальные проскальзывание и частичные исполнения на живом счёте будут
  чуть хуже бэктеста. Закладывай запас.
- `chameleon_backtest.py` — эталон логики бота; freqtrade-версия
  приближённая (другая модель исполнения), но удобна для hyperopt.
