package com.cabal.claim.economy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

public final class EconomyDbWriter {
    private final ExecutorService executor;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final LongAdder submittedTasks = new LongAdder();
    private final LongAdder completedTasks = new LongAdder();
    private final LongAdder failedTasks = new LongAdder();
    private final LongAdder rejectedTasks = new LongAdder();
    private final AtomicInteger outstandingTasks = new AtomicInteger(0);

    public EconomyDbWriter() {
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "cabal-economy-db-writer");
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    public CompletableFuture<Void> runAsync(Runnable task) {
        if (shutdown.get()) {
            rejectedTasks.increment();
            throw new RejectedExecutionException("EconomyDbWriter is shut down");
        }
        submittedTasks.increment();
        outstandingTasks.incrementAndGet();
        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    task.run();
                    completedTasks.increment();
                } catch (RuntimeException | Error e) {
                    failedTasks.increment();
                    throw e;
                } finally {
                    outstandingTasks.decrementAndGet();
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            submittedTasks.add(-1);
            outstandingTasks.decrementAndGet();
            rejectedTasks.increment();
            throw e;
        }
    }

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        if (shutdown.get()) {
            rejectedTasks.increment();
            throw new RejectedExecutionException("EconomyDbWriter is shut down");
        }
        submittedTasks.increment();
        outstandingTasks.incrementAndGet();
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    T result = supplier.get();
                    completedTasks.increment();
                    return result;
                } catch (RuntimeException | Error e) {
                    failedTasks.increment();
                    throw e;
                } finally {
                    outstandingTasks.decrementAndGet();
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            submittedTasks.add(-1);
            outstandingTasks.decrementAndGet();
            rejectedTasks.increment();
            throw e;
        }
    }

    public boolean isWriterThread() {
        return Thread.currentThread().getName().equals("cabal-economy-db-writer");
    }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void awaitCompletion(long timeoutMs) {
        if (timeoutMs <= 0) return;
        CompletableFuture<Void> barrier;
        try {
            barrier = CompletableFuture.runAsync(() -> { }, executor);
        } catch (RejectedExecutionException ignored) {
            return;
        }
        try {
            barrier.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // Best-effort drain before shutdown; caller proceeds to shutdown either way.
        }
    }

    public record WriterMetrics(long submitted, long completed, long failed, long rejected, int outstanding) {}

    public WriterMetrics snapshotAndResetMetrics() {
        return new WriterMetrics(
            submittedTasks.sumThenReset(),
            completedTasks.sumThenReset(),
            failedTasks.sumThenReset(),
            rejectedTasks.sumThenReset(),
            Math.max(0, outstandingTasks.get())
        );
    }
}
