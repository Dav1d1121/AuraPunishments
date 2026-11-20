package dev.aura.punishments.util;

/** Injectable time source so expiry, deadlines and restarts can be tested deterministically. */
@FunctionalInterface
public interface Clock {
    long now();

    Clock SYSTEM = System::currentTimeMillis;
}
