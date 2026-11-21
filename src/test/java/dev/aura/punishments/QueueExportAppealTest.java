package dev.aura.punishments;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Simple.AppealStatus;
import dev.aura.punishments.model.Simple.EvidenceType;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.model.Simple.Verdict;
import dev.aura.punishments.service.*;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 1.1.0: work queue, case export, appeal tickets and read-only CoreProtect evidence. */
class QueueExportAppealTest {
    @TempDir
    Path dir;
    Fx fx;
    Actor owner;
    Actor mod;
    Target steve;
    WorkQueueService queue;
    ExportService exports;

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fx();
        owner = Fx.owner();
        mod = Fx.staff("JrMod", 20, Fx.JR_MOD);
        steve = Fx.target("Steve");
        fx.judges.add(owner.uuid());
        queue = new WorkQueueService(fx.core);
        exports = new ExportService(fx.core, fx.cases, dir);
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    static <T> Result<T> r(CompletableFuture<Result<T>> f) throws Exception {
        return f.get(10, TimeUnit.SECONDS);
    }

    Case mkCase(Target t, boolean custody) throws Exception {
        Case c = r(fx.cases.create(mod, new CaseService.CreateRequest(t, "harassment", Severity.MODERATE,
                "Repeated offensive behaviour towards others.", null, List.of(Fx.ev())))).orThrow();
        if (custody) assertTrue(r(fx.custody.initiate(mod, c.id())).isOk());
        return c;
    }

    // ------------------------------------------------------------------ work queue
    @Test
    void queueIsPrioritisedAndCountsEveryWaitingItem() throws Exception {
        Case trial = mkCase(Fx.target("Alex"), false);
        r(fx.cases.transition(owner, trial.id(), trial.version(), CaseStatus.AWAITING_TRIAL, null));
        Case held = mkCase(steve, true);
        fx.core.settings.dashboard.priority(); // sanity: configured
        r(fx.punishments.issueQuick(mod, new PunishmentService.Request(Fx.target("Bo"), PunishType.MUTE, Durations.HOUR, "Spamming the global chat channel.", List.of())));
        fx.now += 100 * Durations.HOUR; // custody review (48h) and the 72h quick review are now overdue
        var q = r(queue.queue(owner)).orThrow();
        assertEquals(1, q.count(WorkQueueService.Kind.CUSTODY_OVERDUE));
        assertEquals(1, q.count(WorkQueueService.Kind.QUICK_REVIEW_OVERDUE));
        assertEquals(1, q.count(WorkQueueService.Kind.AWAITING_TRIAL));
        assertEquals(WorkQueueService.Kind.CUSTODY_OVERDUE, q.items().get(0).kind(), "overdue custody is the most urgent by default");
        assertEquals(WorkQueueService.Kind.AWAITING_TRIAL, q.items().get(q.items().size() - 1).kind());
        assertTrue(q.items().stream().anyMatch(i -> i.caseId() != null && i.caseId() == held.id()));
    }

    @Test
    void approachingCustodyIsFlaggedBeforeItIsOverdue() throws Exception {
        mkCase(steve, true);
        fx.now += 40 * Durations.HOUR; // 8h left of 48h, inside the 12h "approaching" window
        var q = r(queue.queue(owner)).orThrow();
        assertEquals(1, q.count(WorkQueueService.Kind.CUSTODY_IMMINENT));
        assertEquals(0, q.count(WorkQueueService.Kind.CUSTODY_OVERDUE));
    }

