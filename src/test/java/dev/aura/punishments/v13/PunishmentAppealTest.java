package dev.aura.punishments.v13;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Appeal;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.AppealStatus;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.service.*;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result.Code;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.aura.punishments.v13.V13Support.fx;
import static dev.aura.punishments.v13.V13Support.mod;
import static dev.aura.punishments.v13.V13Support.r;
import static org.junit.jupiter.api.Assertions.*;

/** Appeals against a punishment (Discord Ticket Tool reference), decided with aurapunishments.appeal.decide. */
class PunishmentAppealTest {
    Fx fx;
    Actor mod;
    Actor appealer;
    Actor decider;
    Target steve;
    Punishment ban;

    @BeforeEach
    void setUp() throws Exception {
        fx = fx(false);
        mod = mod("Mod");
        appealer = Fx.staff("Support", 20, Perms.HISTORY_VIEW, Perms.APPEAL_FILE, Perms.APPEAL_TICKET);
        decider = Fx.staff("Reviewer", 60, Perms.HISTORY_VIEW, Perms.APPEAL_DECIDE, Perms.REVOKE_ANY);
        steve = Fx.target("Steve");
        ban = r(fx.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.BAN, 10 * Durations.DAY, "Repeated griefing of builds.", List.of(Fx.ev())))).orThrow();
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    Appeal file(String ticket) throws Exception {
        return r(fx.appeals.file(appealer, ban.id(), "The player says it was a misunderstanding.", ticket)).orThrow();
    }

    Punishment row() throws Exception {
        return fx.db.txSync(c -> Repo.Punishments.get(c, ban.id()));
    }

    @Test
    void filingAnAppealRecordsItWithTheTicketAndDoesNotSuspendThePunishment() throws Exception {
        Appeal a = file("0042");
        assertEquals(AppealStatus.PENDING, a.status());
        assertEquals("0042", a.ticket());
        assertEquals(ban.id(), a.punishmentId());
        assertNull(a.caseId());
        assertEquals(PunishStatus.ACTIVE, row().status());
        assertTrue(fx.punishments.banFor(steve.uuid(), null).isPresent(), "an appeal never lifts a punishment by itself");
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.APPEAL && e.fields().containsKey("Ticket")));
    }

    @Test
    void ticketReferencesAreNormalisedAndGarbageIsRefused() throws Exception {
        assertEquals("appeal-0042", file("appeal-0042").ticket());
        r(fx.appeals.take(decider, fx.db.<Appeal>txSync(c -> Repo.Appeals.pendingForPunishment(c, ban.id())).id()));
        r(fx.appeals.decide(decider, fx.db.<Appeal>txSync(c -> Repo.Appeals.pendingForPunishment(c, ban.id())).id(), AppealStatus.UPHELD, "The ban was correct as issued.", null)).orThrow();
        assertEquals(Code.INVALID, r(fx.appeals.file(appealer, ban.id(), "The player says it was a misunderstanding.", "javascript:alert(1)")).code());
        assertTrue(r(fx.appeals.file(appealer, ban.id(), "The player says it was a misunderstanding.", "https://discord.com/channels/1/2")).isOk());
    }

    @Test
    void aTicketCanBeRequiredByConfiguration() throws Exception {
        Fx f = V13Support.fx(false, m -> m.set.put("appeals.require-ticket", true));
        try {
            Punishment b = r(f.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.BAN, Durations.DAY, "Repeated griefing of builds.", List.of(Fx.ev())))).orThrow();
            assertEquals(Code.INVALID, r(f.appeals.file(appealer, b.id(), "The player says it was a misunderstanding.", null)).code());
            assertTrue(r(f.appeals.file(appealer, b.id(), "The player says it was a misunderstanding.", "0042")).isOk());
        } finally {
            f.close();
        }
    }

    @Test
    void theTicketCanBeLinkedLaterByAuthorisedStaffOnly() throws Exception {
        Appeal a = file(null);
        assertNull(a.ticket());
        Actor nobody = Fx.staff("Nobody", 20, Perms.HISTORY_VIEW);
        assertEquals(Code.NO_PERMISSION, r(fx.appeals.linkTicket(nobody, a.id(), "0042")).code());
        assertEquals("0042", r(fx.appeals.linkTicket(appealer, a.id(), "0042")).orThrow().ticket());
    }

    @Test
    void onlyOneOpenAppealAtATimeAndFilingNeedsThePermission() throws Exception {
        file("1");
        assertEquals(Code.DUPLICATE, r(fx.appeals.file(appealer, ban.id(), "The player says it was a misunderstanding.", "2")).code());
        Actor nobody = Fx.staff("Nobody", 20, Perms.HISTORY_VIEW);
        assertEquals(Code.NO_PERMISSION, r(fx.appeals.file(nobody, ban.id(), "The player says it was a misunderstanding.", null)).code());
    }

    @Test
    void decidingNeedsTheNewPermissionAndTheJudicialOverturnNodeIsNotEnoughWhenTheSystemIsOff() throws Exception {
        Appeal a = file("0042");
        Actor judge = Fx.staff("Judge", 60, Perms.HISTORY_VIEW, Perms.JUDICIAL_OVERTURN);
        assertEquals(Code.NO_PERMISSION, r(fx.appeals.decide(judge, a.id(), AppealStatus.UPHELD, "The original decision stands.", null)).code());
        assertEquals(Code.NO_PERMISSION, r(fx.appeals.take(judge, a.id())).code());
        Actor nobody = Fx.staff("Nobody", 60, Perms.HISTORY_VIEW);
        assertEquals(Code.NO_PERMISSION, r(fx.appeals.decide(nobody, a.id(), AppealStatus.UPHELD, "The original decision stands.", null)).code());
        assertTrue(r(fx.appeals.take(decider, a.id())).isOk());
    }

    @Test
    void judicialOverturnIsAcceptedAsWellWhileTheJudicialSystemIsOn() throws Exception {
        Fx on = V13Support.fx(true);
        try {
            Punishment b = r(on.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.MUTE, Durations.DAY, "Spamming the global channel.", List.of()))).orThrow();
            Appeal a = r(on.appeals.file(appealer, b.id(), "The player says it was a misunderstanding.", "7")).orThrow();
            Actor judge = Fx.staff("Judge", 60, Perms.HISTORY_VIEW, Perms.JUDICIAL_OVERTURN, Perms.REVOKE_ANY);
            assertTrue(r(on.appeals.decide(judge, a.id(), AppealStatus.UPHELD, "The original decision stands.", null)).isOk());
        } finally {
            on.close();
        }
    }

    @Test
    void upholdKeepsThePunishmentAndRecordsReviewerAndRationale() throws Exception {
        Appeal a = file("0042");
        r(fx.appeals.take(decider, a.id())).orThrow();
        assertEquals(AppealStatus.UNDER_REVIEW, fx.db.<Appeal>txSync(c -> Repo.Appeals.get(c, a.id())).status());
        assertEquals(Code.STATE, r(fx.appeals.take(decider, a.id())).code(), "already under review");
        var done = r(fx.appeals.decide(decider, a.id(), AppealStatus.UPHELD, "The evidence is clear; the ban stands.", null)).orThrow();
        assertEquals(AppealStatus.UPHELD, done.status());
        assertEquals("Reviewer", done.decidedByName());
        assertEquals("The evidence is clear; the ban stands.", done.decisionReason());
        assertEquals(PunishStatus.ACTIVE, row().status());
        assertEquals(Code.STATE, r(fx.appeals.decide(decider, a.id(), AppealStatus.OVERTURNED, "Changing my mind about it.", null)).code(), "a decision is final");
    }

    @Test
    void overturnRevokesThePunishmentAtOnceAndRecordsWhy() throws Exception {
        Appeal a = file("0042");
        r(fx.appeals.decide(decider, a.id(), AppealStatus.OVERTURNED, "New evidence shows the wrong player was banned.", null)).orThrow();
        Punishment after = row();
        assertEquals(PunishStatus.REVOKED, after.status());
        assertTrue(after.endReason().startsWith("Appeal overturned"));
        assertTrue(fx.punishments.banFor(steve.uuid(), null).isEmpty(), "the player can log in again");
        assertEquals(-1L, (long) fx.db.<Long>txSync(c -> Repo.Audits.verify(c)));
    }

    @Test
    void reducingShortensThePunishmentAndKeepsTheOriginalExpiry() throws Exception {
        Appeal a = file("0042");
        long originalExpiry = ban.expiresAt();
        assertEquals(Code.INVALID, r(fx.appeals.decide(decider, a.id(), AppealStatus.MODIFIED, "Reducing it to a shorter ban.", null)).code(), "the new length is required");
        assertEquals(Code.INVALID, r(fx.appeals.decide(decider, a.id(), AppealStatus.MODIFIED, "Reducing it to a longer ban.", 20 * Durations.DAY)).code(), "an appeal can only reduce");
        var done = r(fx.appeals.decide(decider, a.id(), AppealStatus.MODIFIED, "First offence; reducing the ban to three days.", 3 * Durations.DAY)).orThrow();
        assertEquals(AppealStatus.MODIFIED, done.status());
        Punishment after = row();
        assertEquals(ban.issuedAt() + 3 * Durations.DAY, after.expiresAt());
        assertEquals(originalExpiry, after.originalExpiresAt());
        assertEquals(PunishStatus.ACTIVE, after.status());
        assertEquals(after.expiresAt(), fx.punishments.banFor(steve.uuid(), null).orElseThrow().expiresAt(), "the cached copy reflects the reduction immediately");
    }

    @Test
    void hierarchyAppliesToDecidingAnAppeal() throws Exception {
        Appeal a = file("0042");
        fx.auth.put(steve.uuid(), new LevelResolver.TargetAuth(80, false)); // the banned player outranks the reviewer
        assertEquals(Code.HIERARCHY, r(fx.appeals.decide(decider, a.id(), AppealStatus.UPHELD, "The original decision stands.", null)).code());
    }

    @Test
    void warningsCanBeOverturnedButNotReduced() throws Exception {
        Punishment w = r(fx.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.WARN, 0, "Please keep chat friendly.", List.of()))).orThrow();
        Appeal a = r(fx.appeals.file(appealer, w.id(), "The player says it was a joke between friends.", null)).orThrow();
        assertEquals(Code.INVALID, r(fx.appeals.decide(decider, a.id(), AppealStatus.MODIFIED, "Reducing a warning to something.", Durations.HOUR)).code());
        assertTrue(r(fx.appeals.decide(decider, a.id(), AppealStatus.OVERTURNED, "A friendly joke; removing the warning.", null)).isOk());
        assertEquals(PunishStatus.REVOKED, fx.db.<Punishment>txSync(c -> Repo.Punishments.get(c, w.id())).status());
    }

    @Test
    void aPunishmentThatIsAlreadyOverCannotBeAppealed() throws Exception {
        r(fx.punishments.revoke(mod, ban.id(), "Revoking this ban before the appeal test."));
        assertEquals(Code.STATE, r(fx.appeals.file(appealer, ban.id(), "The player says it was a misunderstanding.", null)).code());
    }

    @Test
    void openAppealsAreListedForTheQueue() throws Exception {
        file("0042");
        var page = r(fx.appeals.open(decider, 0, 10)).orThrow();
        assertEquals(1, page.total());
        assertEquals(ban.id(), page.items().get(0).punishmentId());
        assertEquals(1L, (long) fx.db.<Long>txSync(c -> Repo.Appeals.countPending(c)));
    }

    @Test
    void appealsAreNotCaseOnlyAnymoreSoThePunishmentAndItsHistoryShowThem() throws Exception {
        Appeal a = file("0042");
        r(fx.appeals.decide(decider, a.id(), AppealStatus.UPHELD, "The evidence is clear; the ban stands.", null)).orThrow();
        var detail = r(fx.records.detail(decider, ban.id())).orThrow();
        assertEquals(1, detail.appeals().size());
        assertEquals(AppealStatus.UPHELD, detail.appeals().get(0).status());
    }
}
