package dev.aura.punishments;

import dev.aura.punishments.config.ConfigException;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.integration.DiscordService;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Review;
import dev.aura.punishments.model.Simple.ReviewStatus;
import dev.aura.punishments.service.*;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for every confirmed finding of the independent 1.1.0 code review. */
class ReviewFixesRegressionTest {
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
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    static <T> Result<T> r(CompletableFuture<Result<T>> f) throws Exception {
        return f.get(10, TimeUnit.SECONDS);
    }

    Review mute(Actor by) throws Exception {
        var p = r(fx.punishments.issueQuick(by, new PunishmentService.Request(steve, PunishType.MUTE, Durations.HOUR, "Spamming the global chat channel.", List.of()))).orThrow();
        return fx.db.txSync(c -> Repo.Reviews.byPunishment(c, p.id()));
    }

    @Test
    void issuerCannotTakeDecideOrReferTheirOwnPunishment() throws Exception {
        Actor selfReviewer = Fx.staff("JrMod", 20, Fx.concat(Fx.JR_MOD, Perms.REVIEW_VIEW, Perms.REVIEW_REVIEW, Perms.REVIEW_REVERSE, Perms.CASE_CREATE));
        Review rv = mute(mod);
        assertEquals(Code.NO_PERMISSION, r(fx.reviews.take(selfReviewer, rv.id(), rv.version())).code());
        assertEquals(Code.NO_PERMISSION, r(fx.reviews.uphold(selfReviewer, rv.id(), rv.version(), "Upholding my own punishment.")).code());
        assertEquals(Code.NO_PERMISSION, r(fx.reviews.reverse(selfReviewer, rv.id(), rv.version(), "Reversing my own punishment.")).code());
        assertEquals(Code.NO_PERMISSION, r(fx.reviews.refer(selfReviewer, rv.id(), rv.version(), "Referring my own punishment.")).code());
        assertEquals(ReviewStatus.PENDING_REVIEW, fx.db.<Review>txSync(c -> Repo.Reviews.get(c, rv.id())).status());
        assertTrue(r(fx.reviews.uphold(owner, rv.id(), rv.version(), "Reviewed by someone else.")).isOk());
    }

    @Test
    void shorteningNeedsOnlyReviewModifyNotIssueTierNodes() throws Exception {
        Review rv = mute(mod);
        Actor modifier = Fx.staff("Modifier", 90, Perms.REVIEW_VIEW, Perms.REVIEW_MODIFY);
        var res = r(fx.reviews.modify(modifier, rv.id(), rv.version(), 20 * 60_000L, null, "Shortened: first offence."));
        assertTrue(res.isOk(), res.message());
        steve = Fx.target("Alex");
        Review rv2 = mute(Fx.staff("Other", 20, Fx.concat(Fx.JR_MOD)));
        assertEquals(Code.LIMIT, r(fx.reviews.modify(modifier, rv2.id(), rv2.version(), 30 * Durations.DAY, null, "Lengthening needs issue authority.")).code());
    }

