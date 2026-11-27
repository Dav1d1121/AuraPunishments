package dev.aura.punishments.v13;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Evidence;
import dev.aura.punishments.model.Records.Note;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Records.Review;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.model.Simple.ReviewStatus;
import dev.aura.punishments.model.Simple.Source;
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

/** With the judicial system off: every punishment is final and immediate, and evidence/notes/emergency/review work without cases. */
class NonJudicialWorkflowTest {
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

    Punishment issue(PunishType t, long dur, String reason, List<EvidenceValidator.Input> ev, String note) throws Exception {
        return r(fx.punishments.issueQuick(mod, new PunishmentService.Request(steve, t, dur, reason, ev, note))).orThrow();
    }

    long caseCount() throws Exception {
        return fx.db.<Long>txSync(c -> dev.aura.punishments.db.Sql.count(c, "SELECT COUNT(*) FROM cases"));
    }

    @Test
    void everyPunishmentTypeIsFinalImmediateAndCreatesNoCase() throws Exception {
        fx.db.txSync(c -> { Repo.Profiles.upsert(c, steve.uuid(), "Steve", "10.1.2.3", 0, fx.now); return null; });
        Punishment warn = issue(PunishType.WARN, 0, "Please keep chat friendly.", List.of(), null);
        assertEquals(Source.STANDARD, warn.source());
        assertEquals("Warning", warn.classification());
        Punishment kick = issue(PunishType.KICK, 0, "Disconnecting for AFK farming.", List.of(), null);
        assertEquals(PunishStatus.COMPLETED, kick.status());
        Punishment mute = issue(PunishType.MUTE, Durations.HOUR, "Spamming the global channel.", List.of(), null);
        assertEquals(PunishStatus.ACTIVE, mute.status());
        assertTrue(fx.punishments.muteFor(steve.uuid()).isPresent(), "the mute is enforceable at once");
        Punishment ban = issue(PunishType.BAN, Durations.DAY, "Repeated griefing of builds.", List.of(Fx.ev()), null);
        assertTrue(fx.punishments.banFor(steve.uuid(), null).isPresent(), "the ban is enforceable at once");
        assertEquals("Punishment", ban.classification());
        r(fx.punishments.revoke(mod, ban.id(), "Testing the ban revoke path here."));
        Punishment ipban = issue(PunishType.IPBAN, Durations.DAY, "Ban evasion with alternate accounts.", List.of(Fx.ev()), null);
        assertEquals("10.1.2.3", ipban.ip());
        assertEquals(0, caseCount(), "no mandatory case, hearing, verdict or sentence step exists");
    }

    @Test
    void evidenceAtIssuanceBelongsToThePunishmentNotACase() throws Exception {
        Punishment ban = issue(PunishType.BAN, Durations.DAY, "Repeated griefing of builds.", List.of(Fx.ev("https://example.com/a.png"), Fx.ev("https://example.com/b.png")), null);
        List<Evidence> ev = fx.db.txSync(c -> Repo.Evidences.forPunishment(c, ban.id(), false));
        assertEquals(2, ev.size());
        assertTrue(ev.stream().allMatch(e -> e.caseId() == null && e.punishmentId() == ban.id()));
        assertEquals(0, caseCount());
        assertNull(ban.caseId());
    }

