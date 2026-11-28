package dev.aura.punishments.service;

import dev.aura.punishments.config.Offense;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.db.Sql;
import dev.aura.punishments.model.CaseStatus;
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

/** Case lifecycle: creation, evidence, notes, assignments, scheduling and validated status changes. */
public final class CaseService extends Svc {
    private final CustodyService custody;

    public record CreateRequest(Target target, String offenseKey, Severity severity, String description,
                                String context, List<EvidenceValidator.Input> evidence) {}

    public record Detail(Case c, List<CaseEvent> events, List<Evidence> evidence, List<Note> notes, Custody custody,
                         List<Punishment> punishments, List<Appeal> appeals, String directJustification,
                         long priorFindings, boolean evidenceHidden, boolean notesHidden) {}

    public record Overview(Profile profile, boolean online, List<Case> openCases, long totalCases,
                           List<Punishment> active, Custody custody, long totalPunishments, long priorFindings,
                           long noteCount) {}

    public record Page<T>(List<T> items, long total, int page, int pageSize) {
        public int pages() {
            return (int) Math.max(1, (total + pageSize - 1) / pageSize);
        }
    }

    /** The only statuses staff move a case between in the case workflow (no court steps). */
    private static final java.util.Set<CaseStatus> CASEWORK_STATUSES = java.util.EnumSet.of(CaseStatus.OPEN, CaseStatus.UNDER_INVESTIGATION,
            CaseStatus.UNDER_REVIEW, CaseStatus.CLOSED);

    public enum Filter { ALL, OPEN, PENDING_TRIAL, AWAITING_VERDICT, AWAITING_SENTENCE, MINE, APPEALS }

    public CaseService(Core core, CustodyService custody) {
        super(core);
        this.custody = custody;
    }

    // ------------------------------------------------------------------ create
    /** A case request that passed every check that does not need the database; the same checks serve "open a case" and "open a case and punish". */
    record Prepared(Offense offense, CaseKind kind, String description, String context, List<EvidenceValidator.Input> evidence) {}

    Result<Prepared> prepare(Actor actor, CreateRequest r) {
        Result<Prepared> off0 = core.mode.refuseNewCase();
        if (off0 != null) return off0;
        Settings s = core.settings;
        boolean full = core.auth.has(actor, Perms.CASE_CREATE);
        boolean report = core.auth.has(actor, Perms.REPORT);
        if (!full && !report) return Result.fail(Code.NO_PERMISSION, "You do not have permission to create cases or reports.");
        Result<Void> access = targetAccess(actor);
        if (!access.isOk()) return access.cast();
        Optional<Offense> off = core.offenses.get(r.offenseKey());
        if (off.isEmpty()) return Result.fail(Code.INVALID, "Unknown offense category.");
        Offense offense = off.get();
        if (!offense.permission().isBlank() && !core.auth.has(actor, offense.permission())) {
            return Result.fail(Code.NO_PERMISSION, "You do not have permission to charge '" + offense.display() + "'.");
        }
        if (r.severity() == null) return Result.fail(Code.INVALID, "Choose a severity.");
        String desc = Text.clean(r.description());
        if (desc.length() < s.cases.minDescription()) {
            return Result.fail(Code.INVALID, "The incident description must be at least " + s.cases.minDescription() + " characters.");
        }
        if (desc.length() > s.cases.maxDescription()) {
            return Result.fail(Code.INVALID, "The incident description is too long (max " + s.cases.maxDescription() + ").");
        }
        String context = Text.truncate(Text.clean(r.context()), s.cases.maxNote());
        List<EvidenceValidator.Input> evidence = new ArrayList<>();
        for (EvidenceValidator.Input in : r.evidence() == null ? List.<EvidenceValidator.Input>of() : r.evidence()) {
            Result<EvidenceValidator.Input> v = EvidenceValidator.validate(s, in);
            if (!v.isOk()) return v.cast();
            evidence.add(v.value());
        }
        int need = Math.max(offense.minEvidenceCase(), s.cases.requireEvidenceOnCreate() ? 1 : 0);
        if (evidence.size() < need) {
            return Result.fail(Code.EVIDENCE, "'" + offense.display() + "' needs at least " + need + " evidence reference(s) to open a case.");
        }
        if (evidence.size() > s.cases.maxEvidence()) return Result.fail(Code.EVIDENCE, "Too many evidence items.");
        return Result.ok(new Prepared(offense, full ? CaseKind.STANDARD : CaseKind.REPORT, desc, context, evidence));
    }

