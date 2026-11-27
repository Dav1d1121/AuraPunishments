package dev.aura.punishments.service;

import dev.aura.punishments.config.Offense;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.*;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import dev.aura.punishments.util.Text;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct punishments (major offenses, evidence-backed, NOT the primary workflow) and emergency
 * protective actions (separately authorised, short, auto-expiring, reviewed by senior staff).
 */
public final class DirectService extends Svc {
    /** Direct punishments are limited to these types; jail goes through custody/judicial sentencing. */
    public static final EnumSet<PunishType> DIRECT_TYPES = EnumSet.of(PunishType.MUTE, PunishType.BAN, PunishType.IPBAN);

    private final PunishmentService punishments;
    private final Map<Long, Long> lastReviewAlert = new ConcurrentHashMap<>();

    public record DirectRequest(Target target, String offenseKey, Severity severity, String description, String justification,
                                List<EvidenceValidator.Input> evidence, PunishType type, long durationMs, Long existingCaseId) {}

    public record EmergencyRequest(Target target, PunishType type, long durationMs, String reason) {}

    /** UPHOLD only exists for case-free emergency actions (judicial system off): the action simply stands until it expires. */
    public enum ReviewDecision { ESCALATE_TO_TRIAL, REVOKE_AND_DISMISS, UPHOLD }

    public DirectService(Core core, PunishmentService punishments) {
        super(core);
        this.punishments = punishments;
    }

