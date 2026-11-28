package dev.aura.punishments.v14;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.service.Actor;
import dev.aura.punishments.service.ActiveSnapshot;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.service.PunishmentService;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The safeguards of 1.4.0 that do not need a second server: IP bans, the local snapshot, and what happens when the database is gone. */
class EnforcementTest {
    Fx fx;

    @AfterEach
    void tearDown() {
        if (fx != null) fx.close();
    }

    static Actor mod() {
        return NetworkTest.staff("Mod");
    }

    static <T> Result<T> r(CompletableFuture<Result<T>> f) throws Exception {
        return f.get(20, TimeUnit.SECONDS);
    }

    Fx fx(String ipBanSettings) throws Exception {
        Fx.Mutator m = new Fx.Mutator();
        m.set.put("punishments.ip-bans.refuse-private-addresses", true);
        m.set.put("punishments.ip-bans.max-accounts-per-address", 3);
        if (ipBanSettings != null) m.set.put("punishments.ip-bans.exclude-addresses", List.of(ipBanSettings));
        return fx = new Fx(null, m);
    }

    void seen(Target t, String ip) throws Exception {
        fx.db.txSync(c -> {
            Repo.Profiles.upsert(c, t.uuid(), t.name(), ip, 0, fx.now);
            return null;
        });
    }

    Result<Punishment> ipBan(Target t) throws Exception {
        return r(fx.punishments.issueQuick(mod(), new PunishmentService.Request(t, PunishType.IPBAN, Durations.DAY, "Ban evasion with alternate accounts.", List.of(Fx.ev()))));
    }

    // ------------------------------------------------------------------ IP bans behind a proxy
    @Test
    void anAddressThatIsNotAPublicPlayerAddressIsNeverIpBanned() throws Exception {
        fx(null);
        for (String proxy : List.of("127.0.0.1", "10.0.0.5", "192.168.1.20", "172.16.4.4", "169.254.1.1", "100.64.3.3", "::1", "fd00::1")) {
            Target t = Fx.target("Player-" + proxy.replace(':', 'x'));
            seen(t, proxy);
            var res = ipBan(t);
            assertEquals(Code.INVALID, res.code(), proxy + " must be refused");
            assertTrue(res.message().contains("not a public player address") && res.message().contains("IP forwarding"), res.message());
        }
        Target ok = Fx.target("Public");
        seen(ok, "203.0.113.9");
        assertTrue(ipBan(ok).isOk(), "a public address is fine");
    }

    @Test
    void anExcludedAddressIsNeverIpBanned() throws Exception {
        fx("203.0.113.77");
        Target t = Fx.target("Behind Proxy");
        seen(t, "203.0.113.77");
        assertEquals(Code.INVALID, ipBan(t).code());
    }

    @Test
    void anAddressSharedByManyAccountsIsRefused() throws Exception {
        fx(null);
        for (int i = 0; i < 5; i++) seen(Fx.target("Sharer" + i), "198.51.100.20");
        var res = ipBan(Fx.target("Sharer1"));
        assertEquals(Code.LIMIT, res.code());
        assertTrue(res.message().contains("5 different accounts") && res.message().contains("shared or proxy address"), res.message());
        seen(Fx.target("Lonely"), "198.51.100.21");
        assertTrue(ipBan(Fx.target("Lonely")).isOk());
    }

    @Test
    void anExistingIpBanOnAProtectedAddressIsNotEnforcedAgainstEveryone() throws Exception {
        // an IP ban recorded while the guard was off (or by an older version) must not lock out a whole proxy once the guard is on
        Fx.Mutator off = new Fx.Mutator();
        off.set.put("punishments.ip-bans.refuse-private-addresses", false);
        fx = new Fx(null, off);
        Target banned = Fx.target("Banned");
        seen(banned, "10.0.0.5");
        assertTrue(ipBan(banned).isOk());
        Target bystander = Fx.target("Bystander");
        assertTrue(fx.punishments.banFor(bystander.uuid(), "10.0.0.5").isPresent(), "guard off: the old behaviour");
        fx.core.settings = dev.aura.punishments.config.Settings.parse(rebuild(true));
        assertTrue(fx.punishments.banFor(bystander.uuid(), "10.0.0.5").isEmpty(), "guard on: an address that is not a player's own is ignored");
        assertTrue(fx.punishments.banFor(banned.uuid(), "10.0.0.5").isPresent(), "the banned ACCOUNT itself stays banned");
    }

