package dev.aura.livetest;

import com.graywolf336.jail.JailsAPI;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.db.Sql;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Appeal;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.service.Actor;
import dev.aura.punishments.service.CaseService;
import dev.aura.punishments.service.DirectService;
import dev.aura.punishments.service.EvidenceValidator;
import dev.aura.punishments.service.JudicialMode;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result.Code;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 1.3.1 live phase: the CASE WORKFLOW (judicial system off, casework on) on the real Paper server with the real JailReloaded, LuckPerms and
 * the other plugins. Real TCP protocol clients drive the real GUI and its text dialogs: open a case through the wizard, punish from it
 * (a ban and a jail, both enforced at once and linked to the case), keep the case open, appeal against the punishment, close and reopen the
 * case, use the quick buttons without a case, refuse every court step, then switch the judicial system on, leave real court work open,
 * switch it off again and watch the case workflow report - and never touch - what is left.
 */
final class Phase12 {
    private static final int PORT = 25599;
    private final LiveTest t;
    private final String run = Long.toString(System.currentTimeMillis() % 100000);
    private final List<McClient> everyone = new ArrayList<>();

    Phase12(LiveTest t) {
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

    private interface Section {
        void run() throws Exception;
    }

    private void section(String name, Section s) {
        try {
            s.run();
        } catch (Throwable e) {
            t.check("[" + name + "] completed without an exception", false, e.toString());
            for (StackTraceElement el : e.getStackTrace()) {
                if (el.getClassName().startsWith("dev.aura")) {
                    t.results.add("     at " + el);
                    break;
                }
            }
        }
    }

    private static final String[] STAFF = {"aurapunishments.*", "aurapunishments.appeal.decide", "aurapunishments.level.100", "aurapunishments.limit.mute.extended",
            "aurapunishments.limit.ban.extended", "aurapunishments.limit.jail.extended", "aurapunishments.limit.ipban.extended", "aurapunishments.immunity.bypass"};

    private McClient login(String name, String... nodes) throws Exception {
        UUID u = Phase8.uuidOf(name);
        var lp = net.luckperms.api.LuckPermsProvider.get();
        var user = lp.getUserManager().loadUser(u).get(10, java.util.concurrent.TimeUnit.SECONDS);
        user.data().clear();
        for (String nd : nodes) user.data().add(net.luckperms.api.node.Node.builder(nd).build());
        lp.getUserManager().saveUser(user).get(10, java.util.concurrent.TimeUnit.SECONDS);
        t.ap.gui().resolver.recordJoin(u, name, "10.12.8." + (Math.abs(name.hashCode()) % 200 + 1), 0).get();
        McClient c = null;
        for (int attempt = 1; c == null; attempt++) {
            try {
                c = McClient.connect(name, u, PORT);
            } catch (IllegalStateException e) {
                if (attempt >= 6 || (e.getMessage() != null && e.getMessage().toLowerCase().contains("banned"))) throw e;
                Thread.sleep(5000);
            }
        }
        everyone.add(c);
        Thread.sleep(1500);
        return c;
    }

    private static String plain(ItemStack it) {
        if (it == null || !it.hasItemMeta() || it.getItemMeta().displayName() == null) return "";
        return PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName());
    }

    private String title(McClient c) throws Exception {
        return t.onMain(() -> {
            Player p = Bukkit.getPlayer(Phase8.uuidOf(c.name));
            var h = p == null ? null : p.getOpenInventory().getTopInventory().getHolder();
            return h == null || !h.getClass().getName().startsWith("dev.aura.punishments.gui.") ? "" : PlainTextComponentSerializer.plainText().serialize(p.getOpenInventory().title());
        });
    }

    private int slot(McClient c, String text) throws Exception {
        return t.onMain(() -> {
            Player p = Bukkit.getPlayer(Phase8.uuidOf(c.name));
            if (p == null) return -1;
            var inv = p.getOpenInventory().getTopInventory();
            for (int i = 0; i < inv.getSize(); i++) if (plain(inv.getItem(i)).contains(text)) return i;
            for (int i = 0; i < inv.getSize(); i++) if (plain(inv.getItem(i)).toLowerCase().contains(text.toLowerCase())) return i;
            return -1;
        });
    }

    private boolean has(McClient c, String name) throws Exception {
        return slot(c, name) >= 0;
    }

