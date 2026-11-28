package dev.aura.punishments.service;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.db.Schema;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The network side of a shared database: what makes four backends behave as one punishment system.
 *
 * <ul>
 *   <li><b>Registry.</b> Every server writes a heartbeat under its own {@code network.server-name}; a second server using the same name is
 *       refused (two servers answering to one name would make presence, leases and statistics meaningless).</li>
 *   <li><b>Change events.</b> The database writes an event whenever a punishment or custody record changes (a trigger, so nothing can forget
 *       to). Each server polls them every {@code network.sync-interval-ms}, reloads its in-memory index and applies what concerns the
 *       players connected to it: a ban disconnects, a kick disconnects, a mute shows its notice, a revocation lifts it.</li>
 *   <li><b>Presence.</b> Where a player is connected, so a kick or a staff screen can tell "online on another backend" from "offline".</li>
 *   <li><b>Leases.</b> Background jobs that must run once per network (custody deadline sweeps, review reminders, retention) run on the
 *       server that holds the job's lease.</li>
 * </ul>
 *
 * <p>Admission decisions (login) never wait for a poll: they ask the database directly ({@link PunishmentService#banForLogin}). The polling
 * only keeps the in-memory index, used on hot paths such as chat, fresh within about a second.
 */
public final class NetworkService {
    /** A presence row nobody refreshed for this long belongs to a crashed server and is ignored. */
    public static final long PRESENCE_STALE_MS = 90_000L;
    /** A server whose heartbeat is older than this is not running (the registry says so; it does not delete anything). */
    public static final long SERVER_STALE_MS = 45_000L;
    private static final int BATCH = 500;

    private final Core core;
    private final PunishmentService punishments;
    private final String name;
    /** What the proxy (Velocity/BungeeCord) calls this backend: the name its Connect message must use. */
    private final String proxyName;
    private final String instanceId = UUID.randomUUID().toString();
    private final long startedAt;
    private volatile long cursor = -1;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicBoolean beating = new AtomicBoolean();
    private volatile long lastPollOk;
    private volatile String lastProblem;
    private volatile long eventsApplied;
    /** Called (off the main thread) when another server changed a jail punishment: the network jail re-reads who is held and, on the jail backend, carries out the queue. */
    private volatile Runnable jailChange = () -> { };

    public void onJailChange(Runnable r) {
        this.jailChange = r == null ? () -> { } : r;
    }

    public NetworkService(Core core, PunishmentService punishments) {
        this.core = core;
        this.punishments = punishments;
        this.name = core.db.origin();
        this.proxyName = core.settings.network.proxyName().isBlank() ? core.db.origin() : core.settings.network.proxyName();
        this.startedAt = core.clock.now();
    }

    public String serverName() {
        return name;
    }

    // ------------------------------------------------------------------ start / stop
    /**
     * Join the network: refuse a duplicate server name, check the clocks, start the change cursor at "now" (the in-memory index was just
     * loaded, so nothing older matters). Failure means this server must not enforce on its own: the plugin disables itself with the message.
     */
    public CompletableFuture<Result<Void>> start(String pluginVersion, boolean jailBackend, long maxSkewMs, boolean allowDuplicateName) {
        return core.db.tx(c -> {
            long now = core.clock.now();
            if (maxSkewMs > 0) {
                long dbNow = core.db.databaseTimeMillisIn(c);
                long skew = dbNow - now;
                if (Math.abs(skew) > maxSkewMs) {
                    return Result.<Void>fail(Code.UNAVAILABLE, "This server's clock differs from the database server's by " + skew / 1000 + " s (limit "
                            + maxSkewMs / 1000 + " s). Punishment expiry is computed from timestamps, so every server of the network needs synchronised time (NTP).");
                }
            }
            Repo.Net.Server existing = Repo.Net.server(c, name);
            if (existing != null && !allowDuplicateName && now - existing.seenAt() < SERVER_STALE_MS && !existing.instanceId().equals(instanceId)) {
                return Result.<Void>fail(Code.DUPLICATE, "Another server is already running as '" + name + "' (heartbeat " + (now - existing.seenAt()) / 1000
                        + " s ago, v" + existing.version() + "). Every server of the network needs its own network.server-name. If that server just crashed, wait "
                        + SERVER_STALE_MS / 1000 + " s and start again.");
            }
            Repo.Net.register(c, name, instanceId, pluginVersion, Schema.latest(), now, jailBackend, proxyName);
            Repo.Net.purgePresence(c, now - PRESENCE_STALE_MS);
            cursor = Repo.Net.lastEventId(c);
            return Result.<Void>ok();
        }).handle((r, ex) -> {
            if (ex != null) return Result.<Void>fail(Code.UNAVAILABLE, "Cannot reach the shared database: " + ex.getMessage());
            return r;
        });
    }

    /** Leave cleanly: the heartbeat is zeroed so a restart is not mistaken for a duplicate, and this server's presence rows go. */
    public void stop() {
        try {
            core.db.txSync(c -> {
                Repo.Net.unregister(c, name, instanceId);
                dev.aura.punishments.db.Sql.update(c, "DELETE FROM network_presence WHERE server=?", name);
                return null;
            });
        } catch (SQLException e) {
            core.log.warning("Could not leave the network registry cleanly: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ heartbeat, presence, leases
    /** Every few seconds: prove this server is alive, refresh its players' presence and drop rows of servers that died. */
    public CompletableFuture<Result<Void>> heartbeat(Collection<UUID> localOnline) {
        if (!beating.compareAndSet(false, true)) return CompletableFuture.completedFuture(Result.fail(Code.UNAVAILABLE, "the previous heartbeat has not finished")); // never queue them up behind a dead database
        return core.db.tx(c -> {
            long now = core.clock.now();
            if (!Repo.Net.heartbeat(c, name, instanceId, now)) {
                return Result.<Void>fail(Code.DUPLICATE, "Another server has taken over the name '" + name + "' in the registry.");
            }
            Repo.Net.touch(c, name, localOnline, now);
            Repo.Net.purgePresence(c, now - PRESENCE_STALE_MS);
            return Result.<Void>ok();
        }).handle((r, ex) -> {
            beating.set(false);
            if (ex != null) {
                lastProblem = ex.getMessage();
                return Result.<Void>fail(Code.UNAVAILABLE, ex.getMessage());
            }
            return r;
        });
    }

    public CompletableFuture<Void> playerJoined(UUID uuid, String playerName) {
        return core.db.tx(c -> {
            Repo.Net.join(c, uuid, playerName, name, core.clock.now());
            return null;
        }).handle((v, ex) -> null);
    }

    public CompletableFuture<Void> playerLeft(UUID uuid) {
        return core.db.tx(c -> {
            Repo.Net.leave(c, uuid, name);
            return null;
        }).handle((v, ex) -> null);
    }

    /** The server the player is connected to, if any (this one included). */
    public CompletableFuture<Optional<String>> whereIs(UUID uuid) {
        return core.db.tx(c -> {
            Repo.Net.Presence p = Repo.Net.presence(c, uuid, core.clock.now(), PRESENCE_STALE_MS);
            return Optional.ofNullable(p == null ? null : p.server());
        }).handle((o, ex) -> ex == null ? o : Optional.<String>empty());
    }

    /** True when the player is connected to a server of the network other than this one. */
    public CompletableFuture<Boolean> isOnlineElsewhere(UUID uuid) {
        return whereIs(uuid).thenApply(s -> s.isPresent() && !s.get().equals(name));
    }

    /** True when this server currently holds the lease for a network-wide background job (and refreshes it). */
    public CompletableFuture<Boolean> lead(String job, long ttlMs) {
        return core.db.tx(c -> Repo.Net.lease(c, job, name, core.clock.now(), ttlMs)).handle((b, ex) -> ex == null && Boolean.TRUE.equals(b));
    }

    // ------------------------------------------------------------------ the change feed
    /**
     * Read what changed on the network since the last poll and apply it here. Never throws; a database problem is remembered and the
     * next poll tries again from the same place (nothing is skipped).
     */
    public CompletableFuture<Integer> poll() {
        if (cursor < 0 || !polling.compareAndSet(false, true)) return CompletableFuture.completedFuture(0);
        return core.db.tx(c -> Repo.Net.eventsAfter(c, cursor, BATCH)).thenCompose(events -> {
            lastPollOk = core.clock.now();
            lastProblem = null;
            if (events.isEmpty()) return CompletableFuture.completedFuture(0);
            long last = events.get(events.size() - 1).id();
            return punishments.reloadCache().thenCompose(r -> applyLocally(events)).thenApply(n -> {
                cursor = last; // only after the events were applied: a failure above means the same events are read again
                eventsApplied += events.size();
                return events.size();
            });
        }).handle((n, ex) -> {
            polling.set(false);
            if (ex != null) {
                lastProblem = ex.getMessage();
                return 0;
            }
            return n;
        });
    }

    /** The effects on players connected to THIS server of changes other servers made. */
    private CompletableFuture<Void> applyLocally(List<Repo.Net.Event> events) {
        Set<Long> punishmentIds = new LinkedHashSet<>();
        boolean jails = false;
        for (Repo.Net.Event e : events) {
            if (!e.origin().equals(name) && "PUNISHMENT".equals(e.kind()) && e.detail() != null && e.detail().startsWith("JAIL:")) jails = true;
        }
        if (jails) {
            try {
                jailChange.run();
            } catch (RuntimeException ex) {
                core.log.warning("Network jail update failed: " + ex.getMessage());
            }
        }
        for (Repo.Net.Event e : events) {
            if (e.origin().equals(name) || e.ref() == null || !"PUNISHMENT".equals(e.kind())) continue; // this server did it itself
            punishmentIds.add(e.ref());
        }
        if (punishmentIds.isEmpty()) return CompletableFuture.completedFuture(null);
        return core.db.tx(c -> {
            List<Punishment> rows = new ArrayList<>();
            for (long id : punishmentIds) {
                Punishment p = Repo.Punishments.get(c, id);
                if (p != null) rows.add(p);
            }
            return rows;
        }).thenCompose(rows -> core.main.run(() -> {
            for (Punishment p : rows) applyOne(p);
        })).exceptionally(t -> {
            core.log.warning("Could not apply a change made on another server: " + t.getMessage());
            return null;
        });
    }

    private void applyOne(Punishment p) {
        if (!core.enforcement.isOnline(p.targetUuid())) {
            if (p.type() == PunishType.WARN) core.notifications.deliverPending(p.targetUuid());
            return;
        }
        switch (p.type()) {
            case BAN, IPBAN -> {
                if (p.status() == PunishStatus.ACTIVE && p.activeAt(core.clock.now())) core.enforcement.disconnect(p.targetUuid(), p);
            }
            case KICK -> core.enforcement.disconnect(p.targetUuid(), p);
            case MUTE -> {
                if (p.status() == PunishStatus.ACTIVE) core.enforcement.noticeMute(p.targetUuid(), p);
                else core.enforcement.noticeUnmute(p.targetUuid());
            }
            case WARN -> core.notifications.deliverPending(p.targetUuid());
            default -> { }
        }
    }

    /** Housekeeping for the leader: events older than the retention are deleted. */
    public CompletableFuture<Integer> purgeOldEvents(long retentionMs) {
        return core.db.tx(c -> Repo.Net.purgeEvents(c, core.clock.now() - retentionMs)).handle((n, ex) -> ex == null ? n : 0);
    }

    // ------------------------------------------------------------------ diagnostics
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add("Network: server '" + name + "' (instance " + instanceId.substring(0, 8) + "), change feed at event " + cursor + ", " + eventsApplied + " applied"
                + (lastProblem == null ? "" : ", PROBLEM: " + lastProblem));
        if (core.netJail != null) out.addAll(core.netJail.describe());
        try {
            for (Repo.Net.Server s : core.db.txSync(Repo.Net::servers)) {
                long age = core.clock.now() - s.seenAt();
                out.add("  - " + s.name() + " v" + s.version() + " (schema " + s.schema() + ")" + (s.seenAt() == 0 ? " stopped" : age < SERVER_STALE_MS ? " running" : " silent " + age / 1000 + "s")
                        + (s.jailBackend() ? " [jail]" : "") + (s.name().equals(name) ? " <- this server" : ""));
            }
            for (String job : new String[]{"housekeeping", "discord-sender"}) {
                String holder = core.db.txSync(c -> Repo.Net.leaseHolder(c, job, core.clock.now()));
                out.add("  " + (job.equals("discord-sender") ? "Discord messages are sent by: " : "Network-wide housekeeping runs on: ") + (holder == null ? "(nobody yet)" : holder));
            }
        } catch (SQLException e) {
            out.add("  (registry unavailable: " + e.getMessage() + ")");
        }
        return out;
    }

    public long cursor() {
        return cursor;
    }

    public String problem() {
        return lastProblem;
    }

    public long lastPollOk() {
        return lastPollOk;
    }
}
