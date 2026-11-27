package dev.aura.punishments.gui;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Appeal;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.AppealStatus;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.permissions.Permission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The GUI on a FRESH install (judicial system off): what is hidden, what is offered, and the full punish / evidence / appeal flow. */
class NonJudicialGuiTest {
    McFx fx;
    PlayerMock owner;
    final UUID victim = UUID.nameUUIDFromBytes("victim".getBytes());
    final Target target = new Target(victim, "Victim", false);

    void start(boolean judicial) {
        fx = new McFx(judicial);
        owner = fx.server.addPlayer("TheOwner");
        for (Permission perm : fx.server.getPluginManager().getPermissions()) {
            if (perm.getName().startsWith("aurapunishments.")) owner.addAttachment(fx.plugin, perm.getName(), true);
        }
        owner.addAttachment(fx.plugin, Perms.APPEAL_DECIDE, true); // not part of aurapunishments.*, granted explicitly
        owner.addAttachment(fx.plugin, Perms.LEVEL_PREFIX + "100", true);
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

    // ------------------------------------------------------------------ install defaults
    @Test
    void aFreshInstallStartsWithTheJudicialSystemOffAndNothingOpen() throws Exception {
        start(false);
        assertFalse(fx.plugin.core().mode.enabled());
        fx.plugin.core().mode.refresh().get();
        assertEquals(dev.aura.punishments.service.JudicialMode.State.DISABLED, fx.plugin.core().mode.state());
    }

    @Test
    void theTargetScreenHidesEveryJudicialOptionAndOffersJailAndAppeals() throws Exception {
        start(false);
        fx.plugin.core().mode.refresh().get();
        Menus.openTarget(fx.gui, owner, target);
        assertTrue(fx.awaitMenu(owner, TargetMenu.class));
        assertNotEquals(Material.WRITABLE_BOOK, at(10), "no Create Case");
        assertNotEquals(Material.BOOK, at(11), "no View Cases");
        assertNotEquals(Material.SPYGLASS, at(12), "evidence is attached to punishments, not submitted to a case");
        assertNotEquals(Material.IRON_BARS, at(13), "no pretrial detention");
        assertNotEquals(Material.NETHERITE_SWORD, at(24), "no direct punishment");
        assertNotEquals(Material.ANVIL, at(28), "no judicial review");
        assertEquals(Views.punishIcon(PunishType.JAIL), at(24), "jail is an ordinary punishment now");
        assertEquals(Material.LECTERN, at(28), "appeals");
        // everything else is still there
        assertEquals(Material.CLOCK, at(14));
        assertEquals(Material.CHEST, at(15));
        assertEquals(Material.OAK_SIGN, at(16));
        assertEquals(Views.punishIcon(PunishType.BAN), at(22));
        assertEquals(Material.REDSTONE_TORCH, at(25), "emergency actions stay");
        assertEquals(Material.ENDER_EYE, at(30));
    }

    @Test
    void withTheJudicialSystemOnTheTargetScreenIsExactlyAsBefore() throws Exception {
        start(true);
        Menus.openTarget(fx.gui, owner, target);
        assertTrue(fx.awaitMenu(owner, TargetMenu.class));
        assertEquals(Material.WRITABLE_BOOK, at(10));
        assertEquals(Material.BOOK, at(11));
        assertEquals(Material.SPYGLASS, at(12));
        assertEquals(Material.IRON_BARS, at(13));
        assertEquals(Material.NETHERITE_SWORD, at(24));
        assertEquals(Material.ANVIL, at(28));
    }

    // ------------------------------------------------------------------ a ban with evidence and a note, then everything afterwards
    private void banThroughTheGui() throws Exception {
        QuickPunishMenu.open(fx.gui, owner, null, target, PunishType.BAN);
        assertTrue(fx.awaitMenu(owner, QuickPunishMenu.class));
        click(10);
        answerDialog("7d");
        assertTrue(fx.awaitMenu(owner, QuickPunishMenu.class));
        click(12);
        answerDialog("Repeated griefing of the spawn builds");
        assertTrue(fx.awaitMenu(owner, QuickPunishMenu.class));
        click(14);                                             // evidence
        assertTrue(fx.awaitMenu(owner, EvidenceTypeMenu.class));
        click(16);                                             // external link
        answerDialog("https://example.com/proof.png");
        assertTrue(fx.awaitMenu(owner, QuickPunishMenu.class));
        click(16);                                             // staff note
        answerDialog("Seen by two staff members");
        assertTrue(fx.awaitMenu(owner, QuickPunishMenu.class));
        click(40);
        assertTrue(fx.awaitMenu(owner, ConfirmMenu.class));
        click(11);
        assertTrue(fx.await(() -> { try { return !punishments().isEmpty(); } catch (Exception e) { return false; } }));
        settle();
    }

    /** Let the GUI finish the call that just completed (its follow-up runs on the next server ticks) before the next screen is opened. */
    private void settle() throws Exception {
        for (int i = 0; i < 4; i++) {
            Thread.sleep(60);
            fx.server.getScheduler().performTicks(3);
        }
        fx.messages(owner);
    }

    @Test
    void aBanWithEvidenceAndANoteIsFinalAtOnceWithNoCase() throws Exception {
        start(false);
        banThroughTheGui();
        Punishment ban = punishments().get(0);
        assertEquals(PunishType.BAN, ban.type());
        assertEquals(PunishStatus.ACTIVE, ban.status());
        assertNull(ban.caseId());
        assertEquals(1, fx.plugin.core().db.<List<?>>txSync(c -> Repo.Evidences.forPunishment(c, ban.id(), false)).size());
        assertEquals(1, fx.plugin.core().db.<List<?>>txSync(c -> Repo.Notes.forPunishment(c, ban.id())).size());
        assertEquals(0L, (long) fx.plugin.core().db.<Long>txSync(c -> dev.aura.punishments.db.Sql.count(c, "SELECT COUNT(*) FROM cases")));
        assertTrue(fx.plugin.punishments().banFor(victim, null).isPresent(), "enforceable immediately");
    }

    @Test
    void historyOpensThePunishmentWhereEvidenceCanBeAddedAfterTheFact() throws Exception {
        start(false);
        banThroughTheGui();
        Punishment ban = punishments().get(0);
        HistoryMenu.open(fx.gui, owner, null, target);
        boolean opened = fx.awaitMenu(owner, HistoryMenu.class);
        assertTrue(opened, "history did not open; messages=" + fx.messages(owner) + " menu=" + fx.menuOf(owner));
        click(6);                                              // Punishments tab
        assertTrue(fx.await(() -> fx.top(owner).getItem(10) != null && fx.top(owner).getItem(10).getType() == Views.punishIcon(PunishType.BAN)));
        click(10);                                             // first punishment
        assertTrue(fx.awaitMenu(owner, PunishmentMenu.class));
        click(10);                                             // evidence
        assertTrue(fx.awaitMenu(owner, EvidenceMenu.class));
        click(46);                                             // submit evidence
        assertTrue(fx.awaitMenu(owner, EvidenceTypeMenu.class));
        click(16);
        answerDialog("https://example.com/later.png");
        assertTrue(fx.await(() -> { try { return fx.plugin.core().db.<List<?>>txSync(c -> Repo.Evidences.forPunishment(c, ban.id(), false)).size() == 2; } catch (Exception e) { return false; } }));
    }

    @Test
    void anAppealIsFiledAndDecidedFromThePunishmentScreen() throws Exception {
        start(false);
        banThroughTheGui();
        Punishment ban = punishments().get(0);
        PunishmentMenu.open(fx.gui, owner, null, ban.id());
        boolean pm = fx.awaitMenu(owner, PunishmentMenu.class);
        assertTrue(pm, "punishment screen did not open; messages=" + fx.messages(owner) + " menu=" + fx.menuOf(owner));
        click(13);                                             // record an appeal
        answerDialog("The player says it was a misunderstanding");
        answerDialog("0042");
        assertTrue(fx.await(() -> { try { return fx.plugin.core().db.<Appeal>txSync(c -> Repo.Appeals.pendingForPunishment(c, ban.id())) != null; } catch (Exception e) { return false; } }));
        Appeal ap = fx.plugin.core().db.txSync(c -> Repo.Appeals.pendingForPunishment(c, ban.id()));
        assertEquals("0042", ap.ticket());
        assertEquals(PunishStatus.ACTIVE, fx.plugin.core().db.<Punishment>txSync(c -> Repo.Punishments.get(c, ban.id())).status(), "filing does not lift anything");
        assertTrue(fx.awaitMenu(owner, PunishmentMenu.class));
        click(32);                                             // decide
        assertTrue(fx.awaitMenu(owner, ChoiceMenu.class));
        click(12);                                             // overturn
        answerDialog("New evidence shows the wrong player was banned");
        assertTrue(fx.awaitMenu(owner, ConfirmMenu.class));
        click(11);
        assertTrue(fx.await(() -> { try { return fx.plugin.core().db.<Punishment>txSync(c -> Repo.Punishments.get(c, ban.id())).status() == PunishStatus.REVOKED; } catch (Exception e) { return false; } }));
        assertEquals(AppealStatus.OVERTURNED, fx.plugin.core().db.<Appeal>txSync(c -> Repo.Appeals.get(c, ap.id())).status());
        assertTrue(fx.plugin.punishments().banFor(victim, null).isEmpty());
    }

    @Test
    void jailIsIssuedThroughTheGuiLikeAnyOtherPunishment() throws Exception {
        start(false);
        fx.server.addPlayer("Victim"); // not required, but a realistic online target
        QuickPunishMenu.open(fx.gui, owner, null, target, PunishType.JAIL);
        assertTrue(fx.awaitMenu(owner, QuickPunishMenu.class));
        click(10);
        answerDialog("2h");
        assertTrue(fx.awaitMenu(owner, QuickPunishMenu.class));
        click(12);
        answerDialog("Griefing the shared farm area");
        assertTrue(fx.awaitMenu(owner, QuickPunishMenu.class));
        click(40);
        assertTrue(fx.awaitMenu(owner, ConfirmMenu.class));
        click(11);
        assertTrue(fx.await(() -> fx.jail.prisoners.containsKey(victim)), "JailReloaded was asked to hold the player");
        assertTrue(fx.await(() -> { try { return punishments().stream().anyMatch(p -> p.type() == PunishType.JAIL && "HELD".equals(p.jailState())); } catch (Exception e) { return false; } }));
    }

    @Test
    void theStaffDashboardShowsAWindDownNoticeWhileJudicialWorkIsStillOpen() throws Exception {
        start(true);
        var owner0 = dev.aura.punishments.service.Actors.of(owner);
        fx.join(fx.plugin.cases().create(owner0, new dev.aura.punishments.service.CaseService.CreateRequest(target, "other", dev.aura.punishments.model.Simple.Severity.MODERATE,
                "Griefing the spawn area repeatedly", null, List.of(new dev.aura.punishments.service.EvidenceValidator.Input(dev.aura.punishments.model.Simple.EvidenceType.EXTERNAL_LINK, "https://example.com/p.png", "")))));
        var cfg = new java.io.File(fx.plugin.getDataFolder(), "config.yml");
        String text = java.nio.file.Files.readString(cfg.toPath());
        java.nio.file.Files.writeString(cfg.toPath(), text.replace("\n  enabled: true\n  # May the staff member", "\n  enabled: false\n  # May the staff member"));
        fx.plugin.reloadFor(fx.server.getConsoleSender());
        assertFalse(fx.plugin.core().mode.enabled());
        assertTrue(fx.await(() -> fx.plugin.core().mode.state() == dev.aura.punishments.service.JudicialMode.State.WIND_DOWN && fx.plugin.core().mode.report().known()));
        assertEquals(1, fx.plugin.core().mode.report().openCases());
        Menus.openDashboard(fx.gui, owner);
        assertTrue(fx.awaitMenu(owner, StaffDashboard.class));
        assertEquals(Material.ORANGE_BANNER, at(22), "administrators see what is still open");
        assertEquals(Material.ANVIL, at(14), "the judicial dashboard stays reachable until the work is finished");
    }
}
