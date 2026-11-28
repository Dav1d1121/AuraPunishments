package dev.aura.punishments.service;

import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Evidence;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Records.Review;
import dev.aura.punishments.model.Records.ReviewEvent;
import dev.aura.punishments.model.Simple.CaseKind;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.model.Simple.ReviewStatus;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import dev.aura.punishments.util.Text;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Owner review of quick punishments.
 *
 * <p>A quick punishment is enforced immediately and exactly as before. If it is of a type that needs review
 * ({@code review.required-types}) a persistent review record is created in the same transaction. Reviewers can
 * inspect it, annotate it, ask for an explanation, and uphold, modify, reverse or refer it. A review that nobody
 * has looked at is only ever reminded about: nothing is reversed or changed because a deadline passed, and a
 * timed punishment still expires normally. Routine warnings and kicks never create a review.
 */
public final class ReviewService extends Svc {
    public static final String STAFF = "STAFF";

    private final PunishmentService punishments;

    public record Detail(Review review, Punishment punishment, List<ReviewEvent> events, List<Evidence> evidence,
                         boolean canSeePrivate, boolean evidenceAttached) {}

    public ReviewService(Core core, PunishmentService punishments) {
        super(core);
        this.punishments = punishments;
    }

    // ------------------------------------------------------------------ creation (called inside the issuing transaction)
    /** @return the review id, or -1 when this punishment does not need one. */
    static long createIfRequired(Core core, Connection c, Actor actor, Punishment p, Long caseId) throws SQLException {
        Settings.Review cfg = core.settings.review;
        boolean emergency = p.source() == dev.aura.punishments.model.Simple.Source.EMERGENCY && !core.mode.enabled() && cfg.includeEmergency();
        if (!cfg.enabled() || !(p.source().quickLike() || emergency) || !(emergency || cfg.requiredTypes().contains(p.type()))) return -1;
        if (!emergency && cfg.permanentOnlyTypes().contains(p.type()) && p.expiresAt() != null) return -1; // e.g. only permanent bans are reviewed
        long duration = p.expiresAt() == null ? Long.MAX_VALUE : p.expiresAt() - p.issuedAt();
        if (!emergency && duration < cfg.minDuration()) return -1;
        if (cfg.exemptLevel() > 0 && actor.perms().level() >= cfg.exemptLevel()) return -1;
        long now = core.clock.now();
        long id = Repo.Reviews.insert(c, p.id(), caseId, p.targetUuid(), p.targetName(), p.issuerUuid(), p.issuerName(), p.type(),
                p.expiresAt() == null ? null : duration, p.reason(), p.issuedAt(), ReviewStatus.PENDING_REVIEW,
                now + cfg.evidenceDeadline(), emergency && p.reviewDeadline() != null ? p.reviewDeadline() : now + cfg.reviewDeadline(), now);
        Repo.Reviews.addEvent(c, id, now, actor.name(), "CREATED", p.classification() + " issued; owner review requested");
        return id;
    }

    static void emitCreated(Core core, Review rv, Punishment p) {
        core.emit(DomainEvent.of(DomainEvent.Type.REVIEW_CREATED).key("review-created:" + rv.id()).caseId(rv.caseId()).player(rv.targetName())
                .actor(rv.issuerName()).summary(rv.ref() + ": " + p.type().display() + " awaiting owner review")
                .field("Classification", p.classification()).field("Reason", Text.truncate(rv.reason(), 300))
                .field("Evidence due", rv.evidenceDue() == null ? "" : "<t:" + rv.evidenceDue() / 1000 + ":R>")
                .field("Review due", rv.reviewDue() == null ? "" : "<t:" + rv.reviewDue() / 1000 + ":R>").build());
    }

    // ------------------------------------------------------------------ reading
    private boolean isOwn(Actor a, Review r) {
        return a.dbUuid() != null && a.dbUuid().equals(r.issuerUuid());
    }

    private boolean canView(Actor a, Review r) {
        return core.auth.has(a, Perms.REVIEW_VIEW) || isOwn(a, r);
    }

    public CompletableFuture<Result<CaseService.Page<Review>>> queue(Actor actor, List<ReviewStatus> statuses, int page, int pageSize) {
        Result<Void> perm = core.auth.require(actor, Perms.REVIEW_VIEW, "view the review queue");
        if (!perm.isOk()) return done(perm.cast());
        return db(c -> Result.ok(new CaseService.Page<>(Repo.Reviews.byStatuses(c, statuses, null, pageSize, page * pageSize),
                Repo.Reviews.countByStatuses(c, statuses, null), page, pageSize)));
    }