    /** The database half of opening a case; runs inside the caller's transaction (so a punishment can be issued in the same one). */
    Result<Case> insertTx(Connection c, Actor actor, CreateRequest r, Prepared p) throws SQLException {
        Settings s = core.settings;
        Offense offense = p.offense();
        long now = core.clock.now();
        Long dup = Sql.one(c, "SELECT id FROM cases WHERE accused_uuid=? AND offense_key=? AND created_at>? AND status IN ('OPEN','UNDER_INVESTIGATION','AWAITING_TRIAL','IN_TRIAL','AWAITING_VERDICT') ORDER BY id DESC LIMIT 1",
                rs -> rs.getLong(1), r.target().uuid(), offense.key(), now - s.cases.duplicateWindow());
        if (dup != null) {
            return Result.<Case>fail(Code.DUPLICATE, "An open case for the same player and offense already exists (" + "AP-" + String.format("%04d", dup)
                    + "). Add evidence or notes to it, or punish from it, instead.");
        }
        long id = Repo.Cases.insert(c, CaseStatus.OPEN, p.kind(), r.target().uuid(), r.target().name(), offense.key(),
                r.severity(), p.description(), p.context().isEmpty() ? null : p.context(), actor.dbUuid(), actor.name(), now);
        Repo.Events.add(c, id, now, actor.dbUuid(), actor.name(), "CREATED", null, CaseStatus.OPEN,
                offense.display() + " / " + r.severity());
        for (EvidenceValidator.Input in : p.evidence()) {
            Repo.Evidences.add(c, id, in.type(), in.reference(), in.description(), actor.dbUuid(), actor.name(), now, null);
        }
        audit(c, actor, "case.create", r.target().uuid(), id, offense.key() + " " + r.severity() + " evidence=" + p.evidence().size(), false);
        return Result.ok(Repo.Cases.get(c, id));
    }

    /** Discord / staff event for a newly opened case. */
    void emitCreated(Actor actor, Case cs, Offense offense) {
        core.emit(DomainEvent.of(DomainEvent.Type.CASE_CREATED).caseId(cs.id()).player(cs.accusedName()).actor(actor.name())
                .summary(offense.display()).field("Offense", offense.display()).field("Severity", cs.severity().name())
                .field("Description", Text.truncate(cs.description(), 300)).build());
    }

    public CompletableFuture<Result<Case>> create(Actor actor, CreateRequest r) {
        Result<Prepared> prep = prepare(actor, r);
        if (!prep.isOk()) return done(prep.cast());
        Prepared p = prep.value();
        return checkTarget(actor, r.target().uuid()).thenCompose(tc -> {
            if (!tc.isOk()) return done(tc.<Case>cast());
            return db(c -> insertTx(c, actor, r, p));
        }).thenApply(res -> {
            if (res.isOk()) emitCreated(actor, res.value(), p.offense());
            return res;
        });
    }

    private Result<Case> loadVisible(Connection c, Actor actor, long id) throws SQLException {
        Case cs = Repo.Cases.get(c, id);
        if (cs == null) return Result.fail(Code.NOT_FOUND, "Case " + id + " does not exist.");
        if (!core.auth.canViewCase(actor, cs)) return Result.fail(Code.NO_PERMISSION, "You do not have permission to view that case.");
        return Result.ok(cs);
    }

    public CompletableFuture<Result<Detail>> detail(Actor actor, long id) {
        if (!core.auth.canViewAnyCase(actor)) return done(Result.fail(Code.NO_PERMISSION, "You do not have permission to view cases."));
        return db(c -> {
            Result<Case> lc = loadVisible(c, actor, id);
            if (!lc.isOk()) return lc.<Detail>cast();
            Case cs = lc.value();
            boolean seeEvidence = core.auth.has(actor, Perms.EVIDENCE_VIEW);
            boolean seeNotes = core.auth.has(actor, Perms.NOTE_VIEW);
            List<Note> notes = new ArrayList<>();
            if (seeNotes) {
                boolean sensitive = core.auth.has(actor, Perms.SENSITIVE_NOTES);
                for (Note n : Repo.Notes.forCase(c, id)) if (!n.sensitive() || sensitive) notes.add(n);
            }
            long priors = Repo.Cases.priorFindings(c, cs.accusedUuid(), core.settings.escalation.categoryScope() ? cs.offenseKey() : null,
                    cs.id(), core.settings.escalation.countDirect());
            return Result.ok(new Detail(cs, Repo.Events.forCase(c, id),
                    seeEvidence ? Repo.Evidences.forCase(c, id, true) : List.of(), notes,
                    Repo.Custodies.forCase(c, id).stream().findFirst().orElse(null),
                    Repo.Punishments.forCase(c, id), Repo.Appeals.forCase(c, id), Repo.Directs.justification(c, id),
                    priors, !seeEvidence, !seeNotes));
        });
    }

