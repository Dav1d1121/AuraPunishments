package dev.aura.livetest;

import com.graywolf336.jail.JailsAPI;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.service.Actor;
import dev.aura.punishments.service.CaseService;
import dev.aura.punishments.service.EvidenceValidator;
import dev.aura.punishments.service.JudicialMode;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.util.Result.Code;
import org.bukkit.Bukkit;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 1.3.0 live phase: switching the judicial system OFF on a server that has open judicial work (a case, a pretrial detainee who is really
 * online and really held by JailReloaded, an open case appeal), then finishing that work, then switching it back ON. Nothing may be
 * released, closed or deleted by the switch itself.
 */
final class Phase10 {
    private static final int PORT = 25599;
    private final LiveTest t;
    private final String run = Long.toString(System.currentTimeMillis() % 100000);

    Phase10(LiveTest t) {
        this.t = t;
    }

    private static boolean waitFor(BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return c.getAsBoolean();
    }

    private McClient login(String name, String... nodes) throws Exception {
        UUID u = Phase8.uuidOf(name);
        var lp = net.luckperms.api.LuckPermsProvider.get();
        var user = lp.getUserManager().loadUser(u).get(10, java.util.concurrent.TimeUnit.SECONDS);
        user.data().clear();
        for (String nd : nodes) user.data().add(net.luckperms.api.node.Node.builder(nd).build());
        lp.getUserManager().saveUser(user).get(10, java.util.concurrent.TimeUnit.SECONDS);
        t.ap.gui().resolver.recordJoin(u, name, "10.9.1." + (Math.abs(name.hashCode()) % 200 + 1), 0).get();
        for (int attempt = 1; ; attempt++) {
            try {
                McClient c = McClient.connect(name, u, PORT);
                Thread.sleep(1500);
                return c;
            } catch (IllegalStateException e) {
                if (attempt >= 6) throw e;
                Thread.sleep(5000);
            }
        }
    }

    private void setSwitch(boolean on) throws Exception {
        File cfg = new File(t.ap.getDataFolder(), "config.yml");
        String text = Files.readString(cfg.toPath());
        String from = on ? "\n  enabled: false\n  # May the staff member" : "\n  enabled: true\n  # May the staff member";
        String to = on ? "\n  enabled: true\n  # May the staff member" : "\n  enabled: false\n  # May the staff member";
        t.check("config.yml contains the judicial switch line to edit", text.contains(from), "");
        Files.writeString(cfg.toPath(), text.replace(from, to));
    }

    void run() throws Exception {
        t.createJail();
        Actor god = LiveTest.god("LiveOwner10");
        McClient staff = login("LiveStaff10" + run, "aurapunishments.*", "aurapunishments.level.100", "aurapunishments.immunity.bypass");
        String defendant = "LiveDefendant" + run;
        McClient def = login(defendant, "aurapunishments.level.1");
        t.check("the server runs with the judicial system ON (upgraded-install configuration)", t.ap.core().mode.enabled(), "");

        // ---- judicial work that is open when the switch is turned off
        Target tg = new Target(Phase8.uuidOf(defendant), defendant, true);
        Case c1 = LiveTest.r(t.ap.cases().create(god, new CaseService.CreateRequest(tg, "other", Severity.MAJOR, "Live test: repeated griefing of the spawn builds",
                null, List.of(new EvidenceValidator.Input(dev.aura.punishments.model.Simple.EvidenceType.EXTERNAL_LINK, "https://example.com/p10.png", ""))))).orThrow();
        var cu = LiveTest.r(t.ap.custody().initiate(god, c1.id()));
        t.check("a REAL online player is held in pretrial detention by the real JailReloaded", cu.isOk() && t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(Phase8.uuidOf(defendant))), cu.message());
        Case c2 = LiveTest.r(t.ap.cases().create(god, new CaseService.CreateRequest(new Target(Phase8.uuidOf("LiveOther" + run), "LiveOther" + run, false), "other", Severity.MINOR,
                "Live test: spamming the chat repeatedly again", null, List.of(new EvidenceValidator.Input(dev.aura.punishments.model.Simple.EvidenceType.EXTERNAL_LINK, "https://example.com/p10b.png", ""))))).orThrow();

