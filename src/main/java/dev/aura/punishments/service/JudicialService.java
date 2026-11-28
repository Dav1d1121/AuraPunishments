package dev.aura.punishments.service;

import com.google.gson.Gson;
import dev.aura.punishments.config.Offense;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.integration.JailPort;
import dev.aura.punishments.integration.JailPort.JailRequest;
import dev.aura.punishments.integration.JailPort.Outcome;
import dev.aura.punishments.integration.JailPort.State;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.*;
import dev.aura.punishments.model.Simple.*;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import dev.aura.punishments.util.Text;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The judge's tools: verdicts, sentencing (with time-served credit), appeals and reversals.
 * Pretrial custody is always ended or converted through {@link CustodyService}/JailReloaded and
 * never leaves a contradictory state (for example a ban while still jailed for the same case).
 */
public final class JudicialService extends Svc {
    private static final Gson GSON = new Gson();

    private final CustodyService custody;
    private final PunishmentService punishments;

    /** @param creditTimeServed null = use judicial.time-served.default-credit-for (jail: automatic; bans/mutes: only when the judge asks for it) */
    public record SentenceSpec(PunishType type, long durationMs, String reason, Boolean creditTimeServed) {}

    public record VerdictResult(Case c, String custodyNote) {}

    public record SentenceResult(Case c, Punishment punishment, long creditedMs) {}

    /** Payload stored with a SENTENCE pending operation so it can be completed after a crash. */
    record SentencePayload(long caseId, String type, long durationMs, long remainingMs, long creditMs, String reason,
                           String actorName, String actorUuid, Long custodyId, String jailPrisonerId, String jailName,
                           boolean newJail) {}

    public JudicialService(Core core, CustodyService custody, PunishmentService punishments) {
        super(core);
        this.custody = custody;
        this.punishments = punishments;
    }

    // ------------------------------------------------------------------ helpers
    private Result<Case> loadFor(Connection c, Actor actor, long id) throws SQLException {
        Case cs = Repo.Cases.get(c, id);
        if (cs == null) return Result.fail(Code.NOT_FOUND, "Case " + id + " does not exist.");
        if (!core.auth.canViewCase(actor, cs)) return Result.fail(Code.NO_PERMISSION, "You do not have permission to view that case.");
        return Result.ok(cs);
    }

    /** Judge rules: assigned judge only (unless verdict.any); prosecutor restriction. Returns the judge to record. */
    private Result<Void> judgeRules(Actor actor, Case cs) {
        Settings s = core.settings;
        if (cs.judgeUuid() != null && actor.dbUuid() != null && !cs.judgeUuid().equals(actor.dbUuid())
                && s.judicial.requireAssignedJudge() && !core.auth.has(actor, Perms.JUDICIAL_VERDICT_ANY)) {
            return Result.fail(Code.NO_PERMISSION, cs.judgeName() + " is the assigned judge for this case.");
        }
        if (!s.judicial.prosecutorMayJudge() && actor.dbUuid() != null && actor.dbUuid().equals(cs.prosecutorUuid())) {
            return Result.fail(Code.NO_PERMISSION, "The prosecutor may not judge their own case.");
        }
        return Result.ok();
    }

    private String reasonCheck(String reason, int min) {
        String r = Text.clean(reason);
        if (r.length() < min) return "A reason of at least " + min + " characters is required.";
        if (r.length() > core.settings.cases.maxReason()) return "The reason is too long (max " + core.settings.cases.maxReason() + ").";
        return null;
    }

