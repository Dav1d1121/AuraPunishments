package dev.aura.punishments;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.model.Simple.Verdict;
import dev.aura.punishments.service.*;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** 1.1.0: defendant communication and the custody "attempted vs confirmed" event trail. */
class DefendantAndEventsTest {
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

    interface Check { boolean ok() throws Exception; }

    static void await(Check c) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (!c.ok() && System.currentTimeMillis() < end) Thread.sleep(10);
    }

    Case mkCase() throws Exception {
        return r(fx.cases.create(mod, new CaseService.CreateRequest(steve, "harassment", Severity.MODERATE,
                "Repeated offensive behaviour towards others.", null, List.of(Fx.ev())))).orThrow();
    }

    List<Map<String, String>> shown() {
        return fx.delivered.stream().filter(n -> n.kind().equals(NotificationService.DEFENDANT)).map(NotificationService::payload).toList();
    }

    @Test
    void detentionMessageCarriesCaseOffenseReasonAndInstructionsAndIsNotAGuiltFinding() throws Exception {
        fx.online.add(steve.uuid());
        Case c = mkCase();
        assertTrue(r(fx.custody.initiate(mod, c.id())).isOk());
        await(() -> shown().size() == 1);
        Map<String, String> m = shown().get(0);
        assertEquals("detention", m.get("event"));
        assertEquals(c.ref(), m.get("ref"));
        assertFalse(m.get("offense").isBlank());
        assertTrue(m.get("reason").contains("offensive behaviour"));
        assertTrue(m.get("instructions").contains("Discord"));
        // the player-facing text itself must say this is not a finding of guilt
        var msgs = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(getClass().getResourceAsStream("/messages.yml")));
        assertTrue(String.join(" ", msgs.getStringList("defendant.detention")).contains("NOT a finding of guilt"));
    }

    @Test
    void offlineDefendantGetsItOnLoginExactlyOnce() throws Exception {
        Case c = mkCase();
        assertTrue(r(fx.custody.initiate(mod, c.id())).isOk());
        await(() -> fx.db.<Integer>txSync(cn -> Repo.Notifications.pendingFor(cn, steve.uuid()).size()) == 1);
        assertTrue(shown().isEmpty(), "offline: nothing shown yet");
        fx.online.add(steve.uuid());
        fx.core.notifications.deliverPending(steve.uuid()).get(5, TimeUnit.SECONDS);
        fx.core.notifications.deliverPending(steve.uuid()).get(5, TimeUnit.SECONDS);
        assertEquals(1, shown().size());
    }

    @Test
    void notificationsSurviveARestart() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("ap", ".db");
        try (Fx f1 = new Fx(file, new Fx.Mutator())) {
            Case c = r(f1.cases.create(mod, new CaseService.CreateRequest(steve, "harassment", Severity.MODERATE, "Repeated offensive behaviour towards others.", null, List.of(Fx.ev())))).orThrow();
            assertTrue(r(f1.custody.initiate(mod, c.id())).isOk());
            await(() -> f1.db.<Integer>txSync(cn -> Repo.Notifications.pendingFor(cn, steve.uuid()).size()) == 1);
        }
        try (Fx f2 = new Fx(file, new Fx.Mutator())) {
            f2.online.add(steve.uuid());
            f2.core.notifications.deliverPending(steve.uuid()).get(5, TimeUnit.SECONDS);
            assertEquals(1, f2.delivered.size(), "queued before the restart, delivered after it");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void trialScheduledThenMovedIsReportedAsPostponed() throws Exception {
        fx.online.add(steve.uuid());
        Case c = mkCase();
        c = r(fx.cases.transition(owner, c.id(), c.version(), CaseStatus.AWAITING_TRIAL, null)).orThrow();
        c = r(fx.cases.scheduleTrial(owner, c.id(), c.version(), fx.now + 2 * Durations.DAY)).orThrow();
        await(() -> shown().stream().anyMatch(m -> m.get("event").equals("trial-scheduled")));
        c = r(fx.cases.scheduleTrial(owner, c.id(), c.version(), fx.now + 3 * Durations.DAY)).orThrow();
        await(() -> shown().stream().anyMatch(m -> m.get("event").equals("trial-postponed")));
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.TRIAL_POSTPONED));
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.TRIAL_SCHEDULED));
    }

    @Test
    void verdictAcquittalReleaseAndStatusReachTheDefendant() throws Exception {
        fx.online.add(steve.uuid());
        Case c = mkCase();
        assertTrue(r(fx.custody.initiate(mod, c.id())).isOk());
        c = r(fx.cases.transition(owner, c.id(), c.version(), CaseStatus.AWAITING_TRIAL, null)).orThrow();
        c = r(fx.cases.assignJudge(owner, c.id(), c.version(), new Target(owner.uuid(), "Owner", true))).orThrow();
        c = r(fx.cases.transition(owner, c.id(), c.version(), CaseStatus.IN_TRIAL, null)).orThrow();
        r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.NOT_GUILTY, "Evidence does not establish the allegation."));
        await(() -> shown().stream().anyMatch(m -> m.get("event").equals("acquittal")));
        assertTrue(shown().stream().anyMatch(m -> m.get("event").equals("acquittal") && m.get("detail").contains("Evidence does not establish")));
        await(() -> shown().stream().anyMatch(m -> m.get("event").equals("release")));
        assertTrue(shown().stream().anyMatch(m -> m.get("event").equals("status")), "IN_TRIAL is a configured defendant status");
    }

    @Test
    void custodyTrailShowsAttemptThenConfirmationAndNeverConfirmsAFailure() throws Exception {
        Case c = mkCase();
        assertTrue(r(fx.custody.initiate(mod, c.id())).isOk());
        await(() -> fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.CUSTODY_STARTED));
        var types = fx.events.stream().map(DomainEvent::type).toList();
        assertTrue(types.indexOf(DomainEvent.Type.CUSTODY_ATTEMPT) < types.indexOf(DomainEvent.Type.CUSTODY_STARTED), "attempt is logged before confirmation");
        // a failing JailReloaded: attempt + failure, but never a "confirmed"
        fx.events.clear();
        Target alex = Fx.target("Alex");
        Case c2 = r(fx.cases.create(mod, new CaseService.CreateRequest(alex, "harassment", Severity.MODERATE, "Repeated offensive behaviour towards others.", null, List.of(Fx.ev())))).orThrow();
        fx.jail.failNext = "cell is full";
        assertFalse(r(fx.custody.initiate(mod, c2.id())).isOk());
        await(() -> fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.CUSTODY_FAILED));
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.CUSTODY_ATTEMPT));
        assertTrue(fx.events.stream().noneMatch(e -> e.type() == DomainEvent.Type.CUSTODY_STARTED), "a failed jail is never reported as confirmed");
        assertTrue(fx.db.<Integer>txSync(cn -> Repo.Notifications.pendingFor(cn, alex.uuid()).size()) == 0, "no detention message for a custody that never happened");
    }

    @Test
    void defendantCommunicationCanBeSwitchedOff() throws Exception {
        Fx.Mutator m = new Fx.Mutator();
        m.set.put("defendant.enabled", false);
        try (Fx f2 = new Fx(null, m)) {
            f2.online.add(steve.uuid());
            Case c = r(f2.cases.create(mod, new CaseService.CreateRequest(steve, "harassment", Severity.MODERATE, "Repeated offensive behaviour towards others.", null, List.of(Fx.ev())))).orThrow();
            assertTrue(r(f2.custody.initiate(mod, c.id())).isOk());
            Thread.sleep(300);
            assertTrue(f2.delivered.isEmpty());
        }
    }
}