    private org.bukkit.configuration.file.YamlConfiguration rebuild(boolean refusePrivate) {
        var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(getClass().getResourceAsStream("/config.yml"), java.nio.charset.StandardCharsets.UTF_8));
        y.set("punishments.ip-bans.refuse-private-addresses", refusePrivate);
        y.set("judicial.enabled", true);
        return y;
    }

    @Test
    void aMissingIpBanSectionKeepsTheOldBehaviourSoUpgradingChangesNothing() throws Exception {
        var y = rebuild(true);
        y.set("punishments.ip-bans", null);
        var s = dev.aura.punishments.config.Settings.parse(y);
        assertFalse(s.ipBans.protectedAddress("10.0.0.5"));
        assertEquals(0, s.ipBans.maxAccounts());
    }

    // ------------------------------------------------------------------ the local snapshot
    @Test
    void theSnapshotRoundTripsEveryActiveBanAndMute(@TempDir File dir) throws Exception {
        fx = new Fx();
        Target a = Fx.target("Alex"), b = Fx.target("Blake");
        seen(a, "203.0.113.1");
        r(fx.punishments.issueQuick(mod(), new PunishmentService.Request(a, PunishType.BAN, Durations.DAY, "Repeated griefing of builds.", List.of(Fx.ev())))).orThrow();
        r(fx.punishments.issueQuick(mod(), new PunishmentService.Request(b, PunishType.MUTE, Durations.HOUR, "Spamming the global channel.", List.of()))).orThrow();
        File f = ActiveSnapshot.file(dir);
        ActiveSnapshot.save(f, fx.punishments.cache().activeAll());
        List<Punishment> back = ActiveSnapshot.load(f);
        assertEquals(2, back.size());
        Punishment ban = back.stream().filter(p -> p.type() == PunishType.BAN).findFirst().orElseThrow();
        assertEquals(a.uuid(), ban.targetUuid());
        assertEquals("Repeated griefing of builds.", ban.reason());
        assertEquals(fx.now + Durations.DAY, ban.expiresAt());
        dev.aura.punishments.service.PunishmentCache cache = new dev.aura.punishments.service.PunishmentCache();
        cache.load(back);
        assertTrue(cache.ban(a.uuid(), fx.now).isPresent(), "a server that restarts during an outage still knows this ban");
        assertTrue(cache.mute(b.uuid(), fx.now).isPresent());
        assertTrue(cache.ban(a.uuid(), fx.now + 2 * Durations.DAY).isEmpty(), "and still lets it expire on time");
    }

    @Test
    void anUnreadableSnapshotRowDoesNotDiscardTheOthers(@TempDir File dir) throws Exception {
        File f = ActiveSnapshot.file(dir);
        java.nio.file.Files.writeString(f.toPath(), """
                punishments:
                - id: 1
                  type: BAN
                  status: ACTIVE
                  target-uuid: 6b3d9b7e-0000-4000-8000-000000000001
                  target-name: Good
                  reason: Valid row here
                  issuer-name: Mod
                  issued-at: 1000
                - id: 2
                  type: NOT_A_TYPE
                  status: ACTIVE
                """);
        assertEquals(1, ActiveSnapshot.load(f).size());
        assertEquals(0, ActiveSnapshot.load(new File(dir, "missing.yml")).size());
    }

    @Test
    void aLoginNeverWaitsLongerThanItsBudgetForTheDatabase() throws Exception {
        fx = new Fx();
        long t0 = System.currentTimeMillis();
        var e = assertThrows(dev.aura.punishments.db.Database.UnavailableException.class, () -> fx.db.txSync(c -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return 1;
        }, 80));
        assertTrue(System.currentTimeMillis() - t0 < 500, "gave up at the budget, not when the work finished");
        assertTrue(e.getMessage().contains("did not answer"), e.getMessage());
        assertTrue(fx.db.reachable(), "a standalone database is always reachable");
    }

    // ------------------------------------------------------------------ the database is gone
    @Test
    void whenTheDatabaseIsGoneKnownBansAreStillEnforcedAndStaffActionsAreRefusedHonestly() throws Exception {
        fx = new Fx();
        Target banned = Fx.target("Banned");
        r(fx.punishments.issueQuick(mod(), new PunishmentService.Request(banned, PunishType.BAN, Durations.DAY, "Repeated griefing of builds.", List.of(Fx.ev())))).orThrow();
        fx.db.close(); // the database is no longer reachable
        assertTrue(fx.punishments.banFor(banned.uuid(), null).isPresent(), "the index still knows the ban");
        assertTrue(fx.punishments.banForLogin(banned.uuid(), null).isPresent());
        var refused = r(fx.punishments.issueQuick(mod(), new PunishmentService.Request(Fx.target("Other"), PunishType.MUTE, Durations.HOUR, "Spamming the global channel.", List.of())));
        assertEquals(Code.DB_ERROR, refused.code(), "never reported as done");
        assertTrue(refused.message().contains("Nothing was changed"), refused.message());
    }
}