    /** Everything the /punish GUI header needs for one target. */
    public CompletableFuture<Result<Overview>> overview(Actor actor, Target t) {
        Result<Void> perm = core.auth.require(actor, Perms.PUNISH, "open the punishment GUI");
        if (!perm.isOk()) return done(perm.cast());
        Result<Void> access = targetAccess(actor);
        if (!access.isOk()) return done(access.cast());
        if (core.settings.protections.blockSelf() && actor.uuid() != null && actor.uuid().equals(t.uuid()) && !actor.isPrivileged()
                && !actor.has(Perms.IMMUNITY_BYPASS)) {
            return done(Result.fail(Code.HIERARCHY, "You cannot open your own punishment screen."));
        }
        return db(c -> {
            Profile pr = Repo.Profiles.get(c, t.uuid());
            boolean viewCases = core.auth.canViewAnyCase(actor);
            boolean viewHistory = core.auth.has(actor, Perms.HISTORY_VIEW);
            List<Case> open = new ArrayList<>();
            if (viewCases) {
                for (Case cs : Repo.Cases.forAccused(c, t.uuid(), 50, 0)) {
                    if (cs.status().isOpenForWork() && core.auth.canViewCase(actor, cs)) open.add(cs);
                    if (open.size() >= 5) break;
                }
            }
            long priors = viewHistory ? Repo.Cases.priorFindings(c, t.uuid(), null, -1, core.settings.escalation.countDirect()) : 0;
            return Result.ok(new Overview(pr, t.online(), open,
                    viewHistory ? Repo.Cases.countForAccused(c, t.uuid()) : 0,
                    Repo.Punishments.activeFor(c, t.uuid()),
                    Repo.Custodies.liveForPlayer(c, t.uuid()),
                    viewHistory ? Repo.Punishments.countForTarget(c, t.uuid()) : 0, priors,
                    core.auth.has(actor, Perms.NOTE_VIEW) ? Sql.count(c, "SELECT COUNT(*) FROM notes WHERE profile_uuid=? AND voided=0", t.uuid()) : 0));
        });
    }

    public CompletableFuture<Result<Page<Case>>> list(Actor actor, Filter f, int page, int pageSize) {
        if (!core.auth.canViewAnyCase(actor)) return done(Result.fail(Code.NO_PERMISSION, "You do not have permission to view cases."));
        boolean all = core.auth.has(actor, Perms.CASE_VIEW_ALL);
        return db(c -> {
            int offset = page * pageSize;
            UUID me = actor.dbUuid();
            if (!all) {
                if (me == null) return Result.<Page<Case>>ok(new Page<>(List.of(), 0, page, pageSize));
                List<Case> mine = Repo.Cases.involving(c, me, pageSize, offset);
                long total = Sql.count(c, "SELECT COUNT(*) FROM cases WHERE prosecutor_uuid=? OR judge_uuid=?", me, me);
                return Result.ok(new Page<>(mine, total, page, pageSize));
            }
            List<CaseStatus> statuses = switch (f) {
                case OPEN -> List.of(CaseStatus.OPEN, CaseStatus.UNDER_INVESTIGATION, CaseStatus.UNDER_REVIEW);
                case PENDING_TRIAL -> List.of(CaseStatus.AWAITING_TRIAL, CaseStatus.IN_TRIAL);
                case AWAITING_VERDICT -> List.of(CaseStatus.AWAITING_VERDICT, CaseStatus.IN_TRIAL);
                case AWAITING_SENTENCE -> List.of(CaseStatus.GUILTY, CaseStatus.AWAITING_SENTENCING);
                case APPEALS -> List.of(CaseStatus.APPEALED);
                default -> null;
            };
            if (f == Filter.MINE) {
                if (me == null) return Result.<Page<Case>>ok(new Page<>(List.of(), 0, page, pageSize));
                return Result.ok(new Page<>(Repo.Cases.involving(c, me, pageSize, offset),
                        Sql.count(c, "SELECT COUNT(*) FROM cases WHERE prosecutor_uuid=? OR judge_uuid=?", me, me), page, pageSize));
            }
            if (statuses == null) return Result.ok(new Page<>(Repo.Cases.all(c, pageSize, offset), Repo.Cases.countAll(c), page, pageSize));
            return Result.ok(new Page<>(Repo.Cases.byStatuses(c, statuses, pageSize, offset), Repo.Cases.countByStatuses(c, statuses), page, pageSize));
        });
    }

    public CompletableFuture<Result<Page<Case>>> historyFor(Actor actor, UUID uuid, int page, int pageSize) {
        if (!core.auth.has(actor, Perms.HISTORY_VIEW)) return done(Result.fail(Code.NO_PERMISSION, "You do not have permission to view history."));
        return db(c -> Result.ok(new Page<>(Repo.Cases.forAccused(c, uuid, pageSize, page * pageSize), Repo.Cases.countForAccused(c, uuid), page, pageSize)));
    }

    /** Cases about one player (visible to the actor), newest first. */
    public CompletableFuture<Result<Page<Case>>> historyForCases(Actor actor, UUID uuid, int page, int pageSize) {
        if (!core.auth.canViewAnyCase(actor)) return done(Result.fail(Code.NO_PERMISSION, "You do not have permission to view cases."));
        return db(c -> {
            List<Case> visible = new ArrayList<>();
            for (Case cs : Repo.Cases.forAccused(c, uuid, 500, 0)) if (core.auth.canViewCase(actor, cs)) visible.add(cs);
            int from = Math.min(visible.size(), page * pageSize);
            int to = Math.min(visible.size(), from + pageSize);
            return Result.ok(new Page<>(new ArrayList<>(visible.subList(from, to)), visible.size(), page, pageSize));
        });
    }

