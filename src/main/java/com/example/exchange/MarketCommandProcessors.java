package com.example.exchange;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** One bounded FIFO executor per supported instrument, in this backend process. */
@Component
public class MarketCommandProcessors {
    private final Map<String, ThreadPoolExecutor> workers = Map.of(
        "BTC-PERP", worker("BTC-PERP"), "ETH-PERP", worker("ETH-PERP"));

    private static ThreadPoolExecutor worker(String name) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), task -> {
                Thread thread = new Thread(task, "market-" + name);
                thread.setDaemon(false);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    <T> Future<T> enqueue(String symbol, Callable<T> command) {
        var worker = workers.get(symbol);
        if (worker == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown instrument");
        try { return worker.submit(command); }
        catch (RejectedExecutionException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Market queue is full or shutting down; retry with the same client ID", ex);
        }
    }

    public <T> T execute(String symbol, Callable<T> command) {
        Future<T> result = enqueue(symbol, command);
        try { return result.get(); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            // Do not cancel an enqueued command: it may already have committed.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Request interrupted; retry with the same client ID", ex);
        } catch (CancellationException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Market processor stopped; retry with the same client ID", ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Market command failed", ex.getCause());
        }
    }

    @PreDestroy
    void shutdown() {
        workers.values().forEach(ThreadPoolExecutor::shutdown);
        for (ThreadPoolExecutor worker : workers.values()) {
            try {
                if (!worker.awaitTermination(30, TimeUnit.SECONDS)) cancelPending(worker);
            } catch (InterruptedException ex) {
                cancelPending(worker);
                Thread.currentThread().interrupt();
            }
        }
    }

    private void cancelPending(ThreadPoolExecutor worker) {
        for (Runnable task : worker.shutdownNow()) {
            if (task instanceof Future<?> future) future.cancel(false);
        }
    }
}
