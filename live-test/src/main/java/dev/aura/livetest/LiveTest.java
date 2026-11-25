package dev.aura.livetest;

import com.graywolf336.jail.JailMain;
import com.graywolf336.jail.JailsAPI;
import com.graywolf336.jail.beans.Jail;
import com.graywolf336.jail.beans.Prisoner;
import dev.aura.punishments.AuraPunishmentsPlugin;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Records.Custody;
import dev.aura.punishments.model.Simple.*;
import dev.aura.punishments.service.*;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Live integration driver. Runs inside a real Paper 26.3 server next to the real JailReloaded 1.2.2 (and LuckPerms
 * when present) and exercises AuraPunishments' real services end to end. Results go to plugins/APLiveTest/results.txt.
 * Real players cannot connect to a headless test server, so players here are OFFLINE players with verified profiles;
 * the online-player paths are covered by the MockBukkit suite.
 */
public final class LiveTest extends JavaPlugin {
    final List<String> results = new ArrayList<>();
    int pass;
    int fail;
    AuraPunishmentsPlugin ap;

    void check(String name, boolean cond, String detail) {
        if (cond) pass++;
        else fail++;
        results.add((cond ? "PASS " : "FAIL ") + name + (detail == null || detail.isBlank() ? "" : "  -- " + detail));
    }

    <T> T onMain(Callable<T> c) throws Exception {
        return Bukkit.getScheduler().callSyncMethod(this, c).get(30, TimeUnit.SECONDS);
    }

    static <T> Result<T> r(CompletableFuture<Result<T>> f) throws Exception {
        return f.get(30, TimeUnit.SECONDS);
    }

