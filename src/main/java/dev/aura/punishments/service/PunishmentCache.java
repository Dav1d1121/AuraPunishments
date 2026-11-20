package dev.aura.punishments.service;

import dev.aura.punishments.model.Records.Punishment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory index of ACTIVE bans, IP bans and mutes. Login and chat checks are memory lookups
 * (no database I/O on hot paths); the database remains the source of truth and the cache is
 * rebuilt from it at startup, so restarts lose nothing.
 */
public final class PunishmentCache {
    private final ConcurrentHashMap<UUID, Punishment> bans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Punishment> mutes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Punishment> ipBans = new ConcurrentHashMap<>();

    public void load(List<Punishment> active) {
        bans.clear();
        mutes.clear();
        ipBans.clear();
        for (Punishment p : active) add(p);
    }

    public void add(Punishment p) {
        if (p.status() != dev.aura.punishments.model.Simple.PunishStatus.ACTIVE) return;
        switch (p.type()) {
            case BAN -> bans.put(p.targetUuid(), p);
            case MUTE -> mutes.put(p.targetUuid(), p);
            case IPBAN -> {
                bans.put(p.targetUuid(), p);
                if (p.ip() != null) ipBans.put(p.ip(), p);
            }
            default -> { }
        }
    }

    public void remove(Punishment p) {
        switch (p.type()) {
            case BAN -> bans.computeIfPresent(p.targetUuid(), (k, v) -> v.id() == p.id() ? null : v);
            case MUTE -> mutes.computeIfPresent(p.targetUuid(), (k, v) -> v.id() == p.id() ? null : v);
            case IPBAN -> {
                bans.computeIfPresent(p.targetUuid(), (k, v) -> v.id() == p.id() ? null : v);
                if (p.ip() != null) ipBans.computeIfPresent(p.ip(), (k, v) -> v.id() == p.id() ? null : v);
            }
            default -> { }
        }
    }

    public Optional<Punishment> ban(UUID uuid, long now) {
        return live(bans.get(uuid), now);
    }

    public Optional<Punishment> mute(UUID uuid, long now) {
        return live(mutes.get(uuid), now);
    }

    public Optional<Punishment> ipBan(String ip, long now) {
        return ip == null ? Optional.empty() : live(ipBans.get(ip), now);
    }

    private static Optional<Punishment> live(Punishment p, long now) {
        return p != null && p.activeAt(now) ? Optional.of(p) : Optional.empty();
    }

    public Collection<Punishment> allMutes() {
        return List.copyOf(mutes.values());
    }

    public int size() {
        return bans.size() + mutes.size() + ipBans.size();
    }
}