    /** Open (still being worked) cases about one player, for "pick a case" steps. */
    public CompletableFuture<Result<Page<Case>>> openCasesFor(Actor actor, UUID uuid, int page, int pageSize) {
        if (!core.auth.canViewAnyCase(actor)) return done(Result.fail(Code.NO_PERMISSION, "You do not have permission to view cases."));
        return db(c -> {
            List<Case> visible = new ArrayList<>();
            for (Case cs : Repo.Cases.forAccused(c, uuid, 500, 0)) {
                if (cs.status().isOpenForWork() && core.auth.canViewCase(actor, cs)) visible.add(cs);
            }
            int from = Math.min(visible.size(), page * pageSize);
            int to = Math.min(visible.size(), from + pageSize);
            return Result.ok(new Page<>(new ArrayList<>(visible.subList(from, to)), visible.size(), page, pageSize));
        });
    }

    /**
     * How many earlier "strikes" the player has, for the offense recommendation. With the judicial system a strike is a guilty finding;
     * in the case workflow there are no findings, so it is a punishment that was issued from a case and not revoked (kicks do not count).
     */
    public CompletableFuture<Result<Long>> priors(UUID accused, String offenseKey, long excludeCase) {
        String scope = core.settings.escalation.categoryScope() ? offenseKey : null;
        if (core.mode.casework()) return db(c -> Result.ok(Repo.Punishments.countCasePunishments(c, accused, scope, excludeCase)));
        return db(c -> Result.ok(Repo.Cases.priorFindings(c, accused, scope, excludeCase, core.settings.escalation.countDirect())));
    }

    public CompletableFuture<Result<List<Note>>> profileNotes(Actor actor, UUID uuid) {
        Result<Void> p = core.auth.require(actor, Perms.NOTE_VIEW, "view staff notes");
        if (!p.isOk()) return done(p.cast());
        boolean sensitive = core.auth.has(actor, Perms.SENSITIVE_NOTES);
        return db(c -> {
            List<Note> out = new ArrayList<>();
            for (Note n : Repo.Notes.forProfile(c, uuid)) if (!n.voided() && (!n.sensitive() || sensitive)) out.add(n);
            return Result.ok(out);
        });
    }

