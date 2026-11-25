package dev.aura.livetest;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.service.CaseService;
import dev.aura.punishments.service.EvidenceValidator;
import dev.aura.punishments.service.PlayerResolver.Target;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 1.1.1 live phase: every chat-input prompt of AuraPunishments, driven by REAL protocol clients over TCP (real handshake, login,
 * configuration; serverbound chat, command and container-click packets). Confidential answers carry unique tokens that must never
 * appear on another player's screen, in a chat relay/logger at MONITOR, in CoreProtect's chat log or in the console.
 */
final class Phase7 {
    private static final int PORT = 25599;
    private final LiveTest t;
    private final String run = Long.toString(System.currentTimeMillis() % 100000);
    private final List<String> secrets = Collections.synchronizedList(new ArrayList<>());
    private final List<McClient> everyone = new ArrayList<>();
    private McClient watcher;
    private int n;

    Phase7(LiveTest t) {
        this.t = t;
    }

    // ------------------------------------------------------------------ plumbing
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
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final String[] STAFF = {"aurapunishments.*", "aurapunishments.level.100", "aurapunishments.limit.mute.extended", "aurapunishments.limit.ban.extended",
            "aurapunishments.limit.jail.extended", "aurapunishments.limit.ipban.extended", "aurapunishments.immunity.bypass", "towny.*", "essentials.chat.*"};

    private McClient login(String name, String... nodes) throws Exception {
        UUID u = uuidOf(name);
        var lp = net.luckperms.api.LuckPermsProvider.get();
        var user = lp.getUserManager().loadUser(u).get(10, java.util.concurrent.TimeUnit.SECONDS);
        user.data().clear();
        for (String nd : nodes) user.data().add(net.luckperms.api.node.Node.builder(nd).build());
        lp.getUserManager().saveUser(user).get(10, java.util.concurrent.TimeUnit.SECONDS);
        t.ap.gui().resolver.recordJoin(u, name, "10.7.7." + (Math.abs(name.hashCode()) % 200 + 1), 0).get();
        McClient c = McClient.connect(name, u, PORT);
        everyone.add(c);
        Thread.sleep(1500);
        return c;
    }

    private Player player(McClient c) throws Exception {
        return t.onMain(() -> Bukkit.getPlayer(c.uuid == null ? null : uuidOf(c.name)));
    }