    static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("live-" + name).getBytes(StandardCharsets.UTF_8));
    }

    static Actor god(String name) {
        return Actor.player(id(name), name, new Actor.PermView() {
            @Override public boolean has(String node) { return true; }
            @Override public int level() { return 1000; }
        });
    }

    static Actor limited(String name, int level, String... nodes) {
        var set = java.util.Set.of(nodes);
        return Actor.player(id(name), name, new Actor.PermView() {
            @Override public boolean has(String node) { return set.contains(node); }
            @Override public int level() { return level; }
        });
    }

    @Override
    public void onEnable() {
        ap = (AuraPunishmentsPlugin) getServer().getPluginManager().getPlugin("AuraPunishments");
        getDataFolder().mkdirs();
        String phase;
        try {
            File pf = new File(getDataFolder(), "phase.txt");
            phase = pf.exists() ? Files.readString(pf.toPath()).trim() : "1";
        } catch (Exception e) {
            phase = "1";
        }
        final String ph = phase;
        Bukkit.getScheduler().runTaskLater(this, () -> Bukkit.getScheduler().runTaskAsynchronously(this, () -> run(ph)), 60L);
    }

    private void run(String phase) {
        try {
            results.add("== phase " + phase + " ==");
            switch (phase) {
                case "1" -> phase1();
                case "2" -> phase2();
                case "3" -> phase3();
                case "4" -> phase4();
                case "5" -> phase5();
                case "6" -> new Phase6(this).run();
                case "7" -> new Phase7(this).run();
                default -> check("known phase", false, phase);
            }
        } catch (Throwable t) {
            fail++;
            results.add("FAIL unexpected exception: " + t);
            for (StackTraceElement el : t.getStackTrace()) {
                if (el.getClassName().startsWith("dev.aura")) {
                    results.add("     at " + el);
                    break;
                }
            }
        }
        results.add("== phase " + phase + ": " + pass + " passed, " + fail + " failed ==");
        try {
            Files.write(new File(getDataFolder(), "results-" + phase + ".txt").toPath(), results, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // best effort
        }
        results.forEach(l -> getLogger().info(l));
        Bukkit.getScheduler().runTask(this, () -> Bukkit.shutdown());
    }

    // ------------------------------------------------------------------ helpers
    void createJail() throws Exception {
        onMain(() -> {
            JailMain jm = (JailMain) getServer().getPluginManager().getPlugin("JailReloaded");
            World w = Bukkit.getWorlds().get(0);
            if (jm.getJailManager().getJail("testjail") == null) {
                Jail j = new Jail(jm, "testjail");
                j.setBounds(w.getName(), -10, 60, -10, 10, 90, 10);
                j.setTeleportIn(new Location(w, 0, 70, 0));
                j.setTeleportFree(new Location(w, 20, 70, 20));
                j.setEnabled(true);
                jm.getJailManager().addJail(j, true);
            }
            // test players are not real joined players
            jm.getConfig().set(com.graywolf336.jail.enums.Settings.ALLOWJAILINGNEVERPLAYEDBEFOREPLAYERS.getPath(), true);
            return null;
        });
    }

    Prisoner prisoner(UUID u) throws Exception {
        return onMain(() -> JailsAPI.getJailManager().getPrisoner(u));
    }

    Case mkCase(Actor by, String name, String offense) throws Exception {
        Target t = new Target(id(name), name, false);
        ap.gui().resolver.recordJoin(t.uuid(), name, "10.0.0." + (Math.abs(name.hashCode()) % 200 + 1), 0).get();
        return r(ap.cases().create(by, new CaseService.CreateRequest(t, offense, Severity.MODERATE,
                "Live test: repeated offensive behaviour towards other players.", null,
                List.of(new EvidenceValidator.Input(EvidenceType.EXTERNAL_LINK, "https://example.com/proof-" + name, ""))))).orThrow();
    }

    Case toTrial(Actor owner, Case c) throws Exception {
        c = r(ap.cases().transition(owner, c.id(), c.version(), CaseStatus.AWAITING_TRIAL, null)).orThrow();
        c = r(ap.cases().assignJudge(owner, c.id(), c.version(), new Target(owner.uuid(), owner.name(), true))).orThrow();
        return r(ap.cases().transition(owner, c.id(), c.version(), CaseStatus.IN_TRIAL, null)).orThrow();
    }

    // ------------------------------------------------------------------ phase 1: full functional run
    private void phase1() throws Exception {
        Actor owner = god("LiveOwner");
        // The owner must hold judicial.verdict for assignJudge validation (online check) - the resolver falls back to LuckPerms offline;
        // grant it through LuckPerms if present so the real path is exercised.
        grantLuckPerms(owner.uuid(), "aurapunishments.judicial.verdict");
        Actor mod = limited("LiveJrMod", 20, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.CASE_CREATE, Perms.CASE_VIEW_ALL, Perms.EVIDENCE_ADD,
                Perms.EVIDENCE_VIEW, Perms.CUSTODY_INITIATE, Perms.CUSTODY_VIEW, Perms.HISTORY_VIEW, Perms.punish(PunishType.MUTE),
                Perms.limit(PunishType.MUTE, "short"));
        createJail();
        check("JailReloaded detected as compatible", ap.core().jail.available(), ap.core().jail.status());
        check("jail 'testjail' usable", onMain(() -> JailsAPI.getJailManager().unusableMessage(JailsAPI.getJailManager().getJail("testjail")) == null), "");

        // --- target resolution
        var res = ap.gui().resolver;
        check("unknown target rejected", r(onMain(() -> res.resolve("NoSuchPlayerXyz"))).code() == Result.Code.NOT_FOUND, "");
        mkCase(owner, "LiveAmbA", "chat_misconduct");
        ap.gui().resolver.recordJoin(UUID.randomUUID(), "LiveAmbA", "10.9.9.9", 0).get();
        check("ambiguous name rejected", r(onMain(() -> res.resolve("LiveAmbA"))).code() == Result.Code.INVALID, "");
        check("verified offline target resolved", r(onMain(() -> res.resolve(id("LiveAmbA").toString()))).isOk(), "by uuid");

        // --- pretrial custody through the REAL JailReloaded
        Case c1 = mkCase(mod, "LiveSteve", "harassment");
        Result<Custody> cu = r(ap.custody().initiate(mod, c1.id()));
        check("custody initiated via JailReloaded", cu.isOk(), cu.message());
        Prisoner p1 = prisoner(id("LiveSteve"));
        check("JailReloaded holds the prisoner", p1 != null, "");
        if (p1 != null) {
            check("prisoner reason tagged with case", p1.getReason().contains("AP-" + String.format("%04d", c1.id())), p1.getReason());
            check("pretrial = open-ended (time -1)", p1.isJailedForever(), "remaining=" + p1.getRemainingTime());
            check("jailer recorded", "LiveJrMod".equals(p1.getJailer()), p1.getJailer());
        }
        if (cu.isOk()) {
            check("custody ACTIVE with 48h deadline", cu.value().status() == CustodyStatus.ACTIVE
                    && Math.abs(cu.value().deadline() - cu.value().startedAt() - 48 * Durations.HOUR) < 1000, String.valueOf(cu.value().status()));
            check("jail prisoner id stored", cu.value().jailPrisonerId() != null, "");
        }
        Case c1b = mkCase(mod, "LiveSteve", "theft");
        Result<Custody> dup = r(ap.custody().initiate(mod, c1b.id()));
        check("second custody for same player refused", !dup.isOk() && dup.code() == Result.Code.DUPLICATE, dup.message());

        // --- JAIL sentence: same record converted, time unit verified, credit applied
        Case t1 = toTrial(owner, c1);
        var v = r(ap.judicial().enterVerdict(owner, t1.id(), t1.version(), Verdict.GUILTY, "Evidence proves the offense beyond doubt."));
        check("guilty verdict recorded", v.isOk() && v.value().c().verdict() == Verdict.GUILTY, v.message());
        String idBefore = p1 == null ? "?" : onMain(() -> JailsAPI.getJailManager().getPrisoner(id("LiveSteve")).ensureJailId());
        Thread.sleep(2500); // measurable time served
        var s = r(ap.judicial().sentence(owner, t1.id(), v.value().c().version(),
                new JudicialService.SentenceSpec(PunishType.JAIL, 2 * Durations.HOUR, "Two hours for harassment.", true)));
        check("jail sentence imposed", s.isOk(), s.message());
        Prisoner p1b = prisoner(id("LiveSteve"));
        check("same JailReloaded record converted (no second jail)", p1b != null && idBefore.equals(onMain(() -> JailsAPI.getJailManager().getPrisoner(id("LiveSteve")).ensureJailId())), "");
        if (p1b != null && s.isOk()) {
            long expected = 2 * Durations.HOUR - s.value().creditedMs();
            check("time-served credit applied (>=2s)", s.value().creditedMs() >= 2000, "credit=" + s.value().creditedMs() + "ms");
            check("JailReloaded time unit is milliseconds & matches", !p1b.isJailedForever() && Math.abs(p1b.getRemainingTime() - expected) < 5000,
                    "remaining=" + p1b.getRemainingTime() + " expected~" + expected);
            Custody serving = ap.core().db.txSync(cn -> Repo.Custodies.forCase(cn, t1.id())).get(0);
            check("custody SERVING without review deadline", serving.status() == CustodyStatus.SERVING && serving.deadline() == null, String.valueOf(serving.status()));
        }

        // --- not guilty releases through JailReloaded
        Case c2 = mkCase(mod, "LiveAlex", "harassment");
        check("custody for Alex", r(ap.custody().initiate(mod, c2.id())).isOk(), "");
        Case t2 = toTrial(owner, c2);
        var ng = r(ap.judicial().enterVerdict(owner, t2.id(), t2.version(), Verdict.NOT_GUILTY, "The evidence does not establish the allegation."));
        check("not guilty verdict", ng.isOk() && ng.value().c().status() == CaseStatus.NOT_GUILTY, ng.message());
        check("acquitted prisoner released in JailReloaded", prisoner(id("LiveAlex")) == null, ng.isOk() ? ng.value().custodyNote() : "");
        check("no punishment for an acquittal", ap.core().db.txSync(cn -> Repo.Punishments.forCase(cn, t2.id())).isEmpty(), "");

        // --- ban sentence releases custody first, then bans; login enforcement
        Case c3 = mkCase(mod, "LiveBob", "harassment");
        check("custody for Bob", r(ap.custody().initiate(mod, c3.id())).isOk(), "");
        Case t3 = toTrial(owner, c3);
        var g3 = r(ap.judicial().enterVerdict(owner, t3.id(), t3.version(), Verdict.GUILTY, "Evidence proves the offense beyond doubt."));
        var ban = r(ap.judicial().sentence(owner, t3.id(), g3.value().c().version(),
                new JudicialService.SentenceSpec(PunishType.BAN, Durations.DAY, "One day ban for harassment.", true)));
        check("ban sentence imposed", ban.isOk(), ban.message());
        check("custody ended before the ban (no contradictory state)", prisoner(id("LiveBob")) == null, "");
        var pre = new AsyncPlayerPreLoginEvent("LiveBob", InetAddress.getByName("10.0.0.5"), id("LiveBob"));
        Bukkit.getPluginManager().callEvent(pre);
        check("banned player denied at login", pre.getLoginResult() == AsyncPlayerPreLoginEvent.Result.KICK_BANNED, String.valueOf(pre.getLoginResult()));
        var clean = new AsyncPlayerPreLoginEvent("LiveClean", InetAddress.getByName("10.0.0.6"), id("LiveClean"));
        Bukkit.getPluginManager().callEvent(clean);
        check("clean player allowed at login", clean.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED, String.valueOf(clean.getLoginResult()));

        // --- unrelated JailReloaded record is never released
        Case c4 = mkCase(mod, "LiveCarl", "harassment");
        check("custody for Carl", r(ap.custody().initiate(mod, c4.id())).isOk(), "");
        onMain(() -> {
            // another admin releases Carl and re-jails him under a different record
            Prisoner old = JailsAPI.getJailManager().getPrisoner(id("LiveCarl"));
            Jail j = JailsAPI.getJailManager().getJailPrisonerIsIn(old);
            JailsAPI.getPrisonerManager().forceUnJail(j, j.getCellPrisonerIsIn(id("LiveCarl")), null, old, Bukkit.getConsoleSender());
            Prisoner other = new Prisoner(id("LiveCarl").toString(), "LiveCarl", false, 3600000L, "OtherAdmin", "unrelated sentence");
            JailsAPI.getPrisonerManager().prepareJail(j, null, null, other);
            return null;
        });
        Custody carl = ap.core().db.txSync(cn -> Repo.Custodies.forCase(cn, c4.id())).get(0);
        check("JailReloaded release event already reconciled the replaced custody", carl.status() == CustodyStatus.ENDED_EXTERNALLY, String.valueOf(carl.status()));
        var rel = r(ap.custody().release(owner, c4.id(), "Releasing after reviewing the case file."));
        check("nothing left to release for the ended custody", !rel.isOk(), rel.message());
        Prisoner keep = prisoner(id("LiveCarl"));
        check("UNRELATED jail sentence was NOT released", keep != null && "unrelated sentence".equals(keep.getReason()), keep == null ? "released!" : keep.getReason());
        // the adapter itself refuses a record that is not the case's own
        var wrong = onMain(() -> ap.core().jail.release(id("LiveCarl"), "not-the-right-record-id", "LiveOwner"));
        check("adapter refuses to release a record with a different id (NOT_OURS)", wrong.state() == dev.aura.punishments.integration.JailPort.State.NOT_OURS, String.valueOf(wrong.state()));
        check("...and the prisoner is still held", prisoner(id("LiveCarl")) != null, "");
        var wrongTime = onMain(() -> ap.core().jail.setRemaining(id("LiveCarl"), "not-the-right-record-id", 1000L, "LiveOwner"));
        check("adapter refuses to change the time of a different record", wrongTime.state() == dev.aura.punishments.integration.JailPort.State.NOT_OURS, String.valueOf(wrongTime.state()));
        check("...time unchanged", prisoner(id("LiveCarl")).getRemainingTime() > 3_000_000L, "");

        // --- external release through JailReloaded's own command
        Case c5 = mkCase(mod, "LiveDora", "harassment");
        var cd = r(ap.custody().initiate(mod, c5.id()));
        check("custody for Dora", cd.isOk(), cd.message());
        onMain(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "unjailforce LiveDora"));
        Thread.sleep(1500);
        Custody dc = ap.core().db.txSync(cn -> Repo.Custodies.forCase(cn, c5.id())).get(0);
        check("external /unjailforce detected and reconciled via JailReloaded event", dc.status() == CustodyStatus.ENDED_EXTERNALLY, String.valueOf(dc.status()));

        // --- crash-style divergence repaired by reconcile
        Case c6 = mkCase(mod, "LiveEve", "harassment");
        check("custody for Eve", r(ap.custody().initiate(mod, c6.id())).isOk(), "");
        onMain(() -> {
            Prisoner p = JailsAPI.getJailManager().getPrisoner(id("LiveEve"));
            Jail j = JailsAPI.getJailManager().getJailPrisonerIsIn(p);
            JailsAPI.getPrisonerManager().forceUnJail(j, j.getCellPrisonerIsIn(id("LiveEve")), null, p, Bukkit.getConsoleSender());
            return null;
        });
        Thread.sleep(500);
        ap.custody().reconcile().get(30, TimeUnit.SECONDS);
        check("reconcile detects prisoner missing from JailReloaded", ap.core().db.txSync(cn -> Repo.Custodies.forCase(cn, c6.id())).get(0).status() == CustodyStatus.ENDED_EXTERNALLY, "");

        // --- state kept across restart (phase 2): an active custody, a permanent ban, a mute
        Case c7 = mkCase(mod, "LiveFrank", "harassment");
        check("custody for Frank (kept for restart test)", r(ap.custody().initiate(mod, c7.id())).isOk(), "");
        var pm = r(ap.punishments().issueQuick(limited("LiveAdm", 80, Perms.PUNISH_OTHERS, Perms.punish(PunishType.BAN), Perms.punishPermanent(PunishType.BAN)),
                new PunishmentService.Request(new Target(id("LivePerma"), "LivePerma", false), PunishType.BAN, Durations.PERMANENT, "Permanent ban for the test.",
                        List.of(new EvidenceValidator.Input(EvidenceType.EXTERNAL_LINK, "https://example.com/p", "")))));
        check("permanent ban issued", pm.isOk(), pm.message());
        var mu = r(ap.punishments().issueQuick(mod, new PunishmentService.Request(new Target(id("LiveMuted"), "LiveMuted", false), PunishType.MUTE, Durations.HOUR,
                "Muted for spam in the test.", List.of())));
        check("mute issued", mu.isOk(), mu.message());
        Files.writeString(new File(getDataFolder(), "state.txt").toPath(), c7.id() + "\n" + id("LiveFrank") + "\n" + onMain(() -> JailsAPI.getJailManager().getPrisoner(id("LiveFrank")).ensureJailId()));

        // --- direct & emergency on the live stack
        Actor adm = limited("LiveDirect", 80, Perms.PUNISH_OTHERS, Perms.DIRECT, Perms.direct(PunishType.BAN), Perms.limit(PunishType.BAN, "long"), Perms.CASE_VIEW_ALL);
        var noEv = r(ap.direct().direct(adm, new DirectService.DirectRequest(new Target(id("LiveCheat"), "LiveCheat", false), "cheating", Severity.MAJOR,
                "Used killaura in front of several witnesses on the survival world.", "Clear-cut cheating caught on video by two staff members.", List.of(), PunishType.BAN, 7 * Durations.DAY, null)));
        check("direct punishment refused without evidence", noEv.code() == Result.Code.EVIDENCE, noEv.message());
        var okd = r(ap.direct().direct(adm, new DirectService.DirectRequest(new Target(id("LiveCheat"), "LiveCheat", false), "cheating", Severity.MAJOR,
                "Used killaura in front of several witnesses on the survival world.", "Clear-cut cheating caught on video by two staff members.",
                List.of(new EvidenceValidator.Input(EvidenceType.EXTERNAL_LINK, "https://example.com/v1", ""), new EvidenceValidator.Input(EvidenceType.COREPROTECT, "/co l u:LiveCheat t:1h", "")),
                PunishType.BAN, 7 * Durations.DAY, null)));
        check("direct punishment with evidence issued", okd.isOk(), okd.message());
        var em = r(ap.direct().emergency(limited("LiveSr", 60, Perms.PUNISH_OTHERS, Perms.EMERGENCY_USE),
                new DirectService.EmergencyRequest(new Target(id("LiveThreat"), "LiveThreat", false), PunishType.BAN, 6 * Durations.HOUR, "Griefing the spawn area right now.")));
        check("emergency action issued with review deadline", em.isOk() && em.value().reviewDeadline() != null, em.message());
        var emBad = r(ap.direct().emergency(mod, new DirectService.EmergencyRequest(new Target(id("LiveThreat2"), "LiveThreat2", false), PunishType.BAN, Durations.HOUR, "Griefing the spawn area right now.")));
        check("emergency action refused without its own permission", emBad.code() == Result.Code.NO_PERMISSION, emBad.message());

        // --- hierarchy using REAL LuckPerms for an OFFLINE target
        if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
            UUID boss = id("LiveBoss");
            grantLuckPerms(boss, "aurapunishments.level.90");
            ap.gui().resolver.recordJoin(boss, "LiveBoss", "10.0.0.99", 0).get();
            var denied = r(ap.cases().create(limited("LiveLowMod", 50, Perms.PUNISH_OTHERS, Perms.CASE_CREATE), new CaseService.CreateRequest(
                    new Target(boss, "LiveBoss", false), "chat_misconduct", Severity.MINOR, "Excessive caps in global chat all day.", null, List.of())));
            check("offline higher-ranked staff protected (real LuckPerms lookup)", denied.code() == Result.Code.HIERARCHY, denied.message());
            UUID vip = id("LiveVip");
            grantLuckPerms(vip, "aurapunishments.immune");
            var imm = r(ap.cases().create(limited("LiveLowMod", 50, Perms.PUNISH_OTHERS, Perms.CASE_CREATE), new CaseService.CreateRequest(
                    new Target(vip, "LiveVip", false), "chat_misconduct", Severity.MINOR, "Excessive caps in global chat all day.", null, List.of())));
            check("immune offline player protected (real LuckPerms lookup)", imm.code() == Result.Code.HIERARCHY, imm.message());
        } else {
            results.add("SKIP LuckPerms hierarchy checks (LuckPerms not installed)");
        }

        // --- config reload validation
        File cfg = new File(ap.getDataFolder(), "config.yml");
        String orig = Files.readString(cfg.toPath());
        Files.writeString(cfg.toPath(), orig.replace("review-deadline: 48h", "review-deadline: banana"));
        onMain(() -> {
            ap.reloadFor(Bukkit.getConsoleSender());
            return null;
        });
        check("invalid config rejected; previous settings stay active", ap.core().settings.custody.reviewDeadline() == 48 * Durations.HOUR, "");
        Files.writeString(cfg.toPath(), orig.replace("review-deadline: 48h", "review-deadline: 24h"));
        onMain(() -> {
            ap.reloadFor(Bukkit.getConsoleSender());
            return null;
        });
        check("valid config reload applied live", ap.core().settings.custody.reviewDeadline() == 24 * Durations.HOUR, "");
        Files.writeString(cfg.toPath(), orig);
        onMain(() -> {
            ap.reloadFor(Bukkit.getConsoleSender());
            return null;
        });

        // --- audit
        long bad = ap.core().db.txSync(cn -> Repo.Audits.verify(cn));
        check("audit hash chain intact", bad == -1, "first bad=" + bad);
        check("audit has entries", ap.core().db.txSync(cn -> Repo.Audits.count(cn)) > 20, "");
        // reconcile everything one more time: nothing unexpected
        ap.custody().reconcile().get(30, TimeUnit.SECONDS);
        check("unfinished pending operations", ap.core().db.txSync(cn -> Repo.Ops.countUnfinished(cn)) == 0L, "");
        try {
            Files.writeString(new File(getDataFolder(), "phase.txt").toPath(), "2");
        } catch (Exception ignored) {
            // best effort
        }
    }

    private void grantLuckPerms(UUID uuid, String node) throws Exception {
        if (getServer().getPluginManager().getPlugin("LuckPerms") == null) return;
        onMain(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + uuid + " permission set " + node + " true"));
        Thread.sleep(700);
    }

    // ------------------------------------------------------------------ phase 2: after restart
    private void phase2() throws Exception {
        List<String> st = Files.readAllLines(new File(getDataFolder(), "state.txt").toPath());
        long caseId = Long.parseLong(st.get(0));
        UUID frank = UUID.fromString(st.get(1));
        String jailId = st.get(2);
        check("permanent ban survived restart", ap.punishments().cache().ban(id("LivePerma"), System.currentTimeMillis()).isPresent(), "");
        check("temporary direct ban survived restart", ap.punishments().cache().ban(id("LiveCheat"), System.currentTimeMillis()).isPresent(), "");
        check("mute survived restart", ap.punishments().cache().mute(id("LiveMuted"), System.currentTimeMillis()).isPresent(), "");
        var pre = new AsyncPlayerPreLoginEvent("LivePerma", InetAddress.getByName("10.0.0.7"), id("LivePerma"));
        Bukkit.getPluginManager().callEvent(pre);
        check("banned player denied at login after restart", pre.getLoginResult() == AsyncPlayerPreLoginEvent.Result.KICK_BANNED, "");
        Custody cu = ap.core().db.txSync(cn -> Repo.Custodies.forCase(cn, caseId)).get(0);
        check("custody record persisted and still ACTIVE after reconciliation", cu.status() == CustodyStatus.ACTIVE, String.valueOf(cu.status()));
        Prisoner p = prisoner(frank);
        check("JailReloaded persisted the prisoner (same record id)", p != null && jailId.equals(onMain(() -> JailsAPI.getJailManager().getPrisoner(frank).ensureJailId())), "");
        check("case still persisted", ap.cases().detail(god("x"), caseId).get(30, TimeUnit.SECONDS).isOk(), "");
        check("audit chain intact after restart", ap.core().db.txSync(cn -> Repo.Audits.verify(cn)) == -1L, "");
        var rel = r(ap.custody().release(god("LiveOwner"), caseId, "Releasing after the restart persistence check."));
        check("release after restart works through JailReloaded", rel.isOk() && prisoner(frank) == null, rel.message());
    }

    // ------------------------------------------------------------------ phase 3 & 4: JailReloaded missing / incompatible
    private void phase3() throws Exception {
        degraded("missing");
    }

    private void phase4() throws Exception {
        degraded("incompatible");
    }

    /** With the REAL EssentialsX (and Towny) loaded: every real alias of a punishment/chat command is covered by the guards. */
    private void phase5() throws Exception {
        var pm = getServer().getPluginManager();
        check("real EssentialsX is loaded", pm.getPlugin("Essentials") != null, "");
        check("real Towny is loaded", pm.getPlugin("Towny") != null, "");
        var settings = ap.core().settings;
        List<String> legacy = List.of("ban", "tempban", "banip", "unban", "kick", "mute", "tempmute", "unmute", "pardon", "essentials:ban", "essentials:mute", "essentials:kick", "minecraft:ban", "minecraft:kick");
        for (String label : legacy) {
            var names = dev.aura.punishments.listener.CommandGuardListener.names(label);
            check("legacy command /" + label + " is covered", names.stream().anyMatch(settings.legacy.blocked()::contains), names.toString());
        }
        List<String> chat = List.of("msg", "tell", "w", "whisper", "m", "r", "reply", "mail", "me", "essentials:msg", "essentials:r", "pm");
        for (String label : chat) {
            if (label.equals("mail")) continue; // only "mail send" is blocked; reading mail stays allowed
            var names = dev.aura.punishments.listener.CommandGuardListener.names(label);
            check("muted-chat command /" + label + " is covered", names.stream().anyMatch(settings.mute.blockedCommands()::contains), names.toString());
        }
        // every alias EssentialsX itself registers for /msg and /ban resolves back to a blocked name
        for (String base : List.of("msg", "r", "ban", "kick", "mute")) {
            var cmd = Bukkit.getPluginCommand(base);
            if (cmd == null) {
                results.add("SKIP no plugin command for /" + base + " (EssentialsX version lacks it)");
                continue;
            }
            for (String alias : cmd.getAliases()) {
                var names = dev.aura.punishments.listener.CommandGuardListener.names(alias);
                boolean covered = names.stream().anyMatch(settings.legacy.blocked()::contains) || names.stream().anyMatch(settings.mute.blockedCommands()::contains);
                check("EssentialsX alias /" + alias + " (of /" + base + ") covered", covered, names.toString());
            }
        }
        check("Towny /town and its /t alias are NOT blocked for muted players", dev.aura.punishments.listener.CommandGuardListener.names("town").stream().noneMatch(settings.mute.blockedCommands()::contains)
                && dev.aura.punishments.listener.CommandGuardListener.names("t").stream().noneMatch(settings.mute.blockedCommands()::contains),
                dev.aura.punishments.listener.CommandGuardListener.names("t").toString());
        check("harmless commands are not blocked: /spawn", dev.aura.punishments.listener.CommandGuardListener.names("spawn").stream().noneMatch(settings.legacy.blocked()::contains)
                && dev.aura.punishments.listener.CommandGuardListener.names("spawn").stream().noneMatch(settings.mute.blockedCommands()::contains), "");
        check("JailReloaded still answers /jail (not shadowed by EssentialsX)", Bukkit.getPluginCommand("jail") != null && Bukkit.getPluginCommand("jail").getPlugin().getName().equals("JailReloaded"),
                Bukkit.getPluginCommand("jail") == null ? "none" : Bukkit.getPluginCommand("jail").getPlugin().getName());
        // The server console must be able to run /ap (all AuraPunishments nodes default to false, even for ops).
        check("console passes the /ap command permission", Bukkit.getConsoleSender().hasPermission("aurapunishments.command"), "");
        long before = ap.core().db.txSync(cn -> dev.aura.punishments.db.Sql.count(cn, "SELECT COUNT(*) FROM audit_log WHERE action='admin.reload'"));
        onMain(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ap reload"));
        Thread.sleep(800);
        long after = ap.core().db.txSync(cn -> dev.aura.punishments.db.Sql.count(cn, "SELECT COUNT(*) FROM audit_log WHERE action='admin.reload'"));
        check("console /ap reload runs through the real command dispatcher", after == before + 1, "audit admin.reload " + before + " -> " + after);
        check("AuraPunishments commands registered", Bukkit.getPluginCommand("punish") != null && Bukkit.getPluginCommand("ap") != null, "");
        check("commands declare permissions (for AuraCommandFilter)", "aurapunishments.punish".equals(Bukkit.getPluginCommand("punish").getPermission())
                && "aurapunishments.dashboard".equals(Bukkit.getPluginCommand("punishments").getPermission())
                && "aurapunishments.command".equals(Bukkit.getPluginCommand("ap").getPermission()), "");
    }

    private void degraded(String what) throws Exception {
        final String who = "LiveGone-" + what;
        Actor owner = god("LiveOwner");
        check("JailReloaded reported unavailable (" + what + ")", !ap.core().jail.available(), ap.core().jail.status());
        Actor mod = limited("LiveJrMod", 20, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.CASE_CREATE, Perms.CASE_VIEW_ALL, Perms.CUSTODY_INITIATE, Perms.punish(PunishType.MUTE), Perms.limit(PunishType.MUTE, "short"));
        Case c = mkCase(mod, who, "harassment");
        check("cases still work without JailReloaded", c.id() > 0, "");
        var cu = r(ap.custody().initiate(mod, c.id()));
        check("custody fails SAFELY with a clear message", !cu.isOk() && cu.code() == Result.Code.UNAVAILABLE && cu.message().toLowerCase().contains("jailreloaded"), cu.message());
        check("no custody record was fabricated", ap.core().db.txSync(cn -> Repo.Custodies.forCase(cn, c.id())).isEmpty(), "");
        var mu = r(ap.punishments().issueQuick(mod, new PunishmentService.Request(new Target(id(who), who, false), PunishType.MUTE, Durations.HOUR, "Muted for spam in the test.", List.of())));
        check("mutes/bans keep working without JailReloaded", mu.isOk(), mu.message());
        Case t = toTrial(owner, c);
        var g = r(ap.judicial().enterVerdict(owner, t.id(), t.version(), Verdict.GUILTY, "Evidence proves the offense beyond doubt."));
        var js = r(ap.judicial().sentence(owner, t.id(), g.value().c().version(), new JudicialService.SentenceSpec(PunishType.JAIL, Durations.HOUR, "One hour jail for harassment.", true)));
        check("jail sentence refused (never faked) without JailReloaded", !js.isOk() && js.code() == Result.Code.UNAVAILABLE, js.message());
        check("case stays awaiting sentencing", ap.cases().detail(owner, t.id()).get(30, TimeUnit.SECONDS).value().c().status() == CaseStatus.GUILTY, "");
        var ban = r(ap.judicial().sentence(owner, t.id(), g.value().c().version(), new JudicialService.SentenceSpec(PunishType.BAN, Durations.DAY, "One day ban for harassment.", true)));
        check("non-jail sentence still possible", ban.isOk(), ban.message());
    }
}
