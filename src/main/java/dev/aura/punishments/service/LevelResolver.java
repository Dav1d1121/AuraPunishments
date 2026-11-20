package dev.aura.punishments.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Resolves a (possibly offline) player's authority level and immunity. */
public interface LevelResolver {

    record TargetAuth(int level, boolean immune) {}

    /**
     * Resolve authority for a player, online or offline. Implementations must be safe to call from
     * any thread. When the permission backend cannot answer for an offline player the cached level
     * from the player's profile is used by the caller.
     */
    CompletableFuture<TargetAuth> resolve(UUID uuid);

    /** Whether a (possibly offline) player holds a permission node. Used to validate judge assignment. */
    CompletableFuture<Boolean> hasPermission(UUID uuid, String node);
}
