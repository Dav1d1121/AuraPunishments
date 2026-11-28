package dev.aura.punishments.v13;

import dev.aura.punishments.AuraPunishmentsPlugin;
import dev.aura.punishments.config.ConfigException;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.service.JudicialMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Upgrading to 1.3.0 from 1.0.0 / 1.2.1: nothing changes by itself, every old value is kept, new options start safe. */
class UpgradeV13Test {
    static YamlConfiguration res(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(UpgradeV13Test.class.getResourceAsStream("/" + name), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ settings level
    @Test
    void anOldConfigKeepsTheJudicialSystemOnAndGetsSafeDefaultsForEverythingNew() throws Exception {
        for (String file : List.of("config-1.0.0.yml", "upgrade/config-1.2.1.yml")) {
            Settings s = Settings.parse(res(file));
            assertTrue(s.judicial.enabled(), file + ": upgrade must not switch the judicial system off");
            assertEquals("", s.network.serverName());
            assertFalse(s.appeals.requireTicket());
            assertTrue(s.review.permanentOnlyTypes().isEmpty(), "review rules behave as before");
            assertTrue(s.review.includeEmergency());
            assertTrue(s.discord.staffLog().enabled(), "the staff punishment log keeps posting what it posted");
            assertFalse(s.discord.publicLog().enabled(), "the public log is off unless the admin turns it on");
            assertEquals(java.util.EnumSet.allOf(PunishType.class), s.discord.staffLog().types());
            assertTrue(s.jail.jailName().isEmpty() && !s.jail.muteWhileJailed());
        }
    }

    @Test
    void theOldValuesThatAdministratorsChangedAreKept() throws Exception {
        YamlConfiguration y = res("upgrade/config-1.2.1.yml");
        y.set("custody.review-deadline", "36h");
        y.set("review.required-types", List.of("BAN"));
        y.set("quick-not-a-key", 1);
        Settings s = Settings.parse(y);
        assertEquals(36 * dev.aura.punishments.util.Durations.HOUR, s.custody.reviewDeadline());
        assertEquals(java.util.Set.of(PunishType.BAN), s.review.requiredTypes());
    }

    @Test
    void theNewOptionsAreValidated() throws Exception {
        YamlConfiguration y = res("config.yml");
        y.set("network.server-name", "bad name!");
        y.set("review.permanent-only-types", List.of("JAIL"));
        y.set("review.required-types", List.of("MUTE"));
        y.set("discord.punishment-log.events", List.of("bogus"));
        y.set("discord.punishment-log.fields", List.of("password"));
        y.set("appeals.min-reason-length", 0);
        ConfigException ex = assertThrows(ConfigException.class, () -> Settings.parse(y));
        String all = String.join("\n", ex.problems());
        assertTrue(all.contains("network.server-name"), all);
        assertTrue(all.contains("permanent-only-types") && all.contains("not listed in review.required-types"), all);
        assertTrue(all.contains("punishment-log.events") && all.contains("bogus"), all);
        assertTrue(all.contains("punishment-log.fields") && all.contains("password"), all);
        assertTrue(all.contains("appeals.min-reason-length"), all);
    }

    @Test
    void jailMayNowBeListedForOwnerReviewButWarningsAndKicksStillMayNot() throws Exception {
        YamlConfiguration y = res("config.yml");
        y.set("review.required-types", List.of("JAIL", "WARN", "KICK", "BAN"));
        ConfigException ex = assertThrows(ConfigException.class, () -> Settings.parse(y));
        String all = String.join("\n", ex.problems());
        assertTrue(all.contains("warnings and kicks never enter owner review"), all);
        y.set("review.required-types", List.of("JAIL", "BAN"));
        assertEquals(java.util.Set.of(PunishType.JAIL, PunishType.BAN), Settings.parse(y).review.requiredTypes());
    }

    // ------------------------------------------------------------------ plugin level (a real upgraded folder)
    @Test
    void anUpgradedServerStartsWithEverythingIntactAndItsConfigFilesUntouched() throws Exception {
        ServerMock server = MockBukkit.mock();
        try {
            String version = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8)).getString("version");
            File data = new File(server.getPluginsFolder(), "AuraPunishments-" + version);
            data.mkdirs();
            byte[] cfg = getClass().getResourceAsStream("/upgrade/config-1.2.1.yml").readAllBytes();
            byte[] msg = getClass().getResourceAsStream("/upgrade/messages-1.2.1.yml").readAllBytes();
            byte[] off = getClass().getResourceAsStream("/upgrade/offenses-1.2.1.yml").readAllBytes();
            Files.write(new File(data, "config.yml").toPath(), cfg);
            Files.write(new File(data, "messages.yml").toPath(), msg);
            Files.write(new File(data, "offenses.yml").toPath(), off);
            MigrationV3Test.writeV2(new File(data, "punishments.db").toPath());

            AuraPunishmentsPlugin plugin = MockBukkit.load(AuraPunishmentsPlugin.class);
            assertTrue(plugin.isEnabled());
            assertTrue(plugin.core().mode.enabled(), "no judicial.enabled line: the judicial system stays on");
            assertEquals(JudicialMode.State.ENABLED, plugin.core().mode.state());
            // administrators' files are never rewritten
            assertArrayEquals(cfg, Files.readAllBytes(new File(data, "config.yml").toPath()), "config.yml is byte-for-byte untouched");
            assertArrayEquals(msg, Files.readAllBytes(new File(data, "messages.yml").toPath()), "messages.yml is untouched");
            assertArrayEquals(off, Files.readAllBytes(new File(data, "offenses.yml").toPath()), "offenses.yml is untouched");
            // a reference copy with every new option is written next to it, once
            File ref = new File(data, "config-" + version + "-reference.yml");
            assertTrue(ref.isFile(), "the new options are documented in a reference file");
            assertTrue(Files.readString(ref.toPath()).contains("MASTER SWITCH"));
            // data migrated and loaded
            assertEquals(3, plugin.core().db.schemaVersion());
            assertTrue(plugin.punishments().banFor(UUID.fromString(MigrationV3Test.ALEX), null).isPresent(), "bans from before the upgrade are enforced");
            assertTrue(plugin.punishments().cache().size() >= 4, "the active bans and mutes were loaded into the enforcement cache: " + plugin.punishments().cache().size());
            // new message keys fall back to the built-in text even though the old messages.yml lacks them
            assertTrue(plugin.core().messages.get("command.jail-guard", "ref", "AP-J0001").toString().contains("AP-J0001"));
            MockBukkit.unmock();
            server = null;
        } finally {
            if (server != null) MockBukkit.unmock();
        }
    }