    /** The caller's own reviews (no permission needed: issuers can always follow up on their own punishments). */
    public CompletableFuture<Result<CaseService.Page<Review>>> mine(Actor actor, int page, int pageSize) {
        if (actor.dbUuid() == null) return done(Result.ok(new CaseService.Page<>(List.of(), 0, page, pageSize)));
        List<ReviewStatus> all = List.of(ReviewStatus.values());
        return db(c -> Result.ok(new CaseService.Page<>(Repo.Reviews.byStatuses(c, all, actor.dbUuid(), pageSize, page * pageSize),
                Repo.Reviews.countByStatuses(c, all, actor.dbUuid()), page, pageSize)));
    }

    public CompletableFuture<Result<Long>> countOpen(Actor actor) {
        return db(c -> Result.ok(Repo.Reviews.countByStatuses(c, List.of(ReviewStatus.PENDING_REVIEW, ReviewStatus.UNDER_REVIEW,
                ReviewStatus.FURTHER_INVESTIGATION), null)));
    }

    public CompletableFuture<Result<List<Review>>> forTarget(Actor actor, UUID target, int limit) {
        Result<Void> perm = core.auth.require(actor, Perms.REVIEW_VIEW, "view reviews");
        if (!perm.isOk()) return done(perm.cast());
        return db(c -> Result.ok(Repo.Reviews.forTarget(c, target, limit)));
    }

    public CompletableFuture<Result<Review>> byPunishment(Actor actor, long punishmentId) {
        return db(c -> {
            Review r = Repo.Reviews.byPunishment(c, punishmentId);
            if (r == null) return Result.<Review>fail(Code.NOT_FOUND, "That punishment has no review record.");
            return canView(actor, r) ? Result.ok(r) : Result.<Review>fail(Code.NO_PERMISSION, "You do not have permission to view reviews.");
        });
    }

    public CompletableFuture<Result<Detail>> detail(Actor actor, long id) {
        return db(c -> {
            Review r = Repo.Reviews.get(c, id);
            if (r == null) return Result.<Detail>fail(Code.NOT_FOUND, "Review " + id + " does not exist.");
            if (!canView(actor, r)) return Result.<Detail>fail(Code.NO_PERMISSION, "You do not have permission to view reviews.");
            boolean priv = core.auth.has(actor, Perms.REVIEW_SENSITIVE);
            List<ReviewEvent> events = new ArrayList<>();
            for (ReviewEvent e : Repo.Reviews.events(c, id)) if (priv || !e.type().equals("NOTE")) events.add(e);
            List<Evidence> ev = new ArrayList<>();
            if (core.auth.has(actor, Perms.EVIDENCE_VIEW) || isOwn(actor, r)) {
                if (r.punishmentId() != null) ev.addAll(Repo.Evidences.forPunishment(c, r.punishmentId(), false)); // evidence attached to the punishment itself
                if (r.caseId() != null) ev.addAll(Repo.Evidences.forCase(c, r.caseId(), false));
            }
            return Result.ok(new Detail(r, r.punishmentId() == null ? null : Repo.Punishments.get(c, r.punishmentId()), events, ev, priv,
                    hasEvidence(c, r)));
        });
    }

    /** Evidence counts wherever it lives: on the punishment itself or on its case. */
    private static boolean hasEvidence(Connection c, Review r) throws SQLException {
        return (r.punishmentId() != null && Repo.Evidences.countActiveForPunishment(c, r.punishmentId()) > 0)
                || (r.caseId() != null && Repo.Evidences.countActive(c, r.caseId()) > 0);
    }

    // ------------------------------------------------------------------ workflow
    private Result<Review> load(Connection c, long id) throws SQLException {
        Review r = Repo.Reviews.get(c, id);
        return r == null ? Result.<Review>fail(Code.NOT_FOUND, "Review " + id + " does not exist.") : Result.ok(r);
    }

    private static Result<Review> open(Review r) {
        if (r.migrated()) return Result.fail(Code.STATE, r.ref() + " was imported from an earlier version and cannot be decided.");
        if (!r.status().open()) return Result.fail(Code.STATE, r.ref() + " is already " + r.status().display().toLowerCase() + ".");
        return Result.ok(r);
    }

