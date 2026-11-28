package dev.aura.punishments.gui;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.EvidenceType;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.service.Actors;
import dev.aura.punishments.service.CaseService;
import dev.aura.punishments.service.EvidenceValidator;
import dev.aura.punishments.service.JudicialMode;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.permissions.Permission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The case workflow in the GUI (1.3.1): Create Case is the main path, the case screen has a Punish button, and no court buttons exist. */
class CaseworkGuiTest {
    McFx fx;
    PlayerMock owner;
    final UUID victim = UUID.nameUUIDFromBytes("victim".getBytes());
    final Target target = new Target(victim, "Victim", false);

    @BeforeEach
    void start() throws Exception {
        fx = McFx.casework();
        owner = fx.server.addPlayer("TheOwner");
        for (Permission perm : fx.server.getPluginManager().getPermissions()) {
            if (perm.getName().startsWith("aurapunishments.")) owner.addAttachment(fx.plugin, perm.getName(), true);
        }
        owner.addAttachment(fx.plugin, Perms.APPEAL_DECIDE, true);
        owner.addAttachment(fx.plugin, Perms.LEVEL_PREFIX + "100", true);
        fx.plugin.core().mode.refresh().get();
    }

    @AfterEach
    void tearDown() {
        if (fx != null) fx.close();
    }

    private void click(int slot) throws Exception {
        Thread.sleep(fx.gui.settings().gui.clickCooldownMs() + 30L);
        fx.click(owner, slot, ClickType.LEFT);
    }

    private void answerDialog(String text) {
        assertTrue(fx.await(() -> fx.dialogs.containsKey(owner.getUniqueId())), "a text dialog should be open");
        fx.answer(owner, text);
    }

    private Material at(int slot) {
        var it = fx.top(owner).getItem(slot);
        return it == null ? Material.AIR : it.getType();
    }

    private List<Punishment> punishments() throws Exception {
        return fx.plugin.core().db.txSync(c -> Repo.Punishments.forTarget(c, victim, 20, 0));
    }

    private Case newCase() {
        return fx.join(fx.plugin.cases().create(Actors.of(owner), new CaseService.CreateRequest(target, "harassment", Severity.MODERATE,
                "Repeatedly harassed other players in chat.", null, List.of(new EvidenceValidator.Input(EvidenceType.EXTERNAL_LINK, "https://example.com/a.png", ""))))).orThrow();
    }

    @Test
    void aFreshInstallIsInCaseworkMode() {
        assertFalse(fx.plugin.core().mode.enabled());
        assertEquals(JudicialMode.State.CASEWORK, fx.plugin.core().mode.state());
    }

    @Test
    void theTargetScreenMakesCreateCaseTheMainPathAndKeepsTheQuickButtons() throws Exception {
        Menus.openTarget(fx.gui, owner, target);
        assertTrue(fx.awaitMenu(owner, TargetMenu.class));
        assertEquals(Material.WRITABLE_BOOK, at(10), "Create Case");
        assertEquals(Material.BOOK, at(11), "View Cases");
        assertEquals(Material.SPYGLASS, at(12), "Submit Evidence to an open case");
        assertNotEquals(Material.IRON_BARS, at(13), "no pretrial detention");
        assertNotEquals(Material.NETHERITE_SWORD, at(24), "no direct punishment");
        assertEquals(Views.punishIcon(PunishType.JAIL), at(24), "jail stays an ordinary punishment");
        assertNotEquals(Material.ANVIL, at(28), "no judicial review");
        assertEquals(Material.LECTERN, at(28), "appeals");
        assertEquals(Material.PAPER, at(18), "a hint that the quick buttons need no case");
        for (PunishType t : new PunishType[]{PunishType.WARN, PunishType.KICK, PunishType.MUTE, PunishType.BAN, PunishType.IPBAN}) {
            assertEquals(Views.punishIcon(t), at(19 + List.of(PunishType.WARN, PunishType.KICK, PunishType.MUTE, PunishType.BAN, PunishType.IPBAN).indexOf(t)), "quick " + t);
        }
        assertEquals(Material.REDSTONE_TORCH, at(25), "emergency actions stay");
    }

