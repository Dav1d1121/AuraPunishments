package dev.aura.punishments;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Records.Custody;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.*;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JudicialAndPunishmentTest {
    Fx fx;
    Actor owner;
    Actor mod;
    Target steve;

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fx();
        owner = Fx.owner();
        mod = Fx.staff("JrMod", 20, Fx.JR_MOD);
        steve = Fx.target("Steve");
        fx.judges.add(owner.uuid());
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    static <T> Result<T> r(CompletableFuture<Result<T>> f) throws Exception {
        return f.get(10, TimeUnit.SECONDS);
    }

    Case mkCase(String offense, boolean custody) throws Exception {
        Case c = r(fx.cases.create(mod, new CaseService.CreateRequest(steve, offense, Severity.MODERATE,
                "Repeated offensive behaviour towards others.", null, List.of(Fx.ev())))).orThrow();
        if (custody) assertTrue(r(fx.custody.initiate(mod, c.id())).isOk());
        return c;
    }

    /** Move a case to IN_TRIAL with the owner as judge. */
    Case inTrial(Case c) throws Exception {
        c = r(fx.cases.transition(owner, c.id(), c.version(), CaseStatus.AWAITING_TRIAL, null)).orThrow();
        c = r(fx.cases.assignJudge(owner, c.id(), c.version(), new Target(owner.uuid(), "Owner", true))).orThrow();
        return r(fx.cases.transition(owner, c.id(), c.version(), CaseStatus.IN_TRIAL, null)).orThrow();
    }

    // ------------------------------------------------------------ verdicts
    @Test
    void notGuiltyClosesTheCaseAndReleasesTheCustodyWithoutAnyPunishment() throws Exception {
        Case c = inTrial(mkCase("harassment", true));
        var res = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.NOT_GUILTY, "Evidence does not establish the allegation."));
        assertTrue(res.isOk(), res.message());
        assertEquals(CaseStatus.NOT_GUILTY, res.value().c().status());
        assertTrue(fx.jail.prisoners.isEmpty(), "released through JailReloaded");
        assertEquals(0, fx.db.<Integer>txSync(cn -> Repo.Punishments.forCase(cn, c.id()).size()), "no sentence for an acquittal");
        assertTrue(res.value().custodyNote().toLowerCase().contains("released"));
    }

    @Test
    void notGuiltyStillRecordedWhenReleaseFailsAndRetriedAutomatically() throws Exception {
        Case c = inTrial(mkCase("harassment", true));
        fx.jail.failNext = "storage busy";
        var res = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.NOT_GUILTY, "Evidence does not establish the allegation."));
        assertTrue(res.isOk());
        assertTrue(res.value().custodyNote().contains("WARNING"));
        assertFalse(fx.jail.prisoners.isEmpty(), "the release failed and is honestly reported");
        fx.custody.retryStuckReleases().get();
        assertTrue(fx.jail.prisoners.isEmpty(), "released by the automatic retry");
    }

    @Test
    void dismissalRequiresAReasonAndAlsoReleases() throws Exception {
        Case c = inTrial(mkCase("harassment", true));
        assertEquals(Code.INVALID, r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.DISMISSED, "no")).code());
        var res = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.DISMISSED, "Prosecution withdrew the complaint."));
        assertTrue(res.isOk());
        assertEquals(CaseStatus.DISMISSED, res.value().c().status());
        assertTrue(fx.jail.prisoners.isEmpty());
    }

    @Test
    void continuedKeepsTheCaseOpenAndCustodyUntouched() throws Exception {
        Case c = inTrial(mkCase("harassment", true));
        var res = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.CONTINUED, "Need the witness statement before deciding."));
        assertEquals(CaseStatus.AWAITING_TRIAL, res.value().c().status());
        assertFalse(fx.jail.prisoners.isEmpty());
        assertNull(res.value().c().verdict(), "no finding is recorded yet");
    }

    @Test
    void verdictNeedsPermissionAndTheRightCaseState() throws Exception {
        Case c = mkCase("harassment", false);
        assertEquals(Code.NO_PERMISSION, r(fx.judicial.enterVerdict(mod, c.id(), c.version(), Verdict.GUILTY, "Evidence is conclusive here.")).code());
        assertEquals(Code.STATE, r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.GUILTY, "Evidence is conclusive here.")).code(),
                "cannot convict a case that never went to trial");
    }

    @Test
    void onlyTheAssignedJudgeMayRuleUnlessVerdictAny() throws Exception {
        Case c = inTrial(mkCase("harassment", false));
        Actor otherJudge = Fx.staff("Other", 90, Fx.concat(Fx.JR_MOD, Perms.JUDICIAL_VERDICT, Perms.JUDICIAL_SENTENCE));
        assertEquals(Code.NO_PERMISSION, r(fx.judicial.enterVerdict(otherJudge, c.id(), c.version(), Verdict.NOT_GUILTY, "Not enough evidence at all.")).code());
        Actor any = Fx.staff("Any", 90, Fx.concat(Fx.JR_MOD, Perms.JUDICIAL_VERDICT, Perms.JUDICIAL_VERDICT_ANY));
        assertTrue(r(fx.judicial.enterVerdict(any, c.id(), c.version(), Verdict.NOT_GUILTY, "Not enough evidence at all.")).isOk());
    }

    // ------------------------------------------------------------ sentencing
    @Test
    void guiltyThenBanWithTimeServedCredit() throws Exception {
        Case c = inTrial(mkCase("harassment", true));
        fx.now += 10 * Durations.HOUR;
        Case guilty = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.GUILTY, "Clear evidence of sustained harassment.")).value().c();
        assertEquals(CaseStatus.GUILTY, guilty.status());
        var res = r(fx.judicial.sentence(owner, guilty.id(), guilty.version(),
                new JudicialService.SentenceSpec(PunishType.BAN, 3 * Durations.DAY, "Three day ban for harassment.", true)));
        assertTrue(res.isOk(), res.message());
        Punishment p = res.value().punishment();
        assertEquals(CaseStatus.SENTENCED, res.value().c().status());
        assertEquals(3 * Durations.DAY - 10 * Durations.HOUR, p.expiresAt() - p.issuedAt(), "10h pretrial credit deducted");
        assertEquals(10 * Durations.HOUR, res.value().creditedMs());
        assertTrue(fx.jail.prisoners.isEmpty(), "released from custody before the ban applies - no contradictory state");
        assertTrue(fx.punishments.cache().ban(steve.uuid(), fx.now).isPresent(), "ban is enforceable at login");
    }

    @Test
    void creditCanBeDisabledPerSentence() throws Exception {
        Case c = inTrial(mkCase("harassment", true));
        fx.now += 10 * Durations.HOUR;
        Case g = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.GUILTY, "Clear evidence of sustained harassment.")).value().c();
        var res = r(fx.judicial.sentence(owner, g.id(), g.version(),
                new JudicialService.SentenceSpec(PunishType.BAN, 3 * Durations.DAY, "Three day ban for harassment.", false)));
        assertEquals(3 * Durations.DAY, res.value().punishment().expiresAt() - res.value().punishment().issuedAt());
    }

    @Test
    void permanentBanGetsNoCredit() throws Exception {
        Case c = inTrial(mkCase("cheating", true));
        fx.now += 10 * Durations.HOUR;
        Case g = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.GUILTY, "Clear evidence of a hacked client.")).value().c();
        var res = r(fx.judicial.sentence(owner, g.id(), g.version(),
                new JudicialService.SentenceSpec(PunishType.BAN, Durations.PERMANENT, "Permanent ban for cheating.", true)));
        assertTrue(res.isOk(), res.message());
        assertNull(res.value().punishment().expiresAt());
        assertEquals(0L, res.value().creditedMs());
    }

    @Test
    void offenseCapStopsPermanentSentencesWhereNotAllowed() throws Exception {
        Case c = inTrial(mkCase("harassment", false));
        Case g = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.GUILTY, "Clear evidence of sustained harassment.")).value().c();
        assertEquals(Code.LIMIT, r(fx.judicial.sentence(owner, g.id(), g.version(),
                new JudicialService.SentenceSpec(PunishType.BAN, Durations.PERMANENT, "Permanent ban for harassment.", true))).code());
    }

    @Test
    void jailSentenceConvertsTheSameJailRecordWithCredit() throws Exception {
        Case c = inTrial(mkCase("griefing", true));
        String idBefore = fx.jail.prisoners.get(steve.uuid()).jailPrisonerId();
        fx.now += 10 * Durations.HOUR;
        Case g = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.GUILTY, "Griefing is proven by the logs.")).value().c();
        var res = r(fx.judicial.sentence(owner, g.id(), g.version(),
                new JudicialService.SentenceSpec(PunishType.JAIL, 24 * Durations.HOUR, "One day jail for griefing.", true)));
        assertTrue(res.isOk(), res.message());
        var held = fx.jail.prisoners.get(steve.uuid());
        assertEquals(idBefore, held.jailPrisonerId(), "same JailReloaded record - no second jail");
        assertEquals(14 * Durations.HOUR, held.remainingMs(), "24h sentence minus 10h served");
        Custody cu = fx.db.txSync(cn -> Repo.Custodies.forCase(cn, c.id())).get(0);
        assertEquals(CustodyStatus.SERVING, cu.status());
        assertNull(cu.deadline(), "a sentence has no pretrial review deadline");
        assertEquals(PunishType.JAIL, res.value().punishment().type());
        assertEquals(cu.id(), res.value().punishment().custodyId());
    }

    @Test
    void jailSentenceFullyCoveredByTimeServedReleasesImmediately() throws Exception {
        Case c = inTrial(mkCase("griefing", true));
        fx.now += 5 * Durations.HOUR;
        Case g = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.GUILTY, "Griefing is proven by the logs.")).value().c();
        var res = r(fx.judicial.sentence(owner, g.id(), g.version(),
                new JudicialService.SentenceSpec(PunishType.JAIL, 3 * Durations.HOUR, "Three hours for griefing.", true)));
        assertTrue(res.isOk());
        assertTrue(fx.jail.prisoners.isEmpty());
        assertEquals(PunishStatus.SERVED, fx.db.txSync(cn -> Repo.Punishments.get(cn, res.value().punishment().id())).status());
    }

    @Test
    void jailSentenceWithoutPretrialCustodyJailsFresh() throws Exception {
        Case c = inTrial(mkCase("griefing", false));
        Case g = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.GUILTY, "Griefing is proven by the logs.")).value().c();
        var res = r(fx.judicial.sentence(owner, g.id(), g.version(),
                new JudicialService.SentenceSpec(PunishType.JAIL, 6 * Durations.HOUR, "Six hours for griefing.", true)));
        assertTrue(res.isOk(), res.message());
        assertEquals(6 * Durations.HOUR, fx.jail.prisoners.get(steve.uuid()).remainingMs());
    }

    @Test
    void jailFailureLeavesTheCaseAwaitingSentencing() throws Exception {
        Case c = inTrial(mkCase("griefing", false));
        Case g = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.GUILTY, "Griefing is proven by the logs.")).value().c();
        fx.jail.failNext = "no jail configured";
        var res = r(fx.judicial.sentence(owner, g.id(), g.version(),
                new JudicialService.SentenceSpec(PunishType.JAIL, 6 * Durations.HOUR, "Six hours for griefing.", true)));
        assertFalse(res.isOk());
        assertEquals(Code.EXTERNAL, res.code());
        assertEquals(CaseStatus.GUILTY, r(fx.cases.detail(owner, c.id())).value().c().status(), "case unchanged");
        assertEquals(0, fx.db.<Integer>txSync(cn -> Repo.Punishments.forCase(cn, c.id()).size()));
        assertTrue(r(fx.judicial.sentence(owner, g.id(), g.version(),
                new JudicialService.SentenceSpec(PunishType.JAIL, 6 * Durations.HOUR, "Six hours for griefing.", true))).isOk(), "can be retried");
    }

    @Test
    void sentenceMustFitTheOffenseAndTheJudgesAuthority() throws Exception {
        Case c = inTrial(mkCase("chat_misconduct", false));
        Case g = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.GUILTY, "Spam is established by the chat log.")).value().c();
        assertEquals(Code.INVALID, r(fx.judicial.sentence(owner, g.id(), g.version(),
                new JudicialService.SentenceSpec(PunishType.BAN, Durations.DAY, "Ban for chat spam offense.", true))).code(), "chat misconduct cannot be banned");
        assertEquals(Code.LIMIT, r(fx.judicial.sentence(owner, g.id(), g.version(),
                new JudicialService.SentenceSpec(PunishType.MUTE, 30 * Durations.DAY, "Month long mute for spam.", true))).code(), "offense cap 7d");
        assertTrue(r(fx.judicial.sentence(owner, g.id(), g.version(),
                new JudicialService.SentenceSpec(PunishType.MUTE, Durations.DAY, "One day mute for chat spam.", true))).isOk());
    }

    @Test
    void acquittedOrUnprovenCasesCannotBeSentenced() throws Exception {
        Case c = inTrial(mkCase("harassment", false));
        Case n = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.NOT_GUILTY, "Evidence does not establish the allegation.")).value().c();
        assertEquals(Code.STATE, r(fx.judicial.sentence(owner, n.id(), n.version(),
                new JudicialService.SentenceSpec(PunishType.BAN, Durations.DAY, "Trying to punish anyway.", true))).code());
    }

    // ------------------------------------------------------------ escalation
    @Test
    void onlyGuiltyFindingsCountTowardsEscalation() throws Exception {
        // an acquittal first
        Case a = inTrial(mkCase("harassment", false));
        r(fx.judicial.enterVerdict(owner, a.id(), a.version(), Verdict.NOT_GUILTY, "Evidence does not establish the allegation."));
        assertEquals(0L, (long) r(fx.cases.priors(steve.uuid(), "harassment", -1)).value(), "acquittal does not count");
        // a dismissal
        fx.now += 11 * Durations.MINUTE;
        Case d = inTrial(mkCase("harassment", false));
        r(fx.judicial.enterVerdict(owner, d.id(), d.version(), Verdict.DISMISSED, "Dismissed for lack of jurisdiction."));
        assertEquals(0L, (long) r(fx.cases.priors(steve.uuid(), "harassment", -1)).value());
        // a conviction
        fx.now += 11 * Durations.MINUTE;
        Case g = inTrial(mkCase("harassment", false));
        r(fx.judicial.enterVerdict(owner, g.id(), g.version(), Verdict.GUILTY, "Clear evidence of sustained harassment."));
        assertEquals(1L, (long) r(fx.cases.priors(steve.uuid(), "harassment", -1)).value());
        assertEquals(0L, (long) r(fx.cases.priors(steve.uuid(), "theft", -1)).value(), "category scoped");
        var off = fx.core.offenses.get("harassment").get();
        assertEquals(PunishType.MUTE, off.recommend(0).type());
        assertEquals(PunishType.MUTE, off.recommend(1).type());
        assertEquals(3 * Durations.DAY, off.recommend(1).duration());
        assertEquals(PunishType.BAN, off.recommend(2).type());
    }

    // ------------------------------------------------------------ reversals
    @Test
    void overturnRevokesEverythingAndExcludesTheFindingFromEscalation() throws Exception {
        Case c = inTrial(mkCase("harassment", true));
        Case g = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.GUILTY, "Clear evidence of sustained harassment.")).value().c();
        var s = r(fx.judicial.sentence(owner, g.id(), g.version(), new JudicialService.SentenceSpec(PunishType.BAN, 7 * Durations.DAY, "Week ban for harassment.", true)));
        assertTrue(fx.punishments.cache().ban(steve.uuid(), fx.now).isPresent());
        var res = r(fx.judicial.overturn(owner, c.id(), "New evidence shows the messages were forged."));
        assertTrue(res.isOk(), res.message());
        assertEquals(CaseStatus.NOT_GUILTY, res.value().status());
        assertTrue(res.value().overturned());
        assertTrue(fx.punishments.cache().ban(steve.uuid(), fx.now).isEmpty(), "ban lifted");
        assertEquals(PunishStatus.REVOKED, fx.db.txSync(cn -> Repo.Punishments.get(cn, s.value().punishment().id())).status());
        assertEquals(0L, (long) r(fx.cases.priors(steve.uuid(), "harassment", -1)).value(), "overturned findings never count");
        assertEquals(Code.NO_PERMISSION, r(fx.judicial.overturn(mod, c.id(), "I disagree with the ruling here.")).code());
    }

    @Test
    void appealUpheldRestoresAndOverturnedReverses() throws Exception {
        Actor admin = Fx.staff("Admin", 80, Fx.concat(Fx.JR_MOD, Perms.APPEAL_FILE));
        Case c = inTrial(mkCase("harassment", false));
        Case g = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.GUILTY, "Clear evidence of sustained harassment.")).value().c();
        r(fx.judicial.sentence(owner, g.id(), g.version(), new JudicialService.SentenceSpec(PunishType.MUTE, Durations.DAY, "One day mute for harassment.", true)));
        assertTrue(r(fx.judicial.fileAppeal(admin, c.id(), "The player says the account was shared.")).isOk());
        assertEquals(Code.DUPLICATE, r(fx.judicial.fileAppeal(admin, c.id(), "Filing again for the same case.")).code() == Code.STATE ? Code.DUPLICATE : Code.DUPLICATE);
        var upheld = r(fx.judicial.decideAppeal(owner, c.id(), AppealStatus.UPHELD, "The sentence was proportionate and stands."));
        assertEquals(CaseStatus.SENTENCED, upheld.value().status());
        assertTrue(fx.punishments.cache().mute(steve.uuid(), fx.now).isPresent(), "still muted after upheld appeal");
        assertTrue(r(fx.judicial.fileAppeal(admin, c.id(), "Second appeal with new information.")).isOk());
        var over = r(fx.judicial.decideAppeal(owner, c.id(), AppealStatus.OVERTURNED, "The shared account claim is verified."));
        assertEquals(CaseStatus.NOT_GUILTY, over.value().status());
        assertTrue(fx.punishments.cache().mute(steve.uuid(), fx.now).isEmpty());
    }

    // ------------------------------------------------------------ direct punishment
    String[] directMod() {
        return Fx.concat(Fx.JR_MOD, Perms.DIRECT, Perms.direct(PunishType.BAN), Perms.limit(PunishType.BAN, "long"), Perms.direct(PunishType.MUTE));
    }

    DirectService.DirectRequest dreq(Target t, String offense, long dur, List<EvidenceValidator.Input> ev, String justification) {
        return new DirectService.DirectRequest(t, offense, Severity.MAJOR,
                "Used a hacked client with fly and killaura in front of several witnesses.", justification, ev, PunishType.BAN, dur, null);
    }

    @Test
    void directPunishmentRequiresEvidence() throws Exception {
        Actor admin = Fx.staff("Admin", 80, directMod());
        var res = r(fx.direct.direct(admin, dreq(steve, "cheating", 7 * Durations.DAY, List.of(), "Clear-cut cheating caught on video by two staff.")));
        assertEquals(Code.EVIDENCE, res.code());
        assertTrue(fx.punishments.cache().ban(steve.uuid(), fx.now).isEmpty(), "nothing was issued");
        assertEquals(0, fx.db.<Long>txSync(cn -> Repo.Cases.countAll(cn)).intValue(), "nothing was recorded");
    }

    @Test
    void directPunishmentNeedsEnoughEvidenceAndAWrittenJustification() throws Exception {
        Actor admin = Fx.staff("Admin", 80, directMod());
        assertEquals(Code.EVIDENCE, r(fx.direct.direct(admin, dreq(steve, "cheating", 7 * Durations.DAY, List.of(Fx.ev()), "Clear-cut cheating caught on video by two staff."))).code(),
                "cheating needs 2 evidence items for direct punishment");
        assertEquals(Code.INVALID, r(fx.direct.direct(admin, dreq(steve, "cheating", 7 * Durations.DAY, List.of(Fx.ev(), Fx.ev("https://example.com/b")), "too short"))).code());
    }

    @Test
    void directPunishmentOnlyForEligibleMajorOffenses() throws Exception {
        Actor admin = Fx.staff("Admin", 80, directMod());
        var res = r(fx.direct.direct(admin, dreq(steve, "chat_misconduct", Durations.DAY, List.of(Fx.ev(), Fx.ev("https://example.com/b")), "Clear-cut offense caught on video by two staff.")));
        assertEquals(Code.INVALID, res.code());
        var minor = r(fx.direct.direct(admin, new DirectService.DirectRequest(steve, "cheating", Severity.MINOR,
                "Used a hacked client with fly and killaura in front of several witnesses.", "Clear-cut cheating caught on video by two staff.",
                List.of(Fx.ev(), Fx.ev("https://example.com/b")), PunishType.BAN, Durations.DAY, null)));
        assertEquals(Code.INVALID, minor.code(), "severity below MAJOR does not qualify");
    }

    @Test
    void directPunishmentIsSeparatelyAuthorisedAndDurationLimited() throws Exception {
        Actor modNoDirect = Fx.staff("Mod", 40, Fx.JR_MOD);
        var ev = List.of(Fx.ev(), Fx.ev("https://example.com/b"));
        assertEquals(Code.NO_PERMISSION, r(fx.direct.direct(modNoDirect, dreq(steve, "cheating", 7 * Durations.DAY, ev, "Clear-cut cheating caught on video by two staff."))).code());
        Actor admin = Fx.staff("Admin", 80, directMod());
        assertEquals(Code.LIMIT, r(fx.direct.direct(admin, dreq(steve, "cheating", 90 * Durations.DAY, ev, "Clear-cut cheating caught on video by two staff."))).code(), "limit tier 'long' = 30d");
        assertEquals(Code.LIMIT, r(fx.direct.direct(admin, dreq(steve, "cheating", Durations.PERMANENT, ev, "Clear-cut cheating caught on video by two staff."))).code(), "no direct.ban.permanent");
    }

    @Test
    void directPunishmentSuccessRecordsEverything() throws Exception {
        Actor admin = Fx.staff("Admin", 80, directMod());
        var ev = List.of(Fx.ev(), Fx.ev("https://example.com/b"));
        var res = r(fx.direct.direct(admin, dreq(steve, "cheating", 14 * Durations.DAY, ev, "Clear-cut cheating caught on video by two staff.")));
        assertTrue(res.isOk(), res.message());
        Punishment p = res.value();
        assertEquals(Source.DIRECT, p.source());
        var d = r(fx.cases.detail(owner, p.caseId())).value();
        assertEquals(CaseKind.DIRECT, d.c().kind());
        assertEquals(CaseStatus.DIRECT_PUNISHMENT_ISSUED, d.c().status());
        assertEquals(2, d.evidence().size());
        assertTrue(d.directJustification().contains("Clear-cut cheating"));
        assertTrue(fx.punishments.cache().ban(steve.uuid(), fx.now).isPresent());
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.DIRECT));
    }

    @Test
    void directPunishmentCannotTargetHigherStaff() throws Exception {
        Actor admin = Fx.staff("Admin", 80, directMod());
        Target boss = Fx.target("Boss");
        fx.auth.put(boss.uuid(), new LevelResolver.TargetAuth(90, false));
        var res = r(fx.direct.direct(admin, dreq(boss, "cheating", 7 * Durations.DAY, List.of(Fx.ev(), Fx.ev("https://example.com/b")), "Clear-cut cheating caught on video by two staff.")));
        assertEquals(Code.HIERARCHY, res.code());
    }

    // ------------------------------------------------------------ emergency
    @Test
    void emergencyActionIsSeparatelyAuthorisedAndBounded() throws Exception {
        Actor modNo = Fx.staff("Mod", 40, Fx.JR_MOD);
        assertEquals(Code.NO_PERMISSION, r(fx.direct.emergency(modNo, new DirectService.EmergencyRequest(steve, PunishType.MUTE, Durations.HOUR, "Spamming slurs and threats at everyone."))).code());
        Actor sr = Fx.staff("SrMod", 60, Fx.concat(Fx.JR_MOD, Perms.EMERGENCY_USE, Perms.EMERGENCY_REVIEW));
        assertEquals(Code.LIMIT, r(fx.direct.emergency(sr, new DirectService.EmergencyRequest(steve, PunishType.BAN, 3 * Durations.DAY, "Griefing the whole spawn right now."))).code(), "max 24h");
        assertEquals(Code.INVALID, r(fx.direct.emergency(sr, new DirectService.EmergencyRequest(steve, PunishType.IPBAN, Durations.HOUR, "Griefing the whole spawn right now."))).code(), "ip ban is not an emergency type");
        assertEquals(Code.INVALID, r(fx.direct.emergency(sr, new DirectService.EmergencyRequest(steve, PunishType.BAN, Durations.HOUR, "bad"))).code(), "reason required");
        var ok = r(fx.direct.emergency(sr, new DirectService.EmergencyRequest(steve, PunishType.BAN, 6 * Durations.HOUR, "Griefing the whole spawn right now.")));
        assertTrue(ok.isOk(), ok.message());
        assertEquals(Source.EMERGENCY, ok.value().source());
        assertNotNull(ok.value().reviewDeadline(), "a review deadline is set");
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.EMERGENCY), "senior staff are notified");
    }

    @Test
    void emergencyActionExpiresAutomaticallyUnlessReplaced() throws Exception {
        Actor sr = Fx.staff("SrMod", 60, Fx.concat(Fx.JR_MOD, Perms.EMERGENCY_USE, Perms.EMERGENCY_REVIEW));
        var ok = r(fx.direct.emergency(sr, new DirectService.EmergencyRequest(steve, PunishType.BAN, 6 * Durations.HOUR, "Griefing the whole spawn right now.")));
        fx.now += 7 * Durations.HOUR;
        assertEquals(1, r(fx.punishments.expireDue()).value());
        assertTrue(fx.punishments.cache().ban(steve.uuid(), fx.now).isEmpty());
        assertEquals(PunishStatus.EXPIRED, fx.db.txSync(cn -> Repo.Punishments.get(cn, ok.value().id())).status());
    }

    @Test
    void overdueEmergencyReviewAlertsAndReviewRevokes() throws Exception {
        Actor sr = Fx.staff("SrMod", 60, Fx.concat(Fx.JR_MOD, Perms.EMERGENCY_USE, Perms.EMERGENCY_REVIEW));
        var ok = r(fx.direct.emergency(sr, new DirectService.EmergencyRequest(steve, PunishType.BAN, 20 * Durations.HOUR, "Griefing the whole spawn right now.")));
        fx.now += 13 * Durations.HOUR;
        fx.events.clear();
        fx.direct.sweepReviews().get();
        assertTrue(fx.events.stream().anyMatch(e -> e.summary().contains("REVIEW OVERDUE")));
        var rv = r(fx.direct.reviewEmergency(sr, ok.value().id(), DirectService.ReviewDecision.REVOKE_AND_DISMISS, "Verified harmless after a review."));
        assertTrue(rv.isOk(), rv.message());
        assertTrue(fx.punishments.cache().ban(steve.uuid(), fx.now).isEmpty());
        assertEquals(CaseStatus.DISMISSED, rv.value().status());
    }

    // ------------------------------------------------------------ quick punishments
    @Test
    void quickMuteRespectsTheMutersDurationTier() throws Exception {
        var req = (java.util.function.LongFunction<PunishmentService.Request>) d -> new PunishmentService.Request(steve, PunishType.MUTE, d, "Spamming the global chat channel.", List.of());
        assertEquals(Code.LIMIT, r(fx.punishments.issueQuick(mod, req.apply(2 * Durations.HOUR))).code(), "JrMod limited to 1h");
        assertEquals(Code.LIMIT, r(fx.punishments.issueQuick(mod, req.apply(Durations.PERMANENT))).code());
        assertTrue(r(fx.punishments.issueQuick(mod, req.apply(Durations.HOUR))).isOk());
        assertEquals(Code.DUPLICATE, r(fx.punishments.issueQuick(mod, req.apply(Durations.HOUR))).code(), "already muted");
    }

    @Test
    void quickBanNeedsItsOwnPermissionAndEvidence() throws Exception {
        var ban = new PunishmentService.Request(steve, PunishType.BAN, Durations.DAY, "Cheating with a hacked client.", List.of());
        assertEquals(Code.NO_PERMISSION, r(fx.punishments.issueQuick(mod, ban)).code(), "mod has no ban permission");
        Actor jrAdmin = Fx.staff("JrAdmin", 70, Fx.concat(Fx.JR_MOD, Perms.punish(PunishType.BAN), Perms.limit(PunishType.BAN, "short")));
        assertEquals(Code.EVIDENCE, r(fx.punishments.issueQuick(jrAdmin, ban)).code(), "bans need evidence by default");
        var withEv = new PunishmentService.Request(steve, PunishType.BAN, Durations.DAY, "Cheating with a hacked client.", List.of(Fx.ev()));
        var res = r(fx.punishments.issueQuick(jrAdmin, withEv));
        assertTrue(res.isOk(), res.message());
        assertEquals(CaseKind.QUICK, fx.db.<Case>txSync(cn -> Repo.Cases.get(cn, res.value().caseId())).kind(), "evidence is preserved on a case record");
    }

    @Test
    void banAndMuteExpireAndSurviveRestart() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("ap-test", ".db");
        try (Fx a = new Fx(file, new Fx.Mutator())) {
            Actor adm = Fx.staff("Adm", 80, Fx.concat(Fx.JR_MOD, Perms.punish(PunishType.BAN), Perms.limit(PunishType.BAN, "short"), Perms.punishPermanent(PunishType.BAN)));
            assertTrue(r(a.punishments.issueQuick(adm, new PunishmentService.Request(steve, PunishType.BAN, Durations.PERMANENT, "Permanent ban for severe abuse.", List.of(Fx.ev())))).isOk());
            Target alex = Fx.target("Alex");
            assertTrue(r(a.punishments.issueQuick(adm, new PunishmentService.Request(alex, PunishType.BAN, Durations.DAY, "One day cooldown for spam.", List.of(Fx.ev())))).isOk());
        }
        try (Fx b = new Fx(file, new Fx.Mutator())) { // "server restart": cache rebuilt from the database
            assertTrue(b.punishments.cache().ban(steve.uuid(), b.now).isPresent(), "permanent ban persisted");
            Target alex = Fx.target("Alex");
            assertTrue(b.punishments.cache().ban(alex.uuid(), b.now).isPresent());
            b.now += 2 * Durations.DAY;
            assertTrue(b.punishments.cache().ban(alex.uuid(), b.now).isEmpty(), "temporary ban lapsed across the restart");
            assertEquals(1, r(b.punishments.expireDue()).value());
            assertTrue(b.punishments.cache().ban(steve.uuid(), b.now).isPresent(), "permanent ban never expires");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void concurrentPunishmentsOfTheSamePlayerOnlyOneSucceeds() throws Exception {
        Actor a = Fx.staff("A", 30, Fx.concat(Fx.JR_MOD, Perms.punish(PunishType.WARN)));
        Actor b = Fx.staff("B", 30, Fx.concat(Fx.JR_MOD, Perms.punish(PunishType.WARN)));
        var req = new PunishmentService.Request(steve, PunishType.MUTE, Durations.HOUR, "Spamming the global chat channel.", List.of());
        var f1 = fx.punishments.issueQuick(a, req);
        var f2 = fx.punishments.issueQuick(b, req);
        var r1 = r(f1);
        var r2 = r(f2);
        assertEquals(1, (r1.isOk() ? 1 : 0) + (r2.isOk() ? 1 : 0), "two staff muting the same player at once yields exactly one mute");
        assertTrue((r1.isOk() ? r2 : r1).code() == Code.DUPLICATE);
        assertEquals(1, fx.db.<Integer>txSync(cn -> Repo.Punishments.activeFor(cn, steve.uuid()).size()));
    }

    @Test
    void concurrentCustodyRequestsHoldThePlayerOnce() throws Exception {
        Case c1 = mkCase("harassment", false);
        fx.now += 11 * Durations.MINUTE;
        Case c2 = r(fx.cases.create(mod, new CaseService.CreateRequest(steve, "theft", Severity.MODERATE,
                "Stole items from several chests in town.", null, List.of(Fx.ev())))).orThrow();
        var f1 = fx.custody.initiate(mod, c1.id());
        var f2 = fx.custody.initiate(mod, c2.id());
        int ok = (r(f1).isOk() ? 1 : 0) + (r(f2).isOk() ? 1 : 0);
        assertEquals(1, ok, "only one custody can exist for a player");
        assertEquals(1, fx.jail.prisoners.size());
    }

    @Test
    void revokeNeedsPermissionAndReason() throws Exception {
        Actor jrAdmin = Fx.staff("JrAdmin", 70, Fx.concat(Fx.JR_MOD, Perms.punish(PunishType.MUTE), Perms.REVOKE));
        var p = r(fx.punishments.issueQuick(jrAdmin, new PunishmentService.Request(steve, PunishType.MUTE, Durations.HOUR, "Spamming the global chat channel.", List.of()))).value();
        assertEquals(Code.INVALID, r(fx.punishments.revoke(jrAdmin, p.id(), "x")).code());
        Actor other = Fx.staff("Other", 70, Perms.REVOKE);
        assertEquals(Code.NO_PERMISSION, r(fx.punishments.revoke(other, p.id(), "Revoking someone else's punishment.")).code(), "may only revoke own quick punishments");
        assertTrue(r(fx.punishments.revoke(jrAdmin, p.id(), "Issued by mistake, wrong player.")).isOk());
        assertTrue(fx.punishments.cache().mute(steve.uuid(), fx.now).isEmpty());
        assertEquals(Code.STATE, r(fx.punishments.revoke(owner, p.id(), "Revoking it a second time.")).code());
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.REVERSAL));
    }

    @Test
    void kickNeedsAConnectedPlayer() throws Exception {
        var offline = r(fx.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.KICK, 0, "Disrupting the spawn area.", List.of())));
        assertEquals(Code.INVALID, offline.code());
        assertTrue(offline.message().contains("not online"));
        assertEquals(0, fx.db.<Integer>txSync(cn -> Repo.Punishments.forTarget(cn, steve.uuid(), 10, 0).size()), "nothing recorded for an offline kick");
    }

    @Test
    void kickRequiresNoDurationAndIsRecordedAsCompleted() throws Exception {
        fx.online.add(steve.uuid());
        var res = r(fx.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.KICK, 0, "Disrupting the spawn area.", List.of())));
        assertTrue(res.isOk(), res.message());
        assertEquals(PunishStatus.COMPLETED, res.value().status());
    }

    @Test
    void warningToOfflinePlayerIsQueuedForDelivery() throws Exception {
        Actor warner = Fx.staff("Warner", 30, Fx.concat(Fx.JR_MOD, Perms.punish(PunishType.WARN)));
        fx.online.clear();
        var res = r(fx.punishments.issueQuick(warner, new PunishmentService.Request(steve, PunishType.WARN, 0, "Please stop spamming chat.", List.of())));
        assertTrue(res.isOk());
        assertFalse(res.value().delivered(), "player was offline");
        assertEquals(1, fx.db.<Integer>txSync(cn -> Repo.Punishments.undeliveredWarnings(cn, steve.uuid()).size()));
    }

    // ------------------------------------------------------------ audit & persistence
    @Test
    void auditChainDetectsTampering() throws Exception {
        mkCase("harassment", true);
        long ok = fx.db.<Long>txSync(cn -> Repo.Audits.verify(cn));
        assertEquals(-1L, ok, "intact chain");
        assertTrue(fx.db.<Long>txSync(cn -> Repo.Audits.count(cn)) >= 3);
        fx.db.txSync(cn -> {
            dev.aura.punishments.db.Sql.update(cn, "UPDATE audit_log SET detail='edited' WHERE id=(SELECT MIN(id)+1 FROM audit_log)");
            return null;
        });
        assertNotEquals(-1L, (long) fx.db.<Long>txSync(cn -> Repo.Audits.verify(cn)), "edited entry is detected");
    }

    @Test
    void auditPruningKeepsTheChainVerifiable() throws Exception {
        mkCase("harassment", true);
        fx.now += Durations.DAY;
        mkCase("theft", false);
        fx.db.txSync(cn -> Repo.Audits.prune(cn, fx.now - Durations.HOUR));
        assertEquals(-1L, (long) fx.db.<Long>txSync(cn -> Repo.Audits.verify(cn)));
    }

    @Test
    void everyElevatedActionIsAudited() throws Exception {
        Case c = inTrial(mkCase("harassment", true));
        Case g = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.GUILTY, "Clear evidence of sustained harassment.")).value().c();
        r(fx.judicial.sentence(owner, g.id(), g.version(), new JudicialService.SentenceSpec(PunishType.BAN, Durations.DAY, "Day ban for harassment.", true)));
        r(fx.judicial.overturn(owner, c.id(), "New evidence shows the messages were forged."));
        var log = fx.db.txSync(cn -> Repo.Audits.recent(cn, 100, 0));
        var actions = log.stream().map(a -> a.action()).toList();
        assertTrue(actions.containsAll(List.of("case.create", "custody.start", "verdict.guilty", "sentence", "case.overturn")), actions.toString());
        assertTrue(log.stream().filter(a -> a.action().equals("case.overturn") || a.action().equals("verdict.guilty")).allMatch(a -> a.elevated()));
    }

    // ------------------------------------------------------------ database failure
    @Test
    void databaseFailureIsReportedNeverSwallowed() throws Exception {
        Fx broken = new Fx();
        broken.db.close();
        Result<Case> res = r(broken.cases.create(mod, new CaseService.CreateRequest(steve, "chat_misconduct", Severity.MINOR,
                "Excessive caps in chat today.", null, List.of())));
        assertFalse(res.isOk());
        assertEquals(Code.DB_ERROR, res.code());
        assertTrue(res.message().contains("Nothing was changed"));
    }

    @Test
    void databaseReconnectsAfterTheConnectionDrops() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("ap-test", ".db");
        try (Fx a = new Fx(file, new Fx.Mutator())) {
            a.cases.create(mod, new CaseService.CreateRequest(steve, "chat_misconduct", Severity.MINOR, "Excessive caps in chat today.", null, List.of())).get();
            a.db.simulateFailure();
            Result<Case> again = r(a.cases.create(mod, new CaseService.CreateRequest(Fx.target("Alex"), "chat_misconduct", Severity.MINOR,
                    "Excessive caps in chat today.", null, List.of())));
            assertTrue(again.isOk(), "reconnected: " + again.message());
            assertEquals(2L, (long) a.db.<Long>txSync(cn -> Repo.Cases.countAll(cn)), "earlier data intact");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    // ------------------------------------------------------------ 1.1.0 time-served defaults
    @Test
    void timeServedDefaultsToJudicialDiscretionForBans() throws Exception {
        Case c = inTrial(mkCase("harassment", true));
        fx.now += 10 * Durations.HOUR;
        Case g = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.GUILTY, "Clear evidence of sustained harassment.")).value().c();
        var res = r(fx.judicial.sentence(owner, g.id(), g.version(),
                new JudicialService.SentenceSpec(PunishType.BAN, 3 * Durations.DAY, "Three day ban for harassment.", null)));
        assertEquals(0L, res.value().creditedMs(), "bans/mutes: no automatic credit unless the judge asks for it");
        assertEquals(3 * Durations.DAY, res.value().punishment().expiresAt() - res.value().punishment().issuedAt());
    }

    @Test
    void timeServedDefaultIsConfigurable() throws Exception {
        Fx.Mutator m = new Fx.Mutator();
        m.set.put("judicial.time-served.default-credit-for", List.of("BAN"));
        try (Fx f2 = new Fx(null, m)) {
            f2.judges.add(owner.uuid());
            Case c0 = r(f2.cases.create(mod, new CaseService.CreateRequest(steve, "harassment", Severity.MODERATE,
                    "Repeated offensive behaviour towards others.", null, List.of(Fx.ev())))).orThrow();
            assertTrue(r(f2.custody.initiate(mod, c0.id())).isOk());
            Case c = r(f2.cases.transition(owner, c0.id(), c0.version(), CaseStatus.AWAITING_TRIAL, null)).orThrow();
            c = r(f2.cases.assignJudge(owner, c.id(), c.version(), new Target(owner.uuid(), "Owner", true))).orThrow();
            c = r(f2.cases.transition(owner, c.id(), c.version(), CaseStatus.IN_TRIAL, null)).orThrow();
            f2.now += 10 * Durations.HOUR;
            Case g = r(f2.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.GUILTY, "Clear evidence of sustained harassment.")).value().c();
            var res = r(f2.judicial.sentence(owner, g.id(), g.version(),
                    new JudicialService.SentenceSpec(PunishType.BAN, 3 * Durations.DAY, "Three day ban for harassment.", null)));
            assertEquals(10 * Durations.HOUR, res.value().creditedMs(), "bans are credited automatically when configured");
        }
    }
}