    @Test
    void queueOnlyShowsWhatTheViewerMayOpen() throws Exception {
        r(fx.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.MUTE, Durations.HOUR, "Spamming the global chat channel.", List.of())));
        Actor dashboardOnly = Fx.staff("Dash", 50, Perms.DASHBOARD);
        var q = r(queue.queue(dashboardOnly)).orThrow();
        assertEquals(0, q.count(WorkQueueService.Kind.QUICK_REVIEW_PENDING), "needs review.view");
        Actor reviewer = Fx.staff("Rev", 90, Perms.REVIEW_VIEW);
        assertEquals(1, r(queue.queue(reviewer)).orThrow().count(WorkQueueService.Kind.QUICK_REVIEW_PENDING));
        assertEquals(Code.NO_PERMISSION, r(queue.queue(Fx.staff("None", 5))).code());
    }

    @Test
    void priorityIsConfigurable() throws Exception {
        Fx.Mutator m = new Fx.Mutator();
        m.set.put("dashboard.priority.awaiting-trial", 999);
        try (Fx f2 = new Fx(null, m)) {
            WorkQueueService q2 = new WorkQueueService(f2.core);
            Case t = r(f2.cases.create(mod, new CaseService.CreateRequest(Fx.target("Alex"), "harassment", Severity.MODERATE, "Repeated offensive behaviour towards others.", null, List.of(Fx.ev())))).orThrow();
            r(f2.cases.transition(owner, t.id(), t.version(), CaseStatus.AWAITING_TRIAL, null));
            Case h = r(f2.cases.create(mod, new CaseService.CreateRequest(steve, "harassment", Severity.MODERATE, "Repeated offensive behaviour towards others.", null, List.of(Fx.ev())))).orThrow();
            assertTrue(r(f2.custody.initiate(mod, h.id())).isOk());
            f2.now += 100 * Durations.HOUR;
            assertEquals(WorkQueueService.Kind.AWAITING_TRIAL, r(q2.queue(owner)).orThrow().items().get(0).kind());
        }
    }

    // ------------------------------------------------------------------ export
    @Test
    void exportContainsTheCaseButNeverIpsOrPrivateNotes() throws Exception {
        fx.db.txSync(c -> {
            Repo.Profiles.upsert(c, steve.uuid(), "Steve", "203.0.113.77", 0, fx.now);
            return null;
        });
        Case c = mkCase(steve, false);
        Actor noter = Fx.staff("Noter", 40, Fx.concat(Fx.JR_MOD, Perms.SENSITIVE_NOTES, Perms.EXPORT));
        assertTrue(r(fx.cases.addNote(noter, c.id(), null, "Totally private suspicion about alts.", true)).isOk());
        assertTrue(r(fx.cases.addNote(noter, c.id(), null, "Public staff note about the incident.", false)).isOk());
        Actor exporter = Fx.staff("Exporter", 40, Fx.concat(Fx.JR_MOD, Perms.EXPORT));
        var md = r(exports.export(exporter, c.id(), ExportService.Format.MARKDOWN, true)).orThrow();
        assertTrue(md.content().startsWith("# Case summary " + c.ref()));
        assertTrue(md.content().contains("Steve"));
        assertTrue(md.content().contains("proof.png"), "evidence is included for someone who may see it");
        assertTrue(md.content().contains("Public staff note"));
        assertFalse(md.content().contains("203.0.113.77"), "IP addresses never leave the server");
        assertFalse(md.content().contains("private suspicion"), "private notes need sensitive.notes");
        assertTrue(Files.exists(md.path()));
        assertTrue(md.path().startsWith(dir.resolve("exports")), "files only ever go to the exports folder");
        assertTrue(md.fileName().matches("AP-\\d{4}-\\d{8}-\\d{6}\\.md"));
        var withPrivate = r(exports.export(noter, c.id(), ExportService.Format.TEXT, false)).orThrow();
        assertTrue(withPrivate.content().contains("private suspicion"), "holders of sensitive.notes see them");
        assertNull(withPrivate.path(), "no file requested");
        assertFalse(withPrivate.content().contains("203.0.113.77"));
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.EXPORT));
    }

    @Test
    void exportNeedsItsOwnPermissionAndRespectsCaseAccess() throws Exception {
        Case c = mkCase(steve, false);
        assertEquals(Code.NO_PERMISSION, r(exports.export(mod, c.id(), ExportService.Format.MARKDOWN, false)).code());
        Actor noEvidence = Fx.staff("NoEv", 40, Perms.EXPORT, Perms.CASE_VIEW_ALL);
        var res = r(exports.export(noEvidence, c.id(), ExportService.Format.MARKDOWN, false)).orThrow();
        assertFalse(res.content().contains("proof.png"), "evidence is left out without case.evidence.view");
        assertTrue(res.content().contains("do not have permission to see evidence"));
        Actor outsider = Fx.staff("Out", 40, Perms.EXPORT);
        assertFalse(r(exports.export(outsider, c.id(), ExportService.Format.MARKDOWN, false)).isOk(), "cannot export a case you may not view");
    }

    // ------------------------------------------------------------------ appeals / ticket tool
    @Test
    void ticketReferencesAreValidatedAndRoutedToTheAppealLog() throws Exception {
        assertEquals("0042", JudicialService.cleanTicket("0042"));
        assertEquals("appeal-0042", JudicialService.cleanTicket("appeal-0042"));
        assertEquals("42", JudicialService.cleanTicket("#42"));
        assertNotNull(JudicialService.cleanTicket("https://discord.com/channels/123456789012345678/987654321098765432"));
        assertNull(JudicialService.cleanTicket("javascript:alert(1)"));
        assertNull(JudicialService.cleanTicket("https://evil.example/channels/1/2"));
        assertNull(JudicialService.cleanTicket("ticket 42 <b>x</b>"));
        assertNull(JudicialService.cleanTicket(""));
        Case c = mkCase(steve, false);
        Actor linker = Fx.staff("Linker", 40, Fx.concat(Fx.JR_MOD, Perms.APPEAL_TICKET));
        assertEquals(Code.NO_PERMISSION, r(fx.judicial.linkAppealTicket(mod, c.id(), "0042")).code());
        assertEquals(Code.INVALID, r(fx.judicial.linkAppealTicket(linker, c.id(), "<script>")).code());
        Case linked = r(fx.judicial.linkAppealTicket(linker, c.id(), "0042")).orThrow();
        assertEquals("0042", linked.appealTicket());
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.APPEAL_TICKET && e.fields().get("Ticket").equals("0042")));
        assertEquals("appeal", DomainEvent.Type.APPEAL_TICKET.category);
        assertEquals("appeal", DomainEvent.Type.APPEAL.category);
    }

    @Test
    void appealIsTakenThenDecidedAndKeepsItsTicket() throws Exception {
        Case c = mkCase(steve, true);
        c = r(fx.cases.transition(owner, c.id(), c.version(), CaseStatus.AWAITING_TRIAL, null)).orThrow();
        c = r(fx.cases.assignJudge(owner, c.id(), c.version(), new Target(owner.uuid(), "Owner", true))).orThrow();
        c = r(fx.cases.transition(owner, c.id(), c.version(), CaseStatus.IN_TRIAL, null)).orThrow();
        Case g = r(fx.judicial.enterVerdict(owner, c.id(), c.version(), Verdict.GUILTY, "Clear evidence of sustained harassment.")).value().c();
        Case s = r(fx.judicial.sentence(owner, g.id(), g.version(), new JudicialService.SentenceSpec(PunishType.BAN, 3 * Durations.DAY, "Three day ban for harassment.", null))).value().c();
        Actor appellant = Fx.staff("Clerk", 40, Fx.concat(Fx.JR_MOD, Perms.APPEAL_FILE));
        assertTrue(r(fx.judicial.fileAppeal(appellant, s.id(), "The accused disputes the finding entirely.", "appeal-0042")).isOk());
        assertEquals("appeal-0042", fx.db.<Case>txSync(cn -> Repo.Cases.get(cn, s.id())).appealTicket());
        assertEquals(Code.INVALID, r(fx.judicial.fileAppeal(appellant, s.id(), "Another appeal for the same case.", "bad link!")).code());
        assertEquals(AppealStatus.PENDING, fx.db.<dev.aura.punishments.model.Records.Appeal>txSync(cn -> Repo.Appeals.pendingForCase(cn, s.id())).status());
        assertTrue(r(fx.judicial.takeAppeal(owner, s.id())).isOk());
        assertEquals(AppealStatus.UNDER_REVIEW, fx.db.<dev.aura.punishments.model.Records.Appeal>txSync(cn -> Repo.Appeals.pendingForCase(cn, s.id())).status());
        assertEquals(Code.STATE, r(fx.judicial.takeAppeal(owner, s.id())).code(), "already taken");
        assertEquals(1, fx.db.<Long>txSync(cn -> Repo.Appeals.countPending(cn)), "an appeal under review is still open work");
        assertEquals(Code.INVALID, r(fx.judicial.decideAppeal(owner, s.id(), AppealStatus.UNDER_REVIEW, "Not a decision at all really.")).code());
        assertTrue(r(fx.judicial.decideAppeal(owner, s.id(), AppealStatus.UPHELD, "The original sentence stands after review.")).isOk());
        assertEquals(0, fx.db.<Long>txSync(cn -> Repo.Appeals.countPending(cn)));
    }

    // ------------------------------------------------------------------ CoreProtect (read-only)
    static final class FakeCp implements CoreProtectPort {
        boolean up = true;
        boolean boom;
        CoreProtectPort.Query last;

        @Override public boolean available() { return up; }
        @Override public String status() { return up ? "fake" : "down"; }
        @Override public Result lookup(Query q) {
            last = q;
            if (boom) throw new IllegalStateException("db locked");
            return new Result(List.of("t1 placed stone", "t2 broke dirt", "t3 placed tnt"), 3, false);
        }
    }

    @Test
    void coreProtectIsReadOnlyClampedAndAttachedAsPlainEvidence() throws Exception {
        CoreProtectService cp = new CoreProtectService(fx.core, fx.cases);
        FakeCp fake = new FakeCp();
        cp.port(fake);
        Case c = mkCase(steve, false);
        Actor looker = Fx.staff("Looker", 40, Fx.concat(Fx.JR_MOD, Perms.COREPROTECT));
        assertEquals(Code.NO_PERMISSION, r(cp.lookup(mod, new CoreProtectPort.Query("Steve", CoreProtectPort.Kind.BLOCKS, 3600, "world", 0, 64, 0, 20))).code());
        var res = r(cp.lookup(looker, new CoreProtectPort.Query("Steve", CoreProtectPort.Kind.BLOCKS, 90L * 86400, "world", 1, 2, 3, 9999))).orThrow();
        assertEquals(3, res.total());
        assertEquals(7 * 86400, fake.last.seconds(), "time window is clamped to integrations.coreprotect.max-time");
        assertEquals(50, fake.last.radius(), "radius is clamped to integrations.coreprotect.max-radius");
        var ev = r(cp.attach(looker, c.id(), fake.last, res)).orThrow();
        assertEquals(EvidenceType.COREPROTECT, ev.type());
        assertTrue(ev.reference().contains("read-only snapshot"));
        // lookups never change the case, the player or the world: no verdict, no punishment, no status change
        Case after = fx.db.txSync(cn -> Repo.Cases.get(cn, c.id()));
        assertEquals(c.status(), after.status());
        assertEquals(0, fx.db.<Integer>txSync(cn -> Repo.Punishments.forCase(cn, c.id()).size()), "CoreProtect never decides guilt");
        assertEquals(Code.INVALID, r(cp.lookup(looker, new CoreProtectPort.Query("St; DROP", CoreProtectPort.Kind.CHAT, 3600, null, 0, 0, 0, 1))).code());
        cp.shutdown();
    }

    @Test
    void coreProtectFailuresAndAbsenceAreReportedHonestly() throws Exception {
        CoreProtectService cp = new CoreProtectService(fx.core, fx.cases);
        Actor looker = Fx.staff("Looker", 40, Perms.COREPROTECT);
        assertFalse(cp.available());
        assertEquals(Code.UNAVAILABLE, r(cp.lookup(looker, new CoreProtectPort.Query("Steve", CoreProtectPort.Kind.CHAT, 3600, null, 0, 0, 0, 1))).code());
        FakeCp fake = new FakeCp();
        fake.boom = true;
        cp.port(fake);
        var res = r(cp.lookup(looker, new CoreProtectPort.Query("Steve", CoreProtectPort.Kind.CHAT, 3600, null, 0, 0, 0, 1)));
        assertEquals(Code.EXTERNAL, res.code());
        assertTrue(res.message().contains("Nothing was changed"));
        Fx.Mutator m = new Fx.Mutator();
        m.set.put("integrations.coreprotect.enabled", false);
        try (Fx f2 = new Fx(null, m)) {
            CoreProtectService off = new CoreProtectService(f2.core, f2.cases);
            off.port(new FakeCp());
            assertFalse(off.available(), "disabled in config");
            off.shutdown();
        }
        cp.shutdown();
    }

    @Test
    void thePortHasNoWriteMethods() {
        var names = java.util.Arrays.stream(CoreProtectPort.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName).toList();
        assertTrue(names.stream().noneMatch(n -> n.toLowerCase().contains("rollback") || n.toLowerCase().contains("restore") || n.toLowerCase().contains("purge")),
                "no rollback/restore/purge capability exists in the integration surface: " + names);
    }
}
