package com.example.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ExchangeController {
    private final JdbcTemplate db;
    private final TransactionTemplate riskTransaction;
    private final MarketCommandProcessors markets;
    public ExchangeController(JdbcTemplate db, PlatformTransactionManager manager, MarketCommandProcessors markets) {
        this.db = db;
        this.markets = markets;
        this.riskTransaction = new TransactionTemplate(manager);
        this.riskTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record OrderRequest(String clientId, String accountId, String symbol, String side, long priceCents, long quantity) {}
    public record MarkRequest(long priceCents) {}

    @GetMapping("/markets")
    public List<Map<String,Object>> markets() { return db.queryForList("SELECT * FROM instruments ORDER BY symbol"); }
    @GetMapping("/accounts")
    public List<Map<String,Object>> accounts() {
        return db.queryForList("SELECT a.id, a.cash_cents, COALESCE((SELECT SUM(o.reserved_cents) FROM orders o WHERE o.account_id=a.id),0) AS reserved_cents, COALESCE((SELECT SUM(CEIL(ABS(p.quantity::numeric)*p.entry_cents/5)) FROM positions p WHERE p.account_id=a.id),0) AS position_margin_cents, a.cash_cents + COALESCE((SELECT SUM(p.quantity::numeric*(i.mark_cents-p.entry_cents)) FROM positions p JOIN instruments i ON i.symbol=p.symbol WHERE p.account_id=a.id),0) AS equity_cents, COALESCE((SELECT SUM(CEIL(ABS(p.quantity::numeric)*i.mark_cents*12/100)) FROM positions p JOIN instruments i ON i.symbol=p.symbol WHERE p.account_id=a.id),0) AS maintenance_margin_cents FROM accounts a ORDER BY a.id");
    }
    @GetMapping("/orders")
    public List<Map<String,Object>> orders() { return db.queryForList("SELECT * FROM orders ORDER BY sequence DESC"); }
    @GetMapping("/positions")
    public List<Map<String,Object>> positions() { return db.queryForList("SELECT p.*, i.mark_cents, p.quantity::numeric*(i.mark_cents-p.entry_cents) AS unrealized_pnl_cents FROM positions p JOIN instruments i ON i.symbol=p.symbol WHERE p.quantity<>0 ORDER BY p.account_id,p.symbol"); }
    @GetMapping("/fills")
    public List<Map<String,Object>> fills() { return db.queryForList("SELECT * FROM fills ORDER BY id DESC"); }
    @GetMapping("/events")
    public List<Map<String,Object>> events() { return db.queryForList("SELECT * FROM events ORDER BY id DESC LIMIT 100"); }
    @GetMapping("/liquidations")
    public List<Map<String,Object>> liquidations() { return db.queryForList("SELECT * FROM liquidation_episodes ORDER BY id DESC"); }

    @Bean
    ApplicationRunner recoverLiquidations(TransactionTemplate tx) {
        return args -> tx.executeWithoutResult(status -> {
            db.queryForList("SELECT pg_advisory_xact_lock(471102)");
            reconcileRisk();
        });
    }

    @PutMapping("/markets/{symbol}/mark")
    public Map<String,Object> updateMark(@PathVariable String symbol, @RequestBody MarkRequest request) {
        if (request.priceCents() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mark must be positive");
        return markets.execute(symbol, () -> riskTransaction.execute(status -> updateMarkInTransaction(symbol, request)));
    }

    private Map<String,Object> updateMarkInTransaction(String symbol, MarkRequest request) {
        db.queryForList("SELECT pg_advisory_xact_lock(471102)");
        if (db.update("UPDATE instruments SET mark_cents=? WHERE symbol=?", request.priceCents(), symbol) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Instrument not found");
        db.update("INSERT INTO events(description) VALUES (?)", "Mark changed: " + symbol + " = " + request.priceCents() + " cents");
        reconcileRisk();
        return db.queryForMap("SELECT * FROM instruments WHERE symbol=?", symbol);
    }

    @PostMapping("/orders")
    public Map<String,Object> submit(@RequestBody OrderRequest r) {
        if (r.clientId() == null || r.clientId().isBlank() || r.accountId() == null || r.symbol() == null || !("BUY".equals(r.side()) || "SELL".equals(r.side())) || r.priceCents() <= 0 || r.quantity() <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid order");
        return markets.execute(r.symbol(), () -> riskTransaction.execute(status -> submitInTransaction(r)));
    }

    private Map<String,Object> submitInTransaction(OrderRequest r) {
        // Normal commands share a gate. Risk reconciliation takes it exclusively.
        // Within the gate, market -> client ID -> sorted account rows is the lock order.
        db.queryForList("SELECT pg_advisory_xact_lock_shared(471102)");
        if (db.queryForObject("SELECT count(*) FROM instruments WHERE symbol=?", Integer.class, r.symbol()) == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown instrument");
        lockMarket(r.symbol());
        db.queryForList("SELECT pg_advisory_xact_lock(471103, hashtext(?))", r.clientId());
        // Lock every account whose resting order might be matched before locking any
        // order rows. Sorted acquisition prevents cross-market account deadlocks.
        var rows = db.queryForList("SELECT * FROM accounts WHERE id=? OR id IN (SELECT account_id FROM orders WHERE symbol=? AND side=? AND status='OPEN' AND price_cents " + ("BUY".equals(r.side()) ? "<=?" : ">=?") + ") ORDER BY id FOR UPDATE", r.accountId(), r.symbol(), "BUY".equals(r.side()) ? "SELL" : "BUY", r.priceCents());
        rows = rows.stream().filter(row -> r.accountId().equals(row.get("id"))).toList();
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        var previous = db.queryForList("SELECT * FROM orders WHERE client_id=?", r.clientId());
        if (!previous.isEmpty()) {
            var p = previous.get(0);
            if (!p.get("account_id").equals(r.accountId()) || !p.get("symbol").equals(r.symbol()) || !p.get("side").equals(r.side()) || ((Number)p.get("price_cents")).longValue()!=r.priceCents() || ((Number)p.get("quantity")).longValue()!=r.quantity())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key reused with different payload");
            return p;
        }
        if (db.queryForObject("SELECT count(*) FROM liquidation_episodes WHERE account_id=? AND status='ACTIVE'", Integer.class, r.accountId()) > 0 || breached(r.accountId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account is under liquidation");
        long reserve;
        try { reserve = Math.addExact(Math.multiplyExact(r.priceCents(), r.quantity()), 4) / 5; }
        catch (ArithmeticException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Notional too large"); }
        long equity = db.queryForObject("SELECT cash_cents + COALESCE((SELECT SUM(p.quantity::numeric*(i.mark_cents-p.entry_cents)) FROM positions p JOIN instruments i ON i.symbol=p.symbol WHERE p.account_id=a.id),0) FROM accounts a WHERE id=?", Long.class, r.accountId());
        long margin = db.queryForObject("SELECT COALESCE(SUM(CEIL(ABS(quantity::numeric)*entry_cents/5)),0) FROM positions WHERE account_id=?", Long.class, r.accountId());
        long reserved = db.queryForObject("SELECT COALESCE(SUM(reserved_cents),0) FROM orders WHERE account_id=?", Long.class, r.accountId());
        if (BigDecimal.valueOf(reserve).compareTo(BigDecimal.valueOf(equity).subtract(BigDecimal.valueOf(reserved)).subtract(BigDecimal.valueOf(margin))) > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient available collateral");
        db.update("INSERT INTO orders(client_id,account_id,symbol,side,price_cents,quantity,remaining,status,reserved_cents) VALUES (?,?,?,?,?,?,?,'OPEN',?)", r.clientId(), r.accountId(), r.symbol(), r.side(), r.priceCents(), r.quantity(), r.quantity(), reserve);
        db.update("INSERT INTO events(description) VALUES (?)", "Order accepted: " + r.clientId());
        if (match(r.clientId())) reconcileAfterCommit();
        return db.queryForMap("SELECT * FROM orders WHERE client_id=?", r.clientId());
    }

    private static long number(Map<String,Object> row, String name) { return ((Number) row.get(name)).longValue(); }

    private boolean match(String clientId) {
        boolean filled = false;
        while (true) {
            var incoming = db.queryForMap("SELECT * FROM orders WHERE client_id=?", clientId);
            long remaining = number(incoming, "remaining");
            if (remaining == 0 || !"OPEN".equals(incoming.get("status"))) return filled;
            long incomingCap = reduceOnlyCap(incoming);
            if (incomingCap == 0) { cancelRemainder(incoming); return filled; }
            boolean buy = incoming.get("side").equals("BUY");
            String sql = "SELECT * FROM orders WHERE symbol=? AND side=? AND status='OPEN' AND account_id<>? AND price_cents " + (buy ? "<=? ORDER BY price_cents ASC, sequence ASC" : ">=? ORDER BY price_cents DESC, sequence ASC") + " LIMIT 1 FOR UPDATE";
            var matches = db.queryForList(sql, incoming.get("symbol"), buy ? "SELL" : "BUY", incoming.get("account_id"), incoming.get("price_cents"));
            if (matches.isEmpty()) return filled;
            var resting = matches.get(0);
            // A concurrent fill on the other market may have made this resting
            // account unsafe before exclusive reconciliation gets the gate.
            if (!(Boolean) resting.get("reduce_only") && (breached((String)resting.get("account_id")) || db.queryForObject("SELECT count(*) FROM liquidation_episodes WHERE account_id=? AND status='ACTIVE'", Integer.class, resting.get("account_id")) > 0)) { cancelRemainder(resting); filled = true; continue; }
            long restingCap = reduceOnlyCap(resting);
            if (restingCap == 0) { cancelRemainder(resting); continue; }
            long quantity = Math.min(Math.min(remaining, number(resting, "remaining")), Math.min(incomingCap, restingCap));
            long price = number(resting, "price_cents");
            // These accounts were locked in sorted order before matching began.
            db.queryForList("SELECT id FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE", incoming.get("account_id"), resting.get("account_id"));
            if (!(Boolean) incoming.get("reduce_only") && !canFill(incoming, buy ? quantity : -quantity, price)) { cancelRemainder(incoming); return filled; }
            if (!(Boolean) resting.get("reduce_only") && !canFill(resting, buy ? -quantity : quantity, price)) { cancelRemainder(resting); continue; }
            updatePosition((String)incoming.get("account_id"), (String)incoming.get("symbol"), buy ? quantity : -quantity, price);
            updatePosition((String)resting.get("account_id"), (String)resting.get("symbol"), buy ? -quantity : quantity, price);
            updateOrder(incoming, quantity);
            updateOrder(resting, quantity);
            if ((Boolean)incoming.get("reduce_only") && reduceOnlyCap(incoming) == 0) cancelRemainder(incoming);
            if ((Boolean)resting.get("reduce_only") && reduceOnlyCap(resting) == 0) cancelRemainder(resting);
            long buyId = number(buy ? incoming : resting, "sequence");
            long sellId = number(buy ? resting : incoming, "sequence");
            db.update("INSERT INTO fills(buy_order,sell_order,quantity,price_cents) VALUES (?,?,?,?)", buyId, sellId, quantity, price);
            filled = true;
            db.update("INSERT INTO events(description) VALUES (?)", "Fill: " + quantity + " " + incoming.get("symbol") + " at " + price + " cents");
        }
    }

    private void lockMarket(String symbol) {
        db.queryForList("SELECT pg_advisory_xact_lock(471104, hashtext(?))", symbol);
    }

    private void reconcileAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    riskTransaction.executeWithoutResult(status -> {
                        db.queryForList("SELECT pg_advisory_xact_lock(471102)");
                        reconcileRisk();
                    });
                } catch (RuntimeException ex) {
                    // The fill is already committed. Startup and future fills/marks
                    // retry reconciliation; never report the committed fill as failed.
                    LoggerFactory.getLogger(ExchangeController.class).error("Post-fill risk reconciliation failed", ex);
                }
            }
        });
    }

    @Scheduled(fixedDelayString = "${exchange.risk.retry-delay-ms:5000}", initialDelayString = "${exchange.risk.retry-delay-ms:5000}")
    public void retryRiskReconciliation() {
        try {
            riskTransaction.executeWithoutResult(status -> {
                if (!Boolean.TRUE.equals(db.queryForObject("SELECT pg_try_advisory_xact_lock(471102)", Boolean.class))) return;
                reconcileRisk();
            });
        } catch (RuntimeException ex) {
            LoggerFactory.getLogger(ExchangeController.class).error("Scheduled risk reconciliation failed; will retry", ex);
        }
    }

    private void updateOrder(Map<String,Object> order, long filled) {
        long left = number(order, "remaining") - filled;
        long reserve = (Boolean)order.get("reduce_only") ? 0 : BigDecimal.valueOf(left).multiply(BigDecimal.valueOf(number(order,"price_cents"))).divide(BigDecimal.valueOf(5), 0, RoundingMode.CEILING).longValueExact();
        db.update("UPDATE orders SET remaining=?, reserved_cents=?, status=? WHERE sequence=?", left, reserve, left == 0 ? "FILLED" : "OPEN", order.get("sequence"));
    }

    private long reduceOnlyCap(Map<String,Object> order) {
        if (!(Boolean) order.get("reduce_only")) return Long.MAX_VALUE;
        var rows = db.queryForList("SELECT quantity FROM positions WHERE account_id=? AND symbol=?", order.get("account_id"), order.get("symbol"));
        long position = rows.isEmpty() ? 0 : number(rows.get(0), "quantity");
        boolean reduces = (position > 0 && "SELL".equals(order.get("side"))) || (position < 0 && "BUY".equals(order.get("side")));
        return reduces ? Math.abs(position) : 0;
    }

    private void cancelRemainder(Map<String,Object> order) {
        db.update("UPDATE orders SET status='CANCELLED', reserved_cents=0 WHERE sequence=? AND status='OPEN'", order.get("sequence"));
    }

    private record PositionChange(long quantity, long entry, long realized) {}

    private PositionChange positionChange(long old, long entry, long delta, long price) {
        long next = Math.addExact(old, delta), newEntry, realized = 0;
        if (old == 0 || Long.signum(old) == Long.signum(delta)) {
            newEntry = BigDecimal.valueOf(old).abs().multiply(BigDecimal.valueOf(entry)).add(BigDecimal.valueOf(delta).abs().multiply(BigDecimal.valueOf(price))).divide(BigDecimal.valueOf(next).abs(), 0, RoundingMode.HALF_UP).longValueExact();
        } else {
            long closed = Math.min(Math.abs(old), Math.abs(delta));
            realized = Math.multiplyExact(Math.multiplyExact(closed, Math.subtractExact(price, entry)), Long.signum(old));
            newEntry = next == 0 ? 0 : (Long.signum(next) == Long.signum(old) ? entry : price);
        }
        return new PositionChange(next, newEntry, realized);
    }

    private BigDecimal initialMargin(long quantity, long price) {
        return BigDecimal.valueOf(quantity).abs().multiply(BigDecimal.valueOf(price)).divide(BigDecimal.valueOf(5), 0, RoundingMode.CEILING);
    }

    // A sell may execute above its limit at a resting bid, increasing the
    // required position margin. Recheck both sides under their account locks.
    private boolean canFill(Map<String,Object> order, long delta, long price) {
        String account = (String) order.get("account_id"), symbol = (String) order.get("symbol");
        var positions = db.queryForList("SELECT quantity,entry_cents FROM positions WHERE account_id=? AND symbol=?", account, symbol);
        long old = positions.isEmpty() ? 0 : number(positions.get(0), "quantity");
        long entry = positions.isEmpty() ? 0 : number(positions.get(0), "entry_cents");
        var change = positionChange(old, entry, delta, price);
        if (old != 0 && Long.signum(old) != Long.signum(delta) && BigDecimal.valueOf(delta).abs().compareTo(BigDecimal.valueOf(old).abs()) <= 0) return true;
        var totals = db.queryForMap("SELECT a.cash_cents + COALESCE((SELECT SUM(p.quantity::numeric*(i.mark_cents-p.entry_cents)) FROM positions p JOIN instruments i ON i.symbol=p.symbol WHERE p.account_id=a.id),0) AS equity, COALESCE((SELECT SUM(CEIL(ABS(p.quantity::numeric)*p.entry_cents/5)) FROM positions p WHERE p.account_id=a.id),0) AS margin, COALESCE((SELECT SUM(reserved_cents) FROM orders WHERE account_id=a.id),0) AS reserved FROM accounts a WHERE id=?", account);
        long mark = db.queryForObject("SELECT mark_cents FROM instruments WHERE symbol=?", Long.class, symbol);
        BigDecimal equity = new BigDecimal(totals.get("equity").toString())
            .subtract(BigDecimal.valueOf(old).multiply(BigDecimal.valueOf(mark - entry)))
            .add(BigDecimal.valueOf(change.realized()))
            .add(BigDecimal.valueOf(change.quantity()).multiply(BigDecimal.valueOf(mark - change.entry())));
        BigDecimal margin = new BigDecimal(totals.get("margin").toString()).subtract(initialMargin(old, entry)).add(initialMargin(change.quantity(), change.entry()));
        BigDecimal reservation = new BigDecimal(totals.get("reserved").toString()).subtract(BigDecimal.valueOf(number(order,"reserved_cents")))
            .add(initialMargin(number(order,"remaining") - Math.abs(delta), number(order,"price_cents")));
        return equity.subtract(margin).subtract(reservation).signum() >= 0;
    }

    private boolean breached(String account) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT (a.cash_cents + COALESCE(SUM(p.quantity::numeric*(i.mark_cents-p.entry_cents)),0)) < COALESCE(SUM(CEIL(ABS(p.quantity::numeric)*i.mark_cents*12/100)),0) FROM accounts a LEFT JOIN positions p ON p.account_id=a.id LEFT JOIN instruments i ON i.symbol=p.symbol WHERE a.id=? GROUP BY a.id", Boolean.class, account));
    }

    // Caller holds the exclusive risk gate. Liquidate one symbol at a time,
    // alphabetically; an episode stays active until every position is closed.
    private void reconcileRisk() {
        var accounts = db.queryForList("SELECT id FROM accounts ORDER BY id", String.class);
        for (String account : accounts) {
            var episodes = db.queryForList("SELECT id FROM liquidation_episodes WHERE account_id=? AND status='ACTIVE'", account);
            if (episodes.isEmpty() && (!breached(account) || db.queryForObject("SELECT count(*) FROM positions WHERE account_id=? AND quantity<>0", Integer.class, account) == 0)) continue;
            if (episodes.isEmpty()) {
                db.update("INSERT INTO liquidation_episodes(account_id,status) VALUES (?,'ACTIVE')", account);
                db.update("INSERT INTO events(description) VALUES (?)", "Liquidation started: " + account);
                episodes = db.queryForList("SELECT id FROM liquidation_episodes WHERE account_id=? AND status='ACTIVE'", account);
            }
            long episode = number(episodes.get(0), "id");
            db.update("UPDATE orders SET status='CANCELLED', reserved_cents=0 WHERE account_id=? AND status='OPEN' AND NOT reduce_only", account);
            var positions = db.queryForList("SELECT p.symbol,p.quantity,i.mark_cents FROM positions p JOIN instruments i ON i.symbol=p.symbol WHERE p.account_id=? AND p.quantity<>0 ORDER BY p.symbol", account);
            if (positions.isEmpty()) {
                db.update("UPDATE orders SET status='CANCELLED', reserved_cents=0 WHERE liquidation_episode_id=? AND status='OPEN'", episode);
                db.update("UPDATE liquidation_episodes SET status='COMPLETE',completed_at=now() WHERE id=?", episode);
                db.update("INSERT INTO events(description) VALUES (?)", "Liquidation completed: " + account);
                continue;
            }
            var position = positions.get(0);
            var open = db.queryForList("SELECT client_id FROM orders WHERE liquidation_episode_id=? AND symbol=? AND status='OPEN'", episode, position.get("symbol"));
            if (open.isEmpty()) {
                long quantity = number(position,"quantity");
                String client = "liquidation-" + episode + "-" + position.get("symbol") + "-" + java.util.UUID.randomUUID();
                db.update("INSERT INTO orders(client_id,account_id,symbol,side,price_cents,quantity,remaining,status,reserved_cents,reduce_only,liquidation_episode_id) VALUES (?,?,?,?,?,?,?,'OPEN',0,true,?)", client, account, position.get("symbol"), quantity > 0 ? "SELL" : "BUY", position.get("mark_cents"), Math.abs(quantity), Math.abs(quantity), episode);
                db.update("INSERT INTO events(description) VALUES (?)", "Liquidation order created: " + account + " " + position.get("symbol"));
                match(client);
                // Matching can close this position immediately; process the next one.
                if (db.queryForObject("SELECT count(*) FROM orders WHERE client_id=? AND status='OPEN'", Integer.class, client) == 0) reconcileRisk();
            }
        }
    }

    private void updatePosition(String account, String symbol, long delta, long price) {
        db.update("INSERT INTO positions(account_id,symbol,quantity,entry_cents) VALUES (?,?,0,0) ON CONFLICT DO NOTHING", account, symbol);
        var position = db.queryForMap("SELECT * FROM positions WHERE account_id=? AND symbol=? FOR UPDATE", account, symbol);
        long old = number(position,"quantity"), entry = number(position,"entry_cents");
        PositionChange change = positionChange(old, entry, delta, price);
        db.update("UPDATE positions SET quantity=?, entry_cents=? WHERE account_id=? AND symbol=?", change.quantity(), change.entry(), account, symbol);
        if (change.realized() != 0) db.update("UPDATE accounts SET cash_cents=cash_cents+? WHERE id=?", change.realized(), account);
    }

    @DeleteMapping("/orders/{clientId}")
    public Map<String,Object> cancel(@PathVariable String clientId) {
        var symbols = db.queryForList("SELECT symbol FROM orders WHERE client_id=?", String.class, clientId);
        if (symbols.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        return markets.execute(symbols.get(0), () -> riskTransaction.execute(status -> cancelInTransaction(clientId)));
    }

    private Map<String,Object> cancelInTransaction(String clientId) {
        db.queryForList("SELECT pg_advisory_xact_lock_shared(471102)");
        var found = db.queryForList("SELECT account_id,symbol FROM orders WHERE client_id=?", clientId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        lockMarket((String)found.get(0).get("symbol"));
        if (Boolean.TRUE.equals(db.queryForObject("SELECT reduce_only FROM orders WHERE client_id=?", Boolean.class, clientId))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Liquidation orders cannot be cancelled manually");
        db.queryForMap("SELECT id FROM accounts WHERE id=? FOR UPDATE", found.get(0).get("account_id"));
        int updated = db.update("UPDATE orders SET status='CANCELLED', reserved_cents=0 WHERE client_id=? AND status='OPEN'", clientId);
        if (updated != 0) db.update("INSERT INTO events(description) VALUES (?)", "Order cancelled: " + clientId);
        return db.queryForMap("SELECT * FROM orders WHERE client_id=?", clientId);
    }
}
