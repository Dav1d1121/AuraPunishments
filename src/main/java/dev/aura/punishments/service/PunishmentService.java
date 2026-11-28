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
    private volatile long lastLoginWarning;
    private CustodyService custody;
    private JailService jails;
    private CaseService cases;

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

    public void bind(CaseService cases) {
        this.cases = cases;
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

    /** Re-read every active punishment into the index in one swap (a change another server of the network made). */
    public CompletableFuture<Result<Integer>> reloadCache() {
        return db(c -> {
            List<Punishment> active = Repo.Punishments.active(c);
            cache.replaceAll(active);
            return Result.ok(active.size());
        });
    }

    /**
     * The login decision. On a standalone server it is a memory lookup. On a network it asks the database - the one authority every
     * server shares - so a ban issued a moment ago on another backend is honoured on the very next connection, without waiting for the change
     * feed. If the database cannot answer, the in-memory index (loaded from it) decides: a known ban never silently disappears.
     * Called on the asynchronous login thread, never on the main thread.
     */
    public Optional<Punishment> banForLogin(UUID uuid, String ip) {
        if (!core.db.shared()) return banFor(uuid, ip);
        try {
            long now = core.clock.now();
            return core.db.txSync(c -> {
                List<Punishment> mine = Repo.Punishments.activeFor(c, uuid);
                cache.syncPlayer(uuid, mine);
                for (Punishment p : mine) {
                    if ((p.type() == PunishType.BAN || p.type() == PunishType.IPBAN) && p.activeAt(now)) return Optional.of(p);
                }
                if (ip != null && !ip.isBlank() && !core.settings.ipBans.protectedAddress(ip)) {
                    Punishment byIp = Repo.Punishments.activeIpBan(c, ip);
                    if (byIp != null && byIp.activeAt(now)) {
                        cache.add(byIp);
                        return Optional.of(byIp);
                    }
                }
                return Optional.<Punishment>empty();
            });
        } catch (SQLException | RuntimeException e) {
            long t = System.currentTimeMillis();
            if (t - lastLoginWarning > 60_000L) {
                lastLoginWarning = t;
                core.log.warning("Login checks cannot reach the shared database (" + e.getMessage() + "); the last known bans are enforced until it answers.");
            }
            return banFor(uuid, ip);
        }
    }

    /** Bring one player's cached mute/ban in line with the database (when they connect to this server). */
    public CompletableFuture<Void> refreshPlayer(UUID uuid) {
        if (!core.db.shared()) return CompletableFuture.completedFuture(null);
        return core.db.tx(c -> Repo.Punishments.activeFor(c, uuid)).thenAccept(list -> cache.syncPlayer(uuid, list)).exceptionally(t -> null);
    }

    // ------------------------------------------------------------------ quick pathway
    public CompletableFuture<Result<Punishment>> issueQuick(Actor actor, Request r) {
        return issue(actor, r, null);
    }

    /**
     * Case workflow (judicial system off, {@code casework.enabled}): punish the accused FROM a case. The punishment is final and
     * immediate (no court), is linked to the case, and the case stays open for notes, more evidence and appeals until staff close it.
     * Everything {@link #issueQuick} checks applies (permission, hierarchy, duration limits, evidence rules, owner review), plus the
     * case's own rules: the accused must be the target, the offense must allow the punishment and its cap, the case must be open.
     */
    public CompletableFuture<Result<Punishment>> issueForCase(Actor actor, long caseId, Request r) {
        if (!core.mode.casework()) {
            return done(Result.fail(Code.UNAVAILABLE, core.mode.enabled()
                    ? "With the judicial system on, punishments from a case are sentences (or direct punishments) - use the court tools."
                    : "Punishing from a case needs the case workflow (casework.enabled: true while judicial.enabled is false)."));
        }
        return issue(actor, r, caseId);
    }

    /** A case opened and its punishment issued in ONE transaction (case workflow): either both exist afterwards or neither does. */
    public record OpenedCase(Case opened, Punishment punishment) {}

    /** The case to open inside the punishment's transaction. */
    private record NewCase(CaseService.CreateRequest request, CaseService.Prepared prepared) {}

    /**
     * Case workflow: open a case (offense, severity, description, evidence) AND punish from it in one step - what the Create Case screen's
     * Submit does when a punishment was chosen. Every rule of {@link #openCase} and {@link #issueForCase} applies; a refusal of either half
     * (an active ban already exists, JailReloaded missing, a limit, an offense rule ...) leaves nothing behind, so the staff member can
     * correct the draft and submit again.
     */
    public CompletableFuture<Result<OpenedCase>> openCaseAndPunish(Actor actor, CaseService.CreateRequest cr, Request r) {
        if (!core.mode.casework() || cases == null) {
            return done(Result.fail(Code.UNAVAILABLE, "Opening a case together with its punishment needs the case workflow (casework.enabled: true while judicial.enabled is false)."));
        }
        if (!core.auth.has(actor, Perms.CASE_CREATE)) {
            return done(Result.fail(Code.NO_PERMISSION, "You need permission to create cases to open a case with a punishment (reports cannot punish)."));
        }
        if (!cr.target().uuid().equals(r.target().uuid())) {
            return done(Result.fail(Code.INVALID, "The punishment must be for the player the case is about."));
        }
        Result<CaseService.Prepared> prep = cases.prepare(actor, cr);
        if (!prep.isOk()) return done(prep.cast());
        return issue(actor, r, null, new NewCase(cr, prep.value())).thenCompose(res -> {
            if (!res.isOk()) return done(res.<OpenedCase>cast());
            Punishment pun = res.value();
            return core.db.tx(c -> Repo.Cases.get(c, pun.caseId())).thenApply(cs -> {
                cases.emitCreated(actor, cs, prep.value().offense());
                OpenedCase both = new OpenedCase(cs, pun);
                return res.message() == null || res.message().isBlank() ? Result.ok(both) : Result.okWith(both, res.message());
            });
        });
    }

    private CompletableFuture<Result<Punishment>> issue(Actor actor, Request r, Long linkedCaseId) {
        return issue(actor, r, linkedCaseId, null);
    }

    private CompletableFuture<Result<Punishment>> issue(Actor actor, Request r, Long linkedCaseId, NewCase newCase) {
        Settings s = core.settings;
        PunishType type = r.type();
        final boolean judicial = core.mode.enabled();
        final boolean linked = linkedCaseId != null || newCase != null;
        if (!linked && core.mode.casework() && s.casework.requireCaseFor().contains(type)) {
            return done(Result.fail(Code.INVALID, type.display() + "s must be issued from a case on this server "
                    + "(casework.require-case-for): open a case with the evidence, then punish from it."));
        }
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
        if (!linked && s.quick.requireEvidence().contains(type) && evidence.isEmpty()) {
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
                ? core.main.call(() -> core.enforcement.isOnline(r.target().uuid())).thenCompose(local -> local || core.network == null
                        ? CompletableFuture.completedFuture(local) : core.network.isOnlineElsewhere(r.target().uuid()))
                : CompletableFuture.completedFuture(true);
        return online.thenCompose(isOn -> {
            if (!isOn) return done(Result.<Void>fail(Code.INVALID, r.target().name() + " is not online, so there is nobody to kick."));
            return checkTarget(actor, r.target().uuid());
        }).thenCompose(tc -> {
            if (!tc.isOk()) return done(tc.<Punishment>cast());
            return db(c -> {
                Long lid = linkedCaseId;
                if (newCase != null) {
                    Result<Case> opened = cases.insertTx(c, actor, newCase.request(), newCase.prepared());
                    if (!opened.isOk()) return opened.<Punishment>cast();
                    lid = opened.value().id();
                }
                Long caseId = lid;
                String offenseKey = null;
                long now0 = core.clock.now();
                if (lid != null) {
                    Case cs = Repo.Cases.get(c, lid);
                    if (cs == null) return Result.<Punishment>fail(Code.NOT_FOUND, "Case " + lid + " does not exist.");
                    if (!core.auth.canViewCase(actor, cs)) return Result.<Punishment>fail(Code.NO_PERMISSION, "You do not have permission to view that case.");
                    if (!cs.status().isOpenForWork()) {
                        return Result.<Punishment>fail(Code.STATE, cs.ref() + " is " + cs.status().display().toLowerCase() + ". Reopen it before punishing from it.");
                    }
                    if (!cs.accusedUuid().equals(r.target().uuid())) {
                        return Result.<Punishment>fail(Code.INVALID, cs.ref() + " is about " + cs.accusedName() + ", not " + r.target().name() + ".");
                    }
                    Optional<dev.aura.punishments.config.Offense> off = core.offenses.get(cs.offenseKey());
                    offenseKey = cs.offenseKey();
                    if (off.isPresent() && type != PunishType.KICK) { // a kick ends a session; it is not a sentence the offense list governs
                        dev.aura.punishments.config.Offense o = off.get();
                        if (!o.allows(type)) {
                            return Result.<Punishment>fail(Code.INVALID, o.display() + " cannot result in a " + type.display().toLowerCase() + " (see offenses.yml).");
                        }
                        if (type.canBeTimed() && dur2 != Durations.PERMANENT && o.exceedsCap(dur2)) {
                            return Result.<Punishment>fail(Code.LIMIT, "That exceeds the maximum for " + o.display() + " (" + Durations.format(o.maxDuration()) + ").");
                        }
                        if (type.canBeTimed() && dur2 == Durations.PERMANENT && o.maxDuration() != Durations.PERMANENT) {
                            return Result.<Punishment>fail(Code.LIMIT, o.display() + " cannot be punished permanently (maximum " + Durations.format(o.maxDuration()) + ").");
                        }
                    }
                    long onCase = Repo.Evidences.countActive(c, lid);
                    if (s.quick.requireEvidence().contains(type) && onCase + evidence.size() == 0) {
                        return Result.<Punishment>fail(Code.EVIDENCE, type.display() + "s require at least one evidence reference - add evidence to " + cs.ref() + " first.");
                    }
                    if (onCase + evidence.size() > s.cases.maxEvidence()) {
                        return Result.<Punishment>fail(Code.LIMIT, cs.ref() + " already has the maximum number of evidence items.");
                    }
                }
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
                if (type == PunishType.JAIL) {
                    Punishment held = Repo.Punishments.liveStandaloneJailFor(c, r.target().uuid());
                    if (held != null) return Result.<Punishment>fail(Code.DUPLICATE, r.target().name() + " is already serving jail punishment #" + held.id() + ". Release them first if it should be replaced.");
                    var cu = Repo.Custodies.liveForPlayer(c, r.target().uuid());
                    if (cu != null) return Result.<Punishment>fail(Code.DUPLICATE, r.target().name() + " is held in pretrial custody (case " + CustodyService.tag(cu.caseId()) + ").");
                }
                Result<Punishment> ins = insert(c, actor, type, r.target(), dur2, cleanReason, judicial ? Source.QUICK : Source.STANDARD, caseId, offenseKey, null, null, true);
                if (!ins.isOk()) return ins;
                Punishment pun = ins.value();
                if (lid != null) {
                    // Everything the issuer supplied belongs to the case; the punishment is linked to it and the case stays open.
                    for (EvidenceValidator.Input in : evidence) {
                        Repo.Evidences.add(c, lid, in.type(), in.reference(), in.description(), actor.dbUuid(), actor.name(), now0, null);
                    }
                    if (note != null) Repo.Notes.add(c, lid, null, actor.dbUuid(), actor.name(), note, false, now0);
                    Repo.Events.add(c, lid, now0, actor.dbUuid(), actor.name(), "PUNISHMENT_ISSUED", null, null,
                            "#" + pun.id() + " " + type.display() + (type.canBeTimed() ? " (" + (pun.expiresAt() == null ? "permanent" : Durations.format(dur2)) + ")" : "")
                            + ": " + clip(cleanReason, 150));
                } else if (!judicial) {
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
                    if (placed.isOk()) emitIssued(placed.value(), evidence.size());
                    return placed;
                });
            }
            return afterIssue(res.value(), false).thenApply(v -> {
                emitIssued(res.value(), evidence.size());
                return res;
            });
        });
    }

    /** Discord/staff events for a quick punishment, plus the review request when one was created. */
    private void emitIssued(Punishment p, int evidenceCount) {
        boolean warn = p.type() == PunishType.WARN;
        DomainEvent.Builder b = DomainEvent.of(warn ? DomainEvent.Type.WARNING : DomainEvent.Type.QUICK_PUNISHMENT)
                .key((warn ? "warning:" : "quick:") + p.id()).caseId(p.caseId()).player(p.targetName()).actor(p.issuerName())
                .summary(p.classification() + ": " + p.type().display() + (p.type().canBeTimed() ? " (" + (p.expiresAt() == null ? "permanent" : Durations.format(p.expiresAt() - p.issuedAt())) + ")" : ""))
                .field("Classification", p.classification()).field("Reason", clip(p.reason(), 300)).punishment(p, "issued", evidenceCount);
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
        // across servers: take the player's row lock first, so two servers punishing the same player at the same moment take turns
        Repo.Profiles.lockPlayer(c, target.uuid(), target.name(), now);
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
            Settings.IpBans guard = core.settings.ipBans;
            if (guard.protectedAddress(ip)) {
                return Result.fail(Code.INVALID, "The recorded address of " + target.name() + " (" + ip + ") is not a public player address (a proxy, a private network or an excluded address), so an IP ban "
                        + "would lock out unrelated players. If you are behind a proxy, check its IP forwarding (docs/NETWORK_DEPLOYMENT.md); ban the account instead.");
            }
            if (guard.maxAccounts() > 0) {
                long accounts = Repo.Profiles.countByIp(c, ip, now - guard.windowMs());
                if (accounts > guard.maxAccounts()) {
                    return Result.fail(Code.LIMIT, accounts + " different accounts connected from " + ip + " in the last " + guard.windowMs() / 86_400_000L + " days (limit "
                            + guard.maxAccounts() + "): it looks like a shared or proxy address, so an IP ban is refused. Ban the accounts instead.");
                }
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
                .key("revoked:" + p.id()).summary(p.type().display() + " #" + p.id() + " revoked: " + clip(why, 150)).field("Revoked because", clip(why, 200))
                .punishment(p, "revoked", 0).build());
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
        if (p.type() == PunishType.JAIL && (newExpires == null || !JailService.live(p.jailState()) || RELEASING_OR_PLACING.contains(p.jailState()))) {
            return Result.fail(Code.INVALID, newExpires == null ? "A jail punishment cannot be made permanent by a modification. Issue a new one instead."
                    : "That jail punishment is not being held right now, so its time cannot be changed.");
        }
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

    private static final java.util.Set<String> RELEASING_OR_PLACING = java.util.Set.of(JailService.PLACING, JailService.RELEASING);

    /**
     * After a committed modification: refresh the enforcement cache and, for a jail punishment, move JailReloaded's remaining time by
     * the same amount (JailReloaded keeps the clock). The result is a failure when JailReloaded could not be updated, so the caller can tell
     * the staff member that the record and the jail now disagree.
     *
     * @param before the punishment as it was before the modification (may be null when unknown)
     */
    CompletableFuture<Result<Void>> afterModify(Actor actor, Punishment before, Punishment after) {
        cache.add(after);
        if (after.type() == PunishType.JAIL && jails != null && JailService.live(after.jailState()) && before != null) {
            return jails.applyTimeChange(actor, before, after);
        }
        return done(Result.ok());
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
                core.emit(DomainEvent.of(DomainEvent.Type.PUNISHMENT_EXPIRED).key("expired:" + p.id()).player(p.targetName()).actor("System")
                        .summary(p.type().display() + " #" + p.id() + " ended (its time ran out)").punishment(p, "expired", 0).build());
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
        if (b.isPresent()) return b;
        return core.settings.ipBans.protectedAddress(ip) ? Optional.empty() : cache.ipBan(ip, now);
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
