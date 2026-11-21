package dev.aura.punishments;

import dev.aura.punishments.config.ConfigException;
import dev.aura.punishments.config.Messages;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.integration.DiscordService;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.service.Actor;
import dev.aura.punishments.service.DomainEvent;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Durations;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Upgrading from 1.0.0: an existing config.yml keeps working, keeps its values, and new options have safe defaults. */
class UpgradeAndConfigTest {
    private static YamlConfiguration res(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(UpgradeAndConfigTest.class.getResourceAsStream("/" + name), StandardCharsets.UTF_8));
    }

    @Test
    void aV100ConfigStillParsesAndItsValuesAreNotReplaced() throws Exception {
        YamlConfiguration old = res("config-1.0.0.yml");
        old.set("custody.review-deadline", "36h"); // an administrator customisation
        old.set("custody.grace-period", "6h");
        old.set("gui.click-cooldown-ms", 400);
        Settings s = Settings.parse(old);
        assertEquals(36 * Durations.HOUR, s.custody.reviewDeadline(), "custody deadline untouched by the upgrade");
        assertEquals(6 * Durations.HOUR, s.custody.gracePeriod());
        assertEquals(400, s.gui.clickCooldownMs());
        // new options fall back to defaults that keep 1.0.0 behaviour unless they are new, additive features
        assertTrue(s.warnings.title().enabled());
        assertEquals(100, s.warnings.title().stay());
        assertEquals(10, s.warnings.title().fadeIn());
        assertEquals(20, s.warnings.title().fadeOut());
        assertTrue(s.review.enabled());
        assertTrue(s.review.requiredTypes().containsAll(List.of(PunishType.MUTE, PunishType.BAN, PunishType.IPBAN)));
        assertFalse(s.review.requiredTypes().contains(PunishType.WARN));
        assertTrue(s.judicial.timeServed().appliesTo().contains(PunishType.BAN), "old applies-to is respected");
        assertTrue(s.judicial.timeServed().defaultCreditFor().containsAll(s.judicial.timeServed().appliesTo()),
                "no default-credit-for key: 1.0.0 behaviour (credit offered on every type) is kept");
        assertFalse(s.discord.enabled());
        assertTrue(s.defendant.enabled());
    }

    @Test
    void theShippedConfigHasTheDocumentedDefaults() throws Exception {
        Settings s = Settings.parse(res("config.yml"));
        assertEquals(48 * Durations.HOUR, s.custody.reviewDeadline());
        assertEquals(12 * Durations.HOUR, s.custody.gracePeriod());
        assertTrue(s.custody.releaseAfterGrace(), "effective automatic release is 48h + 12h = 60h");
        assertEquals(java.util.Set.of(PunishType.JAIL), s.judicial.timeServed().defaultCreditFor());
        assertEquals(24 * Durations.HOUR, s.review.evidenceDeadline());
        assertEquals(72 * Durations.HOUR, s.review.reviewDeadline());
        assertEquals(3, s.warnings.repeat().threshold());
        assertEquals(7, s.discord.channels().size());
    }

    @Test
    void oneWebhookV100DiscordLayoutKeepsWorking() throws Exception {
        YamlConfiguration old = res("config-1.0.0.yml");
        old.set("discord.enabled", true);
        old.set("discord.webhook-url", "https://discord.com/api/webhooks/1/abc");
        old.set("discord.events.verdict.webhook-url", "https://discord.com/api/webhooks/2/def");
        old.set("discord.events.appeal.enabled", false);
        Settings s = Settings.parse(old);
        var ev = (java.util.function.Function<DomainEvent.Type, List<String>>) t -> DiscordService.route(s, DomainEvent.of(t).build());
        assertEquals(List.of("default#0"), ev.apply(DomainEvent.Type.CASE_CREATED));
        assertEquals(List.of("ev:verdict#0"), ev.apply(DomainEvent.Type.VERDICT), "per-event webhook from 1.0.0 still wins");
        assertTrue(ev.apply(DomainEvent.Type.APPEAL).isEmpty(), "per-event disable from 1.0.0 still works");
    }

    @Test
    void newMessagesFallBackToBundledDefaultsWhenTheAdminFileLacksThem() throws Exception {
        Messages m = Messages.parse(res("messages-1.0.0.yml"), res("messages.yml")); // an old messages.yml over the new defaults
        assertTrue(m.has("defendant.detention"));
        assertFalse(m.raw("defendant.detention").contains("missing message"));
        assertFalse(m.raw("staffnote.decision").contains("missing message"));
        // an admin-customised old message is kept
        YamlConfiguration custom = res("messages-1.0.0.yml");
        custom.set("warning.notice", List.of("<red>CUSTOM"));
        assertEquals("<red>CUSTOM", Messages.parse(custom, res("messages.yml")).rawList("warning.notice").get(0));
    }

    @Test
    void badNewValuesAreAllReportedAtOnce() {
        YamlConfiguration y = res("config.yml");
        y.set("warnings.notifications.title.stay-ticks", 0);
        y.set("warnings.notifications.sound.volume", 9.0);
        y.set("warnings.notifications.sound.sound", "Not A Key!");
        y.set("warnings.repeat.threshold", 1);
        y.set("review.required-types", List.of("WARN", "JAIL", "BOGUS"));
        y.set("review.reminder-before", "100d");
        y.set("discord.channels.custody.fallback", "nowhere");
        y.set("discord.channels.appeal.mention-roles", List.of("not-a-role"));
        y.set("exports.folder", "../escape");
        y.set("dashboard.priority.awaiting-trial", 5000);
        y.set("integrations.coreprotect.max-radius", 0);
        try {
            Settings.parse(y);
            fail("expected a ConfigException");
        } catch (ConfigException e) {
            String all = String.join("\n", e.problems());
            for (String must : List.of("stay-ticks", "volume", "sound.sound", "threshold", "required-types", "reminder-before", "custody.fallback",
                    "mention-roles", "exports.folder", "awaiting-trial", "max-radius")) {
                assertTrue(all.contains(must), "problem list should mention " + must + " but was:\n" + all);
            }
        }
    }

    @Test
    void refusedElevatedActionsBecomeSecurityEventsOncePerMinute() throws Exception {
        try (Fx fx = new Fx()) {
            Actor nobody = Fx.staff("Sneaky", 10, Perms.PUNISH_OTHERS);
            fx.core.auth.require(nobody, Perms.REVIEW_REVERSE, "reverse a punishment");
            fx.core.auth.require(nobody, Perms.REVIEW_REVERSE, "reverse a punishment");
            fx.core.auth.require(nobody, Perms.PUNISH, "open the GUI"); // not an elevated node: not reported
            assertEquals(1, fx.events.stream().filter(e -> e.type() == DomainEvent.Type.SECURITY).count());
            assertEquals("Sneaky", fx.events.get(0).actor());
            assertEquals("administrative", DomainEvent.Type.SECURITY.category);
        }
    }
}
