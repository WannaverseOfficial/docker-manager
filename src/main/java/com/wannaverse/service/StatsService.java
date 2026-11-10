package com.wannaverse.service;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Statistics;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/**
 * Samples stats for many containers at once. Each sample takes about a second (Docker waits for a
 * second reading to compute CPU), so they run in parallel on a bounded pool with an overall time
 * limit; a container that does not answer is skipped instead of stalling the whole page.
 */
@Service
public class StatsService {
    private final ExecutorService pool =
            Executors.newFixedThreadPool(
                    8,
                    r -> {
                        Thread t = new Thread(r, "stats");
                        t.setDaemon(true);
                        return t;
                    });

    public <T> List<T> collect(
            DockerAPI api,
            List<Container> containers,
            BiFunction<Container, Statistics, T> mapper) {
        List<Future<T>> futures = new ArrayList<>();
        for (Container c : containers) {
            futures.add(
                    pool.submit(
                            () -> {
                                Statistics stats = api.getContainerStats(c.getId());
                                return stats == null ? null : mapper.apply(c, stats);
                            }));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            try {
                long left = Math.max(0, deadline - System.nanoTime());
                results.add(f.get(left, TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                f.cancel(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException ignored) {
                // Container stopped between listing and sampling.
            }
        }
        return results.stream().filter(Objects::nonNull).toList();
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
