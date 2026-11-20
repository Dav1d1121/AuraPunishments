package dev.aura.punishments.integration;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.Records.Profile;
import dev.aura.punishments.service.Actors;
import dev.aura.punishments.service.Core;
import dev.aura.punishments.service.LevelResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Staff authority for online and offline players. Online: live permission check. Offline: the permission
 * backend (LuckPerms) when available, otherwise the level cached in the player's profile at last logout.
 */
public final class BukkitLevelResolver implements LevelResolver {
    /** Offline permission lookups (LuckPerms). Optional. */
    public interface OfflineBackend {
        CompletableFuture<TargetAuth> auth(UUID uuid, String immunityNode);

        CompletableFuture<Boolean> has(UUID uuid, String node);
    }

    private final Core core;
    private volatile OfflineBackend backend;

    public BukkitLevelResolver(Core core) {
        this.core = core;
    }

    public void backend(OfflineBackend b) {
        this.backend = b;
    }

    @Override
    public CompletableFuture<TargetAuth> resolve(UUID uuid) {
        String immunity = core.settings.protections.immunityNode();
        return core.main.call(() -> {
            Player p = Bukkit.getPlayer(uuid);
            return p == null ? null : new TargetAuth(Actors.levelOf(p), immunity != null && !immunity.isBlank() && p.hasPermission(immunity));
        }).thenCompose(online -> {
            if (online != null) return CompletableFuture.completedFuture(online);
            OfflineBackend b = backend;
            CompletableFuture<TargetAuth> fromCache = core.db.tx(c -> {
                Profile pr = Repo.Profiles.get(c, uuid);
                return new TargetAuth(pr == null ? 0 : pr.level(), false);
            });
            if (b == null) return fromCache;
            return b.auth(uuid, immunity).handle((auth, ex) -> auth).thenCompose(a -> a != null ? CompletableFuture.completedFuture(a) : fromCache);
        });
    }

    @Override
    public CompletableFuture<Boolean> hasPermission(UUID uuid, String node) {
        return core.main.call(() -> {
            Player p = Bukkit.getPlayer(uuid);
            return p == null ? null : p.hasPermission(node);
        }).thenCompose(online -> {
            if (online != null) return CompletableFuture.completedFuture(online);
            OfflineBackend b = backend;
            return b == null ? CompletableFuture.completedFuture(false) : b.has(uuid, node).handle((v, ex) -> ex == null && Boolean.TRUE.equals(v));
        });
    }
}
