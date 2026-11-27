package dev.aura.punishments.service;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.Records.Appeal;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Records.Evidence;
import dev.aura.punishments.model.Records.Note;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Records.Review;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import dev.aura.punishments.util.Text;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Evidence and staff notes attached directly to a punishment, for every punishment, whatever created it. This is what makes
 * evidence usable when the judicial system is off (there is no case to hang it on) and lets staff add evidence after the
 * fact from the history screen in either mode. Evidence is never deleted: it is voided with a reason (and may be replaced).
 *
 * <p>Evidence that already lives on a case (judicial sentences, direct punishments, evidence added while cases were in
 * use) stays there and is shown alongside; nothing is moved or rewritten.
 */
public final class PunishmentRecords extends Svc {
    private final CaseService cases;

    public PunishmentRecords(Core core, CaseService cases) {
        super(core);
        this.cases = cases;
    }

    /**
     * Everything attached to one punishment, filtered to what the viewer may see.
     *
     * @param evidence punishment-level evidence first, then evidence on the punishment's case (read-only here)
     */
    public record Detail(Punishment punishment, List<Evidence> evidence, List<Note> notes, List<Appeal> appeals, Review review,
                         Case linkedCase, boolean evidenceHidden, boolean notesHidden, boolean mayAddEvidence, boolean mayAddNote,
                         boolean issuer) {}

    private boolean isIssuer(Actor a, Punishment p) {
        return a.dbUuid() != null && a.dbUuid().equals(p.issuerUuid());
    }

    private boolean mayAddEvidence(Actor a, Punishment p) {
        return isIssuer(a, p) || core.auth.has(a, Perms.EVIDENCE_ADD) || core.auth.has(a, Perms.REVIEW_REVIEW);
    }

    public CompletableFuture<Result<Detail>> detail(Actor actor, long punishmentId) {
        if (!core.auth.has(actor, Perms.HISTORY_VIEW)) return done(Result.fail(Code.NO_PERMISSION, "You do not have permission to view punishment history."));
        return db(c -> {
            Punishment p = Repo.Punishments.get(c, punishmentId);
            if (p == null) return Result.<Detail>fail(Code.NOT_FOUND, "Punishment #" + punishmentId + " does not exist.");
            boolean issuer = isIssuer(actor, p);
            boolean seeEvidence = issuer || core.auth.has(actor, Perms.EVIDENCE_VIEW) || core.auth.has(actor, Perms.REVIEW_VIEW);
            boolean seeNotes = core.auth.has(actor, Perms.NOTE_VIEW);
            List<Evidence> ev = new ArrayList<>();
            if (seeEvidence) {
                ev.addAll(Repo.Evidences.forPunishment(c, punishmentId, true));
                if (p.caseId() != null) ev.addAll(Repo.Evidences.forCase(c, p.caseId(), true));
            }
            List<Note> notes = new ArrayList<>();
            if (seeNotes) {
                boolean sensitive = core.auth.has(actor, Perms.SENSITIVE_NOTES);
                for (Note n : Repo.Notes.forPunishment(c, punishmentId)) if (sensitive || !n.sensitive()) notes.add(n);
            }
            List<Appeal> appeals = new ArrayList<>(Repo.Appeals.forPunishment(c, punishmentId));
            if (p.caseId() != null) appeals.addAll(Repo.Appeals.forCase(c, p.caseId()));
            Case linked = p.caseId() == null ? null : Repo.Cases.get(c, p.caseId());
            if (linked != null && !core.auth.canViewCase(actor, linked)) linked = null;
            return Result.ok(new Detail(p, ev, notes, appeals, Repo.Reviews.byPunishment(c, punishmentId), linked, !seeEvidence, !seeNotes,
                    mayAddEvidence(actor, p), core.auth.has(actor, Perms.NOTE_ADD), issuer));
        });
    }

    // ------------------------------------------------------------------ evidence
    /**
     * Attach evidence inside the caller's transaction (also used by the owner-review flow). Goes to the punishment's
     * case only while the judicial system is on and that case is still being worked; otherwise to the punishment.
     */
    static long attachTx(Core core, Connection c, Actor actor, Punishment p, EvidenceValidator.Input in, long now) throws SQLException {
        if (core.mode.enabled() && p.caseId() != null) {
            Case cs = Repo.Cases.get(c, p.caseId());
            if (cs != null && cs.status().isOpenForWork()) {
                long id = Repo.Evidences.add(c, cs.id(), in.type(), in.reference(), in.description(), actor.dbUuid(), actor.name(), now, null);
                Repo.Events.add(c, cs.id(), now, actor.dbUuid(), actor.name(), "EVIDENCE_ADDED", null, null, in.type() + " #" + id);
                return id;
            }
        }
        return Repo.Evidences.addToPunishment(c, p.id(), in.type(), in.reference(), in.description(), actor.dbUuid(), actor.name(), now, null);
    }

