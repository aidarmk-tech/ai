import asyncio
import json
import uuid

from .db import connect
from .market import MarketRecorder, _f, now_ms


class StableMarketRecorder(MarketRecorder):
    """Runtime hardening around the research recorder.

    This class is the production recorder used by the API. It keeps the raw
    recorder implementation simple while enforcing the invariants required for
    accountable research: stable subscriptions, persisted-before-used samples,
    fresh microstructure fields, exact recorded paper entries and synchronized
    pair exits.
    """

    def _refresh_universe_once(self) -> None:
        ranked = []
        for symbol, ticker in self.tickers.items():
            if not symbol.endswith("USDT") or ticker.get("st") not in (None, 1):
                continue
            ranked.append((_f(ticker.get("q")), symbol))
        ranked.sort(reverse=True)

        target = max(2, int(self.settings.universe_size))
        if len(ranked) < target:
            self._health(
                "universe",
                "WARMING",
                f"tickers={len(ranked)}/{target} bootstrap={len(self.universe)} gen={self.universe_generation}",
                now_ms(),
            )
            return

        selected = [s for _, s in ranked[:target]]
        for required in ("ETHUSDT", "BTCUSDT"):
            if required not in selected:
                selected.insert(0, required)
        new_universe = list(dict.fromkeys(selected))[:target]
        new_micro = new_universe[: self.settings.microstructure_size]

        old_universe_set = set(self.universe)
        old_micro_set = set(self.micro)
        new_universe_set = set(new_universe)
        new_micro_set = set(new_micro)
        membership_changed = (
            new_universe_set != old_universe_set or new_micro_set != old_micro_set
        )

        # Ranking order is useful to B/D, but WebSocket subscriptions are sets.
        # Update ranking every refresh without reconnecting just because two
        # already-subscribed symbols exchanged rank positions.
        self.universe = new_universe
        self.micro = new_micro
        if membership_changed:
            self.universe_generation += 1
            # A newly admitted micro symbol must start replenishment from a
            # clean baseline rather than comparing against an old membership.
            for symbol in list(self.depth_previous):
                if symbol not in new_micro_set or symbol not in old_micro_set:
                    self.depth_previous.pop(symbol, None)

        self._health(
            "universe",
            "OK",
            (
                f"universe={len(self.universe)} micro={len(self.micro)} "
                f"gen={self.universe_generation} membership_changed={int(membership_changed)}"
            ),
            now_ms(),
        )

    async def _universe_loop(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            try:
                self._refresh_universe_once()
            except Exception as exc:
                self._health("universe", "DEGRADED", repr(exc))

            if len(self.tickers) < self.settings.universe_size:
                interval = 2
            else:
                # Ranking does not need minute-level churn. Refresh at the same
                # cadence as the subscription lease (default five minutes).
                interval = max(60, int(getattr(self.settings, "subscription_refresh_seconds", 300)))
            try:
                await asyncio.wait_for(stop.wait(), timeout=interval)
            except TimeoutError:
                pass

    async def _sample_loop(self, stop: asyncio.Event) -> None:
        """Only advance sampler health/time when real market rows were added."""
        while not stop.is_set():
            tick = now_ms() // 1000 * 1000
            try:
                self._flush_flow(tick)
                self._sample_depth(tick)
                if (tick // 1000) % self.settings.market_sample_seconds == 0:
                    fresh_rows = self._sample_market(tick)
                    if fresh_rows:
                        if self.last_sample_ms and tick - self.last_sample_ms > self.settings.max_sample_gap_seconds * 1000:
                            self._record_gap(self.last_sample_ms, tick, "live_sampler_gap")
                        self._evaluate_strategies(tick)
                        self.last_sample_ms = tick
                        self._health(
                            "market_sampler",
                            "OK",
                            f"rows={fresh_rows} universe={len(self.universe)}",
                            tick,
                        )
                    else:
                        self._health(
                            "market_sampler",
                            "DEGRADED",
                            f"no fresh market rows; universe={len(self.universe)} last_real_sample_ms={self.last_sample_ms}",
                            self.last_sample_ms or None,
                        )

                if tick - self._cleanup_last_ms >= 3600_000:
                    self._cleanup_raw(tick)
                    self._cleanup_last_ms = tick
                if tick - self._gap_audit_last_ms >= 3600_000:
                    self._audit_sample_gaps(tick)
                    self._gap_audit_last_ms = tick
            except Exception as exc:
                self._health("market_sampler", "DEGRADED", repr(exc), self.last_sample_ms or None)

            try:
                await asyncio.wait_for(stop.wait(), timeout=1)
            except TimeoutError:
                pass

    def _flush_flow(self, current_second_ms: int) -> None:
        ready = [(k, v) for k, v in self.flow_buckets.items() if k[1] < current_second_ms]
        if not ready:
            return

        rows = []
        history_updates = []
        keys = []
        for (symbol, ts), values in ready:
            buy_notional, sell_notional, buy_qty, sell_qty, count = values
            rows.append((ts, symbol, buy_notional, sell_notional, buy_qty, sell_qty, count))
            history_updates.append((symbol, ts, buy_notional, sell_notional))
            keys.append((symbol, ts))

        # Persist first. In-memory strategy history must never contain a point
        # that cannot later be replayed from the SQLite evidence base.
        with connect(self.settings.db_path) as conn:
            conn.executemany(
                """INSERT OR REPLACE INTO flow_samples
                   (ts_ms,symbol,buy_notional,sell_notional,buy_qty,sell_qty,agg_trades)
                   VALUES(?,?,?,?,?,?,?)""",
                rows,
            )

        for symbol, ts, buy_notional, sell_notional in history_updates:
            self.flow_history[symbol].append((ts, buy_notional, sell_notional))
        for key in keys:
            self.flow_buckets.pop(key, None)

    def _sample_depth(self, ts: int) -> None:
        rows = []
        updates = []
        for symbol in self.micro:
            depth = self.depth_latest.get(symbol)
            if depth is None:
                continue
            event_ts, bid_n, ask_n, imbalance, spread, best_bid_qty, best_ask_qty = depth
            if ts - event_ts > 3_000:
                continue

            prev = self.depth_previous.get(symbol)
            prev_ts = None
            prev_bid = bid_n
            prev_ask = ask_n
            if prev is not None:
                if len(prev) == 3:
                    prev_ts, prev_bid, prev_ask = prev
                else:
                    # Compatibility with an in-memory object created by an older
                    # implementation during tests; do not use a timestamp-less
                    # value as a replenishment baseline.
                    prev = None

            if prev is None or prev_ts is None or ts - prev_ts > 2_500:
                bid_rep = 0.0
                ask_rep = 0.0
            else:
                bid_rep = (bid_n - prev_bid) / prev_bid if prev_bid > 0 else 0.0
                ask_rep = (ask_n - prev_ask) / prev_ask if prev_ask > 0 else 0.0
                bid_rep = max(-5.0, min(5.0, bid_rep))
                ask_rep = max(-5.0, min(5.0, ask_rep))

            rows.append(
                (ts, symbol, spread, bid_n, ask_n, imbalance, best_bid_qty, best_ask_qty, bid_rep, ask_rep)
            )
            updates.append((symbol, ts, bid_n, ask_n, imbalance, bid_rep, ask_rep, spread or 0.0))

        if not rows:
            return
        with connect(self.settings.db_path) as conn:
            conn.executemany(
                """INSERT OR REPLACE INTO depth_samples
                   (ts_ms,symbol,spread_bps,bid_notional_10,ask_notional_10,imbalance,
                    best_bid_qty,best_ask_qty,bid_replenishment,ask_replenishment)
                   VALUES(?,?,?,?,?,?,?,?,?,?)""",
                rows,
            )

        for symbol, row_ts, bid_n, ask_n, imbalance, bid_rep, ask_rep, spread in updates:
            self.depth_previous[symbol] = (row_ts, bid_n, ask_n)
            self.depth_history[symbol].append((row_ts, imbalance, bid_rep, ask_rep, spread))

    @staticmethod
    def _payload_age_ms(payload: dict, ts: int) -> int | None:
        if not payload:
            return None
        stamp = payload.get("E") or payload.get("T")
        if stamp is None:
            return None
        try:
            return max(0, ts - int(stamp))
        except (TypeError, ValueError):
            return None

    def _sample_market(self, ts: int) -> int:
        rows = []
        history_updates = []
        for symbol in self.universe:
            ticker = self.tickers.get(symbol)
            if not ticker:
                continue
            event_ts = int(ticker.get("E") or ts)
            if ts - event_ts > 15_000:
                continue
            last = _f(ticker.get("c"))
            if last <= 0:
                continue

            book = self.books.get(symbol, {})
            book_age = self._payload_age_ms(book, ts)
            if book_age is None or book_age > 10_000:
                book = {}

            mark = self.marks.get(symbol, {})
            mark_age = self._payload_age_ms(mark, ts)
            if mark_age is None or mark_age > 3_000:
                mark = {}

            rows.append(
                (
                    ts,
                    symbol,
                    last,
                    _f(book.get("b"), None),
                    _f(book.get("a"), None),
                    _f(book.get("B"), None),
                    _f(book.get("A"), None),
                    _f(mark.get("p"), None),
                    _f(mark.get("i"), None),
                    _f(mark.get("r"), None),
                    _f(ticker.get("q"), None),
                    int(ticker.get("n") or 0),
                )
            )
            history_updates.append((symbol, ts, last))

        if not rows:
            return 0
        with connect(self.settings.db_path) as conn:
            conn.executemany(
                """INSERT OR REPLACE INTO market_samples
                   (ts_ms,symbol,last_price,bid,ask,bid_qty,ask_qty,mark_price,index_price,
                    funding_rate,quote_volume_24h,trade_count_24h)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                rows,
            )

        for symbol, row_ts, last in history_updates:
            self.history[symbol].append((row_ts, last))
        return len(rows)

    def _open_paper(self, signal, ts: int, signal_json: str) -> None:
        with connect(self.settings.db_path) as conn:
            a_row = conn.execute(
                "SELECT last_price FROM market_samples WHERE symbol=? AND ts_ms=?",
                (signal.symbol_a, ts),
            ).fetchone()
            b_row = None
            if signal.symbol_b:
                b_row = conn.execute(
                    "SELECT last_price FROM market_samples WHERE symbol=? AND ts_ms=?",
                    (signal.symbol_b, ts),
                ).fetchone()

            if a_row is None or (signal.symbol_b and b_row is None):
                conn.execute(
                    "INSERT INTO participant_events(ts_ms,participant_id,symbol,event_type,payload_json) VALUES(?,?,?,?,?)",
                    (ts, signal.participant_id, signal.key, "PAPER_SKIPPED_NO_ENTRY_SAMPLE", signal_json),
                )
                return

            a = float(a_row["last_price"])
            b = float(b_row["last_price"]) if b_row is not None else None
            open_count = conn.execute(
                "SELECT COUNT(*) FROM paper_trades WHERE participant_id=? AND status='OPEN'",
                (signal.participant_id,),
            ).fetchone()[0]
            duplicate = conn.execute(
                """SELECT COUNT(*) FROM paper_trades WHERE participant_id=? AND status='OPEN'
                   AND symbol_a=? AND COALESCE(symbol_b,'')=COALESCE(?, '')""",
                (signal.participant_id, signal.symbol_a, signal.symbol_b),
            ).fetchone()[0]
            if open_count >= self.settings.paper_max_open or duplicate:
                conn.execute(
                    "INSERT INTO participant_events(ts_ms,participant_id,symbol,event_type,payload_json) VALUES(?,?,?,?,?)",
                    (ts, signal.participant_id, signal.key, "PAPER_SKIPPED_CAPACITY", signal_json),
                )
                return

            equity = conn.execute(
                "SELECT equity FROM participants WHERE participant_id=?",
                (signal.participant_id,),
            ).fetchone()[0]
            notional = max(
                1.0,
                min(
                    self.settings.paper_starting_notional_usdt,
                    equity / max(1, self.settings.paper_max_open),
                ),
            )
            conn.execute(
                """INSERT INTO paper_trades
                   (trade_id,participant_id,symbol_a,symbol_b,side_a,side_b,hedge_ratio,opened_at_ms,
                    entry_a,entry_b,exit_due_ms,notional_usdt,status,signal_json)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?, 'OPEN', ?)""",
                (
                    uuid.uuid4().hex,
                    signal.participant_id,
                    signal.symbol_a,
                    signal.symbol_b,
                    signal.side_a,
                    signal.side_b,
                    signal.hedge_ratio,
                    ts,
                    a,
                    b,
                    ts + signal.horizon_seconds * 1000,
                    notional,
                    signal_json,
                ),
            )
            conn.execute(
                "INSERT INTO participant_events(ts_ms,participant_id,symbol,event_type,payload_json) VALUES(?,?,?,?,?)",
                (ts, signal.participant_id, signal.key, "PAPER_OPEN", signal_json),
            )

    @staticmethod
    def _common_pair_sample(conn, symbol_a: str, symbol_b: str, target_ms: int, grace_ms: int):
        return conn.execute(
            """SELECT a.ts_ms,a.last_price AS price_a,b.last_price AS price_b
               FROM market_samples a JOIN market_samples b ON b.ts_ms=a.ts_ms
               WHERE a.symbol=? AND b.symbol=? AND a.ts_ms>=? AND a.ts_ms<=?
               ORDER BY a.ts_ms ASC LIMIT 1""",
            (symbol_a, symbol_b, target_ms, target_ms + grace_ms),
        ).fetchone()

    def _close_due_paper(self, ts: int) -> None:
        closed = 0
        invalid = 0
        grace_ms = self.settings.paper_exit_grace_seconds * 1000
        with connect(self.settings.db_path) as conn:
            trades = conn.execute(
                "SELECT * FROM paper_trades WHERE status='OPEN' AND exit_due_ms<=? ORDER BY exit_due_ms LIMIT 100",
                (ts,),
            ).fetchall()
            for trade in trades:
                if trade["symbol_b"]:
                    pair = self._common_pair_sample(
                        conn,
                        trade["symbol_a"],
                        trade["symbol_b"],
                        trade["exit_due_ms"],
                        grace_ms,
                    )
                    if pair is None:
                        pa = pb = None
                    else:
                        pa = {"ts_ms": pair["ts_ms"], "last_price": pair["price_a"]}
                        pb = {"ts_ms": pair["ts_ms"], "last_price": pair["price_b"]}
                else:
                    pa = self._sample_at_or_after(
                        conn,
                        trade["symbol_a"],
                        trade["exit_due_ms"],
                        grace_ms,
                    )
                    pb = None

                if pa is None or (trade["symbol_b"] and pb is None):
                    if ts <= trade["exit_due_ms"] + grace_ms:
                        continue
                    conn.execute(
                        "UPDATE paper_trades SET status='INVALID_GAP',closed_at_ms=? WHERE trade_id=?",
                        (ts, trade["trade_id"]),
                    )
                    display = (
                        trade["symbol_a"]
                        if not trade["symbol_b"]
                        else f"{trade['symbol_a']}|{trade['symbol_b']}"
                    )
                    conn.execute(
                        "INSERT INTO participant_events(ts_ms,participant_id,symbol,event_type,payload_json) VALUES(?,?,?,?,?)",
                        (
                            ts,
                            trade["participant_id"],
                            display,
                            "PAPER_INVALID_GAP",
                            json.dumps(
                                {
                                    "trade_id": trade["trade_id"],
                                    "exit_due_ms": trade["exit_due_ms"],
                                    "grace_ms": grace_ms,
                                    "requires_common_pair_tick": bool(trade["symbol_b"]),
                                }
                            ),
                        ),
                    )
                    invalid += 1
                    continue

                exit_a = float(pa["last_price"])
                exit_b = float(pb["last_price"]) if pb else None
                close_ts = int(pa["ts_ms"])
                ra = self._directional_return(trade["side_a"], trade["entry_a"], exit_a)
                if trade["symbol_b"]:
                    rb = self._directional_return(trade["side_b"], trade["entry_b"], exit_b)
                    beta = abs(trade["hedge_ratio"] or 1.0)
                    wa, wb = 1.0 / (1.0 + beta), beta / (1.0 + beta)
                    gross = wa * ra + wb * rb
                else:
                    gross = ra

                roundtrip_cost = 2 * (
                    self.settings.paper_fee_bps_per_side + self.settings.paper_slippage_bps_per_side
                ) / 10000.0
                net = gross - roundtrip_cost
                pnl = trade["notional_usdt"] * net
                conn.execute(
                    """UPDATE paper_trades SET status='CLOSED',closed_at_ms=?,exit_a=?,exit_b=?,
                       gross_return_pct=?,net_return_pct=?,pnl_usdt=? WHERE trade_id=?""",
                    (close_ts, exit_a, exit_b, gross * 100, net * 100, pnl, trade["trade_id"]),
                )
                conn.execute(
                    "UPDATE participants SET equity=equity+? WHERE participant_id=?",
                    (pnl, trade["participant_id"]),
                )
                display = (
                    trade["symbol_a"]
                    if not trade["symbol_b"]
                    else f"{trade['symbol_a']}|{trade['symbol_b']}"
                )
                conn.execute(
                    "INSERT INTO participant_events(ts_ms,participant_id,symbol,event_type,payload_json) VALUES(?,?,?,?,?)",
                    (
                        close_ts,
                        trade["participant_id"],
                        display,
                        "PAPER_CLOSE",
                        json.dumps(
                            {
                                "trade_id": trade["trade_id"],
                                "gross_return_pct": gross * 100,
                                "net_return_pct": net * 100,
                                "pnl_usdt": pnl,
                                "exit_sample_ms": close_ts,
                                "common_pair_tick": bool(trade["symbol_b"]),
                            }
                        ),
                    ),
                )
                closed += 1

        if closed or invalid:
            self._health("paper_book", "OK", f"closed={closed} invalid_gap={invalid}", ts)
