package dev.aura.punishments.service;

import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.integration.RemoteJailPort;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.PunishStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The network jail: one backend (the <i>jail backend</i>, {@code network.jail.backend}) has JailReloaded and the jail. This class makes the
 * rest of the network behave as if the jail were everywhere:
 *
 * <ul>
 *   <li><b>Hold.</b> A player with a live jail punishment is <i>held</i> on every backend: they cannot move, build, fight, chat or run commands
 *       until JailReloaded has taken them (so an unreachable jail backend, a slow transfer or a login on the wrong backend is never an escape).</li>
 *   <li><b>Transfer.</b> A held player who is on the wrong backend is sent to the jail backend through the proxy's plugin-message channel, and
 *       asked again every few seconds until they arrive.</li>
 *   <li><b>Return.</b> When the sentence ends (served, revoked, ended in JailReloaded) the jail backend sends the player back to where they came from
 *       (or the lobby, or a fixed server), with fallbacks, and the hold is lifted everywhere.</li>
 * </ul>
 *
 * Which players are held is read from the shared database (the punishment rows are the single source of truth): at login, on every change
 * another server reports, and every few seconds as a safety net. {@link #tick} runs on the main thread once a second.
 */
public final class NetworkJail {
    private record Hold(long punishmentId, Long expiresAt, boolean queued) {}

    private static final class Return {
        final List<String> targets;
        final long notBefore;
        int index;
        long sentAt;

        Return(List<String> targets, long notBefore) {
            this.targets = targets;
            this.notBefore = notBefore;
        }
    }

    private final Core core;
    private final Map<UUID, Hold> holds = new ConcurrentHashMap<>();
    private final Set<UUID> placed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastSent = new ConcurrentHashMap<>();
    private final Map<UUID, Return> returning = new ConcurrentHashMap<>();
    private volatile Map<String, Repo.Net.Server> registry = Map.of();
    private volatile String lastProblem;

    public NetworkJail(Core core) {
        this.core = core;
    }

    private Settings.NetJail cfg() {
        return core.settings.network.jail();
    }

    /** True when {@code network.jail.backend} is set (and the database is shared). */
    public boolean enabled() {
        return core.db.shared() && cfg().enabled();
    }

    public String backendName() {
        return cfg().backend();
    }

    /** True on the one server that has JailReloaded and the jail. */
    public boolean isBackend() {
        return enabled() && cfg().backend().equals(core.db.origin());
    }

    /** The jail backend is running: its registry heartbeat is fresh (or this IS the jail backend). */
    public boolean backendRunning() {
        if (isBackend()) return true;
        Repo.Net.Server s = registry.get(cfg().backend());
        return s != null && s.seenAt() > 0 && core.clock.now() - s.seenAt() < NetworkService.SERVER_STALE_MS;
    }

    /** The proxy's name for the jail backend (its registry entry says so; the server name when it does not). */
    public String backendProxyName() {
        Repo.Net.Server s = registry.get(cfg().backend());
        return s == null ? cfg().backend() : s.proxy();
    }

    // ------------------------------------------------------------------ who is held
    /** True while the player must be restrained on this server: a live jail they are not yet inside JailReloaded for. */
    public boolean mustHold(UUID uuid) {
        return cfg().hold() && holds.containsKey(uuid) && !placed.contains(uuid);
    }

    public boolean isJailed(UUID uuid) {
        return holds.containsKey(uuid);
    }

    public int heldCount() {
        return holds.size();
    }

    /** Re-read the registry and the live jails. Called at start, on every jail change another server reports and every few seconds. */
    public CompletableFuture<Void> refresh() {
        if (!enabled()) return CompletableFuture.completedFuture(null);
        return core.db.tx(c -> {
            Map<String, Repo.Net.Server> reg = new ConcurrentHashMap<>();
            for (Repo.Net.Server s : Repo.Net.servers(c)) reg.put(s.name(), s);
            return new Object[]{reg, Repo.Punishments.liveStandaloneJail(c)};
        }).thenAccept(o -> {
            @SuppressWarnings("unchecked") Map<String, Repo.Net.Server> reg = (Map<String, Repo.Net.Server>) o[0];
            @SuppressWarnings("unchecked") List<Punishment> live = (List<Punishment>) o[1];
            registry = Map.copyOf(reg);
            long now = core.clock.now();
            Set<UUID> keep = new LinkedHashSet<>();
            for (Punishment p : live) {
                if (p.status() != PunishStatus.ACTIVE) continue; // revoked: the hold is lifted at once, JailReloaded is told separately
                boolean queued = RemoteJailPort.placeholder(p.jailPrisonerId()) || p.jailPrisonerId() == null;
                // a jail that never reached JailReloaded runs out by the clock, wherever the player is
                if (queued && p.expiresAt() != null && now >= p.expiresAt()) continue;
                holds.put(p.targetUuid(), new Hold(p.id(), p.expiresAt(), queued));
                keep.add(p.targetUuid());
            }
            holds.keySet().removeIf(u -> !keep.contains(u));
            placed.removeIf(u -> !holds.containsKey(u));
            lastProblem = null;
        }).exceptionally(t -> {
            lastProblem = t.getMessage();
            return null;
        });
    }

    /** A login on this server: ask the database (not a cache) whether this player is serving a jail. Call off the main thread. */
    public void admit(UUID uuid) {
        if (!enabled() || !core.db.reachable()) return; // database away: what is already known keeps holding
        try {
            Punishment p = core.db.txSync(c -> Repo.Punishments.liveStandaloneJailFor(c, uuid), 1500L);
            if (p != null && p.status() == PunishStatus.ACTIVE) {
                holds.put(uuid, new Hold(p.id(), p.expiresAt(), RemoteJailPort.placeholder(p.jailPrisonerId()) || p.jailPrisonerId() == null));
            } else {
                holds.remove(uuid);
                placed.remove(uuid);
            }
        } catch (SQLException e) {
            lastProblem = e.getMessage(); // database down: what is already known keeps holding
        }
    }

    /** A player left this server: forget the transfer bookkeeping (the hold itself stays; it follows the player to the next backend). */
    public void left(UUID uuid) {
        placed.remove(uuid);
        lastSent.remove(uuid);
        returning.remove(uuid);
    }

    // ------------------------------------------------------------------ origin (where to send them back to)
    /** The proxy name of the backend the player is on right now, or null (offline, or already on the jail backend). */
    public String originFor(Connection c, UUID uuid, long now) throws SQLException {
        Repo.Net.Presence at = Repo.Net.presence(c, uuid, now, NetworkService.PRESENCE_STALE_MS);
        if (at == null || at.server().equals(cfg().backend())) return null;
        Repo.Net.Server s = Repo.Net.server(c, at.server());
        return s == null ? at.server() : s.proxy();
    }

    // ------------------------------------------------------------------ the transfers (main thread)
    /** Once a second: move held players to the jail backend, send released players home. */
    public void tick() {
        if (!enabled()) return;
        long now = core.clock.now();
        long retryMs = cfg().transferRetrySeconds() * 1000L;
        boolean backend = isBackend();
        for (UUID u : holds.keySet()) {
            if (!core.enforcement.isOnline(u)) continue;
            if (backend) {
                boolean inside = core.jail.available() && core.jail.find(u).map(s -> !s.offlinePending()).orElse(false);
                if (inside) {
                    placed.add(u);
                } else {
                    placed.remove(u);
                    core.enforcement.hint(u, "command.jail-hold-placing");
                }
                continue;
            }
            placed.remove(u);
            if (!backendRunning()) {
                core.enforcement.hint(u, "command.jail-hold-waiting");
                continue;
            }
            core.enforcement.hint(u, "command.jail-hold-sending");
            Long last = lastSent.get(u);
            if (last == null || now - last >= retryMs) {
                core.enforcement.transfer(u, backendProxyName());
                lastSent.put(u, now);
            }
        }
        for (var e : returning.entrySet()) {
            UUID u = e.getKey();
            Return r = e.getValue();
            if (!core.enforcement.isOnline(u) || holds.containsKey(u)) {
                returning.remove(u);
                continue;
            }
            if (now < r.notBefore) continue;
            if (r.sentAt != 0 && now - r.sentAt < retryMs) continue;
            if (r.sentAt != 0) r.index++; // still here after a whole interval: that server did not take them, try the next one
            if (r.index >= r.targets.size()) {
                returning.remove(u);
                core.log.warning("Could not send " + u + " back from the jail after trying " + r.targets + ". They are free; the proxy's default server applies at their next login.");
                continue;
            }
            if (r.sentAt == 0) core.enforcement.hint(u, "command.jail-returning");
            core.enforcement.transfer(u, r.targets.get(r.index));
            r.sentAt = now;
        }
    }

    /** The sentence ended (served, revoked or ended in JailReloaded): lift the hold and, on the jail backend, send the player home. */
    public void released(Punishment p) {
        if (!enabled()) return;
        holds.remove(p.targetUuid());
        placed.remove(p.targetUuid());
        if (!isBackend()) return;
        List<String> targets = new ArrayList<>();
        Settings.NetJail cfg = cfg();
        String primary = switch (cfg.returnMode()) {
            case "FIXED" -> cfg.fixedServer();
            case "LOBBY" -> cfg.lobbyServer();
            default -> p.jailOrigin() == null || p.jailOrigin().isBlank() ? cfg.lobbyServer() : p.jailOrigin();
        };
        String self = backendProxyName();
        for (String t : new String[]{primary, cfg.lobbyServer(), cfg.fixedServer()}) {
            if (t != null && !t.isBlank() && !t.equals(self) && !targets.contains(t)) targets.add(t);
        }
        if (!targets.isEmpty()) returning.put(p.targetUuid(), new Return(targets, core.clock.now() + 2000L));
    }

    // ------------------------------------------------------------------ diagnostics
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        if (!enabled()) return out;
        out.add("Network jail: backend '" + backendName() + "'" + (isBackend() ? " (this server)" : backendRunning() ? " (running)" : " (NOT RUNNING - jailed players are held where they are)")
                + ", " + holds.size() + " jailed, " + returning.size() + " being sent back, return to " + cfg().returnMode().toLowerCase()
                + (lastProblem == null ? "" : ", PROBLEM: " + lastProblem));
        return out;
    }
}