    @Test
    void aCaseIsOpenedThroughTheWizardAndThenPunishedFromWithoutLeavingTheCase() throws Exception {
        Menus.openTarget(fx.gui, owner, target);
        assertTrue(fx.awaitMenu(owner, TargetMenu.class));
        click(10);                                         // Create Case
        assertTrue(fx.awaitMenu(owner, CaseCreateMenu.class));
        click(11);                                         // offense
        assertTrue(fx.awaitMenu(owner, OffenseSelectMenu.class));
        click(11);                                         // harassment
        assertTrue(fx.awaitMenu(owner, CaseCreateMenu.class));
        click(13);                                         // description
        answerDialog("Repeatedly harassed other players in the global chat.");
        assertTrue(fx.awaitMenu(owner, CaseCreateMenu.class));
        click(15);                                         // evidence
        assertTrue(fx.awaitMenu(owner, EvidenceTypeMenu.class));
        click(16);
        answerDialog("https://example.com/proof.png");
        assertTrue(fx.awaitMenu(owner, CaseCreateMenu.class));
        click(31);                                         // submit
        assertTrue(fx.awaitMenu(owner, CaseMenu.class), "the new case opens");
        Case cs = fx.plugin.core().db.<List<Case>>txSync(c -> Repo.Cases.all(c, 5, 0)).get(0);
        assertEquals(CaseStatus.OPEN, cs.status());

        assertEquals(Material.NETHERITE_SWORD, at(13), "the Punish button");
        click(13);
        assertTrue(fx.awaitMenu(owner, ChoiceMenu.class));
        click(12);                                         // Mute
        assertTrue(fx.awaitMenu(owner, QuickPunishMenu.class));
        click(10);
        answerDialog("1h");
        assertTrue(fx.awaitMenu(owner, QuickPunishMenu.class));
        click(12);
        answerDialog("Harassing other players in global chat");
        assertTrue(fx.awaitMenu(owner, QuickPunishMenu.class));
        click(40);
        assertTrue(fx.awaitMenu(owner, ConfirmMenu.class));
        click(11);
        assertTrue(fx.await(() -> { try { return !punishments().isEmpty(); } catch (Exception e) { return false; } }));
        Punishment mute = punishments().get(0);
        assertEquals(PunishType.MUTE, mute.type());
        assertEquals(PunishStatus.ACTIVE, mute.status());
        assertEquals(cs.id(), mute.caseId(), "linked to the case it was issued from");
        assertTrue(fx.plugin.punishments().muteFor(victim).isPresent(), "enforceable at once");
        assertEquals(CaseStatus.OPEN, fx.plugin.core().db.<Case>txSync(c -> Repo.Cases.get(c, cs.id())).status(), "the case stays open");
        assertTrue(fx.awaitMenu(owner, CaseMenu.class), "back on the case, which now lists the punishment");
    }

    @Test
    void theCaseScreenHasNoCourtButtons() throws Exception {
        Case cs = newCase();
        Menus.openCase(fx.gui, owner, cs.id());
        assertTrue(fx.awaitMenu(owner, CaseMenu.class));
        assertEquals(Material.NETHERITE_SWORD, at(13));
        assertEquals(Material.COMPARATOR, at(19), "change status");
        assertNotEquals(Material.LECTERN, at(20), "no judge assignment");
        assertNotEquals(Material.CLOCK, at(21), "no trial scheduling");
        assertNotEquals(Material.NAME_TAG, at(22), "no hearing reference");
        assertNotEquals(Material.BELL, at(28), "no verdict");
        assertNotEquals(Material.NETHERITE_AXE, at(29), "no sentencing");
        assertNotEquals(Material.NAME_TAG, at(37), "no case-level appeal ticket");
    }

    @Test
    void theStatusMenuOffersOnlyTheCaseworkStatuses() throws Exception {
        Case cs = newCase();
        Menus.openCase(fx.gui, owner, cs.id());
        assertTrue(fx.awaitMenu(owner, CaseMenu.class));
        click(19);
        assertTrue(fx.awaitMenu(owner, ChoiceMenu.class));
        assertEquals(Material.GRAY_DYE, at(10), "Open is the current status, so it cannot be chosen");
        assertEquals(Views.statusIcon(CaseStatus.UNDER_INVESTIGATION), at(11));
        assertEquals(Views.statusIcon(CaseStatus.UNDER_REVIEW), at(12));
        assertEquals(Views.statusIcon(CaseStatus.CLOSED), at(13));
        assertNotEquals(Views.statusIcon(CaseStatus.AWAITING_TRIAL), at(14), "no trial states");
        assertNotEquals(Views.statusIcon(CaseStatus.IN_TRIAL), at(15), "no trial states");
    }

    @Test
    void theDashboardOffersCasesButNoJudicialDashboard() throws Exception {
        Menus.openDashboard(fx.gui, owner);
        assertTrue(fx.awaitMenu(owner, StaffDashboard.class));
        assertEquals(Material.BOOK, at(11));
        assertEquals(Material.WRITABLE_BOOK, at(12));
        assertEquals(Material.ENCHANTED_BOOK, at(13));
        assertNotEquals(Material.ANVIL, at(14), "the judicial dashboard appears only to finish left-over court work");
        assertNotEquals(Material.ORANGE_BANNER, at(22), "nothing is left over");
    }
}
