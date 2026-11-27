package dev.aura.punishments.gui;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Appeal;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.service.Perms;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** /ap jail, unjail, appeal, appeals, punishment and judicial, and which commands each mode shows. */
class CommandsV13Test {
    McFx fx;
    PlayerMock staff;

    void start(boolean judicial, String... nodes) {
        fx = new McFx(judicial);
        staff = fx.staff("Staffer", nodes);
        fx.server.addPlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        if (fx != null) fx.close();
    }

    static String[] jailer() {
        return new String[]{Perms.COMMAND, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.HISTORY_VIEW, Perms.EVIDENCE_ADD, Perms.NOTE_ADD, Perms.REVOKE, Perms.APPEAL_FILE,
                Perms.punish(PunishType.JAIL), Perms.limit(PunishType.JAIL, "extended"), Perms.punish(PunishType.MUTE), Perms.limit(PunishType.MUTE, "short"), Perms.LEVEL_PREFIX + "20"};
    }

    private String all(PlayerMock p) {
        return String.join("\n", fx.messages(p));
    }

    private List<Punishment> history() throws Exception {
        UUID steve = fx.server.getPlayer("Steve").getUniqueId();
        return fx.plugin.core().db.txSync(c -> Repo.Punishments.forTarget(c, steve, 20, 0));
    }

    private void settle() throws Exception {
        for (int i = 0; i < 4; i++) {
            Thread.sleep(60);
            fx.server.getScheduler().performTicks(3);
        }
    }

    @Test
    void jailAndUnjailWorkFromTheCommandLine() throws Exception {
        start(false, jailer());
        staff.performCommand("ap jail Steve 2h Griefing the shared farm area -e https://example.com/proof.png");
        assertTrue(fx.await(() -> { try { return !history().isEmpty(); } catch (Exception e) { return false; } }));
        settle();
        Punishment p = history().get(0);
        assertEquals(PunishType.JAIL, p.type());
        assertEquals("HELD", p.jailState());
        assertTrue(fx.jail.prisoners.containsKey(fx.server.getPlayer("Steve").getUniqueId()));
        assertTrue(all(staff).contains("Jail Sentence issued to Steve"));
        staff.performCommand("ap unjail Steve Served enough, behaving well now");
        assertTrue(fx.await(() -> { try { return history().get(0).status() == PunishStatus.REVOKED; } catch (Exception e) { return false; } }));
        settle();
        assertTrue(fx.jail.prisoners.isEmpty());
        assertTrue(all(staff).contains("released from jail"));
    }

    @Test
    void jailIsNotAvailableAsACommandWhileTheJudicialSystemIsOn() throws Exception {
        start(true, jailer());
        staff.performCommand("ap jail Steve 2h Griefing the shared farm area");
        settle();
        assertTrue(history().isEmpty());
        assertFalse(fx.plugin.getCommand("aurapunishments").getTabCompleter().onTabComplete(staff, fx.plugin.getCommand("aurapunishments"), "ap", new String[]{"j"}).contains("jail"));
        assertTrue(fx.plugin.getCommand("aurapunishments").getTabCompleter().onTabComplete(staff, fx.plugin.getCommand("aurapunishments"), "ap", new String[]{"unj"}).contains("unjail"),
                "unjail stays available so standalone jails left from a judicial-off period can always be released");
    }

    @Test
    void helpListsJudicialCommandsOnlyWhileTheyApply() throws Exception {
        start(false, Perms.COMMAND, Perms.JUDICIAL_VIEW, Perms.CASE_VIEW_ALL, Perms.HISTORY_VIEW, Perms.ADMIN);
        fx.plugin.core().mode.refresh().get();
        staff.performCommand("ap help");
        String help = all(staff);
        assertFalse(help.contains("/ap judge"), "no judicial dashboard while the system is off");
        assertTrue(help.contains("/ap judicial"), "but the switch/status command is there for administrators");
        assertTrue(help.contains("/ap punishment"));
        assertTrue(help.contains("/ap history"), "history stays");
        assertTrue(help.contains("/ap case "), "historical case records stay reachable");
    }

    @Test
    void appealsAreRecordedFromTheCommandLineWithATicket() throws Exception {
        start(false, jailer());
        staff.performCommand("ap mute Steve 1h Spamming the global channel");
        assertTrue(fx.await(() -> { try { return !history().isEmpty(); } catch (Exception e) { return false; } }));
        settle();
        long id = history().get(0).id();
        staff.performCommand("ap appeal " + id + " -t 0042 The player says it was a misunderstanding");
        assertTrue(fx.await(() -> { try { return fx.plugin.core().db.<Appeal>txSync(c -> Repo.Appeals.pendingForPunishment(c, id)) != null; } catch (Exception e) { return false; } }));
        assertEquals("0042", fx.plugin.core().db.<Appeal>txSync(c -> Repo.Appeals.pendingForPunishment(c, id)).ticket());
    }

    @Test
    void theJudicialStatusCommandExplainsTheOpenWorkAndNeedsAdmin() throws Exception {
        start(true, Perms.COMMAND, Perms.ADMIN, Perms.CASE_CREATE, Perms.CASE_VIEW_ALL, Perms.PUNISH_OTHERS, Perms.PUNISH, Perms.LEVEL_PREFIX + "20");
        var owner = dev.aura.punishments.service.Actors.of(staff);
        var created = fx.join(fx.plugin.cases().create(owner, new dev.aura.punishments.service.CaseService.CreateRequest(
                new dev.aura.punishments.service.PlayerResolver.Target(UUID.nameUUIDFromBytes("x".getBytes()), "Xavier", false), "other", dev.aura.punishments.model.Simple.Severity.MINOR,
                "Spamming the chat again and again", null, List.of(new dev.aura.punishments.service.EvidenceValidator.Input(dev.aura.punishments.model.Simple.EvidenceType.EXTERNAL_LINK, "https://example.com/x.png", "")))));
        assertTrue(created.isOk(), created.message());
        var cfg = new java.io.File(fx.plugin.getDataFolder(), "config.yml");
        java.nio.file.Files.writeString(cfg.toPath(), java.nio.file.Files.readString(cfg.toPath()).replace("\n  enabled: true\n  # May the staff member", "\n  enabled: false\n  # May the staff member"));
        fx.plugin.reloadFor(staff);
        assertTrue(fx.await(() -> fx.plugin.core().mode.report().known() && fx.plugin.core().mode.report().openCases() == 1));
        fx.messages(staff);
        staff.performCommand("ap judicial");
        String out = all(staff);
        assertTrue(out.contains("WIND_DOWN"), out);
        assertTrue(out.contains("Nothing is released, closed or deleted automatically"), out);
        assertTrue(out.contains("1 open case"), out);
        PlayerMock nobody = fx.staff("Nobody", Perms.COMMAND);
        nobody.performCommand("ap judicial");
        assertTrue(all(nobody).toLowerCase().contains("permission"));
    }
}