    /** Nobody may take or decide a review of a punishment they issued themselves (console/system excepted). */
    private Result<Review> notOwn(Actor actor, Review r) {
        if (isOwn(actor, r) && !actor.isPrivileged()) return Result.fail(Code.NO_PERMISSION, "You cannot review a punishment you issued yourself; another reviewer must decide it.");
        return Result.ok(r);
    }

    public CompletableFuture<Result<Review>> take(Actor actor, long id, long expectedVersion) {
        Result<Void> perm = core.auth.require(actor, Perms.REVIEW_REVIEW, "review punishments");
        if (!perm.isOk()) return done(perm.cast());
        return db(c -> {
            Result<Review> lr = load(c, id);
            if (!lr.isOk()) return lr;
            Result<Review> op = open(lr.value());
            if (!op.isOk()) return op;
            Result<Review> own = notOwn(actor, lr.value());
            if (!own.isOk()) return own;
            long now = core.clock.now();
            if (!Repo.Reviews.setStatus(c, id, expectedVersion, ReviewStatus.UNDER_REVIEW, actor.name(), null, false, now)) return stale(id);
            Repo.Reviews.addEvent(c, id, now, actor.name(), "TAKEN", "Review started");
            audit(c, actor, "review.take", lr.value().targetUuid(), lr.value().caseId(), "review=" + id, false);
            return Result.ok(Repo.Reviews.get(c, id));
        });
    }

    private static Result<Review> stale(long id) {
        return Result.fail(Code.CONFLICT, "Review RV-" + String.format("%04d", id) + " was changed by someone else. Reopen it and try again.");
    }

    /** Private reviewer note (shown only to holders of review.evidence.sensitive). Never sent to Discord. */
    public CompletableFuture<Result<Void>> addNote(Actor actor, long id, String text) {
        Result<Void> perm = core.auth.require(actor, Perms.REVIEW_REVIEW, "annotate reviews");
        if (!perm.isOk()) return done(perm);
        String t = Text.clean(text);
        if (t.length() < 3 || t.length() > 1000) return done(Result.fail(Code.INVALID, "A note must be 3-1000 characters."));
        return db(c -> {
            Result<Review> lr = load(c, id);
            if (!lr.isOk()) return lr.<Void>cast();
            Repo.Reviews.addEvent(c, id, core.clock.now(), actor.name(), "NOTE", t);
            audit(c, actor, "review.note", lr.value().targetUuid(), lr.value().caseId(), "review=" + id, false);
            return Result.ok();
        });
    }

    public CompletableFuture<Result<Void>> requestExplanation(Actor actor, long id, String question) {
        Result<Void> perm = core.auth.require(actor, Perms.REVIEW_REVIEW, "request an explanation");
        if (!perm.isOk()) return done(perm);
        String q = Text.clean(question);
        if (q.length() < 5 || q.length() > 600) return done(Result.fail(Code.INVALID, "The question must be 5-600 characters."));
        return db(c -> {
            Result<Review> lr = load(c, id);
            if (!lr.isOk()) return lr.<Void>cast();
            Review r = lr.value();
            long now = core.clock.now();
            Repo.Reviews.addEvent(c, id, now, actor.name(), "EXPLANATION_REQUESTED", q);
            if (r.issuerUuid() != null) {
                Map<String, String> pl = new LinkedHashMap<>();
                pl.put("event", "explanation-requested");
                pl.put("ref", r.ref());
                pl.put("player", r.targetName());
                pl.put("detail", q);
                pl.put("by", actor.name());
                core.notifications.enqueue(c, "rv-explain:" + id + ":" + now, r.issuerUuid(), STAFF, "review", id, pl);
            }
            audit(c, actor, "review.explanation", r.targetUuid(), r.caseId(), "review=" + id, false);
            return Result.ok();
        }).thenApply(res -> {
            if (res.isOk()) notifyIssuer(id);
            return res;
        });
    }

    private void notifyIssuer(long reviewId) {
        core.db.tx(c -> Repo.Reviews.get(c, reviewId)).thenAccept(r -> {
            if (r != null && r.issuerUuid() != null) core.notifications.deliverPending(r.issuerUuid());
        });
    }

