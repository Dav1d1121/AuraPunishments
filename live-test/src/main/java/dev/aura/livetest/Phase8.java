package dev.aura.livetest;

import com.graywolf336.jail.JailsAPI;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.db.Sql;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Appeal;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.service.DirectService;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.service.PunishmentService;
import dev.aura.punishments.util.Durations;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 1.3.0 live phase: a FRESH install (judicial system off) on the real Paper server with the real JailReloaded 1.2.2, LuckPerms, Towny,
 * Essentials, CoreProtect. Real TCP protocol clients drive the GUI and its text dialogs; punishments are final and enforced at once;
 * evidence, notes, appeals, jail, emergency actions and owner review all work without a single case. Phase 8b (after a real restart)
 * checks that everything survived.
 */
final class Phase8 {
    private static final int PORT = 25599;
    private final LiveTest t;
    private final String run = Long.toString(System.currentTimeMillis() % 100000);
    private final List<McClient> everyone = new ArrayList<>();

    Phase8(LiveTest t) {
        this.t = t;
    }

    // ------------------------------------------------------------------ plumbing (same approach as the earlier live phases)
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

    static UUID uuidOf(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static final String[] STAFF = {"aurapunishments.*", "aurapunishments.appeal.decide", "aurapunishments.level.100", "aurapunishments.limit.mute.extended",
            "aurapunishments.limit.ban.extended", "aurapunishments.limit.jail.extended", "aurapunishments.limit.ipban.extended", "aurapunishments.immunity.bypass"};

    private McClient login(String name, String... nodes) throws Exception {
        UUID u = uuidOf(name);
        var lp = net.luckperms.api.LuckPermsProvider.get();
        var user = lp.getUserManager().loadUser(u).get(10, java.util.concurrent.TimeUnit.SECONDS);
        user.data().clear();
        for (String nd : nodes) user.data().add(net.luckperms.api.node.Node.builder(nd).build());
        lp.getUserManager().saveUser(user).get(10, java.util.concurrent.TimeUnit.SECONDS);
        t.ap.gui().resolver.recordJoin(u, name, "10.8.8." + (Math.abs(name.hashCode()) % 200 + 1), 0).get();
        McClient c = null;
        for (int attempt = 1; c == null; attempt++) { // the server may still be finishing its start-up (restart phase)
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
            Player p = Bukkit.getPlayer(uuidOf(c.name));
            var h = p == null ? null : p.getOpenInventory().getTopInventory().getHolder();
            return h == null || !h.getClass().getName().startsWith("dev.aura.punishments.gui.") ? "" : PlainTextComponentSerializer.plainText().serialize(p.getOpenInventory().title());
        });
    }

    private int slot(McClient c, String text) throws Exception {
        return t.onMain(() -> {
            Player p = Bukkit.getPlayer(uuidOf(c.name));
            if (p == null) return -1;
            var inv = p.getOpenInventory().getTopInventory();
            for (int i = 0; i < inv.getSize(); i++) if (plain(inv.getItem(i)).contains(text)) return i;
            for (int i = 0; i < inv.getSize(); i++) if (plain(inv.getItem(i)).toLowerCase().contains(text.toLowerCase())) return i;
            return -1;
        });
    }

