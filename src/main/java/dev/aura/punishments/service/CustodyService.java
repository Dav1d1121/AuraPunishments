package dev.aura.punishments.service;

import dev.aura.punishments.integration.JailPort;
import dev.aura.punishments.integration.JailPort.JailRequest;
import dev.aura.punishments.integration.JailPort.Outcome;
import dev.aura.punishments.integration.JailPort.PrisonerState;
import dev.aura.punishments.integration.JailPort.State;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Records.Custody;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.CustodyStatus;
import dev.aura.punishments.model.Simple.OpState;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import dev.aura.punishments.util.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Pretrial custody. AuraPunishments records WHY and UNTIL WHEN someone is held; JailReloaded does the
 * imprisonment. Every operation follows the same recoverable pattern:
 * <ol>
 *   <li>record intent (custody row + pending operation) in one transaction;</li>
 *   <li>call JailReloaded on the main thread and verify the outcome;</li>
 *   <li>record the verified result in a second transaction.</li>
 * </ol>
 * A crash between steps leaves a PENDING operation that {@link #reconcile()} resolves against
 * JailReloaded's real state at the next start. Nothing is reported as successful unless it was.
 */
public final class CustodyService extends Svc {
    private final PunishmentService punishments;
    /** Players whose JailReloaded record we are changing right now; their events are ours, not external. */
    private final Set<UUID> selfOps = ConcurrentHashMap.newKeySet();

    public CustodyService(Core core, PunishmentService punishments) {
        super(core);
        this.punishments = punishments;
        punishments.bind(this);
    }

    private volatile Map<String, Custody> heldByName = Map.of();

    /** Live custody for a player name (any case), from an in-memory index refreshed after every change. */
    public java.util.Optional<Custody> heldByName(String name) {
        return java.util.Optional.ofNullable(name == null ? null : heldByName.get(name.toLowerCase(java.util.Locale.ROOT)));
    }

    public CompletableFuture<Void> refreshIndex() {
        return core.db.tx(c -> Repo.Custodies.allLive(c)).thenAccept(list -> {
            Map<String, Custody> m = new java.util.HashMap<>();
            for (Custody cu : list) m.put(cu.name().toLowerCase(java.util.Locale.ROOT), cu);
            heldByName = Map.copyOf(m);
        }).exceptionally(t -> null);
    }

    public static String tag(long caseId) {
        return "AP-" + String.format("%04d", caseId);
    }

    // ------------------------------------------------------------------ initiate
    public CompletableFuture<Result<Custody>> initiate(Actor actor, long caseId) {
        Settings s = core.settings;
        if (!s.custody.enabled()) return done(Result.fail(Code.UNAVAILABLE, "Pretrial custody is disabled in the configuration."));
        Result<Void> perm = core.auth.require(actor, Perms.CUSTODY_INITIATE, "initiate pretrial detention");
        if (!perm.isOk()) return done(perm.cast());
        Result<Void> access = targetAccess(actor);
        if (!access.isOk()) return done(access.cast());
        if (!core.jail.available()) {
            return done(Result.fail(Code.UNAVAILABLE, "JailReloaded is not available (" + core.jail.status() + "). Detention was not started."));
        }
        return core.db.tx(c -> Repo.Cases.get(c, caseId)).handle((cs, ex) -> ex == null ? cs : null).thenCompose(cs -> {
            if (cs == null) return done(Result.<Custody>fail(Code.NOT_FOUND, "Case " + caseId + " does not exist."));
            if (!core.auth.canViewCase(actor, cs)) return done(Result.<Custody>fail(Code.NO_PERMISSION, "You cannot access that case."));
            return checkTarget(actor, cs.accusedUuid()).thenCompose(tc -> {
                if (!tc.isOk()) return done(tc.<Custody>cast());
                return startCustody(actor, caseId);
            });
        });
    }

    private CompletableFuture<Result<Custody>> startCustody(Actor actor, long caseId) {
        long[] custodyIdBox = new long[1];
        Case[] caseBox = new Case[1];
        // Step 1: record intent.
        return db(c -> {
            Case cs = Repo.Cases.get(c, caseId);
            if (cs == null) return Result.<Long>fail(Code.NOT_FOUND, "Case " + caseId + " does not exist.");
            if (!cs.status().custodyAllowed()) {
                return Result.<Long>fail(Code.STATE, "Case " + cs.ref() + " is " + cs.status().display() + "; custody cannot be started.");
            }
            Custody live = Repo.Custodies.liveForPlayer(c, cs.accusedUuid());
            if (live != null) {
                return Result.<Long>fail(Code.DUPLICATE, cs.accusedName() + " is already in custody (case " + tag(live.caseId()) + ").");
            }
            long now = core.clock.now();
            long id = Repo.Custodies.insert(c, caseId, cs.accusedUuid(), cs.accusedName(), actor.dbUuid(), actor.name(), now);
            Repo.Ops.add(c, "JAIL_START", caseId, id, cs.accusedUuid(), null, now);
            Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "CUSTODY_REQUESTED", cs.status(), cs.status(), null);
            audit(c, actor, "custody.request", cs.accusedUuid(), caseId, "custody=" + id, false);
            caseBox[0] = cs;
            custodyIdBox[0] = id;
            return Result.ok(id);
        }).thenCompose(r1 -> {
            if (!r1.isOk()) return done(r1.<Custody>cast());
            Case cs = caseBox[0];
            long custodyId = custodyIdBox[0];
            Settings s = core.settings;
            JailRequest req = new JailRequest(cs.accusedUuid(), cs.accusedName(), cs.id(), tag(cs.id()),
                    Text.truncate(cs.description(), 100), actor.name(), -1L, s.custody.jailName(), s.custody.cellName(),
                    s.custody.muteWhileHeld());
            // Step 2: JailReloaded (main thread), verified by the adapter.
            return core.main.call(() -> {
                selfOps.add(cs.accusedUuid());
                try {
                    return core.jail.jail(req);
                } catch (Throwable t) {
                    return Outcome.of(State.ERROR, "JailReloaded threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
                } finally {
                    selfOps.remove(cs.accusedUuid());
                }
            }).thenCompose(out -> finishStart(actor, cs, custodyId, out));
        });
    }

    private CompletableFuture<Result<Custody>> finishStart(Actor actor, Case cs, long custodyId, Outcome out) {
        boolean ok = out.ok() && out.jailPrisonerId() != null;
        if (out.state() == State.ALREADY_JAILED) {
            ok = false;
        }
        final boolean success = ok;
        // Step 3: record the verified result.
        return db(c -> {
            long now = core.clock.now();
            Long opId = Repo.Ops.unfinished(c).stream().filter(o -> o.custodyId() != null && o.custodyId() == custodyId && o.kind().equals("JAIL_START"))
                    .map(o -> o.id()).findFirst().orElse(null);
            if (success) {
                long deadline = now + core.settings.custody.reviewDeadline();
                Repo.Custodies.markActive(c, custodyId, now, deadline, out.jailName(), out.jailPrisonerId(), now);
                if (opId != null) Repo.Ops.setState(c, opId, OpState.DONE, null, now);
                Repo.Events.add(c, cs.id(), now, actor.dbUuid(), actor.name(), "CUSTODY_STARTED", cs.status(), cs.status(),
                        "Held in " + out.jailName() + " until review " + Durations.format(core.settings.custody.reviewDeadline()));
                audit(c, actor, "custody.start", cs.accusedUuid(), cs.id(), "custody=" + custodyId + " jail=" + out.jailName(), false);
                return Result.ok(Repo.Custodies.get(c, custodyId));
            }
            String msg = out.message() == null ? "JailReloaded did not confirm the detention." : out.message();
            Repo.Custodies.end(c, custodyId, CustodyStatus.FAILED, actor.name(), msg, now);
            Repo.Custodies.setStatus(c, custodyId, CustodyStatus.FAILED, msg, now);
            if (opId != null) Repo.Ops.setState(c, opId, OpState.FAILED, msg, now);
            Repo.Events.add(c, cs.id(), now, actor.dbUuid(), actor.name(), "CUSTODY_FAILED", cs.status(), cs.status(), Text.truncate(msg, 200));
            audit(c, actor, "custody.failed", cs.accusedUuid(), cs.id(), "custody=" + custodyId + " " + Text.truncate(msg, 150), false);
            // Persist the failure, then report it. (Returning a failure would roll the record back, so commit first.)
            return Result.ok(Repo.Custodies.get(c, custodyId));
        }).thenApply(res -> {
            if (!res.isOk()) {
                return Result.<Custody>fail(Code.DB_ERROR, "JailReloaded " + (success ? "confirmed the detention, but it could not be recorded. It will be reconciled automatically." : "did not confirm the detention.")
                        + " " + res.message());
            }
            Custody cu = res.value();
            if (cu.status() == CustodyStatus.FAILED) {
                return Result.<Custody>fail(Code.EXTERNAL, "Detention failed: " + cu.lastError());
            }
            refreshIndex();
            core.emit(DomainEvent.of(DomainEvent.Type.CUSTODY_STARTED).caseId(cs.id()).player(cs.accusedName()).actor(actor.name())
                    .summary("Detained in " + cu.jailName()).field("Jail", cu.jailName())
                    .field("Review deadline", "<t:" + (cu.deadline() / 1000) + ":R>").build());
            return Result.ok(cu);
        });
    }

    // ------------------------------------------------------------------ release
    /** Release pretrial custody for a case (staff action). */
    public CompletableFuture<Result<Custody>> release(Actor actor, long caseId, String reason) {
        if (!core.auth.has(actor, Perms.CUSTODY_RELEASE) && !core.auth.has(actor, Perms.JUDICIAL_RELEASE)) {
            return done(Result.fail(Code.NO_PERMISSION, "You do not have permission to release a prisoner."));
        }
        String why = Text.clean(reason);
        if (why.length() < core.settings.cases.minReason()) {
            return done(Result.fail(Code.INVALID, "A reason of at least " + core.settings.cases.minReason() + " characters is required."));
        }
        return core.db.tx(c -> Repo.Custodies.liveForCase(c, caseId)).handle((cu, ex) -> ex == null ? cu : null).thenCompose(cu -> {
            if (cu == null) return done(Result.<Custody>fail(Code.NOT_FOUND, "There is no active custody for that case."));
            if (cu.status() == CustodyStatus.SERVING) {
                return done(Result.<Custody>fail(Code.STATE, "That player is serving a jail sentence. Revoke the sentence to end it."));
            }
            return doRelease(actor, cu, why);
        });
    }

    /** Internal: a case reached a final state; its pretrial custody must end. */
    CompletableFuture<Result<Custody>> releaseForCaseResolution(Actor actor, long caseId, String reason) {
        return core.db.tx(c -> Repo.Custodies.liveForCase(c, caseId)).handle((cu, ex) -> ex == null ? cu : null).thenCompose(cu -> {
            if (cu == null || cu.status() == CustodyStatus.SERVING) return done(Result.<Custody>ok(cu));
            return doRelease(actor, cu, reason);
        });
    }

    CompletableFuture<Result<Custody>> releaseForPunishment(Actor actor, Punishment p, String reason) {
        if (p.custodyId() == null) return done(Result.fail(Code.NOT_FOUND, "That sentence has no jail record."));
        return core.db.tx(c -> Repo.Custodies.get(c, p.custodyId())).handle((cu, ex) -> ex == null ? cu : null).thenCompose(cu -> {
            if (cu == null || !cu.status().isLive()) return done(Result.<Custody>ok(cu));
            return doRelease(actor, cu, reason);
        });
    }

    CompletableFuture<Result<Custody>> doRelease(Actor actor, Custody cu, String reason) {
        CustodyStatus before = cu.status();
        UUID uuid = cu.uuid();
        // Step 1: record intent.
        return db(c -> {
            long now = core.clock.now();
            Custody fresh = Repo.Custodies.get(c, cu.id());
            if (fresh == null || !fresh.status().isLive()) return Result.<Custody>fail(Code.STATE, "That custody has already ended.");
            if (fresh.status() != CustodyStatus.RELEASING) Repo.Custodies.setStatus(c, cu.id(), CustodyStatus.RELEASING, null, now);
            Repo.Ops.add(c, "JAIL_RELEASE", cu.caseId(), cu.id(), uuid, null, now);
            audit(c, actor, "custody.release.request", uuid, cu.caseId(), "custody=" + cu.id() + " " + Text.truncate(reason, 120), false);
            return Result.ok(fresh);
        }).thenCompose(r1 -> {
            if (!r1.isOk()) return done(r1);
            if (!core.jail.available()) {
                return revertRelease(cu, before, "JailReloaded is not available: " + core.jail.status())
                        .thenApply(x -> Result.<Custody>fail(Code.UNAVAILABLE, "JailReloaded is not available (" + core.jail.status()
                                + "). The prisoner was NOT released; it will be retried automatically."));
            }
            // Step 2: JailReloaded.
            return core.main.call(() -> {
                selfOps.add(uuid);
                try {
                    return core.jail.release(uuid, cu.jailPrisonerId(), actor.name());
                } catch (Throwable t) {
                    return Outcome.of(State.ERROR, "JailReloaded threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
                } finally {
                    selfOps.remove(uuid);
                }
            }).thenCompose(out -> finishRelease(actor, cu, before, reason, out));
        });
    }

    private CompletableFuture<Void> revertRelease(Custody cu, CustodyStatus before, String error) {
        return core.db.tx(c -> {
            long now = core.clock.now();
            Repo.Custodies.setStatus(c, cu.id(), before == CustodyStatus.RELEASING ? CustodyStatus.ACTIVE : before, error, now);
            for (var op : Repo.Ops.unfinished(c)) {
                if (op.custodyId() != null && op.custodyId() == cu.id() && op.kind().equals("JAIL_RELEASE")) {
                    Repo.Ops.setState(c, op.id(), OpState.FAILED, error, now);
                }
            }
            return null;
        }).handle((v, ex) -> null);
    }

    private CompletableFuture<Result<Custody>> finishRelease(Actor actor, Custody cu, CustodyStatus before, String reason, Outcome out) {
        State st = out.state();
        boolean released = st == State.RELEASED || st == State.NOT_JAILED || st == State.NOT_OURS;
        boolean pending = st == State.RELEASE_PENDING;
        if (!released && !pending) {
            String err = out.message() == null ? "JailReloaded refused the release." : out.message();
            return revertRelease(cu, before, err).thenApply(x -> Result.<Custody>fail(Code.EXTERNAL,
                    "The prisoner was NOT released: " + err + " It will be retried automatically."));
        }
        return db(c -> {
            long now = core.clock.now();
            CustodyStatus end = st == State.NOT_OURS ? CustodyStatus.ENDED_EXTERNALLY : CustodyStatus.RELEASED;
            String note = st == State.NOT_OURS ? "Record in JailReloaded was not this custody (already ended): " + reason : reason;
            if (released) {
                Repo.Custodies.end(c, cu.id(), end, actor.name(), Text.truncate(note, 250), now);
                if (before == CustodyStatus.SERVING) Repo.Punishments.serveByCustody(c, cu.id(), now);
            }
            for (var op : Repo.Ops.unfinished(c)) {
                if (op.custodyId() != null && op.custodyId() == cu.id() && op.kind().equals("JAIL_RELEASE")) {
                    Repo.Ops.setState(c, op.id(), released ? OpState.DONE : OpState.EXTERNAL_DONE, null, now);
                }
            }
            Repo.Events.add(c, cu.caseId(), now, actor.dbUuid(), actor.name(), released ? "CUSTODY_RELEASED" : "CUSTODY_RELEASE_PENDING",
                    null, null, Text.truncate(reason, 250));
            audit(c, actor, released ? "custody.release" : "custody.release.pending", cu.uuid(), cu.caseId(),
                    "custody=" + cu.id() + " " + Text.truncate(reason, 120), false);
            return Result.ok(Repo.Custodies.get(c, cu.id()));
        }).thenApply(res -> {
            if (res.isOk()) {
                refreshIndex();
                core.emit(DomainEvent.of(DomainEvent.Type.CUSTODY_RELEASED).caseId(cu.caseId()).player(cu.name()).actor(actor.name())
                        .summary(pending ? "Release accepted (completes at next login)" : "Released").field("Reason", Text.truncate(reason, 200)).build());
            }
            return res;
        });
    }

    // ------------------------------------------------------------------ extend
    public CompletableFuture<Result<Custody>> extend(Actor actor, long caseId, String reason) {
        Result<Void> perm = core.auth.require(actor, Perms.JUDICIAL_MANAGE, "extend a custody review deadline");
        if (!perm.isOk()) return done(perm.cast());
        String why = Text.clean(reason);
        if (why.length() < core.settings.cases.minReason()) {
            return done(Result.fail(Code.INVALID, "A reason of at least " + core.settings.cases.minReason() + " characters is required."));
        }
        return db(c -> {
            Custody cu = Repo.Custodies.liveForCase(c, caseId);
            if (cu == null || cu.status() != CustodyStatus.ACTIVE) return Result.<Custody>fail(Code.NOT_FOUND, "There is no active pretrial custody for that case.");
            if (cu.extensions() >= core.settings.custody.maxExtensions()) {
                return Result.<Custody>fail(Code.LIMIT, "This custody has already been extended the maximum number of times (" + core.settings.custody.maxExtensions() + ").");
            }
            long now = core.clock.now();
            long base = Math.max(cu.deadline() == null ? now : cu.deadline(), now);
            Repo.Custodies.extend(c, cu.id(), base + core.settings.custody.extensionLength(), now);
            Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "CUSTODY_EXTENDED", null, null, Text.truncate(why, 250));
            audit(c, actor, "custody.extend", cu.uuid(), caseId, "custody=" + cu.id() + " " + Text.truncate(why, 120), true);
            return Result.ok(Repo.Custodies.get(c, cu.id()));
        });
    }

    // ------------------------------------------------------------------ queries
    public CompletableFuture<Result<List<Custody>>> live(Actor actor) {
        Result<Void> perm = core.auth.requireAny(actor, "view custody", Perms.CUSTODY_VIEW, Perms.JUDICIAL_VIEW);
        if (!perm.isOk()) return done(perm.cast());
        return db(c -> Result.ok(Repo.Custodies.allLive(c)));
    }

    public CompletableFuture<Result<Custody>> liveForPlayer(UUID uuid) {
        return db(c -> Result.ok(Repo.Custodies.liveForPlayer(c, uuid)));
    }

    // ------------------------------------------------------------------ external events (from JailReloaded)
    /** JailReloaded released a prisoner. If we did not cause it, reconcile our record and alert. */
    public CompletableFuture<Void> onPrisonerReleased(UUID uuid, String jailPrisonerId) {
        if (selfOps.contains(uuid)) return CompletableFuture.completedFuture(null);
        List<DomainEvent> events = new ArrayList<>();
        return db(c -> {
            Custody cu = Repo.Custodies.liveForPlayer(c, uuid);
            if (cu == null) return Result.<Void>ok();
            if (cu.jailPrisonerId() != null && jailPrisonerId != null && !cu.jailPrisonerId().equals(jailPrisonerId)) return Result.<Void>ok();
            long now = core.clock.now();
            switch (cu.status()) {
                case SERVING -> {
                    Repo.Custodies.end(c, cu.id(), CustodyStatus.RELEASED, "JailReloaded", "Sentence completed", now);
                    Repo.Punishments.serveByCustody(c, cu.id(), now);
                    Repo.Events.add(c, cu.caseId(), now, null, "JailReloaded", "SENTENCE_SERVED", null, null, "Jail sentence completed");
                }
                case RELEASING -> {
                    Repo.Custodies.end(c, cu.id(), CustodyStatus.RELEASED, "JailReloaded", "Release completed", now);
                    Repo.Events.add(c, cu.caseId(), now, null, "JailReloaded", "CUSTODY_RELEASED", null, null, "Release completed");
                }
                default -> {
                    Repo.Custodies.end(c, cu.id(), CustodyStatus.ENDED_EXTERNALLY, "JailReloaded", "Released outside AuraPunishments", now);
                    Repo.Events.add(c, cu.caseId(), now, null, "JailReloaded", "CUSTODY_ENDED_EXTERNALLY", null, null,
                            "The prisoner was released through JailReloaded directly");
                    Repo.Audits.append(c, now, null, "JailReloaded", "custody.external-release", uuid, cu.caseId(), "custody=" + cu.id(), true);
                    events.add(DomainEvent.of(DomainEvent.Type.CUSTODY_ALERT).caseId(cu.caseId()).player(cu.name())
                            .summary("Released through JailReloaded directly (outside the case process)").build());
                }
            }
            return Result.<Void>ok();
        }).thenAccept(r -> {
            events.forEach(core::emit);
            refreshIndex();
        });
    }

    // ------------------------------------------------------------------ reconciliation
    /**
     * Compare AuraPunishments' live custody records with JailReloaded's actual prisoners and repair
     * differences. Safe to run at any time; run at startup and periodically.
     */
    public CompletableFuture<Result<Integer>> reconcile() {
        return reconcile(false);
    }

    /** Startup variant: nothing can be in flight yet, so interrupted requests are resolved immediately. */
    public CompletableFuture<Result<Integer>> reconcileOnStartup() {
        return reconcile(true);
    }

    /** Requests younger than this are left alone by the periodic pass: they may still be talking to JailReloaded. */
    static final long IN_FLIGHT_GRACE_MS = 60_000;

    private CompletableFuture<Result<Integer>> reconcile(boolean startup) {
        if (!core.jail.available()) return done(Result.fail(Code.UNAVAILABLE, "JailReloaded unavailable; reconciliation skipped."));
        return core.main.call(() -> {
            Map<UUID, PrisonerState> m = new java.util.HashMap<>();
            for (PrisonerState ps : core.jail.allPrisoners()) m.put(ps.uuid(), ps);
            return m;
        }).thenCompose(snapshot -> {
            List<DomainEvent> events = new ArrayList<>();
            List<Custody> retryRelease = new ArrayList<>();
            return db(c -> {
                int changed = 0;
                long now = core.clock.now();
                for (Custody cu : Repo.Custodies.allLive(c)) {
                    PrisonerState ps = snapshot.get(cu.uuid());
                    boolean tagged = ps != null && ps.reason() != null && ps.reason().contains(tag(cu.caseId()));
                    switch (cu.status()) {
                        case REQUESTED -> {
                            if (!startup && now - cu.requestedAt() < IN_FLIGHT_GRACE_MS) break; // possibly still in flight
                            if (ps != null && tagged) {
                                long deadline = now + core.settings.custody.reviewDeadline();
                                Repo.Custodies.markActive(c, cu.id(), cu.requestedAt(), cu.requestedAt() + core.settings.custody.reviewDeadline(),
                                        ps.jailName(), ps.jailPrisonerId(), now);
                                markOps(c, cu.id(), "JAIL_START", OpState.DONE, null, now);
                                Repo.Events.add(c, cu.caseId(), now, null, "System", "CUSTODY_RECOVERED", null, null,
                                        "Interrupted detention found in JailReloaded and adopted");
                            } else {
                                Repo.Custodies.end(c, cu.id(), CustodyStatus.FAILED, "System", "Interrupted before JailReloaded confirmed", now);
                                markOps(c, cu.id(), "JAIL_START", OpState.FAILED, "Interrupted; no prisoner found in JailReloaded", now);
                                Repo.Events.add(c, cu.caseId(), now, null, "System", "CUSTODY_FAILED", null, null,
                                        "Detention was interrupted and no prisoner exists in JailReloaded");
                            }
                            changed++;
                        }
                        case ACTIVE, SERVING -> {
                            boolean mismatch = ps != null && cu.jailPrisonerId() != null && ps.jailPrisonerId() != null
                                    && !cu.jailPrisonerId().equals(ps.jailPrisonerId());
                            if (ps == null || mismatch) {
                                if (cu.status() == CustodyStatus.SERVING) {
                                    Repo.Custodies.end(c, cu.id(), CustodyStatus.RELEASED, "System", "Sentence completed (not found in JailReloaded)", now);
                                    Repo.Punishments.serveByCustody(c, cu.id(), now);
                                    Repo.Events.add(c, cu.caseId(), now, null, "System", "SENTENCE_SERVED", null, null, "Jail sentence no longer present in JailReloaded");
                                } else {
                                    Repo.Custodies.end(c, cu.id(), CustodyStatus.ENDED_EXTERNALLY, "System",
                                            mismatch ? "Prisoner re-jailed under a different JailReloaded record" : "Prisoner no longer held in JailReloaded", now);
                                    Repo.Events.add(c, cu.caseId(), now, null, "System", "CUSTODY_ENDED_EXTERNALLY", null, null,
                                            "Custody ended outside AuraPunishments");
                                    Repo.Audits.append(c, now, null, "System", "custody.reconcile.ended", cu.uuid(), cu.caseId(), "custody=" + cu.id(), true);
                                    events.add(DomainEvent.of(DomainEvent.Type.CUSTODY_ALERT).caseId(cu.caseId()).player(cu.name())
                                            .summary("Custody record ended: prisoner is no longer held in JailReloaded").build());
                                }
                                changed++;
                            } else if (cu.jailPrisonerId() == null && ps.jailPrisonerId() != null) {
                                Repo.Custodies.markActive(c, cu.id(), cu.startedAt() == null ? now : cu.startedAt(),
                                        cu.deadline() == null ? now : cu.deadline(), ps.jailName(), ps.jailPrisonerId(), now);
                                changed++;
                            }
                        }
                        case RELEASING -> {
                            if (ps == null || (ps.remainingMs() == 0 && !ps.forever())) {
                                // Gone, or accepted for release and waiting for the player's next login.
                                if (ps == null) {
                                    Repo.Custodies.end(c, cu.id(), CustodyStatus.RELEASED, "System", "Release completed", now);
                                    markOps(c, cu.id(), "JAIL_RELEASE", OpState.DONE, null, now);
                                    changed++;
                                }
                            } else {
                                retryRelease.add(cu);
                            }
                        }
                        default -> { }
                    }
                }
                return Result.ok(changed);
            }).thenCompose(res -> {
                events.forEach(core::emit);
                CompletableFuture<Void> retries = CompletableFuture.completedFuture(null);
                for (Custody cu : retryRelease) {
                    retries = retries.thenCompose(v -> doRelease(Actor.system(), cu, "Retrying interrupted release").thenAccept(x -> { }));
                }
                return retries.thenApply(v -> res);
            });
        });
    }

    private void markOps(java.sql.Connection c, long custodyId, String kind, OpState state, String error, long now) throws java.sql.SQLException {
        for (var op : Repo.Ops.unfinished(c)) {
            if (op.custodyId() != null && op.custodyId() == custodyId && op.kind().equals(kind)) {
                Repo.Ops.setState(c, op.id(), state, error, now);
            }
        }
    }

    /** Ensure no acquitted/closed case still holds a prisoner (e.g. after a failed release). */
    public CompletableFuture<Void> retryStuckReleases() {
        return core.db.tx(c -> {
            List<Custody> stuck = new ArrayList<>();
            for (Custody cu : Repo.Custodies.allLive(c)) {
                if (cu.status() == CustodyStatus.SERVING) continue;
                Case cs = Repo.Cases.get(c, cu.caseId());
                if (cs != null && !cs.status().custodyAllowed() && cs.status() != CaseStatus.SENTENCED) stuck.add(cu);
            }
            return stuck;
        }).thenCompose(list -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (Custody cu : list) {
                chain = chain.thenCompose(v -> doRelease(Actor.system(), cu, "Case resolved; releasing automatically").thenAccept(r -> {
                    if (!r.isOk()) {
                        core.emit(DomainEvent.of(DomainEvent.Type.CUSTODY_ALERT).caseId(cu.caseId()).player(cu.name())
                                .summary("Case is resolved but the prisoner could not be released: " + r.message()).build());
                    }
                }));
            }
            return chain;
        }).exceptionally(t -> {
            core.log.warning("Stuck-release check failed: " + t.getMessage());
            return null;
        });
    }

    // ------------------------------------------------------------------ deadlines
    /** Send due alerts and apply the on-deadline policy. Run about once a minute. */
    public CompletableFuture<Void> sweepDeadlines() {
        Settings s = core.settings;
        List<DomainEvent> events = new ArrayList<>();
        List<Custody> autoRelease = new ArrayList<>();
        return core.db.tx(c -> {
            long now = core.clock.now();
            for (Custody cu : Repo.Custodies.allLive(c)) {
                if (cu.status() != CustodyStatus.ACTIVE || cu.deadline() == null) continue;
                Set<String> sent = new HashSet<>();
                for (String t : cu.alertsSent().split(",")) if (!t.isBlank()) sent.add(t);
                boolean dirty = false;
                long left = cu.deadline() - now;
                if (left > 0) {
                    for (long before : s.custody.alertBefore()) {
                        String token = "a" + before;
                        if (left <= before && !sent.contains(token)) {
                            // only the nearest un-sent threshold is announced; earlier ones are marked too
                            for (long b2 : s.custody.alertBefore()) if (b2 >= left) sent.add("a" + b2);
                            events.add(DomainEvent.of(DomainEvent.Type.CUSTODY_ALERT).caseId(cu.caseId()).player(cu.name())
                                    .summary("Pretrial custody review due in " + Durations.format(left)).field("Deadline", "<t:" + (cu.deadline() / 1000) + ":R>").build());
                            dirty = true;
                            break;
                        }
                    }
                } else {
                    long lastOverdue = 0;
                    for (String t : sent) if (t.startsWith("o")) lastOverdue = Math.max(lastOverdue, Long.parseLong(t.substring(1)));
                    if (lastOverdue == 0 || now - lastOverdue >= s.custody.overdueRepeat()) {
                        sent.removeIf(t -> t.startsWith("o"));
                        sent.add("o" + now);
                        String tail = s.custody.releaseAfterGrace()
                                ? " Automatic release in " + Durations.format(Math.max(0, cu.deadline() + s.custody.gracePeriod() - now)) + " unless a decision is made."
                                : " A judge must decide or release.";
                        events.add(DomainEvent.of(DomainEvent.Type.CUSTODY_ALERT).caseId(cu.caseId()).player(cu.name())
                                .summary("Custody review deadline passed " + Durations.format(now - cu.deadline()) + " ago." + tail).build());
                        dirty = true;
                    }
                    if (s.custody.releaseAfterGrace() && now > cu.deadline() + s.custody.gracePeriod()) autoRelease.add(cu);
                }
                if (dirty) Repo.Custodies.setAlerts(c, cu.id(), sent.stream().collect(Collectors.joining(",")), now);
            }
            return null;
        }).thenCompose(v -> {
            events.forEach(core::emit);
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (Custody cu : autoRelease) {
                chain = chain.thenCompose(x -> doRelease(Actor.system(), cu,
                        "Review deadline and grace period passed without a decision; released automatically").thenAccept(r -> {
                    if (!r.isOk()) core.log.warning("Automatic release for case " + tag(cu.caseId()) + " failed: " + r.message());
                }));
            }
            return chain;
        }).exceptionally(t -> {
            core.log.warning("Custody deadline sweep failed: " + t.getMessage());
            return null;
        });
    }
}