    @Test
    void referChecksHierarchyAndCannotBeRepeated() throws Exception {
        Review rv = mute(mod);
        Actor referrer = Fx.staff("Referrer", 30, Perms.REVIEW_REVIEW, Perms.CASE_CREATE, Perms.PUNISH_OTHERS);
        fx.auth.put(steve.uuid(), new LevelResolver.TargetAuth(80, false)); // target outranks the referrer
        assertEquals(Code.HIERARCHY, r(fx.reviews.refer(referrer, rv.id(), rv.version(), "Pattern needs a full hearing.")).code());
        fx.auth.put(steve.uuid(), new LevelResolver.TargetAuth(0, false));
        Long id = r(fx.reviews.refer(referrer, rv.id(), rv.version(), "Pattern needs a full hearing.")).orThrow();
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.CASE_CREATED && e.caseId() == id.longValue()));
        Review after = fx.db.txSync(c -> Repo.Reviews.get(c, rv.id()));
        assertEquals(Code.DUPLICATE, r(fx.reviews.refer(referrer, rv.id(), after.version(), "Again, to create more cases.")).code());
        assertEquals(1, fx.db.<Long>txSync(c -> Repo.Cases.countForAccused(c, steve.uuid())), "exactly one case was opened");
    }

    @Test
    void warningsQueuedWhileDeliveryRunsAreNeverMissed() throws Exception {
        Actor warner = Fx.staff("Warner", 5, Perms.PUNISH_OTHERS, Perms.punish(PunishType.WARN));
        fx.online.add(steve.uuid());
        List<CompletableFuture<?>> all = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            all.add(fx.punishments.issueQuick(warner, new PunishmentService.Request(steve, PunishType.WARN, 0, "Warning number " + i + " for spam.", List.of())));
            all.add(fx.core.notifications.deliverPending(steve.uuid()));
        }
        for (var f : all) f.get(15, TimeUnit.SECONDS);
        long end = System.currentTimeMillis() + 5000;
        while ((fx.delivered.size() < 12 || fx.db.<Integer>txSync(c -> Repo.Notifications.pendingFor(c, steve.uuid()).size()) > 0) && System.currentTimeMillis() < end) {
            fx.core.notifications.deliverPending(steve.uuid()).get(5, TimeUnit.SECONDS);
            Thread.sleep(20);
        }
        assertEquals(12, fx.delivered.size());
        assertEquals(12, fx.delivered.stream().map(n -> n.id()).distinct().count(), "no duplicates");
    }

    @Test
    void legacyDiscordLayoutDoesNotStartPostingNewEventsAndExportFolderCannotEscape() throws Exception {
        YamlConfiguration old = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config-1.0.0.yml"), StandardCharsets.UTF_8));
        old.set("discord.enabled", true);
        old.set("discord.webhook-url", "https://discord.com/api/webhooks/1/abc");
        Settings s = Settings.parse(old);
        assertTrue(DiscordService.route(s, DomainEvent.of(DomainEvent.Type.WARNING).build()).isEmpty(), "routine warnings are not posted unless the admin opts in");
        assertTrue(DiscordService.route(s, DomainEvent.of(DomainEvent.Type.SECURITY).build()).isEmpty());
        assertFalse(DiscordService.route(s, DomainEvent.of(DomainEvent.Type.VERDICT).build()).isEmpty(), "1.0.0 events keep posting");
        assertEquals(3, s.discord.maxRetries(), "the retry default did not change");
        old.set("discord.events.warning.enabled", true);
        assertFalse(DiscordService.route(Settings.parse(old), DomainEvent.of(DomainEvent.Type.WARNING).build()).isEmpty(), "explicit opt-in works");
        for (String bad : List.of("..", ".", ".hidden", "a/b")) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
            y.set("exports.folder", bad);
            assertThrows(ConfigException.class, () -> Settings.parse(y), bad);
        }
        YamlConfiguration k = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        k.set("review.required-types", List.of("MUTE", "KICK"));
        assertThrows(ConfigException.class, () -> Settings.parse(k), "kicks never need review");
    }

    @Test
    void ipv6InAnyFormAndCoreProtectSecretsAreScrubbed() throws Exception {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        y.set("discord.enabled", true);
        y.set("discord.webhook-url", "https://discord.com/api/webhooks/1/abc");
        String body = DiscordService.body(Settings.parse(y), DomainEvent.of(DomainEvent.Type.WARNING).summary("from 2001:db8::1 and ::1 and fe80::a:b and 10.1.2.3").build());
        for (String ip : List.of("2001:db8::1", "fe80::a:b", "10.1.2.3")) assertFalse(body.contains(ip), ip);
        assertEquals("2026-01-01 10:00 /login [hidden]", CoreProtectService.redact("2026-01-01 10:00 /login hunter2"));
        assertTrue(CoreProtectService.redact("2026-01-01 10:00 my ip is 203.0.113.9").contains("[ip hidden]"));
        assertEquals("2026-01-01 10:00 placed stone", CoreProtectService.redact("2026-01-01 10:00 placed stone"));
    }

    @Test
    void reasonOnlyChangeAndSameCategoryCounting() throws Exception {
        Review rv = mute(mod);
        fx.now += 2 * Durations.HOUR; // the mute has elapsed but has not been swept yet
        assertTrue(r(fx.reviews.modify(owner, rv.id(), rv.version(), null, "Corrected reason for the record.", "Reason fixed only.")).isOk());
        Fx.Mutator m = new Fx.Mutator();
        m.set.put("warnings.repeat.same-category-only", true);
        try (Fx f2 = new Fx(null, m)) {
            Actor w = Fx.staff("W", 5, Perms.PUNISH_OTHERS, Perms.punish(PunishType.WARN));
            for (int i = 0; i < 3; i++) r(f2.punishments.issueQuick(w, new PunishmentService.Request(steve, PunishType.WARN, 0, "Warning number " + i + " for spam.", List.of())));
            var last = f2.db.<List<java.util.Map<String, String>>>txSync(c -> Repo.Notifications.pendingFor(c, steve.uuid()).stream().map(NotificationService::payload).toList());
            assertEquals("3", last.get(2).get("count"), "quick warnings have no category, so they are all counted");
        }
    }
}