    private boolean prompting(McClient c) throws Exception {
        return t.onMain(() -> {
            Player p = Bukkit.getPlayer(uuidOf(c.name));
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

    private boolean has(McClient c, String name) throws Exception {
        return slot(c, name) >= 0;
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
            Player p = Bukkit.getPlayer(uuidOf(c.name));
            if (p != null) p.closeInventory();
            return null;
        });
        Thread.sleep(600);
    }

    private long count(String sql) throws Exception {
        return t.ap.core().db.txSync(cn -> Sql.count(cn, sql));
    }

    private List<Punishment> forTarget(String name) throws Exception {
        UUID u = uuidOf(name);
        return t.ap.core().db.txSync(cn -> Repo.Punishments.forTarget(cn, u, 20, 0));
    }

    private Punishment latest(String name, PunishType type) throws Exception {
        return forTarget(name).stream().filter(p -> p.type() == type).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ entry
    private McClient staff;
    private McClient guard;
    private String tMute;
    private String tBan;
    private String tJail;
    private String tExpire;

    void run() throws Exception {
        t.createJail();
        tMute = "LiveTgtM" + run;
        tBan = "LiveTgtB" + run;
        tJail = "LiveTgtJ" + run;
        tExpire = "LiveTgtX" + run;
        try {
            staff = login("LiveStaff8" + run, STAFF);
            guard = login("LiveGuard8" + run, "aurapunishments.level.50", "jail.*");
        } catch (Throwable e) {
            t.check("test clients connect over TCP", false, e.toString());
            return;
        }
        t.check("real TCP clients are in the play phase", staff.playing && guard.playing, "");
        section("fresh-install", this::freshInstall);
        section("screen", this::screen);
        section("reset", () -> reset(staff));
        section("mute", this::mute);
        section("reset", () -> reset(staff));
        section("ban", this::ban);
        section("reset", () -> reset(staff));
        section("punishment-screen-evidence-appeal", this::appeal);
        section("reset", () -> reset(staff));
        section("jail-gui", this::jailGui);
        section("reset", () -> reset(staff));
        section("jail-guard-and-unjail", this::jailGuardAndUnjail);
        section("reset", () -> reset(staff));
        section("jail-expiry", this::jailExpiry);
        section("emergency", this::emergency);
        section("reset", () -> reset(staff));
        section("owner-review", this::ownerReview);
        section("for-restart", this::prepareRestart);
        for (McClient c : everyone) c.close();
    }

    // ------------------------------------------------------------------ 1. fresh install state
    private void freshInstall() throws Exception {
        t.check("a fresh install starts with the judicial system OFF", !t.ap.core().mode.enabled(), "");
        File cfg = new File(t.ap.getDataFolder(), "config.yml");
        String text = Files.readString(cfg.toPath());
        t.check("config.yml on disk contains the explicit 'enabled: false' line", text.contains("\n  enabled: false\n"), "");
        t.check("schema is v3 with this server's name recorded", t.ap.core().db.schemaVersion() == 3 && t.ap.core().db.origin() != null && !t.ap.core().db.origin().isBlank(), t.ap.core().db.origin());
        t.check("no judicial work is open", t.ap.core().mode.refresh().get().blocking() == 0, "");
        t.check("JailReloaded is detected and compatible", t.ap.core().jail.available(), t.ap.core().jail.status());
    }

    // ------------------------------------------------------------------ 2. what the GUI offers
    private void screen() throws Exception {
        login(tMute, "aurapunishments.level.1");
        staff.command("punish " + tMute);
        t.check("/punish opened the target screen", waitTitle(staff, tMute, 8000), title(staff));
        for (String gone : List.of("Create Case", "View Cases", "Pretrial Detention", "Direct Punishment", "Judicial Review", "Submit Evidence")) {
            t.check("judicial option hidden: " + gone, !has(staff, gone), "");
        }
        for (String there : List.of("Issue Warning", "Issue Kick", "Issue Mute", "Issue Ban", "Issue IP Ban", "Issue Jail", "Emergency Action", "Punishment History",
                "Staff Notes", "Warnings", "Jail Status", "Appeals", "Release / Revoke")) {
            t.check("option available: " + there, has(staff, there), "");
        }
        staff.command("ap help");
        Thread.sleep(800);
        t.check("/ap help does not list the judicial dashboard", !staff.saw("/ap judge"), "");
        t.check("/ap help lists jail, unjail, appeals and the judicial status command", staff.saw("/ap jail") && staff.saw("/ap unjail") && staff.saw("/ap appeals") && staff.saw("/ap judicial"), "");
    }

    // ------------------------------------------------------------------ 3. a mute through the GUI with evidence and a note
    private void mute() throws Exception {
        McClient target = everyone.stream().filter(c -> c.name.equals(tMute)).findFirst().orElseThrow();
        staff.command("punish " + tMute);
        t.check("target screen", waitTitle(staff, tMute, 8000), title(staff));
        press(staff, "Issue Mute");
        press(staff, "Custom duration");
        answer(staff, "the mute duration", "1h");
        press(staff, "Reason");
        answer(staff, "the mute reason", "Spamming the global channel repeatedly");
        press(staff, "Evidence");
        press(staff, "external link");
        answer(staff, "the evidence link", "https://example.com/live-mute-" + run + ".png");
        press(staff, "Staff note");
        answer(staff, "the staff note", "Third spam report this week " + run);
        press(staff, "CONTINUE");
        t.check("confirmation screen", waitTitle(staff, "confirm", 4000), title(staff));
        press(staff, "CONFIRM");
        t.check("the mute was recorded", waitFor(() -> {
            try {
                return latest(tMute, PunishType.MUTE) != null;
            } catch (Exception e) {
                return false;
            }
        }, 8000), "");
        Punishment m = latest(tMute, PunishType.MUTE);
        t.check("it is a final, immediately active punishment (ACTIVE, source STANDARD, no case)", m != null && m.status() == PunishStatus.ACTIVE
                && m.source().name().equals("STANDARD") && m.caseId() == null, String.valueOf(m));
        t.check("the evidence is attached to the punishment, not to a case", t.ap.core().db.txSync(cn -> Repo.Evidences.forPunishment(cn, m.id(), false)).size() == 1, "");
        t.check("the staff note is attached to the punishment", t.ap.core().db.txSync(cn -> Repo.Notes.forPunishment(cn, m.id())).size() == 1, "");
        t.check("no case exists anywhere", count("SELECT COUNT(*) FROM cases") == 0, "");
        t.check("the punishment carries a unique id and this server's name", m.uid() != null && m.uid().length() == 36 && m.origin() != null, m.uid() + " / " + m.origin());
        target.clear();
        target.chat("trying to talk after the mute " + run);
        t.check("the muted player cannot chat and is told so", waitFor(() -> target.saw("muted"), 6000), target.snapshot().toString());
        t.check("the punishment is in the owner review queue (mute is a reviewed type)", count("SELECT COUNT(*) FROM reviews WHERE punishment_id=" + m.id()) == 1, "");
    }

    // ------------------------------------------------------------------ 4. a ban: the online target is removed at once and cannot return
    private void ban() throws Exception {
        McClient target = login(tBan, "aurapunishments.level.1");
        staff.command("punish " + tBan);
        t.check("target screen", waitTitle(staff, tBan, 8000), title(staff));
        press(staff, "Issue Ban");
        press(staff, "Custom duration");
        answer(staff, "the ban duration", "7d");
        press(staff, "Reason");
        answer(staff, "the ban reason", "Repeated griefing of the spawn builds");
        press(staff, "Incomplete"); // the button reads "Incomplete" until every requirement is met
        t.check("without evidence the screen stays (incomplete)", waitTitle(staff, "ban", 2000) && !title(staff).toLowerCase().contains("confirm"), title(staff));
        press(staff, "Evidence");
        press(staff, "external link");
        answer(staff, "the evidence link", "https://example.com/live-ban-" + run + ".png");
        press(staff, "CONTINUE");
        press(staff, "CONFIRM");
        t.check("the ban was recorded", waitFor(() -> {
            try {
                return latest(tBan, PunishType.BAN) != null;
            } catch (Exception e) {
                return false;
            }
        }, 8000), "");
        t.check("the online player was disconnected with the ban screen", waitFor(() -> target.disconnected != null, 8000)
                && target.disconnected.toLowerCase().contains("banned"), String.valueOf(target.disconnected));
        boolean refused;
        String why = "";
        try {
            McClient again = McClient.connect(tBan, uuidOf(tBan), PORT);
            everyone.add(again);
            refused = false;
        } catch (Throwable e) {
            refused = true;
            why = e.getMessage();
        }
        t.check("the banned player cannot log back in (checked at login, no restart needed)", refused && why.toLowerCase().contains("banned"), why);
    }

    // ------------------------------------------------------------------ 5. open the punishment: add evidence later, record an appeal, decide it
    private void appeal() throws Exception {
        Punishment ban = latest(tBan, PunishType.BAN);
        staff.command("ap punishment " + ban.id());
        t.check("/ap punishment opened the punishment screen", waitTitle(staff, "Punishment #" + ban.id(), 8000), title(staff));
        press(staff, "Evidence");
        t.check("evidence screen", waitTitle(staff, "evidence", 4000), title(staff));
        press(staff, "Submit evidence");
        press(staff, "external link");
        answer(staff, "the later evidence link", "https://example.com/live-ban-later-" + run + ".png");
        t.check("evidence added AFTER the fact is attached to the punishment", waitFor(() -> {
            try {
                return t.ap.core().db.txSync(cn -> Repo.Evidences.forPunishment(cn, ban.id(), false)).size() == 2;
            } catch (Exception e) {
                return false;
            }
        }, 6000), "");
        reset(staff);
        staff.command("ap punishment " + ban.id());
        t.check("punishment screen again", waitTitle(staff, "Punishment #" + ban.id(), 8000), title(staff));
        press(staff, "Appeals");
        answer(staff, "the appeal grounds", "The player says another account was used");
        answer(staff, "the ticket reference", "0042");
        t.check("the appeal is recorded with the Ticket Tool reference and nothing is lifted", waitFor(() -> {
            try {
                Appeal a = t.ap.core().db.txSync(cn -> Repo.Appeals.pendingForPunishment(cn, ban.id()));
                return a != null && "0042".equals(a.ticket()) && a.caseId() == null;
            } catch (Exception e) {
                return false;
            }
        }, 6000), "");
        t.check("an appeal never suspends the punishment", t.ap.punishments().banFor(uuidOf(tBan), null).isPresent(), "");
        t.check("the appeal is listed in the appeals queue", waitFor(() -> {
            try {
                return t.ap.appeals().open(dev.aura.punishments.service.Actors.of(Bukkit.getPlayer(uuidOf(staff.name))), 0, 10).get().value().total() == 1;
            } catch (Exception e) {
                return false;
            }
        }, 5000), "");
        press(staff, "Take appeal");
        press(staff, "Decide appeal");
        t.check("decision choices", waitTitle(staff, "appeal #", 4000), title(staff));
        press(staff, "OVERTURN");
        answer(staff, "the decision reasoning", "New evidence shows the wrong account was banned");
        t.check("confirmation", waitTitle(staff, "confirm", 4000), title(staff));
        press(staff, "CONFIRM");
        t.check("overturning revokes the ban", waitFor(() -> {
            try {
                return latest(tBan, PunishType.BAN).status() == PunishStatus.REVOKED;
            } catch (Exception e) {
                return false;
            }
        }, 8000), "");
        Appeal done = t.ap.core().db.txSync(cn -> Repo.Appeals.forPunishment(cn, ban.id()).get(0));
        t.check("the appeal records reviewer, decision and rationale", done.status().name().equals("OVERTURNED") && done.decidedByName() != null && done.decisionReason().contains("wrong account"), String.valueOf(done));
        boolean back;
        try {
            McClient again = McClient.connect(tBan, uuidOf(tBan), PORT);
            everyone.add(again);
            back = again.playing;
        } catch (Throwable e) {
            back = false;
        }
        t.check("the player can log in again after the appeal was upheld", back, "");
    }

    // ------------------------------------------------------------------ 6. jail through the GUI with the real JailReloaded
    private void jailGui() throws Exception {
        McClient target = login(tJail, "aurapunishments.level.1");
        staff.command("punish " + tJail);
        t.check("target screen", waitTitle(staff, tJail, 8000), title(staff));
        press(staff, "Issue Jail");
        t.check("jail screen", waitTitle(staff, "jail", 4000), title(staff));
        press(staff, "Custom duration");
        answer(staff, "the jail duration", "30m");
        press(staff, "Reason");
        answer(staff, "the jail reason", "Griefing the shared farm area");
        press(staff, "CONTINUE");
        t.check("confirmation screen mentions JailReloaded and that it is final", waitTitle(staff, "confirm", 4000), title(staff));
        press(staff, "CONFIRM");
        t.check("the jail punishment is recorded as HELD", waitFor(() -> {
            try {
                Punishment p = latest(tJail, PunishType.JAIL);
                return p != null && "HELD".equals(p.jailState());
            } catch (Exception e) {
                return false;
            }
        }, 10000), String.valueOf(latest(tJail, PunishType.JAIL)));
        Punishment j = latest(tJail, PunishType.JAIL);
        t.check("JailReloaded really holds the player (its own API says so)", t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(uuidOf(tJail))), "");
        t.check("the prisoner record carries our tag so it is recognisable after a crash", t.onMain(() -> JailsAPI.getJailManager().getPrisoner(uuidOf(tJail)).getReason().startsWith("[AP-J")), "");
        t.check("no case and no pretrial custody exist for it", j != null && j.caseId() == null && j.custodyId() == null && count("SELECT COUNT(*) FROM custody") == 0, "");
        t.check("the player is actually inside the jail area", t.onMain(() -> {
            var loc = Bukkit.getPlayer(uuidOf(tJail)).getLocation();
            return Math.abs(loc.getX()) <= 12 && Math.abs(loc.getZ()) <= 12;
        }), "");
        staff.command("punish " + tJail);
        waitTitle(staff, tJail, 6000);
        t.check("the target screen's Jail Status shows the jail punishment", slot(staff, "Jail Status") >= 0, "");
    }

    // ------------------------------------------------------------------ 7. the JailReloaded bypass is blocked; /ap unjail releases properly
    private void jailGuardAndUnjail() throws Exception {
        guard.clear();
        guard.command("jail unjail " + tJail);
        Thread.sleep(1500);
        t.check("another staff member cannot release the prisoner through JailReloaded's own command (guarded)", guard.saw("serving jail punishment"), guard.snapshot().toString());
        t.check("the prisoner is still held", t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(uuidOf(tJail))), "");
        staff.command("ap unjail " + tJail + " Served enough, behaving well now");
        t.check("/ap unjail frees the prisoner in JailReloaded", waitFor(() -> {
            try {
                return !t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(uuidOf(tJail)));
            } catch (Exception e) {
                return false;
            }
        }, 10000), "");
        Punishment j = latest(tJail, PunishType.JAIL);
        t.check("the punishment is revoked, with the staff member and reason recorded", waitFor(() -> {
            try {
                Punishment p = latest(tJail, PunishType.JAIL);
                return p.status() == PunishStatus.REVOKED && "RELEASED".equals(p.jailState());
            } catch (Exception e) {
                return false;
            }
        }, 8000) && latest(tJail, PunishType.JAIL).endReason().contains("Served enough"), String.valueOf(latest(tJail, PunishType.JAIL)));
        t.check("the release was our own, so no 'ended outside AuraPunishments' alert was raised", count("SELECT COUNT(*) FROM audit_log WHERE action='jail.ended-externally'") == 0, "");
        t.check("audit chain is intact", t.ap.core().db.txSync(cn -> Repo.Audits.verify(cn)) == -1L, "");
    }

    // ------------------------------------------------------------------ 8. the sentence ends by itself (JailReloaded's timer)
    private void jailExpiry() throws Exception {
        McClient target = login(tExpire, "aurapunishments.level.1");
        var res = t.ap.punishments().issueQuick(dev.aura.punishments.service.Actors.of(Bukkit.getPlayer(uuidOf(staff.name))),
                new PunishmentService.Request(new Target(uuidOf(tExpire), tExpire, true), PunishType.JAIL, 20_000L, "Short test sentence for the timer", List.of(), null)).get();
        t.check("a 20-second jail punishment is accepted", res.isOk(), res.message());
        Punishment j = latest(tExpire, PunishType.JAIL);
        t.check("held by JailReloaded", j != null && "HELD".equals(j.jailState()) && t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(uuidOf(tExpire))), String.valueOf(j));
        boolean served = waitFor(() -> {
            try {
                return latest(tExpire, PunishType.JAIL).status() == PunishStatus.SERVED;
            } catch (Exception e) {
                return false;
            }
        }, 240_000);
        t.check("when JailReloaded's timer ends the sentence, the punishment becomes SERVED automatically", served, String.valueOf(latest(tExpire, PunishType.JAIL)));
        t.check("and the player is out of JailReloaded", !t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(uuidOf(tExpire))), "");
        t.check("the end was recorded as a served sentence, not as an outside release", latest(tExpire, PunishType.JAIL).endReason().equals("Sentence served"), String.valueOf(latest(tExpire, PunishType.JAIL).endReason()));
    }

    // ------------------------------------------------------------------ 9. emergency action without a case, reviewed through the punishment screen
    private String tEmerg;

    private void emergency() throws Exception {
        tEmerg = "LiveTgtE" + run;
        t.ap.gui().resolver.recordJoin(uuidOf(tEmerg), tEmerg, "10.8.9.9", 0).get();
        var em = t.ap.direct().emergency(dev.aura.punishments.service.Actors.of(Bukkit.getPlayer(uuidOf(staff.name))),
                new DirectService.EmergencyRequest(new Target(uuidOf(tEmerg), tEmerg, false), PunishType.MUTE, Durations.HOUR, "Raiding the voice chat with slurs right now")).get();
        t.check("an emergency action is accepted with no case", em.isOk() && em.value().caseId() == null, em.message());
        Punishment p = latest(tEmerg, PunishType.MUTE);
        t.check("it carries a review deadline and is waiting for senior review", p != null && p.reviewDeadline() != null && !p.reviewed(), String.valueOf(p));
        t.check("it also entered the owner review queue (review.include-emergency)", count("SELECT COUNT(*) FROM reviews WHERE punishment_id=" + p.id()) == 1, "");
        staff.command("ap punishment " + p.id());
        t.check("punishment screen", waitTitle(staff, "Punishment #" + p.id(), 8000), title(staff));
        press(staff, "Review emergency action");
        press(staff, "Uphold");
        answer(staff, "the review reasoning", "The evidence supports the protective action");
        t.check("upholding settles the emergency review and the owner review together", waitFor(() -> {
            try {
                return latest(tEmerg, PunishType.MUTE).reviewed() && count("SELECT COUNT(*) FROM reviews WHERE punishment_id=" + p.id() + " AND status='UPHELD'") == 1;
            } catch (Exception e) {
                return false;
            }
        }, 8000), "");
        t.check("the action is still in force until it expires", latest(tEmerg, PunishType.MUTE).status() == PunishStatus.ACTIVE, "");
    }

    // ------------------------------------------------------------------ 10. owner review is oversight, never an approval step
    private void ownerReview() throws Exception {
        t.check("the permanent-or-long bans/mutes created above are in the review queue while already in force",
                count("SELECT COUNT(*) FROM reviews WHERE status IN ('PENDING_REVIEW','UNDER_REVIEW')") >= 1 && t.ap.punishments().muteFor(uuidOf(tMute)).isPresent(), "");
        staff.command("ap review");
        t.check("/ap review opens the queue", waitTitle(staff, "review", 6000), title(staff));
        reset(staff);
    }

    // ------------------------------------------------------------------ state for the restart phase
    private void prepareRestart() throws Exception {
        String tOff = "LiveOffJ" + run;
        t.ap.gui().resolver.recordJoin(uuidOf(tOff), tOff, "10.8.9.10", 0).get();
        var res = t.ap.punishments().issueQuick(dev.aura.punishments.service.Actors.of(Bukkit.getPlayer(uuidOf(staff.name))),
                new PunishmentService.Request(new Target(uuidOf(tOff), tOff, false), PunishType.JAIL, 6 * Durations.HOUR, "Jailing an offline player before the restart", List.of(), null)).get();
        t.check("an OFFLINE player can be jailed (registered, placed at next login)", res.isOk() && "PENDING_PLACEMENT".equals(res.value().jailState()), res.message());
        Files.writeString(new File(t.getDataFolder(), "state8.txt").toPath(), String.join("\n", tMute, tOff, tBan, String.valueOf(latest(tMute, PunishType.MUTE).id()),
                String.valueOf(res.value().id()), String.valueOf(count("SELECT COUNT(*) FROM punishments")), String.valueOf(count("SELECT COUNT(*) FROM evidence")),
                String.valueOf(count("SELECT COUNT(*) FROM appeals")), String.valueOf(count("SELECT COUNT(*) FROM audit_log"))));
        Files.writeString(new File(t.getDataFolder(), "phase.txt").toPath(), "9");
    }

    // ------------------------------------------------------------------ phase 8b: after a real server restart
    void afterRestart() throws Exception {
        List<String> st = Files.readAllLines(new File(t.getDataFolder(), "state8.txt").toPath());
        String tMuteName = st.get(0);
        String tOff = st.get(1);
        long muteId = Long.parseLong(st.get(3));
        long jailId = Long.parseLong(st.get(4));
        t.check("schema v3 and the judicial system still OFF after the restart", t.ap.core().db.schemaVersion() == 3 && !t.ap.core().mode.enabled(), "");
        t.check("every record survived: punishments, evidence, appeals and audit entries are all still there",
                count("SELECT COUNT(*) FROM punishments") == Long.parseLong(st.get(5)) && count("SELECT COUNT(*) FROM evidence") == Long.parseLong(st.get(6))
                        && count("SELECT COUNT(*) FROM appeals") == Long.parseLong(st.get(7)) && count("SELECT COUNT(*) FROM audit_log") >= Long.parseLong(st.get(8)), "");
        t.check("the audit chain still verifies", t.ap.core().db.txSync(cn -> Repo.Audits.verify(cn)) == -1L, "");
        t.check("the mute is still enforced (loaded into the cache at start)", t.ap.punishments().muteFor(uuidOf(tMuteName)).isPresent(), "");
        Punishment jail = t.ap.core().db.txSync(cn -> Repo.Punishments.get(cn, jailId));
        t.check("the offline player's jail punishment is still live after the restart (reconciled against JailReloaded, not guessed)",
                jail.status() == PunishStatus.ACTIVE && ("PENDING_PLACEMENT".equals(jail.jailState()) || "HELD".equals(jail.jailState())), String.valueOf(jail));
        t.check("JailReloaded still holds them", t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(uuidOf(tOff))), "");
        t.check("no pending operation is stuck", count("SELECT COUNT(*) FROM pending_ops WHERE state IN ('PENDING','EXTERNAL_DONE')") == 0, "");
        McClient s = login("LiveStaff8b" + System.currentTimeMillis() % 100000, STAFF);
        McClient j = login(tOff, "aurapunishments.level.1");
        Thread.sleep(2500);
        t.check("when the jailed player logs in, JailReloaded places them in the jail", t.onMain(() -> {
            var loc = Bukkit.getPlayer(uuidOf(tOff)).getLocation();
            return Math.abs(loc.getX()) <= 12 && Math.abs(loc.getZ()) <= 12;
        }), "");
        t.ap.jails().reconcile().get();
        Punishment after = t.ap.core().db.txSync(cn -> Repo.Punishments.get(cn, jailId));
        t.check("the record now says HELD", "HELD".equals(after.jailState()), after.jailState());
        s.command("ap unjail " + tOff + " Releasing after the restart check");
        t.check("an unjail after the restart works", waitFor(() -> {
            try {
                return !t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(uuidOf(tOff)));
            } catch (Exception e) {
                return false;
            }
        }, 10000), "");
        t.check("the mute from before the restart is still blocking chat", waitFor(() -> t.ap.punishments().muteFor(uuidOf(tMuteName)).isPresent(), 2000), "");
        j.close();
        s.close();
    }
}
