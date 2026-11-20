package dev.aura.punishments.integration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Used when JailReloaded is absent, disabled or incompatible. Never pretends an operation worked. */
public final class UnavailableJailPort implements JailPort {
    private final String reason;

    public UnavailableJailPort(String reason) {
        this.reason = reason;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String status() {
        return "UNAVAILABLE - " + reason;
    }

    private Outcome fail() {
        return Outcome.of(State.ERROR, "JailReloaded is not available: " + reason);
    }

    @Override
    public Outcome jail(JailRequest r) {
        return fail();
    }

    @Override
    public Outcome release(UUID uuid, String id, String actor) {
        return fail();
    }

    @Override
    public Outcome setRemaining(UUID uuid, String id, long ms, String actor) {
        return fail();
    }

    @Override
    public Optional<PrisonerState> find(UUID uuid) {
        return Optional.empty();
    }

    @Override
    public List<PrisonerState> allPrisoners() {
        return List.of();
    }

    @Override
    public List<String> jailNames() {
        return List.of();
    }
}
