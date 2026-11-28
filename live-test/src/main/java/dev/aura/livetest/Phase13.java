package dev.aura.livetest;

import com.graywolf336.jail.JailsAPI;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.db.Sql;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.PunishStatus;
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
 * 1.3.2 live phase: the punishment is chosen INSIDE the Create Case screen. Real protocol clients on the real server drive the real menus:
 * Create Case -> offense, description, evidence -> Punishment -> the usual chooser and the usual punishment screen (duration, reason) ->
 * "Use this punishment" -> Submit case + punishment -> one confirmation. The case is opened and the punishment issued together, final at
 * once, and no owner review is created. Also checks that owner review is off on a fresh install and its screens are out of the way.
 */
final class Phase13 {
    private static final int PORT = 25599;
    private final LiveTest t;
    private final String run = Long.toString(System.currentTimeMillis() % 100000);
    private final List<McClient> everyone = new ArrayList<>();

    Phase13(LiveTest t) {
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

    private int materialSlot(McClient c, String type) throws Exception {
        return t.onMain(() -> {
            Player p = Bukkit.getPlayer(Phase8.uuidOf(c.name));
            if (p == null) return -1;
            var inv = p.getOpenInventory().getTopInventory();
            for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) != null && inv.getItem(i).getType().name().equals(type)) return i;
            return -1;
        });
    }

    private String materialAt(McClient c, int slot) throws Exception {
        return t.onMain(() -> {
            Player p = Bukkit.getPlayer(Phase8.uuidOf(c.name));
            var it = p == null ? null : p.getOpenInventory().getTopInventory().getItem(slot);
            return it == null ? "AIR" : it.getType().name();
        });
    }

    // ------------------------------------------------------------------ entry
    private McClient staff;
    private String tMute;
    private String tJail;
    private String tBan;

    void run() throws Exception {
        t.createJail();
        tMute = "LiveAlpha" + run;
        tJail = "LiveBravo" + run;
        tBan = "LiveCharlie" + run;
        try {
            staff = login("LiveStaff13" + run, STAFF);
        } catch (Throwable e) {
            t.check("test clients connect over TCP", false, e.toString());
            return;
        }
        section("fresh-install", this::freshInstall);
        section("reset", () -> reset(staff));
        section("mute-from-create-case", this::muteFromCreateCase);
        section("reset", () -> reset(staff));
        section("jail-from-create-case", this::jailFromCreateCase);
        section("reset", () -> reset(staff));
        section("ban-from-create-case", this::banFromCreateCase);
        section("integrity", this::integrity);
        for (McClient c : everyone) c.close();
    }

    private void freshInstall() throws Exception {
        t.check("a fresh install is in the case workflow", t.ap.core().mode.casework(), String.valueOf(t.ap.core().mode.state()));
        t.check("owner review is OFF on a fresh install (review.enabled: false)", !t.ap.core().settings.review.enabled(), "");
        cmd(staff, "punishments");
        t.check("the staff dashboard opened", waitTitle(staff, "AuraPunishments", 8000) || waitTitle(staff, "Staff", 8000) || !title(staff).isEmpty(), title(staff));
        t.check("its owner-review buttons are out of the way", !has(staff, "Owner review queue") && !has(staff, "My punishments under review"), "");
        cmd(staff, "ap help");
        t.check("/ap help does not list /ap review while review is off", !staff.saw("/ap review"), "");
    }

    /** Open the wizard for this player, choose Harassment, describe it and add evidence (leaves the New case screen open). */
    private void draftCase(String player) throws Exception {
        login(player, "aurapunishments.level.1");
        cmd(staff, "punish " + player);
        t.check("target screen for " + player, waitTitle(staff, player, 8000), title(staff));
        press(staff, "Create Case");
        t.check("the New case screen opened", waitTitle(staff, "New case", 4000), title(staff));
        press(staff, "1. Offense");
        press(staff, "Harassment");
        press(staff, "3. Incident description");
        answer(staff, "the incident description", "Repeatedly harassed other players in the global chat " + run);
        press(staff, "5. Evidence");
        press(staff, "external link");
        answer(staff, "the evidence link", "https://example.com/live-p13-" + player + ".png");
    }

    /** Punishment button -> the usual chooser -> the usual punishment screen -> duration/reason -> "Use this punishment". */
    private void choose(String type, String duration, String reason) throws Exception {
        press(staff, "6. Punishment");
        t.check("the usual list of punishments opened for the new case", waitTitle(staff, "Punishment for the new case", 4000), title(staff));
        press(staff, type);
        t.check("the usual " + type + " screen opened, tied to the new case", waitTitle(staff, "for the new case", 4000), title(staff));
        if (duration != null) {
            press(staff, "Custom duration");
            answer(staff, "the duration", duration);
        }
        press(staff, "Reason");
        answer(staff, "the reason", reason);
        press(staff, "USE THIS PUNISHMENT");
        t.check("back on the New case screen", waitTitle(staff, "New case", 4000), title(staff));
    }

    private void muteFromCreateCase() throws Exception {
        McClient target = login(tMute, "aurapunishments.level.1");
        draftCase(tMute);
        press(staff, "6. Punishment");
        int ipban = slot(staff, "IP Ban");
        t.check("in the punishment list the IP Ban entry is a locked (grey) button", ipban >= 0 && materialAt(staff, ipban).equals("GRAY_DYE"), "slot " + ipban + " " + materialAt(staff, ipban));
        t.check("while Mute is available", slot(staff, "Mute") >= 0 && !materialAt(staff, slot(staff, "Mute")).equals("GRAY_DYE"), "");
        reset(staff);
        cmd(staff, "punish " + tMute);
        waitTitle(staff, tMute, 8000);
        press(staff, "Create Case");
        waitTitle(staff, "New case", 4000);
        press(staff, "1. Offense");
        press(staff, "Harassment");
        press(staff, "3. Incident description");
        answer(staff, "the incident description", "Repeatedly harassed other players in the global chat " + run);
        press(staff, "5. Evidence");
        press(staff, "external link");
        answer(staff, "the evidence link", "https://example.com/live-p13-mute.png");
        choose("Mute", "1h", "Harassing other players in global chat " + run);
        t.check("nothing has been issued yet", count("SELECT COUNT(*) FROM punishments WHERE target_name='" + tMute + "'") == 0 && count("SELECT COUNT(*) FROM cases") == 0, "");
        press(staff, "SUBMIT CASE");
        t.check("one confirmation for the case and the punishment", waitTitle(staff, "confirm", 4000), title(staff));
        press(staff, "CONFIRM");
        t.check("the new case opened", waitTitle(staff, "AP-", 8000), title(staff));
        Punishment mute = latest(tMute, PunishType.MUTE);
        Case cs = mute == null ? null : t.ap.core().db.txSync(cn -> Repo.Cases.get(cn, mute.caseId()));
        t.check("the case and the mute exist together; the mute is ACTIVE, STANDARD (no court), linked to the case", mute != null && cs != null && mute.status() == PunishStatus.ACTIVE
                && mute.source().name().equals("STANDARD") && mute.caseId() == cs.id(), String.valueOf(mute));
        t.check("the case is OPEN with its evidence", cs != null && cs.status() == CaseStatus.OPEN && t.ap.core().db.txSync(cn -> Repo.Evidences.countActive(cn, cs.id())) == 1, String.valueOf(cs));
        t.check("the mute is enforced at once", t.ap.punishments().muteFor(Phase8.uuidOf(tMute)).isPresent(), "");
        target.clear();
        target.chat("talking after the mute " + run);
        t.check("the muted player cannot chat and is told so", waitFor(() -> target.saw("muted"), 6000), target.snapshot().toString());
        t.check("NO owner review was created", count("SELECT COUNT(*) FROM reviews") == 0, "");
        t.check("the case timeline shows the case being opened and the punishment issued", t.ap.core().db.txSync(cn -> Repo.Events.forCase(cn, cs.id())).stream()
                .anyMatch(e -> e.type().equals("PUNISHMENT_ISSUED")), "");
        t.check("no court rows: no custody, no verdict", count("SELECT COUNT(*) FROM custody") == 0 && cs.verdict() == null, "");
    }

    private void jailFromCreateCase() throws Exception {
        draftCase(tJail);
        choose("Jail", "30m", "Griefing the shared farm area " + run);
        press(staff, "SUBMIT CASE");
        t.check("confirmation", waitTitle(staff, "confirm", 4000), title(staff));
        press(staff, "CONFIRM");
        t.check("the jail punishment is recorded as HELD", waitFor(() -> {
            try {
                Punishment p = latest(tJail, PunishType.JAIL);
                return p != null && "HELD".equals(p.jailState());
            } catch (Exception e) {
                return false;
            }
        }, 12000), String.valueOf(latest(tJail, PunishType.JAIL)));
        Punishment j = latest(tJail, PunishType.JAIL);
        t.check("JailReloaded really holds the player (its own API says so)", t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(Phase8.uuidOf(tJail))), "");
        t.check("linked to the new case, not pretrial custody", j != null && j.caseId() != null && j.custodyId() == null && count("SELECT COUNT(*) FROM custody") == 0, String.valueOf(j));
        t.check("still no owner review", count("SELECT COUNT(*) FROM reviews") == 0, "");
        cmd(staff, "ap unjail " + tJail + " Released at the end of the live test " + run);
        t.check("/ap unjail releases the player", waitFor(() -> {
            try {
                return !t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(Phase8.uuidOf(tJail)));
            } catch (Exception e) {
                return false;
            }
        }, 10000), staff.snapshot().toString());
    }

    private void banFromCreateCase() throws Exception {
        McClient target = login(tBan, "aurapunishments.level.1");
        draftCase(tBan);
        choose("Ban", "7d", "Repeated harassment of other players " + run);
        press(staff, "SUBMIT CASE");
        t.check("confirmation", waitTitle(staff, "confirm", 4000), title(staff));
        press(staff, "CONFIRM");
        t.check("the ban was recorded", waitFor(() -> {
            try {
                return latest(tBan, PunishType.BAN) != null;
            } catch (Exception e) {
                return false;
            }
        }, 10000), "");
        Punishment ban = latest(tBan, PunishType.BAN);
        t.check("the case's evidence satisfied the ban's evidence requirement; ban ACTIVE and linked", ban != null && ban.status() == PunishStatus.ACTIVE && ban.caseId() != null, String.valueOf(ban));
        t.check("the online player was disconnected with the ban screen", waitFor(() -> target.disconnected != null, 8000) && target.disconnected.toLowerCase().contains("banned"), String.valueOf(target.disconnected));
        t.check("and no owner review exists for it", count("SELECT COUNT(*) FROM reviews") == 0, "");
    }

    private void integrity() throws Exception {
        t.check("three cases exist, each open, each with exactly one linked punishment", count("SELECT COUNT(*) FROM cases WHERE status='OPEN'") == 3
                && count("SELECT COUNT(*) FROM punishments WHERE case_id IS NOT NULL") == 3, "");
        t.check("the audit chain is intact", t.ap.core().db.txSync(cn -> Repo.Audits.verify(cn)) == -1L, "");
        t.check("no pending operation is left over", count("SELECT COUNT(*) FROM pending_ops WHERE state IN ('PENDING','EXTERNAL_DONE')") == 0, "");
    }
}
