package com.example.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.concurrent.*;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"exchange.risk.retry-delay-ms=1000", "server.shutdown=immediate"})
class MatchingIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String external = System.getenv("TEST_DATABASE_URL");
        if (external != null && !external.isBlank()) {
            if (!external.matches("^jdbc:postgresql://[^/]+/[a-zA-Z0-9_]*_test(?:\\?.*)?$"))
                throw new IllegalArgumentException("TEST_DATABASE_URL must point to a database whose name ends in _test");
            r.add("spring.datasource.url", () -> external);
            r.add("spring.datasource.username", () -> "exchange");
            r.add("spring.datasource.password", () -> "exchange");
        } else {
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
            postgres.start();
            r.add("spring.datasource.url", postgres::getJdbcUrl);
            r.add("spring.datasource.username", postgres::getUsername);
            r.add("spring.datasource.password", postgres::getPassword);
        }
    }
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate db;
    @LocalServerPort int port;
    @Autowired org.springframework.boot.ApplicationRunner recoverLiquidations;

    @BeforeEach void reset() {
        db.execute("TRUNCATE fills, orders, positions, events, liquidation_episodes RESTART IDENTITY CASCADE");
        db.execute("DELETE FROM accounts");
        db.update("UPDATE instruments SET mark_cents=5000");
        db.update("INSERT INTO accounts VALUES ('alice',100000),('maker1',100000),('maker2',100000),('maker3',100000)");
    }
    void order(String id, String account, String side, long price, long quantity) {
        var request = new ExchangeController.OrderRequest(id, account, "BTC-PERP", side, price, quantity);
        assertThat(rest.postForEntity("/api/orders", request, String.class).getStatusCode().is2xxSuccessful()).isTrue();
    }
    @Test void betterPriceThenTimePriorityAndPartialFill() {
        order("s1", "maker1", "SELL", 5100, 2);
        order("s2", "maker2", "SELL", 5000, 2);
        order("s3", "maker3", "SELL", 5000, 2);
        order("b1", "alice", "BUY", 5100, 3);
        assertThat(db.queryForList("SELECT sell_order FROM fills ORDER BY id", Long.class)).containsExactly(2L, 3L);
        assertThat(db.queryForObject("SELECT remaining FROM orders WHERE client_id='s3'", Long.class)).isEqualTo(1L);
        assertThat(db.queryForObject("SELECT quantity FROM positions WHERE account_id='alice'", Long.class)).isEqualTo(3L);
        assertThat(db.queryForObject("SELECT entry_cents FROM positions WHERE account_id='alice'", Long.class)).isEqualTo(5000L);
    }
    @Test void closingPositionRealizesPnlAndReversalChangesBasis() {
        order("sell", "maker1", "SELL", 5000, 2);
        order("buy", "alice", "BUY", 5000, 2);
        order("bid", "maker2", "BUY", 5500, 3);
        order("close-and-reverse", "alice", "SELL", 5500, 3);
        assertThat(db.queryForObject("SELECT cash_cents FROM accounts WHERE id='alice'", Long.class)).isEqualTo(101000L);
        assertThat(db.queryForObject("SELECT quantity FROM positions WHERE account_id='alice'", Long.class)).isEqualTo(-1L);
        assertThat(db.queryForObject("SELECT entry_cents FROM positions WHERE account_id='alice'", Long.class)).isEqualTo(5500L);
    }
    @Test void partialLiquidationRecoversAndCannotReverse() throws Exception {
        db.update("UPDATE accounts SET cash_cents=10000 WHERE id='alice'");
        order("initial-sell", "maker1", "SELL", 5000, 10);
        order("initial-buy", "alice", "BUY", 5000, 10);
        assertThat(rest.exchange("/api/markets/BTC-PERP/mark", org.springframework.http.HttpMethod.PUT, new org.springframework.http.HttpEntity<>(new ExchangeController.MarkRequest(4500)), String.class).getStatusCode().is2xxSuccessful()).isTrue();
        order("buyer1", "maker2", "BUY", 4500, 5);
        assertThat(db.queryForObject("SELECT quantity FROM positions WHERE account_id='alice' AND symbol='BTC-PERP'", Long.class)).isEqualTo(5L);
        assertThat(db.queryForObject("SELECT cash_cents FROM accounts WHERE id='alice'", Long.class)).isEqualTo(7500L);
        assertThat(db.queryForObject("SELECT remaining FROM orders WHERE reduce_only AND status='OPEN'", Long.class)).isEqualTo(5L);
        recoverLiquidations.run(null);
        assertThat(db.queryForObject("SELECT count(*) FROM orders WHERE reduce_only", Integer.class)).isEqualTo(1);
        order("buyer2", "maker3", "BUY", 4500, 5);
        assertThat(db.queryForObject("SELECT quantity FROM positions WHERE account_id='alice' AND symbol='BTC-PERP'", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT cash_cents FROM accounts WHERE id='alice'", Long.class)).isEqualTo(5000L);
        assertThat(db.queryForObject("SELECT count(*) FROM fills f JOIN orders o ON o.sequence=f.sell_order WHERE o.reduce_only", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT status FROM liquidation_episodes WHERE account_id='alice'", String.class)).isEqualTo("COMPLETE");
    }
    @Test void recoveryCreatesMissingLiquidationOrder() throws Exception {
        db.update("UPDATE accounts SET cash_cents=10000 WHERE id='alice'");
        order("sell", "maker1", "SELL", 5000, 10);
        order("buy", "alice", "BUY", 5000, 10);
        db.update("UPDATE instruments SET mark_cents=4500 WHERE symbol='BTC-PERP'");
        recoverLiquidations.run(null);
        recoverLiquidations.run(null);
        assertThat(db.queryForObject("SELECT count(*) FROM orders WHERE account_id='alice' AND reduce_only AND status='OPEN'", Integer.class)).isEqualTo(1);
    }
    @Test void scheduledRetryFindsBreachWithoutAnotherCommandOrRestart() throws Exception {
        db.update("UPDATE accounts SET cash_cents=10000 WHERE id='alice'");
        order("timer-sell", "maker1", "SELL", 5000, 10);
        order("timer-buy", "alice", "BUY", 5000, 10);
        // Hold the risk gate over at least one retry tick, then release it.
        // The mark update commits independently, as if detection failed after a commit.
        try (var connection = db.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(471102)")) { statement.execute(); }
            db.update("UPDATE instruments SET mark_cents=4500 WHERE symbol='BTC-PERP'");
            Thread.sleep(1500);
            assertThat(db.queryForObject("SELECT count(*) FROM orders WHERE account_id='alice' AND reduce_only", Integer.class)).isZero();
            connection.rollback();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        int count;
        do {
            count = db.queryForObject("SELECT count(*) FROM orders WHERE account_id='alice' AND reduce_only AND status='OPEN'", Integer.class);
            if (count == 0) Thread.sleep(100);
        } while (count == 0 && System.nanoTime() < deadline);
        assertThat(count).isEqualTo(1);
        // A subsequent retry must preserve the same single liquidation order.
        recoverLiquidations.run(null);
        assertThat(db.queryForObject("SELECT count(*) FROM orders WHERE account_id='alice' AND reduce_only AND status='OPEN'", Integer.class)).isEqualTo(1);
    }
    @Test void staleLiquidationOrderCannotOpenReversePosition() {
        db.update("UPDATE accounts SET cash_cents=10000 WHERE id='alice'");
        order("s", "maker1", "SELL", 5000, 10);
        order("b", "alice", "BUY", 5000, 10);
        rest.exchange("/api/markets/BTC-PERP/mark", org.springframework.http.HttpMethod.PUT, new org.springframework.http.HttpEntity<>(new ExchangeController.MarkRequest(4500)), String.class);
        // Simulate a persisted order whose original size is larger than current exposure.
        db.update("UPDATE positions SET quantity=2 WHERE account_id='alice' AND symbol='BTC-PERP'");
        order("buyer", "maker2", "BUY", 4500, 5);
        assertThat(db.queryForObject("SELECT quantity FROM positions WHERE account_id='alice' AND symbol='BTC-PERP'", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT f.quantity FROM fills f JOIN orders o ON f.sell_order=o.sequence WHERE o.reduce_only", Long.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT status FROM orders WHERE reduce_only", String.class)).isEqualTo("CANCELLED");
    }
    @Test void simultaneousOrdersShareCollateralAndCancellationReleasesIt() throws Exception {
        db.update("UPDATE accounts SET cash_cents=10000 WHERE id='alice'");
        var barrier = new CyclicBarrier(3);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var btc = pool.submit(() -> { barrier.await(); return rest.postForEntity("/api/orders", new ExchangeController.OrderRequest("concurrent-btc", "alice", "BTC-PERP", "BUY", 3500, 10), String.class); });
            var eth = pool.submit(() -> { barrier.await(); return rest.postForEntity("/api/orders", new ExchangeController.OrderRequest("concurrent-eth", "alice", "ETH-PERP", "BUY", 3500, 10), String.class); });
            barrier.await(10, TimeUnit.SECONDS);
            ResponseEntity<String> a = btc.get(20, TimeUnit.SECONDS), b = eth.get(20, TimeUnit.SECONDS);
            assertThat(java.util.List.of(a.getStatusCode().value(), b.getStatusCode().value())).containsExactlyInAnyOrder(200, 409);
            assertThat(db.queryForObject("SELECT sum(reserved_cents) FROM orders WHERE account_id='alice' AND status='OPEN'", Long.class)).isEqualTo(7000);
            assertThat(db.queryForObject("SELECT cash_cents - (SELECT sum(reserved_cents) FROM orders WHERE account_id='alice') FROM accounts WHERE id='alice'", Long.class)).isEqualTo(3000);
            String accepted = a.getStatusCode().is2xxSuccessful() ? "concurrent-btc" : "concurrent-eth";
            String other = a.getStatusCode().is2xxSuccessful() ? "ETH-PERP" : "BTC-PERP";
            assertThat(rest.exchange("/api/orders/" + accepted, org.springframework.http.HttpMethod.DELETE, null, String.class).getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(db.queryForObject("SELECT sum(reserved_cents) FROM orders WHERE account_id='alice'", Long.class)).isZero();
            assertThat(rest.postForEntity("/api/orders", new ExchangeController.OrderRequest("fresh-other", "alice", other, "BUY", 3500, 10), String.class).getStatusCode().is2xxSuccessful()).isTrue();
        } finally { pool.shutdownNow(); }
    }

    @Test void unrelatedMarketContinuesWhileAnAccountIsLocked() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try (var connection = db.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM accounts WHERE id='alice' FOR UPDATE")) { statement.executeQuery(); }
            var blocked = pool.submit(() -> rest.postForEntity("/api/orders", new ExchangeController.OrderRequest("blocked-btc", "alice", "BTC-PERP", "BUY", 3500, 10), String.class));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%SELECT * FROM accounts WHERE id=%'", Integer.class) == 0) Thread.sleep(20);
            assertThat(db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%SELECT * FROM accounts WHERE id=%'", Integer.class)).isGreaterThan(0);
            assertThat(blocked.isDone()).isFalse();
            var other = pool.submit(() -> rest.postForEntity("/api/orders", new ExchangeController.OrderRequest("independent-eth", "maker1", "ETH-PERP", "BUY", 3500, 10), String.class)).get(10, TimeUnit.SECONDS);
            assertThat(other.getStatusCode().is2xxSuccessful()).isTrue();
            connection.rollback();
            assertThat(blocked.get(10, TimeUnit.SECONDS).getStatusCode().is2xxSuccessful()).isTrue();
        } finally { pool.shutdownNow(); }
    }
    @Test void executionAtBetterBidRechecksSellerCollateral() {
        db.update("UPDATE accounts SET cash_cents=10000 WHERE id='alice'");
        rest.exchange("/api/markets/BTC-PERP/mark", org.springframework.http.HttpMethod.PUT, new org.springframework.http.HttpEntity<>(new ExchangeController.MarkRequest(10000)), String.class);
        order("high-bid", "maker1", "BUY", 10000, 10);
        order("low-ask", "alice", "SELL", 5000, 10);
        assertThat(db.queryForObject("SELECT status FROM orders WHERE client_id='low-ask'", String.class)).isEqualTo("CANCELLED");
        assertThat(db.queryForObject("SELECT count(*) FROM fills", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT reserved_cents FROM orders WHERE client_id='low-ask'", Long.class)).isZero();
    }
    @Test void oppositeCounterpartiesAcrossMarketsDoNotDeadlock() throws Exception {
        order("btc-resting", "maker1", "SELL", 5000, 2);
        assertThat(rest.postForEntity("/api/orders", new ExchangeController.OrderRequest("eth-resting", "alice", "ETH-PERP", "SELL", 5000, 2), String.class).getStatusCode().is2xxSuccessful()).isTrue();
        var barrier = new CyclicBarrier(3);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var btc = pool.submit(() -> { barrier.await(); return rest.postForEntity("/api/orders", new ExchangeController.OrderRequest("btc-incoming", "alice", "BTC-PERP", "BUY", 5000, 2), String.class); });
            var eth = pool.submit(() -> { barrier.await(); return rest.postForEntity("/api/orders", new ExchangeController.OrderRequest("eth-incoming", "maker1", "ETH-PERP", "BUY", 5000, 2), String.class); });
            barrier.await(10, TimeUnit.SECONDS);
            assertThat(btc.get(20, TimeUnit.SECONDS).getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(eth.get(20, TimeUnit.SECONDS).getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(db.queryForObject("SELECT count(*) FROM fills", Integer.class)).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }
    @Test void liveNotificationsWaitUntilEventCommits() throws Exception {
        // Use a fresh high sequence value: the notifier may have seen IDs from
        // earlier tests before the test fixture restarted the database sequence.
        db.queryForObject("SELECT setval(pg_get_serial_sequence('events','id'), ?, false)", Long.class, System.currentTimeMillis() * 1000L);
        var client = java.net.http.HttpClient.newHttpClient();
        var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/stream")).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        var pool = Executors.newSingleThreadExecutor();
        try (var input = response.body(); var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input)); var connection = db.getDataSource().getConnection()) {
            var changed = pool.submit(() -> { String line; while ((line = reader.readLine()) != null) if (line.startsWith("event:") && line.contains("change")) return true; return false; });
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("INSERT INTO events(description) VALUES ('pending verification')")) { insert.executeUpdate(); }
            Thread.sleep(1500);
            assertThat(changed.isDone()).isFalse();
            connection.commit();
            assertThat(changed.get(8, TimeUnit.SECONDS)).isTrue();
        } finally { pool.shutdownNow(); }
    }
}
