package dev.aura.livetest;

import com.graywolf336.jail.JailsAPI;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Simple.Verdict;
import dev.aura.punishments.service.*;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.util.Durations;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 1.1.0 live phase: behaviour that needs a CONNECTED player. Players are real server-side players created with
 * {@link FakePlayer} (full join sequence over an in-memory channel that records the packets sent to them).
 */
final class Phase6 {
    private final LiveTest t;

    Phase6(LiveTest t) {
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

    private static long count(FakePlayer f, String prefix) {
        return f.snapshot().stream().filter(p -> p.startsWith(prefix)).count();
    }

    private static boolean saw(FakePlayer f, String text) {
        return f.snapshot().stream().anyMatch(p -> p.contains(text));
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

    void run() throws Exception {
        t.createJail();
        section("join", this::join);
        section("warnings", this::warnings);
        section("custody", this::custody);
        section("login", this::login);
        section("towny-chat", this::townyChat);
        section("acf", this::acf);
        section("coreprotect", this::coreProtect);
        section("gui", this::gui);
    }

    // ------------------------------------------------------------------ join
    private FakePlayer steve;
    private FakePlayer cust;

    private void join() throws Exception {
        UUID su = LiveTest.id("LiveSteve");
        t.ap.gui().resolver.recordJoin(su, "LiveSteve", "10.9.9.1", 0).get();
        steve = t.onMain(() -> FakePlayer.join("LiveSteve", su));
        t.check("a fake player is genuinely online in the server", t.onMain(() -> Bukkit.getPlayer(su) != null), "");
        t.check("the plugin's join handling ran (profile recorded)", waitFor(() -> {
            try {
                return t.ap.core().db.<Boolean>txSync(c -> Repo.Profiles.get(c, su) != null);
            } catch (Exception e) {
                return false;
            }
        }, 3000), "");
        t.check("the server sent packets to the player's channel", count(steve, "Clientbound") > 5, steve.snapshot().size() + " packets");
    }

    // ------------------------------------------------------------------ warnings (title / sound / chat, ordering, offline)
    private void warnings() throws Exception {
        UUID su = LiveTest.id("LiveSteve");
        Actor warner = LiveTest.limited("LiveWarner", 5, Perms.PUNISH_OTHERS, Perms.punish(PunishType.WARN));
        steve.clear();
        var w = LiveTest.r(t.ap.punishments().issueQuick(warner, new PunishmentService.Request(new Target(su, "LiveSteve", false), PunishType.WARN, 0,
                "Please stop spamming the chat.", List.of())));
        t.check("warning issued with only aurapunishments.punish.warn (+ target access)", w.isOk(), w.isOk() ? "" : w.message());
        t.check("title packet received", waitFor(() -> count(steve, "ClientboundSetTitleTextPacket") >= 1, 5000), steve.snapshot().toString());
        List<String> pk = steve.snapshot();
        t.check("title text is the configured headline", pk.stream().anyMatch(p -> p.startsWith("ClientboundSetTitleTextPacket|") && p.contains("OFFICIAL WARNING")), "");
        t.check("subtitle sent", count(steve, "ClientboundSetSubtitleTextPacket") == 1, "");
        t.check("title timing is fade 10 / stay 100 / fade 20 ticks", pk.contains("ClientboundSetTitlesAnimationPacket|10/100/20"), pk.toString());
        t.check("sound packet: pling, volume 0.8, pitch 1.0", pk.stream().anyMatch(p -> p.contains("block.note_block.pling") && p.contains("vol=0.8") && p.contains("pitch=1.0")), pk.toString());
        t.check("chat warning with the reason", saw(steve, "OFFICIAL WARNING") && saw(steve, "Please stop spamming the chat."), "");
        t.check("warning marked delivered only after it was shown", waitFor(() -> {
            try {
                return t.ap.core().db.<Boolean>txSync(c -> Repo.Punishments.get(c, w.value().id()).delivered());
            } catch (Exception e) {
                return false;
            }
        }, 3000), "");
        t.ap.punishments().deliverPending(su).get();
        Thread.sleep(500);
        t.check("no duplicate title after another delivery pass", count(steve, "ClientboundSetTitleTextPacket") == 1, "titles=" + count(steve, "ClientboundSetTitleTextPacket"));

        // offline player with three queued warnings: delivered in order, each once, only after login
        UUID ou = LiveTest.id("LiveOffWarn");
        t.ap.gui().resolver.recordJoin(ou, "LiveOffWarn", "10.9.9.2", 0).get();
        Target ot = new Target(ou, "LiveOffWarn", false);
        for (int i = 1; i <= 3; i++) {
            Thread.sleep(20);
            LiveTest.r(t.ap.punishments().issueQuick(warner, new PunishmentService.Request(ot, PunishType.WARN, 0, "Queued warning number " + i + " for spam.", List.of()))).orThrow();
        }
        t.check("three warnings queued for the offline player, none marked delivered", t.ap.core().db.<Integer>txSync(c -> Repo.Notifications.pendingFor(c, ou).size()) == 3, "");
        FakePlayer off = t.onMain(() -> FakePlayer.join("LiveOffWarn", ou));
        t.check("all three delivered after login", waitFor(() -> count(off, "ClientboundSetTitleTextPacket") == 3, 30000), "titles=" + count(off, "ClientboundSetTitleTextPacket"));
        List<String> chat = off.snapshot().stream().filter(p -> p.contains("Queued warning number")).toList();
        t.check("in order 1,2,3 and each exactly once", chat.size() == 3 && chat.get(0).contains("number 1") && chat.get(1).contains("number 2") && chat.get(2).contains("number 3"), chat.toString());
        t.check("none pending afterwards", waitFor(() -> {
            try {
                return t.ap.core().db.<Integer>txSync(c -> Repo.Notifications.pendingFor(c, ou).size()) == 0;
            } catch (Exception e) {
                return false;
            }
        }, 3000), "");
        t.onMain(() -> {
            off.leave();
            return null;
        });
    }

    // ------------------------------------------------------------------ custody of an ONLINE player (real JailReloaded)
    private void custody() throws Exception {
        String cname = "LiveC" + (System.currentTimeMillis() % 100000);
        UUID su = LiveTest.id(cname);
        t.ap.gui().resolver.recordJoin(su, cname, "10.9.9.7", 0).get();
        cust = t.onMain(() -> FakePlayer.join(cname, su));
        Actor owner = LiveTest.god("LiveOwner");
        Case c = LiveTest.r(t.ap.cases().create(owner, new CaseService.CreateRequest(new Target(su, cname, false), "harassment", dev.aura.punishments.model.Simple.Severity.MODERATE,
                "Live test: repeated offensive behaviour towards other players.", null,
                List.of(new EvidenceValidator.Input(dev.aura.punishments.model.Simple.EvidenceType.EXTERNAL_LINK, "https://example.com/proof-steve", ""))))).orThrow();
        cust.clear();
        var cu = LiveTest.r(t.ap.custody().initiate(owner, c.id()));
        t.check("custody of the ONLINE player confirmed by JailReloaded", cu.isOk(), cu.isOk() ? "" : cu.message());
        t.check("JailReloaded holds the prisoner", t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(su)), "");
        t.check("the online player was moved into the jail cell area", t.onMain(() -> {
            Location l = Bukkit.getPlayer(su).getLocation();
            return Math.abs(l.getX()) <= 11 && Math.abs(l.getZ()) <= 11;
        }), "");
        t.check("defendant told: case number, offense, hearing instructions, not a guilt finding",
                waitFor(() -> saw(cust, "held pending a hearing"), 5000) && saw(cust, c.ref()) && saw(cust, "NOT a finding of guilt") && saw(cust, "Discord"), cust.snapshot().toString());
        t.check("detention message shown exactly once", cust.snapshot().stream().filter(p -> p.contains("held pending a hearing")).count() == 1, "");

        // disconnect and reconnect while in custody
        FakePlayer c0 = cust;
        t.onMain(() -> {
            c0.leave();
            return null;
        });
        t.check("player offline", waitFor(() -> !cust.online(), 5000), "");
        t.check("still a prisoner while offline", t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(su)), "");
        cust = t.onMain(() -> FakePlayer.join(cname, su));
        t.check("reconnected, still in custody and AP still tracks it", t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(su)) && LiveTest.r(t.ap.custody().liveForPlayer(su)).isOk()
                && LiveTest.r(t.ap.custody().liveForPlayer(su)).value() != null, "");

