package com.example.exchange;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** Enabled only for the separate throwaway demonstration database. */
@RestController
@ConditionalOnProperty(name = "exchange.demo.enabled", havingValue = "true")
public class DemoResetController {
    private final JdbcTemplate db;
    public DemoResetController(JdbcTemplate db) { this.db = db; }

    @PostMapping("/api/demo/reset")
    @Transactional
    public Map<String,String> reset() {
        db.queryForList("SELECT pg_advisory_xact_lock(471102)");
        db.execute("TRUNCATE fills, orders, positions, liquidation_episodes CASCADE");
        db.update("DELETE FROM events");
        db.update("DELETE FROM accounts");
        db.update("INSERT INTO accounts(id,cash_cents) VALUES ('alice',10000),('maker',100000000),('buyer1',100000000),('buyer2',100000000)");
        db.update("UPDATE instruments SET mark_cents=5000");
        db.update("INSERT INTO events(description) VALUES ('Demo reset: isolated demo accounts restored')");
        return Map.of("status", "reset");
    }
}