    @Test
    void aBanStillNeedsEvidenceWhenConfiguredAndAJailDoesNotByDefault() throws Exception {
        var noEv = r(fx.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.BAN, Durations.DAY, "Repeated griefing of builds.", List.of())));
        assertEquals(Code.EVIDENCE, noEv.code());
        assertFalse(fx.core.settings.quick.requireEvidence().contains(PunishType.JAIL), "jail does not require evidence by default");
        Fx lenient = V13Support.fx(false, m -> m.set.put("punishments.quick.require-evidence", List.of("BAN", "IPBAN", "JAIL")));
        try {
            var jail = r(lenient.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.JAIL, Durations.HOUR, "Griefing the shared farm area.", List.of())));
            assertEquals(Code.EVIDENCE, jail.code(), "evidence for jail is a config choice");
        } finally {
            lenient.close();
        }
    }

    @Test
    void anOptionalNoteAtIssuanceIsAttachedToThePunishment() throws Exception {
        Punishment mute = issue(PunishType.MUTE, Durations.HOUR, "Spamming the global channel.", List.of(), "Second time this week; was warned in Discord.");
        List<Note> notes = fx.db.txSync(c -> Repo.Notes.forPunishment(c, mute.id()));
        assertEquals(1, notes.size());
        assertEquals(mute.id(), notes.get(0).punishmentId());
        Actor noNotes = Fx.staff("NoNotes", 20, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.punish(PunishType.MUTE), Perms.limit(PunishType.MUTE, "short"));
        var refused = r(fx.punishments.issueQuick(noNotes, new PunishmentService.Request(Fx.target("Other"), PunishType.MUTE, Durations.HOUR, "Spamming the global channel.", List.of(), "a note")));
        assertEquals(Code.NO_PERMISSION, refused.code(), "a note needs the note permission");
    }

    @Test
    void evidenceCanBeAddedAfterwardsByTheIssuerOrByStaffWithPermissionButNotByStrangers() throws Exception {
        Punishment mute = issue(PunishType.MUTE, Durations.HOUR, "Spamming the global channel.", List.of(), null);
        var later = r(fx.records.addEvidence(mod, mute.id(), Fx.ev("https://example.com/later.png"))).orThrow();
        assertEquals(mute.id(), later.punishmentId());
        Actor colleague = Fx.staff("Colleague", 20, Perms.HISTORY_VIEW, Perms.EVIDENCE_ADD, Perms.EVIDENCE_VIEW);
        assertTrue(r(fx.records.addEvidence(colleague, mute.id(), Fx.ev("https://example.com/c.png"))).isOk());
        Actor stranger = Fx.staff("Stranger", 20, Perms.HISTORY_VIEW);
        assertEquals(Code.NO_PERMISSION, r(fx.records.addEvidence(stranger, mute.id(), Fx.ev("https://example.com/s.png"))).code());
        var detail = r(fx.records.detail(colleague, mute.id())).orThrow();
        assertEquals(2, detail.evidence().size());
        var hidden = r(fx.records.detail(stranger, mute.id())).orThrow();
        assertTrue(hidden.evidenceHidden() && hidden.evidence().isEmpty(), "evidence is only shown to those who may view it");
    }

    @Test
    void evidenceIsVoidedNeverDeletedAndTheLastItemOfARequiredTypeCannotBeVoidedWithoutAReplacement() throws Exception {
        Punishment ban = issue(PunishType.BAN, Durations.DAY, "Repeated griefing of builds.", List.of(Fx.ev("https://example.com/a.png")), null);
        Actor admin = Fx.staff("Admin", 50, Perms.HISTORY_VIEW, Perms.EVIDENCE_VIEW, Perms.EVIDENCE_VOID, Perms.EVIDENCE_ADD);
        Evidence only = fx.db.<List<Evidence>>txSync(c -> Repo.Evidences.forPunishment(c, ban.id(), false)).get(0);
        assertEquals(Code.EVIDENCE, r(fx.records.voidEvidence(admin, only.id(), "Wrong screenshot was uploaded.", null)).code());
        var replaced = r(fx.records.voidEvidence(admin, only.id(), "Wrong screenshot was uploaded.", Fx.ev("https://example.com/right.png"))).orThrow();
        assertTrue(replaced.voided());
        List<Evidence> all = fx.db.txSync(c -> Repo.Evidences.forPunishment(c, ban.id(), true));
        assertEquals(2, all.size(), "the voided item and its replacement both remain on record");
        assertEquals(only.id(), all.get(1).correctsId());
        assertEquals(Code.INVALID, r(fx.records.voidEvidence(admin, all.get(1).id(), "no", null)).code(), "a reason is required");
    }

    @Test
    void staffNotesOnAPunishmentRespectSensitiveAndViewPermissions() throws Exception {
        Punishment mute = issue(PunishType.MUTE, Durations.HOUR, "Spamming the global channel.", List.of(), null);
        r(fx.records.addNote(mod, mute.id(), "Looked at the logs; clearly intentional.", false)).orThrow();
        assertEquals(Code.NO_PERMISSION, r(fx.records.addNote(mod, mute.id(), "Private detail about the player.", true)).code());
        Actor senior = Fx.staff("Senior", 40, Perms.HISTORY_VIEW, Perms.NOTE_ADD, Perms.NOTE_VIEW, Perms.SENSITIVE_NOTES);
        r(fx.records.addNote(senior, mute.id(), "Private detail about the player.", true)).orThrow();
        assertEquals(2, r(fx.records.detail(senior, mute.id())).orThrow().notes().size());
        assertEquals(1, r(fx.records.detail(mod, mute.id())).orThrow().notes().size(), "the sensitive note is hidden from staff without the permission");
    }

    // ------------------------------------------------------------------ emergency without a case
    @Test
    void emergencyActionsWorkWithoutACaseAndAreReviewedBySeniorStaff() throws Exception {
        Actor responder = Fx.staff("Responder", 20, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.EMERGENCY_USE);
        Punishment em = r(fx.direct.emergency(responder, new DirectService.EmergencyRequest(steve, PunishType.MUTE, Durations.HOUR, "Raiding the voice chat with slurs right now."))).orThrow();
        assertEquals(Source.EMERGENCY, em.source());
        assertNull(em.caseId(), "no case is created");
        assertEquals(0, caseCount());
        assertNotNull(em.reviewDeadline());
        Review rv = fx.db.txSync(c -> Repo.Reviews.byPunishment(c, em.id()));
        assertNotNull(rv, "review.include-emergency puts it in the owner review queue");
        assertEquals(ReviewStatus.PENDING_REVIEW, rv.status());
        Actor reviewer = Fx.staff("Reviewer", 60, Perms.EMERGENCY_REVIEW);
        assertEquals(Code.UNAVAILABLE, r(fx.direct.reviewEmergencyAction(reviewer, em.id(), DirectService.ReviewDecision.ESCALATE_TO_TRIAL, "Escalating it to a trial.")).code());
        var upheld = r(fx.direct.reviewEmergencyAction(reviewer, em.id(), DirectService.ReviewDecision.UPHOLD, "The evidence supports the action.")).orThrow();
        assertTrue(upheld.reviewed());
        assertEquals(PunishStatus.ACTIVE, upheld.status(), "upholding keeps the action in force until it expires");
        assertEquals(ReviewStatus.UPHELD, fx.db.<Review>txSync(c -> Repo.Reviews.byPunishment(c, em.id())).status(), "one decision settles both queues");
    }

    @Test
    void anEmergencyActionCanBeRevokedByItsReview() throws Exception {
        Actor responder = Fx.staff("Responder", 20, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.EMERGENCY_USE);
        Punishment em = r(fx.direct.emergency(responder, new DirectService.EmergencyRequest(steve, PunishType.BAN, Durations.HOUR, "Griefing the spawn while staff are away."))).orThrow();
        assertTrue(fx.punishments.banFor(steve.uuid(), null).isPresent());
        Actor reviewer = Fx.staff("Reviewer", 60, Perms.EMERGENCY_REVIEW);
        var gone = r(fx.direct.reviewEmergencyAction(reviewer, em.id(), DirectService.ReviewDecision.REVOKE_AND_DISMISS, "This was not as serious as it looked.")).orThrow();
        assertEquals(PunishStatus.REVOKED, gone.status());
        assertTrue(fx.punishments.banFor(steve.uuid(), null).isEmpty());
        assertEquals(ReviewStatus.REVERSED, fx.db.<Review>txSync(c -> Repo.Reviews.byPunishment(c, em.id())).status());
    }

    @Test
    void anOwnerReviewDecisionAlsoSettlesTheEmergencyReview() throws Exception {
        Actor responder = Fx.staff("Responder", 20, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.EMERGENCY_USE);
        Punishment em = r(fx.direct.emergency(responder, new DirectService.EmergencyRequest(steve, PunishType.MUTE, Durations.HOUR, "Raiding the voice chat with slurs right now."))).orThrow();
        Review rv = fx.db.txSync(c -> Repo.Reviews.byPunishment(c, em.id()));
        r(fx.reviews.uphold(Fx.owner(), rv.id(), rv.version(), "Reviewed through the owner queue; fine."));
        assertTrue(fx.db.<Punishment>txSync(c -> Repo.Punishments.get(c, em.id())).reviewed());
        assertTrue(fx.db.<List<Punishment>>txSync(c -> Repo.Punishments.emergencyAwaitingReview(c)).isEmpty());
    }

    @Test
    void emergencyActionsStillCreateACaseWhenTheJudicialSystemIsOn() throws Exception {
        Fx on = V13Support.fx(true);
        try {
            Actor responder = Fx.staff("Responder", 20, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.EMERGENCY_USE);
            Punishment em = r(on.direct.emergency(responder, new DirectService.EmergencyRequest(steve, PunishType.MUTE, Durations.HOUR, "Raiding the voice chat with slurs right now."))).orThrow();
            assertNotNull(em.caseId(), "unchanged behaviour when the judicial system is on");
            assertNull(on.db.<Review>txSync(c -> Repo.Reviews.byPunishment(c, em.id())), "and no extra owner review");
        } finally {
            on.close();
        }
    }

    // ------------------------------------------------------------------ owner review rules
    @Test
    void ownerReviewIsConfigurableByTypeAndNeverBlocksThePunishment() throws Exception {
        Fx f = V13Support.fx(false, m -> {
            m.set.put("review.required-types", List.of("MUTE", "BAN", "IPBAN"));
            m.set.put("review.permanent-only-types", List.of("MUTE"));
        });
        try {
            Actor staff = mod("Mod2");
            var routine = r(f.punishments.issueQuick(staff, new PunishmentService.Request(steve, PunishType.MUTE, Durations.HOUR, "Spamming the global channel.", List.of()))).orThrow();
            assertNull(f.db.<Review>txSync(c -> Repo.Reviews.byPunishment(c, routine.id())), "a routine temporary mute is not submitted");
            assertTrue(f.punishments.muteFor(steve.uuid()).isPresent(), "and it is in force regardless");
            r(f.punishments.revoke(staff, routine.id(), "Clearing it to issue the next test mute."));
            var permanent = r(f.punishments.issueQuick(staff, new PunishmentService.Request(steve, PunishType.MUTE, Durations.PERMANENT, "Hateful harassment of other players.", List.of(), null)));
            assertEquals(Code.LIMIT, permanent.code(), "a permanent mute needs its own permission");
            Actor perm = Fx.staff("Perm", 30, Fx.concat(V13Support.punisher(), Perms.punishPermanent(PunishType.MUTE)));
            var forever = r(f.punishments.issueQuick(perm, new PunishmentService.Request(steve, PunishType.MUTE, Durations.PERMANENT, "Hateful harassment of other players.", List.of()))).orThrow();
            Review rv = f.db.txSync(c -> Repo.Reviews.byPunishment(c, forever.id()));
            assertNotNull(rv, "a permanent mute is submitted");
            assertEquals(ReviewStatus.PENDING_REVIEW, rv.status());
            assertTrue(f.punishments.muteFor(steve.uuid()).isPresent(), "review is oversight, never an approval step");
        } finally {
            f.close();
        }
    }

    @Test
    void reviewCanBeSwitchedOffEntirely() throws Exception {
        Fx f = V13Support.fx(false, m -> m.set.put("review.enabled", false));
        try {
            var ban = r(f.punishments.issueQuick(mod("M3"), new PunishmentService.Request(steve, PunishType.BAN, Durations.DAY, "Repeated griefing of builds.", List.of(Fx.ev())))).orThrow();
            assertNull(f.db.<Review>txSync(c -> Repo.Reviews.byPunishment(c, ban.id())));
        } finally {
            f.close();
        }
    }

    @Test
    void evidenceAddedThroughTheReviewGoesToThePunishmentAndNoHiddenCaseIsCreated() throws Exception {
        Punishment ban = issue(PunishType.BAN, Durations.DAY, "Repeated griefing of builds.", List.of(Fx.ev()), null);
        Review rv = fx.db.txSync(c -> Repo.Reviews.byPunishment(c, ban.id()));
        assertNotNull(rv);
        var ev = r(fx.reviews.submitEvidence(mod, rv.id(), Fx.ev("https://example.com/more.png"))).orThrow();
        assertEquals(ban.id(), ev.punishmentId());
        assertEquals(0, caseCount());
        var detail = r(fx.reviews.detail(Fx.owner(), rv.id())).orThrow();
        assertEquals(2, detail.evidence().size());
        assertTrue(detail.evidenceAttached());
    }

    @Test
    void directPunishmentsAndCaseCreationAreUnavailable() throws Exception {
        var direct = r(fx.direct.direct(Fx.owner(), new DirectService.DirectRequest(steve, "other", dev.aura.punishments.model.Simple.Severity.SEVERE, "Serious griefing across many builds",
                "Obvious and repeated", List.of(Fx.ev()), PunishType.BAN, Durations.PERMANENT, null)));
        assertEquals(Code.UNAVAILABLE, direct.code());
        assertEquals(Code.UNAVAILABLE, r(fx.cases.create(Fx.owner(), new CaseService.CreateRequest(steve, "other", dev.aura.punishments.model.Simple.Severity.MINOR,
                "Spamming the chat again", null, List.of(Fx.ev())))).code());
    }

    // ------------------------------------------------------------------ offline players, hierarchy, limits (unchanged by the switch)
    @Test
    void offlinePlayersAreCoveredByEveryPunishmentThatMakesSense() throws Exception {
        fx.online.remove(steve.uuid());
        fx.db.txSync(c -> { Repo.Profiles.upsert(c, steve.uuid(), "Steve", "10.5.5.5", 0, fx.now); return null; });
        Punishment warn = issue(PunishType.WARN, 0, "Please keep chat friendly.", List.of(), null);
        assertFalse(warn.delivered(), "an offline player sees the warning at next login");
        assertEquals(Code.INVALID, r(fx.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.KICK, 0, "Kick them please", List.of()))).code(), "nobody to kick");
        assertTrue(issue(PunishType.MUTE, Durations.HOUR, "Spamming the global channel.", List.of(), null).status() == PunishStatus.ACTIVE);
        assertTrue(issue(PunishType.BAN, Durations.DAY, "Repeated griefing of builds.", List.of(Fx.ev()), null).status() == PunishStatus.ACTIVE);
        assertTrue(fx.punishments.banFor(steve.uuid(), null).isPresent(), "enforced when they next try to log in");
        r(fx.punishments.revoke(mod, fx.punishments.banFor(steve.uuid(), null).orElseThrow().id(), "Revoking the ban to test the offline jail."));
        Punishment jail = r(fx.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.JAIL, Durations.HOUR, "Griefing the shared farm area.", List.of()))).orThrow();
        assertEquals(PunishStatus.ACTIVE, jail.status());
    }

    @Test
    void hierarchyImmunityAndRankLimitsStillApplyWithTheJudicialSystemOff() throws Exception {
        fx.auth.put(steve.uuid(), new LevelResolver.TargetAuth(50, false));
        var higher = r(fx.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.MUTE, Durations.HOUR, "Spamming the global channel.", List.of())));
        assertEquals(Code.HIERARCHY, higher.code(), "a lower rank cannot punish a higher rank");
        fx.auth.put(steve.uuid(), new LevelResolver.TargetAuth(0, true));
        assertEquals(Code.HIERARCHY, r(fx.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.MUTE, Durations.HOUR, "Spamming the global channel.", List.of()))).code(),
                "immune players cannot be punished");
        fx.auth.put(steve.uuid(), new LevelResolver.TargetAuth(0, false));
        Actor limited = Fx.staff("Limited", 20, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.punish(PunishType.MUTE), Perms.limit(PunishType.MUTE, "short"));
        assertEquals(Code.LIMIT, r(fx.punishments.issueQuick(limited, new PunishmentService.Request(steve, PunishType.MUTE, 5 * Durations.HOUR, "Spamming the global channel.", List.of()))).code(),
                "rank duration tiers apply");
        assertEquals(Code.LIMIT, r(fx.punishments.issueQuick(limited, new PunishmentService.Request(steve, PunishType.MUTE, Durations.PERMANENT, "Spamming the global channel.", List.of()))).code(),
                "permanent needs its own node");
        assertEquals(Code.NO_PERMISSION, r(fx.punishments.issueQuick(limited, new PunishmentService.Request(steve, PunishType.BAN, Durations.HOUR, "Spamming the global channel.", List.of(Fx.ev())))).code());
        Actor stranger = Fx.staff("Stranger", 20, Perms.PUNISH);
        assertEquals(Code.NO_PERMISSION, r(fx.records.addNote(stranger, 1, "A note nobody may write.", false)).code());
    }

    @Test
    void modificationsRevocationsAndExpiryAreRecordedAndAuditedWithoutAnyCase() throws Exception {
        Punishment mute = issue(PunishType.MUTE, Durations.DAY, "Spamming the global channel.", List.of(), null);
        fx.now += Durations.DAY + 1000;
        assertEquals(1, r(fx.punishments.expireDue()).orThrow());
        assertEquals(PunishStatus.EXPIRED, fx.db.<Punishment>txSync(c -> Repo.Punishments.get(c, mute.id())).status());
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.PUNISHMENT_EXPIRED && e.info() != null && e.info().event().equals("expired")));
        assertEquals(-1L, (long) fx.db.<Long>txSync(c -> Repo.Audits.verify(c)), "the audit chain stays intact");
    }
}
