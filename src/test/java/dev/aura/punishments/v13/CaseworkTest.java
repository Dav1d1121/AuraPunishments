package dev.aura.punishments.v13;

import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.db.Sql;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Records.CaseEvent;
import dev.aura.punishments.model.Records.Evidence;
import dev.aura.punishments.model.Records.Note;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.model.Simple.ReviewStatus;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.model.Simple.Source;
import dev.aura.punishments.service.*;
import dev.aura.punishments.service.JudicialMode.State;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static dev.aura.punishments.v13.V13Support.fx;
import static dev.aura.punishments.v13.V13Support.r;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.3.1 case workflow: the judicial system is off, but staff still open a case, add evidence and notes, and punish FROM the case.
 * The punishment is final and immediate, linked to the case, and the case stays open until staff close it. No court step exists.
 */
class CaseworkTest {
    Fx fx;
    Actor mod;
    Target steve;

    static Actor staff(String name) {
        return Fx.staff(name, 20, Fx.concat(V13Support.punisher(), Perms.CASE_CREATE, Perms.CASE_VIEW_ALL, Perms.CASE_VIEW_OWN, Perms.CASE_MANAGE, Perms.CASE_CLOSE,
                Perms.punishPermanent(PunishType.BAN), Perms.APPEAL_FILE));
    }

