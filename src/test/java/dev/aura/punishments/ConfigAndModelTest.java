package dev.aura.punishments;

import dev.aura.punishments.config.ConfigException;
import dev.aura.punishments.config.Messages;
import dev.aura.punishments.config.OffenseCatalog;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Text;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConfigAndModelTest {
    private static YamlConfiguration res(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(ConfigAndModelTest.class.getResourceAsStream("/" + name), StandardCharsets.UTF_8));
    }

    private static List<String> problems(YamlConfiguration y) {
        try {
            Settings.parse(y);
            return List.of();
        } catch (ConfigException e) {
            return e.problems();
        }
    }

    // ------------------------------------------------------------ defaults must be valid
    @Test
    void shippedDefaultsAreValid() throws Exception {
        Settings s = Settings.parse(res("config.yml"));
        assertEquals(48 * Durations.HOUR, s.custody.reviewDeadline(), "default pretrial review deadline is 48h");
        assertTrue(s.custody.releaseAfterGrace());
        assertTrue(s.judicial.timeServed().enabled());
        OffenseCatalog oc = OffenseCatalog.parse(res("offenses.yml"));
        for (String k : List.of("chat_misconduct", "harassment", "advertising", "griefing", "theft", "towny_violation", "cheating", "exploiting",
                "staff_obstruction", "severe_malicious", "other")) {
            assertTrue(oc.get(k).isPresent(), "required category " + k);
        }
        assertTrue(oc.get("cheating").get().directEligible());
        assertFalse(oc.get("chat_misconduct").get().directEligible());
        Messages.parse(res("messages.yml"), res("messages.yml"));
    }

    @Test
    void settingsRejectBadValuesWithEveryProblemListed() {
        YamlConfiguration y = res("config.yml");
        y.set("custody.review-deadline", "banana");
        y.set("gui.click-cooldown-ms", -5);
        y.set("direct.minimum-severity", "TRIVIAL");
        y.set("discord.webhook-url", "http://example.com/not-discord");
        y.set("database.type", "mysql");
        y.set("database.file", "../../etc/passwd");
        List<String> p = problems(y);
        assertTrue(p.size() >= 6, p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("custody.review-deadline")));
        assertTrue(p.stream().anyMatch(x -> x.contains("gui.click-cooldown-ms")));
        assertTrue(p.stream().anyMatch(x -> x.contains("direct.minimum-severity")));
        assertTrue(p.stream().anyMatch(x -> x.contains("discord.webhook-url")));
        assertTrue(p.stream().anyMatch(x -> x.contains("database.type")));
        assertTrue(p.stream().anyMatch(x -> x.contains("database.file")), "path traversal in the file name is rejected");
    }

    @Test
    void emergencyActionsCannotBeConfiguredIntoJailOrIpBans() {
        YamlConfiguration y = res("config.yml");
        y.set("emergency.allowed-types", List.of("MUTE", "BAN", "JAIL", "IPBAN"));
        assertTrue(problems(y).stream().anyMatch(x -> x.contains("emergency.allowed-types")));
    }

    @Test
    void alertsMustFitInsideTheReviewDeadline() {
        YamlConfiguration y = res("config.yml");
        y.set("custody.alert-before", List.of("72h"));
        assertTrue(problems(y).stream().anyMatch(x -> x.contains("custody.alert-before")));
    }

    @Test
    void webhookMustBeAnHttpsDiscordUrl() {
        assertTrue(Settings.validWebhook("https://discord.com/api/webhooks/123/abc"));
        assertTrue(Settings.validWebhook("https://discordapp.com/api/webhooks/123/abc"));
        assertFalse(Settings.validWebhook("http://discord.com/api/webhooks/123/abc"));
        assertFalse(Settings.validWebhook("https://evil.example/api/webhooks/123/abc"));
        assertFalse(Settings.validWebhook("https://discord.com.evil.example/api/webhooks/1/a"));
        assertFalse(Settings.validWebhook("https://discord.com/other/path"));
        assertFalse(Settings.validWebhook(""));
    }

    @Test
    void offenseCatalogRejectsBrokenEntries() {
        YamlConfiguration y = res("offenses.yml");
        y.set("offenses.griefing.severity", "EXTREME");
        y.set("offenses.theft.allowed-punishments", List.of("MUTE", "EXPLODE"));
        y.set("offenses.advertising.max-duration", "forever-ish");
        y.set("offenses.harassment.recommendations", List.of(java.util.Map.of("priors", 0, "type", "BAN")));
        ConfigException e = assertThrows(ConfigException.class, () -> OffenseCatalog.parse(y));
        assertTrue(e.problems().size() >= 4, e.problems().toString());
        YamlConfiguration noOther = res("offenses.yml");
        noOther.set("offenses.other", null);
        assertThrows(ConfigException.class, () -> OffenseCatalog.parse(noOther));
    }

    @Test
    void wrongMessageTypesAreRejectedAndMissingKeysFallBackToDefaults() throws Exception {
        YamlConfiguration user = new YamlConfiguration();
        user.set("screen.ban", 42);
        user.set("mute.notice", List.of("fine", 7));
        ConfigException e = assertThrows(ConfigException.class, () -> Messages.parse(user, res("messages.yml")));
        assertTrue(e.problems().size() >= 2, e.problems().toString());
        Messages m = Messages.parse(new YamlConfiguration(), res("messages.yml"));
        assertFalse(m.raw("screen.ban").contains("missing message"));
        assertTrue(m.raw("no.such.key").contains("missing message"));
    }

    @Test
    void typedTextCannotInjectFormattingIntoMessages() throws Exception {
        Messages m = Messages.parse(new YamlConfiguration(), res("messages.yml"));
        var comp = m.get("mute.blocked", "expires", "<click:run_command:/op me>click</click><red>x");
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(comp);
        assertTrue(plain.contains("<click:run_command:/op me>"), "tags typed by users are shown literally, never interpreted");
        assertTrue(comp.children().stream().noneMatch(c -> c.clickEvent() != null) && comp.clickEvent() == null);
    }

    // ------------------------------------------------------------ durations & text
    @Test
    void durationsParseAndFormat() {
        assertEquals(30 * Durations.MINUTE, Durations.parse("30m"));
        assertEquals(36 * Durations.HOUR, Durations.parse("1d12h"));
        assertEquals(Durations.WEEK, Durations.parse("1w"));
        assertEquals(Durations.PERMANENT, Durations.parse("permanent"));
        assertEquals(Durations.PERMANENT, Durations.parse("PERM"));
        for (String bad : new String[]{"", "abc", "10", "-5m", "0m", "5x", "1d banana", "99999999999999999999d"}) {
            assertEquals(-2L, Durations.parse(bad), "'" + bad + "' is invalid");
        }
        assertEquals("1d12h", Durations.format(36 * Durations.HOUR));
        assertEquals("permanent", Durations.format(Durations.PERMANENT));
    }

    @Test
    void textCleaningStripsControlCharactersAndLinksAreRestricted() {
        assertEquals("a b c", Text.clean("  a\u0000 b\n\tc  "));
        assertFalse(Text.clean("x§cred").contains("§"));
        assertTrue(Text.isHttpUrl("https://example.com/a?b=c"));
        assertFalse(Text.isHttpUrl("javascript:alert(1)"));
        assertFalse(Text.isHttpUrl("file:///etc/passwd"));
        assertFalse(Text.isHttpUrl("https://"));
        assertFalse(Text.isHttpUrl("ftp://example.com"));
    }

    // ------------------------------------------------------------ state machine invariants
    @Test
    void caseStateMachineInvariants() {
        for (CaseStatus s : CaseStatus.values()) {
            assertFalse(s.canMoveTo(s), s + " has no self-transition");
            assertFalse(s.next().isEmpty(), s + " always has a way out (even CLOSED can be reopened for review)");
        }
        // acquittals and guilty findings are distinct and never convert into each other directly
        assertFalse(CaseStatus.NOT_GUILTY.canMoveTo(CaseStatus.GUILTY));
        assertFalse(CaseStatus.GUILTY.canMoveTo(CaseStatus.NOT_GUILTY));
        assertFalse(CaseStatus.OPEN.canMoveTo(CaseStatus.SENTENCED), "no sentencing without a verdict");
        assertFalse(CaseStatus.OPEN.canMoveTo(CaseStatus.GUILTY));
        assertFalse(CaseStatus.AWAITING_TRIAL.canMoveTo(CaseStatus.SENTENCED));
        assertFalse(CaseStatus.DISMISSED.canMoveTo(CaseStatus.SENTENCED));
        assertFalse(CaseStatus.NOT_GUILTY.canMoveTo(CaseStatus.SENTENCED));
        // every status can eventually reach CLOSED
        for (CaseStatus s : CaseStatus.values()) {
            Set<CaseStatus> seen = EnumSet.of(s);
            java.util.ArrayDeque<CaseStatus> q = new java.util.ArrayDeque<>(List.of(s));
            while (!q.isEmpty()) {
                for (CaseStatus n : q.poll().next()) if (seen.add(n)) q.add(n);
            }
            assertTrue(seen.contains(CaseStatus.CLOSED), s + " can reach CLOSED");
        }
        // custody can only run while a case is unresolved
        assertTrue(CaseStatus.IN_TRIAL.custodyAllowed());
        assertFalse(CaseStatus.NOT_GUILTY.custodyAllowed());
        assertFalse(CaseStatus.CLOSED.custodyAllowed());
        assertFalse(CaseStatus.SENTENCED.custodyAllowed());
    }

    @Test
    void punishTypePermissionTokens() {
        assertEquals("ipban", PunishType.IPBAN.node());
        assertEquals(PunishType.IPBAN, PunishType.parse("ip-ban"));
        assertTrue(PunishType.WARN.instant() && PunishType.KICK.instant());
        assertFalse(PunishType.MUTE.instant());
    }
}
