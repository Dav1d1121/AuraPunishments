package dev.aura.punishments.gui;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Simple.*;
import dev.aura.punishments.service.Actors;
import dev.aura.punishments.service.CaseService;
import dev.aura.punishments.service.DirectService;
import dev.aura.punishments.service.EvidenceValidator;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.util.Durations;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.permissions.Permission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the real screens with clicks and chat prompts, exactly as staff would. */
class GuiFlowTest {
    McFx fx;

    @BeforeEach
    void setUp() {
        fx = new McFx();
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    /** Everything the plugin declares, plus the top authority level. */
    private PlayerMock owner(String name) {
        PlayerMock p = fx.server.addPlayer(name);
        for (Permission perm : fx.server.getPluginManager().getPermissions()) {
            if (perm.getName().startsWith("aurapunishments.")) p.addAttachment(fx.plugin, perm.getName(), true);
        }
        p.addAttachment(fx.plugin, Perms.LEVEL_PREFIX + "100", true);
        return p;
    }

    private void click(PlayerMock p, int slot) throws Exception {
        Thread.sleep(fx.gui.settings().gui.clickCooldownMs() + 30L);
        fx.click(p, slot, ClickType.LEFT);
    }

    private void answer(PlayerMock p, String text) {
        assertTrue(fx.await(() -> fx.gui.prompts().has(p)), "a chat prompt should be waiting");
        p.chat(text);
    }

    private long countCases() throws Exception {
        return fx.plugin.core().db.<Long>txSync(cn -> Repo.Cases.countAll(cn));
    }

    // ------------------------------------------------------------ create a case entirely through the GUI
    @Test
    void createACaseThroughTheWizard() throws Exception {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        fx.server.addPlayer("Steve");
        mod.performCommand("punish Steve");
        assertTrue(fx.awaitMenu(mod, TargetMenu.class));
        click(mod, 10);                                   // Create Case
        assertInstanceOf(CaseCreateMenu.class, fx.menuOf(mod));
        click(mod, 31);                                   // submit while incomplete
        assertEquals(0, countCases(), "incomplete wizard cannot submit");
        assertTrue(fx.messages(mod).stream().anyMatch(m -> m.toLowerCase().contains("first")));
        click(mod, 11);                                   // choose offense
        assertInstanceOf(OffenseSelectMenu.class, fx.menuOf(mod));
        click(mod, 11);                                   // harassment (second in the list)
        assertInstanceOf(CaseCreateMenu.class, fx.menuOf(mod));
        click(mod, 13);                                   // description
        answer(mod, "Repeatedly harassed other players in the global chat.");
        assertTrue(fx.awaitMenu(mod, CaseCreateMenu.class));
        click(mod, 31);                                   // harassment needs evidence: still incomplete
        assertEquals(0, countCases());
        click(mod, 15);                                   // add evidence
        assertInstanceOf(EvidenceTypeMenu.class, fx.menuOf(mod));
        click(mod, 16);                                   // external link
        answer(mod, "https://example.com/proof.png");
        assertTrue(fx.awaitMenu(mod, CaseCreateMenu.class));
        click(mod, 31);                                   // submit
        assertTrue(fx.awaitMenu(mod, CaseMenu.class), "the new case opens");
        assertEquals(1, countCases());
        Case c = fx.plugin.core().db.<List<Case>>txSync(cn -> Repo.Cases.all(cn, 5, 0)).get(0);
        assertEquals("harassment", c.offenseKey());
        assertEquals("Mod", c.prosecutorName(), "the reporting staff member is the default prosecutor");
        assertEquals(CaseStatus.OPEN, c.status());
        assertEquals(1, fx.plugin.core().db.<Long>txSync(cn -> Repo.Evidences.countActive(cn, c.id())).intValue());
        assertTrue(fx.title(mod).contains(c.ref()));
    }

    // ------------------------------------------------------------ trial -> verdict -> sentence through the GUI
    @Test
    void verdictAndSentenceThroughTheGui() throws Exception {
        PlayerMock judge = owner("Judge");
        PlayerMock steve = fx.server.addPlayer("Steve");
        var actor = Actors.of(judge);
        Case c = fx.join(fx.plugin.cases().create(actor, new CaseService.CreateRequest(new Target(steve.getUniqueId(), "Steve", true), "harassment",
                Severity.MODERATE, "Repeatedly harassed other players in chat.", null, List.of(new EvidenceValidator.Input(EvidenceType.EXTERNAL_LINK, "https://example.com/a", ""))))).orThrow();
        c = fx.join(fx.plugin.cases().transition(actor, c.id(), c.version(), CaseStatus.AWAITING_TRIAL, null)).orThrow();
        c = fx.join(fx.plugin.cases().assignJudge(actor, c.id(), c.version(), new Target(judge.getUniqueId(), "Judge", true))).orThrow();
        c = fx.join(fx.plugin.cases().transition(actor, c.id(), c.version(), CaseStatus.IN_TRIAL, null)).orThrow();

        judge.performCommand("ap case " + c.id());
        assertTrue(fx.awaitMenu(judge, CaseMenu.class));
        click(judge, 28);                                  // Enter verdict
        assertInstanceOf(ChoiceMenu.class, fx.menuOf(judge));
        click(judge, 10);                                  // Guilty
        answer(judge, "The chat logs prove sustained harassment beyond doubt.");
        assertTrue(fx.awaitMenu(judge, ConfirmMenu.class));
        click(judge, 11);                                  // confirm
        assertTrue(fx.awaitMenu(judge, CaseMenu.class));
        long id = c.id();
        assertTrue(fx.await(() -> {
            try {
                return fx.plugin.core().db.txSync(cn -> Repo.Cases.get(cn, id)).status() == CaseStatus.GUILTY;
            } catch (Exception e) {
                return false;
            }
        }), "guilty verdict recorded");

        // sentence
        click(judge, 29);                                  // Impose sentence
        assertInstanceOf(SentenceMenu.class, fx.menuOf(judge));
        click(judge, 12);                                  // BAN
        click(judge, 18);                                  // first preset duration
        click(judge, 40);                                  // incomplete: no reasoning yet
        assertTrue(fx.plugin.punishments().banFor(steve.getUniqueId(), null).isEmpty());
        click(judge, 32);                                  // reasoning
        answer(judge, "Three day ban for sustained harassment of players.");
        assertTrue(fx.awaitMenu(judge, SentenceMenu.class));
        click(judge, 40);                                  // submit
        assertTrue(fx.awaitMenu(judge, ConfirmMenu.class));
        click(judge, 11);                                  // confirm
        assertTrue(fx.await(() -> fx.plugin.punishments().banFor(steve.getUniqueId(), null).isPresent()), "the sentence is enforced");
        Case done = fx.plugin.core().db.txSync(cn -> Repo.Cases.get(cn, id));
        assertEquals(CaseStatus.SENTENCED, done.status());
        assertFalse(steve.isOnline() && fx.server.getPlayer("Steve") != null, "the banned online player was disconnected");
    }

    // ------------------------------------------------------------ direct punishment guard
    @Test
    void directPunishmentScreenWillNotSubmitUntilEveryRequirementIsMet() throws Exception {
        PlayerMock admin = fx.staff("Admin", Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.DIRECT, Perms.direct(PunishType.BAN), Perms.limit(PunishType.BAN, "long"),
                Perms.LEVEL_PREFIX + "80", Perms.CASE_VIEW_ALL);
        PlayerMock steve = fx.server.addPlayer("Steve");
        admin.performCommand("punish Steve");
        assertTrue(fx.awaitMenu(admin, TargetMenu.class));
        assertEquals(Material.NETHERITE_SWORD, fx.top(admin).getItem(24).getType());
        click(admin, 24);
        assertInstanceOf(DirectMenu.class, fx.menuOf(admin));
        click(admin, 40);   // nothing chosen
        assertTrue(fx.messages(admin).stream().anyMatch(m -> m.toLowerCase().contains("evidence")));
        // only direct-eligible (major) offenses are offered
        click(admin, 10);
        assertInstanceOf(OffenseSelectMenu.class, fx.menuOf(admin));
        var inv = fx.top(admin);
        int offered = 0;
        for (int s : Views.LIST_SLOTS) if (inv.getItem(s) != null) offered++;
        assertEquals(3, offered, "cheating, exploiting and severe malicious activity only");
        assertEquals(0, fx.plugin.core().db.<Long>txSync(cn -> Repo.Cases.countAll(cn)).intValue());
        assertTrue(fx.plugin.punishments().banFor(steve.getUniqueId(), null).isEmpty());
    }

    // ------------------------------------------------------------ every other screen renders with real data
    @Test
    void everyScreenRendersWithRealData() throws Exception {
        PlayerMock o = owner("Owner");
        PlayerMock steve = fx.server.addPlayer("Steve");
        var actor = Actors.of(o);
        var t = new Target(steve.getUniqueId(), "Steve", true);
        Case c = fx.join(fx.plugin.cases().create(actor, new CaseService.CreateRequest(t, "harassment", Severity.MODERATE,
                "Repeatedly harassed other players in chat.", "some context", List.of(new EvidenceValidator.Input(EvidenceType.EXTERNAL_LINK, "https://example.com/a", ""),
                new EvidenceValidator.Input(EvidenceType.COREPROTECT, "/co l u:Steve t:1h", ""))))).orThrow();
        fx.join(fx.plugin.cases().addNote(actor, c.id(), null, "Private observation about this player", false)).orThrow();
        fx.join(fx.plugin.cases().addNote(actor, null, t.uuid(), "Profile level note", true)).orThrow();
        assertTrue(fx.join(fx.plugin.custody().initiate(actor, c.id())).isOk());
        fx.join(fx.plugin.direct().emergency(actor, new DirectService.EmergencyRequest(new Target(java.util.UUID.randomUUID(), "Threat", false),
                PunishType.BAN, Durations.HOUR, "Griefing the spawn area right now."))).orThrow();
        fx.join(fx.plugin.punishments().issueQuick(actor, new dev.aura.punishments.service.PunishmentService.Request(t, PunishType.MUTE, Durations.HOUR,
                "Spamming the global chat channel.", List.of())));

        record Open(String name, Runnable run, Class<? extends Menu> type) {}
        List<Open> opens = List.of(
                new Open("target", () -> Menus.openTarget(fx.gui, o, t), TargetMenu.class),
                new Open("dashboard", () -> Menus.openDashboard(fx.gui, o), StaffDashboard.class),
                new Open("judicial", () -> Menus.openJudicial(fx.gui, o), JudicialDashboard.class),
                new Open("case", () -> Menus.openCase(fx.gui, o, c.id()), CaseMenu.class),
                new Open("cases", () -> Menus.openCases(fx.gui, o, CaseService.Filter.ALL), CaseListMenu.class),
                new Open("history", () -> Menus.openHistory(fx.gui, o, t), HistoryMenu.class),
                new Open("admin", () -> Menus.openAdmin(fx.gui, o), AdminMenu.class));
        for (Open op : opens) {
            op.run().run();
            assertTrue(fx.awaitMenu(o, op.type()), op.name() + " should open");
            assertNotNull(fx.top(o).getItem(4), op.name() + " has a header");
        }
        // case screen: sub-screens
        Menus.openCase(fx.gui, o, c.id());
        assertTrue(fx.awaitMenu(o, CaseMenu.class));
        int[][] sub = {{10, 0}, {11, 0}, {14, 0}, {13, 0}};
        click(o, 10);   // evidence
        assertTrue(fx.awaitMenu(o, EvidenceMenu.class));
        assertEquals(2, java.util.stream.IntStream.of(Views.LIST_SLOTS).filter(s -> fx.top(o).getItem(s) != null).count());
        click(o, 45);   // back
        assertTrue(fx.awaitMenu(o, CaseMenu.class));
        click(o, 11);   // notes
        assertTrue(fx.awaitMenu(o, NotesMenu.class));
        click(o, 45);
        assertTrue(fx.awaitMenu(o, CaseMenu.class));
        click(o, 14);   // punishments (none yet on the case)
        assertInstanceOf(PunishmentListMenu.class, fx.menuOf(o));
        click(o, 45);
        assertTrue(fx.awaitMenu(o, CaseMenu.class));
        click(o, 19);   // status choices
        assertInstanceOf(ChoiceMenu.class, fx.menuOf(o));
        click(o, 31);
        assertTrue(fx.awaitMenu(o, CaseMenu.class));
        click(o, 23);   // edit details
        assertInstanceOf(ChoiceMenu.class, fx.menuOf(o));
        // target screen: every quick punishment screen and the direct / emergency screens
        Menus.openTarget(fx.gui, o, t);
        assertTrue(fx.awaitMenu(o, TargetMenu.class));
        for (int slot : new int[]{19, 20, 21, 22, 23}) {
            click(o, slot);
            assertInstanceOf(QuickPunishMenu.class, fx.menuOf(o), "quick punish at slot " + slot);
            click(o, 45);
            assertTrue(fx.awaitMenu(o, TargetMenu.class));
        }
        click(o, 24);
        assertInstanceOf(DirectMenu.class, fx.menuOf(o));
        click(o, 45);
        assertTrue(fx.awaitMenu(o, TargetMenu.class));
        click(o, 25);
        assertInstanceOf(EmergencyMenu.class, fx.menuOf(o));
        click(o, 45);
        assertTrue(fx.awaitMenu(o, TargetMenu.class));
        click(o, 30);   // revoke list (the mute)
        assertTrue(fx.awaitMenu(o, PunishmentListMenu.class));
        click(o, 45);
        assertTrue(fx.awaitMenu(o, TargetMenu.class));
        click(o, 16);   // notes of the player
        assertTrue(fx.awaitMenu(o, NotesMenu.class));
        // judicial dashboard sub-screens
        Menus.openJudicial(fx.gui, o);
        assertTrue(fx.awaitMenu(o, JudicialDashboard.class));
        click(o, 13);   // custody list
        assertTrue(fx.awaitMenu(o, CustodyListMenu.class));
        assertNotNull(fx.top(o).getItem(10), "Steve is listed in custody");
        click(o, 45);
        assertTrue(fx.awaitMenu(o, JudicialDashboard.class));
        click(o, 15);   // emergency reviews
        assertTrue(fx.awaitMenu(o, EmergencyReviewList.class));
        assertNotNull(fx.top(o).getItem(10));
        // admin: audit log
        Menus.openAdmin(fx.gui, o);
        assertTrue(fx.awaitMenu(o, AdminMenu.class));
        click(o, 12);
        assertTrue(fx.awaitMenu(o, AuditMenu.class));
        assertNotNull(fx.top(o).getItem(10));
    }
}
