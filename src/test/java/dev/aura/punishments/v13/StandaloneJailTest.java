package dev.aura.punishments.v13;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.integration.JailPort;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.OpState;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.service.*;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.aura.punishments.v13.V13Support.fx;
import static dev.aura.punishments.v13.V13Support.mod;
import static dev.aura.punishments.v13.V13Support.r;
import static org.junit.jupiter.api.Assertions.*;

/** Jail as an ordinary punishment: placement, verified results, early release, expiry, crash recovery and the safeguards. */
class StandaloneJailTest {
    Fx fx;
    Actor mod;
    Target steve;

    @BeforeEach
    void setUp() throws Exception {
        fx = fx(false);
        mod = mod("Mod");
        steve = Fx.target("Steve");
        fx.online.add(steve.uuid());
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    Result<Punishment> jail(long dur) throws Exception {
        return r(fx.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.JAIL, dur, "Griefing the shared farm area.", List.of(Fx.ev()), "Seen twice.")));
    }

    Punishment row(long id) throws Exception {
        return fx.db.txSync(c -> Repo.Punishments.get(c, id));
    }

    // ------------------------------------------------------------------ placing
    @Test
    void jailIsAnOrdinaryPunishmentVerifiedAgainstJailReloaded() throws Exception {
        Punishment p = jail(Durations.HOUR).orThrow();
        assertEquals(PunishType.JAIL, p.type());
        assertEquals(PunishStatus.ACTIVE, p.status());
        assertEquals(JailService.HELD, p.jailState());
        assertNotNull(p.jailPrisonerId());
        assertEquals("main", p.jailName());
        assertNull(p.caseId());
        assertNull(p.custodyId());
        assertEquals(1, fx.jail.prisoners.size());
        var held = fx.jail.prisoners.get(steve.uuid());
        assertEquals(Durations.HOUR, held.remainingMs(), "the sentence length is handed to JailReloaded");
        assertTrue(held.reason().startsWith("[" + JailService.tag(p.id()) + "]"), "tagged so it can be recognised after a crash");
        assertEquals(1, fx.db.<List<Punishment>>txSync(c -> Repo.Punishments.liveStandaloneJail(c)).size());
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.QUICK_PUNISHMENT && e.info() != null && e.info().type().equals("JAIL")));
        assertEquals(1, fx.db.<List<dev.aura.punishments.model.Records.Evidence>>txSync(c -> Repo.Evidences.forPunishment(c, p.id(), false)).size(), "evidence is attached at issuance");
        assertEquals(1, fx.db.<List<dev.aura.punishments.model.Records.Note>>txSync(c -> Repo.Notes.forPunishment(c, p.id())).size(), "and so is the note");
    }

    @Test
    void permanentJailNeedsItsOwnPermission() throws Exception {
        assertEquals(Code.LIMIT, jail(Durations.PERMANENT).code());
        mod = Fx.staff("Perm", 30, Fx.concat(V13Support.punisher(), Perms.punishPermanent(PunishType.JAIL)));
        Punishment p = jail(Durations.PERMANENT).orThrow();
        assertNull(p.expiresAt());
        assertEquals(-1L, fx.jail.prisoners.get(steve.uuid()).remainingMs());
    }

    @Test
    void theDurationIsLimitedByTheStaffMembersTier() throws Exception {
        mod = Fx.staff("Short", 20, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.NOTE_ADD, Perms.punish(PunishType.JAIL), Perms.limit(PunishType.JAIL, "short"));
        assertEquals(Code.LIMIT, jail(Durations.DAY).code());
        assertTrue(jail(30 * Durations.MINUTE).isOk());
    }

    @Test
    void ifJailReloadedRefusesNothingIsRecordedAsJailed() throws Exception {
        fx.jail.failNext = "Jail 'main' has no empty cell.";
        var res = jail(Durations.HOUR);
        assertEquals(Code.EXTERNAL, res.code());
        assertTrue(res.message().contains("no empty cell"));
        assertTrue(fx.jail.prisoners.isEmpty());
        List<Punishment> hist = fx.db.txSync(c -> Repo.Punishments.forTarget(c, steve.uuid(), 10, 0));
        assertEquals(1, hist.size());
        assertEquals(PunishStatus.REVOKED, hist.get(0).status(), "the attempt stays on record, closed as not started");
        assertEquals(JailService.FAILED, hist.get(0).jailState());
        assertTrue(hist.get(0).endReason().startsWith("Jail was not started"));
        assertTrue(fx.db.<List<Punishment>>txSync(c -> Repo.Punishments.liveStandaloneJail(c)).isEmpty());
        assertTrue(fx.events.stream().noneMatch(e -> e.type() == DomainEvent.Type.QUICK_PUNISHMENT), "no 'issued' notice for a jail that never happened");
        assertNull(fx.db.<dev.aura.punishments.model.Records.Review>txSync(c -> Repo.Reviews.byPunishment(c, hist.get(0).id())), "no review for a punishment that never took effect");
    }

    @Test
    void ifJailReloadedIsDownNothingIsCreatedAtAll() throws Exception {
        fx.jail.available = false;
        var res = jail(Durations.HOUR);
        assertEquals(Code.UNAVAILABLE, res.code());
        assertEquals(0L, (long) fx.db.<Long>txSync(c -> Repo.Punishments.countForTarget(c, steve.uuid())));
    }

    @Test
    void anAlreadyJailedPlayerIsRefusedUpFrontAndLeavesNoFailedRecord() throws Exception {
        jail(Durations.HOUR).orThrow();
        var second = jail(Durations.HOUR);
        assertEquals(Code.DUPLICATE, second.code());
        assertTrue(second.message().contains("already serving jail punishment"));
        assertEquals(1, fx.db.<List<Punishment>>txSync(c -> Repo.Punishments.forTarget(c, steve.uuid(), 10, 0)).size(), "no noise record for the refused attempt");
        assertEquals(1, fx.db.<List<Punishment>>txSync(c -> Repo.Punishments.liveStandaloneJail(c)).size());
    }

    @Test
    void anOfflinePlayerIsRegisteredAndPlacedAtNextLogin() throws Exception {
        fx.online.remove(steve.uuid());
        fx.jail.offline.add(steve.uuid());
        Punishment p = jail(Durations.HOUR).orThrow();
        assertEquals(JailService.PENDING_PLACEMENT, p.jailState());
        assertEquals(PunishStatus.ACTIVE, p.status());
    }

    @Test
    void jailIsStillRefusedThroughTheQuickPathWhenTheJudicialSystemIsOn() throws Exception {
        Fx on = V13Support.fx(true);
        try {
            var res = r(on.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.JAIL, Durations.HOUR, "Griefing the shared farm area.", List.of())));
            assertEquals(Code.INVALID, res.code(), "unchanged: with cases on, jail is issued through a case");
        } finally {
            on.close();
        }
    }

    // ------------------------------------------------------------------ releasing
    @Test
    void anAuthorisedStaffMemberCanReleaseEarlyWithARecordedReason() throws Exception {
        Punishment p = jail(Durations.HOUR).orThrow();
        Actor releaser = Fx.staff("Releaser", 50, Perms.REVOKE_ANY);
        assertEquals(Code.INVALID, r(fx.jails.unjail(releaser, steve.uuid(), "no")).code(), "a reason is required");
        r(fx.jails.unjail(releaser, steve.uuid(), "Served enough, behaving well.")).orThrow();
        Punishment after = row(p.id());
        assertEquals(PunishStatus.REVOKED, after.status());
        assertEquals("Releaser", after.endedBy());
        assertEquals(JailService.RELEASED, after.jailState());
        assertTrue(fx.jail.prisoners.isEmpty(), "the prisoner is really out of JailReloaded");
        assertEquals(-1L, (long) fx.db.<Long>txSync(c -> Repo.Audits.verify(c)));
    }

    @Test
    void releasePermissionsAreEnforced() throws Exception {
        Punishment p = jail(Durations.HOUR).orThrow();
        Actor nobody = Fx.staff("Nobody", 50, Perms.PUNISH);
        assertEquals(Code.NO_PERMISSION, r(fx.jails.unjail(nobody, steve.uuid(), "Trying to release without rights.")).code());
        assertTrue(fx.jail.prisoners.containsKey(steve.uuid()));
        Actor other = Fx.staff("OtherIssuer", 50, Perms.REVOKE);
        assertEquals(Code.NO_PERMISSION, r(fx.jails.unjail(other, steve.uuid(), "Only the issuer may use the own-revoke right.")).code());
        Actor custody = Fx.staff("Custody", 50, Perms.CUSTODY_RELEASE);
        assertTrue(r(fx.jails.unjail(custody, steve.uuid(), "Custody release holders may free prisoners.")).isOk());
        assertEquals(PunishStatus.REVOKED, row(p.id()).status());
    }

    @Test
    void theIssuerMayReleaseTheirOwnJailWithTheRevokeRight() throws Exception {
        jail(Durations.HOUR).orThrow();
        assertTrue(r(fx.jails.unjail(mod, steve.uuid(), "Mistake; wrong player was jailed.")).isOk());
    }

    @Test
    void releasingSomeoneNotJailedByUsIsRefused() throws Exception {
        var res = r(fx.jails.unjail(Fx.owner(), steve.uuid(), "Nobody is jailed here at all."));
        assertEquals(Code.NOT_FOUND, res.code());
    }

    @Test
    void ifJailReloadedCannotReleaseThePunishmentIsRevokedAndTheReleaseIsRetried() throws Exception {
        Punishment p = jail(Durations.HOUR).orThrow();
        fx.jail.failNext = "JailReloaded is busy";
        var res = r(fx.jails.unjail(Fx.owner(), steve.uuid(), "Releasing the prisoner early, please."));
        assertTrue(res.isOk(), "the revocation itself is recorded");
        assertNotNull(res.message(), "and the staff member is told the release is not complete");
        Punishment mid = row(p.id());
        assertEquals(PunishStatus.REVOKED, mid.status());
        assertEquals(JailService.RELEASING, mid.jailState());
        assertTrue(fx.jail.prisoners.containsKey(steve.uuid()));
        fx.jails.retryStuckReleases().get();
        assertEquals(JailService.RELEASED, row(p.id()).jailState());
        assertTrue(fx.jail.prisoners.isEmpty());
    }

    @Test
    void releaseForAnOfflinePrisonerCompletesAtNextLogin() throws Exception {
        fx.online.remove(steve.uuid());
        fx.jail.offline.add(steve.uuid());
        Punishment p = jail(Durations.HOUR).orThrow();
        fx.jail.releasePendingOffline = true;
        r(fx.jails.unjail(Fx.owner(), steve.uuid(), "Releasing before they log in again.")).orThrow();
        assertEquals(JailService.RELEASING, row(p.id()).jailState(), "not reported as released until JailReloaded completes it");
        fx.jails.onPrisonerReleased(steve.uuid(), p.jailPrisonerId()).get();
        assertEquals(JailService.RELEASED, row(p.id()).jailState());
    }

    // ------------------------------------------------------------------ expiry & external changes
    @Test
    void whenJailReloadedsTimerEndsTheSentenceIsRecordedAsServed() throws Exception {
        Punishment p = jail(Durations.HOUR).orThrow();
        fx.now += Durations.HOUR + 5000;
        fx.jail.prisoners.remove(steve.uuid());
        fx.jails.onPrisonerReleased(steve.uuid(), p.jailPrisonerId()).get();
        Punishment after = row(p.id());
        assertEquals(PunishStatus.SERVED, after.status());
        assertEquals("Sentence served", after.endReason());
        assertEquals(JailService.RELEASED, after.jailState());
    }

    @Test
    void anEarlyReleaseInsideJailReloadedIsRecordedHonestlyAndStaffAreAlerted() throws Exception {
        Punishment p = jail(Durations.DAY).orThrow();
        fx.jail.prisoners.remove(steve.uuid());
        fx.jails.onPrisonerReleased(steve.uuid(), p.jailPrisonerId()).get();
        Punishment after = row(p.id());
        assertEquals(PunishStatus.REVOKED, after.status());
        assertEquals(JailService.ENDED_EXTERNALLY, after.jailState());
        assertTrue(after.endReason().contains("outside AuraPunishments"));
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.CUSTODY_ALERT && e.summary().contains("outside AuraPunishments")));
    }

    @Test
    void ourOwnReleaseIsNotMistakenForAnExternalOne() throws Exception {
        Punishment p = jail(Durations.DAY).orThrow();
        r(fx.jails.unjail(Fx.owner(), steve.uuid(), "A normal early release by staff.")).orThrow();
        fx.jails.onPrisonerReleased(steve.uuid(), p.jailPrisonerId()).get(); // the JailReloaded event that follows our own call
        assertEquals(JailService.RELEASED, row(p.id()).jailState());
        assertTrue(fx.events.stream().noneMatch(e -> e.type() == DomainEvent.Type.CUSTODY_ALERT));
    }

    // ------------------------------------------------------------------ crash recovery
    @Test
    void aCrashAfterJailReloadedAcceptedIsAdoptedByReconciliation() throws Exception {
        Punishment p = jail(Durations.HOUR).orThrow();
        // simulate: the record is still PLACING (the process died before step 3) but JailReloaded has the prisoner
        fx.db.txSync(c -> { Repo.Punishments.setJail(c, p.id(), JailService.PLACING, null, null, null); return null; });
        fx.db.txSync(c -> { Repo.Ops.addForPunishment(c, "JAIL_PLACE", p.id(), steve.uuid(), null, fx.now); return null; });
        fx.now += 5_000;
        r(fx.jails.reconcile()).orThrow();
        Punishment after = row(p.id());
        assertEquals(JailService.HELD, after.jailState());
        assertEquals(PunishStatus.ACTIVE, after.status());
        assertEquals(0L, fx.db.<Long>txSync(c -> Repo.Ops.countUnfinished(c)), "the interrupted operation is closed");
    }

    @Test
    void aCrashBeforeJailReloadedWasCalledIsResolvedAsNotStartedOnceTheGraceHasPassed() throws Exception {
        Punishment p = jail(Durations.HOUR).orThrow();
        fx.jail.prisoners.clear();
        fx.db.txSync(c -> { Repo.Punishments.setJail(c, p.id(), JailService.PLACING, null, null, null); return null; });
        r(fx.jails.reconcile()).orThrow();
        assertEquals(JailService.PLACING, row(p.id()).jailState(), "inside the in-flight grace period nothing is decided");
        fx.now += 120_000;
        r(fx.jails.reconcile()).orThrow();
        Punishment after = row(p.id());
        assertEquals(JailService.FAILED, after.jailState());
        assertEquals(PunishStatus.REVOKED, after.status());
    }

    @Test
    void reconciliationNoticesAPrisonerThatWasFreedWhileTheServerWasDown() throws Exception {
        Punishment p = jail(Durations.HOUR).orThrow();
        fx.now += Durations.HOUR + 60_000;
        fx.jail.prisoners.clear();
        r(fx.jails.reconcile()).orThrow();
        assertEquals(PunishStatus.SERVED, row(p.id()).status());
    }

    @Test
    void reconciliationCompletesAReleaseThatJailReloadedFinishedMeanwhile() throws Exception {
        Punishment p = jail(Durations.DAY).orThrow();
        fx.jail.releasePendingOffline = true;
        fx.online.remove(steve.uuid());
        r(fx.jails.unjail(Fx.owner(), steve.uuid(), "Release is accepted but pending.")).orThrow();
        assertEquals(JailService.RELEASING, row(p.id()).jailState());
        fx.jail.prisoners.clear(); // the player logged in and JailReloaded finished it
        r(fx.jails.reconcile()).orThrow();
        assertEquals(JailService.RELEASED, row(p.id()).jailState());
    }

    @Test
    void reconciliationDoesNothingWhenJailReloadedIsUnavailable() throws Exception {
        Punishment p = jail(Durations.HOUR).orThrow();
        fx.jail.available = false;
        r(fx.jails.reconcile()).orThrow();
        assertEquals(JailService.HELD, row(p.id()).jailState(), "never guess about a prisoner while JailReloaded cannot be asked");
    }

    // ------------------------------------------------------------------ safeguards
    @Test
    void theCommandGuardIndexKnowsWhoIsServingAJailPunishment() throws Exception {
        Punishment p = jail(Durations.HOUR).orThrow();
        fx.jails.refreshIndex().get();
        assertEquals(p.id(), fx.jails.heldByName("STEVE").orElseThrow().id());
        r(fx.jails.unjail(Fx.owner(), steve.uuid(), "Releasing so the guard no longer applies.")).orThrow();
        fx.jails.refreshIndex().get();
        assertTrue(fx.jails.heldByName("steve").isEmpty());
    }

    @Test
    void pretrialCustodyAndJailPunishmentsAreDistinct() throws Exception {
        Punishment p = jail(Durations.HOUR).orThrow();
        assertNull(fx.db.<dev.aura.punishments.model.Records.Custody>txSync(c -> Repo.Custodies.liveForPlayer(c, steve.uuid())), "a jail punishment is not pretrial custody");
        assertEquals(JailService.tag(p.id()).startsWith("AP-J"), true);
    }

    @Test
    void jailHistoryAndAuditEntriesExist() throws Exception {
        Punishment p = jail(Durations.HOUR).orThrow();
        r(fx.jails.unjail(Fx.owner(), steve.uuid(), "Releasing to check the audit trail.")).orThrow();
        var audit = fx.db.<List<dev.aura.punishments.model.Records.Audit>>txSync(c -> Repo.Audits.recent(c, 50, 0));
        assertTrue(audit.stream().anyMatch(a -> a.action().equals("punishment.jail")));
        assertTrue(audit.stream().anyMatch(a -> a.action().equals("jail.start")));
        assertTrue(audit.stream().anyMatch(a -> a.action().equals("punishment.revoke")));
        assertTrue(audit.stream().anyMatch(a -> a.action().equals("jail.release")));
        assertTrue(audit.stream().filter(a -> a.opId() != null).count() >= 3, "entries carry the operation id");
    }
}
