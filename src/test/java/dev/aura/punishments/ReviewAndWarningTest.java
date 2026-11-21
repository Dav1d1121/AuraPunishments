package dev.aura.punishments;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Records.Review;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.model.Simple.ReviewStatus;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** 1.1.0: warning notifications and the owner-review workflow for quick punishments. */
class ReviewAndWarningTest {
    Fx fx;
    Actor owner;
    Actor mod;
    Actor warner;
    Target steve;

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fx();
        owner = Fx.owner();
        mod = Fx.staff("JrMod", 20, Fx.JR_MOD);
        warner = Fx.staff("Warner", 5, Perms.PUNISH_OTHERS, Perms.punish(PunishType.WARN));
        steve = Fx.target("Steve");
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    static <T> Result<T> r(CompletableFuture<Result<T>> f) throws Exception {
        return f.get(10, TimeUnit.SECONDS);
    }

    static void await(BooleanSupplier c) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(10);
    }

    Punishment warn(Actor by, String reason) throws Exception {
        return r(fx.punishments.issueQuick(by, new PunishmentService.Request(steve, PunishType.WARN, 0, reason, List.of()))).orThrow();
    }

    Punishment mute(Actor by, long duration) throws Exception {
        return r(fx.punishments.issueQuick(by, new PunishmentService.Request(steve, PunishType.MUTE, duration, "Spamming the global chat channel.", List.of()))).orThrow();
    }

    Review reviewOf(Punishment p) throws Exception {
        return fx.db.txSync(c -> Repo.Reviews.byPunishment(c, p.id()));
    }

    // ------------------------------------------------------------------ warnings
    @Test
    void onlineWarningIsShownOnceAndMarkedDelivered() throws Exception {
        fx.online.add(steve.uuid());
        Punishment p = warn(warner, "Please stop spamming chat.");
        await(() -> fx.delivered.size() == 1);
        assertEquals(1, fx.delivered.size());
        assertEquals("Please stop spamming chat.", NotificationService.payload(fx.delivered.get(0)).get("reason"));
        await(() -> { try { return fx.db.<Boolean>txSync(c -> Repo.Punishments.get(c, p.id()).delivered()); } catch (Exception e) { return false; } });
        assertTrue(fx.db.<Boolean>txSync(c -> Repo.Punishments.get(c, p.id()).delivered()));
        fx.core.notifications.deliverPending(steve.uuid()).get(5, TimeUnit.SECONDS);
        assertEquals(1, fx.delivered.size(), "a second delivery pass must not show it again");
    }

    @Test
    void offlineWarningsAreQueuedAndMarkedDeliveredOnlyAfterTheyAreShown() throws Exception {
        Punishment p = warn(warner, "First: spamming the chat.");
        fx.core.notifications.deliverPending(steve.uuid()).get(5, TimeUnit.SECONDS);
        assertTrue(fx.delivered.isEmpty());
        assertEquals(1, fx.db.<Integer>txSync(c -> Repo.Notifications.pendingFor(c, steve.uuid()).size()));
        assertFalse(fx.db.<Boolean>txSync(c -> Repo.Punishments.get(c, p.id()).delivered()), "not delivered while the player is offline");
        fx.online.add(steve.uuid());
        fx.core.notifications.deliverPending(steve.uuid()).get(5, TimeUnit.SECONDS);
        assertEquals(1, fx.delivered.size());
        assertTrue(fx.db.<Boolean>txSync(c -> Repo.Punishments.get(c, p.id()).delivered()));
        assertEquals(0, fx.db.<Integer>txSync(c -> Repo.Notifications.pendingFor(c, steve.uuid()).size()));
    }

    @Test
    void severalQueuedWarningsAreDeliveredInOrderWithoutDuplicates() throws Exception {
        Punishment a = warn(warner, "Warning one: chat spam.");
        fx.now += 1000;
        Punishment b = warn(warner, "Warning two: chat spam.");
        fx.now += 1000;
        Punishment c3 = warn(warner, "Warning three: chat spam.");
        fx.online.add(steve.uuid());
        // two concurrent logins/passes must still show each warning exactly once
        CompletableFuture<Void> x = fx.core.notifications.deliverPending(steve.uuid());
        CompletableFuture<Void> y = fx.core.notifications.deliverPending(steve.uuid());
        x.get(5, TimeUnit.SECONDS);
        y.get(5, TimeUnit.SECONDS);
        await(() -> fx.delivered.size() == 3);
        assertEquals(3, fx.delivered.size());
        assertEquals(List.of(String.valueOf(a.id()), String.valueOf(b.id()), String.valueOf(c3.id())),
                fx.delivered.stream().map(n -> NotificationService.payload(n).get("id")).toList(), "oldest first");
    }

    @Test
    void warnNeedsOnlyThePunishWarnNodeAndStillHonoursHierarchy() throws Exception {
        assertTrue(r(fx.punishments.issueQuick(warner, new PunishmentService.Request(steve, PunishType.WARN, 0, "Language in public chat.", List.of()))).isOk(),
                "no review, no level and no other permission are needed");
        Actor nobody = Fx.staff("Nobody", 5, Perms.PUNISH_OTHERS);
        assertEquals(Code.NO_PERMISSION, r(fx.punishments.issueQuick(nobody, new PunishmentService.Request(steve, PunishType.WARN, 0, "Language in public chat.", List.of()))).code());
        Target boss = Fx.target("Boss");
        fx.auth.put(boss.uuid(), new LevelResolver.TargetAuth(50, false));
        assertEquals(Code.HIERARCHY, r(fx.punishments.issueQuick(warner, new PunishmentService.Request(boss, PunishType.WARN, 0, "Language in public chat.", List.of()))).code());
    }

    @Test
    void routineWarningsAndKicksNeverEnterTheReviewQueue() throws Exception {
        Punishment w = warn(warner, "Please stop spamming chat.");
        assertNull(reviewOf(w));
        fx.online.add(steve.uuid());
        Punishment k = r(fx.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.KICK, 0, "Disrupting the spawn area.", List.of()))).orThrow();
        assertNull(reviewOf(k));
        assertEquals("Routine Warning", w.classification());
        assertEquals(0, fx.db.<Long>txSync(c -> Repo.Reviews.countByStatuses(c, List.of(ReviewStatus.PENDING_REVIEW), null)));
    }

    @Test
    void repeatWarningsAreCountedButNeverEscalateAutomatically() throws Exception {
        for (int i = 1; i <= 4; i++) {
            fx.now += 1000;
            warn(warner, "Warning number " + i + " for chat spam.");
        }
        List<Map<String, String>> counts = fx.db.txSync(c -> Repo.Notifications.pendingFor(c, steve.uuid()).stream().map(NotificationService::payload).toList());
        assertEquals("4", counts.get(3).get("count"));
        assertEquals(0, fx.db.<Integer>txSync(c -> (int) Repo.Punishments.forTarget(c, steve.uuid(), 50, 0).stream().filter(p -> p.type() != PunishType.WARN).count()),
                "warnings never trigger a jail, mute or ban by themselves");
        assertTrue(fx.jail.prisoners.isEmpty());
        assertEquals(0, fx.db.<Long>txSync(c -> Repo.Cases.countOpenForAccused(c, steve.uuid())));
    }

    // ------------------------------------------------------------------ owner review
    @Test
    void aQuickMuteIsImmediateAndCreatesAPendingReview() throws Exception {
        Punishment p = mute(mod, Durations.HOUR);
        assertTrue(fx.punishments.cache().mute(steve.uuid(), fx.now).isPresent(), "enforced immediately, before any review");
        Review rv = reviewOf(p);
        assertNotNull(rv);
        assertEquals(ReviewStatus.PENDING_REVIEW, rv.status());
        assertEquals(fx.now + 24 * Durations.HOUR, rv.evidenceDue());
        assertEquals(fx.now + 72 * Durations.HOUR, rv.reviewDue());
        assertEquals("Emergency Quick Punishment", p.classification());
        await(() -> fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.REVIEW_CREATED));
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.REVIEW_CREATED && e.key().startsWith("review-created:")));
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.QUICK_PUNISHMENT));
    }

    @Test
    void reviewDecisionsNeedTheirOwnPermissionsAndNotesStayPrivate() throws Exception {
        Review rv = reviewOf(mute(mod, Durations.HOUR));
        Actor viewer = Fx.staff("Viewer", 40, Perms.REVIEW_VIEW);
        Actor reviewer = Fx.staff("Reviewer", 90, Perms.REVIEW_VIEW, Perms.REVIEW_REVIEW);
        assertEquals(Code.NO_PERMISSION, r(fx.reviews.take(viewer, rv.id(), rv.version())).code());
        Review taken = r(fx.reviews.take(reviewer, rv.id(), rv.version())).orThrow();
        assertEquals(ReviewStatus.UNDER_REVIEW, taken.status());
        assertTrue(r(fx.reviews.addNote(reviewer, rv.id(), "Looks justified but ask for the log.")).isOk());
        assertTrue(r(fx.reviews.detail(viewer, rv.id())).orThrow().events().stream().noneMatch(e -> e.type().equals("NOTE")), "private notes hidden from plain viewers");
        Actor sensitive = Fx.staff("Sens", 90, Perms.REVIEW_VIEW, Perms.REVIEW_SENSITIVE);
        assertTrue(r(fx.reviews.detail(sensitive, rv.id())).orThrow().events().stream().anyMatch(e -> e.type().equals("NOTE")));
        assertEquals(Code.NO_PERMISSION, r(fx.reviews.reverse(reviewer, rv.id(), taken.version(), "Not justified at all here.")).code(), "reversing is a separate permission");
        assertEquals(Code.NO_PERMISSION, r(fx.reviews.modify(reviewer, rv.id(), taken.version(), Durations.HOUR / 2, null, "Shorten it please.")).code());
        Review up = r(fx.reviews.uphold(reviewer, rv.id(), taken.version(), "The evidence supports the mute.")).orThrow();
        assertEquals(ReviewStatus.UPHELD, up.status());
        assertEquals(Code.STATE, r(fx.reviews.uphold(reviewer, rv.id(), up.version(), "Deciding a second time.")).code());
        assertTrue(fx.punishments.cache().mute(steve.uuid(), fx.now).isPresent(), "upholding leaves the punishment in force");
    }

    @Test
    void staleReviewVersionIsRejected() throws Exception {
        Review rv = reviewOf(mute(mod, Durations.HOUR));
        Actor reviewer = Fx.staff("Reviewer", 90, Perms.REVIEW_VIEW, Perms.REVIEW_REVIEW);
        assertTrue(r(fx.reviews.take(reviewer, rv.id(), rv.version())).isOk());
        assertEquals(Code.CONFLICT, r(fx.reviews.uphold(reviewer, rv.id(), rv.version(), "Using an out-of-date copy.")).code());
    }

    @Test
    void reversingRevokesThePunishmentAndNotifiesTheIssuer() throws Exception {
        Punishment p = mute(mod, Durations.HOUR);
        Review rv = reviewOf(p);
        fx.online.add(mod.uuid());
        Review done = r(fx.reviews.reverse(owner, rv.id(), rv.version(), "The mute was applied to the wrong player.")).orThrow();
        assertEquals(ReviewStatus.REVERSED, done.status());
        assertTrue(fx.punishments.cache().mute(steve.uuid(), fx.now).isEmpty());
        assertEquals(PunishStatus.REVOKED, fx.db.<Punishment>txSync(c -> Repo.Punishments.get(c, p.id())).status());
        await(() -> fx.delivered.stream().anyMatch(n -> n.kind().equals(ReviewService.STAFF)));
        assertTrue(fx.delivered.stream().anyMatch(n -> n.kind().equals(ReviewService.STAFF) && n.uuid().equals(mod.uuid())), "issuer is told");
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.REVIEW_DECISION));
    }

    @Test
    void modifyingShortensTheDurationFromTheOriginalIssueTime() throws Exception {
        Punishment p = mute(mod, Durations.HOUR);
        Review rv = reviewOf(p);
        fx.now += 10 * 60_000L;
        Review m = r(fx.reviews.modify(owner, rv.id(), rv.version(), 30 * 60_000L, null, "Reduced: first offence for this player.")).orThrow();
        assertEquals(ReviewStatus.MODIFIED, m.status());
        Punishment after = fx.db.txSync(c -> Repo.Punishments.get(c, p.id()));
        assertEquals(p.issuedAt() + 30 * 60_000L, after.expiresAt());
        assertEquals(p.expiresAt(), after.originalExpiresAt(), "the original expiry is preserved for the record");
        assertEquals(after.expiresAt(), fx.punishments.cache().mute(steve.uuid(), fx.now).orElseThrow().expiresAt(), "cache refreshed");
        // a duration that has already elapsed is refused instead of silently ending the punishment
    }

    @Test
    void modifyingToADurationThatAlreadyElapsedIsRefused() throws Exception {
        Review rv = reviewOf(mute(mod, Durations.HOUR));
        fx.now += 20 * 60_000L;
        assertEquals(Code.INVALID, r(fx.reviews.modify(owner, rv.id(), rv.version(), 5 * 60_000L, null, "Too short now.")).code());
        assertEquals(ReviewStatus.PENDING_REVIEW, reviewOf(fx.db.txSync(c -> Repo.Punishments.get(c, rv.punishmentId()))).status());
    }

    @Test
    void anUnreviewedPunishmentIsNeverReversedAutomaticallyAndRemindersAreDeduplicated() throws Exception {
        Punishment p = mute(owner, 30 * Durations.DAY);
        Review rv = reviewOf(p);
        fx.now += 80 * Durations.HOUR; // far past the 72h review deadline
        assertEquals(2, r(fx.reviews.remind()).orThrow(), "one evidence reminder for the issuer + one overdue notice for the owner team");
        assertEquals(0, r(fx.reviews.remind()).orThrow(), "no second reminder inside the repeat window");
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.REVIEW_OVERDUE));
        assertEquals(ReviewStatus.PENDING_REVIEW, reviewOf(p).status(), "still waiting for a human decision");
        assertTrue(fx.punishments.cache().mute(steve.uuid(), fx.now).isPresent(), "still in force");
        fx.now += 13 * Durations.HOUR;
        assertEquals(2, r(fx.reviews.remind()).orThrow(), "reminds again after the repeat interval");
        assertEquals(2, fx.events.stream().filter(e -> e.type() == DomainEvent.Type.REVIEW_OVERDUE).count());
        assertEquals(2, fx.events.stream().filter(e -> e.type() == DomainEvent.Type.REVIEW_OVERDUE).map(DomainEvent::key).distinct().count(), "each reminder has its own dedupe key");
        assertEquals(rv.id(), reviewOf(p).id());
    }

    @Test
    void issuerGetsEvidenceRemindersAndCanAddEvidenceThatCreatesTheCase() throws Exception {
        Punishment p = mute(mod, Durations.HOUR);
        Review rv = reviewOf(p);
        assertNull(rv.caseId());
        fx.now += 20 * Durations.HOUR; // inside the "12h before deadline" window
        fx.online.add(mod.uuid());
        assertTrue(r(fx.reviews.remind()).orThrow() >= 1);
        await(() -> fx.delivered.stream().anyMatch(n -> n.kind().equals(ReviewService.STAFF)));
        assertTrue(fx.delivered.stream().anyMatch(n -> n.uuid().equals(mod.uuid())));
        Actor stranger = Fx.staff("Stranger", 20, Fx.JR_MOD);
        assertEquals(Code.NO_PERMISSION, r(fx.reviews.submitEvidence(stranger, rv.id(), Fx.ev())).code(), "only the issuer or a reviewer");
        var ev = r(fx.reviews.submitEvidence(mod, rv.id(), Fx.ev())).orThrow();
        Review after = reviewOf(p);
        assertNotNull(after.caseId(), "a case was created to hold the evidence");
        assertEquals(after.caseId(), fx.db.<Punishment>txSync(c -> Repo.Punishments.get(c, p.id())).caseId());
        assertEquals(1, fx.db.<Integer>txSync(c -> Repo.Evidences.forCase(c, after.caseId(), false).size()));
        assertNotNull(ev);
    }

    @Test
    void referOpensAJudicialCaseAndMarksFurtherInvestigation() throws Exception {
        Review rv = reviewOf(mute(mod, Durations.HOUR));
        Long caseId = r(fx.reviews.refer(owner, rv.id(), rv.version(), "Pattern suggests a full case is needed.")).orThrow();
        assertNotNull(caseId);
        assertEquals(ReviewStatus.FURTHER_INVESTIGATION, reviewOf(fx.db.txSync(c -> Repo.Punishments.get(c, rv.punishmentId()))).status());
        assertTrue(fx.punishments.cache().mute(steve.uuid(), fx.now).isPresent(), "referral does not lift the punishment");
    }

    @Test
    void exemptLevelAndMinimumDurationSkipReview() throws Exception {
        Fx.Mutator m = new Fx.Mutator();
        m.set.put("review.exempt-level", 80);
        m.set.put("review.min-duration", "30m");
        try (Fx f2 = new Fx(null, m)) {
            Actor senior = Fx.staff("Senior", 90, Fx.concat(Fx.JR_MOD, Perms.limit(PunishType.MUTE, "long")));
            Punishment exempt = r(f2.punishments.issueQuick(senior, new PunishmentService.Request(steve, PunishType.MUTE, Durations.HOUR, "Spamming the global chat channel.", List.of()))).orThrow();
            assertNull(f2.db.<Review>txSync(c -> Repo.Reviews.byPunishment(c, exempt.id())), "level >= exempt-level skips review");
            f2.punishments.cache().remove(exempt);
            Target alex = Fx.target("Alex");
            Punishment tooShort = r(f2.punishments.issueQuick(mod, new PunishmentService.Request(alex, PunishType.MUTE, 10 * 60_000L, "Spamming the global chat channel.", List.of()))).orThrow();
            assertNull(f2.db.<Review>txSync(c -> Repo.Reviews.byPunishment(c, tooShort.id())), "below min-duration skips review");
            Target bo = Fx.target("Bo");
            Punishment longer = r(f2.punishments.issueQuick(mod, new PunishmentService.Request(bo, PunishType.MUTE, Durations.HOUR, "Spamming the global chat channel.", List.of()))).orThrow();
            assertNotNull(f2.db.<Review>txSync(c -> Repo.Reviews.byPunishment(c, longer.id())));
        }
    }

    @Test
    void reviewCanBeSwitchedOffEntirely() throws Exception {
        Fx.Mutator m = new Fx.Mutator();
        m.set.put("review.enabled", false);
        try (Fx f2 = new Fx(null, m)) {
            Punishment p = r(f2.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.MUTE, Durations.HOUR, "Spamming the global chat channel.", List.of()))).orThrow();
            assertNull(f2.db.<Review>txSync(c -> Repo.Reviews.byPunishment(c, p.id()))); // quick punishments themselves are unaffected
            assertTrue(f2.punishments.cache().mute(steve.uuid(), f2.now).isPresent());
        }
    }
}
