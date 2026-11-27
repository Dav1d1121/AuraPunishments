package dev.aura.punishments.service;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.integration.JailPort.JailRequest;
import dev.aura.punishments.integration.JailPort.Outcome;
import dev.aura.punishments.integration.JailPort.PrisonerState;
import dev.aura.punishments.integration.JailPort.State;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.OpState;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import dev.aura.punishments.util.Text;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jail as an ordinary punishment (no case, no pretrial custody). The punishment row IS the record: it carries the
 * JailReloaded prisoner id and a {@code jail_state}. JailReloaded still does the imprisonment, and every step follows the
 * same recoverable pattern as pretrial custody:
 * <ol>
 *   <li>record the punishment (state PLACING) and a pending operation in one transaction;</li>
 *   <li>ask JailReloaded on the main thread and verify the result;</li>
 *   <li>record the verified result (HELD, or the punishment is closed as "jail not started").</li>
 * </ol>
 * A crash between steps is resolved by {@link #reconcile()} against JailReloaded's real prisoner list. The plugin never
 * reports a jail it could not verify, and never marks someone released while JailReloaded still holds them.
 *
 * <p>States: PLACING, HELD, PENDING_PLACEMENT (offline player, placed at next login), RELEASING (release accepted, the
 * player is freed at next login), RELEASED, FAILED (never started), ENDED_EXTERNALLY (freed in JailReloaded by something
 * other than AuraPunishments; staff are alerted).
 */
public final class JailService extends Svc {
    public static final String PLACING = "PLACING";
    public static final String HELD = "HELD";
    public static final String PENDING_PLACEMENT = "PENDING_PLACEMENT";
    public static final String RELEASING = "RELEASING";
    public static final String RELEASED = "RELEASED";
    public static final String FAILED = "FAILED";
    public static final String ENDED_EXTERNALLY = "ENDED_EXTERNALLY";

    private static final long IN_FLIGHT_GRACE_MS = 60_000;
    /** A release this close to (or after) the nominal end counts as the sentence being served. */
    private static final long SERVED_TOLERANCE_MS = 60_000;

    private final PunishmentService punishments;
    private final Set<UUID> selfOps = ConcurrentHashMap.newKeySet();
    private volatile Map<String, Punishment> heldByName = Map.of();

    public JailService(Core core, PunishmentService punishments) {
        super(core);
        this.punishments = punishments;
    }

    public static String tag(long punishmentId) {
        return "AP-J" + String.format("%04d", punishmentId);
    }

    public static boolean live(String jailState) {
        return PLACING.equals(jailState) || HELD.equals(jailState) || PENDING_PLACEMENT.equals(jailState) || RELEASING.equals(jailState);
    }

    // ------------------------------------------------------------------ index (used by the command guard)
    /** The live standalone jail punishment for a player name, from an in-memory index refreshed after every change. */
    public Optional<Punishment> heldByName(String name) {
        return Optional.ofNullable(name == null ? null : heldByName.get(name.toLowerCase(Locale.ROOT)));
    }

    public CompletableFuture<Void> refreshIndex() {
        return core.db.tx(c -> Repo.Punishments.liveStandaloneJail(c)).thenAccept(list -> {
            Map<String, Punishment> m = new HashMap<>();
            for (Punishment p : list) m.put(p.targetName().toLowerCase(Locale.ROOT), p);
            heldByName = Map.copyOf(m);
        }).exceptionally(t -> null);
    }

    public CompletableFuture<Result<List<Punishment>>> live(Actor actor) {
        Result<Void> perm = core.auth.requireAny(actor, "view jailed players", Perms.HISTORY_VIEW, Perms.CUSTODY_VIEW, Perms.ADMIN);
        if (!perm.isOk()) return done(perm.cast());
        return db(c -> Result.ok(Repo.Punishments.liveStandaloneJail(c)));
    }

    // ------------------------------------------------------------------ placing (step 1 happens in PunishmentService.issueQuick)
    /**
     * Steps 2 and 3 for a jail punishment that was just recorded in state PLACING.
     *
     * @return the punishment once JailReloaded confirmed it, or a failure (and the punishment is closed as "not started")
     */
    CompletableFuture<Result<Punishment>> place(Actor actor, Punishment p) {
        var cfg = core.settings.jail;
        String jailName = cfg.jailName().isBlank() ? core.settings.custody.jailName() : cfg.jailName();
        String cellName = cfg.cellName().isBlank() ? core.settings.custody.cellName() : cfg.cellName();
        long duration = p.expiresAt() == null ? -1L : Math.max(1000L, p.expiresAt() - p.issuedAt());
        JailRequest req = new JailRequest(p.targetUuid(), p.targetName(), 0L, tag(p.id()), Text.truncate(p.reason(), 100), actor.name(),
                duration, jailName, cellName, cfg.muteWhileJailed());
        core.emit(DomainEvent.of(DomainEvent.Type.CUSTODY_ATTEMPT).key("jail-attempt:" + p.id()).player(p.targetName()).actor(actor.name())
                .summary("Jail punishment #" + p.id() + " requested; awaiting JailReloaded confirmation (not yet confirmed)").build());
        return core.main.call(() -> {
            selfOps.add(p.targetUuid());
            try {
                return core.jail.jail(req);
            } catch (Throwable t) {
                return Outcome.of(State.ERROR, "JailReloaded threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                selfOps.remove(p.targetUuid());
            }
        }).thenCompose(out -> finishPlace(actor, p, out));
    }

    private CompletableFuture<Result<Punishment>> finishPlace(Actor actor, Punishment p, Outcome out) {
        boolean ok = out.ok() && out.jailPrisonerId() != null && out.state() != State.ALREADY_JAILED;
        return db(c -> {
            long now = core.clock.now();
            Long op = openOp(c, p.id(), "JAIL_PLACE");
            if (ok) {
                Repo.Punishments.setJail(c, p.id(), out.state() == State.JAILED_PENDING_PLACEMENT ? PENDING_PLACEMENT : HELD,
                        out.jailName(), out.jailPrisonerId(), null);
                if (op != null) Repo.Ops.setState(c, op, OpState.DONE, null, now);
                audit(c, actor, "jail.start", p.targetUuid(), null, "punishment=" + p.id() + " jail=" + out.jailName() + " tag=" + tag(p.id()), false);
                Punishment fresh = Repo.Punishments.get(c, p.id());
                ReviewService.createIfRequired(core, c, actor, fresh, null);
                return Result.ok(fresh);
            }
            String msg = out.message() == null ? "JailReloaded did not confirm the jail." : out.message();
            // Never leave a record that says someone is jailed when they are not.
            Repo.Punishments.end(c, p.id(), PunishStatus.REVOKED, "System", "Jail was not started: " + Text.truncate(msg, 200), now);
            Repo.Punishments.setJail(c, p.id(), FAILED, null, null, Text.truncate(msg, 250));
            if (op != null) Repo.Ops.setState(c, op, OpState.FAILED, msg, now);
            audit(c, actor, "jail.failed", p.targetUuid(), null, "punishment=" + p.id() + " " + Text.truncate(msg, 150), false);
            return Result.ok(Repo.Punishments.get(c, p.id())); // commit the failure record, then report it below
        }).thenApply(res -> {
            if (!res.isOk()) {
                return Result.<Punishment>fail(Code.DB_ERROR, "JailReloaded " + (ok ? "confirmed the jail, but it could not be recorded. It will be reconciled automatically."
                        : "did not confirm the jail.") + " " + res.message());
            }
            Punishment after = res.value();
            if (FAILED.equals(after.jailState())) {
                punishments.cache().remove(after);
                core.emit(DomainEvent.of(DomainEvent.Type.CUSTODY_FAILED).key("jail-failed:" + after.id()).player(after.targetName()).actor(actor.name())
                        .summary("JailReloaded did not confirm jail punishment #" + after.id() + ": " + Text.truncate(String.valueOf(after.jailError()), 200)).build());
                return Result.<Punishment>fail(Code.EXTERNAL, "The jail was not started: " + after.jailError());
            }
            refreshIndex();
            return Result.ok(after);
        });
    }

    private Long openOp(java.sql.Connection c, long punishmentId, String kind) throws java.sql.SQLException {
        return Repo.Ops.unfinished(c).stream().filter(o -> o.punishmentId() != null && o.punishmentId() == punishmentId && o.kind().equals(kind))
                .map(o -> o.id()).findFirst().orElse(null);
    }

    /** Called inside the issuing transaction: record the pending operation for the new jail punishment. */
    void recordPlacing(java.sql.Connection c, Punishment p, long now) throws java.sql.SQLException {
        Repo.Punishments.setJail(c, p.id(), PLACING, null, null, null);
        Repo.Ops.addForPunishment(c, "JAIL_PLACE", p.id(), p.targetUuid(), null, now);
    }

    // ------------------------------------------------------------------ releasing
    /**
     * Early release: revokes the jail punishment (with a recorded reason) and frees the prisoner. Authorised for holders of
     * punish.revoke.any, custody/judicial release, or the issuer with punish.revoke.
     */
    public CompletableFuture<Result<Punishment>> unjail(Actor actor, UUID target, String reason) {
        String why = Text.clean(reason);
        if (why.length() < core.settings.cases.minReason()) {
            return done(Result.fail(Code.INVALID, "A reason of at least " + core.settings.cases.minReason() + " characters is required."));
        }
        return core.db.tx(c -> Repo.Punishments.liveStandaloneJailFor(c, target)).handle((p, ex) -> ex == null ? Optional.ofNullable(p) : Optional.<Punishment>empty())
                .thenCompose(op -> {
                    if (op.isEmpty()) {
                        return done(Result.<Punishment>fail(Code.NOT_FOUND, "That player is not serving a jail punishment from AuraPunishments. "
                                + "(Pretrial detention is released through its case.)"));
                    }
                    Punishment p = op.get();
                    boolean own = actor.dbUuid() != null && actor.dbUuid().equals(p.issuerUuid());
                    boolean may = core.auth.has(actor, Perms.REVOKE_ANY) || core.auth.has(actor, Perms.CUSTODY_RELEASE)
                            || core.auth.has(actor, Perms.JUDICIAL_RELEASE) || (own && core.auth.has(actor, Perms.REVOKE));
                    if (!may) return done(Result.<Punishment>fail(Code.NO_PERMISSION, "You do not have permission to release that prisoner."));
                    if (p.status() != PunishStatus.ACTIVE) {
                        // already revoked/ended but still physically held: finish the release rather than refuse
                        return releasePhysical(actor, p, why).thenApply(x -> x.isOk() ? Result.ok(p) : x.<Punishment>cast());
                    }
                    return punishments.revoke(actor, p.id(), why, true);
                });
    }

    /** Free the prisoner for a punishment that was just revoked (called by {@link PunishmentService#revoke}). */
    CompletableFuture<Result<Punishment>> releaseForRevoked(Actor actor, Punishment p, String reason) {
        return releasePhysical(actor, p, reason);
    }

    private CompletableFuture<Result<Punishment>> releasePhysical(Actor actor, Punishment p, String reason) {
        if (!live(p.jailState())) return done(Result.ok(p));
        // Step 1: record intent.
        return db(c -> {
            long now = core.clock.now();
            Punishment fresh = Repo.Punishments.get(c, p.id());
            if (fresh == null || !live(fresh.jailState())) return Result.<Punishment>ok(fresh);
            Repo.Punishments.setJail(c, p.id(), RELEASING, null, null, null);
            if (openOp(c, p.id(), "JAIL_RELEASE") == null) Repo.Ops.addForPunishment(c, "JAIL_RELEASE", p.id(), p.targetUuid(), null, now);
            audit(c, actor, "jail.release.request", p.targetUuid(), null, "punishment=" + p.id() + " " + Text.truncate(reason, 120), false);
            return Result.ok(fresh);
        }).thenCompose(r1 -> {
            if (!r1.isOk()) return done(r1);
            if (r1.value() == null || !live(r1.value().jailState())) return done(r1);
            if (!core.jail.available()) {
                return done(Result.<Punishment>fail(Code.UNAVAILABLE, "JailReloaded is not available (" + core.jail.status()
                        + "). The punishment is revoked and the prisoner will be released as soon as JailReloaded is back (retried automatically)."));
            }
            String prisonerId = r1.value().jailPrisonerId();
            return core.main.call(() -> {
                selfOps.add(p.targetUuid());
                try {
                    return core.jail.release(p.targetUuid(), prisonerId, actor.name());
                } catch (Throwable t) {
                    return Outcome.of(State.ERROR, "JailReloaded threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
                } finally {
                    selfOps.remove(p.targetUuid());
                }
            }).thenCompose(out -> finishRelease(actor, p, reason, out));
        });
    }

    private CompletableFuture<Result<Punishment>> finishRelease(Actor actor, Punishment p, String reason, Outcome out) {
        State st = out.state();
        boolean released = st == State.RELEASED || st == State.NOT_JAILED || st == State.NOT_OURS;
        boolean pending = st == State.RELEASE_PENDING;
        if (!released && !pending) {
            String err = out.message() == null ? "JailReloaded refused the release." : out.message();
            return db(c -> {
                Repo.Punishments.setJail(c, p.id(), RELEASING, null, null, Text.truncate(err, 250));
                return Result.ok(Repo.Punishments.get(c, p.id()));
            }).thenApply(x -> Result.<Punishment>fail(Code.EXTERNAL, "The prisoner was NOT released yet: " + err + " It will be retried automatically."));
        }
        return db(c -> {
            long now = core.clock.now();
            Long op = openOp(c, p.id(), "JAIL_RELEASE");
            if (released) {
                Repo.Punishments.setJail(c, p.id(), st == State.NOT_OURS ? ENDED_EXTERNALLY : RELEASED, null, null, null);
                if (op != null) Repo.Ops.setState(c, op, OpState.DONE, null, now);
            } else if (op != null) {
                Repo.Ops.setState(c, op, OpState.EXTERNAL_DONE, null, now);
            }
            audit(c, actor, released ? "jail.release" : "jail.release.pending", p.targetUuid(), null,
                    "punishment=" + p.id() + " " + Text.truncate(reason, 120), false);
            return Result.ok(Repo.Punishments.get(c, p.id()));
        }).thenApply(res -> {
            if (res.isOk()) {
                refreshIndex();
                core.emit(DomainEvent.of(DomainEvent.Type.CUSTODY_RELEASED).key("jail-released:" + p.id() + ":" + pending).player(p.targetName()).actor(actor.name())
                        .summary(pending ? "Jail release accepted (completes at next login)" : "Released from jail").field("Reason", Text.truncate(reason, 200)).build());
            }
            return res;
        });
    }

    // ------------------------------------------------------------------ changing the time of a held prisoner
    /**
     * A jail punishment was shortened or lengthened (appeal or owner review): move JailReloaded's remaining time by the same difference, so
     * the prisoner is released when the modified sentence says. JailReloaded's own clock is kept (it only counts online time), which is why this
     * is a difference applied to its remaining time rather than an absolute time.
     */
    CompletableFuture<Result<Void>> applyTimeChange(Actor actor, Punishment before, Punishment after) {
        if (after.expiresAt() == null) return done(Result.fail(Code.INVALID, "A jail punishment cannot be made permanent by a modification."));
        return core.main.call(() -> {
            var st = core.jail.find(after.targetUuid());
            if (st.isEmpty()) return Outcome.of(State.NOT_JAILED, "not held");
            var s = st.get();
            long now = core.clock.now();
            long remaining = s.forever() || before.expiresAt() == null ? after.expiresAt() - now
                    : s.remainingMs() + (after.expiresAt() - before.expiresAt());
            return core.jail.setRemaining(after.targetUuid(), after.jailPrisonerId(), Math.max(1000L, remaining), actor.name());
        }).thenCompose(out -> {
            boolean ok = out.state() == State.UPDATED || out.state() == State.NOT_JAILED;
            return db(c -> {
                audit(c, actor, ok ? "jail.time" : "jail.time.failed", after.targetUuid(), null, "punishment=" + after.id() + " " + Text.truncate(String.valueOf(out.message()), 150), !ok);
                if (!ok) Repo.Punishments.setJail(c, after.id(), after.jailState(), null, null, Text.truncate("Time change not applied: " + out.message(), 250));
                return Result.ok(null);
            }).thenApply(x -> {
                if (ok) return Result.<Void>ok();
                core.emit(DomainEvent.of(DomainEvent.Type.CUSTODY_ALERT).key("jail-time:" + after.id() + ":" + after.modifiedAt()).player(after.targetName()).actor("System")
                        .summary("Jail punishment #" + after.id() + " was shortened or lengthened, but JailReloaded did not accept the new time (" + out.message()
                                + "). The record and the jail now disagree: fix it with JailReloaded's /jail time command.").build());
                return Result.<Void>fail(Code.EXTERNAL, "The punishment was changed, but JailReloaded did not accept the new time: " + out.message()
                        + " Adjust it with JailReloaded's /jail time command.");
            });
        });
    }

    // ------------------------------------------------------------------ JailReloaded events
    /** JailReloaded released this prisoner (timer, login completion or a direct command). */
    public CompletableFuture<Void> onPrisonerReleased(UUID uuid, String jailPrisonerId) {
        if (selfOps.contains(uuid)) return CompletableFuture.completedFuture(null);
        return core.db.tx(c -> {
            Punishment p = Repo.Punishments.byJailPrisoner(c, uuid, jailPrisonerId);
            if (p == null || !live(p.jailState())) return null;
            return applyReleased(c, p);
        }).thenAccept(p -> {
            if (p != null) {
                refreshIndex();
                alertIfExternal(p);
            }
        }).exceptionally(t -> {
            core.log.warning("Could not record a JailReloaded release: " + t.getMessage());
            return null;
        });
    }

    /** The prisoner is no longer in JailReloaded: record how it ended. Returns the updated row. */
    private Punishment applyReleased(java.sql.Connection c, Punishment p) throws java.sql.SQLException {
        long now = core.clock.now();
        if (RELEASING.equals(p.jailState())) {
            Repo.Punishments.setJail(c, p.id(), RELEASED, null, null, null);
            closeOps(c, p.id(), "JAIL_RELEASE", OpState.DONE, now);
        } else {
            boolean served = p.expiresAt() != null && now >= p.expiresAt() - SERVED_TOLERANCE_MS;
            if (served) {
                Repo.Punishments.end(c, p.id(), PunishStatus.SERVED, "System", "Sentence served", now);
                Repo.Punishments.setJail(c, p.id(), RELEASED, null, null, null);
                audit(c, Actor.system(), "jail.served", p.targetUuid(), null, "punishment=" + p.id(), false);
            } else {
                Repo.Punishments.end(c, p.id(), PunishStatus.REVOKED, "JailReloaded",
                        "Released in JailReloaded outside AuraPunishments before the sentence ended", now);
                Repo.Punishments.setJail(c, p.id(), ENDED_EXTERNALLY, null, null, null);
                audit(c, Actor.system(), "jail.ended-externally", p.targetUuid(), null, "punishment=" + p.id(), true);
            }
            closeOps(c, p.id(), "JAIL_PLACE", OpState.DONE, now);
        }
        return Repo.Punishments.get(c, p.id());
    }

    private void closeOps(java.sql.Connection c, long punishmentId, String kind, OpState to, long now) throws java.sql.SQLException {
        for (var op : Repo.Ops.unfinished(c)) {
            if (op.punishmentId() != null && op.punishmentId() == punishmentId && op.kind().equals(kind)) Repo.Ops.setState(c, op.id(), to, null, now);
        }
    }

    private void alertIfExternal(Punishment p) {
        if (ENDED_EXTERNALLY.equals(p.jailState())) {
            core.emit(DomainEvent.of(DomainEvent.Type.CUSTODY_ALERT).key("jail-external:" + p.id()).player(p.targetName()).actor("System")
                    .summary("Jail punishment #" + p.id() + " ended in JailReloaded outside AuraPunishments (before the sentence ran out). "
                            + "Use /ap unjail so releases are recorded properly.").build());
        }
    }

    // ------------------------------------------------------------------ reconciliation
    public CompletableFuture<Result<Integer>> reconcile() {
        if (!core.jail.available()) return done(Result.ok(0));
        return core.main.call(() -> {
            Map<UUID, PrisonerState> m = new HashMap<>();
            for (PrisonerState s : core.jail.allPrisoners()) m.put(s.uuid(), s);
            return m;
        }).thenCompose(prisoners -> db(c -> {
            int changed = 0;
            long now = core.clock.now();
            for (Punishment p : Repo.Punishments.liveStandaloneJail(c)) {
                PrisonerState st = prisoners.get(p.targetUuid());
                String state = p.jailState();
                if (PLACING.equals(state)) {
                    String marker = "[" + tag(p.id()) + "]";
                    if (st != null && st.reason() != null && st.reason().startsWith(marker)) {
                        Repo.Punishments.setJail(c, p.id(), st.offlinePending() ? PENDING_PLACEMENT : HELD, st.jailName(), st.jailPrisonerId(), null);
                        closeOps(c, p.id(), "JAIL_PLACE", OpState.DONE, now);
                        audit(c, Actor.system(), "jail.reconciled", p.targetUuid(), null, "punishment=" + p.id() + " adopted", false);
                        changed++;
                    } else if (now - p.issuedAt() > IN_FLIGHT_GRACE_MS) {
                        Repo.Punishments.end(c, p.id(), PunishStatus.REVOKED, "System", "Jail was not started (interrupted before JailReloaded confirmed it)", now);
                        Repo.Punishments.setJail(c, p.id(), FAILED, null, null, "interrupted");
                        closeOps(c, p.id(), "JAIL_PLACE", OpState.FAILED, now);
                        audit(c, Actor.system(), "jail.reconciled", p.targetUuid(), null, "punishment=" + p.id() + " not started", false);
                        changed++;
                    }
                    continue;
                }
                boolean gone = st == null || (p.jailPrisonerId() != null && !p.jailPrisonerId().equals(st.jailPrisonerId()));
                if (gone) {
                    applyReleased(c, p);
                    changed++;
                } else if (PENDING_PLACEMENT.equals(state) && !st.offlinePending()) {
                    Repo.Punishments.setJail(c, p.id(), HELD, st.jailName(), null, null);
                    changed++;
                }
            }
            return Result.ok(changed);
        })).thenApply(res -> {
            if (res.isOk() && res.value() > 0) refreshIndex();
            return res;
        }).exceptionally(t -> {
            core.log.warning("Standalone jail reconciliation failed: " + t.getMessage());
            return Result.ok(0);
        });
    }

    /** Retry releases that were accepted for a revoked punishment but have not completed (JailReloaded was down, player offline...). */
    public CompletableFuture<Void> retryStuckReleases() {
        if (!core.jail.available()) return CompletableFuture.completedFuture(null);
        return core.db.tx(c -> Repo.Punishments.liveStandaloneJail(c)).thenCompose(list -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (Punishment p : list) {
                if (!RELEASING.equals(p.jailState())) continue;
                chain = chain.thenCompose(v -> core.main.call(() -> core.jail.find(p.targetUuid()).isPresent()).thenCompose(stillHeld -> {
                    if (!stillHeld) return core.db.tx(c -> {
                        applyReleased(c, p);
                        return null;
                    }).handle((x, ex) -> null);
                    return releasePhysical(Actor.system(), p, "Retry of an accepted release").handle((x, ex) -> null);
                }));
            }
            return chain;
        }).thenCompose(v -> refreshIndex()).exceptionally(t -> null);
    }

    /** Startup recovery: same resolution as the periodic reconcile. */
    public CompletableFuture<Result<Integer>> reconcileOnStartup() {
        return reconcile().thenCompose(r -> refreshIndex().thenApply(v -> r));
    }

    /** Pending standalone jail operations (for diagnostics). */
    public static String describe(Punishment p) {
        return p.type() == PunishType.JAIL && p.jailState() != null
                ? tag(p.id()) + " " + p.jailState().toLowerCase(Locale.ROOT).replace('_', ' ') + (p.jailName() == null ? "" : " in " + p.jailName())
                + (p.expiresAt() == null ? ", until released" : ", " + Durations.format(p.expiresAt() - p.issuedAt()))
                : "";
    }
}