        // ---- the administrator switches it off and reloads, with a real /ap reload command
        setSwitch(false);
        staff.clear();
        staff.command("ap reload");
        t.check("the reload is accepted and the switch is now off", waitFor(() -> !t.ap.core().mode.enabled(), 8000), "");
        t.check("the state is WIND_DOWN: work is open, so the system is not silently 'disabled'", waitFor(() -> t.ap.core().mode.state() == JudicialMode.State.WIND_DOWN && t.ap.core().mode.report().known(), 8000),
                String.valueOf(t.ap.core().mode.state()));
        var rep = t.ap.core().mode.report();
        t.check("it counted the 2 open cases and 1 detainee", rep.openCases() == 2 && rep.pretrialDetainees() == 1, String.valueOf(rep));
        Thread.sleep(1000);
        t.check("NOTHING was released by the switch: the detainee is still held by JailReloaded", t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(Phase8.uuidOf(defendant))), "");
        t.check("NOTHING was closed or deleted: both cases are still open and the custody is live", t.ap.core().db.txSync(cn -> Repo.Cases.countByStatuses(cn, List.of(CaseStatus.OPEN))) == 2
                && t.ap.core().db.txSync(cn -> Repo.Custodies.allLive(cn)).size() == 1, "");
        t.check("the staff member who reloaded was told what is still open", staff.saw("still open") || staff.saw("wind") || staff.saw("Judicial system switched OFF"), staff.snapshot().toString());

        // ---- new judicial work is refused; existing work is not
        var refused = LiveTest.r(t.ap.cases().create(god, new CaseService.CreateRequest(new Target(Phase8.uuidOf("LiveNew" + run), "LiveNew" + run, false), "other", Severity.MINOR,
                "Live test: this new case must be refused", null, List.of(new EvidenceValidator.Input(dev.aura.punishments.model.Simple.EvidenceType.EXTERNAL_LINK, "https://example.com/n.png", "")))));
        t.check("a NEW case is refused with an explanation", refused.code() == Code.UNAVAILABLE && refused.message().contains("switched off"), refused.message());
        t.check("a NEW pretrial detention is refused", LiveTest.r(t.ap.custody().initiate(god, c2.id())).code() == Code.UNAVAILABLE, "");

        // ---- /ap judicial and the dashboard tell the administrator
        staff.clear();
        staff.command("ap judicial");
        t.check("/ap judicial explains the open work", waitFor(() -> staff.saw("WIND_DOWN") && staff.saw("2 open case") && staff.saw("pretrial detention"), 6000), staff.snapshot().toString());
        staff.command("punishments");
        waitFor(() -> {
            try {
                return t.onMain(() -> {
                    var p = Bukkit.getPlayer(Phase8.uuidOf(staff.name));
                    var inv = p.getOpenInventory().getTopInventory();
                    return inv.getHolder() != null && inv.getHolder().getClass().getName().contains("StaffDashboard");
                });
            } catch (Exception e) {
                return false;
            }
        }, 6000);
        t.check("the staff dashboard shows the 'Judicial wind-down' banner", t.onMain(() -> {
            var p = Bukkit.getPlayer(Phase8.uuidOf(staff.name));
            var inv = p.getOpenInventory().getTopInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                var it = inv.getItem(i);
                if (it != null && it.hasItemMeta() && it.getItemMeta().displayName() != null
                        && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName()).contains("wind-down")) return true;
            }
            return false;
        }), "");
        t.onMain(() -> {
            Bukkit.getPlayer(Phase8.uuidOf(staff.name)).closeInventory();
            return null;
        });

        // ---- finishing the work with the normal tools
        var rel = LiveTest.r(t.ap.custody().release(god, c1.id(), "Released while the judicial system is wound down"));
        t.check("the detainee is released through the normal custody tool", rel.isOk() && waitFor(() -> {
            try {
                return !t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(Phase8.uuidOf(defendant)));
            } catch (Exception e) {
                return false;
            }
        }, 10000), rel.message());
        Case a = t.ap.core().db.txSync(cn -> Repo.Cases.get(cn, c1.id()));
        t.check("both cases can still be closed", LiveTest.r(t.ap.cases().transition(god, a.id(), a.version(), CaseStatus.CLOSED, "Closing during wind-down")).isOk()
                && LiveTest.r(t.ap.cases().transition(god, c2.id(), t.ap.core().db.txSync(cn -> Repo.Cases.get(cn, c2.id())).version(), CaseStatus.CLOSED, "Closing during wind-down")).isOk(), "");
        staff.command("ap judicial recount");
        t.check("with nothing open the state becomes DISABLED", waitFor(() -> t.ap.core().mode.state() == JudicialMode.State.DISABLED, 8000), String.valueOf(t.ap.core().mode.state()));
        t.check("historical cases are still readable", LiveTest.r(t.ap.cases().detail(god, c1.id())).isOk(), "");
        t.check("a closed case cannot be reopened while the system is off", LiveTest.r(t.ap.cases().transition(god, c2.id(), t.ap.core().db.txSync(cn -> Repo.Cases.get(cn, c2.id())).version(), CaseStatus.UNDER_REVIEW, "Trying to reopen")).code() == Code.UNAVAILABLE, "");

        // ---- switching back ON restores everything
        setSwitch(true);
        staff.command("ap reload");
        t.check("switched back ON", waitFor(() -> t.ap.core().mode.enabled(), 8000), "");
        var again = LiveTest.r(t.ap.cases().create(god, new CaseService.CreateRequest(new Target(Phase8.uuidOf("LiveNew" + run), "LiveNew" + run, false), "other", Severity.MINOR,
                "Live test: a new case is possible again", null, List.of(new EvidenceValidator.Input(dev.aura.punishments.model.Simple.EvidenceType.EXTERNAL_LINK, "https://example.com/n2.png", "")))));
        t.check("cases can be opened again", again.isOk(), again.message());
        t.check("audit chain intact and the switch changes were audited", t.ap.core().db.txSync(cn -> Repo.Audits.verify(cn)) == -1L
                && t.ap.core().db.txSync(cn -> dev.aura.punishments.db.Sql.count(cn, "SELECT COUNT(*) FROM audit_log WHERE action='admin.judicial-switch'")) == 2, "");
        staff.close();
        def.close();
    }
}