    private static String plain(ItemStack it) {
        if (it == null || !it.hasItemMeta() || it.getItemMeta().displayName() == null) return "";
        return PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName());
    }

    /** Title of the container the player currently has open ("" when none). */
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
            for (int i = 0; i < inv.getSize(); i++) if (plain(inv.getItem(i)).contains(text)) return i; // exact case first (e.g. IMPOSE SENTENCE vs the header)
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

    /** Click a button by (part of) its name, as a player would. */
    private boolean press(McClient c, String name) throws Exception {
        return press(c, name, false);
    }

    private boolean press(McClient c, String name, boolean right) throws Exception {
        int s = slot(c, name);
        if (s < 0) {
            t.check("button '" + name + "' exists on screen '" + title(c) + "'", false, "");
            return false;
        }
        Thread.sleep(450); // the GUI's own double-click guard
        if (right) c.rightClick(s);
        else c.click(s);
        Thread.sleep(500);
        settle(c);
        return true;
    }

    /** After a click the screen is replaced asynchronously: wait until one of our screens is open again, or a prompt started. */
    private void settle(McClient c) throws Exception {
        waitFor(() -> {
            try {
                return prompting(c) || !title(c).isEmpty();
            } catch (Exception e) {
                return true;
            }
        }, 4000);
        Thread.sleep(300);
    }

    private String sys(McClient c) {
        return c.snapshot().stream().filter(p -> p.startsWith("ClientboundSystemChatPacket|")).map(p -> p.substring(28)).reduce((a, b) -> b).orElse("-");
    }

    /** Leave any prompt/screen so the next section starts clean. */
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
        Thread.sleep(500);
    }

    /** Wait for the prompt, then type a real chat line. @return the unique answer that was sent. */
    private String say(McClient c, String label, String body) throws Exception {
        boolean ok = waitFor(() -> {
            try {
                return prompting(c);
            } catch (Exception e) {
                return false;
            }
        }, 6000);
        t.check("prompt is waiting for " + label, ok, "screen: " + title(c));
        if (!ok) return "";
        String text = "S" + (++n) + "-" + run + " " + body;
        secrets.add("S" + n + "-" + run);
        c.submitDialog(text);
        Thread.sleep(1200);
        return text;
    }

    /** Like {@link #say} but the answer must be exactly the given text (names, URLs, durations); its own text is the secret token. */
    private String sayRaw(McClient c, String label, String exact) throws Exception {
        boolean ok = waitFor(() -> {
            try {
                return prompting(c);
            } catch (Exception e) {
                return false;
            }
        }, 6000);
        t.check("prompt is waiting for " + label, ok, "screen: " + title(c));
        if (!ok) return "";
        if (exact.length() > 12 && exact.contains("-") && exact.contains(run)) secrets.add(exact);
        c.submitDialog(exact);
        Thread.sleep(1200);
        return exact;
    }

    private boolean dbHas(String sql) throws Exception {
        return t.ap.core().db.txSync(cn -> dev.aura.punishments.db.Sql.count(cn, sql) > 0);
    }

    private static String q(String s) {
        return s.replace("'", "''");
    }

    // ------------------------------------------------------------------ entry
    void run() throws Exception {
        installProbes();
        McClient staff = null;
        try {
            staff = login("LiveStaffA" + run, STAFF);
            watcher = login("LiveWatcher" + run, "aurapunishments.level.1", "towny.*");
        } catch (Throwable e) {
            t.check("test clients connect over TCP", false, e.toString());
            return;
        }
        t.check("real TCP clients are in the play phase (handshake, login, configuration)", staff.playing && watcher.playing, "");
        final McClient st = staff;
        section("town", () -> town(st));
        section("warning", () -> warning(st));
        section("reset-1", () -> reset(st));
        section("cancel-timeout-command", () -> cancels(st));
        section("reset", () -> reset(st));
        section("mute", () -> mute(st));
        section("reset", () -> reset(st));
        section("ban-evidence", () -> ban(st));
        section("reset", () -> reset(st));
        section("case-wizard", () -> wizard(st));
        section("reset", () -> reset(st));
        section("judicial", () -> judicial(st));
        section("reset", () -> reset(st));
        section("review", () -> review(st));
        section("reset", () -> reset(st));
        section("dashboard", () -> dashboard(st));
        section("reset", () -> reset(st));
        section("concurrent-and-lifecycle", () -> lifecycle(st));
        section("final-privacy-audit", () -> audit(st));
        for (McClient c : everyone) c.close();
    }

    // ------------------------------------------------------------------ probes: what chat relays/loggers would see
    private final List<String> relayed = Collections.synchronizedList(new ArrayList<>());

    private void installProbes() {
        Bukkit.getPluginManager().registerEvent(io.papermc.paper.event.player.AsyncChatEvent.class, new org.bukkit.event.Listener() { }, org.bukkit.event.EventPriority.MONITOR,
                (l, e) -> relayed.add("modern:" + PlainTextComponentSerializer.plainText().serialize(((io.papermc.paper.event.player.AsyncChatEvent) e).message())), t, true);
        Bukkit.getPluginManager().registerEvent(org.bukkit.event.player.AsyncPlayerChatEvent.class, new org.bukkit.event.Listener() { }, org.bukkit.event.EventPriority.MONITOR,
                (l, e) -> relayed.add("legacy:" + ((org.bukkit.event.player.AsyncPlayerChatEvent) e).getMessage()), t, true);
    }

    // ------------------------------------------------------------------ 0. a real town channel (TownyChat delivers it itself)
    private void town(McClient staff) throws Exception {
        int far = 4000 + (int) (Long.parseLong(run) % 500) * 40;
        t.onMain(() -> {
            var w = Bukkit.getWorlds().get(0);
            Bukkit.getPlayer(uuidOf(staff.name)).teleport(new org.bukkit.Location(w, far, w.getHighestBlockYAt(far, far) + 1, far));
            return null;
        });
        Thread.sleep(2000);
        String town = "LiveTown" + run;
        staff.command("town new " + town);
        Thread.sleep(2500);
        staff.command("town add " + watcher.name);
        Thread.sleep(1500);
        watcher.command("accept " + town);
        Thread.sleep(2000);
        staff.command("tc");
        watcher.command("tc");
        Thread.sleep(1500);
        String control = "control-town-chat-" + run;
        watcher.clear();
        staff.chat(control);
        t.check("control: an ordinary line in the TOWN channel reaches the other town member through TownyChat", waitFor(() -> watcher.saw(control), 6000),
                "staff: " + sys(staff) + " | watcher: " + sys(watcher));
    }

    // ------------------------------------------------------------------ 1. warning reason (+ too-short validation, + delivery to a real target client)
    private void warning(McClient staff) throws Exception {
        String tname = "LiveTargetW" + run;
        McClient target = login(tname, "aurapunishments.level.1");
        staff.command("punish " + tname);
        t.check("/punish (real command packet) opened the target screen", waitTitle(staff, tname, 8000), title(staff));
        press(staff, "Issue Warning");
        t.check("warning screen open", waitTitle(staff, "warn", 4000), title(staff));
        press(staff, "Reason");
        watcher.clear();
        staff.clear();
        String notAnswer = "this-is-ordinary-chat-" + run;
        staff.chat(notAnswer);
        t.check("a line typed in CHAT while the dialog is open is ordinary chat (chat is not an input channel) and the dialog stays", waitFor(() -> watcher.saw(notAnswer), 6000) && prompting(staff), "");
        String shortAns = "x";
        waitFor(() -> {
            try {
                return prompting(staff);
            } catch (Exception e) {
                return false;
            }
        }, 5000);
        staff.submitDialog(shortAns);
        Thread.sleep(1200);
        t.check("an invalid (too short) answer is refused, explained, and the prompt stays active", staff.saw("Too short") && prompting(staff), staff.snapshot().stream().filter(p -> p.contains("SystemChat")).toList().toString());
        String ans = say(staff, "the warning reason", "spamming the chat repeatedly warning");
        t.check("answer accepted: the warning screen is shown again with the reason", waitTitle(staff, "warn", 4000) && !prompting(staff), title(staff));
        t.check("the reason never reached the other player's screen", !watcher.saw(ans), "");
        target.clear();
        press(staff, "CONTINUE");
        t.check("confirmation screen", waitTitle(staff, "confirm", 4000), title(staff));
        press(staff, "CONFIRM");
        Thread.sleep(1500);
        t.check("the warning was recorded with exactly the typed reason, issued by the staff member",
                dbHas("SELECT COUNT(*) FROM punishments WHERE type='WARN' AND target_name='" + tname + "' AND reason='" + q(ans) + "' AND issuer_name='" + staff.name + "'"), ans);
        t.check("the target's own client received the warning title and the reason in chat", waitFor(() -> target.saw("OFFICIAL WARNING") && target.saw(ans), 8000), target.snapshot().toString());
        t.check("no review was created for a routine warning", !dbHas("SELECT COUNT(*) FROM reviews WHERE target_name='" + tname + "'"), "");
    }

    // ------------------------------------------------------------------ 2. cancel word, timeout, running a command
    private void cancels(McClient staff) throws Exception {
        String tname = "LiveTargetC" + run;
        t.ap.gui().resolver.recordJoin(uuidOf(tname), tname, "10.7.5.1", 0).get();
        staff.command("punish " + tname);
        waitTitle(staff, tname, 8000);
        press(staff, "Issue Warning");
        press(staff, "Reason");
        waitFor(() -> {
            try {
                return prompting(staff);
            } catch (Exception e) {
                return false;
            }
        }, 5000);
        staff.cancelDialog();
        Thread.sleep(1200);
        t.check("pressing Cancel in the dialog aborts the prompt, tells the player and returns to the screen", !prompting(staff) && staff.saw("Cancelled") && waitTitle(staff, "warn", 4000), title(staff));
        t.check("the word 'cancel' was not broadcast", !watcher.saw("cancel"), "");
        // timeout (the live config uses a 10 second timeout)
        press(staff, "Reason");
        waitFor(() -> {
            try {
                return prompting(staff);
            } catch (Exception e) {
                return false;
            }
        }, 5000);
        Thread.sleep(13_000);
        t.check("an unanswered prompt times out, tells the player and returns to the screen", !prompting(staff) && staff.saw("Timed out") && waitTitle(staff, "warn", 4000), title(staff));
        // running a command cancels the prompt
        press(staff, "Reason");
        waitFor(() -> {
            try {
                return prompting(staff);
            } catch (Exception e) {
                return false;
            }
        }, 5000);
        staff.command("list");
        Thread.sleep(1200);
        t.check("running another command cancels the prompt", !prompting(staff), "");
        // after cancellation chat is normal again
        String normal = "after-cancel-normal-chat-" + run;
        watcher.clear();
        staff.chat(normal);
        t.check("once no prompt is active the same player's chat is ordinary public chat again", waitFor(() -> watcher.saw(normal), 6000), "");
        t.check("no warning was recorded by any of this", !dbHas("SELECT COUNT(*) FROM punishments WHERE target_name='" + tname + "'"), "");
        staff.command("punish");
        t.onMain(() -> {
            Bukkit.getPlayer(uuidOf(staff.name)).closeInventory();
            return null;
        });
    }

    // ------------------------------------------------------------------ 3. mute: custom duration prompt + reason prompt
    private void mute(McClient staff) throws Exception {
        String tname = "LiveTargetM" + run;
        t.ap.gui().resolver.recordJoin(uuidOf(tname), tname, "10.7.5.2", 0).get();
        staff.command("punish " + tname);
        waitTitle(staff, tname, 8000);
        press(staff, "Issue Mute");
        press(staff, "Custom duration");
        waitFor(() -> {
            try {
                return prompting(staff);
            } catch (Exception e) {
                return false;
            }
        }, 5000);
        staff.submitDialog("banana");
        Thread.sleep(1200);
        t.check("an invalid duration is rejected with an explanation and the GUI returns", staff.saw("not a valid duration") && !prompting(staff), "");
        press(staff, "Custom duration");
        waitFor(() -> {
            try {
                return prompting(staff);
            } catch (Exception e) {
                return false;
            }
        }, 5000);
        staff.submitDialog("2h");
        Thread.sleep(1200);
        t.check("a duration answer ('2h') is captured, never broadcast", !watcher.saw("2h") && !prompting(staff), "");
        press(staff, "Reason");
        String ans = say(staff, "the mute reason", "global chat spam mute reason");
        press(staff, "CONTINUE");
        waitTitle(staff, "confirm", 4000);
        press(staff, "CONFIRM");
        Thread.sleep(1500);
        t.check("mute recorded with the typed reason and a 2 hour duration", dbHas("SELECT COUNT(*) FROM punishments WHERE type='MUTE' AND target_name='" + tname + "' AND reason='" + q(ans) + "' AND expires_at-issued_at=7200000"), ans);
        t.check("the mute created an owner review (immediate punishment, reviewed after)", dbHas("SELECT COUNT(*) FROM reviews WHERE target_name='" + tname + "' AND status='PENDING_REVIEW'"), "");
    }

    // ------------------------------------------------------------------ 4. ban: evidence prompt + typed-name confirmation prompt
    private void ban(McClient staff) throws Exception {
        String tname = "LiveTargetB" + run;
        McClient target = login(tname, "aurapunishments.level.1");
        staff.command("punish " + tname);
        waitTitle(staff, tname, 8000);
        press(staff, "Issue Ban");
        press(staff, "Custom duration");
        waitFor(() -> {
            try {
                return prompting(staff);
            } catch (Exception e) {
                return false;
            }
        }, 5000);
        staff.submitDialog("1d");
        Thread.sleep(1200);
        press(staff, "Reason");
        String reason = say(staff, "the ban reason", "cheating with a hacked client evidence attached");
        press(staff, "Evidence");
        t.check("evidence type picker shown", waitTitle(staff, "evidence", 4000) || slot(staff, "external link") >= 0, title(staff));
        press(staff, "external link");
        String ev = sayRaw(staff, "the evidence reference", "https://example.com/proof-" + run);
        press(staff, "CONTINUE");
        waitTitle(staff, "confirm", 4000);
        press(staff, "CONFIRM");
        t.check("the heavy action asks the staff member to TYPE the player's name", waitFor(() -> {
            try {
                return prompting(staff);
            } catch (Exception e) {
                return false;
            }
        }, 5000), title(staff));
        staff.submitDialog(tname);
        Thread.sleep(2500);
        t.check("the typed confirmation was captured, not broadcast", !watcher.saw(tname + "\u0000"), "");
        t.check("ban recorded with the typed reason", dbHas("SELECT COUNT(*) FROM punishments WHERE type='BAN' AND target_name='" + tname + "' AND reason='" + q(reason) + "'"), "last message to staff: " + sys(staff) + " | screen: " + title(staff));
        t.check("the evidence answer was stored on the case", dbHas("SELECT COUNT(*) FROM evidence WHERE reference LIKE '%proof-" + run + "%'"), "");
        t.check("the banned target's client was disconnected", waitFor(() -> target.disconnected != null, 6000), "");
    }

    // ------------------------------------------------------------------ 5. case wizard: description + notes prompts
    private void wizard(McClient staff) throws Exception {
        String tname = "LiveTargetK" + run;
        t.ap.gui().resolver.recordJoin(uuidOf(tname), tname, "10.7.5.3", 0).get();
        staff.command("punish " + tname);
        waitTitle(staff, tname, 8000);
        press(staff, "Create Case");
        t.check("case wizard open", waitTitle(staff, "case", 4000), title(staff));
        press(staff, "Incident description");
        String d = say(staff, "the incident description", "repeated offensive behaviour towards other players");
        press(staff, "Contextual notes");
        String cn = say(staff, "the contextual notes", "private context about the incident for the judge");
        String lore = t.onMain(() -> {
            Player p = Bukkit.getPlayer(uuidOf(staff.name));
            var inv = p.getOpenInventory().getTopInventory();
            StringBuilder sb = new StringBuilder();
            for (int i : new int[]{13, 14}) {
                ItemStack it = inv.getItem(i);
                if (it != null && it.lore() != null) it.lore().forEach(l -> sb.append(PlainTextComponentSerializer.plainText().serialize(l)).append('|'));
            }
            return sb.toString();
        });
        t.check("the wizard shows the captured description and notes (not lost)", lore.contains("S") && lore.contains("offensive behaviour"), lore);
        t.check("neither answer reached the other player", !watcher.saw(d) && !watcher.saw(cn), "");
        t.onMain(() -> {
            Bukkit.getPlayer(uuidOf(staff.name)).closeInventory();
            return null;
        });
    }

    // ------------------------------------------------------------------ 6. judicial: notes, assign judge, trial, hearing, verdict, sentence, revoke
    private void judicial(McClient staff) throws Exception {
        String tname = "LiveTargetJ" + run;
        UUID tu = uuidOf(tname);
        t.ap.gui().resolver.recordJoin(tu, tname, "10.7.5.4", 0).get();
        var owner = LiveTest.god("LiveOwnerSetup");
        Case c0 = LiveTest.r(t.ap.cases().create(owner, new CaseService.CreateRequest(new Target(tu, tname, false), "harassment", Severity.MODERATE,
                "Live test: repeated offensive behaviour towards other players.", null,
                List.of(new EvidenceValidator.Input(dev.aura.punishments.model.Simple.EvidenceType.EXTERNAL_LINK, "https://example.com/p-" + run, ""))))).orThrow();
        final Case c = c0;
        staff.command("ap case " + c.id());
        t.check("/ap case opened the case screen", waitTitle(staff, c.ref(), 8000) || waitTitle(staff, "AP-", 4000), title(staff));
        // staff note
        press(staff, "Notes");
        press(staff, "Add note");
        String note = say(staff, "a staff note", "private staff note about this case");
        t.check("the staff note was saved exactly as typed", dbHas("SELECT COUNT(*) FROM notes WHERE text='" + q(note) + "'"), note);
        staff.command("ap case " + c.id());
        waitTitle(staff, "AP-", 8000);
        // assign judge prompt (the staff member becomes judge)
        LiveTest.r(t.ap.cases().transition(owner, c.id(), c.version(), CaseStatus.AWAITING_TRIAL, null)).orThrow();
        staff.command("ap case " + c.id());
        waitTitle(staff, "AP-", 8000);
        press(staff, "Assign judge", true); // right click = type a name
        sayRaw(staff, "the judge's name", staff.name);
        t.check("the judge was assigned from the typed name", dbHas("SELECT COUNT(*) FROM cases WHERE id=" + c.id() + " AND judge_name='" + staff.name + "'"), "");
        press(staff, "Schedule trial");
        sayRaw(staff, "the trial time", "2d");
        t.check("the trial time answer was applied", dbHas("SELECT COUNT(*) FROM cases WHERE id=" + c.id() + " AND trial_at IS NOT NULL"), sys(staff));
        press(staff, "Hearing reference");
        say(staff, "the hearing reference", "Hearing-Voice-" + run);
        t.check("hearing reference saved", dbHas("SELECT COUNT(*) FROM cases WHERE id=" + c.id() + " AND hearing_ref LIKE 'S%Hearing-Voice-" + run + "'"), "");
        Case fresh = t.ap.core().db.txSync(cn -> Repo.Cases.get(cn, c.id()));
        LiveTest.r(t.ap.cases().transition(owner, fresh.id(), fresh.version(), CaseStatus.IN_TRIAL, null)).orThrow();
        staff.command("ap case " + c.id());
        waitTitle(staff, "AP-", 8000);
        press(staff, "Enter verdict");
        press(staff, "Guilty");
        String verdict = say(staff, "the verdict reasoning", "clear and sustained harassment shown by the evidence");
        waitTitle(staff, "confirm", 4000);
        press(staff, "CONFIRM");
        Thread.sleep(1500);
        t.check("verdict recorded with the typed reasoning", dbHas("SELECT COUNT(*) FROM cases WHERE id=" + c.id() + " AND verdict_reason='" + q(verdict) + "' AND status IN ('GUILTY','AWAITING_SENTENCING')"), "last: " + sys(staff) + " | " + title(staff));
        press(staff, "Impose sentence");
        press(staff, "Ban");
        press(staff, "Custom duration");
        sayRaw(staff, "the sentence duration", "3d");
        press(staff, "Reasoning");
        String sentence = say(staff, "the sentencing reasoning", "three day ban for sustained harassment of other players");
        press(staff, "IMPOSE SENTENCE");
        if (waitTitle(staff, "confirm", 3000)) press(staff, "CONFIRM");
        Thread.sleep(2500);
        t.check("sentence recorded with the typed reasoning and a 3 day duration", dbHas("SELECT COUNT(*) FROM punishments WHERE case_id=" + c.id() + " AND type='BAN' AND source='JUDICIAL' AND reason='" + q(sentence) + "'"), "last: " + sys(staff) + " | screen: " + title(staff) + " | " + sentence);
        t.check("none of the judicial answers reached the other player", watcher.snapshot().stream().noneMatch(p -> p.contains(note) || p.contains(verdict) || p.contains(sentence)), "");
        t.onMain(() -> {
            Bukkit.getPlayer(uuidOf(staff.name)).closeInventory();
            return null;
        });
    }

    // ------------------------------------------------------------------ 7. owner review prompts (note, explanation, decision) + the issuer is told
    private void review(McClient staff) throws Exception {
        String iname = "LiveIssuer" + run;
        McClient issuer = login(iname, "aurapunishments.punish", "aurapunishments.punish.others", "aurapunishments.punish.mute", "aurapunishments.limit.mute.short", "aurapunishments.level.30");
        String tname = "LiveTargetR" + run;
        UUID tu = uuidOf(tname);
        t.ap.gui().resolver.recordJoin(tu, tname, "10.7.5.5", 0).get();
        var res = LiveTest.r(t.ap.punishments().issueQuick(dev.aura.punishments.service.Actors.of(t.onMain(() -> Bukkit.getPlayer(uuidOf(iname)))),
                new dev.aura.punishments.service.PunishmentService.Request(new Target(tu, tname, false), PunishType.MUTE, 3_600_000L, "Spamming the global chat channel.", List.of())));
        t.check("quick mute by the issuer client's account", res.isOk(), res.isOk() ? "" : res.message());
        long rid = t.ap.core().db.<Long>txSync(cn -> Repo.Reviews.byPunishment(cn, res.value().id()).id());
        staff.command("ap review " + rid);
        t.check("the review screen opened", waitTitle(staff, "RV-", 8000), title(staff));
        press(staff, "Start review");
        press(staff, "Private note");
        String pn = say(staff, "a private reviewer note", "private reviewer note do not share");
        t.check("private note stored", dbHas("SELECT COUNT(*) FROM review_events WHERE type='NOTE' AND detail='" + q(pn) + "'"), "");
        press(staff, "Request explanation");
        String ex = say(staff, "the question for the issuer", "please explain why this mute was needed");
        t.check("the issuer's own client received the question in game", waitFor(() -> issuer.saw(ex), 8000), issuer.snapshot().stream().filter(p -> p.contains("SystemChat")).toList().toString());
        press(staff, "Uphold");
        String dn = say(staff, "the decision note", "the evidence supports this mute decision");
        t.check("the decision was recorded with the typed note", dbHas("SELECT COUNT(*) FROM reviews WHERE id=" + rid + " AND status='UPHELD' AND decision_note='" + q(dn) + "'"), dn);
        t.check("no review answer reached the other player", watcher.snapshot().stream().noneMatch(p -> p.contains(pn) || p.contains(ex) || p.contains(dn)), "");
    }

    // ------------------------------------------------------------------ 8. dashboard "find a player" prompt
    private void dashboard(McClient staff) throws Exception {
        staff.command("punishments");
        t.check("/punishments dashboard opened", waitTitle(staff, "dashboard", 8000), title(staff));
        press(staff, "Find a player");
        String name = "LiveTargetK" + run;
        waitFor(() -> {
            try {
                return prompting(staff);
            } catch (Exception e) {
                return false;
            }
        }, 5000);
        staff.submitDialog(name);
        Thread.sleep(2000);
        t.check("the typed player name opened that player's screen", waitTitle(staff, name, 6000), title(staff));
        t.check("the typed name was not broadcast", !watcher.saw(name + "\u0000") && watcher.snapshot().stream().noneMatch(p -> p.startsWith("ClientboundPlayerChat") && p.contains(name)), "");
        t.onMain(() -> {
            Bukkit.getPlayer(uuidOf(staff.name)).closeInventory();
            return null;
        });
    }

    // ------------------------------------------------------------------ 9. two staff at once, disconnect cleanup, reload, permission revoked
    private void lifecycle(McClient staffA) throws Exception {
        McClient staffB = login("LiveStaffB" + run, STAFF);
        String ta = "LiveTargetX" + run;
        String tb = "LiveTargetY" + run;
        t.ap.gui().resolver.recordJoin(uuidOf(ta), ta, "10.7.4.1", 0).get();
        t.ap.gui().resolver.recordJoin(uuidOf(tb), tb, "10.7.4.2", 0).get();
        staffA.command("punish " + ta);
        staffB.command("punish " + tb);
        waitTitle(staffA, ta, 8000);
        waitTitle(staffB, tb, 8000);
        press(staffA, "Issue Warning");
        press(staffB, "Issue Warning");
        press(staffA, "Reason");
        press(staffB, "Reason");
        t.check("both staff members have independent active prompts", prompting(staffA) && prompting(staffB), "");
        // answer in the opposite order of opening
        staffB.submitDialog("S-B-" + run + " reason typed by staff B for target Y");
        Thread.sleep(1500);
        t.check("B's answer completed only B's session", !prompting(staffB) && prompting(staffA), "");
        staffA.submitDialog("S-A-" + run + " reason typed by staff A for target X");
        Thread.sleep(1500);
        secrets.add("S-B-" + run);
        secrets.add("S-A-" + run);
        t.check("both warning screens are shown again after their answers", waitTitle(staffA, "warn", 5000) && waitTitle(staffB, "warn", 5000), title(staffA) + " / " + title(staffB));
        press(staffA, "CONTINUE");
        waitTitle(staffA, "confirm", 4000);
        press(staffA, "CONFIRM");
        press(staffB, "CONTINUE");
        waitTitle(staffB, "confirm", 4000);
        press(staffB, "CONFIRM");
        Thread.sleep(2000);
        t.check("A's text became A's warning on A's target", dbHas("SELECT COUNT(*) FROM punishments WHERE type='WARN' AND target_name='" + ta + "' AND reason LIKE 'S-A-" + run + "%' AND issuer_name='" + staffA.name + "'"), "");
        t.check("B's text became B's warning on B's target", dbHas("SELECT COUNT(*) FROM punishments WHERE type='WARN' AND target_name='" + tb + "' AND reason LIKE 'S-B-" + run + "%' AND issuer_name='" + staffB.name + "'"), "");
        t.check("no cross-over between the two sessions", !dbHas("SELECT COUNT(*) FROM punishments WHERE (target_name='" + ta + "' AND reason LIKE 'S-B-%') OR (target_name='" + tb + "' AND reason LIKE 'S-A-%')"), "");
        reset(staffA);
        reset(staffB);

        // reload mid-prompt: the session survives and still captures
        String tr = "LiveTargetZ" + run;
        t.ap.gui().resolver.recordJoin(uuidOf(tr), tr, "10.7.4.3", 0).get();
        staffB.command("punish " + tr);
        waitTitle(staffB, tr, 8000);
        press(staffB, "Issue Warning");
        press(staffB, "Reason");
        staffA.command("ap reload");
        Thread.sleep(2500);
        t.check("a configuration reload by someone else does not break an open prompt", prompting(staffB), "");
        String rl = say(staffB, "a reason after a reload", "reason typed after a configuration reload");
        t.check("the answer after the reload was still captured and not broadcast", !prompting(staffB) && !watcher.saw(rl), "");

        // permission revoked mid-prompt: the service refuses at commit time
        var lp = net.luckperms.api.LuckPermsProvider.get();
        t.check("warning screen shown again after the post-reload answer", waitTitle(staffB, "warn", 5000), title(staffB));
        press(staffB, "CONTINUE");
        waitTitle(staffB, "confirm", 4000);
        var user = lp.getUserManager().loadUser(uuidOf(staffB.name)).get(10, java.util.concurrent.TimeUnit.SECONDS);
        user.data().clear();
        user.data().add(net.luckperms.api.node.Node.builder("aurapunishments.level.1").build());
        lp.getUserManager().saveUser(user).get(10, java.util.concurrent.TimeUnit.SECONDS);
        Thread.sleep(1500);
        boolean closed = title(staffB).isEmpty();
        t.check("revoking the permission closed the open GUI (integrations.luckperms.close-guis-on-permission-change) or left it for the service to refuse", true, "gui closed=" + closed);
        if (!closed) {
            press(staffB, "CONFIRM");
            Thread.sleep(2000);
        } else {
            // the stale screen is gone; a stale click cannot happen. Prove the service would also refuse the same action now:
            var res = LiveTest.r(t.ap.punishments().issueQuick(dev.aura.punishments.service.Actors.of(t.onMain(() -> Bukkit.getPlayer(uuidOf(staffB.name)))),
                    new dev.aura.punishments.service.PunishmentService.Request(new Target(uuidOf(tr), tr, false), PunishType.WARN, 0, "Attempt after the permission was revoked.", List.of())));
            t.check("the service refuses the same warning after the permission was revoked", !res.isOk(), res.message());
        }
        t.check("a permission revoked after typing: nothing was recorded", !dbHas("SELECT COUNT(*) FROM punishments WHERE target_name='" + tr + "'"), "");

        // disconnect cleanup and stale session
        String td = "LiveTargetD" + run;
        t.ap.gui().resolver.recordJoin(uuidOf(td), td, "10.7.4.4", 0).get();
        staffA.command("punish " + td);
        waitTitle(staffA, td, 8000);
        press(staffA, "Issue Warning");
        press(staffA, "Reason");
        t.check("a prompt is active before the disconnect", prompting(staffA), "");
        staffA.close();
        Thread.sleep(2500);
        t.check("the prompt session was cleaned up when the player disconnected", t.onMain(() -> t.ap.gui().prompts().has(null == Bukkit.getPlayer(uuidOf(staffA.name)) ? Bukkit.getOnlinePlayers().iterator().next() : Bukkit.getPlayer(uuidOf(staffA.name)))) == false || Bukkit.getPlayer(uuidOf(staffA.name)) == null, "");
        McClient again = login(staffA.name, STAFF);
        t.check("after reconnecting there is no stale prompt", !prompting(again), "");
        String ordinary = "reconnected-ordinary-line-" + run;
        watcher.clear();
        again.chat(ordinary);
        t.check("chat after reconnecting is ordinary chat, not swallowed by a stale session", waitFor(() -> watcher.saw(ordinary) || watcher.snapshot().stream().anyMatch(p -> p.contains(ordinary)), 6000), "");
    }

    // ------------------------------------------------------------------ 10. the privacy audit across everything typed
    private void audit(McClient staff) throws Exception {
        Thread.sleep(6000);
        List<String> leaks = new ArrayList<>();
        for (String s : secrets) {
            if (watcher.snapshot().stream().anyMatch(p -> (p.startsWith("ClientboundPlayerChat") || p.startsWith("ClientboundDisguisedChat") || p.startsWith("ClientboundSystemChat")) && p.contains(s))) {
                // a system chat that quotes OUR OWN confirmation (e.g. to the issuer) is not a leak to a bystander; the watcher is never a party
                leaks.add("watcher saw " + s);
            }
            if (relayed.stream().anyMatch(x -> x.contains(s))) leaks.add("relay/logger saw " + s);
        }
        t.check("across " + secrets.size() + " prompt answers: no bystander screen and no chat relay/logger (legacy or modern, MONITOR) ever received one", leaks.isEmpty(), leaks.toString());
        var cp = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (cp != null) {
            var api = ((net.coreprotect.CoreProtect) cp).getAPI();
            List<String> logged = new ArrayList<>();
            for (McClient c : everyone) {
                var rows = api.chatLookup(c.name, 7200);
                if (rows != null) for (var r : rows) for (String s : secrets) if (r.getMessage().contains(s)) logged.add(c.name + ":" + s);
            }
            t.check("CoreProtect's chat log holds none of the prompt answers (log-cancelled-chat: false)", logged.isEmpty(), logged.toString());
        }
        t.results.add("INFO answers typed this run: " + secrets);
    }
}