    static YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(CaseworkTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() throws Exception {
        fx = fx(false, m -> m.set.put("casework.enabled", true));
        fx.core.mode.refresh().get();
        mod = staff("Mod");
        steve = Fx.target("Steve");
        fx.online.add(steve.uuid());
        fx.db.txSync(c -> { Repo.Profiles.upsert(c, steve.uuid(), "Steve", "10.1.2.3", 0, fx.now); return null; });
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    Case open(String offense, List<EvidenceValidator.Input> ev) throws Exception {
        return r(fx.cases.create(mod, new CaseService.CreateRequest(steve, offense, Severity.MODERATE, "Griefing the shared farm area repeatedly", null, ev))).orThrow();
    }

    Case open(String offense) throws Exception {
        return open(offense, List.of(Fx.ev()));
    }

    Case row(long id) throws Exception {
        return fx.db.txSync(c -> Repo.Cases.get(c, id));
    }

    Result<Punishment> punish(Case cs, PunishType t, long dur) throws Exception {
        return punish(mod, cs, t, dur, List.of(), null);
    }

    Result<Punishment> punish(Actor who, Case cs, PunishType t, long dur, List<EvidenceValidator.Input> ev, String note) throws Exception {
        return r(fx.punishments.issueForCase(who, cs.id(), new PunishmentService.Request(steve, t, dur, "Griefing the shared farm area.", ev, note)));
    }

    // ------------------------------------------------------------------ the switch
    @Test
    void theShippedFileGivesAFreshInstallTheCaseWorkflowWithTheJudicialSystemOff() throws Exception {
        Settings s = Settings.parse(shipped());
        assertFalse(s.judicial.enabled());
        assertTrue(s.casework.enabled());
        assertTrue(s.casework.requireCaseFor().isEmpty());
    }

    @Test
    void anUpgradedConfigWithoutTheSectionKeepsTheOldBehaviour() throws Exception {
        YamlConfiguration old = shipped();
        old.set("casework", null);
        old.set("judicial.enabled", false); // an admin who already switched the judicial system off under 1.3.0
        assertFalse(Settings.parse(old).casework.enabled(), "a missing section must not turn anything on by itself");
    }

    @Test
    void thereAreFourStatesAndCaseworkIsOneOfThem() throws Exception {
        assertEquals(State.CASEWORK, fx.core.mode.state());
        assertTrue(fx.core.mode.casework());
        assertTrue(fx.core.mode.casesAvailable());
        assertTrue(fx.core.mode.casesVisible());
        assertFalse(fx.core.mode.enabled());
        assertFalse(fx.core.mode.judicialVisible(), "no court screens while nothing from the court is left over");
    }

    @Test
    void caseworkOffAndJudicialOffStillRefusesCasesLikeVersion130() throws Exception {
        Fx plain = fx(false);
        try {
            plain.core.mode.refresh().get();
            var res = r(plain.cases.create(mod, new CaseService.CreateRequest(steve, "other", Severity.MINOR, "Spamming the chat again", null, List.of(Fx.ev()))));
            assertEquals(Code.UNAVAILABLE, res.code());
            assertTrue(res.message().contains("switched off"));
            assertEquals(State.DISABLED, plain.core.mode.state());
            assertFalse(plain.core.mode.casesAvailable());
        } finally {
            plain.close();
        }
    }

    @Test
    void withTheJudicialSystemOnPunishingFromACaseIsNotThePathAndNothingChanges() throws Exception {
        Fx full = fx(true);
        try {
            Case cs = r(full.cases.create(Fx.owner(), new CaseService.CreateRequest(steve, "other", Severity.MINOR, "Spamming the chat again", null, List.of(Fx.ev())))).orThrow();
            var res = r(full.punishments.issueForCase(Fx.owner(), cs.id(), new PunishmentService.Request(steve, PunishType.WARN, 0, "Spamming chat again.", List.of())));
            assertEquals(Code.UNAVAILABLE, res.code());
            assertTrue(res.message().contains("sentences"));
        } finally {
            full.close();
        }
    }

    // ------------------------------------------------------------------ cases without a court
    @Test
    void casesOpenNormallyButNoCourtStepExists() throws Exception {
        Case cs = open("harassment");
        assertEquals(CaseStatus.OPEN, cs.status());
        assertEquals(Code.UNAVAILABLE, r(fx.custody.initiate(Fx.owner(), cs.id())).code(), "no pretrial detention");
        var direct = r(fx.direct.direct(Fx.owner(), new DirectService.DirectRequest(steve, "other", Severity.SEVERE, "Serious griefing across many builds", "Obvious and repeated",
                List.of(Fx.ev()), PunishType.BAN, Durations.PERMANENT, null)));
        assertEquals(Code.UNAVAILABLE, direct.code(), "direct punishments exist to bypass the court");
        Actor owner = Fx.owner();
        assertEquals(Code.UNAVAILABLE, r(fx.cases.transition(owner, cs.id(), cs.version(), CaseStatus.AWAITING_TRIAL, null)).code());
        assertEquals(Code.UNAVAILABLE, r(fx.cases.transition(owner, cs.id(), cs.version(), CaseStatus.IN_TRIAL, null)).code());
        assertEquals(Code.UNAVAILABLE, r(fx.cases.assignJudge(owner, cs.id(), cs.version(), Fx.target("Judge"))).code());
        assertEquals(Code.UNAVAILABLE, r(fx.cases.scheduleTrial(owner, cs.id(), cs.version(), fx.now + Durations.DAY)).code());
        assertEquals(Code.UNAVAILABLE, r(fx.cases.setHearingRef(owner, cs.id(), cs.version(), "#hearing")).code());
        assertEquals(CaseStatus.OPEN, row(cs.id()).status(), "every refusal left the case untouched");
    }

    @Test
    void investigatingAndClosingAreAvailableAndAClosedCaseCanBeReopened() throws Exception {
        Case cs = open("harassment");
        Case inv = r(fx.cases.transition(mod, cs.id(), cs.version(), CaseStatus.UNDER_INVESTIGATION, null)).orThrow();
        assertEquals(CaseStatus.UNDER_INVESTIGATION, inv.status());
        Case closed = r(fx.cases.transition(mod, cs.id(), inv.version(), CaseStatus.CLOSED, "Nothing further to investigate here")).orThrow();
        assertEquals(CaseStatus.CLOSED, closed.status());
        Case review = r(fx.cases.transition(mod, cs.id(), closed.version(), CaseStatus.UNDER_REVIEW, "New evidence turned up later")).orThrow();
        Case reopened = r(fx.cases.transition(mod, cs.id(), review.version(), CaseStatus.OPEN, "Reopening to continue the work")).orThrow();
        assertEquals(CaseStatus.OPEN, reopened.status(), "the case workflow lets staff reopen a case; the plain-off mode does not");
    }

    @Test
    void closingTheCaseNeverLiftsWhatWasIssuedFromIt() throws Exception {
        Case cs = open("harassment");
        Punishment mute = punish(cs, PunishType.MUTE, Durations.DAY).orThrow();
        Punishment jail = punish(cs, PunishType.JAIL, Durations.HOUR).orThrow();
        Case closed = r(fx.cases.transition(mod, cs.id(), cs.version(), CaseStatus.CLOSED, "Closing the case, the punishments run their course")).orThrow();
        assertEquals(CaseStatus.CLOSED, closed.status());
        assertTrue(fx.punishments.muteFor(steve.uuid()).isPresent(), "the mute is still enforced");
        assertEquals(PunishStatus.ACTIVE, fx.db.<Punishment>txSync(c -> Repo.Punishments.get(c, mute.id())).status());
        assertEquals(JailService.HELD, fx.db.<Punishment>txSync(c -> Repo.Punishments.get(c, jail.id())).jailState());
        assertEquals(1, fx.jail.prisoners.size(), "the prisoner is still held by JailReloaded");
    }

    @Test
    void theAccusedIsNotToldAboutHearingsBecauseThereAreNone() throws Exception {
        Case cs = open("harassment");
        r(fx.cases.transition(mod, cs.id(), cs.version(), CaseStatus.UNDER_INVESTIGATION, null)).orThrow();
        Thread.sleep(150);
        long defendantNotices = fx.db.<Long>txSync(c -> Sql.count(c, "SELECT COUNT(*) FROM player_notifications WHERE kind='DEFENDANT'"));
        assertEquals(0, defendantNotices);
    }

    // ------------------------------------------------------------------ punishing from a case
    @Test
    void aPunishmentFromACaseIsFinalImmediateLinkedAndTheCaseStaysOpen() throws Exception {
        Case cs = open("harassment");
        Punishment mute = punish(cs, PunishType.MUTE, Durations.HOUR).orThrow();
        assertEquals(PunishStatus.ACTIVE, mute.status());
        assertEquals(Source.STANDARD, mute.source(), "not a judicial sentence: there was no court");
        assertEquals(cs.id(), mute.caseId());
        assertEquals("harassment", mute.offenseKey());
        assertTrue(fx.punishments.muteFor(steve.uuid()).isPresent(), "enforceable the moment it was issued");
        assertEquals(CaseStatus.OPEN, row(cs.id()).status(), "the case stays open for notes, evidence and appeals");
        List<CaseEvent> events = fx.db.txSync(c -> Repo.Events.forCase(c, cs.id()));
        assertTrue(events.stream().anyMatch(e -> e.type().equals("PUNISHMENT_ISSUED") && e.detail().contains("#" + mute.id())), "the case timeline records the punishment");
        assertTrue(fx.db.<List<Punishment>>txSync(c -> Repo.Punishments.forCase(c, cs.id())).stream().anyMatch(p -> p.id() == mute.id()));
        // more work on the same case afterwards
        r(fx.cases.addNote(mod, cs.id(), null, "Spoke with the player on Discord afterwards.", false)).orThrow();
        r(fx.cases.addEvidence(mod, cs.id(), Fx.ev("https://example.com/later.png"))).orThrow();
        assertEquals(CaseStatus.OPEN, row(cs.id()).status());
    }

    @Test
    void evidenceAndNotesSuppliedWhilePunishingBelongToTheCase() throws Exception {
        Case cs = open("harassment");
        Punishment ban = punish(mod, cs, PunishType.BAN, Durations.DAY, List.of(Fx.ev("https://example.com/new.png")), "Third incident this week.").orThrow();
        List<Evidence> onCase = fx.db.txSync(c -> Repo.Evidences.forCase(c, cs.id(), false));
        assertEquals(2, onCase.size(), "the evidence the case was opened with plus the new item");
        assertTrue(onCase.stream().anyMatch(e -> "https://example.com/new.png".equals(e.reference())));
        assertTrue(fx.db.<List<Evidence>>txSync(c -> Repo.Evidences.forPunishment(c, ban.id(), false)).isEmpty(), "nothing is stored twice");
        List<Note> notes = fx.db.txSync(c -> Repo.Notes.forCase(c, cs.id()));
        assertEquals(1, notes.size());
        assertEquals("Third incident this week.", notes.get(0).text());
        assertEquals(CaseStatus.OPEN, row(cs.id()).status());
    }

    @Test
    void everyPunishmentTypeCanBeIssuedFromAnAppropriateCase() throws Exception {
        Case h = open("harassment"); // warn mute ban jail
        assertEquals(PunishType.WARN, punish(h, PunishType.WARN, 0).orThrow().type());
        assertEquals(PunishType.MUTE, punish(h, PunishType.MUTE, Durations.HOUR).orThrow().type());
        Punishment jail = punish(h, PunishType.JAIL, Durations.HOUR).orThrow();
        assertEquals(JailService.HELD, jail.jailState());
        assertEquals(1, fx.jail.prisoners.size(), "JailReloaded was asked and confirmed");
        assertEquals(h.id(), jail.caseId());
        assertNull(jail.custodyId(), "this is a jail sentence, not pretrial custody");
        Punishment kick = punish(h, PunishType.KICK, 0).orThrow();
        assertEquals(PunishStatus.COMPLETED, kick.status(), "a kick is not governed by the offense's sentence list");
        Case ch = r(fx.cases.create(mod, new CaseService.CreateRequest(steve, "cheating", Severity.SEVERE, "Using a fly hack to raid bases", null, List.of(Fx.ev())))).orThrow();
        Punishment ip = punish(ch, PunishType.IPBAN, Durations.DAY).orThrow();
        assertEquals("10.1.2.3", ip.ip());
        assertEquals(ch.id(), ip.caseId());
    }

    @Test
    void theOffensesOwnRulesStillApply() throws Exception {
        Case h = open("harassment"); // max 30d, no IP ban
        var ip = punish(h, PunishType.IPBAN, Durations.DAY);
        assertEquals(Code.INVALID, ip.code());
        assertTrue(ip.message().contains("cannot result in"));
        assertEquals(Code.LIMIT, punish(h, PunishType.BAN, 60 * Durations.DAY).code(), "longer than the offense allows");
        assertEquals(Code.LIMIT, punish(h, PunishType.BAN, Durations.PERMANENT).code(), "permanent where the offense is capped");
        assertTrue(fx.db.<List<Punishment>>txSync(c -> Repo.Punishments.forCase(c, h.id())).isEmpty(), "nothing was issued");
    }

    @Test
    void theCaseAndTheTargetMustAgree() throws Exception {
        Case cs = open("harassment");
        Target someoneElse = Fx.target("Alex");
        var res = r(fx.punishments.issueForCase(mod, cs.id(), new PunishmentService.Request(someoneElse, PunishType.WARN, 0, "Wrong player entirely here.", List.of())));
        assertEquals(Code.INVALID, res.code());
        assertTrue(res.message().contains("Steve"));
        assertEquals(Code.NOT_FOUND, r(fx.punishments.issueForCase(mod, 9999, new PunishmentService.Request(steve, PunishType.WARN, 0, "No such case exists.", List.of()))).code());
    }

    @Test
    void aFinishedCaseMustBeReopenedBeforeItCanBePunishedFrom() throws Exception {
        Case cs = open("harassment");
        Case closed = r(fx.cases.transition(mod, cs.id(), cs.version(), CaseStatus.CLOSED, "Closing the case for now")).orThrow();
        var refused = punish(closed, PunishType.WARN, 0);
        assertEquals(Code.STATE, refused.code());
        Case review = r(fx.cases.transition(mod, cs.id(), closed.version(), CaseStatus.UNDER_REVIEW, "More evidence has arrived")).orThrow();
        assertTrue(punish(review, PunishType.WARN, 0).isOk(), "an open case in review can be punished from");
    }

    @Test
    void evidenceAlreadyOnTheCaseCountsTowardTheRequirement() throws Exception {
        Case withEvidence = open("harassment");
        assertTrue(punish(withEvidence, PunishType.BAN, Durations.DAY).isOk(), "bans need evidence by default; the case already holds some");
        fx.punishments.revoke(Fx.owner(), fx.db.<Long>txSync(c -> Repo.Punishments.forCase(c, withEvidence.id()).get(0).id()), "Resetting for the next check here.").get();
        Case bare = open("other", List.of());
        var res = punish(bare, PunishType.BAN, Durations.DAY);
        assertEquals(Code.EVIDENCE, res.code(), "no evidence on the case and none supplied");
        assertTrue(punish(mod, bare, PunishType.BAN, Durations.DAY, List.of(Fx.ev("https://example.com/p.png")), null).isOk(), "evidence supplied while punishing satisfies it");
    }

    // ------------------------------------------------------------------ authority
    @Test
    void permissionsAndHierarchyAreCheckedExactlyAsForQuickPunishments() throws Exception {
        Case cs = open("harassment");
        Actor noBan = Fx.staff("Helper", 20, Fx.concat(V13Support.punisher(), Perms.CASE_VIEW_ALL));
        Actor nobody = Fx.staff("Nobody", 20, Perms.PUNISH, Perms.PUNISH_OTHERS);
        // warn/mute are held by the helper (punisher()), so use a type the helper lacks
        Actor warnOnly = Fx.staff("WarnOnly", 20, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.CASE_VIEW_ALL, Perms.punish(PunishType.WARN));
        assertEquals(Code.NO_PERMISSION, punish(warnOnly, cs, PunishType.BAN, Durations.DAY, List.of(), null).code());
        assertEquals(Code.NO_PERMISSION, punish(nobody, cs, PunishType.WARN, 0, List.of(), null).code(), "nobody holding a punish node cannot");
        assertEquals(Code.NO_PERMISSION, punish(warnOnly, cs, PunishType.MUTE, Durations.HOUR, List.of(), null).code());
        assertTrue(punish(noBan, cs, PunishType.WARN, 0, List.of(), null).isOk());
        fx.auth.put(steve.uuid(), new LevelResolver.TargetAuth(80, false)); // the accused outranks the staff member
        assertEquals(Code.HIERARCHY, punish(cs, PunishType.MUTE, Durations.HOUR).code());
    }

    @Test
    void whoCannotSeeTheCaseCannotPunishFromIt() throws Exception {
        Case cs = open("harassment");
        Actor blind = Fx.staff("Blind", 20, Fx.concat(V13Support.punisher()));
        assertEquals(Code.NO_PERMISSION, punish(blind, cs, PunishType.WARN, 0, List.of(), null).code());
    }

    @Test
    void durationLimitsApplyToCasePunishmentsToo() throws Exception {
        Case cs = open("harassment");
        Actor shortOnly = Fx.staff("Short", 20, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.CASE_VIEW_ALL, Perms.punish(PunishType.MUTE), Perms.limit(PunishType.MUTE, "short"));
        var res = punish(shortOnly, cs, PunishType.MUTE, 20 * Durations.DAY, List.of(), null);
        assertFalse(res.isOk(), "above the staff member's own tier");
        assertTrue(punish(shortOnly, cs, PunishType.MUTE, Durations.HOUR, List.of(), null).isOk());
    }

    // ------------------------------------------------------------------ quick punishments, review, appeals
    @Test
    void theQuickButtonsStillWorkWithoutACase() throws Exception {
        Punishment p = r(fx.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.WARN, 0, "Please keep chat friendly.", List.of()))).orThrow();
        assertNull(p.caseId());
        assertEquals(0L, (long) fx.db.<Long>txSync(c -> Sql.count(c, "SELECT COUNT(*) FROM cases")), "no hidden case is invented");
    }