    public CompletableFuture<Result<Evidence>> addEvidence(Actor actor, long punishmentId, EvidenceValidator.Input input) {
        Result<EvidenceValidator.Input> v = EvidenceValidator.validate(core.settings, input);
        if (!v.isOk()) return done(v.cast());
        EvidenceValidator.Input in = v.value();
        return db(c -> {
            Punishment p = Repo.Punishments.get(c, punishmentId);
            if (p == null) return Result.<Evidence>fail(Code.NOT_FOUND, "Punishment #" + punishmentId + " does not exist.");
            if (!mayAddEvidence(actor, p)) return Result.<Evidence>fail(Code.NO_PERMISSION, "Only the issuing staff member or staff with evidence permission can add evidence.");
            if (Repo.Evidences.countActiveForPunishment(c, punishmentId) >= core.settings.cases.maxEvidence()) {
                return Result.<Evidence>fail(Code.LIMIT, "This punishment already has the maximum number of evidence items.");
            }
            long now = core.clock.now();
            long id = attachTx(core, c, actor, p, in, now);
            Review rv = Repo.Reviews.byPunishment(c, punishmentId);
            if (rv != null) Repo.Reviews.addEvent(c, rv.id(), now, actor.name(), "EVIDENCE_ADDED", in.type() + " #" + id);
            audit(c, actor, "evidence.add", p.targetUuid(), p.caseId(), "punishment=" + punishmentId + " evidence=" + id + " type=" + in.type(), false);
            return Result.ok(Repo.Evidences.get(c, id));
        });
    }

    /** Void (never delete) evidence on a punishment, optionally replacing it with a corrected item. */
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
        return core.db.tx(c -> Repo.Evidences.get(c, evidenceId)).handle((ev, ex) -> ev).thenCompose(ev0 -> {
            if (ev0 != null && ev0.caseId() != null) return cases.voidEvidence(actor, evidenceId, reason, replacement); // evidence that lives on a case
            return db(c -> {
                Evidence ev = Repo.Evidences.get(c, evidenceId);
                if (ev == null || ev.punishmentId() == null) return Result.<Evidence>fail(Code.NOT_FOUND, "Evidence #" + evidenceId + " does not exist.");
                if (ev.voided()) return Result.<Evidence>fail(Code.STATE, "That evidence is already voided.");
                Punishment p = Repo.Punishments.get(c, ev.punishmentId());
                long now = core.clock.now();
                Long newId = null;
                if (repFinal != null) {
                    newId = Repo.Evidences.addToPunishment(c, p.id(), repFinal.type(), repFinal.reference(), repFinal.description(), actor.dbUuid(), actor.name(), now, evidenceId);
                }
                boolean needsEvidence = core.settings.quick.requireEvidence().contains(p.type());
                if (needsEvidence && newId == null && Repo.Evidences.countActiveForPunishment(c, p.id()) <= 1) {
                    return Result.<Evidence>fail(Code.EVIDENCE, "A " + p.type().display().toLowerCase() + " must keep at least one evidence item. Provide a replacement instead of voiding it.");
                }
                Repo.Evidences.voidIt(c, evidenceId, actor.dbUuid(), actor.name(), why, now);
                audit(c, actor, "evidence.void", p.targetUuid(), p.caseId(), "punishment=" + p.id() + " evidence=" + evidenceId + " reason=" + Text.truncate(why, 120), true);
                return Result.ok(Repo.Evidences.get(c, evidenceId));
            });
        });
    }

    // ------------------------------------------------------------------ notes
    public CompletableFuture<Result<Note>> addNote(Actor actor, long punishmentId, String text, boolean sensitive) {
        Result<Void> perm = core.auth.require(actor, Perms.NOTE_ADD, "add staff notes");
        if (!perm.isOk()) return done(perm.cast());
        if (sensitive && !core.auth.has(actor, Perms.SENSITIVE_NOTES)) {
            return done(Result.fail(Code.NO_PERMISSION, "You do not have permission to write sensitive notes."));
        }
        String t = Text.clean(text);
        if (t.length() < 3) return done(Result.fail(Code.INVALID, "The note is too short."));
        if (t.length() > core.settings.cases.maxNote()) return done(Result.fail(Code.INVALID, "The note is too long (max " + core.settings.cases.maxNote() + ")."));
        return db(c -> {
            Punishment p = Repo.Punishments.get(c, punishmentId);
            if (p == null) return Result.<Note>fail(Code.NOT_FOUND, "Punishment #" + punishmentId + " does not exist.");
            long now = core.clock.now();
            long id = Repo.Notes.addForPunishment(c, punishmentId, p.targetUuid(), actor.dbUuid(), actor.name(), t, sensitive, now);
            audit(c, actor, "note.add", p.targetUuid(), p.caseId(), "punishment=" + punishmentId + " note=" + id + (sensitive ? " sensitive" : ""), false);
            return Result.ok(Repo.Notes.get(c, id));
        });
    }
}