    // ------------------------------------------------------------------ evidence
    public CompletableFuture<Result<Evidence>> addEvidence(Actor actor, long caseId, EvidenceValidator.Input input) {
        Result<Void> perm = core.auth.require(actor, Perms.EVIDENCE_ADD, "submit evidence");
        if (!perm.isOk()) return done(perm.cast());
        Result<EvidenceValidator.Input> v = EvidenceValidator.validate(core.settings, input);
        if (!v.isOk()) return done(v.cast());
        EvidenceValidator.Input in = v.value();
        return db(c -> {
            Result<Case> lc = loadVisible(c, actor, caseId);
            if (!lc.isOk()) return lc.<Evidence>cast();
            Case cs = lc.value();
            if (!cs.status().acceptsEvidence()) return Result.<Evidence>fail(Code.STATE, "Case " + cs.ref() + " is closed; reopen it before adding evidence.");
            if (Repo.Evidences.countActive(c, caseId) >= core.settings.cases.maxEvidence()) {
                return Result.<Evidence>fail(Code.LIMIT, "This case already has the maximum number of evidence items.");
            }
            long now = core.clock.now();
            long id = Repo.Evidences.add(c, caseId, in.type(), in.reference(), in.description(), actor.dbUuid(), actor.name(), now, null);
            Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "EVIDENCE_ADDED", null, null, in.type() + " #" + id);
            audit(c, actor, "evidence.add", cs.accusedUuid(), caseId, "evidence=" + id + " type=" + in.type(), false);
            return Result.ok(Repo.Evidences.get(c, id));
        });
    }

    /** Evidence is never deleted: it is voided with a recorded reason (and may be replaced by a corrected item). */
    public CompletableFuture<Result<Evidence>> voidEvidence(Actor actor, long evidenceId, String reason, EvidenceValidator.Input replacement) {
        Result<Void> perm = core.auth.require(actor, Perms.EVIDENCE_VOID, "void evidence");
        if (!perm.isOk()) return done(perm.cast());
        String why = Text.clean(reason);
        if (why.length() < core.settings.cases.minReason()) {
            return done(Result.fail(Code.INVALID, "A reason of at least " + core.settings.cases.minReason() + " characters is required."));
        }
        EvidenceValidator.Input rep = null;
        if (replacement != null) {
            Result<EvidenceValidator.Input> v = EvidenceValidator.validate(core.settings, replacement);
            if (!v.isOk()) return done(v.cast());
            rep = v.value();
        }
        final EvidenceValidator.Input repFinal = rep;
        return db(c -> {
            Evidence ev = Repo.Evidences.get(c, evidenceId);
            if (ev == null) return Result.<Evidence>fail(Code.NOT_FOUND, "Evidence #" + evidenceId + " does not exist.");
            Result<Case> lc = loadVisible(c, actor, ev.caseId());
            if (!lc.isOk()) return lc.<Evidence>cast();
            Case cs = lc.value();
            if (ev.voided()) return Result.<Evidence>fail(Code.STATE, "That evidence is already voided.");
            long now = core.clock.now();
            Long newId = null;
            if (repFinal != null) {
                newId = Repo.Evidences.add(c, cs.id(), repFinal.type(), repFinal.reference(), repFinal.description(), actor.dbUuid(), actor.name(), now, evidenceId);
            }
            if ((cs.kind() == CaseKind.DIRECT || cs.kind() == CaseKind.QUICK) && newId == null
                    && Repo.Evidences.countActive(c, cs.id()) <= 1) {
                return Result.<Evidence>fail(Code.EVIDENCE, "A punishment backed by this case must keep at least one evidence item. Provide a replacement instead of voiding it.");
            }
            Repo.Evidences.voidIt(c, evidenceId, actor.dbUuid(), actor.name(), why, now);
            Repo.Events.add(c, cs.id(), now, actor.dbUuid(), actor.name(), "EVIDENCE_VOIDED", null, null,
                    "#" + evidenceId + (newId != null ? " -> corrected as #" + newId : "") + ": " + Text.truncate(why, 150));
            audit(c, actor, "evidence.void", cs.accusedUuid(), cs.id(), "evidence=" + evidenceId + " reason=" + Text.truncate(why, 120), true);
            return Result.ok(Repo.Evidences.get(c, evidenceId));
        });
    }

    // ------------------------------------------------------------------ notes
    public CompletableFuture<Result<Note>> addNote(Actor actor, Long caseId, UUID profile, String text, boolean sensitive) {
        Result<Void> perm = core.auth.require(actor, Perms.NOTE_ADD, "add staff notes");
        if (!perm.isOk()) return done(perm.cast());
        if (sensitive && !core.auth.has(actor, Perms.SENSITIVE_NOTES)) {
            return done(Result.fail(Code.NO_PERMISSION, "You do not have permission to write sensitive notes."));
        }
        String t = Text.clean(text);
        if (t.length() < 3) return done(Result.fail(Code.INVALID, "The note is too short."));
        if (t.length() > core.settings.cases.maxNote()) return done(Result.fail(Code.INVALID, "The note is too long (max " + core.settings.cases.maxNote() + ")."));
        if (caseId == null && profile == null) return done(Result.fail(Code.INVALID, "A note must belong to a case or a player."));
        return db(c -> {
            UUID target = profile;
            if (caseId != null) {
                Result<Case> lc = loadVisible(c, actor, caseId);
                if (!lc.isOk()) return lc.<Note>cast();
                if (!lc.value().status().acceptsEvidence()) return Result.<Note>fail(Code.STATE, "That case is closed.");
                target = lc.value().accusedUuid();
            } else {
                Result<Void> acc = targetAccess(actor);
                if (!acc.isOk()) return acc.<Note>cast();
            }
            long now = core.clock.now();
            long id = Repo.Notes.add(c, caseId, caseId == null ? profile : null, actor.dbUuid(), actor.name(), t, sensitive, now);
            if (caseId != null) Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "NOTE_ADDED", null, null, sensitive ? "sensitive note" : "note");
            audit(c, actor, "note.add", target, caseId, "note=" + id + (sensitive ? " sensitive" : ""), false);
            return Result.ok(Repo.Notes.get(c, id));
        });
    }

    public CompletableFuture<Result<Void>> voidNote(Actor actor, long noteId, String reason) {
        Result<Void> perm = core.auth.require(actor, Perms.NOTE_VOID, "void notes");
        if (!perm.isOk()) return done(perm);
        String why = Text.clean(reason);
        if (why.length() < core.settings.cases.minReason()) {
            return done(Result.fail(Code.INVALID, "A reason of at least " + core.settings.cases.minReason() + " characters is required."));
        }
        return db(c -> {
            Note n = Repo.Notes.get(c, noteId);
            if (n == null || n.voided()) return Result.<Void>fail(Code.NOT_FOUND, "That note does not exist or is already voided.");
            if (n.sensitive() && !core.auth.has(actor, Perms.SENSITIVE_NOTES)) return Result.<Void>fail(Code.NO_PERMISSION, "You cannot modify sensitive notes.");
            Repo.Notes.voidIt(c, noteId, actor.name(), why, core.clock.now());
            audit(c, actor, "note.void", n.profileUuid(), n.caseId(), "note=" + noteId + " reason=" + Text.truncate(why, 120), true);
            return Result.ok();
        });
    }

    // ------------------------------------------------------------------ status changes
    private Result<Void> permitTransition(Actor a, CaseStatus to) {
        Authority au = core.auth;
        return switch (to) {
            case OPEN, UNDER_INVESTIGATION, AWAITING_TRIAL -> au.requireAny(a, "change this case's status", Perms.CASE_MANAGE, Perms.JUDICIAL_MANAGE);
            case IN_TRIAL, AWAITING_VERDICT -> au.require(a, Perms.JUDICIAL_MANAGE, "manage trial proceedings");
            case UNDER_REVIEW -> au.requireAny(a, "place a case under review", Perms.CASE_MANAGE, Perms.JUDICIAL_MANAGE, Perms.EMERGENCY_REVIEW, Perms.JUDICIAL_OVERTURN);
            case CLOSED -> au.requireAny(a, "close cases", Perms.CASE_CLOSE, Perms.JUDICIAL_MANAGE);
            default -> Result.fail(Code.STATE, "That status is set by its own action (verdict, sentence, appeal) and cannot be changed manually.");
        };
    }

    public CompletableFuture<Result<Case>> transition(Actor actor, long caseId, long expectedVersion, CaseStatus to, String reason) {
        if (core.mode.casework() && !CASEWORK_STATUSES.contains(to)) {
            Result<Case> court = core.mode.refuseCourt("'" + to.display() + "'");
            return done(court != null ? court : Result.<Case>fail(Code.UNAVAILABLE, "That status is not available."));
        }
        Result<Void> perm = permitTransition(actor, to);
        if (!perm.isOk()) return done(perm.cast());
        String why = Text.clean(reason);
        boolean needsReason = to == CaseStatus.CLOSED || to == CaseStatus.UNDER_REVIEW || to == CaseStatus.OPEN;
        if (needsReason && why.length() < core.settings.cases.minReason()) {
            return done(Result.fail(Code.INVALID, "A reason of at least " + core.settings.cases.minReason() + " characters is required."));
        }
        return db(c -> {
            Result<Case> lc = loadVisible(c, actor, caseId);
            if (!lc.isOk()) return lc;
            Case cs = lc.value();
            if (cs.version() != expectedVersion) return Result.<Case>fail(Code.CONFLICT, "This case was changed by someone else. Reopen it to see the latest version.");
            if (!cs.status().canMoveTo(to)) {
                return Result.<Case>fail(Code.STATE, "A case that is '" + cs.status().display() + "' cannot move to '" + to.display() + "'.");
            }
            // With the judicial system off, a finished case may only be closed; it cannot be brought back to life.
            // (The case workflow keeps cases open and lets staff reopen a closed one through Under Review.)
            if (!core.mode.enabled() && !core.mode.casework() && !cs.status().isOpenForWork() && to != CaseStatus.CLOSED) {
                return Result.<Case>fail(Code.UNAVAILABLE, "The judicial system is switched off, so a finished case cannot be reopened or appealed through the case. "
                        + "Use the punishment's own appeal and history tools instead.");
            }
            if (to == CaseStatus.IN_TRIAL && cs.judgeUuid() == null) {
                return Result.<Case>fail(Code.STATE, "Assign a judge before the trial starts.");
            }
            long now = core.clock.now();
            if (!Repo.Cases.setStatus(c, caseId, expectedVersion, cs.status(), to, now)) {
                return Result.<Case>fail(Code.CONFLICT, "This case was changed by someone else. Reopen it to see the latest version.");
            }
            Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "STATUS", cs.status(), to, why.isEmpty() ? null : Text.truncate(why, 250));
            audit(c, actor, "case.status", cs.accusedUuid(), caseId, cs.status() + "->" + to + (why.isEmpty() ? "" : " " + Text.truncate(why, 100)),
                    to == CaseStatus.CLOSED || to == CaseStatus.UNDER_REVIEW);
            return Result.ok(Repo.Cases.get(c, caseId));
        }).thenCompose(res -> {
            if (res.isOk()) {
                Case after = res.value();
                core.emit(DomainEvent.of(DomainEvent.Type.CASE_STATUS).key("case-status:" + after.id() + ":" + after.version()).caseId(after.id())
                        .player(after.accusedName()).actor(actor.name()).summary("Status: " + after.status().display())
                        .field("Reason", why.isEmpty() ? null : Text.truncate(why, 200)).build());
                if (core.settings.defendant.statuses().contains(after.status().name())) {
                    core.notifications.defendant(after, "status", "Status: " + after.status().display(), String.valueOf(after.version()));
                }
            }
            if (res.isOk() && !res.value().status().custodyAllowed()) {
                return custody.releaseForCaseResolution(Actor.system(), caseId, "Case " + res.value().status().display().toLowerCase() + ": " + Text.truncate(why, 100))
                        .thenApply(r -> res);
            }
            return done(res);
        });
    }

    // ------------------------------------------------------------------ field edits & assignments
    public record Edit(String description, Severity severity, String offenseKey, String context) {}

    public CompletableFuture<Result<Case>> edit(Actor actor, long caseId, long expectedVersion, Edit e) {
        Result<Void> perm = core.auth.require(actor, Perms.CASE_MANAGE, "edit case details");
        if (!perm.isOk()) return done(perm.cast());
        Settings s = core.settings;
        String desc = e.description() == null ? null : Text.clean(e.description());
        if (desc != null && (desc.length() < s.cases.minDescription() || desc.length() > s.cases.maxDescription())) {
            return done(Result.fail(Code.INVALID, "The description must be " + s.cases.minDescription() + "-" + s.cases.maxDescription() + " characters."));
        }
        if (e.offenseKey() != null && core.offenses.get(e.offenseKey()).isEmpty()) return done(Result.fail(Code.INVALID, "Unknown offense category."));
        return db(c -> {
            Result<Case> lc = loadVisible(c, actor, caseId);
            if (!lc.isOk()) return lc;
            Case cs = lc.value();
            if (!(cs.status() == CaseStatus.OPEN || cs.status() == CaseStatus.UNDER_INVESTIGATION || cs.status() == CaseStatus.AWAITING_TRIAL)) {
                return Result.<Case>fail(Code.STATE, "Case details can only be edited before the trial begins.");
            }
            List<String> sets = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            StringBuilder diff = new StringBuilder();
            if (desc != null && !desc.equals(cs.description())) {
                sets.add("description=?");
                args.add(desc);
                diff.append("description changed; ");
            }
            if (e.severity() != null && e.severity() != cs.severity()) {
                sets.add("severity=?");
                args.add(e.severity().name());
                diff.append("severity ").append(cs.severity()).append("->").append(e.severity()).append("; ");
            }
            if (e.offenseKey() != null && !e.offenseKey().equals(cs.offenseKey())) {
                sets.add("offense_key=?");
                args.add(e.offenseKey());
                diff.append("offense ").append(cs.offenseKey()).append("->").append(e.offenseKey()).append("; ");
            }
            if (e.context() != null) {
                sets.add("context=?");
                args.add(Text.truncate(Text.clean(e.context()), s.cases.maxNote()));
                diff.append("context changed; ");
            }
            if (sets.isEmpty()) return Result.<Case>fail(Code.INVALID, "Nothing to change.");
            long now = core.clock.now();
            if (!Repo.Cases.updateFields(c, caseId, expectedVersion, String.join(", ", sets), now, args.toArray())) {
                return Result.<Case>fail(Code.CONFLICT, "This case was changed by someone else. Reopen it to see the latest version.");
            }
            Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "EDITED", null, null, diff.toString().trim());
            audit(c, actor, "case.edit", cs.accusedUuid(), caseId, diff.toString().trim(), false);
            return Result.ok(Repo.Cases.get(c, caseId));
        });
    }

    public CompletableFuture<Result<Case>> assignJudge(Actor actor, long caseId, long expectedVersion, Target judge) {
        Result<Case> court = core.mode.refuseCourt("Assigning a judge");
        if (court != null) return done(court);
        Result<Void> perm = core.auth.requireAny(actor, "assign a judge", Perms.JUDICIAL_MANAGE);
        if (!perm.isOk()) return done(perm.cast());
        return core.levels.hasPermission(judge.uuid(), Perms.JUDICIAL_VERDICT).handle((ok, ex) -> ex == null && Boolean.TRUE.equals(ok)).thenCompose(can -> {
            if (!can) return done(Result.<Case>fail(Code.INVALID, judge.name() + " does not hold the judicial verdict permission and cannot be assigned as judge."));
            return db(c -> {
                Result<Case> lc = loadVisible(c, actor, caseId);
                if (!lc.isOk()) return lc;
                Case cs = lc.value();
                if (!cs.status().isOpenForWork()) return Result.<Case>fail(Code.STATE, "That case is no longer open.");
                if (cs.accusedUuid().equals(judge.uuid())) return Result.<Case>fail(Code.INVALID, "The accused cannot judge their own case.");
                if (!core.settings.judicial.prosecutorMayJudge() && judge.uuid().equals(cs.prosecutorUuid())) {
                    return Result.<Case>fail(Code.INVALID, "The prosecutor may not also be the judge (see judicial.prosecutor-may-judge).");
                }
                long now = core.clock.now();
                if (!Repo.Cases.updateFields(c, caseId, expectedVersion, "judge_uuid=?, judge_name=?", now, judge.uuid().toString(), judge.name())) {
                    return Result.<Case>fail(Code.CONFLICT, "This case was changed by someone else. Reopen it to see the latest version.");
                }
                Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "JUDGE_ASSIGNED", null, null, judge.name());
                audit(c, actor, "case.judge", cs.accusedUuid(), caseId, "judge=" + judge.name(), false);
                return Result.ok(Repo.Cases.get(c, caseId));
            });
        });
    }

    public CompletableFuture<Result<Case>> assignProsecutor(Actor actor, long caseId, long expectedVersion, Target p) {
        Result<Void> perm = core.auth.require(actor, Perms.CASE_MANAGE, "reassign the prosecutor");
        if (!perm.isOk()) return done(perm.cast());
        return db(c -> {
            Result<Case> lc = loadVisible(c, actor, caseId);
            if (!lc.isOk()) return lc;
            Case cs = lc.value();
            if (!cs.status().isOpenForWork()) return Result.<Case>fail(Code.STATE, "That case is no longer open.");
            long now = core.clock.now();
            if (!Repo.Cases.updateFields(c, caseId, expectedVersion, "prosecutor_uuid=?, prosecutor_name=?", now, p.uuid().toString(), p.name())) {
                return Result.<Case>fail(Code.CONFLICT, "This case was changed by someone else. Reopen it to see the latest version.");
            }
            Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "PROSECUTOR_ASSIGNED", null, null, p.name());
            audit(c, actor, "case.prosecutor", cs.accusedUuid(), caseId, "prosecutor=" + p.name(), false);
            return Result.ok(Repo.Cases.get(c, caseId));
        });
    }

    public CompletableFuture<Result<Case>> scheduleTrial(Actor actor, long caseId, long expectedVersion, long trialAtMillis) {
        Result<Case> court = core.mode.refuseCourt("Scheduling a trial");
        if (court != null) return done(court);
        Result<Void> perm = core.auth.requireAny(actor, "schedule a trial", Perms.JUDICIAL_MANAGE, Perms.CASE_MANAGE);
        if (!perm.isOk()) return done(perm.cast());
        final Long[] previousTrial = new Long[1];
        long now0 = core.clock.now();
        if (trialAtMillis < now0 - Durations.MINUTE || trialAtMillis > now0 + Durations.YEAR) {
            return done(Result.fail(Code.INVALID, "The trial time must be in the future (within a year)."));
        }
        return db(c -> {
            Result<Case> lc = loadVisible(c, actor, caseId);
            if (!lc.isOk()) return lc;
            Case cs = lc.value();
            if (!(cs.status() == CaseStatus.OPEN || cs.status() == CaseStatus.UNDER_INVESTIGATION || cs.status() == CaseStatus.AWAITING_TRIAL)) {
                return Result.<Case>fail(Code.STATE, "A trial can only be scheduled while the case is open or awaiting trial.");
            }
            long now = core.clock.now();
            previousTrial[0] = cs.trialAt();
            CaseStatus next = cs.status() == CaseStatus.AWAITING_TRIAL ? CaseStatus.AWAITING_TRIAL : CaseStatus.AWAITING_TRIAL;
            if (!Repo.Cases.updateFields(c, caseId, expectedVersion, "trial_at=?, status=?", now, trialAtMillis, next.name())) {
                return Result.<Case>fail(Code.CONFLICT, "This case was changed by someone else. Reopen it to see the latest version.");
            }
            Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "TRIAL_SCHEDULED", cs.status(), next, "<t:" + (trialAtMillis / 1000) + ">");
            audit(c, actor, "case.trial", cs.accusedUuid(), caseId, "trial=" + trialAtMillis, false);
            return Result.ok(Repo.Cases.get(c, caseId));
        }).thenApply(res -> {
            if (res.isOk()) {
                Case cs = res.value();
                boolean moved = previousTrial[0] != null && !previousTrial[0].equals(trialAtMillis);
                core.notifications.defendant(cs, moved ? "trial-postponed" : "trial-scheduled", Text.dateTime(trialAtMillis)
                        + (cs.hearingRef() == null ? "" : " - " + cs.hearingRef()), String.valueOf(cs.version()));
                core.emit(DomainEvent.of(moved ? DomainEvent.Type.TRIAL_POSTPONED : DomainEvent.Type.TRIAL_SCHEDULED).key("trial:" + cs.id() + ":" + cs.version())
                        .caseId(cs.id()).player(cs.accusedName()).actor(actor.name())
                        .summary("<t:" + (trialAtMillis / 1000) + ":F>").field("Judge", cs.judgeName()).field("Prosecutor", cs.prosecutorName())
                        .field("Hearing", cs.hearingRef()).build());
            }
            return res;
        });
    }

    public CompletableFuture<Result<Case>> setHearingRef(Actor actor, long caseId, long expectedVersion, String ref) {
        Result<Case> court = core.mode.refuseCourt("A hearing reference");
        if (court != null) return done(court);
        Result<Void> perm = core.auth.requireAny(actor, "record the hearing reference", Perms.JUDICIAL_MANAGE, Perms.CASE_MANAGE);
        if (!perm.isOk()) return done(perm.cast());
        String r = Text.truncate(Text.clean(ref), 200);
        if (r.isEmpty()) return done(Result.fail(Code.INVALID, "Enter the Discord channel name or link for the hearing."));
        if (r.startsWith("http") && !Text.isHttpUrl(r)) return done(Result.fail(Code.INVALID, "That link is not a valid http(s) URL."));
        return db(c -> {
            Result<Case> lc = loadVisible(c, actor, caseId);
            if (!lc.isOk()) return lc;
            Case cs = lc.value();
            if (!cs.status().isOpenForWork()) return Result.<Case>fail(Code.STATE, "That case is no longer open.");
            long now = core.clock.now();
            if (!Repo.Cases.updateFields(c, caseId, expectedVersion, "hearing_ref=?", now, r)) {
                return Result.<Case>fail(Code.CONFLICT, "This case was changed by someone else. Reopen it to see the latest version.");
            }
            Repo.Events.add(c, caseId, now, actor.dbUuid(), actor.name(), "HEARING_REF", null, null, r);
            audit(c, actor, "case.hearing", cs.accusedUuid(), caseId, r, false);
            return Result.ok(Repo.Cases.get(c, caseId));
        });
    }
}
