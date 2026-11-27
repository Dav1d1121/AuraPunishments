package dev.aura.punishments.service;

import java.util.Map;
import java.util.LinkedHashMap;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Records.Profile;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.CaseKind;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.model.Simple.Source;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import dev.aura.punishments.util.Text;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Issues, revokes and expires warnings, kicks, mutes, bans and IP bans. Authoritative for every
 * punishment created through AuraPunishments (quick, judicial, direct and emergency pathways all
 * funnel through {@link #insert}).
 */
public final class PunishmentService extends Svc {
    private final PunishmentCache cache = new PunishmentCache();
    private CustodyService custody;
    private JailService jails;

    /** @param note optional staff note stored with the punishment (judicial system off) or on the player's profile (on) */
    public record Request(Target target, PunishType type, long durationMs, String reason,
                          List<EvidenceValidator.Input> evidence, String note) {
        public Request(Target target, PunishType type, long durationMs, String reason, List<EvidenceValidator.Input> evidence) {
            this(target, type, durationMs, reason, evidence, null);
        }
    }

    public PunishmentService(Core core) {
        super(core);
    }

    void bind(CustodyService custody) {
        this.custody = custody;
    }

    public void bind(JailService jails) {
        this.jails = jails;
    }

    public PunishmentCache cache() {
        return cache;
    }

    /** Load every ACTIVE punishment into the cache (startup). */
    public CompletableFuture<Result<Integer>> loadCache() {
        return db(c -> {
            List<Punishment> active = Repo.Punishments.active(c);
            cache.load(active);
            return Result.ok(active.size());
        });
    }

    // ------------------------------------------------------------------ quick pathway
    public CompletableFuture<Result<Punishment>> issueQuick(Actor actor, Request r) {
        Settings s = core.settings;
        PunishType type = r.type();
        final boolean judicial = core.mode.enabled();
        if (type == PunishType.JAIL) {
            if (judicial) {
                return done(Result.fail(Code.INVALID, "Jail sentences are issued through a case (pretrial custody or a judicial sentence)."));
            }
            if (jails == null || !core.jail.available()) {
                return done(Result.fail(Code.UNAVAILABLE, "JailReloaded is not available (" + core.jail.status() + "). Nobody was jailed."));
            }
        }
        if (!core.auth.canQuick(actor, type)) {
            return done(Result.fail(Code.NO_PERMISSION, "You do not have permission to issue " + type.display().toLowerCase() + "s."));
        }
        Result<Void> access = targetAccess(actor);
        if (!access.isOk()) return done(access.cast());
        String reason = Text.clean(r.reason());
        if (s.quick.requireReason() || type != PunishType.KICK) {
            if (reason.length() < s.cases.minReason()) {
                return done(Result.fail(Code.INVALID, "A reason of at least " + s.cases.minReason() + " characters is required."));
            }
        }
        if (reason.length() > s.cases.maxReason()) {
            return done(Result.fail(Code.INVALID, "The reason is too long (max " + s.cases.maxReason() + " characters)."));
        }
        long duration = type.canBeTimed() ? r.durationMs() : 0;
        if (type.canBeTimed() && duration != Durations.PERMANENT && duration <= 0) {
            return done(Result.fail(Code.INVALID, "Specify a duration (for example 1d) or 'permanent'."));
        }
        Result<Void> dur = core.auth.checkDuration(actor, type, duration, false);
        if (!dur.isOk()) return done(dur.cast());

        // evidence
        List<EvidenceValidator.Input> evidence = new java.util.ArrayList<>();
        for (EvidenceValidator.Input in : r.evidence() == null ? List.<EvidenceValidator.Input>of() : r.evidence()) {
            Result<EvidenceValidator.Input> v = EvidenceValidator.validate(s, in);
            if (!v.isOk()) return done(v.cast());
            evidence.add(v.value());
        }
        if (s.quick.requireEvidence().contains(type) && evidence.isEmpty()) {
            return done(Result.fail(Code.EVIDENCE, type.display() + "s require at least one evidence reference."));
        }
        if (evidence.size() > s.cases.maxEvidence()) {
            return done(Result.fail(Code.EVIDENCE, "Too many evidence items."));
        }
        String noteText = r.note() == null ? null : Text.clean(r.note());
        if (noteText != null && noteText.isEmpty()) noteText = null;
        if (noteText != null) {
            if (!core.auth.has(actor, Perms.NOTE_ADD)) return done(Result.fail(Code.NO_PERMISSION, "You do not have permission to add staff notes."));
            if (noteText.length() > s.cases.maxNote()) return done(Result.fail(Code.INVALID, "The note is too long (max " + s.cases.maxNote() + ")."));
        }
        final String note = noteText;
        final String cleanReason = reason;
        final long dur2 = duration;
        // A kick needs a connected player; recording one against an offline player would do nothing.
        CompletableFuture<Boolean> online = type == PunishType.KICK
                ? core.main.call(() -> core.enforcement.isOnline(r.target().uuid())) : CompletableFuture.completedFuture(true);
        return online.thenCompose(isOn -> {
            if (!isOn) return done(Result.<Void>fail(Code.INVALID, r.target().name() + " is not online, so there is nobody to kick."));
            return checkTarget(actor, r.target().uuid());
        }).thenCompose(tc -> {
            if (!tc.isOk()) return done(tc.<Punishment>cast());
            return db(c -> {
                Long caseId = null;
                long now0 = core.clock.now();
                if (!evidence.isEmpty() && judicial) {
                    long now = now0;
                    caseId = Repo.Cases.insert(c, CaseStatus.CLOSED, CaseKind.QUICK, r.target().uuid(), r.target().name(),
                            "other", Severity.MODERATE, clip(cleanReason, 400), null, actor.dbUuid(), actor.name(), now);
                    Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "QUICK_PUNISHMENT", null, CaseStatus.CLOSED,
                            type.display() + (type.canBeTimed() ? " (" + Durations.format(dur2) + ")" : ""));
                    for (EvidenceValidator.Input in : evidence) {
                        Repo.Evidences.add(c, caseId, in.type(), in.reference(), in.description(), actor.dbUuid(), actor.name(), now, null);
                    }
                }
                Result<Punishment> ins = insert(c, actor, type, r.target(), dur2, cleanReason, judicial ? Source.QUICK : Source.STANDARD, caseId, null, null, null, true);
                if (!ins.isOk()) return ins;
                Punishment pun = ins.value();
                if (!judicial) {
                    // No case exists: evidence and the optional note belong to the punishment itself.
                    for (EvidenceValidator.Input in : evidence) {
                        Repo.Evidences.addToPunishment(c, pun.id(), in.type(), in.reference(), in.description(), actor.dbUuid(), actor.name(), now0, null);
                    }
                    if (note != null) Repo.Notes.addForPunishment(c, pun.id(), pun.targetUuid(), actor.dbUuid(), actor.name(), note, false, now0);
                } else if (note != null) {
                    Repo.Notes.add(c, null, pun.targetUuid(), actor.dbUuid(), actor.name(), note, false, now0);
                }
                if (type == PunishType.JAIL) {
                    jails.recordPlacing(c, pun, now0); // the review (if any) is created once JailReloaded confirms
                    return Result.ok(Repo.Punishments.get(c, pun.id()));
                }
                ReviewService.createIfRequired(core, c, actor, pun, caseId);
                return ins;
            });
        }).thenCompose(res -> {
            if (!res.isOk()) return done(res);
            if (type == PunishType.JAIL) {
                return jails.place(actor, res.value()).thenApply(placed -> {
                    if (placed.isOk()) emitIssued(placed.value());
                    return placed;
                });
            }
            return afterIssue(res.value(), false).thenApply(v -> {
                emitIssued(res.value());
                return res;
            });
        });
    }

    /** Discord/staff events for a quick punishment, plus the review request when one was created. */
    private void emitIssued(Punishment p) {
        boolean warn = p.type() == PunishType.WARN;
        DomainEvent.Builder b = DomainEvent.of(warn ? DomainEvent.Type.WARNING : DomainEvent.Type.QUICK_PUNISHMENT)
                .key((warn ? "warning:" : "quick:") + p.id()).caseId(p.caseId()).player(p.targetName()).actor(p.issuerName())
                .summary(p.classification() + ": " + p.type().display() + (p.type().canBeTimed() ? " (" + (p.expiresAt() == null ? "permanent" : Durations.format(p.expiresAt() - p.issuedAt())) + ")" : ""))
                .field("Classification", p.classification()).field("Reason", clip(p.reason(), 300));
        core.emit(b.build());
        core.db.tx(c -> Repo.Reviews.byPunishment(c, p.id())).thenAccept(rv -> {
            if (rv != null && rv.status() == dev.aura.punishments.model.Simple.ReviewStatus.PENDING_REVIEW) ReviewService.emitCreated(core, rv, p);
        });
    }

    // ------------------------------------------------------------------ shared insert (inside caller's transaction)
    /**
     * Insert a punishment row, refusing duplicates (one active mute / ban per player). Call inside a
     * transaction; call {@link #afterIssue} after it commits.
     */
    Result<Punishment> insert(Connection c, Actor actor, PunishType type, Target target, long durationMs, String reason,
                              Source source, Long caseId, String offense, Long reviewDeadline, Long custodyId,
                              boolean audit) throws SQLException {
        long now = core.clock.now();
        if (type == PunishType.MUTE || type == PunishType.BAN || type == PunishType.IPBAN) {
            for (Punishment active : Repo.Punishments.activeFor(c, target.uuid())) {
                boolean banLike = (type == PunishType.BAN || type == PunishType.IPBAN)
                        && (active.type() == PunishType.BAN || active.type() == PunishType.IPBAN);
                if (active.type() == type || banLike) {
                    return Result.fail(Code.DUPLICATE, target.name() + " already has an active " + active.type().display().toLowerCase()
                            + " (#" + active.id() + "). Revoke it first if it should be replaced.");
                }
            }
        }
        String ip = null;
        if (type == PunishType.IPBAN) {
            Profile p = Repo.Profiles.get(c, target.uuid());
            ip = p == null ? null : p.lastIp();
            if (ip == null || ip.isBlank()) {
                return Result.fail(Code.INVALID, "No IP address is recorded for " + target.name() + " yet, so an IP ban is not possible.");
            }
        }
        Long expires = type.canBeTimed() && durationMs != Durations.PERMANENT ? now + durationMs : null;
        PunishStatus status = type.instant() ? PunishStatus.COMPLETED : PunishStatus.ACTIVE;
        boolean online = type != PunishType.WARN; // warnings count as delivered only once the player has actually seen them
        long id = Repo.Punishments.insert(c, type, status, target.uuid(), target.name(), ip, reason, actor.dbUuid(), actor.name(),
                now, expires, caseId, source, offense, online, reviewDeadline, custodyId, opId());
        if (type == PunishType.WARN) {
            Settings.WarnRepeat rep = core.settings.warnings.repeat();
            long n = Repo.Punishments.countWarningsSince(c, target.uuid(), now - rep.window(), rep.sameCategoryOnly() && offense != null ? offense : null); // quick warnings carry no offense category: count them all
            Map<String, String> pl = new LinkedHashMap<>();
            pl.put("reason", reason);
            pl.put("issuer", actor.name());
            pl.put("id", String.valueOf(id));
            pl.put("count", String.valueOf(n));
            core.notifications.enqueue(c, "warn:" + id, target.uuid(), NotificationService.WARNING, "punishment", id, pl);
        }
        if (audit) {
            boolean elevated = type == PunishType.IPBAN || (type.canBeTimed() && durationMs == Durations.PERMANENT) || !source.quickLike();
            audit(c, actor, "punishment." + type.node(), target.uuid(), caseId,
                    "id=" + id + " source=" + source + " duration=" + (type.canBeTimed() ? Durations.format(durationMs) : "-")
                            + " reason=" + clip(reason, 120), elevated);
        }
        return Result.ok(Repo.Punishments.get(c, id));
    }

    /** Apply a committed punishment: update the cache and act on the online player. */
    CompletableFuture<Void> afterIssue(Punishment p, boolean late) {
        cache.add(p);
        return core.main.run(() -> {
            switch (p.type()) {
                case BAN, IPBAN, KICK -> core.enforcement.disconnect(p.targetUuid(), p);
                case MUTE -> core.enforcement.noticeMute(p.targetUuid(), p);
                case WARN -> core.notifications.deliverPending(p.targetUuid());
                default -> { }
            }
        }).exceptionally(t -> {
            core.log.warning("Could not apply punishment #" + p.id() + " to the online player: " + t.getMessage());
            return null;
        });
    }

    // ------------------------------------------------------------------ revoke
    public CompletableFuture<Result<Punishment>> revoke(Actor actor, long id, String reason) {
        return revoke(actor, id, reason, false);
    }

    /** @param authorised true when the caller already verified a permission that allows reversing this punishment (owner review). */
    CompletableFuture<Result<Punishment>> revoke(Actor actor, long id, String reason, boolean authorised) {
        String why = Text.clean(reason);
        if (why.length() < core.settings.cases.minReason()) {
            return done(Result.fail(Code.INVALID, "A reason of at least " + core.settings.cases.minReason() + " characters is required."));
        }
        return db(c -> revokeTx(c, actor, id, why, authorised)).thenCompose(res -> afterRevoke(actor, res, why));
    }

    /** The database half of a revocation; runs inside the caller's transaction (so an appeal decision can use it atomically). */
    Result<Punishment> revokeTx(Connection c, Actor actor, long id, String why, boolean authorised) throws SQLException {
        Punishment p = Repo.Punishments.get(c, id);
        if (p == null) return Result.fail(Code.NOT_FOUND, "Punishment #" + id + " does not exist.");
        if (p.status() != PunishStatus.ACTIVE && p.status() != PunishStatus.COMPLETED) {
            return Result.fail(Code.STATE, "Punishment #" + id + " is already " + p.status().name().toLowerCase() + ".");
        }
        boolean own = actor.dbUuid() != null && actor.dbUuid().equals(p.issuerUuid()) && p.source().quickLike();
        if (!(authorised || core.auth.has(actor, Perms.REVOKE_ANY) || (own && core.auth.has(actor, Perms.REVOKE)))) {
            return Result.fail(Code.NO_PERMISSION, "You do not have permission to revoke that punishment.");
        }
        if (!Repo.Punishments.end(c, id, PunishStatus.REVOKED, actor.name(), why, core.clock.now())) {
            return Result.fail(Code.CONFLICT, "That punishment changed while you were revoking it.");
        }
        if (p.caseId() != null) {
            Repo.Events.add(c, p.caseId(), core.clock.now(), actor.dbUuid(), actor.name(), "PUNISHMENT_REVOKED", null, null,
                    "#" + id + " " + p.type().display() + ": " + clip(why, 150));
        }
        audit(c, actor, "punishment.revoke", p.targetUuid(), p.caseId(), "id=" + id + " type=" + p.type() + " reason=" + clip(why, 120), !own);
        return Result.ok(Repo.Punishments.get(c, id));
    }

    /** Everything that follows a committed revocation: cache, unmute notice, jail release, Discord. */
    CompletableFuture<Result<Punishment>> afterRevoke(Actor actor, Result<Punishment> res, String why) {
        if (!res.isOk()) return done(res);
        Punishment p = res.value();
        cache.remove(p);
        core.main.run(() -> {
            if (p.type() == PunishType.MUTE) core.enforcement.noticeUnmute(p.targetUuid());
        });
        CompletableFuture<Void> jail = CompletableFuture.completedFuture(null);
        String[] releaseNote = new String[1];
        if (p.type() == PunishType.JAIL && p.custodyId() != null && custody != null) {
            jail = custody.releaseForPunishment(actor, p, "Punishment #" + p.id() + " revoked: " + clip(why, 100)).thenAccept(r -> {
                if (!r.isOk()) core.log.warning("Punishment #" + p.id() + " revoked but jail release failed: " + r.message());
            });
        } else if (p.type() == PunishType.JAIL && jails != null && JailService.live(p.jailState())) {
            jail = jails.releaseForRevoked(actor, p, "Punishment #" + p.id() + " revoked: " + clip(why, 100)).thenAccept(r -> {
                if (!r.isOk()) {
                    core.log.warning("Punishment #" + p.id() + " revoked but jail release is not complete: " + r.message());
                    releaseNote[0] = "Revoked, but " + p.targetName() + " has not been released from jail yet: " + r.message();
                }
            });
        }
        core.emit(DomainEvent.of(DomainEvent.Type.REVERSAL).caseId(p.caseId()).player(p.targetName()).actor(actor.name())
                .key("revoked:" + p.id()).summary(p.type().display() + " #" + p.id() + " revoked: " + clip(why, 150)).field("Reason", clip(why, 200)).build());
        return jail.thenApply(v -> releaseNote[0] == null ? res : Result.okWith(res.value(), releaseNote[0]));
    }

    /**
     * Change the expiry (and optionally the reason) of an ACTIVE punishment inside the caller's transaction. The first
     * original expiry is kept for the record. Call {@link #afterModify} once the transaction has committed.
     *
     * @param newExpires absolute expiry, or null for permanent
     */
    Result<Punishment> modifyTx(Connection c, Actor actor, long id, Long newExpires, String newReason) throws SQLException {
        Punishment p = Repo.Punishments.get(c, id);
        if (p == null) return Result.fail(Code.NOT_FOUND, "Punishment #" + id + " does not exist.");
        if (p.status() != PunishStatus.ACTIVE) return Result.fail(Code.STATE, "Punishment #" + id + " is no longer active, so it cannot be modified.");
        long now = core.clock.now();
        if (newExpires != null && newExpires <= now) return Result.fail(Code.INVALID, "That duration would already have elapsed. Revoke the punishment instead.");
        if (!Repo.Punishments.modify(c, id, newExpires, newReason, actor.name(), now)) {
            return Result.fail(Code.CONFLICT, "That punishment changed while you were modifying it.");
        }
        if (p.caseId() != null) {
            Repo.Events.add(c, p.caseId(), now, actor.dbUuid(), actor.name(), "PUNISHMENT_MODIFIED", null, null,
                    "#" + id + " " + (newExpires == null ? "permanent" : Durations.format(newExpires - p.issuedAt())));
        }
        audit(c, actor, "punishment.modify", p.targetUuid(), p.caseId(), "id=" + id + " expires=" + (newExpires == null ? "permanent" : Durations.format(newExpires - p.issuedAt())), true);
        return Result.ok(Repo.Punishments.get(c, id));
    }

    void afterModify(Punishment p) {
        cache.add(p); // replaces the cached copy (new expiry/reason)
    }

    // ------------------------------------------------------------------ expiry & delivery
    /** Move every elapsed timed punishment to EXPIRED and drop it from the cache. */
    public CompletableFuture<Result<Integer>> expireDue() {
        return db(c -> {
            long now = core.clock.now();
            List<Punishment> due = Repo.Punishments.dueForExpiry(c, now);
            for (Punishment p : due) {
                if (p.type() == PunishType.JAIL) continue; // JailReloaded's own timer ends jail sentences
                Repo.Punishments.end(c, p.id(), PunishStatus.EXPIRED, "System", "Expired", now);
            }
            return Result.ok(due);
        }).thenApply(res -> {
            if (!res.isOk()) return res.<Integer>cast();
            int n = 0;
            for (Punishment p : res.value()) {
                if (p.type() == PunishType.JAIL) continue;
                cache.remove(p);
                n++;
                if (p.type() == PunishType.MUTE) core.main.run(() -> core.enforcement.noticeUnmute(p.targetUuid()));
            }
            return Result.ok(n);
        });
    }

    /** Deliver queued notifications (warnings, defendant messages) that were waiting for this player to come online. */
    public CompletableFuture<Void> deliverPending(UUID uuid) {
        return core.notifications.deliverPending(uuid); // warnings, defendant and staff notices (punishments.warning-delivery is superseded by warnings.notifications.*)
    }

    // ------------------------------------------------------------------ warning history
    public record WarningEntry(Punishment warning, long followUps, boolean delivered) {}

    public record WarningHistory(List<WarningEntry> entries, long recentCount, int threshold, long windowMs, boolean repeatOffender) {}

    /** Warnings on a player's record, newest first, with the repeat indicator and whether any later action was taken. */
    public CompletableFuture<Result<WarningHistory>> warningHistory(Actor actor, UUID uuid, int limit) {
        if (!core.auth.has(actor, Perms.HISTORY_VIEW)) return done(Result.fail(Code.NO_PERMISSION, "You do not have permission to view punishment history."));
        return db(c -> {
            Settings.WarnRepeat rep = core.settings.warnings.repeat();
            List<WarningEntry> out = new java.util.ArrayList<>();
            for (Punishment w : Repo.Punishments.warningsFor(c, uuid, limit)) {
                out.add(new WarningEntry(w, Repo.Punishments.followUpsAfter(c, uuid, w.issuedAt()), w.delivered()));
            }
            long recent = Repo.Punishments.countWarningsSince(c, uuid, core.clock.now() - rep.window(), null);
            return Result.ok(new WarningHistory(out, recent, rep.threshold(), rep.window(), rep.enabled() && recent >= rep.threshold()));
        });
    }

    // ------------------------------------------------------------------ lookups
    public Optional<Punishment> banFor(UUID uuid, String ip) {
        long now = core.clock.now();
        Optional<Punishment> b = cache.ban(uuid, now);
        return b.isPresent() ? b : cache.ipBan(ip, now);
    }

    public Optional<Punishment> muteFor(UUID uuid) {
        return cache.mute(uuid, core.clock.now());
    }

    public CompletableFuture<Result<List<Punishment>>> activeFor(UUID uuid) {
        return db(c -> Result.ok(Repo.Punishments.activeFor(c, uuid)));
    }

    public CompletableFuture<Result<List<Punishment>>> history(Actor actor, UUID uuid, int limit, int offset) {
        if (!core.auth.has(actor, Perms.HISTORY_VIEW)) return done(Result.fail(Code.NO_PERMISSION, "You do not have permission to view punishment history."));
        return db(c -> Result.ok(Repo.Punishments.forTarget(c, uuid, limit, offset)));
    }

    public CompletableFuture<Result<Punishment>> get(Actor actor, long id) {
        if (!core.auth.has(actor, Perms.HISTORY_VIEW)) return done(Result.fail(Code.NO_PERMISSION, "You do not have permission to view punishment history."));
        return db(c -> {
            Punishment p = Repo.Punishments.get(c, id);
            return p == null ? Result.<Punishment>fail(Code.NOT_FOUND, "Punishment #" + id + " does not exist.") : Result.ok(p);
        });
    }

    /** Mark an expired/completed state for cases where JailReloaded ended a jail sentence. */
    CompletableFuture<Void> markServed(long punishmentId) {
        return core.db.tx(c -> {
            Repo.Punishments.end(c, punishmentId, PunishStatus.SERVED, "System", "Sentence served", core.clock.now());
            return null;
        }).handle((v, ex) -> null);
    }
}