    @Test
    void aVersion130ServerIsToldAboutTheCaseWorkflowAndItsConfigIsNotTouched() throws Exception {
        ServerMock server = MockBukkit.mock();
        try {
            String version = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8)).getString("version");
            File data = new File(server.getPluginsFolder(), "AuraPunishments-" + version);
            data.mkdirs();
            String shipped = new String(getClass().getResourceAsStream("/config.yml").readAllBytes(), StandardCharsets.UTF_8);
            int from = shipped.indexOf("\n# CASE WORKFLOW");
            int endMark = shipped.indexOf("  require-case-for: []\n", from);
            assertTrue(from > 0 && endMark > from, "the shipped file contains the casework section");
            String as130 = shipped.substring(0, from) + shipped.substring(endMark + "  require-case-for: []\n".length());
            assertFalse(as130.contains("casework"), "the fixture looks like a 1.3.0 config");
            byte[] cfg = as130.getBytes(StandardCharsets.UTF_8);
            Files.write(new File(data, "config.yml").toPath(), cfg);

            AuraPunishmentsPlugin plugin = MockBukkit.load(AuraPunishmentsPlugin.class);
            assertTrue(plugin.isEnabled());
            assertFalse(plugin.core().mode.casework(), "a missing casework section means off: upgrading changes nothing by itself");
            assertArrayEquals(cfg, Files.readAllBytes(new File(data, "config.yml").toPath()), "config.yml is byte-for-byte untouched");
            File ref = new File(data, "config-" + version + "-reference.yml");
            assertTrue(ref.isFile(), "the reference file shows the new section");
            assertTrue(Files.readString(ref.toPath()).contains("casework:"));
            MockBukkit.unmock();
            server = null;
        } finally {
            if (server != null) MockBukkit.unmock();
        }
    }
}
