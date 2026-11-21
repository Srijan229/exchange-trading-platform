package com.example.exchange;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Notifications are hints: PostgreSQL remains the source of truth. */
@RestController
public class LiveUpdates implements ApplicationRunner {
    private final JdbcTemplate db;
    private final Set<SseEmitter> clients = ConcurrentHashMap.newKeySet();
    private volatile long cursor;
    public LiveUpdates(JdbcTemplate db) { this.db = db; }

    @Override public void run(ApplicationArguments args) {
        cursor = db.queryForObject("SELECT COALESCE(MAX(id),0) FROM events", Long.class);
    }

    @GetMapping(value = "/api/stream", produces = "text/event-stream")
    public SseEmitter subscribe() throws IOException {
        SseEmitter client = new SseEmitter(0L);
        clients.add(client);
        client.onCompletion(() -> clients.remove(client));
        client.onTimeout(() -> clients.remove(client));
        client.onError(error -> clients.remove(client));
        try { client.send(SseEmitter.event().name("ready").data("refresh")); }
        catch (IOException ex) { clients.remove(client); throw ex; }
        return client;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void publishCommittedEvents() {
        try {
            var events = db.queryForList("SELECT id FROM events WHERE id>? ORDER BY id LIMIT 500", cursor);
            for (var event : events) {
                long id = ((Number) event.get("id")).longValue();
                for (var client : clients) {
                    try { client.send(SseEmitter.event().id(Long.toString(id)).name("change").data("refresh")); }
                    catch (IOException | IllegalStateException ex) { clients.remove(client); client.complete(); }
                }
                cursor = id;
            }
        } catch (RuntimeException ex) {
            LoggerFactory.getLogger(LiveUpdates.class).warn("Live notification scan failed; will retry", ex);
        }
    }

    // Periodic refresh also covers out-of-order transaction commits and idle proxies.
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void refreshHeartbeat() {
        for (var client : clients) {
            try { client.send(SseEmitter.event().name("refresh").data("refresh")); }
            catch (IOException | IllegalStateException ex) { clients.remove(client); client.complete(); }
        }
    }
}
