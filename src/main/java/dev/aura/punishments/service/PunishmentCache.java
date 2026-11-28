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
    private volatile ConcurrentHashMap<UUID, Punishment> bans = new ConcurrentHashMap<>();
    private volatile ConcurrentHashMap<UUID, Punishment> mutes = new ConcurrentHashMap<>();
    private volatile ConcurrentHashMap<String, Punishment> ipBans = new ConcurrentHashMap<>();

    public void load(List<Punishment> active) {
        bans.clear();
        mutes.clear();
        ipBans.clear();
        for (Punishment p : active) add(p);
    }

    /**
     * Replace everything in one swap, so a reader never sees a half-empty index while the new one is being built (a change made on
     * another server of the network triggers this; clearing in place would leave a moment in which nobody is banned).
     */
    public void replaceAll(List<Punishment> active) {
        ConcurrentHashMap<UUID, Punishment> b = new ConcurrentHashMap<>();
        ConcurrentHashMap<UUID, Punishment> m = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Punishment> ip = new ConcurrentHashMap<>();
        for (Punishment p : active) {
            if (p.status() != dev.aura.punishments.model.Simple.PunishStatus.ACTIVE) continue;
            switch (p.type()) {
                case BAN -> b.put(p.targetUuid(), p);
                case MUTE -> m.put(p.targetUuid(), p);
                case IPBAN -> {
                    b.put(p.targetUuid(), p);
                    if (p.ip() != null) ip.put(p.ip(), p);
                }
                default -> { }
            }
        }
        this.bans = b;
        this.mutes = m;
        this.ipBans = ip;
    }

    /** Bring one player's entries in line with what the database says is active for them right now. */
    public void syncPlayer(UUID uuid, List<Punishment> activeForPlayer) {
        bans.remove(uuid);
        mutes.remove(uuid);
        for (Punishment p : activeForPlayer) add(p);
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
