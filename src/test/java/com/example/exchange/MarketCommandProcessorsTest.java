package com.example.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MarketCommandProcessorsTest {
    @Test void commandsForOneMarketQueueInOrderAndAnotherMarketProceeds() throws Exception {
        var workers = new MarketCommandProcessors();
        var events = new LinkedBlockingQueue<String>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var first = workers.enqueue("BTC-PERP", () -> { events.add("btc-1-start"); entered.countDown(); release.await(); events.add("btc-1-end"); return 1; });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = workers.enqueue("BTC-PERP", () -> { events.add("btc-2"); return 2; });
            var third = workers.enqueue("BTC-PERP", () -> { events.add("btc-3"); return 3; });
            var eth = workers.enqueue("ETH-PERP", () -> { events.add("eth"); return 4; });
            assertThat(eth.get(5, TimeUnit.SECONDS)).isEqualTo(4);
            assertThat(second.isDone()).isFalse();
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(third.get(5, TimeUnit.SECONDS)).isEqualTo(3);
            assertThat(events).containsExactly("btc-1-start", "eth", "btc-1-end", "btc-2", "btc-3");
        } finally { release.countDown(); workers.shutdown(); }
    }
    @Test void fullQueueRejectsWithoutEnqueuingMoreWork() throws Exception {
        var workers = new MarketCommandProcessors();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var first = workers.enqueue("BTC-PERP", () -> { entered.countDown(); release.await(); return 0; });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 256; i++) workers.enqueue("BTC-PERP", () -> 1);
            assertThatThrownBy(() -> workers.enqueue("BTC-PERP", () -> 2))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("503");
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isZero();
        } finally { release.countDown(); workers.shutdown(); }
    }
}
