package dev.aura.punishments;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Records.Custody;
import dev.aura.punishments.model.Simple.CaseKind;
import dev.aura.punishments.model.Simple.CustodyStatus;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.service.Actor;
import dev.aura.punishments.service.CaseService;
import dev.aura.punishments.service.DomainEvent;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CaseAndCustodyTest {
    Fx fx;
    Actor mod;

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fx();
        mod = Fx.staff("JrMod", 20, Fx.JR_MOD);
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    static <T> Result<T> r(CompletableFuture<Result<T>> f) throws Exception {
        return f.get(10, TimeUnit.SECONDS);
    }

    Case mkCase(Actor by, String player, String offense) throws Exception {
        Result<Case> res = r(fx.cases.create(by, new CaseService.CreateRequest(Fx.target(player), offense, Severity.MODERATE,
                "Spammed offensive messages in global chat repeatedly.", "context", List.of(Fx.ev()))));
        assertTrue(res.isOk(), () -> "create failed: " + res.message());
        return res.value();
    }

    // ------------------------------------------------------------ case creation
    @Test
    void creatingACaseNeedsPermission() throws Exception {
        Actor nobody = Fx.staff("Nobody", 5);
        Result<Case> res = r(fx.cases.create(nobody, new CaseService.CreateRequest(Fx.target("Steve"), "harassment", Severity.MINOR,
                "A long enough description here.", null, List.of(Fx.ev()))));
        assertEquals(Code.NO_PERMISSION, res.code());
    }

    @Test
    void caseCreationEnforcesOffenseEvidenceMinimum() throws Exception {
        Result<Case> res = r(fx.cases.create(mod, new CaseService.CreateRequest(Fx.target("Steve"), "harassment", Severity.MODERATE,
                "Harassed another player in chat.", null, List.of())));
        assertEquals(Code.EVIDENCE, res.code());
        assertTrue(r(fx.cases.create(mod, new CaseService.CreateRequest(Fx.target("Steve"), "chat_misconduct", Severity.MINOR,
                "Excessive caps in chat.", null, List.of()))).isOk(), "chat misconduct needs no evidence");
    }

    @Test
    void caseCreationValidatesText() throws Exception {
        Result<Case> res = r(fx.cases.create(mod, new CaseService.CreateRequest(Fx.target("Steve"), "chat_misconduct", Severity.MINOR,
                "short", null, List.of())));
        assertEquals(Code.INVALID, res.code());
        Result<Case> bad = r(fx.cases.create(mod, new CaseService.CreateRequest(Fx.target("Steve"), "nope", Severity.MINOR,
                "A long enough description here.", null, List.of())));
        assertEquals(Code.INVALID, bad.code());
    }

    @Test
    void javascriptLinkIsRejectedAsEvidence() throws Exception {
        Result<Case> res = r(fx.cases.create(mod, new CaseService.CreateRequest(Fx.target("Steve"), "harassment", Severity.MINOR,
                "A long enough description here.", null, List.of(Fx.ev("javascript:alert(1)")))));
        assertEquals(Code.EVIDENCE, res.code());
    }

    @Test
    void duplicateOpenCaseIsRefused() throws Exception {
        mkCase(mod, "Steve", "harassment");
        Result<Case> dup = r(fx.cases.create(mod, new CaseService.CreateRequest(Fx.target("Steve"), "harassment", Severity.MODERATE,
                "Same thing again, long enough text.", null, List.of(Fx.ev()))));
        assertEquals(Code.DUPLICATE, dup.code());
    }

    @Test
    void failedCreationLeavesNothingBehind() throws Exception {
        r(fx.cases.create(mod, new CaseService.CreateRequest(Fx.target("Steve"), "harassment", Severity.MODERATE,
                "Harassed another player in chat.", null, List.of(Fx.ev("not a link at all")))));
        assertEquals(0L, (long) fx.db.<Long>txSync(c -> Repo.Cases.countAll(c)));
    }

    @Test
    void equalOrHigherRankedTargetIsProtected() throws Exception {
        Target boss = Fx.target("BossMan");
        fx.auth.put(boss.uuid(), new dev.aura.punishments.service.LevelResolver.TargetAuth(20, false)); // equal to JrMod
        Result<Case> res = r(fx.cases.create(mod, new CaseService.CreateRequest(boss, "chat_misconduct", Severity.MINOR,
                "Excessive caps in chat today.", null, List.of())));
        assertEquals(Code.HIERARCHY, res.code());
        fx.auth.put(boss.uuid(), new dev.aura.punishments.service.LevelResolver.TargetAuth(5, false));
        assertTrue(r(fx.cases.create(mod, new CaseService.CreateRequest(boss, "chat_misconduct", Severity.MINOR,
                "Excessive caps in chat today.", null, List.of()))).isOk());
    }

    @Test
    void immuneTargetCannotBeCharged() throws Exception {
        Target vip = Fx.target("Vip");
        fx.auth.put(vip.uuid(), new dev.aura.punishments.service.LevelResolver.TargetAuth(0, true));
        assertEquals(Code.HIERARCHY, r(fx.cases.create(mod, new CaseService.CreateRequest(vip, "chat_misconduct", Severity.MINOR,
                "Excessive caps in chat today.", null, List.of()))).code());
    }

    @Test
    void selfTargetingIsRefused() throws Exception {
        Target self = new Target(mod.uuid(), mod.name(), true);
        assertEquals(Code.HIERARCHY, r(fx.cases.create(mod, new CaseService.CreateRequest(self, "chat_misconduct", Severity.MINOR,
                "Excessive caps in chat today.", null, List.of()))).code());
    }

    // ------------------------------------------------------------ state machine
    @Test
    void invalidTransitionsAreRejected() throws Exception {
        Actor owner = Fx.owner();
        Case c = mkCase(owner, "Steve", "harassment");
        Result<Case> bad = r(fx.cases.transition(owner, c.id(), c.version(), CaseStatus.IN_TRIAL, null));
        assertEquals(Code.STATE, bad.code(), "cannot jump OPEN -> IN_TRIAL");
        Result<Case> direct = r(fx.cases.transition(owner, c.id(), c.version(), CaseStatus.SENTENCED, null));
        assertEquals(Code.STATE, direct.code(), "SENTENCED is only set by sentencing");
    }

    @Test
    void staleVersionIsRejectedAsConflict() throws Exception {
        Actor owner = Fx.owner();
        Case c = mkCase(owner, "Steve", "harassment");
        assertTrue(r(fx.cases.transition(owner, c.id(), c.version(), CaseStatus.UNDER_INVESTIGATION, null)).isOk());
        Result<Case> stale = r(fx.cases.transition(owner, c.id(), c.version(), CaseStatus.AWAITING_TRIAL, null));
        assertEquals(Code.CONFLICT, stale.code());
    }

    @Test
    void concurrentModificationsOnlyOneWins() throws Exception {
        Actor owner = Fx.owner();
        Case c = mkCase(owner, "Steve", "harassment");
        CompletableFuture<Result<Case>> a = fx.cases.transition(owner, c.id(), c.version(), CaseStatus.UNDER_INVESTIGATION, null);
        CompletableFuture<Result<Case>> b = fx.cases.transition(owner, c.id(), c.version(), CaseStatus.AWAITING_TRIAL, null);
        int wins = (r(a).isOk() ? 1 : 0) + (r(b).isOk() ? 1 : 0);
        assertEquals(1, wins, "exactly one of two concurrent changes at the same version may succeed");
    }

    @Test
    void trialCannotStartWithoutAJudge() throws Exception {
        Actor owner = Fx.owner();
        Case c = mkCase(owner, "Steve", "harassment");
        c = r(fx.cases.transition(owner, c.id(), c.version(), CaseStatus.AWAITING_TRIAL, null)).value();
        assertEquals(Code.STATE, r(fx.cases.transition(owner, c.id(), c.version(), CaseStatus.IN_TRIAL, null)).code());
    }

    @Test
    void judgeMustHoldVerdictPermissionAndCannotBeTheAccused() throws Exception {
        Actor owner = Fx.owner();
        Case c = mkCase(owner, "Steve", "harassment");
        Target notJudge = Fx.target("Nobody");
        assertEquals(Code.INVALID, r(fx.cases.assignJudge(owner, c.id(), c.version(), notJudge)).code());
        fx.judges.add(notJudge.uuid());
        assertTrue(r(fx.cases.assignJudge(owner, c.id(), c.version(), notJudge)).isOk());
        Case fresh = r(fx.cases.detail(owner, c.id())).value().c();
        Target accused = Fx.target("Steve");
        fx.judges.add(accused.uuid());
        assertEquals(Code.INVALID, r(fx.cases.assignJudge(owner, c.id(), fresh.version(), accused)).code());
    }

    // ------------------------------------------------------------ evidence & notes
    @Test
    void evidenceIsNeverDeletedOnlyVoidedWithReason() throws Exception {
        Actor owner = Fx.owner();
        Case c = mkCase(owner, "Steve", "harassment");
        var ev = r(fx.cases.addEvidence(owner, c.id(), Fx.ev("https://example.com/two.png"))).value();
        assertEquals(Code.INVALID, r(fx.cases.voidEvidence(owner, ev.id(), "no", null)).code(), "reason required");
        assertTrue(r(fx.cases.voidEvidence(owner, ev.id(), "Wrong screenshot uploaded by mistake", Fx.ev("https://example.com/right.png"))).isOk());
        var detail = r(fx.cases.detail(owner, c.id())).value();
        assertEquals(3, detail.evidence().size(), "voided item is kept for the record");
        assertEquals(1, detail.evidence().stream().filter(e -> e.voided()).count());
        assertTrue(detail.evidence().stream().anyMatch(e -> e.correctsId() != null && e.correctsId() == ev.id()));
    }

    @Test
    void evidenceAndNotesRespectViewPermissions() throws Exception {
        Actor owner = Fx.owner();
        Case c = mkCase(owner, "Steve", "harassment");
        r(fx.cases.addNote(owner, c.id(), null, "Private staff observation here", false));
        Actor viewer = Fx.staff("Viewer", 5, Perms.CASE_VIEW_ALL);
        var d = r(fx.cases.detail(viewer, c.id())).value();
        assertTrue(d.evidenceHidden() && d.notesHidden());
        assertTrue(d.evidence().isEmpty() && d.notes().isEmpty());
    }

    @Test
    void traineeSeesOnlyOwnCases() throws Exception {
        Actor owner = Fx.owner();
        Case others = mkCase(owner, "Steve", "harassment");
        Actor trainee = Fx.staff("Trainee", 1, Perms.CASE_VIEW_OWN, Perms.REPORT, Perms.PUNISH_OTHERS);
        assertEquals(Code.NO_PERMISSION, r(fx.cases.detail(trainee, others.id())).code());
        Result<Case> own = r(fx.cases.create(trainee, new CaseService.CreateRequest(Fx.target("Alex"), "chat_misconduct", Severity.MINOR,
                "Posted spam in chat several times.", null, List.of())));
        assertTrue(own.isOk());
        assertEquals(CaseKind.REPORT, own.value().kind());
        assertTrue(r(fx.cases.detail(trainee, own.value().id())).isOk());
    }

    // ------------------------------------------------------------ custody
    @Test
    void custodyIsPerformedByJailReloadedAndVerified() throws Exception {
        Case c = mkCase(mod, "Steve", "harassment");
        Result<Custody> res = r(fx.custody.initiate(mod, c.id()));
        assertTrue(res.isOk(), res.message());
        Custody cu = res.value();
        assertEquals(CustodyStatus.ACTIVE, cu.status());
        assertEquals(fx.now + 48 * Durations.HOUR, cu.deadline(), "default 48h review deadline");
        assertTrue(fx.jail.prisoners.containsKey(Fx.target("Steve").uuid()), "JailReloaded holds the prisoner");
        assertTrue(fx.jail.prisoners.values().iterator().next().reason().contains("AP-" + String.format("%04d", c.id())), "tagged with the case");
        assertEquals(DomainEvent.Type.CUSTODY_STARTED, fx.events.get(fx.events.size() - 1).type());
    }

    @Test
    void failedJailingIsNeverReportedAsSuccess() throws Exception {
        Case c = mkCase(mod, "Steve", "harassment");
        fx.jail.failNext = "No jail is configured";
        Result<Custody> res = r(fx.custody.initiate(mod, c.id()));
        assertFalse(res.isOk());
        assertEquals(Code.EXTERNAL, res.code());
        assertTrue(res.message().contains("No jail is configured"));
        Custody cu = fx.db.txSync(cn -> Repo.Custodies.forCase(cn, c.id())).get(0);
        assertEquals(CustodyStatus.FAILED, cu.status());
        assertTrue(fx.jail.prisoners.isEmpty());
        // and a retry is possible afterwards
        assertTrue(r(fx.custody.initiate(mod, c.id())).isOk());
    }

    @Test
    void custodyRefusedWhenJailReloadedMissing() throws Exception {
        Case c = mkCase(mod, "Steve", "harassment");
        fx.jail.available = false;
        Result<Custody> res = r(fx.custody.initiate(mod, c.id()));
        assertEquals(Code.UNAVAILABLE, res.code());
        assertEquals(0, fx.db.<Integer>txSync(cn -> Repo.Custodies.allLive(cn).size()), "no custody record is created");
    }

    @Test
    void custodyNeedsItsOwnPermission() throws Exception {
        Actor helper = Fx.staff("Helper", 10, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.CASE_CREATE, Perms.CASE_VIEW_ALL);
        Case c = mkCase(helper, "Steve", "harassment");
        assertEquals(Code.NO_PERMISSION, r(fx.custody.initiate(helper, c.id())).code());
    }

    @Test
    void aPlayerCannotBeHeldTwice() throws Exception {
        Case a = mkCase(mod, "Steve", "harassment");
        assertTrue(r(fx.custody.initiate(mod, a.id())).isOk());
        Case b = mkCase(mod, "Steve", "theft");
        assertEquals(Code.DUPLICATE, r(fx.custody.initiate(mod, b.id())).code());
    }

    @Test
    void releaseOnlyEndsTheCustodyThatBelongsToTheCase() throws Exception {
        Case c = mkCase(mod, "Steve", "harassment");
        r(fx.custody.initiate(mod, c.id()));
        // someone re-jails the player under an unrelated record behind our back
        var uuid = Fx.target("Steve").uuid();
        fx.jail.prisoners.remove(uuid);
        fx.jail.jail(new dev.aura.punishments.integration.JailPort.JailRequest(uuid, "Steve", 999, "XX-1", "unrelated", "Other", 3600000L, null, null, false));
        Actor judge = Fx.owner();
        Result<Custody> res = r(fx.custody.release(judge, c.id(), "Releasing after review of the case"));
        assertTrue(res.isOk());
        assertEquals(CustodyStatus.ENDED_EXTERNALLY, res.value().status());
        assertTrue(fx.jail.prisoners.containsKey(uuid), "the unrelated jail sentence was NOT released");
    }

    @Test
    void releaseFailureKeepsCustodyAndIsReported() throws Exception {
        Case c = mkCase(mod, "Steve", "harassment");
        r(fx.custody.initiate(mod, c.id()));
        fx.jail.failNext = "storage busy";
        Result<Custody> res = r(fx.custody.release(Fx.owner(), c.id(), "Releasing after the review of the case"));
        assertFalse(res.isOk());
        assertTrue(res.message().contains("NOT released"));
        assertTrue(fx.jail.prisoners.containsKey(Fx.target("Steve").uuid()));
        Custody cu = fx.db.txSync(cn -> Repo.Custodies.liveForCase(cn, c.id()));
        assertEquals(CustodyStatus.ACTIVE, cu.status(), "still tracked as held");
        // retry works
        assertTrue(r(fx.custody.release(Fx.owner(), c.id(), "Releasing after the review of the case")).isOk());
        assertTrue(fx.jail.prisoners.isEmpty());
    }

    @Test
    void offlinePrisonerReleaseStaysPendingUntilConfirmed() throws Exception {
        Case c = mkCase(mod, "Steve", "harassment");
        r(fx.custody.initiate(mod, c.id()));
        fx.jail.releasePendingOffline = true;
        Result<Custody> res = r(fx.custody.release(Fx.owner(), c.id(), "Releasing after the review of the case"));
        assertTrue(res.isOk());
        assertEquals(CustodyStatus.RELEASING, res.value().status(), "not claimed as released yet");
        // JailReloaded completes the release when the player logs in
        fx.jail.prisoners.remove(Fx.target("Steve").uuid());
        r(fx.custody.reconcile());
        assertEquals(CustodyStatus.RELEASED, fx.db.txSync(cn -> Repo.Custodies.get(cn, res.value().id())).status());
    }

    // ------------------------------------------------------------ reconciliation / recovery
    @Test
    void reconcileAdoptsADetentionInterruptedAfterJailReloadedConfirmed() throws Exception {
        Case c = mkCase(mod, "Steve", "harassment");
        var t = Fx.target("Steve");
        // simulate a crash: intent recorded, JailReloaded did jail, but step 3 never ran
        fx.db.txSync(cn -> {
            long id = Repo.Custodies.insert(cn, c.id(), t.uuid(), "Steve", mod.uuid(), "JrMod", fx.now);
            Repo.Ops.add(cn, "JAIL_START", c.id(), id, t.uuid(), null, fx.now);
            return null;
        });
        fx.jail.jail(new dev.aura.punishments.integration.JailPort.JailRequest(t.uuid(), "Steve", c.id(),
                "AP-" + String.format("%04d", c.id()), "x", "JrMod", -1, null, null, false));
        r(fx.custody.reconcile());
        Custody cu = fx.db.txSync(cn -> Repo.Custodies.liveForCase(cn, c.id()));
        assertNotNull(cu);
        assertEquals(CustodyStatus.ACTIVE, cu.status());
        assertNotNull(cu.jailPrisonerId());
        assertEquals(0L, (long) fx.db.<Long>txSync(cn -> dev.aura.punishments.db.Repo.Ops.countUnfinished(cn)));
    }

    @Test
    void reconcileFailsAnInterruptedDetentionThatNeverHappened() throws Exception {
        Case c = mkCase(mod, "Steve", "harassment");
        var t = Fx.target("Steve");
        fx.db.txSync(cn -> {
            long id = Repo.Custodies.insert(cn, c.id(), t.uuid(), "Steve", mod.uuid(), "JrMod", fx.now);
            Repo.Ops.add(cn, "JAIL_START", c.id(), id, t.uuid(), null, fx.now);
            return null;
        });
        r(fx.custody.reconcile());
        assertNull(fx.db.txSync(cn -> Repo.Custodies.liveForCase(cn, c.id())));
        assertEquals(CustodyStatus.FAILED, fx.db.txSync(cn -> Repo.Custodies.forCase(cn, c.id())).get(0).status());
    }

    @Test
    void reconcileDetectsPrisonerReleasedBehindOurBack() throws Exception {
        Case c = mkCase(mod, "Steve", "harassment");
        r(fx.custody.initiate(mod, c.id()));
        fx.jail.prisoners.clear(); // /unjailforce
        fx.events.clear();
        r(fx.custody.reconcile());
        assertEquals(CustodyStatus.ENDED_EXTERNALLY, fx.db.txSync(cn -> Repo.Custodies.forCase(cn, c.id())).get(0).status());
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.CUSTODY_ALERT), "staff are alerted");
    }

    @Test
    void closingACaseReleasesItsPrisoner() throws Exception {
        Actor owner = Fx.owner();
        Case c = mkCase(owner, "Steve", "harassment");
        r(fx.custody.initiate(owner, c.id()));
        c = r(fx.cases.detail(owner, c.id())).value().c();
        assertTrue(r(fx.cases.transition(owner, c.id(), c.version(), CaseStatus.CLOSED, "Closing, insufficient evidence")).isOk());
        assertTrue(fx.jail.prisoners.isEmpty(), "a closed case cannot hold a prisoner");
    }

    // ------------------------------------------------------------ deadlines
    @Test
    void deadlineAlertsAndAutomaticReleaseAfterGrace() throws Exception {
        Case c = mkCase(mod, "Steve", "harassment");
        r(fx.custody.initiate(mod, c.id()));
        fx.now += 37 * Durations.HOUR;   // 11h left -> the 12h alert is due
        fx.events.clear();
        fx.custody.sweepDeadlines().get();
        assertEquals(1, fx.events.stream().filter(e -> e.type() == DomainEvent.Type.CUSTODY_ALERT).count());
        fx.custody.sweepDeadlines().get();
        assertEquals(1, fx.events.stream().filter(e -> e.type() == DomainEvent.Type.CUSTODY_ALERT).count(), "not repeated");
        fx.now += 12 * Durations.HOUR;   // past the deadline
        fx.custody.sweepDeadlines().get();
        assertTrue(fx.jail.prisoners.containsKey(Fx.target("Steve").uuid()), "still held during the grace period");
        fx.now += 12 * Durations.HOUR + Durations.MINUTE;  // past deadline + 12h grace
        fx.custody.sweepDeadlines().get();
        assertTrue(fx.jail.prisoners.isEmpty(), "an unresolved case does not mean indefinite imprisonment");
    }

    @Test
    void judgeMayExtendUpToTheLimit() throws Exception {
        Actor owner = Fx.owner();
        Case c = mkCase(owner, "Steve", "harassment");
        Custody cu = r(fx.custody.initiate(owner, c.id())).value();
        Custody e1 = r(fx.custody.extend(owner, c.id(), "Need more time to gather witness statements")).value();
        assertEquals(cu.deadline() + 24 * Durations.HOUR, e1.deadline());
        assertTrue(r(fx.custody.extend(owner, c.id(), "Still gathering the witness statements")).isOk());
        assertEquals(Code.LIMIT, r(fx.custody.extend(owner, c.id(), "A third extension request here")).code());
    }
}
