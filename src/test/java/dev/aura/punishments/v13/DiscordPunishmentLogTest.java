package dev.aura.punishments.v13;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aura.punishments.config.ConfigException;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.integration.DiscordService;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.service.*;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Durations;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static dev.aura.punishments.v13.V13Support.fx;
import static dev.aura.punishments.v13.V13Support.mod;
import static dev.aura.punishments.v13.V13Support.r;
import static org.junit.jupiter.api.Assertions.*;

/** Configurable Discord punishment notifications: private staff log filters, and the optional sanitized public log. */
class DiscordPunishmentLogTest {
    static final String STAFF_URL = "https://discord.com/api/webhooks/1/staff";
    static final String PUBLIC_URL = "https://discord.com/api/webhooks/2/public";
    Fx fx;
    Punishment ban;
    Punishment mute;

    @BeforeEach
    void setUp() throws Exception {
        fx = fx(false, m -> m.serverName = "smp");
        Actor staff = mod("Mod");
        var steve = Fx.target("Steve");
        fx.db.txSync(c -> { dev.aura.punishments.db.Repo.Profiles.upsert(c, steve.uuid(), "Steve", "10.9.8.7", 0, fx.now); return null; });
        ban = r(fx.punishments.issueQuick(staff, new PunishmentService.Request(steve, PunishType.BAN, 7 * Durations.DAY, "Repeated griefing of builds near spawn.",
                List.of(Fx.ev("https://example.com/proof.png")), "Private staff note about Steve"))).orThrow();
        mute = r(fx.punishments.issueQuick(staff, new PunishmentService.Request(Fx.target("Alex"), PunishType.MUTE, Durations.HOUR, "Spamming the global channel.", List.of()))).orThrow();
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    static Settings settings(Consumer<YamlConfiguration> tweak) throws Exception {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(DiscordPunishmentLogTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        y.set("discord.enabled", true);
        y.set("discord.webhook-url", STAFF_URL);
        tweak.accept(y);
        return Settings.parse(y);
    }

    DomainEvent issued(Punishment p) {
        return fx.events.stream().filter(e -> e.info() != null && e.info().id() == p.id() && e.info().event().equals("issued")).findFirst().orElseThrow();
    }

    static JsonObject json(String body) {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    static String fieldNames(String body) {
        StringBuilder sb = new StringBuilder();
        JsonArray f = json(body).getAsJsonArray("embeds").get(0).getAsJsonObject().getAsJsonArray("fields");
        f.forEach(x -> sb.append(x.getAsJsonObject().get("name").getAsString()).append('|'));
        return sb.toString();
    }

    // ------------------------------------------------------------------ private staff log
    @Test
    void theDefaultKeepsPostingEveryPunishmentToTheStaffLog() throws Exception {
        Settings s = settings(y -> { });
        assertFalse(DiscordService.route(s, issued(ban)).isEmpty());
        assertFalse(DiscordService.route(s, issued(mute)).isEmpty());
        assertTrue(DiscordService.publicRoute(s, issued(ban)).isEmpty(), "the public log is off by default");
    }

    @Test
    void theStaffLogCanBeFilteredByPunishmentType() throws Exception {
        Settings s = settings(y -> y.set("discord.punishment-log.types", List.of("BAN", "IPBAN")));
        assertFalse(DiscordService.route(s, issued(ban)).isEmpty());
        assertTrue(DiscordService.route(s, issued(mute)).isEmpty(), "mutes are not posted when only bans are listed");
    }

    @Test
    void theStaffLogCanBeFilteredByLifecycleEvent() throws Exception {
        Settings s = settings(y -> y.set("discord.punishment-log.events", List.of("revoked")));
        assertTrue(DiscordService.route(s, issued(ban)).isEmpty());
        r(fx.punishments.revoke(mod("Mod"), ban.id(), "Revoking to see the revoke notice."));
        DomainEvent revoked = fx.events.stream().filter(e -> e.info() != null && e.info().event().equals("revoked")).findFirst().orElseThrow();
        assertFalse(DiscordService.route(s, revoked).isEmpty());
    }

    @Test
    void theStaffLogCanBeSwitchedOffAsAWholeWithoutTouchingJudicialNotices() throws Exception {
        Settings s = settings(y -> y.set("discord.punishment-log.enabled", false));
        assertTrue(DiscordService.route(s, issued(ban)).isEmpty());
        DomainEvent caseEvent = DomainEvent.of(DomainEvent.Type.CASE_CREATED).caseId(3L).player("Steve").summary("New case").build();
        assertFalse(DiscordService.route(s, caseEvent).isEmpty(), "judicial notices follow their own channel switch");
        Settings noJudicial = settings(y -> y.set("discord.punishment-log.events", List.of("issued", "revoked")));
        assertTrue(DiscordService.route(noJudicial, caseEvent).isEmpty(), "'judicial' left out of events silences case/verdict/custody notices");
    }

    @Test
    void thePunishmentLogCanHaveItsOwnWebhooks() throws Exception {
        Settings s = settings(y -> y.set("discord.punishment-log.webhooks", List.of(STAFF_URL, "https://discord.com/api/webhooks/3/second")));
        assertEquals(List.of("plog#0", "plog#1"), DiscordService.route(s, issued(ban)));
    }

    @Test
    void theStaffFieldsAreChosenInConfig() throws Exception {
        Settings all = settings(y -> y.set("discord.punishment-log.fields", List.of("player", "type", "reason", "duration", "expires", "issuer", "punishment-id", "server", "evidence", "source", "network-status")));
        String full = fieldNames(DiscordService.body(all, issued(ban)));
        for (String f : List.of("Player", "Type", "Reason", "Duration", "Expires", "By", "Punishment", "Server", "Evidence", "Source", "Network")) {
            assertTrue(full.contains(f + "|"), "expected field " + f + " in " + full);
        }
        Settings few = settings(y -> y.set("discord.punishment-log.fields", List.of("player", "type")));
        String minimal = fieldNames(DiscordService.body(few, issued(ban)));
        assertTrue(minimal.contains("Player|") && minimal.contains("Type|"));
        assertFalse(minimal.contains("By|") || minimal.contains("Server|") || minimal.contains("Evidence|"), minimal);
        assertFalse(DiscordService.body(few, issued(ban)).contains("Repeated griefing"), "the reason is left out when it is not listed");
    }

    @Test
    void theOriginatingServerAndExpiryAreReported() throws Exception {
        Settings s = settings(y -> { });
        String body = DiscordService.body(s, issued(ban));
        assertTrue(body.contains("smp"), "the server that issued it");
        assertTrue(body.contains("<t:" + (ban.expiresAt() / 1000) + ":f>"), "the expiry as a Discord timestamp");
    }

    @Test
    void ipAddressesNeverAppearAnywhere() throws Exception {
        Settings s = settings(y -> {
            y.set("discord.public-log.enabled", true);
            y.set("discord.public-log.webhooks", List.of(PUBLIC_URL));
        });
        assertFalse(DiscordService.body(s, issued(ban)).contains("10.9.8.7"));
        assertFalse(DiscordService.publicBody(s, issued(ban)).contains("10.9.8.7"));
    }

    @Test
    void anUpgradedInstallDoesNotSuddenlyStartPostingNewKindsOfNotice() throws Exception {
        Settings legacy = settings(y -> y.set("discord.channels", null)); // an old config without the channels section
        fx.now += 8 * Durations.DAY;
        r(fx.punishments.expireDue());
        DomainEvent expired = fx.events.stream().filter(e -> e.type() == DomainEvent.Type.PUNISHMENT_EXPIRED).findFirst().orElseThrow();
        assertTrue(DiscordService.route(legacy, expired).isEmpty(), "expiry notices are new in 1.3.0 and stay off until the admin asks for them");
    }

    // ------------------------------------------------------------------ public log
    @Test
    void thePublicLogUsesOnlyItsOwnWebhooksAndOnlyTheListedTypesAndEvents() throws Exception {
        Settings s = settings(y -> {
            y.set("discord.public-log.enabled", true);
            y.set("discord.public-log.webhooks", List.of(PUBLIC_URL));
            y.set("discord.public-log.types", List.of("BAN"));
            y.set("discord.public-log.events", List.of("issued"));
        });
        assertEquals(List.of("pub#0"), DiscordService.publicRoute(s, issued(ban)));
        assertTrue(DiscordService.publicRoute(s, issued(mute)).isEmpty(), "mutes are not announced publicly unless listed");
        assertFalse(DiscordService.route(s, issued(ban)).contains("pub#0"), "the staff routing never includes the public destination");
        assertTrue(DiscordService.route(s, issued(ban)).stream().noneMatch(d -> d.contains(PUBLIC_URL)));
    }

    @Test
    void thePublicNoticeIsSanitisedByConstruction() throws Exception {
        Settings s = settings(y -> {
            y.set("discord.public-log.enabled", true);
            y.set("discord.public-log.webhooks", List.of(PUBLIC_URL));
            y.set("discord.public-log.fields", List.of("player", "type", "duration", "expires", "server"));
        });
        String body = DiscordService.publicBody(s, issued(ban));
        for (String forbidden : List.of("Mod", "Repeated griefing", "proof.png", "Private staff note", "10.9.8.7", "#" + ban.id(), "AP-", "evidence", "Evidence", "By")) {
            assertFalse(body.contains(forbidden), "the public notice must not contain '" + forbidden + "': " + body);
        }
        assertTrue(body.contains("Steve") && body.contains("smp"));
        assertEquals(0, json(body).getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size(), "no pings");
    }

    @Test
    void theReasonIsShownPubliclyOnlyWhenExplicitlyEnabled() throws Exception {
        Settings off = settings(y -> {
            y.set("discord.public-log.enabled", true);
            y.set("discord.public-log.webhooks", List.of(PUBLIC_URL));
        });
        assertFalse(DiscordService.publicBody(off, issued(ban)).contains("Repeated griefing"));
        Settings on = settings(y -> {
            y.set("discord.public-log.enabled", true);
            y.set("discord.public-log.webhooks", List.of(PUBLIC_URL));
            y.set("discord.public-log.show-reason", true);
        });
        assertTrue(DiscordService.publicBody(on, issued(ban)).contains("Repeated griefing"));
    }

    @Test
    void privateFieldsCannotBeConfiguredIntoThePublicLog() throws Exception {
        ConfigException ex = assertThrows(ConfigException.class, () -> settings(y -> {
            y.set("discord.public-log.enabled", true);
            y.set("discord.public-log.webhooks", List.of(PUBLIC_URL));
            y.set("discord.public-log.fields", List.of("player", "issuer", "evidence"));
        }));
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("issuer") && p.contains("public log")), ex.problems().toString());
    }

    @Test
    void theEnabledPublicLogNeedsItsOwnWebhookNeverTheStaffOne() throws Exception {
        ConfigException ex = assertThrows(ConfigException.class, () -> settings(y -> y.set("discord.public-log.enabled", true)));
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("public-log") && p.contains("never uses the private staff webhooks")), ex.problems().toString());
    }

    // ------------------------------------------------------------------ no duplicates
    @Test
    void theSameFactIsQueuedOncePerDestinationWhateverHowOftenItIsEmitted() throws Exception {
        Settings s = settings(y -> {
            y.set("discord.public-log.enabled", true);
            y.set("discord.public-log.webhooks", List.of(PUBLIC_URL));
        });
        DiscordService discord = new DiscordService(() -> s, Logger.getLogger("test")) {
            @Override
            protected String target(String configured) {
                return "http://127.0.0.1:1/unreachable"; // nothing is delivered in this test; only the outbox is inspected
            }
        };
        try {
            DomainEvent e = issued(ban);
            discord.send(e);
            discord.send(e);
            discord.send(e);
            long pending = discord.store().count(dev.aura.punishments.model.Simple.OutboxState.PENDING)
                    + discord.store().count(dev.aura.punishments.model.Simple.OutboxState.DEAD);
            assertEquals(2, pending, "one staff message and one public message, never more");
        } finally {
            discord.shutdown();
        }
    }
}