    // ------------------------------------------------------------------ direct
    public CompletableFuture<Result<Punishment>> direct(Actor actor, DirectRequest r) {
        Result<Punishment> off0 = core.mode.refuseNew("a direct punishment (it exists to bypass the judicial process; ordinary punishments are final when it is off)");
        if (off0 != null) return done(off0);
        Settings s = core.settings;
        if (!s.direct.enabled()) return done(Result.fail(Code.UNAVAILABLE, "Direct punishments are disabled in the configuration."));
        PunishType type = r.type();
        if (!DIRECT_TYPES.contains(type)) return done(Result.fail(Code.INVALID, "Direct punishment supports mute, ban and IP ban only."));
        if (!core.auth.canDirect(actor, type)) {
            return done(Result.fail(Code.NO_PERMISSION, "You are not authorised to issue direct " + type.display().toLowerCase() + "s."));
        }
        Result<Void> access = targetAccess(actor);
        if (!access.isOk()) return done(access.cast());
        Optional<Offense> off = core.offenses.get(r.offenseKey());
        if (off.isEmpty()) return done(Result.fail(Code.INVALID, "Unknown offense category."));
        Offense offense = off.get();
        if (!offense.directEligible()) {
            return done(Result.fail(Code.INVALID, "'" + offense.display() + "' is not a major offense eligible for direct punishment. Open a case instead."));
        }
        if (!offense.permission().isBlank() && !core.auth.has(actor, offense.permission())) {
            return done(Result.fail(Code.NO_PERMISSION, "You do not have permission to charge '" + offense.display() + "'."));
        }
        if (r.severity() == null || r.severity().ordinal() < s.direct.minSeverity().ordinal()) {
            return done(Result.fail(Code.INVALID, "Direct punishment requires a severity of at least " + s.direct.minSeverity() + "."));
        }
        if (!offense.allows(type)) return done(Result.fail(Code.INVALID, offense.display() + " cannot result in a " + type.display().toLowerCase() + "."));
        long duration = type == PunishType.MUTE || type == PunishType.BAN || type == PunishType.IPBAN ? r.durationMs() : 0;
        if (duration != Durations.PERMANENT && duration <= 0) return done(Result.fail(Code.INVALID, "Choose a duration or permanent."));
        if (duration != Durations.PERMANENT && offense.exceedsCap(duration)) {
            return done(Result.fail(Code.LIMIT, "That exceeds the maximum for " + offense.display() + " (" + Durations.format(offense.maxDuration()) + ")."));
        }
        if (duration == Durations.PERMANENT && offense.maxDuration() != Durations.PERMANENT) {
            return done(Result.fail(Code.LIMIT, offense.display() + " cannot result in a permanent punishment."));
        }
        Result<Void> dur = core.auth.checkDuration(actor, type, duration, true);
        if (!dur.isOk()) return done(dur.cast());

        String desc = Text.clean(r.description());
        String just = Text.clean(r.justification());
        if (desc.length() < s.direct.minDescription()) return done(Result.fail(Code.INVALID, "The detailed description must be at least " + s.direct.minDescription() + " characters."));
        if (desc.length() > s.cases.maxDescription()) return done(Result.fail(Code.INVALID, "The description is too long (max " + s.cases.maxDescription() + ")."));
        if (just.length() < s.direct.minJustification()) {
            return done(Result.fail(Code.INVALID, "Explain why bypassing the judicial process is justified (at least " + s.direct.minJustification() + " characters)."));
        }
        if (just.length() > s.cases.maxDescription()) return done(Result.fail(Code.INVALID, "The justification is too long."));
        List<EvidenceValidator.Input> evidence = new ArrayList<>();
        for (EvidenceValidator.Input in : r.evidence() == null ? List.<EvidenceValidator.Input>of() : r.evidence()) {
            Result<EvidenceValidator.Input> v = EvidenceValidator.validate(s, in);
            if (!v.isOk()) return done(v.cast());
            evidence.add(v.value());
        }
        int need = Math.max(s.direct.minEvidence(), offense.minEvidenceDirect());
        if (evidence.size() < need && r.existingCaseId() == null) {
            return done(Result.fail(Code.EVIDENCE, "Direct punishment for '" + offense.display() + "' requires at least " + need + " evidence reference(s)."));
        }
        if (evidence.size() > s.cases.maxEvidence()) return done(Result.fail(Code.EVIDENCE, "Too many evidence items."));

        String reason = Text.truncate(offense.display() + ": " + desc, 200);
        return checkTarget(actor, r.target().uuid()).thenCompose(tc -> {
            if (!tc.isOk()) return done(tc.<Punishment>cast());
            return db(c -> {
                long now = core.clock.now();
                long caseId;
                if (r.existingCaseId() != null) {
                    Case cs = Repo.Cases.get(c, r.existingCaseId());
                    if (cs == null) return Result.<Punishment>fail(Code.NOT_FOUND, "Case does not exist.");
                    if (cs.kind() != CaseKind.EMERGENCY || cs.status() != CaseStatus.UNDER_INVESTIGATION || !cs.accusedUuid().equals(r.target().uuid())) {
                        return Result.<Punishment>fail(Code.STATE, "Only an emergency case under investigation can be converted to a direct punishment.");
                    }
                    long have = Repo.Evidences.countActive(c, cs.id()) + evidence.size();
                    if (have < need) return Result.<Punishment>fail(Code.EVIDENCE, "At least " + need + " evidence reference(s) are required in total.");
                    caseId = cs.id();
                    // The emergency action is replaced by the formal punishment.
                    for (Punishment p : Repo.Punishments.forCase(c, caseId)) {
                        if (p.source() == Source.EMERGENCY && p.status() == PunishStatus.ACTIVE) {
                            Repo.Punishments.end(c, p.id(), PunishStatus.REVOKED, actor.name(), "Replaced by a formal direct punishment", now);
                            Repo.Punishments.markReviewed(c, p.id());
                        }
                    }
                    Repo.Cases.setStatus(c, caseId, cs.version(), cs.status(), CaseStatus.DIRECT_PUNISHMENT_ISSUED, now);
                } else {
                    caseId = Repo.Cases.insert(c, CaseStatus.DIRECT_PUNISHMENT_ISSUED, CaseKind.DIRECT, r.target().uuid(), r.target().name(),
                            offense.key(), r.severity(), desc, null, actor.dbUuid(), actor.name(), now);
                }
                for (EvidenceValidator.Input in : evidence) {
                    Repo.Evidences.add(c, caseId, in.type(), in.reference(), in.description(), actor.dbUuid(), actor.name(), now, null);
                }
                Result<Punishment> ins = punishments.insert(c, actor, type, r.target(), duration, reason, Source.DIRECT, caseId, offense.key(), null, null, true);
                if (!ins.isOk()) return ins;
                Repo.Directs.add(c, caseId, ins.value().id(), offense.key(), just, actor.name(), now);
                Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "DIRECT_PUNISHMENT", null, CaseStatus.DIRECT_PUNISHMENT_ISSUED,
                        type.display() + " " + Durations.format(duration) + " | justification: " + Text.truncate(just, 150));
                audit(c, actor, "direct.punish", r.target().uuid(), caseId, "punishment=" + ins.value().id() + " " + type + " "
                        + Durations.format(duration) + " offense=" + offense.key() + " evidence=" + evidence.size(), true);
                return ins;
            });
        }).thenCompose(res -> {
            if (!res.isOk()) return done(res);
            Punishment p = res.value();
            return punishments.afterIssue(p, false).thenApply(v -> {
                core.emit(DomainEvent.of(DomainEvent.Type.DIRECT).caseId(p.caseId()).player(p.targetName()).actor(actor.name())
                        .summary(type.display() + " (" + Durations.format(duration) + ")").field("Offense", offense.display())
                        .field("Punishment", type.display()).field("Duration", Durations.format(duration))
                        .field("Justification", Text.truncate(just, 300)).build());
                return res;
            });
        });
    }

    // ------------------------------------------------------------------ emergency
    public CompletableFuture<Result<Punishment>> emergency(Actor actor, EmergencyRequest r) {
        Settings s = core.settings;
        if (!s.emergency.enabled()) return done(Result.fail(Code.UNAVAILABLE, "Emergency actions are disabled in the configuration."));
        Result<Void> perm = core.auth.require(actor, Perms.EMERGENCY_USE, "use emergency protective actions");
        if (!perm.isOk()) return done(perm.cast());
        Result<Void> access = targetAccess(actor);
        if (!access.isOk()) return done(access.cast());
        PunishType type = r.type();
        if (!s.emergency.allowedTypes().contains(type)) {
            return done(Result.fail(Code.INVALID, type.display() + " is not an allowed emergency action."));
        }
        long duration = r.durationMs() <= 0 ? s.emergency.defaultDuration() : r.durationMs();
        if (duration == Durations.PERMANENT || duration > s.emergency.maxDuration()) {
            return done(Result.fail(Code.LIMIT, "Emergency actions last at most " + Durations.format(s.emergency.maxDuration()) + "."));
        }
        String reason = Text.clean(r.reason());
        int min = Math.max(15, s.cases.minReason());
        if (reason.length() < min) return done(Result.fail(Code.INVALID, "Describe the urgent threat (at least " + min + " characters)."));
        if (reason.length() > s.cases.maxReason()) return done(Result.fail(Code.INVALID, "The reason is too long."));
        return checkTarget(actor, r.target().uuid()).thenCompose(tc -> {
            if (!tc.isOk()) return done(tc.<Punishment>cast());
            return db(c -> {
                long now = core.clock.now();
                // With the judicial system off there is no case: the action is a punishment that expires by itself and is reviewed by senior staff.
                boolean withCase = core.mode.enabled();
                Long caseId = withCase ? Repo.Cases.insert(c, CaseStatus.UNDER_INVESTIGATION, CaseKind.EMERGENCY, r.target().uuid(), r.target().name(),
                        "other", Severity.SEVERE, "EMERGENCY: " + reason, null, actor.dbUuid(), actor.name(), now) : null;
                Result<Punishment> ins = punishments.insert(c, actor, type, r.target(), duration, "Temporary protective action: " + reason,
                        Source.EMERGENCY, caseId, "other", now + s.emergency.reviewDeadline(), null, true);
                if (!ins.isOk()) return ins;
                if (withCase) {
                    Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "EMERGENCY_ACTION", null, CaseStatus.UNDER_INVESTIGATION,
                            type.display() + " " + Durations.format(duration) + " - review due in " + Durations.format(s.emergency.reviewDeadline()));
                } else {
                    ReviewService.createIfRequired(core, c, actor, ins.value(), null); // owner review queue, when review.include-emergency is on
                }
                audit(c, actor, "emergency.action", r.target().uuid(), caseId, "punishment=" + ins.value().id() + " " + type + " " + Durations.format(duration), true);
                return ins;
            });
        }).thenCompose(res -> {
            if (!res.isOk()) return done(res);
            Punishment p = res.value();
            return punishments.afterIssue(p, false).thenApply(v -> {
                core.emit(DomainEvent.of(DomainEvent.Type.EMERGENCY).caseId(p.caseId()).player(p.targetName()).actor(actor.name())
                        .summary(type.display() + " for " + Durations.format(duration) + "; senior review required")
                        .field("Reason", Text.truncate(reason, 300)).field("Review by", "<t:" + (p.reviewDeadline() / 1000) + ":R>").build());
                return res;
            });
        });
    }

    /**
     * Review an emergency action that has no case (judicial system off): uphold it (it runs to its expiry) or revoke it.
     * Mirrors the decision on the owner-review record when one exists, so the two queues never disagree.
     */
    public CompletableFuture<Result<Punishment>> reviewEmergencyAction(Actor actor, long punishmentId, ReviewDecision decision, String reasoning) {
        Result<Void> perm = core.auth.require(actor, Perms.EMERGENCY_REVIEW, "review emergency actions");
        if (!perm.isOk()) return done(perm.cast());
        if (decision == ReviewDecision.ESCALATE_TO_TRIAL) {
            return done(Result.fail(Code.UNAVAILABLE, "There is no trial to escalate to (the judicial system is switched off). Uphold or revoke the action."));
        }
        String why = Text.clean(reasoning);
        if (why.length() < core.settings.cases.minReason()) {
            return done(Result.fail(Code.INVALID, "A reason of at least " + core.settings.cases.minReason() + " characters is required."));
        }
        Punishment[] revoked = new Punishment[1];
        return db(c -> {
            Punishment p = Repo.Punishments.get(c, punishmentId);
            if (p == null || p.source() != Source.EMERGENCY) return Result.<Punishment>fail(Code.NOT_FOUND, "That is not an emergency action.");
            if (p.reviewed()) return Result.<Punishment>fail(Code.STATE, "That emergency action has already been reviewed.");
            if (p.caseId() != null) return Result.<Punishment>fail(Code.STATE, "That action belongs to a case; review it through the case.");
            long now = core.clock.now();
            Repo.Punishments.markReviewed(c, punishmentId);
            var rv = Repo.Reviews.byPunishment(c, punishmentId);
            if (decision == ReviewDecision.REVOKE_AND_DISMISS) {
                if (p.status() == PunishStatus.ACTIVE) {
                    Repo.Punishments.end(c, p.id(), PunishStatus.REVOKED, actor.name(), "Emergency review: " + Text.truncate(why, 100), now);
                    revoked[0] = p;
                }
            }
            if (rv != null && rv.status().open()) {
                var to = decision == ReviewDecision.REVOKE_AND_DISMISS ? ReviewStatus.REVERSED : ReviewStatus.UPHELD;
                Repo.Reviews.setStatus(c, rv.id(), rv.version(), to, actor.name(), why, true, now);
                Repo.Reviews.addEvent(c, rv.id(), now, actor.name(), "DECISION", to.display() + " (through the emergency review): " + Text.truncate(why, 200));
            }
            audit(c, actor, "emergency.review", p.targetUuid(), null, "punishment=" + p.id() + " " + decision + " " + Text.truncate(why, 100), true);
            return Result.ok(Repo.Punishments.get(c, punishmentId));
        }).thenApply(res -> {
            if (res.isOk()) {
                if (revoked[0] != null) {
                    punishments.cache().remove(revoked[0]);
                    core.main.run(() -> {
                        if (revoked[0].type() == PunishType.MUTE) core.enforcement.noticeUnmute(revoked[0].targetUuid());
                    });
                }
                lastReviewAlert.remove(punishmentId);
                core.emit(DomainEvent.of(DomainEvent.Type.REVERSAL).player(res.value().targetName()).actor(actor.name()).key("emergency-reviewed:" + punishmentId)
                        .summary("Emergency action reviewed: " + (decision == ReviewDecision.REVOKE_AND_DISMISS ? "revoked" : "upheld")).field("Reasoning", Text.truncate(why, 300)).build());
            }
            return res;
        });
    }

    public CompletableFuture<Result<Case>> reviewEmergency(Actor actor, long punishmentId, ReviewDecision decision, String reasoning) {
        Result<Void> perm = core.auth.require(actor, Perms.EMERGENCY_REVIEW, "review emergency actions");
        if (!perm.isOk()) return done(perm.cast());
        if (decision == ReviewDecision.UPHOLD) return done(Result.fail(Code.INVALID, "Choose escalate to trial or revoke and dismiss."));
        String why = Text.clean(reasoning);
        if (why.length() < core.settings.cases.minReason()) {
            return done(Result.fail(Code.INVALID, "A reason of at least " + core.settings.cases.minReason() + " characters is required."));
        }
        Punishment[] revoked = new Punishment[1];
        return db(c -> {
            Punishment p = Repo.Punishments.get(c, punishmentId);
            if (p == null || p.source() != Source.EMERGENCY) return Result.<Case>fail(Code.NOT_FOUND, "That is not an emergency action.");
            if (p.reviewed()) return Result.<Case>fail(Code.STATE, "That emergency action has already been reviewed.");
            Case cs = p.caseId() == null ? null : Repo.Cases.get(c, p.caseId());
            if (cs == null) return Result.<Case>fail(Code.NOT_FOUND, "The case for that action is missing.");
            long now = core.clock.now();
            Repo.Punishments.markReviewed(c, punishmentId);
            if (decision == ReviewDecision.ESCALATE_TO_TRIAL) {
                if (cs.status().canMoveTo(CaseStatus.AWAITING_TRIAL)) {
                    Repo.Cases.setStatus(c, cs.id(), cs.version(), cs.status(), CaseStatus.AWAITING_TRIAL, now);
                }
                Repo.Events.add(c, cs.id(), now, actor.dbUuid(), actor.name(), "EMERGENCY_REVIEWED", cs.status(), CaseStatus.AWAITING_TRIAL,
                        "Escalated to trial: " + Text.truncate(why, 200));
            } else {
                if (p.status() == PunishStatus.ACTIVE) {
                    Repo.Punishments.end(c, p.id(), PunishStatus.REVOKED, actor.name(), "Emergency review: " + Text.truncate(why, 100), now);
                    revoked[0] = p;
                }
                if (cs.status().canMoveTo(CaseStatus.DISMISSED)) {
                    Repo.Cases.setStatus(c, cs.id(), cs.version(), cs.status(), CaseStatus.DISMISSED, now);
                }
                Repo.Events.add(c, cs.id(), now, actor.dbUuid(), actor.name(), "EMERGENCY_REVIEWED", cs.status(), CaseStatus.DISMISSED,
                        "Revoked and dismissed: " + Text.truncate(why, 200));
            }
            audit(c, actor, "emergency.review", p.targetUuid(), cs.id(), decision + " " + Text.truncate(why, 100), true);
            return Result.ok(Repo.Cases.get(c, cs.id()));
        }).thenApply(res -> {
            if (res.isOk()) {
                if (revoked[0] != null) {
                    punishments.cache().remove(revoked[0]);
                    core.main.run(() -> {
                        if (revoked[0].type() == PunishType.MUTE) core.enforcement.noticeUnmute(revoked[0].targetUuid());
                    });
                }
                lastReviewAlert.remove(punishmentId);
                core.emit(DomainEvent.of(DomainEvent.Type.REVERSAL).caseId(res.value().id()).player(res.value().accusedName()).actor(actor.name())
                        .summary("Emergency action reviewed: " + decision.name().toLowerCase().replace('_', ' ')).field("Reasoning", Text.truncate(why, 300)).build());
            }
            return res;
        });
    }

    /** Alert about emergency actions whose review deadline has passed. Run about once a minute. */
    public CompletableFuture<Void> sweepReviews() {
        return core.db.tx(c -> Repo.Punishments.emergencyAwaitingReview(c)).thenAccept(list -> {
            long now = core.clock.now();
            for (Punishment p : list) {
                if (p.reviewDeadline() == null || now < p.reviewDeadline()) continue;
                Long last = lastReviewAlert.get(p.id());
                if (last != null && now - last < core.settings.emergency.overdueRepeat()) continue;
                lastReviewAlert.put(p.id(), now);
                core.emit(DomainEvent.of(DomainEvent.Type.EMERGENCY).caseId(p.caseId()).player(p.targetName()).actor("System")
                        .summary("REVIEW OVERDUE for emergency " + p.type().display().toLowerCase() + " (issued by " + p.issuerName()
                                + "). It expires automatically " + (p.expiresAt() == null ? "" : "<t:" + (p.expiresAt() / 1000) + ":R>") + " unless replaced by a formal punishment.").build());
            }
        }).exceptionally(t -> {
            core.log.warning("Emergency review sweep failed: " + t.getMessage());
            return null;
        });
    }
}
