package dev.aura.punishments.service;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Hop onto the server main thread (Bukkit state, JailReloaded, inventories). */
public interface MainThread {
    <T> CompletableFuture<T> call(Supplier<T> work);

    default CompletableFuture<Void> run(Runnable work) {
        return call(() -> {
            work.run();
            return null;
        });
    }

    /** Completes on the main thread after the given number of ticks (inline implementations complete immediately). */
    default CompletableFuture<Void> later(long ticks) {
        return run(() -> { });
    }

    /** Runs inline; for unit tests where there is no scheduler. */
    MainThread INLINE = new MainThread() {
        @Override
        public <T> CompletableFuture<T> call(Supplier<T> work) {
            try {
                return CompletableFuture.completedFuture(work.get());
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        }
    };
}