    /** The issuer (or a reviewer) adds supporting evidence to the review's case, creating the case when there was none. */
    public CompletableFuture<Result<Evidence>> submitEvidence(Actor actor, long id, EvidenceValidator.Input input) {
        Result<EvidenceValidator.Input> v = EvidenceValidator.validate(core.settings, input);
        if (!v.isOk()) return done(v.cast());
        EvidenceValidator.Input in = v.value();
        return db(c -> {
            Result<Review> lr = load(c, id);
            if (!lr.isOk()) return lr.<Evidence>cast();
            Review r = lr.value();
            if (!(isOwn(actor, r) || core.auth.has(actor, Perms.REVIEW_REVIEW))) {
                return Result.<Evidence>fail(Code.NO_PERMISSION, "Only the issuing staff member or a reviewer can add evidence to this review.");
            }
            if (r.status() == ReviewStatus.REVERSED || r.migrated()) return Result.<Evidence>fail(Code.STATE, r.ref() + " no longer accepts evidence.");
            long now = core.clock.now();
            Long caseId = r.caseId();
            if (caseId == null && !core.mode.enabled()) {
                // judicial system off: the evidence belongs to the punishment itself (no hidden case is created)
                Punishment pun = r.punishmentId() == null ? null : Repo.Punishments.get(c, r.punishmentId());
                if (pun == null) return Result.<Evidence>fail(Code.NOT_FOUND, r.ref() + " has no punishment to attach evidence to.");
                if (Repo.Evidences.countActiveForPunishment(c, pun.id()) >= core.settings.cases.maxEvidence()) {
                    return Result.<Evidence>fail(Code.LIMIT, "This punishment already has the maximum number of evidence items.");
                }
                long evId = Repo.Evidences.addToPunishment(c, pun.id(), in.type(), in.reference(), in.description(), actor.dbUuid(), actor.name(), now, null);
                boolean late = r.evidenceDue() != null && now > r.evidenceDue();
                Repo.Reviews.addEvent(c, id, now, actor.name(), "EVIDENCE_ADDED", in.type() + " #" + evId + (late ? " (after the evidence deadline)" : ""));
                audit(c, actor, "review.evidence", r.targetUuid(), null, "review=" + id + " punishment=" + pun.id() + " evidence=" + evId, false);
                return Result.ok(Repo.Evidences.get(c, evId));
            }
            if (caseId == null) {
                caseId = Repo.Cases.insert(c, CaseStatus.CLOSED, CaseKind.QUICK, r.targetUuid(), r.targetName(), "other", Severity.MODERATE,
                        clip(r.reason(), 400), null, r.issuerUuid(), r.issuerName(), now);
                Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "QUICK_PUNISHMENT", null, CaseStatus.CLOSED,
                        r.ptype().display() + " (evidence added through review " + r.ref() + ")");
                Repo.Reviews.setCase(c, id, caseId, now);
                if (r.punishmentId() != null) Repo.Punishments.setCase(c, r.punishmentId(), caseId);
            }
            if (Repo.Evidences.countActive(c, caseId) >= core.settings.cases.maxEvidence()) {
                return Result.<Evidence>fail(Code.LIMIT, "This case already has the maximum number of evidence items.");
            }
            long evId = Repo.Evidences.add(c, caseId, in.type(), in.reference(), in.description(), actor.dbUuid(), actor.name(), now, null);
            boolean late = r.evidenceDue() != null && now > r.evidenceDue();
            Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "EVIDENCE_ADDED", null, null, in.type() + " #" + evId + " (review " + r.ref() + ")");
            Repo.Reviews.addEvent(c, id, now, actor.name(), "EVIDENCE_ADDED", in.type() + " #" + evId + (late ? " (after the evidence deadline)" : ""));
            audit(c, actor, "review.evidence", r.targetUuid(), caseId, "review=" + id + " evidence=" + evId, false);
            return Result.ok(Repo.Evidences.get(c, evId));
        }).thenApply(res -> {
            if (res.isOk()) {
                core.db.tx(c -> Repo.Reviews.get(c, id)).thenAccept(r -> {
                    if (r != null) core.emit(DomainEvent.of(DomainEvent.Type.REVIEW_EVIDENCE).caseId(r.caseId()).player(r.targetName()).actor(actor.name())
                            .key("review-evidence:" + res.value().id()).summary(r.ref() + ": supporting evidence added (" + res.value().type() + ")").build());
                });
            }
            return res;
        });
    }

    public CompletableFuture<Result<Review>> uphold(Actor actor, long id, long expectedVersion, String note) {
        return decide(actor, id, expectedVersion, ReviewStatus.UPHELD, note, null, null, Perms.REVIEW_REVIEW);
    }

    public CompletableFuture<Result<Review>> furtherInvestigation(Actor actor, long id, long expectedVersion, String note) {
        return decide(actor, id, expectedVersion, ReviewStatus.FURTHER_INVESTIGATION, note, null, null, Perms.REVIEW_REVIEW);
    }

    public CompletableFuture<Result<Review>> reverse(Actor actor, long id, long expectedVersion, String note) {
        return decide(actor, id, expectedVersion, ReviewStatus.REVERSED, note, null, null, Perms.REVIEW_REVERSE);
    }

    /** @param newDurationMs measured from when the punishment was issued; {@code null} keeps the duration. */
    public CompletableFuture<Result<Review>> modify(Actor actor, long id, long expectedVersion, Long newDurationMs, String newReason, String note) {
        return decide(actor, id, expectedVersion, ReviewStatus.MODIFIED, note, newDurationMs, newReason, Perms.REVIEW_MODIFY);
    }

    private CompletableFuture<Result<Review>> decide(Actor actor, long id, long expectedVersion, ReviewStatus to, String note,
                                                     Long newDuration, String newReason, String node) {
        Result<Void> perm = core.auth.require(actor, node, "decide this review");
        if (!perm.isOk()) return done(perm.cast());
        String why = Text.clean(note);
        if (why.length() < 5 || why.length() > 1000) return done(Result.fail(Code.INVALID, "A decision note of 5-1000 characters is required."));
        String reason = newReason == null ? null : Text.clean(newReason);
        if (to == ReviewStatus.MODIFIED) {
            if (newDuration == null && (reason == null || reason.isEmpty())) return done(Result.fail(Code.INVALID, "Change the duration and/or the reason."));
            if (newDuration != null && newDuration != Durations.PERMANENT && newDuration <= 0) return done(Result.fail(Code.INVALID, "Enter a valid duration."));
            if (reason != null && !reason.isEmpty() && reason.length() < core.settings.cases.minReason()) {
                return done(Result.fail(Code.INVALID, "A reason of at least " + core.settings.cases.minReason() + " characters is required."));
            }
        }
        Punishment[] beforeBox = new Punishment[1]; // a modified punishment as it was before (JailReloaded's time is moved by the difference)
        // Step 1: validate + (for reverse) take the punishment out of effect. Step 2: record the decision atomically.
        CompletableFuture<Result<Void>> effect;
        if (to == ReviewStatus.REVERSED) {
            effect = core.db.tx(c -> Repo.Reviews.get(c, id)).thenCompose(r -> {
                if (r == null) return done(Result.<Void>fail(Code.NOT_FOUND, "Review " + id + " does not exist."));
                Result<Review> op = open(r);
                if (!op.isOk()) return done(op.<Void>cast());
                if (r.version() != expectedVersion) return done(Result.<Void>fail(Code.CONFLICT, stale(id).message()));
                if (r.punishmentId() == null) return done(Result.<Void>ok());
                return core.db.tx(c -> Repo.Punishments.get(c, r.punishmentId())).thenCompose(p -> {
                    if (p == null || (p.status() != PunishStatus.ACTIVE && p.status() != PunishStatus.COMPLETED)) return done(Result.<Void>ok()); // already over: record only
                    return punishments.revoke(actor, p.id(), "Owner review " + r.ref() + ": " + why, true).thenApply(rr -> rr.isOk() ? Result.<Void>ok() : rr.<Void>cast());
                });
            });
        } else {
            effect = done(Result.<Void>ok());
        }
        return effect.thenCompose(er -> {
            if (!er.isOk()) return done(er.<Review>cast());
            return db(c -> {
                Result<Review> lr = load(c, id);
                if (!lr.isOk()) return lr;
                Review r = lr.value();
                Result<Review> op = open(r);
                if (!op.isOk() && !(to == ReviewStatus.REVERSED && r.status() == ReviewStatus.REVERSED)) return op;
                Result<Review> own = notOwn(actor, r);
                if (!own.isOk()) return own;
                long now = core.clock.now();
                String detail = to.display();
                if (to == ReviewStatus.MODIFIED) {
                    Punishment p = r.punishmentId() == null ? null : Repo.Punishments.get(c, r.punishmentId());
                    if (p == null || p.status() != PunishStatus.ACTIVE) return Result.<Review>fail(Code.STATE, "That punishment is no longer active, so it cannot be modified. Reverse it or uphold the review.");
                    Long newExpires = newDuration == null ? p.expiresAt() : newDuration == Durations.PERMANENT ? null : p.issuedAt() + newDuration;
                    if (p.type() == dev.aura.punishments.model.PunishType.JAIL && newDuration != null
                            && (newExpires == null || !JailService.live(p.jailState()) || JailService.RELEASING.equals(p.jailState()))) {
                        return Result.<Review>fail(Code.INVALID, newExpires == null ? "A jail punishment cannot be made permanent by a modification."
                                : "That jail punishment is not being held right now, so its time cannot be changed.");
                    }
                    beforeBox[0] = p;
                    if (newDuration != null && newExpires != null && newExpires <= now) return Result.<Review>fail(Code.INVALID, "That duration would already have elapsed. Reverse the punishment instead.");
                    boolean lengthens = newExpires == null ? p.expiresAt() != null : p.expiresAt() != null && newExpires > p.expiresAt();
                    if (lengthens && !core.auth.checkDuration(actor, p.type(), newDuration, true).isOk()) {
                        return Result.<Review>fail(Code.LIMIT, "That duration is above what you may set.");
                    }
                    if (!Repo.Punishments.modify(c, p.id(), newExpires, reason == null || reason.isEmpty() ? null : reason, actor.name(), now)) {
                        return Result.<Review>fail(Code.CONFLICT, "The punishment changed while you were deciding.");
                    }
                    Repo.Reviews.setReason(c, id, newExpires == null ? null : newExpires - p.issuedAt(), reason == null || reason.isEmpty() ? null : reason, now);
                    detail += " (" + (newExpires == null ? "permanent" : Durations.format(newExpires - p.issuedAt())) + ")";
                    if (r.caseId() != null) {
                        Repo.Events.add(c, r.caseId(), now, actor.dbUuid(), actor.name(), "PUNISHMENT_MODIFIED", null, null, "#" + p.id() + " " + detail);
                    }
                }
                if (!Repo.Reviews.setStatus(c, id, expectedVersion, to, actor.name(), why, to.decided(), now)) {
                    return to == ReviewStatus.REVERSED ? Result.<Review>fail(Code.CONFLICT, "The punishment was reversed, but the review changed meanwhile; reopen it to record the decision.") : stale(id);
                }
                Repo.Reviews.addEvent(c, id, now, actor.name(), "DECISION", detail + ": " + why);
                if (r.punishmentId() != null && to.decided()) {
                    Punishment pp = Repo.Punishments.get(c, r.punishmentId());
                    if (pp != null && pp.source() == dev.aura.punishments.model.Simple.Source.EMERGENCY) Repo.Punishments.markReviewed(c, pp.id()); // one decision settles both queues
                }
                audit(c, actor, "review." + to.name().toLowerCase(), r.targetUuid(), r.caseId(), "review=" + id + " " + detail, to != ReviewStatus.UPHELD);
                return Result.ok(Repo.Reviews.get(c, id));
            });
        }).thenCompose(res -> {
            if (!res.isOk()) return done(res);
            afterDecision(actor, res.value(), why);
            Punishment before = beforeBox[0];
            if (to == ReviewStatus.MODIFIED && newDuration != null && before != null && before.type() == dev.aura.punishments.model.PunishType.JAIL) {
                return core.db.tx(c -> Repo.Punishments.get(c, before.id())).thenCompose(after -> punishments.afterModify(actor, before, after))
                        .handle((rr, ex) -> rr != null && !rr.isOk() ? Result.okWith(res.value(), rr.message()) : res);
            }
            return done(res);
        });
    }

    private void afterDecision(Actor actor, Review r, String note) {
        if (r.punishmentId() != null) {
            core.db.tx(c -> Repo.Punishments.get(c, r.punishmentId())).thenAccept(p -> {
                if (p == null) return;
                punishments.cache().add(p); // replaces the cached copy atomically the cached copy (modified expiry/reason); no-op when no longer active
            });
        }
        if (r.status() == ReviewStatus.MODIFIED && r.punishmentId() != null) {
            core.db.tx(c -> Repo.Punishments.get(c, r.punishmentId())).thenAccept(pm -> {
                if (pm != null) core.emit(DomainEvent.of(DomainEvent.Type.PUNISHMENT_MODIFIED).key("modified:" + pm.id() + ":" + r.version()).player(pm.targetName()).actor(actor.name())
                        .summary(pm.type().display() + " #" + pm.id() + " modified by owner review").field("Why", Text.truncate(note, 300)).punishment(pm, "modified", 0).build());
            });
        }
        core.emit(DomainEvent.of(DomainEvent.Type.REVIEW_DECISION).key("review-decision:" + r.id() + ":" + r.version()).caseId(r.caseId())
                .player(r.targetName()).actor(actor.name()).summary(r.ref() + ": " + r.status().display())
                .field("Issued by", r.issuerName()).field("Decision note", Text.truncate(note, 400)).build());
        if (r.issuerUuid() != null && !r.issuerUuid().equals(actor.dbUuid())) {
            core.db.tx(c -> {
                Map<String, String> pl = new LinkedHashMap<>();
                pl.put("event", "decision");
                pl.put("ref", r.ref());
                pl.put("player", r.targetName());
                pl.put("detail", r.status().display() + ": " + Text.truncate(note, 200));
                pl.put("by", actor.name());
                core.notifications.enqueue(c, "rv-decision:" + r.id() + ":" + r.version(), r.issuerUuid(), STAFF, "review", r.id(), pl);
                return null;
            }).thenRun(() -> core.notifications.deliverPending(r.issuerUuid()));
        }
    }

    /** Refer the punishment to the judicial system: opens a standard case and marks the review "Further investigation". */
    public CompletableFuture<Result<Long>> refer(Actor actor, long id, long expectedVersion, String note) {
        Result<Long> off0 = core.mode.refuseNewCase();
        if (off0 != null) return done(off0);
        Result<Void> perm = core.auth.requireAny(actor, "refer to the judicial system", Perms.REVIEW_REVIEW);
        if (!perm.isOk()) return done(perm.cast());
        Result<Void> cp = core.auth.require(actor, Perms.CASE_CREATE, "open cases");
        if (!cp.isOk()) return done(cp.cast());
        String why = Text.clean(note);
        if (why.length() < 5) return done(Result.fail(Code.INVALID, "Explain why this is being referred (at least 5 characters)."));
        return core.db.tx(c -> Repo.Reviews.get(c, id)).thenCompose(r0 -> r0 == null
                ? done(Result.<Long>fail(Code.NOT_FOUND, "Review " + id + " does not exist."))
                : checkTarget(actor, r0.targetUuid()).thenCompose(tc -> tc.isOk() ? referNow(actor, id, expectedVersion, why) : done(tc.<Long>cast())));
    }

    private CompletableFuture<Result<Long>> referNow(Actor actor, long id, long expectedVersion, String why) {
        return db(c -> {
            Result<Review> lr = load(c, id);
            if (!lr.isOk()) return lr.<Long>cast();
            Review r = lr.value();
            Result<Review> op = open(r);
            if (!op.isOk()) return op.<Long>cast();
            if (r.status() == ReviewStatus.FURTHER_INVESTIGATION) return Result.<Long>fail(Code.DUPLICATE, r.ref() + " was already referred.");
            Result<Review> own = notOwn(actor, r);
            if (!own.isOk()) return own.<Long>cast();
            long now = core.clock.now();
            long caseId = Repo.Cases.insert(c, CaseStatus.OPEN, CaseKind.STANDARD, r.targetUuid(), r.targetName(), "other", Severity.MODERATE,
                    clip("Referred from owner review " + r.ref() + " (" + r.ptype().display() + "): " + r.reason(), 400), clip(why, 300),
                    actor.dbUuid(), actor.name(), now);
            Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "CREATED", null, CaseStatus.OPEN, "Referred from review " + r.ref());
            if (!Repo.Reviews.setStatus(c, id, expectedVersion, ReviewStatus.FURTHER_INVESTIGATION, actor.name(), why, false, now)) return Result.<Long>fail(Code.CONFLICT, stale(id).message());
            Repo.Reviews.addEvent(c, id, now, actor.name(), "REFERRED", "Judicial case " + "AP-" + String.format("%04d", caseId) + " opened: " + why);
            audit(c, actor, "review.refer", r.targetUuid(), caseId, "review=" + id, true);
            return Result.ok(caseId);
        }).thenApply(res -> {
            if (res.isOk()) core.emit(DomainEvent.of(DomainEvent.Type.CASE_CREATED).key("case-created:" + res.value()).caseId(res.value()).actor(actor.name())
                    .summary("Referred from owner review " + "RV-" + String.format("%04d", id)).build());
            return res;
        });
    }

    public CompletableFuture<Result<Review>> setDeadlines(Actor actor, long id, Long evidenceDueMs, Long reviewDueMs) {
        Result<Void> perm = core.auth.require(actor, Perms.REVIEW_DEADLINES, "change review deadlines");
        if (!perm.isOk()) return done(perm.cast());
        return db(c -> {
            Result<Review> lr = load(c, id);
            if (!lr.isOk()) return lr;
            Result<Review> op = open(lr.value());
            if (!op.isOk()) return op;
            long now = core.clock.now();
            if ((evidenceDueMs != null && evidenceDueMs < now) || (reviewDueMs != null && reviewDueMs < now)) {
                return Result.<Review>fail(Code.INVALID, "A deadline cannot be in the past.");
            }
            Repo.Reviews.setDeadlines(c, id, evidenceDueMs, reviewDueMs, now);
            Repo.Reviews.addEvent(c, id, now, actor.name(), "DEADLINES_CHANGED", "evidence=" + (evidenceDueMs == null ? "none" : String.valueOf(evidenceDueMs))
                    + " review=" + (reviewDueMs == null ? "none" : String.valueOf(reviewDueMs)));
            audit(c, actor, "review.deadlines", lr.value().targetUuid(), lr.value().caseId(), "review=" + id, false);
            return Result.ok(Repo.Reviews.get(c, id));
        });
    }

    // ------------------------------------------------------------------ reminders (never an action, only information)
    /** Called periodically. Sends evidence reminders to issuers and review reminders/overdue notices to the owner team. */
    public CompletableFuture<Result<Integer>> remind() {
        Settings.Review cfg = core.settings.review;
        if (!cfg.enabled()) return done(Result.ok(0));
        List<DomainEvent> events = new ArrayList<>();
        List<UUID> toDeliver = new ArrayList<>();
        return db(c -> {
            long now = core.clock.now();
            int n = 0;
            for (Review r : Repo.Reviews.openReviews(c)) {
                if (r.migrated()) continue;
                boolean hasEvidence = hasEvidence(c, r);
                // evidence reminder to the issuer
                if (!hasEvidence && r.evidenceDue() != null && r.issuerUuid() != null && r.evidenceReminders() < cfg.maxEvidenceReminders()
                        && now >= r.evidenceDue() - cfg.reminderBefore()
                        && (r.lastEvidenceReminder() == null || now - r.lastEvidenceReminder() >= cfg.reminderRepeat())) {
                    boolean late = now > r.evidenceDue();
                    Map<String, String> pl = new LinkedHashMap<>();
                    pl.put("event", late ? "evidence-overdue" : "evidence-due");
                    pl.put("ref", r.ref());
                    pl.put("player", r.targetName());
                    pl.put("detail", late ? "The evidence deadline has passed. Add evidence now: the punishment stays in force." : "Please add supporting evidence for your " + r.ptype().display().toLowerCase() + ".");
                    core.notifications.enqueue(c, "rv-evrem:" + r.id() + ":" + (r.evidenceReminders() + 1), r.issuerUuid(), STAFF, "review", r.id(), pl);
                    Repo.Reviews.noteEvidenceReminder(c, r.id(), now);
                    toDeliver.add(r.issuerUuid());
                    n++;
                }
                // review reminder to the owner team
                if (r.reviewDue() != null && now >= r.reviewDue() - cfg.reminderBefore()
                        && (r.lastReviewReminder() == null || now - r.lastReviewReminder() >= cfg.reminderRepeat())) {
                    boolean overdue = now > r.reviewDue();
                    int seq = r.reviewReminders() + 1;
                    events.add(DomainEvent.of(overdue ? DomainEvent.Type.REVIEW_OVERDUE : DomainEvent.Type.REVIEW_REMINDER)
                            .key("review-rem:" + r.id() + ":" + seq).caseId(r.caseId()).player(r.targetName()).actor(r.issuerName())
                            .summary(r.ref() + ": " + r.ptype().display() + (overdue ? " review is overdue (still in force, nothing is reversed automatically)" : " review is due soon"))
                            .field("Status", r.status().display()).field("Review due", "<t:" + r.reviewDue() / 1000 + ":R>").build());
                    Repo.Reviews.noteReviewReminder(c, r.id(), now);
                    n++;
                }
            }
            return Result.ok(n);
        }).thenApply(res -> {
            if (res.isOk()) {
                events.forEach(core::emit);
                for (UUID u : toDeliver) core.notifications.deliverPending(u);
            }
            return res;
        });
    }
}