    @Test
    void requireCaseForSendsTheNamedTypesThroughACase() throws Exception {
        Fx strict = fx(false, m -> {
            m.set.put("casework.enabled", true);
            m.set.put("casework.require-case-for", List.of("BAN", "IPBAN", "JAIL"));
        });
        try {
            strict.core.mode.refresh().get();
            var quick = r(strict.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.BAN, Durations.DAY, "Repeated griefing of builds.", List.of(Fx.ev()))));
            assertEquals(Code.INVALID, quick.code());
            assertTrue(quick.message().contains("from a case"));
            assertTrue(r(strict.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.MUTE, Durations.HOUR, "Spamming the global channel.", List.of()))).isOk(),
                    "types that are not listed stay quick");
            Case cs = r(strict.cases.create(mod, new CaseService.CreateRequest(steve, "harassment", Severity.MODERATE, "Griefing the shared farm area repeatedly", null, List.of(Fx.ev())))).orThrow();
            assertTrue(r(strict.punishments.issueForCase(mod, cs.id(), new PunishmentService.Request(steve, PunishType.BAN, Durations.DAY, "Repeated griefing of builds.", List.of()))).isOk());
        } finally {
            strict.close();
        }
    }

    @Test
    void anOwnerReviewIsCreatedForACaseBanAndLinkedToTheCase() throws Exception {
        Case ch = r(fx.cases.create(mod, new CaseService.CreateRequest(steve, "cheating", Severity.SEVERE, "Using a fly hack to raid bases", null, List.of(Fx.ev())))).orThrow();
        Punishment ban = punish(ch, PunishType.BAN, Durations.PERMANENT).orThrow();
        var rv = fx.db.txSync(c -> Repo.Reviews.byPunishment(c, ban.id()));
        assertNotNull(rv, "a permanent ban is reviewed by the owner exactly as a quick one is");
        assertEquals(ReviewStatus.PENDING_REVIEW, rv.status());
        assertEquals(ch.id(), rv.caseId(), "the review points at the case");
        // evidence added through the review lands on the case
        Evidence added = r(fx.reviews.submitEvidence(mod, rv.id(), Fx.ev("https://example.com/review.png"))).orThrow();
        assertEquals(ch.id(), added.caseId());
    }

    @Test
    void appealsAreFiledAgainstThePunishmentNotTheCase() throws Exception {
        Case cs = open("harassment");
        Punishment mute = punish(cs, PunishType.MUTE, Durations.HOUR).orThrow();
        assertFalse(fx.appeals.viaCase(mute), "there is no case-level appeal without a court");
        var appeal = r(fx.appeals.file(mod, mute.id(), "I was not the player who did this at all.", null));
        assertTrue(appeal.isOk(), appeal.isOk() ? "" : appeal.message());
        assertEquals(CaseStatus.OPEN, row(cs.id()).status());
    }

    // ------------------------------------------------------------------ leftovers from the court
    @Test
    void courtWorkLeftOverFromBeforeIsReportedAndNeverResolvedForYou() throws Exception {
        fx.close();
        fx = fx(true);
        Case cs = r(fx.cases.create(Fx.owner(), new CaseService.CreateRequest(steve, "other", Severity.MODERATE, "Griefing the spawn area repeatedly", null, List.of(Fx.ev())))).orThrow();
        r(fx.custody.initiate(Fx.owner(), cs.id())).orThrow();
        YamlConfiguration y = shipped();
        y.set("judicial.enabled", false);
        y.set("casework.enabled", true);
        fx.core.settings = Settings.parse(y);
        fx.core.mode.refresh().get();
        assertEquals(State.CASEWORK, fx.core.mode.state());
        assertEquals(1, fx.core.mode.report().pretrialDetainees());
        assertTrue(fx.core.mode.report().courtLeftover() > 0);
        assertTrue(fx.core.mode.judicialVisible(), "the court screens come back only so the leftovers can be finished");
        assertFalse(fx.core.mode.report().courtLines().isEmpty());
        assertEquals(1, fx.jail.prisoners.size(), "the detainee was not released by a configuration change");
        // finishing the existing work is allowed, and then the court screens go away again
        r(fx.custody.release(Fx.owner(), cs.id(), "Released while the court is retired")).orThrow();
        Case now = row(cs.id());
        r(fx.cases.transition(Fx.owner(), cs.id(), now.version(), CaseStatus.CLOSED, "Closing the leftover case")).orThrow();
        fx.core.mode.refresh().get();
        assertEquals(0, fx.core.mode.report().courtLeftover());
        assertFalse(fx.core.mode.judicialVisible());
    }
}