    // ------------------------------------------------------------------ verdict
    public CompletableFuture<Result<VerdictResult>> enterVerdict(Actor actor, long caseId, long expectedVersion, Verdict verdict, String reasoning) {
        Result<Void> perm = core.auth.require(actor, Perms.JUDICIAL_VERDICT, "enter verdicts");
        if (!perm.isOk()) return done(perm.cast());
        String problem = reasonCheck(reasoning, core.settings.judicial.minVerdictReason());
        if (problem != null) return done(Result.fail(Code.INVALID, problem));
        String reason = Text.clean(reasoning);
        CompletableFuture<Result<Void>> hierarchy = verdict == Verdict.GUILTY
                ? core.db.tx(c -> Repo.Cases.get(c, caseId)).handle((cs, ex) -> cs).thenCompose(cs -> cs == null ? done(Result.<Void>ok()) : checkTarget(actor, cs.accusedUuid()))
                : done(Result.ok());
        return hierarchy.thenCompose(h -> {
            if (!h.isOk()) return done(h.<VerdictResult>cast());
            return db(c -> {
                Result<Case> lc = loadFor(c, actor, caseId);
                if (!lc.isOk()) return lc.<VerdictResult>cast();
                Case cs = lc.value();
                if (cs.version() != expectedVersion) return Result.<VerdictResult>fail(Code.CONFLICT, "This case was changed by someone else. Reopen it to see the latest version.");
                if (!(cs.status() == CaseStatus.IN_TRIAL || cs.status() == CaseStatus.AWAITING_VERDICT)) {
                    return Result.<VerdictResult>fail(Code.STATE, "A verdict can only be entered while the case is 'In Trial' or 'Awaiting Verdict' (it is '" + cs.status().display() + "').");
                }
                Result<Void> jr = judgeRules(actor, cs);
                if (!jr.isOk()) return jr.<VerdictResult>cast();
                CaseStatus to = switch (verdict) {
                    case GUILTY -> CaseStatus.GUILTY;
                    case NOT_GUILTY -> CaseStatus.NOT_GUILTY;
                    case DISMISSED -> CaseStatus.DISMISSED;
                    case CONTINUED -> CaseStatus.AWAITING_TRIAL;
                };
                long now = core.clock.now();
                boolean ok;
                if (verdict == Verdict.CONTINUED) {
                    ok = Repo.Cases.setStatus(c, caseId, expectedVersion, cs.status(), to, now);
                } else {
                    ok = Repo.Cases.recordVerdict(c, caseId, expectedVersion, cs.status(), to, verdict, reason, actor.name(), now);
                    if (ok && cs.judgeUuid() == null && actor.dbUuid() != null) {
                        Repo.Cases.updateFields(c, caseId, expectedVersion + 1, "judge_uuid=?, judge_name=?", now, actor.dbUuid().toString(), actor.name());
                    }
                }
                if (!ok) return Result.<VerdictResult>fail(Code.CONFLICT, "This case was changed by someone else. Reopen it to see the latest version.");
                Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "VERDICT_" + verdict.name(), cs.status(), to, Text.truncate(reason, 250));
                audit(c, actor, "verdict." + verdict.name().toLowerCase(), cs.accusedUuid(), caseId, Text.truncate(reason, 120), true);
                return Result.ok(new VerdictResult(Repo.Cases.get(c, caseId), null));
            });
        }).thenCompose(res -> {
            if (!res.isOk()) return done(res);
            Case cs = res.value().c();
            core.notifications.defendant(cs, verdict == Verdict.NOT_GUILTY || verdict == Verdict.DISMISSED ? "acquittal" : "verdict", verdict.display()
                    + (core.settings.defendant.includeReasoning() ? " - " + Text.truncate(reason, 300) : ""), String.valueOf(cs.version()));
            core.emit(DomainEvent.of(DomainEvent.Type.VERDICT).key("verdict:" + cs.id() + ":" + cs.version()).caseId(cs.id()).player(cs.accusedName()).actor(actor.name())
                    .summary(verdict.display()).field("Verdict", verdict.display()).field("Reasoning", Text.truncate(reason, 300)).build());
            if (verdict == Verdict.NOT_GUILTY || verdict == Verdict.DISMISSED) {
                return custody.releaseForCaseResolution(Actor.system(), caseId, verdict.display() + ": " + Text.truncate(reason, 100)).thenApply(r -> {
                    String note;
                    if (r.value() == null && r.isOk()) note = "No pretrial custody was active.";
                    else if (r.isOk()) note = r.value().status() == CustodyStatus.RELEASING
                            ? "Release accepted by JailReloaded; it completes when the player next logs in." : "Released from pretrial custody.";
                    else note = "WARNING: the verdict is recorded but the prisoner could not be released yet (" + r.message() + "). The release is retried automatically.";
                    return Result.ok(new VerdictResult(cs, note));
                });
            }
            return done(res);
        });
    }

    // ------------------------------------------------------------------ sentence
    public CompletableFuture<Result<SentenceResult>> sentence(Actor actor, long caseId, long expectedVersion, SentenceSpec spec) {
        Result<Void> perm = core.auth.require(actor, Perms.JUDICIAL_SENTENCE, "impose sentences");
        if (!perm.isOk()) return done(perm.cast());
        PunishType type = spec.type();
        if (type == PunishType.KICK) return done(Result.fail(Code.INVALID, "A kick is not a sentence."));
        String problem = reasonCheck(spec.reason(), core.settings.cases.minReason());
        if (problem != null) return done(Result.fail(Code.INVALID, problem));
        String reason = Text.clean(spec.reason());
        long duration = type.canBeTimed() ? spec.durationMs() : 0;
        if (type.canBeTimed() && duration != Durations.PERMANENT && duration <= 0) {
            return done(Result.fail(Code.INVALID, "Choose a duration or permanent."));
        }
        if (type == PunishType.JAIL && duration == Durations.PERMANENT) {
            return done(Result.fail(Code.INVALID, "Jail sentences must have an end time."));
        }
        Result<Void> dur = core.auth.checkDuration(actor, type, duration, false);
        if (!dur.isOk()) return done(dur.cast());
        if (type == PunishType.JAIL && !core.courtJail().available()) {
            return done(Result.fail(Code.UNAVAILABLE, "JailReloaded is not available (" + core.courtJail().status() + "); a jail sentence cannot be imposed."));
        }

        return core.db.tx(c -> Repo.Cases.get(c, caseId)).handle((cs, ex) -> cs).thenCompose(cs0 -> {
            if (cs0 == null) return done(Result.<SentenceResult>fail(Code.NOT_FOUND, "Case " + caseId + " does not exist."));
            return checkTarget(actor, cs0.accusedUuid()).thenCompose(h -> {
                if (!h.isOk()) return done(h.<SentenceResult>cast());
                boolean credit = spec.creditTimeServed() != null ? spec.creditTimeServed()
                        : core.settings.judicial.timeServed().defaultCreditFor().contains(type);
                return runSentence(actor, caseId, expectedVersion, type, duration, reason, credit);
            });
        });
    }

    private CompletableFuture<Result<SentenceResult>> runSentence(Actor actor, long caseId, long expectedVersion, PunishType type,
                                                                   long duration, String reason, boolean credit) {
        Settings s = core.settings;
        Object[] box = new Object[3]; // case, custody, payload id
        long[] ids = new long[2];     // custody id for new jail, op id
        // Step 1: validate and record intent.
        return db(c -> {
            Result<Case> lc = loadFor(c, actor, caseId);
            if (!lc.isOk()) return lc.<SentencePayload>cast();
            Case cs = lc.value();
            if (cs.version() != expectedVersion) return Result.<SentencePayload>fail(Code.CONFLICT, "This case was changed by someone else. Reopen it to see the latest version.");
            if (!(cs.status() == CaseStatus.GUILTY || cs.status() == CaseStatus.AWAITING_SENTENCING)) {
                return Result.<SentencePayload>fail(Code.STATE, "Only a case with a guilty verdict can be sentenced (it is '" + cs.status().display() + "').");
            }
            if (cs.verdict() != Verdict.GUILTY) return Result.<SentencePayload>fail(Code.STATE, "There is no guilty verdict on this case.");
            Result<Void> jr = judgeRules(actor, cs);
            if (!jr.isOk()) return jr.<SentencePayload>cast();
            Optional<Offense> off = core.offenses.get(cs.offenseKey());
            if (off.isPresent()) {
                if (!off.get().allows(type)) return Result.<SentencePayload>fail(Code.INVALID, off.get().display() + " cannot result in a " + type.display().toLowerCase() + " (see offenses.yml).");
                if (type.canBeTimed() && off.get().exceedsCap(duration)) {
                    return Result.<SentencePayload>fail(Code.LIMIT, "That exceeds the maximum for " + off.get().display() + " (" + Durations.format(off.get().maxDuration()) + ").");
                }
            }
            Custody cu = Repo.Custodies.liveForCase(c, caseId);
            long now = core.clock.now();
            long credited = 0;
            long remaining = duration;
            if (cu != null && cu.status() == CustodyStatus.ACTIVE && cu.startedAt() != null && credit && s.judicial.timeServed().enabled()
                    && s.judicial.timeServed().appliesTo().contains(type) && type.canBeTimed() && duration != Durations.PERMANENT) {
                long served = Math.max(0, now - cu.startedAt());
                credited = Math.min(duration, (long) (served * s.judicial.timeServed().rate()));
                remaining = duration - credited;
            }
            boolean needsJailStep = type == PunishType.JAIL && remaining > 0;
            Long custodyId = cu == null ? null : cu.id();
            boolean newJail = false;
            if (type == PunishType.JAIL && cu == null) {
                Custody other = Repo.Custodies.liveForPlayer(c, cs.accusedUuid());
                if (other != null) return Result.<SentencePayload>fail(Code.DUPLICATE, cs.accusedName() + " is already held for case " + CustodyService.tag(other.caseId()) + ".");
                custodyId = Repo.Custodies.insert(c, caseId, cs.accusedUuid(), cs.accusedName(), actor.dbUuid(), actor.name(), now);
                newJail = true;
                needsJailStep = true;
            }
            SentencePayload pl = new SentencePayload(caseId, type.name(), duration, remaining, credited, reason, actor.name(),
                    actor.dbUuid() == null ? null : actor.dbUuid().toString(), custodyId, cu == null ? null : cu.jailPrisonerId(),
                    cu == null ? null : cu.jailName(), newJail);
            if (needsJailStep) {
                ids[1] = Repo.Ops.add(c, "SENTENCE", caseId, custodyId, cs.accusedUuid(), GSON.toJson(pl), now);
            }
            audit(c, actor, "sentence.begin", cs.accusedUuid(), caseId, type + " " + (type.canBeTimed() ? Durations.format(duration) : "") + " credit=" + Durations.format(credited), false);
            box[0] = cs;
            box[1] = cu;
            return Result.ok(pl);
        }).thenCompose(r1 -> {
            if (!r1.isOk()) return done(r1.<SentenceResult>cast());
            SentencePayload pl = r1.value();
            Case cs = (Case) box[0];
            Custody cu = (Custody) box[1];
            long opId = ids[1];
            // Step 2: external effect on JailReloaded.
            CompletableFuture<Result<SentencePayload>> step2;
            if (type == PunishType.JAIL && pl.remainingMs() > 0) {
                step2 = core.main.call(() -> jailStep(actor, cs, cu, pl)).thenCompose(out -> {
                    if (!out.ok()) {
                        return failSentenceOp(opId, pl, out.message()).thenApply(x ->
                                Result.<SentencePayload>fail(Code.EXTERNAL, "JailReloaded could not apply the jail sentence: " + out.message() + " The case is unchanged."));
                    }
                    SentencePayload withIds = new SentencePayload(pl.caseId(), pl.type(), pl.durationMs(), pl.remainingMs(), pl.creditMs(),
                            pl.reason(), pl.actorName(), pl.actorUuid(), pl.custodyId(),
                            out.jailPrisonerId() != null ? out.jailPrisonerId() : pl.jailPrisonerId(),
                            out.jailName() != null ? out.jailName() : pl.jailName(), pl.newJail());
                    return core.db.tx(c -> {
                        Repo.Ops.setState(c, opId, OpState.EXTERNAL_DONE, null, core.clock.now());
                        return null;
                    }).handle((v, ex) -> Result.ok(withIds));
                });
            } else if (cu != null && cu.status().isLive()) {
                // Non-jail sentence (or fully-credited jail): the prisoner must be released first.
                step2 = custody.doRelease(Actor.system(), cu, "Sentenced: " + type.display() + " - " + Text.truncate(reason, 80)).thenApply(rel -> {
                    if (!rel.isOk()) {
                        return Result.<SentencePayload>fail(Code.EXTERNAL, "The prisoner could not be released from pretrial custody, so the sentence was not applied: " + rel.message());
                    }
                    return Result.ok(pl);
                });
            } else {
                step2 = done(Result.ok(pl));
            }
            // Step 3: record the sentence.
            return step2.thenCompose(r2 -> {
                if (!r2.isOk()) return done(r2.<SentenceResult>cast());
                return finalizeSentence(actor, r2.value(), opId, expectedVersion);
            });
        });
    }

    private Outcome jailStep(Actor actor, Case cs, Custody cu, SentencePayload pl) {
        try {
            if (cu != null && !pl.newJail()) {
                return core.courtJail().setRemaining(cs.accusedUuid(), cu.jailPrisonerId(), pl.remainingMs(), actor.name());
            }
            Settings s = core.settings;
            JailRequest req = new JailRequest(cs.accusedUuid(), cs.accusedName(), cs.id(), CustodyService.tag(cs.id()),
                    Text.truncate(pl.reason(), 100), actor.name(), pl.remainingMs(), s.custody.jailName(), s.custody.cellName(), s.custody.muteWhileHeld());
            return core.courtJail().jail(req);
        } catch (Throwable t) {
            return Outcome.of(State.ERROR, "JailReloaded threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private CompletableFuture<Void> failSentenceOp(long opId, SentencePayload pl, String error) {
        return core.db.tx(c -> {
            long now = core.clock.now();
            if (opId != 0) Repo.Ops.setState(c, opId, OpState.FAILED, error, now);
            if (pl.newJail() && pl.custodyId() != null) {
                Repo.Custodies.end(c, pl.custodyId(), CustodyStatus.FAILED, pl.actorName(), error, now);
            }
            return null;
        }).handle((v, ex) -> null);
    }

    /** Insert the punishment, update custody and move the case to SENTENCED in one transaction. */
    private CompletableFuture<Result<SentenceResult>> finalizeSentence(Actor actor, SentencePayload pl, long opId, long expectedVersion) {
        PunishType type = PunishType.valueOf(pl.type());
        Punishment[] out = new Punishment[1];
        return db(c -> {
            Case cs = Repo.Cases.get(c, pl.caseId());
            if (cs == null) return Result.<SentenceResult>fail(Code.NOT_FOUND, "Case vanished.");
            if (!(cs.status() == CaseStatus.GUILTY || cs.status() == CaseStatus.AWAITING_SENTENCING)) {
                return Result.<SentenceResult>fail(Code.STATE, "The case is no longer awaiting sentencing.");
            }
            long now = core.clock.now();
            Target target = new Target(cs.accusedUuid(), cs.accusedName(), false);
            long insertDuration = type.canBeTimed() ? (pl.remainingMs() > 0 || pl.durationMs() == Durations.PERMANENT ? pl.remainingMs() : 1) : 0;
            if (pl.durationMs() == Durations.PERMANENT) insertDuration = Durations.PERMANENT;
            Actor who = new Actor(pl.actorUuid() == null ? null : UUID.fromString(pl.actorUuid()), pl.actorName(), Actor.Kind.PLAYER, actor.perms());
            Long custodyForRow = type == PunishType.JAIL ? pl.custodyId() : null;
            Result<Punishment> ins = punishments.insert(c, who, type, target, insertDuration, pl.reason(), Source.JUDICIAL, cs.id(), cs.offenseKey(),
                    null, custodyForRow, true);
            if (!ins.isOk()) return ins.<SentenceResult>cast();
            Punishment p = ins.value();
            boolean fullyCredited = type.canBeTimed() && pl.durationMs() != Durations.PERMANENT && pl.remainingMs() <= 0;
            if (type == PunishType.JAIL && pl.custodyId() != null) {
                if (fullyCredited) {
                    Repo.Punishments.end(c, p.id(), PunishStatus.SERVED, "System", "Sentence fully served in pretrial custody (time-served credit)", now);
                } else {
                    Repo.Custodies.markServing(c, pl.custodyId(), pl.jailName(), pl.jailPrisonerId(), now, pl.newJail() ? now : null);
                }
            } else if (fullyCredited) {
                Repo.Punishments.end(c, p.id(), PunishStatus.SERVED, "System", "Fully served in pretrial custody (time-served credit)", now);
            }
            Repo.Cases.setStatus(c, cs.id(), cs.version(), cs.status(), CaseStatus.SENTENCED, now);
            Repo.Events.add(c, cs.id(), now, who.dbUuid(), who.name(), "SENTENCED", cs.status(), CaseStatus.SENTENCED,
                    type.display() + (type.canBeTimed() ? " " + Durations.format(pl.durationMs()) : "")
                            + (pl.creditMs() > 0 ? " (credit " + Durations.format(pl.creditMs()) + ")" : ""));
            audit(c, who, "sentence", cs.accusedUuid(), cs.id(), "punishment=" + p.id() + " " + type + " " + Durations.format(pl.durationMs()), true);
            if (opId != 0) Repo.Ops.setState(c, opId, OpState.DONE, null, now);
            out[0] = Repo.Punishments.get(c, p.id());
            return Result.ok(new SentenceResult(Repo.Cases.get(c, cs.id()), out[0], pl.creditMs()));
        }).thenCompose(res -> {
            if (!res.isOk()) {
                if (opId != 0) {
                    core.log.severe("Sentence for case " + pl.caseId() + " was applied in JailReloaded but could not be recorded (" + res.message()
                            + "). The pending operation will be completed automatically at the next start.");
                }
                return done(res);
            }
            Punishment p = res.value().punishment();
            return punishments.afterIssue(p, false).thenApply(v -> {
                Case cs = res.value().c();
                core.notifications.defendant(cs, "sentence", type.display() + (type.canBeTimed() ? " (" + Durations.format(pl.durationMs()) + ")" : "")
                        + (core.settings.defendant.includeReasoning() ? " - " + Text.truncate(pl.reason(), 300) : ""), String.valueOf(p.id()));
                core.emit(DomainEvent.of(DomainEvent.Type.SENTENCE).key("sentence:" + p.id()).caseId(cs.id()).player(cs.accusedName()).actor(pl.actorName())
                        .summary(type.display() + (type.canBeTimed() ? " (" + Durations.format(pl.durationMs()) + ")" : ""))
                        .field("Sentence", type.display()).field("Duration", type.canBeTimed() ? Durations.format(pl.durationMs()) : null)
                        .field("Time served credit", pl.creditMs() > 0 ? Durations.format(pl.creditMs()) : null)
                        .field("Reasoning", Text.truncate(pl.reason(), 300)).punishment(p, "issued", 0).build());
                return res;
            });
        });
    }

    // ------------------------------------------------------------------ appeals
    public CompletableFuture<Result<Case>> fileAppeal(Actor actor, long caseId, String reason) {
        return fileAppeal(actor, caseId, reason, null);
    }

    public CompletableFuture<Result<Case>> fileAppeal(Actor actor, long caseId, String reason, String ticketRaw) {
        String ticketClean = ticketRaw == null || ticketRaw.isBlank() ? null : cleanTicket(ticketRaw);
        if (ticketRaw != null && !ticketRaw.isBlank() && ticketClean == null) return done(Result.fail(Code.INVALID, "That does not look like a ticket number or a Discord ticket link."));
        Result<Void> perm = core.auth.require(actor, Perms.APPEAL_FILE, "file appeals");
        if (!perm.isOk()) return done(perm.cast());
        String problem = reasonCheck(reason, core.settings.cases.minReason());
        if (problem != null) return done(Result.fail(Code.INVALID, problem));
        String why = Text.clean(reason);
        return db(c -> {
            Result<Case> lc = loadFor(c, actor, caseId);
            if (!lc.isOk()) return lc;
            Case cs = lc.value();
            if (!(cs.status() == CaseStatus.SENTENCED || cs.status() == CaseStatus.DIRECT_PUNISHMENT_ISSUED)) {
                return Result.<Case>fail(Code.STATE, "Only a sentenced case or a direct punishment can be appealed.");
            }
            if (Repo.Appeals.pendingForCase(c, caseId) != null) return Result.<Case>fail(Code.DUPLICATE, "There is already a pending appeal on this case.");
            long now = core.clock.now();
            Repo.Appeals.add(c, caseId, actor.name(), why, now);
            Repo.Cases.setStatus(c, caseId, cs.version(), cs.status(), CaseStatus.APPEALED, now);
            if (ticketClean != null) Repo.Cases.setAppealTicket(c, caseId, ticketClean, now);
            Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "APPEAL_FILED", cs.status(), CaseStatus.APPEALED, Text.truncate(why, 250));
            audit(c, actor, "appeal.file", cs.accusedUuid(), caseId, Text.truncate(why, 120), false);
            return Result.ok(Repo.Cases.get(c, caseId));
        }).thenApply(res -> {
            if (res.isOk()) {
                core.emit(DomainEvent.of(DomainEvent.Type.APPEAL).key("appeal-filed:" + caseId + ":" + res.value().version()).caseId(caseId).player(res.value().accusedName()).actor(actor.name())
                        .summary("Appeal filed").field("Grounds", Text.truncate(why, 300)).field("Ticket", ticketClean).build());
            }
            return res;
        });
    }

    /** Ticket Tool (Discord) ticket reference: a ticket number/label or a discord.com/channels link. Nothing is fetched from Discord. */
    public static String cleanTicket(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.matches("#?[A-Za-z0-9][A-Za-z0-9_\\-]{0,39}")) return t.startsWith("#") ? t.substring(1) : t;
        if (t.length() <= 200 && t.matches("https://(?:[a-z]+\\.)?discord(?:app)?\\.com/channels/\\d+/\\d+(?:/\\d+)?/?")) return t;
        return null;
    }

    /** Attach (or replace) the Discord ticket used for this case's appeal. Routed to the appeal log channel. */
    public CompletableFuture<Result<Case>> linkAppealTicket(Actor actor, long caseId, String ticketRaw) {
        Result<Void> perm = core.auth.requireAny(actor, "link appeal tickets", Perms.APPEAL_TICKET, Perms.JUDICIAL_OVERTURN, Perms.APPEAL_DECIDE);
        if (!perm.isOk()) return done(perm.cast());
        String ticket = cleanTicket(ticketRaw);
        if (ticket == null) return done(Result.fail(Code.INVALID, "Enter the Ticket Tool ticket number (for example 0042 or appeal-0042) or the ticket channel link (https://discord.com/channels/...)."));
        return db(c -> {
            Result<Case> lc = loadFor(c, actor, caseId);
            if (!lc.isOk()) return lc;
            Case cs = lc.value();
            long now = core.clock.now();
            Repo.Cases.setAppealTicket(c, caseId, ticket, now);
            Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "APPEAL_TICKET", null, null, ticket);
            audit(c, actor, "appeal.ticket", cs.accusedUuid(), caseId, "ticket=" + ticket, false);
            return Result.ok(Repo.Cases.get(c, caseId));
        }).thenApply(res -> {
            if (res.isOk()) {
                core.emit(DomainEvent.of(DomainEvent.Type.APPEAL_TICKET).key("appeal-ticket:" + caseId + ":" + ticket).caseId(caseId)
                        .player(res.value().accusedName()).actor(actor.name()).summary("Appeal ticket linked").field("Ticket", ticket).build());
            }
            return res;
        });
    }

    /** A reviewer takes the pending appeal (status "Under Review"). */
    public CompletableFuture<Result<Case>> takeAppeal(Actor actor, long caseId) {
        Result<Void> perm = core.auth.requireAny(actor, "review appeals", Perms.JUDICIAL_OVERTURN, Perms.APPEAL_DECIDE);
        if (!perm.isOk()) return done(perm.cast());
        return db(c -> {
            Result<Case> lc = loadFor(c, actor, caseId);
            if (!lc.isOk()) return lc;
            Appeal ap = Repo.Appeals.pendingForCase(c, caseId);
            if (ap == null) return Result.<Case>fail(Code.STATE, "No open appeal was found on that case.");
            if (ap.status() == AppealStatus.UNDER_REVIEW) return Result.<Case>fail(Code.STATE, "That appeal is already under review by " + ap.assignedName() + ".");
            Repo.Appeals.setStatus(c, ap.id(), AppealStatus.UNDER_REVIEW, actor.name());
            Repo.Events.add(c, caseId, core.clock.now(), actor.dbUuid(), actor.name(), "APPEAL_UNDER_REVIEW", null, null, null);
            audit(c, actor, "appeal.take", lc.value().accusedUuid(), caseId, "appeal=" + ap.id(), false);
            return Result.ok(Repo.Cases.get(c, caseId));
        }).thenApply(res -> {
            if (res.isOk()) core.emit(DomainEvent.of(DomainEvent.Type.APPEAL).key("appeal-taken:" + caseId + ":" + core.clock.now()).caseId(caseId)
                    .player(res.value().accusedName()).actor(actor.name()).summary("Appeal is now under review").build());
            return res;
        });
    }

    public CompletableFuture<Result<Case>> decideAppeal(Actor actor, long caseId, AppealStatus decision, String reasoning) {
        Result<Void> perm = core.auth.requireAny(actor, "decide appeals", Perms.JUDICIAL_OVERTURN, Perms.APPEAL_DECIDE);
        if (!perm.isOk()) return done(perm.cast());
        if (decision == AppealStatus.PENDING || decision == AppealStatus.UNDER_REVIEW) return done(Result.fail(Code.INVALID, "Choose a decision."));
        String problem = reasonCheck(reasoning, core.settings.judicial.minVerdictReason());
        if (problem != null) return done(Result.fail(Code.INVALID, problem));
        String why = Text.clean(reasoning);
        List<Punishment> revoked = new ArrayList<>();
        return db(c -> {
            Result<Case> lc = loadFor(c, actor, caseId);
            if (!lc.isOk()) return lc;
            Case cs = lc.value();
            if (cs.status() != CaseStatus.APPEALED) return Result.<Case>fail(Code.STATE, "That case has no appeal under consideration.");
            Appeal ap = Repo.Appeals.pendingForCase(c, caseId);
            if (ap == null) return Result.<Case>fail(Code.STATE, "No pending appeal was found.");
            if (decision == AppealStatus.UNDER_REVIEW) return Result.<Case>fail(Code.INVALID, "Choose upheld, overturned or modified.");
            long now = core.clock.now();
            Repo.Appeals.decide(c, ap.id(), decision, actor.name(), why, now);
            CaseStatus to;
            switch (decision) {
                case UPHELD -> to = cs.kind() == CaseKind.DIRECT ? CaseStatus.DIRECT_PUNISHMENT_ISSUED : CaseStatus.SENTENCED;
                case OVERTURNED -> {
                    to = CaseStatus.NOT_GUILTY;
                    revoked.addAll(revokeAll(c, actor, cs, "Appeal overturned: " + Text.truncate(why, 100), now));
                    Repo.Cases.markOverturned(c, caseId, now);
                }
                default -> { // MODIFIED
                    revoked.addAll(revokeAll(c, actor, cs, "Appeal: sentence modified, to be re-imposed: " + Text.truncate(why, 80), now));
                    to = cs.kind() == CaseKind.DIRECT || cs.verdict() != Verdict.GUILTY ? CaseStatus.UNDER_REVIEW : CaseStatus.GUILTY;
                }
            }
            Case fresh = Repo.Cases.get(c, caseId);
            if (!Repo.Cases.setStatus(c, caseId, fresh.version(), CaseStatus.APPEALED, to, now)) {
                return Result.<Case>fail(Code.CONFLICT, "This case was changed by someone else.");
            }
            Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "APPEAL_" + decision.name(), CaseStatus.APPEALED, to, Text.truncate(why, 250));
            audit(c, actor, "appeal.decide", cs.accusedUuid(), caseId, decision + " " + Text.truncate(why, 100), true);
            return Result.ok(Repo.Cases.get(c, caseId));
        }).thenCompose(res -> {
            if (!res.isOk()) return done(res);
            return afterRevocations(actor, revoked, "appeal decision").thenApply(v -> {
                core.emit(DomainEvent.of(DomainEvent.Type.APPEAL).caseId(caseId).player(res.value().accusedName()).actor(actor.name())
                        .summary("Appeal " + decision.name().toLowerCase()).key("appeal-decided:" + caseId + ":" + res.value().version()).field("Decision", decision.name()).field("Reasoning", Text.truncate(why, 300)).build());
                return res;
            });
        });
    }

    // ------------------------------------------------------------------ overturn
    public CompletableFuture<Result<Case>> overturn(Actor actor, long caseId, String reasoning) {
        Result<Void> perm = core.auth.require(actor, Perms.JUDICIAL_OVERTURN, "overturn findings");
        if (!perm.isOk()) return done(perm.cast());
        String problem = reasonCheck(reasoning, core.settings.judicial.minVerdictReason());
        if (problem != null) return done(Result.fail(Code.INVALID, problem));
        String why = Text.clean(reasoning);
        List<Punishment> revoked = new ArrayList<>();
        return db(c -> {
            Result<Case> lc = loadFor(c, actor, caseId);
            if (!lc.isOk()) return lc;
            Case cs = lc.value();
            if (!(cs.status() == CaseStatus.SENTENCED || cs.status() == CaseStatus.DIRECT_PUNISHMENT_ISSUED
                    || cs.status() == CaseStatus.UNDER_REVIEW || cs.status() == CaseStatus.APPEALED)) {
                return Result.<Case>fail(Code.STATE, "Only a sentenced case, direct punishment, or case under review can be overturned.");
            }
            long now = core.clock.now();
            revoked.addAll(revokeAll(c, actor, cs, "Overturned: " + Text.truncate(why, 100), now));
            Repo.Cases.markOverturned(c, caseId, now);
            Case fresh = Repo.Cases.get(c, caseId);
            if (!Repo.Cases.setStatus(c, caseId, fresh.version(), cs.status(), CaseStatus.NOT_GUILTY, now)) {
                return Result.<Case>fail(Code.CONFLICT, "This case was changed by someone else.");
            }
            Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "OVERTURNED", cs.status(), CaseStatus.NOT_GUILTY, Text.truncate(why, 250));
            audit(c, actor, "case.overturn", cs.accusedUuid(), caseId, Text.truncate(why, 120), true);
            return Result.ok(Repo.Cases.get(c, caseId));
        }).thenCompose(res -> {
            if (!res.isOk()) return done(res);
            return afterRevocations(actor, revoked, "overturn").thenApply(v -> {
                core.emit(DomainEvent.of(DomainEvent.Type.REVERSAL).caseId(caseId).player(res.value().accusedName()).actor(actor.name())
                        .summary("Findings overturned; " + revoked.size() + " punishment(s) revoked").field("Reasoning", Text.truncate(why, 300)).build());
                return res;
            });
        });
    }

    private List<Punishment> revokeAll(Connection c, Actor actor, Case cs, String reason, long now) throws SQLException {
        List<Punishment> ended = new ArrayList<>();
        for (Punishment p : Repo.Punishments.forCase(c, cs.id())) {
            if (p.status() == PunishStatus.ACTIVE || p.status() == PunishStatus.COMPLETED) {
                if (Repo.Punishments.end(c, p.id(), PunishStatus.REVOKED, actor.name(), reason, now)) ended.add(p);
            }
        }
        return ended;
    }

    private CompletableFuture<Void> afterRevocations(Actor actor, List<Punishment> revoked, String why) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Punishment p : revoked) {
            punishments.cache().remove(p);
            if (p.type() == PunishType.MUTE) core.main.run(() -> core.enforcement.noticeUnmute(p.targetUuid()));
            if (p.type() == PunishType.JAIL) {
                chain = chain.thenCompose(v -> custody.releaseForPunishment(actor, p, "Punishment revoked by " + why)
                        .thenAccept(r -> {
                            if (!r.isOk()) core.log.warning("Jail release after " + why + " failed: " + r.message());
                        }));
            }
        }
        return chain;
    }

    // ------------------------------------------------------------------ crash recovery for SENTENCE operations
    /** Complete or abandon SENTENCE operations that were interrupted by a crash or restart. */
    public CompletableFuture<Void> resumeOps() {
        return core.db.tx(c -> Repo.Ops.unfinished(c)).thenCompose(ops -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (PendingOp op : ops) {
                if (!op.kind().equals("SENTENCE")) continue;
                chain = chain.thenCompose(v -> resumeOne(op));
            }
            return chain;
        }).exceptionally(t -> {
            core.log.warning("Resuming interrupted sentences failed: " + t.getMessage());
            return null;
        });
    }

    private CompletableFuture<Void> resumeOne(PendingOp op) {
        SentencePayload pl;
        try {
            pl = GSON.fromJson(op.payload(), SentencePayload.class);
        } catch (RuntimeException e) {
            return abandon(op, "Unreadable payload");
        }
        if (op.state() == OpState.EXTERNAL_DONE) {
            core.log.info("Completing interrupted sentence for case " + pl.caseId() + " (JailReloaded step already done).");
            return finalizeSentence(Actor.system(), pl, op.id(), 0).thenAccept(r -> {
                if (!r.isOk()) core.log.warning("Could not complete interrupted sentence for case " + pl.caseId() + ": " + r.message());
            });
        }
        // PENDING: the JailReloaded step may or may not have happened. Check reality.
        return core.main.call(() -> core.courtJail().find(UUID.fromString(op.uuid().toString()))).thenCompose(found -> {
            boolean applied = found.isPresent() && found.get().reason() != null && found.get().reason().contains(CustodyService.tag(pl.caseId()))
                    && (pl.newJail() || Math.abs(found.get().remainingMs() - pl.remainingMs()) < 5 * Durations.MINUTE);
            if (applied) {
                SentencePayload withIds = new SentencePayload(pl.caseId(), pl.type(), pl.durationMs(), pl.remainingMs(), pl.creditMs(),
                        pl.reason(), pl.actorName(), pl.actorUuid(), pl.custodyId(), found.get().jailPrisonerId(), found.get().jailName(), pl.newJail());
                core.log.info("Interrupted sentence for case " + pl.caseId() + " was applied in JailReloaded; recording it now.");
                return finalizeSentence(Actor.system(), withIds, op.id(), 0).thenAccept(r -> { });
            }
            return abandon(op, "Interrupted before JailReloaded applied it; the case remains awaiting sentencing").thenCompose(v ->
                    pl.newJail() && pl.custodyId() != null ? core.db.tx(c -> {
                        Repo.Custodies.end(c, pl.custodyId(), CustodyStatus.FAILED, "System", "Sentence interrupted", core.clock.now());
                        return null;
                    }).handle((x, ex) -> (Void) null) : CompletableFuture.<Void>completedFuture(null));
        });
    }

    private CompletableFuture<Void> abandon(PendingOp op, String why) {
        return core.db.tx(c -> {
            Repo.Ops.setState(c, op.id(), OpState.FAILED, why, core.clock.now());
            return null;
        }).handle((v, ex) -> null);
    }
}