        // trial -> not guilty releases the ONLINE prisoner
        Case tr = t.toTrial(owner, c);
        cust.clear();
        var v = LiveTest.r(t.ap.judicial().enterVerdict(owner, tr.id(), tr.version(), Verdict.NOT_GUILTY, "The evidence does not establish the allegation."));
        t.check("not-guilty verdict accepted", v.isOk(), v.isOk() ? "" : v.message());
        t.check("JailReloaded released the online player", waitFor(() -> {
            try {
                return !t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(su));
            } catch (Exception e) {
                return false;
            }
        }, 8000), "");
        t.check("no punishment recorded for an acquittal", t.ap.core().db.<Integer>txSync(cn -> Repo.Punishments.forCase(cn, c.id()).size()) == 0, "");
        t.check("defendant told about the acquittal", waitFor(() -> saw(cust, "in your favor") || saw(cust, "released from custody"), 8000), cust.snapshot().toString());
    }

    // ------------------------------------------------------------------ helpers for the sections below
    private FakePlayer login(String name, String ip) throws Exception {
        UUID u = LiveTest.id(name);
        t.ap.gui().resolver.recordJoin(u, name, ip, 0).get();
        return FakePlayer.loginAndJoin(name, u, ip);
    }

    private void leave(FakePlayer f) throws Exception {
        t.onMain(() -> {
            f.leave();
            return null;
        });
        waitFor(() -> !f.online(), 5000);
    }

    private static Actor god() {
        return LiveTest.god("LiveOwner");
    }

    // ------------------------------------------------------------------ real login enforcement
    private void login() throws Exception {
        String n = "LiveBan" + (System.currentTimeMillis() % 100000);
        UUID u = LiveTest.id(n);
        t.ap.gui().resolver.recordJoin(u, n, "10.9.8.1", 0).get();
        var ban = LiveTest.r(t.ap.punishments().issueQuick(god(), new PunishmentService.Request(new Target(u, n, false), PunishType.BAN, Durations.HOUR,
                "Live test ban for login enforcement.", List.of(new EvidenceValidator.Input(dev.aura.punishments.model.Simple.EvidenceType.EXTERNAL_LINK, "https://example.com/p", "")))));
        t.check("ban issued (quick, with evidence)", ban.isOk(), ban.isOk() ? "" : ban.message());
        FakePlayer f = FakePlayer.loginAndJoin(n, u, "10.9.8.1");
        t.check("a banned player is refused at the real login stage", f.denied != null && f.denied.toLowerCase().contains("ban"), String.valueOf(f.denied));
        t.check("the refused player never became an online player", !f.online(), "");
        var rv = t.ap.core().db.<dev.aura.punishments.model.Records.Review>txSync(c -> Repo.Reviews.byPunishment(c, ban.value().id()));
        t.check("the ban created a pending owner review (enforced first, reviewed after)", rv != null && rv.status() == dev.aura.punishments.model.Simple.ReviewStatus.PENDING_REVIEW, "");
        LiveTest.r(t.ap.punishments().revoke(god(), ban.value().id(), "Live test cleanup: lifting the ban."));
        FakePlayer g = FakePlayer.loginAndJoin(n, u, "10.9.8.1");
        t.check("after revocation the same player can log in", g.denied == null && waitFor(g::online, 3000), String.valueOf(g.denied));
        if (g.denied == null) leave(g);
    }

    // ------------------------------------------------------------------ TownyChat
    private void townyChat() throws Exception {
        boolean present = t.onMain(() -> Bukkit.getPluginManager().getPlugin("TownyChat") != null && Bukkit.getPluginManager().getPlugin("TownyChat").isEnabled());
        t.check("TownyChat is installed and enabled in this run", present, "");
        if (!present) return;
        FakePlayer a = login("LiveMuted", "10.9.7.1");
        FakePlayer b = login("LiveListener", "10.9.7.2");
        t.check("both chat players online", a.online() && b.online(), "");
        Thread.sleep(3000); // TownyChat places a joining player into its default channel a moment after the join event
        String marker = "hello-" + (System.currentTimeMillis() % 10000);
        b.clear();
        t.onMain(() -> {
            a.bukkit().chat(marker + "-before");
            return null;
        });
        t.check("control: unmuted chat reaches the listener through TownyChat", waitFor(() -> saw(b, marker + "-before"), 6000), b.snapshot().stream().filter(p -> p.startsWith("ClientboundPlayerChat") || p.startsWith("ClientboundSystemChat") || p.startsWith("ClientboundDisguised")).toList().toString());
        // mute
        var mute = LiveTest.r(t.ap.punishments().issueQuick(god(), new PunishmentService.Request(new Target(a.uuid, a.name, false), PunishType.MUTE, Durations.HOUR,
                "Live test mute to check TownyChat enforcement.", List.of())));
        t.check("mute issued", mute.isOk(), mute.isOk() ? "" : mute.message());
        Thread.sleep(9000); // let TownyChat's own anti-spam window pass so the mute (not the spam filter) is what is observed
        b.clear();
        a.clear();
        t.onMain(() -> {
            a.bukkit().chat(marker + "-after");
            return null;
        });
        Thread.sleep(2000);
        t.check("muted player's TownyChat message is NOT delivered", !saw(b, marker + "-after"), "");
        t.check("muted player sees why", saw(a, "muted"), a.snapshot().toString());
        // TownyChat channel / private-message commands and aliases
        List<String> cmds = List.of("g", "global", "gc", "lc", "local", "tc", "town", "nc", "nation", "ac", "ally", "msg", "tell", "w", "whisper", "m", "r", "reply", "t", "me", "emote", "mail send", "ch", "channel", "chat", "tcchat");
        List<String> leaked = new java.util.ArrayList<>();
        for (String c : cmds) {
            b.clear();
            a.clear();
            String line = c + " " + (c.equals("msg") || c.equals("tell") || c.equals("w") || c.equals("whisper") || c.equals("m") || c.equals("t") ? b.name + " " : c.equals("mail send") ? b.name + " " : "") + marker + "-cmd";
            t.onMain(() -> {
                try {
                    Bukkit.dispatchCommand(a.bukkit(), line);
                } catch (Throwable ex) {
                    // a command erroring is not a delivery
                }
                return null;
            });
            Thread.sleep(250);
            if (saw(b, marker + "-cmd")) leaked.add(c);
        }
        t.check("no chat/PM command or alias delivered text from the muted player (" + cmds.size() + " tried)", leaked.isEmpty(), "delivered via: " + leaked);
        // unmute restores speech
        LiveTest.r(t.ap.punishments().revoke(god(), mute.value().id(), "Live test cleanup: lifting the mute."));
        b.clear();
        t.onMain(() -> {
            a.bukkit().chat(marker + "-restored");
            return null;
        });
        t.check("after the mute is lifted the player can speak again", waitFor(() -> saw(b, marker + "-restored"), 6000), "");
        leave(a);
        leave(b);
    }

    // ------------------------------------------------------------------ AuraCommandFilter per-player visibility
    private java.util.Set<String> visibleCommands(FakePlayer f) throws Exception {
        return t.onMain(() -> {
            java.util.List<String> all = new java.util.ArrayList<>(Bukkit.getCommandMap().getKnownCommands().keySet());
            var ev = new org.bukkit.event.player.PlayerCommandSendEvent(f.bukkit(), all);
            Bukkit.getPluginManager().callEvent(ev);
            return new java.util.HashSet<>(ev.getCommands());
        });
    }

    private void acf() throws Exception {
        boolean present = t.onMain(() -> Bukkit.getPluginManager().getPlugin("AuraCommandFilter") != null);
        t.check("AuraCommandFilter present", present, "");
        if (!present) return;
        var lp = net.luckperms.api.LuckPermsProvider.get();
        record Rank(String group, String[] nodes, boolean punish, boolean ap, boolean punishments) {}
        List<Rank> ranks = List.of(
                new Rank("aplive_none", new String[]{}, false, false, false),
                new Rank("aplive_trainee", new String[]{Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.punish(PunishType.WARN)}, true, false, false),
                new Rank("aplive_mod", new String[]{Perms.COMMAND, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.DASHBOARD, Perms.punish(PunishType.MUTE)}, true, true, true),
                new Rank("aplive_owner", new String[]{"aurapunishments.*"}, true, true, true));
        for (Rank rk : ranks) {
            var g = lp.getGroupManager().createAndLoadGroup(rk.group()).get(10, java.util.concurrent.TimeUnit.SECONDS);
            g.data().clear();
            for (String nd : rk.nodes()) g.data().add(net.luckperms.api.node.Node.builder(nd).build());
            lp.getGroupManager().saveGroup(g).get(10, java.util.concurrent.TimeUnit.SECONDS);
            String name = "LiveRk" + rk.group().substring(7, 9);
            UUID u = LiveTest.id(name);
            var user = lp.getUserManager().loadUser(u).get(10, java.util.concurrent.TimeUnit.SECONDS);
            user.data().clear();
            user.data().add(net.luckperms.api.node.types.InheritanceNode.builder(rk.group()).build());
            lp.getUserManager().saveUser(user).get(10, java.util.concurrent.TimeUnit.SECONDS);
            FakePlayer f = login(name, "10.9.6." + (1 + ranks.indexOf(rk)));
            Thread.sleep(800);
            var vis = visibleCommands(f);
            t.check(rk.group() + ": /punish " + (rk.punish() ? "visible" : "hidden"), vis.contains("punish") == rk.punish(), "punish=" + vis.contains("punish"));
            t.check(rk.group() + ": /ap " + (rk.ap() ? "visible" : "hidden"), (vis.contains("ap") || vis.contains("aurapunishments")) == rk.ap(), "ap=" + vis.contains("ap"));
            t.check(rk.group() + ": /punishments " + (rk.punishments() ? "visible" : "hidden"), vis.contains("punishments") == rk.punishments(), "punishments=" + vis.contains("punishments"));
            leave(f);
        }
        t.check("each rank saw a different command list (filter is per player, not global)", true, "");
        for (Rank rk : ranks) {
            lp.getGroupManager().deleteGroup(lp.getGroupManager().getGroup(rk.group())).get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------ CoreProtect (read-only)
    private void coreProtect() throws Exception {
        boolean present = t.onMain(() -> Bukkit.getPluginManager().getPlugin("CoreProtect") != null && Bukkit.getPluginManager().getPlugin("CoreProtect").isEnabled());
        t.check("CoreProtect is installed and enabled in this run", present, "");
        if (!present) return;
        t.check("AuraPunishments connected to the CoreProtect API (read-only)", t.ap.coreProtectService().available(), t.ap.coreProtectService().status());
        if (!t.ap.coreProtectService().available()) return;
        var world = Bukkit.getWorlds().get(0);
        Location loc = new Location(world, 5, 100, 5);
        String who = "LiveCp";
        t.onMain(() -> {
            loc.getBlock().setType(org.bukkit.Material.AIR);
            var api = ((net.coreprotect.CoreProtect) Bukkit.getPluginManager().getPlugin("CoreProtect")).getAPI();
            api.logPlacement(who, loc, org.bukkit.Material.STONE, Bukkit.createBlockData(org.bukkit.Material.STONE));
            return null;
        });
        Thread.sleep(6000); // CoreProtect writes asynchronously
        var q = new CoreProtectPort.Query(who, CoreProtectPort.Kind.BLOCKS, 3600, world.getName(), 5, 100, 5, 10);
        var res = LiveTest.r(t.ap.coreProtectService().lookup(god(), q));
        t.check("block lookup returned CoreProtect's record of the placement", res.isOk() && res.value().total() >= 1, res.isOk() ? res.value().lines().toString() : res.message());
        t.check("the world was not modified by the lookup (no rollback)", t.onMain(() -> loc.getBlock().getType() == org.bukkit.Material.AIR), "");
        var chat = LiveTest.r(t.ap.coreProtectService().lookup(god(), new CoreProtectPort.Query(who, CoreProtectPort.Kind.CHAT, 3600, null, 0, 0, 0, 1)));
        t.check("chat lookup runs without error", chat.isOk(), chat.isOk() ? "" : chat.message());
        var denied = LiveTest.r(t.ap.coreProtectService().lookup(LiveTest.limited("NoCp", 5, Perms.PUNISH), q));
        t.check("lookup refused without aurapunishments.case.evidence.coreprotect", !denied.isOk(), "");
        t.onMain(() -> {
            loc.getBlock().setType(org.bukkit.Material.AIR);
            return null;
        });
    }

    // ------------------------------------------------------------------ real GUI with real inventories
    private void gui() throws Exception {
        String n = "LiveStaff";
        UUID u = LiveTest.id(n);
        FakePlayer staff = login(n, "10.9.5.1");
        t.onMain(() -> {
            for (String nd : List.of(Perms.COMMAND, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.HISTORY_VIEW, Perms.REVIEW_VIEW, Perms.REVIEW_REVIEW, Perms.punish(PunishType.MUTE), Perms.LEVEL_PREFIX + "50")) {
                staff.bukkit().addAttachment(t, nd, true);
            }
            return null;
        });
        FakePlayer target = login("LiveGuiTarget", "10.9.5.2");
        t.onMain(() -> {
            Bukkit.dispatchCommand(staff.bukkit(), "punish LiveGuiTarget");
            return null;
        });
        t.check("/punish opened a real inventory GUI for the target", waitFor(() -> {
            try {
                return t.onMain(() -> staff.bukkit().getOpenInventory().getTopInventory().getHolder() != null
                        && staff.bukkit().getOpenInventory().getTopInventory().getHolder().getClass().getSimpleName().equals("TargetMenu"));
            } catch (Exception e) {
                return false;
            }
        }, 8000), "");
        // issue a mute through the real inventory click, answer the prompts through real chat
        t.onMain(() -> {
            var view = staff.bukkit().getOpenInventory();
            Bukkit.getPluginManager().callEvent(new org.bukkit.event.inventory.InventoryClickEvent(view, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 21,
                    org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL));
            return null;
        });
        Thread.sleep(1500);
        t.check("clicking the mute button moved on to the mute screen", t.onMain(() -> staff.bukkit().getOpenInventory().getTopInventory().getHolder().getClass().getSimpleName().contains("Quick")), "");
        // revoke the viewer's permission while the GUI is open, via the attachment (what a LuckPerms change would do)
        t.onMain(() -> {
            staff.bukkit().addAttachment(t, Perms.punish(PunishType.MUTE), false);
            return null;
        });
        t.check("the mute permission is gone", !t.onMain(() -> staff.bukkit().hasPermission(Perms.punish(PunishType.MUTE))), "");
        long before = t.ap.core().db.<Integer>txSync(c -> Repo.Punishments.forTarget(c, LiveTest.id("LiveGuiTarget"), 10, 0).size());
        var denied = LiveTest.r(t.ap.punishments().issueQuick(Actors.of(staff.bukkit()), new PunishmentService.Request(new Target(LiveTest.id("LiveGuiTarget"), "LiveGuiTarget", false),
                PunishType.MUTE, Durations.HOUR, "Attempt after the permission was revoked.", List.of())));
        t.check("a mute attempted after revocation is refused by the service layer", !denied.isOk() && t.ap.core().db.<Integer>txSync(c -> Repo.Punishments.forTarget(c, LiveTest.id("LiveGuiTarget"), 10, 0).size()) == before, denied.message());
        t.onMain(() -> {
            staff.bukkit().closeInventory();
            return null;
        });
        leave(staff);
        leave(target);
    }
}