    private boolean prompting(McClient c) throws Exception {
        return t.onMain(() -> {
            Player p = Bukkit.getPlayer(Phase8.uuidOf(c.name));
            return p != null && t.ap.gui().prompts().has(p);
        });
    }

    private boolean waitTitle(McClient c, String contains, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (title(c).toLowerCase().contains(contains.toLowerCase())) return true;
            Thread.sleep(100);
        }
        return title(c).toLowerCase().contains(contains.toLowerCase());
    }

    private boolean press(McClient c, String name) throws Exception {
        int s = slot(c, name);
        if (s < 0) {
            t.check("button '" + name + "' exists on screen '" + title(c) + "'", false, "");
            return false;
        }
        Thread.sleep(450);
        c.click(s);
        Thread.sleep(500);
        waitFor(() -> {
            try {
                return prompting(c) || !title(c).isEmpty();
            } catch (Exception e) {
                return true;
            }
        }, 4000);
        Thread.sleep(300);
        return true;
    }

    private void cmd(McClient c, String text) throws Exception {
        c.clear();
        c.command(text);
        Thread.sleep(900);
        if (c.saw("Chat disabled")) {
            c.clear();
            c.command(text);
            Thread.sleep(900);
        }
    }

    private void answer(McClient c, String label, String text) throws Exception {
        boolean ok = waitFor(() -> {
            try {
                return prompting(c);
            } catch (Exception e) {
                return false;
            }
        }, 6000);
        t.check("dialog is waiting for " + label, ok, "screen: " + title(c));
        if (!ok) return;
        c.submitDialog(text);
        Thread.sleep(1200);
    }

    private void reset(McClient c) throws Exception {
        if (prompting(c)) {
            if (c.dialogOpen()) c.cancelDialog();
            Thread.sleep(1000);
        }
        t.onMain(() -> {
            Player p = Bukkit.getPlayer(Phase8.uuidOf(c.name));
            if (p != null) p.closeInventory();
            return null;
        });
        Thread.sleep(600);
    }

    private long count(String sql) throws Exception {
        return t.ap.core().db.txSync(cn -> Sql.count(cn, sql));
    }

    private List<Punishment> forTarget(String name) throws Exception {
        UUID u = Phase8.uuidOf(name);
        return t.ap.core().db.txSync(cn -> Repo.Punishments.forTarget(cn, u, 20, 0));
    }

    private Punishment latest(String name, PunishType type) throws Exception {
        return forTarget(name).stream().filter(p -> p.type() == type).findFirst().orElse(null);
    }

    private Case caseRow(long id) throws Exception {
        return t.ap.core().db.txSync(cn -> Repo.Cases.get(cn, id));
    }

    private void setJudicial(boolean on) throws Exception {
        File cfg = new File(t.ap.getDataFolder(), "config.yml");
        String text = Files.readString(cfg.toPath());
        String from = on ? "\n  enabled: false\n  # May the staff member" : "\n  enabled: true\n  # May the staff member";
        String to = on ? "\n  enabled: true\n  # May the staff member" : "\n  enabled: false\n  # May the staff member";
        t.check("config.yml contains the judicial switch line to edit", text.contains(from), "");
        Files.writeString(cfg.toPath(), text.replace(from, to));
    }

    private void editConfig(String from, String to) throws Exception {
        File cfg = new File(t.ap.getDataFolder(), "config.yml");
        String text = Files.readString(cfg.toPath());
        t.check("config.yml contains the text to change: " + from.strip(), text.contains(from), "");
        Files.writeString(cfg.toPath(), text.replace(from, to));
    }

    // ------------------------------------------------------------------ entry
    private McClient staff;
    private String tAlpha;
    private String tBravo;
    private String tCharlie;
    private Case caseA;
    private Case caseB;
    private Punishment banA;

    void run() throws Exception {
        t.createJail();
        tAlpha = "LiveAlpha" + run;
        tBravo = "LiveBravo" + run;
        tCharlie = "LiveCharlie" + run;
        try {
            staff = login("LiveStaff12" + run, STAFF);
        } catch (Throwable e) {
            t.check("test clients connect over TCP", false, e.toString());
            return;
        }
        t.check("a real TCP client is in the play phase", staff.playing, "");
        section("fresh-install", this::freshInstall);
        section("screen", this::screen);
        section("reset", () -> reset(staff));
        section("wizard", this::wizard);
        section("reset", () -> reset(staff));
        section("ban-from-case", this::banFromCase);
        section("reset", () -> reset(staff));
        section("jail-from-case", this::jailFromCase);
        section("reset", () -> reset(staff));
        section("no-court", this::noCourt);
        section("reset", () -> reset(staff));
        section("quick-without-case", this::quickWithoutCase);
        section("require-case-for", this::requireCaseFor);
        section("reset", () -> reset(staff));
        section("appeal", this::appeal);
        section("reset", () -> reset(staff));
        section("close-and-reopen", this::closeAndReopen);
        section("reset", () -> reset(staff));
        section("leftover-court-work", this::leftoverCourtWork);
        section("integrity", this::integrity);
        for (McClient c : everyone) c.close();
    }

    // ------------------------------------------------------------------ 1. a fresh install is in the case workflow
    private void freshInstall() throws Exception {
        t.check("judicial.enabled is false on a fresh install", !t.ap.core().mode.enabled(), "");
        t.check("the case workflow is on: state CASEWORK", t.ap.core().mode.casework() && t.ap.core().mode.state() == JudicialMode.State.CASEWORK, String.valueOf(t.ap.core().mode.state()));
        String text = Files.readString(new File(t.ap.getDataFolder(), "config.yml").toPath());
        t.check("config.yml on disk contains the shipped 'casework: enabled: true' section", text.contains("\ncasework:\n  enabled: true\n"), "");
        t.check("the latest schema and a server name are recorded", t.ap.core().db.schemaVersion() == dev.aura.punishments.db.Schema.latest() && t.ap.core().db.origin() != null, "");
        t.check("JailReloaded is detected and compatible", t.ap.core().jail.available(), t.ap.core().jail.status());
        t.check("no court work is open", t.ap.core().mode.refresh().get().courtLeftover() == 0 && !t.ap.core().mode.judicialVisible(), "");
    }

    // ------------------------------------------------------------------ 2. what the target screen offers
    private void screen() throws Exception {
        login(tAlpha, "aurapunishments.level.1");
        cmd(staff, "punish " + tAlpha);
        t.check("/punish opened the target screen", waitTitle(staff, tAlpha, 8000), title(staff));
        for (String there : List.of("Create Case", "View Cases", "Submit Evidence", "Issue Warning", "Issue Kick", "Issue Mute", "Issue Ban", "Issue IP Ban", "Issue Jail",
                "Emergency Action", "Punishment History", "Staff Notes", "Warnings", "Jail Status", "Appeals", "Release / Revoke")) {
            t.check("option available: " + there, has(staff, there), "");
        }
        for (String gone : List.of("Pretrial Detention", "Direct Punishment", "Judicial Review", "In Custody")) {
            t.check("court option hidden: " + gone, !has(staff, gone), "");
        }
        t.check("Create Case comes first (the main path), before the quick buttons", slot(staff, "Create Case") < slot(staff, "Issue Warning"), "");
        cmd(staff, "ap help");
        t.check("/ap help lists the case commands but not the judicial dashboard", staff.saw("/ap cases") && !staff.saw("/ap judge"), staff.snapshot().toString());
        cmd(staff, "ap judicial");
        t.check("/ap judicial reports the case workflow", staff.saw("CASEWORK") && staff.saw("no court"), staff.snapshot().toString());
    }

    // ------------------------------------------------------------------ 3. open a case through the wizard
    private void wizard() throws Exception {
        cmd(staff, "punish " + tAlpha);
        t.check("target screen", waitTitle(staff, tAlpha, 8000), title(staff));
        press(staff, "Create Case");
        t.check("the case wizard opened", waitTitle(staff, "New case", 4000), title(staff));
        press(staff, "1. Offense");
        press(staff, "Harassment");
        press(staff, "3. Incident description");
        answer(staff, "the incident description", "Repeatedly harassed other players in the global chat " + run);
        press(staff, "5. Evidence");
        press(staff, "external link");
        answer(staff, "the evidence link", "https://example.com/live-case-" + run + ".png");
        press(staff, "SUBMIT CASE");
        t.check("the new case opened on screen", waitTitle(staff, "AP-", 8000), title(staff));
        t.check("exactly one case exists", waitFor(() -> {
            try {
                return count("SELECT COUNT(*) FROM cases") == 1;
            } catch (Exception e) {
                return false;
            }
        }, 6000), "");
        caseA = t.ap.core().db.txSync(cn -> Repo.Cases.all(cn, 5, 0)).get(0);
        t.check("it is OPEN, about the right player, with the evidence attached", caseA.status() == CaseStatus.OPEN && caseA.accusedName().equals(tAlpha)
                && caseA.offenseKey().equals("harassment") && t.ap.core().db.txSync(cn -> Repo.Evidences.countActive(cn, caseA.id())) == 1, String.valueOf(caseA));
        t.check("the case screen has a Punish button and no court buttons", has(staff, "Punish") && !has(staff, "Assign judge") && !has(staff, "Schedule trial")
                && !has(staff, "Hearing reference") && !has(staff, "Enter verdict") && !has(staff, "Impose sentence"), "");
    }

    // ------------------------------------------------------------------ 4. ban from the case: final, linked, enforced, case stays open
    private void banFromCase() throws Exception {
        McClient alpha = everyone.stream().filter(c -> c.name.equals(tAlpha)).findFirst().orElseThrow();
        if (!title(staff).contains(caseA.ref())) {
            cmd(staff, "ap case " + caseA.id());
            waitTitle(staff, caseA.ref(), 6000);
        }
        press(staff, "Punish");
        t.check("the punishment chooser opened for the case", waitTitle(staff, "Punish from", 4000), title(staff));
        press(staff, "Ban");
        t.check("the ban screen opened with the case as context", waitTitle(staff, "from " + caseA.ref(), 4000), title(staff));
        press(staff, "Custom duration");
        answer(staff, "the ban duration", "7d");
        press(staff, "Reason");
        answer(staff, "the ban reason", "Repeated harassment of other players " + run);
        press(staff, "CONTINUE");
        t.check("confirmation screen says the case stays open", waitTitle(staff, "confirm", 4000), title(staff));
        press(staff, "CONFIRM");
        t.check("the ban was recorded", waitFor(() -> {
            try {
                return latest(tAlpha, PunishType.BAN) != null;
            } catch (Exception e) {
                return false;
            }
        }, 8000), "");
        banA = latest(tAlpha, PunishType.BAN);
        t.check("it is final and immediate: ACTIVE, source STANDARD (no court), linked to the case, offense recorded", banA != null && banA.status() == PunishStatus.ACTIVE
                && banA.source().name().equals("STANDARD") && banA.caseId() != null && banA.caseId() == caseA.id() && "harassment".equals(banA.offenseKey()), String.valueOf(banA));
        t.check("the online player was disconnected with the ban screen", waitFor(() -> alpha.disconnected != null, 8000) && alpha.disconnected.toLowerCase().contains("banned"),
                String.valueOf(alpha.disconnected));
        boolean refused;
        String why = "";
        try {
            McClient again = McClient.connect(tAlpha, Phase8.uuidOf(tAlpha), PORT);
            everyone.add(again);
            refused = false;
        } catch (Throwable e) {
            refused = true;
            why = e.getMessage();
        }
        t.check("the banned player cannot log back in", refused && why.toLowerCase().contains("banned"), why);
        t.check("the case is STILL OPEN", caseRow(caseA.id()).status() == CaseStatus.OPEN, String.valueOf(caseRow(caseA.id()).status()));
        t.check("the case timeline records the punishment", t.ap.core().db.txSync(cn -> Repo.Events.forCase(cn, caseA.id())).stream()
                .anyMatch(e -> e.type().equals("PUNISHMENT_ISSUED") && e.detail().contains("#" + banA.id())), "");
        t.check("the screen returned to the case, which now lists the punishment", waitTitle(staff, caseA.ref(), 6000), title(staff));
        t.check("no judicial rows were created: no custody, no verdict, no sentence", count("SELECT COUNT(*) FROM custody") == 0 && caseRow(caseA.id()).verdict() == null, "");
        t.check("evidence supplied earlier stays on the case, nothing is duplicated onto the punishment",
                t.ap.core().db.txSync(cn -> Repo.Evidences.forPunishment(cn, banA.id(), false)).isEmpty(), "");
    }

    // ------------------------------------------------------------------ 5. jail from a case with the real JailReloaded
    private void jailFromCase() throws Exception {
        McClient bravo = login(tBravo, "aurapunishments.level.1");
        caseB = LiveTest.r(t.ap.cases().create(LiveTest.god("LiveOwner12"), new CaseService.CreateRequest(new Target(Phase8.uuidOf(tBravo), tBravo, true), "harassment", Severity.MODERATE,
                "Live test: harassing the shop owners " + run, null, List.of(new EvidenceValidator.Input(dev.aura.punishments.model.Simple.EvidenceType.EXTERNAL_LINK, "https://example.com/live-b-" + run + ".png", ""))))).orThrow();
        cmd(staff, "ap case " + caseB.id());
        t.check("/ap case opened the case", waitTitle(staff, caseB.ref(), 8000), title(staff));
        press(staff, "Punish");
        press(staff, "Jail");
        t.check("the jail screen has the case as context", waitTitle(staff, "from " + caseB.ref(), 4000), title(staff));
        press(staff, "Custom duration");
        answer(staff, "the jail duration", "30m");
        press(staff, "Reason");
        answer(staff, "the jail reason", "Harassing the shop owners " + run);
        press(staff, "CONTINUE");
        press(staff, "CONFIRM");
        t.check("the jail punishment is recorded as HELD", waitFor(() -> {
            try {
                Punishment p = latest(tBravo, PunishType.JAIL);
                return p != null && "HELD".equals(p.jailState());
            } catch (Exception e) {
                return false;
            }
        }, 10000), String.valueOf(latest(tBravo, PunishType.JAIL)));
        Punishment j = latest(tBravo, PunishType.JAIL);
        t.check("JailReloaded really holds the player (its own API says so)", t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(Phase8.uuidOf(tBravo))), "");
        t.check("it is a jail punishment linked to the case, not pretrial custody", j != null && j.caseId() != null && j.caseId() == caseB.id() && j.custodyId() == null
                && count("SELECT COUNT(*) FROM custody") == 0, String.valueOf(j));
        t.check("the case is still open", caseRow(caseB.id()).status() == CaseStatus.OPEN, "");
        // early release through the normal command; the case is untouched and records the revocation
        cmd(staff, "ap unjail " + tBravo + " Released early during the live test " + run);
        t.check("/ap unjail releases the player through JailReloaded", waitFor(() -> {
            try {
                return !t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(Phase8.uuidOf(tBravo)));
            } catch (Exception e) {
                return false;
            }
        }, 10000), staff.snapshot().toString());
        t.check("the case timeline records the revocation and the case is still open", t.ap.core().db.txSync(cn -> Repo.Events.forCase(cn, caseB.id())).stream()
                .anyMatch(e -> e.type().equals("PUNISHMENT_REVOKED")) && caseRow(caseB.id()).status() == CaseStatus.OPEN, "");
        bravo.clear();
    }

    // ------------------------------------------------------------------ 6. there is no court
    private void noCourt() throws Exception {
        Actor god = LiveTest.god("LiveOwner12");
        t.check("pretrial detention is refused", LiveTest.r(t.ap.custody().initiate(god, caseB.id())).code() == Code.UNAVAILABLE, "");
        t.check("a direct punishment is refused", LiveTest.r(t.ap.direct().direct(god, new DirectService.DirectRequest(new Target(Phase8.uuidOf(tBravo), tBravo, true), "other", Severity.SEVERE,
                "Live test: serious griefing across many builds", "Obvious and repeated", List.of(new EvidenceValidator.Input(dev.aura.punishments.model.Simple.EvidenceType.EXTERNAL_LINK, "https://example.com/d.png", "")),
                PunishType.BAN, Durations.PERMANENT, null))).code() == Code.UNAVAILABLE, "");
        Case b = caseRow(caseB.id());
        t.check("moving a case to a trial status is refused", LiveTest.r(t.ap.cases().transition(god, b.id(), b.version(), CaseStatus.AWAITING_TRIAL, null)).code() == Code.UNAVAILABLE, "");
        t.check("assigning a judge and scheduling a trial are refused", LiveTest.r(t.ap.cases().assignJudge(god, b.id(), b.version(), new Target(Phase8.uuidOf("LiveJudge" + run), "LiveJudge" + run, false))).code() == Code.UNAVAILABLE
                && LiveTest.r(t.ap.cases().scheduleTrial(god, b.id(), b.version(), System.currentTimeMillis() + Durations.DAY)).code() == Code.UNAVAILABLE, "");
        cmd(staff, "ap case " + caseB.id());
        waitTitle(staff, caseB.ref(), 8000);
        press(staff, "Change status");
        t.check("the status chooser opened", waitTitle(staff, "Change status", 4000), title(staff));
        t.check("it offers investigation, review and closing only", has(staff, "Under Investigation") && has(staff, "Under Review") && has(staff, "Closed")
                && !has(staff, "Awaiting Trial") && !has(staff, "In Trial") && !has(staff, "Awaiting Verdict"), "");
        t.check("the accused received no hearing notices (there are none)", count("SELECT COUNT(*) FROM player_notifications WHERE kind='DEFENDANT'") == 0, "");
    }

    // ------------------------------------------------------------------ 7. the quick path still works without a case
    private void quickWithoutCase() throws Exception {
        long before = count("SELECT COUNT(*) FROM cases");
        login(tCharlie, "aurapunishments.level.1");
        cmd(staff, "ap warn " + tCharlie + " Please keep the global chat friendly " + run);
        t.check("a quick warning is recorded", waitFor(() -> {
            try {
                return latest(tCharlie, PunishType.WARN) != null;
            } catch (Exception e) {
                return false;
            }
        }, 8000), staff.snapshot().toString());
        Punishment w = latest(tCharlie, PunishType.WARN);
        t.check("it is a plain punishment with no case, and no hidden case was invented", w != null && w.caseId() == null && count("SELECT COUNT(*) FROM cases") == before, String.valueOf(w));
        cmd(staff, "ap mute " + tCharlie + " 1h Spamming the global channel " + run);
        t.check("a quick mute is enforced at once", waitFor(() -> {
            try {
                return t.ap.punishments().muteFor(Phase8.uuidOf(tCharlie)).isPresent();
            } catch (Exception e) {
                return false;
            }
        }, 8000), staff.snapshot().toString());
        t.check("still no extra case", count("SELECT COUNT(*) FROM cases") == before, "");
    }

    // ------------------------------------------------------------------ 8. casework.require-case-for
    private void requireCaseFor() throws Exception {
        editConfig("  require-case-for: []\n", "  require-case-for: [BAN, JAIL]\n");
        staff.clear();
        staff.command("ap reload");
        t.check("the new setting is loaded", waitFor(() -> t.ap.core().settings.casework.requireCaseFor().contains(PunishType.BAN), 8000), "");
        cmd(staff, "ap ban " + tCharlie + " 1d Quick ban that must now go through a case");
        t.check("a quick ban is refused with an explanation that names the case", staff.saw("from a case"), staff.snapshot().toString());
        t.check("nothing was banned", latest(tCharlie, PunishType.BAN) == null, "");
        t.check("a quick kick is still allowed (types not listed stay quick)", LiveTest.r(t.ap.punishments().issueQuick(LiveTest.god("LiveOwner12"), new dev.aura.punishments.service.PunishmentService.Request(
                new Target(Phase8.uuidOf(tCharlie), tCharlie, true), PunishType.KICK, 0, "Live test: kicked to check the quick path", List.of()))).isOk(), "");
        editConfig("  require-case-for: [BAN, JAIL]\n", "  require-case-for: []\n");
        staff.clear();
        staff.command("ap reload");
        t.check("restored", waitFor(() -> t.ap.core().settings.casework.requireCaseFor().isEmpty(), 8000), "");
    }

    // ------------------------------------------------------------------ 9. appeals go through the punishment
    private void appeal() throws Exception {
        cmd(staff, "ap punishment " + banA.id());
        t.check("the punishment screen opened", waitTitle(staff, "Punishment #" + banA.id(), 8000), title(staff));
        t.check("it shows the case it was issued from", has(staff, caseA.ref()) || has(staff, "Case"), "");
        press(staff, "Appeals");
        answer(staff, "the appeal grounds", "The player says another account was used " + run);
        answer(staff, "the ticket reference", "0123");
        t.check("the appeal is recorded against the PUNISHMENT with the ticket reference", waitFor(() -> {
            try {
                Appeal a = t.ap.core().db.txSync(cn -> Repo.Appeals.pendingForPunishment(cn, banA.id()));
                return a != null && "0123".equals(a.ticket()) && a.caseId() == null;
            } catch (Exception e) {
                return false;
            }
        }, 6000), "");
        t.check("filing an appeal lifts nothing", t.ap.punishments().banFor(Phase8.uuidOf(tAlpha), null).isPresent(), "");
        press(staff, "Take appeal");
        press(staff, "Decide appeal");
        press(staff, "OVERTURN");
        answer(staff, "the decision reasoning", "New evidence shows the wrong account was banned " + run);
        press(staff, "CONFIRM");
        t.check("overturning revokes the ban", waitFor(() -> {
            try {
                return latest(tAlpha, PunishType.BAN).status() == PunishStatus.REVOKED;
            } catch (Exception e) {
                return false;
            }
        }, 8000), "");
        t.check("the case records the revocation and is still open", t.ap.core().db.txSync(cn -> Repo.Events.forCase(cn, caseA.id())).stream().anyMatch(e -> e.type().equals("PUNISHMENT_REVOKED"))
                && caseRow(caseA.id()).status() == CaseStatus.OPEN, "");
        boolean back;
        try {
            McClient again = McClient.connect(tAlpha, Phase8.uuidOf(tAlpha), PORT);
            everyone.add(again);
            back = again.playing;
        } catch (Throwable e) {
            back = false;
        }
        t.check("the player can log in again", back, "");
    }

    // ------------------------------------------------------------------ 10. close, refuse punishing from it, reopen
    private void closeAndReopen() throws Exception {
        cmd(staff, "ap case " + caseA.id());
        t.check("case screen", waitTitle(staff, caseA.ref(), 8000), title(staff));
        press(staff, "Change status");
        press(staff, "Closed");
        answer(staff, "the closing reason", "Handled and appealed successfully " + run);
        t.check("the case is CLOSED", waitFor(() -> {
            try {
                return caseRow(caseA.id()).status() == CaseStatus.CLOSED;
            } catch (Exception e) {
                return false;
            }
        }, 6000), "");
        var refused = LiveTest.r(t.ap.punishments().issueForCase(LiveTest.god("LiveOwner12"), caseA.id(), new dev.aura.punishments.service.PunishmentService.Request(
                new Target(Phase8.uuidOf(tAlpha), tAlpha, true), PunishType.WARN, 0, "Live test: warning from a closed case", List.of())));
        t.check("a closed case cannot be punished from until it is reopened", refused.code() == Code.STATE, refused.message());
        Case c = caseRow(caseA.id());
        var rev = LiveTest.r(t.ap.cases().transition(LiveTest.god("LiveOwner12"), c.id(), c.version(), CaseStatus.UNDER_REVIEW, "Reopening after new information " + run));
        c = caseRow(caseA.id());
        var open = LiveTest.r(t.ap.cases().transition(LiveTest.god("LiveOwner12"), c.id(), c.version(), CaseStatus.OPEN, "Back to open to continue the work " + run));
        t.check("the case workflow can reopen a closed case", rev.isOk() && open.isOk() && caseRow(caseA.id()).status() == CaseStatus.OPEN, rev.message() + " / " + open.message());
        t.check("punishing from the reopened case works again", LiveTest.r(t.ap.punishments().issueForCase(LiveTest.god("LiveOwner12"), caseA.id(), new dev.aura.punishments.service.PunishmentService.Request(
                new Target(Phase8.uuidOf(tAlpha), tAlpha, true), PunishType.WARN, 0, "Live test: warning from the reopened case", List.of()))).isOk(), "");
    }

    // ------------------------------------------------------------------ 11. court work left over from when the judicial system was on
    private void leftoverCourtWork() throws Exception {
        setJudicial(true);
        staff.clear();
        staff.command("ap reload");
        t.check("the judicial system is switched ON again", waitFor(() -> t.ap.core().mode.enabled(), 8000), "");
        Actor god = LiveTest.god("LiveOwner12");
        McClient charlie = everyone.stream().filter(c -> c.name.equals(tCharlie) && c.playing && c.disconnected == null).findFirst().orElse(null);
        if (charlie == null) charlie = login(tCharlie, "aurapunishments.level.1");
        Case cc = LiveTest.r(t.ap.cases().create(god, new CaseService.CreateRequest(new Target(Phase8.uuidOf(tCharlie), tCharlie, true), "other", Severity.MAJOR,
                "Live test: repeated griefing of the spawn builds " + run, null, List.of(new EvidenceValidator.Input(dev.aura.punishments.model.Simple.EvidenceType.EXTERNAL_LINK, "https://example.com/c-" + run + ".png", ""))))).orThrow();
        var cu = LiveTest.r(t.ap.custody().initiate(god, cc.id()));
        t.check("with the judicial system ON a REAL online player is held in pretrial detention by the real JailReloaded", cu.isOk()
                && t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(Phase8.uuidOf(tCharlie))), cu.message());
        // switch it off again: the case workflow stays on
        setJudicial(false);
        staff.clear();
        staff.command("ap reload");
        t.check("switched OFF: the state is CASEWORK (not WIND_DOWN: the case workflow is on)", waitFor(() -> !t.ap.core().mode.enabled() && t.ap.core().mode.state() == JudicialMode.State.CASEWORK, 8000),
                String.valueOf(t.ap.core().mode.state()));
        t.ap.core().mode.refresh().get();
        var rep = t.ap.core().mode.report();
        t.check("the leftover pretrial detainee is counted", rep.pretrialDetainees() == 1 && rep.courtLeftover() >= 1, String.valueOf(rep));
        t.check("the court screens are visible again only to finish the leftovers", t.ap.core().mode.judicialVisible(), "");
        Thread.sleep(1500);
        t.check("NOTHING was released by the switch: the detainee is still held by JailReloaded", t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(Phase8.uuidOf(tCharlie))), "");
        staff.clear();
        staff.command("ap judicial");
        t.check("/ap judicial reports the case workflow and the leftover detainee", waitFor(() -> staff.saw("CASEWORK") && staff.saw("pretrial detention"), 6000), staff.snapshot().toString());
        // new court work is still refused, existing court work can be finished
        t.check("new pretrial detention is refused", LiveTest.r(t.ap.custody().initiate(god, caseB.id())).code() == Code.UNAVAILABLE, "");
        var rel = LiveTest.r(t.ap.custody().release(god, cc.id(), "Released while the court is retired " + run));
        t.check("the detainee is released through the normal custody tool", rel.isOk() && waitFor(() -> {
            try {
                return !t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(Phase8.uuidOf(tCharlie)));
            } catch (Exception e) {
                return false;
            }
        }, 10000), rel.message());
        Case c3 = caseRow(cc.id());
        t.check("the leftover case can be closed", LiveTest.r(t.ap.cases().transition(god, c3.id(), c3.version(), CaseStatus.CLOSED, "Closing the leftover case " + run)).isOk(), "");
        t.ap.core().mode.refresh().get();
        t.check("with nothing left, the court screens disappear again", t.ap.core().mode.report().courtLeftover() == 0 && !t.ap.core().mode.judicialVisible(), String.valueOf(t.ap.core().mode.report()));
        t.check("and new cases still open under the case workflow", LiveTest.r(t.ap.cases().create(god, new CaseService.CreateRequest(new Target(Phase8.uuidOf("LiveNew" + run), "LiveNew" + run, false), "other", Severity.MINOR,
                "Live test: a case under the case workflow " + run, null, List.of(new EvidenceValidator.Input(dev.aura.punishments.model.Simple.EvidenceType.EXTERNAL_LINK, "https://example.com/n-" + run + ".png", ""))))).isOk(), "");
    }

    // ------------------------------------------------------------------ 12. integrity
    private void integrity() throws Exception {
        t.check("the audit chain is intact", t.ap.core().db.txSync(cn -> Repo.Audits.verify(cn)) == -1L, "");
        t.check("every case and punishment carries a unique id and this server's name", count("SELECT COUNT(*) FROM cases WHERE uid IS NULL OR origin IS NULL") == 0
                && count("SELECT COUNT(*) FROM punishments WHERE uid IS NULL OR origin IS NULL") == 0, "");
        t.check("no punishment is linked to a case that does not exist", count("SELECT COUNT(*) FROM punishments p WHERE p.case_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM cases c WHERE c.id=p.case_id)") == 0, "");
        t.check("no pending operation is left over", count("SELECT COUNT(*) FROM pending_ops WHERE state IN ('PENDING','EXTERNAL_DONE')") == 0, "");
    }
}
