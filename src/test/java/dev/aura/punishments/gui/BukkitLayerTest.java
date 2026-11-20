package dev.aura.punishments.gui;

import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.service.Actors;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.service.PunishmentService;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BukkitLayerTest {
    McFx fx;

    @BeforeEach
    void setUp() {
        fx = new McFx();
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    // ------------------------------------------------------------ /punish <player>
    @Test
    void punishOpensTheTargetGuiForAnOnlinePlayer() {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        PlayerMock steve = fx.server.addPlayer("Steve");
        mod.performCommand("punish Steve");
        assertTrue(fx.awaitMenu(mod, TargetMenu.class), "target GUI should open");
        assertEquals("Punish: Steve", fx.title(mod));
        assertEquals(Material.PLAYER_HEAD, fx.top(mod).getItem(4).getType(), "player head in the header");
        assertTrue(McFx.plain(fx.top(mod).getItem(4).getItemMeta().displayName()).contains("Steve"));
    }

    @Test
    void punishOpensTheGuiForAVerifiedOfflinePlayer() throws Exception {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        fx.gui.resolver.recordJoin(java.util.UUID.randomUUID(), "OfflineOlly", "10.0.0.1", 0).get();
        mod.performCommand("punish OfflineOlly");
        assertTrue(fx.awaitMenu(mod, TargetMenu.class));
        assertEquals("Punish: OfflineOlly", fx.title(mod));
        List<String> lore = fx.top(mod).getItem(4).getItemMeta().lore().stream().map(McFx::plain).toList();
        assertTrue(lore.stream().anyMatch(l -> l.contains("Offline")), lore.toString());
    }

    @Test
    void unknownTargetIsRejectedWithoutOpeningAnything() {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        mod.performCommand("punish NobodyHasEverHeardOfThem");
        assertTrue(fx.await(() -> fx.messages(mod).stream().anyMatch(m -> m.contains("Unknown player"))));
        assertNull(fx.menuOf(mod));
    }

    @Test
    void punishRequiresItsPermission() {
        PlayerMock nobody = fx.staff("Nobody");
        fx.server.addPlayer("Steve");
        nobody.performCommand("punish Steve");
        fx.server.getScheduler().performTicks(20);
        assertNull(fx.menuOf(nobody));
    }

    @Test
    void punishPermissionAloneDoesNotAllowTargetingOthers() {
        PlayerMock p = fx.staff("Half", Perms.PUNISH);
        fx.server.addPlayer("Steve");
        p.performCommand("punish Steve");
        fx.server.getScheduler().performTicks(20);
        assertNull(fx.menuOf(p));
        assertTrue(fx.messages(p).stream().anyMatch(m -> m.contains("permission")));
    }

    @Test
    void cannotOpenYourOwnPunishmentScreen() {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        mod.performCommand("punish Mod");
        assertTrue(fx.await(() -> fx.messages(mod).stream().anyMatch(m -> m.toLowerCase().contains("own punishment screen") || m.toLowerCase().contains("yourself"))));
        assertNull(fx.menuOf(mod));
    }

    @Test
    void higherRankedStaffAreProtectedFromCaseCreation() {
        PlayerMock boss = fx.staff("Boss", Perms.LEVEL_PREFIX + "90");
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        Result<?> r = fx.join(fx.plugin.cases().create(Actors.of(mod), new dev.aura.punishments.service.CaseService.CreateRequest(
                new Target(boss.getUniqueId(), "Boss", true), "chat_misconduct", dev.aura.punishments.model.Simple.Severity.MINOR,
                "Excessive caps in chat all day long.", null, List.of())));
        assertEquals(Result.Code.HIERARCHY, r.code());
    }

    // ------------------------------------------------------------ permission-sensitive GUI actions
    @Test
    void unpermittedActionsAreShownLockedAndPermittedOnesAreLive() {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        fx.server.addPlayer("Steve");
        mod.performCommand("punish Steve");
        assertTrue(fx.awaitMenu(mod, TargetMenu.class));
        var inv = fx.top(mod);
        assertEquals(Material.SKELETON_SKULL, inv.getItem(21).getType(), "mute is available");
        assertEquals(Material.LEATHER_BOOTS, inv.getItem(20).getType(), "kick is available");
        assertEquals(Material.GRAY_DYE, inv.getItem(22).getType(), "ban is locked");
        assertEquals(Material.GRAY_DYE, inv.getItem(24).getType(), "direct punishment is locked");
        assertEquals(Material.GRAY_DYE, inv.getItem(25).getType(), "emergency action is locked");
        assertEquals(Material.WRITABLE_BOOK, inv.getItem(10).getType(), "create case is available");
        assertEquals(Material.GRAY_DYE, inv.getItem(30).getType(), "revoke is locked");
        fx.click(mod, 22);
        assertTrue(fx.messages(mod).stream().anyMatch(m -> m.contains("permission")));
        assertTrue(fx.menuOf(mod) instanceof TargetMenu, "locked action opens nothing");
    }

    @Test
    void lockedActionsCanBeHiddenByConfig() throws Exception {
        java.io.File cfg = new java.io.File(fx.plugin.getDataFolder(), "config.yml");
        String text = java.nio.file.Files.readString(cfg.toPath()).replace("hide-locked-actions: false", "hide-locked-actions: true");
        java.nio.file.Files.writeString(cfg.toPath(), text);
        fx.plugin.reloadFor(fx.server.getConsoleSender());
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        fx.server.addPlayer("Steve");
        mod.performCommand("punish Steve");
        assertTrue(fx.awaitMenu(mod, TargetMenu.class));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, fx.top(mod).getItem(22).getType(), "hidden: only filler remains");
    }

    // ------------------------------------------------------------ GUI integrity
    @Test
    void itemsCanNeverBeMovedOrTaken() {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        fx.server.addPlayer("Steve");
        mod.performCommand("punish Steve");
        assertTrue(fx.awaitMenu(mod, TargetMenu.class));
        for (ClickType t : new ClickType[]{ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK, ClickType.DROP}) {
            assertTrue(fx.click(mod, 4, t).isCancelled(), t + " must be cancelled");
            assertTrue(fx.click(mod, 40, t).isCancelled(), t + " on an empty slot must be cancelled");
        }
        InventoryDragEvent drag = new InventoryDragEvent(mod.getOpenInventory(), null, new org.bukkit.inventory.ItemStack(Material.STONE),
                false, Map.of(12, new org.bukkit.inventory.ItemStack(Material.STONE)));
        fx.server.getPluginManager().callEvent(drag);
        assertTrue(drag.isCancelled(), "drag is cancelled");
    }

    private static final class CountMenu extends Menu {
        int clicks;

        CountMenu(Gui gui, org.bukkit.entity.Player p) {
            super(gui, p, null);
        }

        @Override
        protected net.kyori.adventure.text.Component title() {
            return net.kyori.adventure.text.Component.text("count");
        }

        @Override
        protected void render() {
            set(10, Items.item(Material.STONE, "<white>x"), (p, t) -> clicks++);
        }
    }

    @Test
    void rapidRepeatedClicksAreDebounced() throws Exception {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        CountMenu m = new CountMenu(fx.gui, mod);
        m.open();
        fx.click(mod, 10);
        fx.click(mod, 10);
        fx.click(mod, 10);
        assertEquals(1, m.clicks, "triple click inside the cooldown counts once");
        Thread.sleep(fx.gui.settings().gui.clickCooldownMs() + 60L);
        fx.click(mod, 10);
        assertEquals(2, m.clicks, "a later deliberate click is accepted");
    }

    @Test
    void anInFlightActionBlocksRepeatsUntilItFinishes() {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        var slow = new java.util.concurrent.CompletableFuture<Result<String>>();
        int[] done = {0};
        fx.gui.call(mod, slow, v -> done[0]++);
        fx.gui.call(mod, slow, v -> done[0]++);   // duplicate while the first is still running
        slow.complete(Result.ok("x"));
        assertTrue(fx.await(() -> done[0] >= 1));
        fx.server.getScheduler().performTicks(5);
        assertEquals(1, done[0], "the duplicate call was refused");
        assertTrue(fx.messages(mod).stream().anyMatch(m -> m.contains("Still working")));
    }

    @Test
    void aStaleWindowCannotBeUsedAfterItWasReplaced() {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        CountMenu a = new CountMenu(fx.gui, mod);
        a.open();
        org.bukkit.inventory.InventoryView staleView = mod.getOpenInventory();
        CountMenu b = new CountMenu(fx.gui, mod);
        b.open();
        // a click delivered for the OLD window (for example from a laggy client)
        org.bukkit.event.inventory.InventoryClickEvent e = new org.bukkit.event.inventory.InventoryClickEvent(staleView,
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 10, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        fx.server.getPluginManager().callEvent(e);
        assertTrue(e.isCancelled());
        assertEquals(0, a.clicks, "stale menu does not act");
        assertSame(b, fx.menuOf(mod));
    }

    @Test
    void permissionRevokedWhileTheGuiIsOpenStopsTheAction() throws Exception {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        var att = mod.addAttachment(fx.plugin);
        att.setPermission(Perms.punish(PunishType.MUTE), true);
        fx.server.addPlayer("Steve");
        mod.performCommand("punish Steve");
        assertTrue(fx.awaitMenu(mod, TargetMenu.class));
        // admin revokes the mute authority while the staff member still has the GUI open
        for (var a : new java.util.ArrayList<>(mod.getEffectivePermissions())) {
            if (a.getAttachment() != null) a.getAttachment().unsetPermission(a.getPermission());
        }
        mod.addAttachment(fx.plugin, Perms.PUNISH, true);
        Result<Punishment> r = fx.join(fx.plugin.punishments().issueQuick(Actors.of(mod), new PunishmentService.Request(
                new Target(fx.server.getPlayer("Steve").getUniqueId(), "Steve", true), PunishType.MUTE, Durations.HOUR, "Spamming the global chat channel.", List.of())));
        assertEquals(Result.Code.NO_PERMISSION, r.code(), "the service re-checks at execution, so the open GUI grants nothing");
    }

    // ------------------------------------------------------------ prompts
    @Test
    void chatPromptCapturesTheLineAndNeverBroadcastsIt() {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        PlayerMock other = fx.server.addPlayer("Other");
        String[] got = {null};
        fx.gui.prompts().ask(mod, "Type something", 3, 50, text -> got[0] = text, () -> { });
        mod.chat("the secret reason");
        assertTrue(fx.await(() -> got[0] != null));
        assertEquals("the secret reason", got[0]);
        assertTrue(fx.messages(other).stream().noneMatch(m -> m.contains("secret")), "answer must not reach other players");
    }

    @Test
    void chatPromptCanBeCancelledAndTimesOutCleanly() {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        boolean[] cancelled = {false};
        String[] got = {null};
        fx.gui.prompts().ask(mod, "Type something", 3, 50, t -> got[0] = t, () -> cancelled[0] = true);
        mod.chat("cancel");
        assertTrue(fx.await(() -> cancelled[0]));
        assertNull(got[0]);
        fx.gui.prompts().ask(mod, "Again", 3, 50, t -> got[0] = t, () -> cancelled[0] = false);
        mod.chat("ab"); // too short: stays pending
        fx.server.getScheduler().performTicks(5);
        assertNull(got[0]);
        assertTrue(fx.gui.prompts().has(mod));
        fx.server.getScheduler().performTicks(20L * 100);   // beyond the 90s timeout
        assertFalse(fx.gui.prompts().has(mod), "timed out");
        assertNull(got[0]);
    }

    // ------------------------------------------------------------ mute enforcement
    private Punishment mute(PlayerMock target, PlayerMock issuerForService) {
        Result<Punishment> r = fx.join(fx.plugin.punishments().issueQuick(Actors.of(issuerForService), new PunishmentService.Request(
                new Target(target.getUniqueId(), target.getName(), true), PunishType.MUTE, Durations.HOUR, "Spamming the global chat channel.", List.of())));
        assertTrue(r.isOk(), r.message());
        fx.server.getScheduler().performTicks(3);
        return r.value();
    }

    @Test
    void mutedPlayerCannotSpeakInPublicChat() {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        PlayerMock steve = fx.server.addPlayer("Steve");
        PlayerMock other = fx.server.addPlayer("Other");
        mute(steve, mod);
        fx.messages(other);
        steve.chat("hello everyone");
        fx.server.getScheduler().performTicks(3);
        assertTrue(fx.messages(other).stream().noneMatch(m -> m.contains("hello everyone")), "muted chat is cancelled");
        assertTrue(fx.messages(steve).stream().anyMatch(m -> m.toLowerCase().contains("muted")));
        assertTrue(fx.plugin.punishments().muteFor(other.getUniqueId()).isEmpty(), "unmuted players are unaffected");
    }

    private boolean blocked(PlayerMock p, String cmd) {
        PlayerCommandPreprocessEvent e = new PlayerCommandPreprocessEvent(p, cmd);
        fx.server.getPluginManager().callEvent(e);
        return e.isCancelled();
    }

    @Test
    void mutedPlayerCannotUsePrivateMessageOrChannelCommandsIncludingAliases() {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        PlayerMock steve = fx.server.addPlayer("Steve");
        // stand-ins for EssentialsX /msg (with aliases) and TownyChat channel shortcuts
        fx.registerCommand("msg", "whisperx", "tellx", "pmx");
        fx.registerCommand("tc");
        fx.registerCommand("g");
        fx.registerCommand("mail");
        assertFalse(blocked(steve, "/msg Bob hi"), "not muted yet");
        mute(steve, mod);
        assertTrue(blocked(steve, "/msg Bob hi"));
        assertTrue(blocked(steve, "/tell Bob hi"));
        assertTrue(blocked(steve, "/essentials:msg Bob hi"), "namespaced form");
        assertTrue(blocked(steve, "/whisperx Bob hi"), "alias resolved through the command map");
        assertTrue(blocked(steve, "/tellx Bob hi"), "alias resolved through the command map");
        assertTrue(blocked(steve, "/tc hello town"), "TownyChat town channel");
        assertTrue(blocked(steve, "/g hello"), "TownyChat global channel");
        assertTrue(blocked(steve, "/r thanks"));
        assertTrue(blocked(steve, "/mail send Bob hi"));
        assertFalse(blocked(steve, "/mail read"), "reading mail is allowed");
        assertFalse(blocked(steve, "/help"), "allowed commands stay allowed");
        assertFalse(blocked(steve, "/spawn"));
    }

    @Test
    void aLabelOwnedByAnotherCommandIsJudgedByThatCommand() {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        PlayerMock steve = fx.server.addPlayer("Steve");
        fx.registerCommand("msg", "tx");          // messaging command with alias tx
        fx.registerCommand("town", "t");          // Towny-style: /t belongs to /town
        mute(steve, mod);
        assertTrue(blocked(steve, "/tx Bob hi"), "alias of /msg");
        assertFalse(blocked(steve, "/town"), "/town stays usable while muted");
        assertFalse(blocked(steve, "/t spawn"), "an alias owned by /town is judged as /town");
    }

    @Test
    void muteExpiryRestoresSpeech() throws Exception {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        PlayerMock steve = fx.server.addPlayer("Steve");
        Punishment p = mute(steve, mod);
        assertTrue(fx.plugin.punishments().muteFor(steve.getUniqueId()).isPresent());
        // expire it the way the maintenance task does (DB row + cache); the clock here is real, so shorten it in the database
        fx.plugin.core().db.txSync(cn -> {
            dev.aura.punishments.db.Sql.update(cn, "UPDATE punishments SET expires_at=? WHERE id=?", System.currentTimeMillis() - 1000, p.id());
            return null;
        });
        fx.join(fx.plugin.punishments().loadCache());
        assertTrue(fx.plugin.punishments().muteFor(steve.getUniqueId()).isEmpty(), "an elapsed mute is never enforced");
        assertEquals(1, fx.join(fx.plugin.punishments().expireDue()).value());
        assertFalse(blocked(steve, "/msg Bob hi"));
    }

    // ------------------------------------------------------------ legacy commands
    @Test
    void legacyPunishmentCommandsAreRedirectedForStaffWithoutBypass() {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        fx.registerCommand("ban", "ebanx");
        fx.registerCommand("tempban");
        fx.registerCommand("mute");
        fx.registerCommand("kick");
        fx.registerCommand("gamemode");
        for (String c : new String[]{"/ban Steve", "/minecraft:ban Steve", "/essentials:mute Steve 1h", "/tempban Steve 1d", "/kick Steve", "/ebanx Steve", "/pardon Steve"}) {
            assertTrue(blocked(mod, c), c + " must be redirected");
        }
        assertTrue(fx.messages(mod).stream().anyMatch(m -> m.contains("/punish")), "the staff member is told where to go");
        assertFalse(blocked(mod, "/gamemode creative"), "unrelated commands untouched");
        PlayerMock owner = fx.staff("Owner", Perms.LEGACY_BYPASS);
        assertFalse(blocked(owner, "/ban Steve"), "explicit bypass node still works");
    }

    @Test
    void legacyBlockingCanBeTurnedOffInConfig() throws Exception {
        java.io.File cfg = new java.io.File(fx.plugin.getDataFolder(), "config.yml");
        java.nio.file.Files.writeString(cfg.toPath(), java.nio.file.Files.readString(cfg.toPath()).replace("  block: true\n  blocked:", "  block: false\n  blocked:"));
        fx.plugin.reloadFor(fx.server.getConsoleSender());
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        fx.registerCommand("ban");
        assertFalse(blocked(mod, "/ban Steve"));
    }

    // ------------------------------------------------------------ custody guard for JailReloaded commands
    @Test
    void jailReloadedCommandsCannotReleaseAPrisonerHeldForACase() throws Exception {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        PlayerMock steve = fx.server.addPlayer("Steve");
        fx.registerCommand("unjail");
        fx.registerCommand("unjailforce");
        fx.registerCommand("jail");
        var cs = fx.join(fx.plugin.cases().create(Actors.of(mod), new dev.aura.punishments.service.CaseService.CreateRequest(
                new Target(steve.getUniqueId(), "Steve", true), "chat_misconduct", dev.aura.punishments.model.Simple.Severity.MINOR,
                "Excessive caps in chat all day long.", null, List.of()))).orThrow();
        var cu = fx.join(fx.plugin.custody().initiate(Actors.of(mod), cs.id()));
        assertTrue(cu.isOk(), cu.message());
        fx.join(fx.plugin.custody().refreshIndex());
        PlayerMock admin = fx.staff("JailAdmin");
        assertTrue(blocked(admin, "/unjail Steve"), "/unjail of a case prisoner is refused");
        assertTrue(blocked(admin, "/unjailforce steve"));
        assertTrue(blocked(admin, "/jail time Steve +1h"));
        assertTrue(fx.messages(admin).stream().anyMatch(m -> m.contains("AP-")), "told which case holds them");
        assertFalse(blocked(admin, "/unjail SomeoneElse"), "other prisoners are unaffected");
        PlayerMock override = fx.staff("Override", Perms.CUSTODY_OVERRIDE);
        assertFalse(blocked(override, "/unjail Steve"), "explicit override node");
    }

    // ------------------------------------------------------------ login enforcement
    private AsyncPlayerPreLoginEvent login(String name, java.util.UUID id, String ip) throws Exception {
        // pre-login is an asynchronous event: fire it from a non-server thread like a real connection would
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                var e = new AsyncPlayerPreLoginEvent(name, InetAddress.getByName(ip), id);
                fx.server.getPluginManager().callEvent(e);
                return e;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }).get(10, TimeUnit.SECONDS);
    }

    @Test
    void bansAreEnforcedAtLoginIncludingIpBans() throws Exception {
        PlayerMock owner = fx.staff("Owner", Perms.PUNISH_OTHERS, Perms.punish(PunishType.BAN), Perms.limit(PunishType.BAN, "long"), Perms.punishPermanent(PunishType.BAN),
                Perms.punish(PunishType.IPBAN), Perms.limit(PunishType.IPBAN, "long"), Perms.LEVEL_PREFIX + "100");
        java.util.UUID u = java.util.UUID.randomUUID();
        fx.gui.resolver.recordJoin(u, "Griefer", "203.0.113.7", 0).get();
        Result<Punishment> b = fx.join(fx.plugin.punishments().issueQuick(Actors.of(owner), new PunishmentService.Request(new Target(u, "Griefer", false), PunishType.BAN,
                Durations.PERMANENT, "Permanent ban for griefing.", List.of(dev.aura.punishments.support.Fx.ev()))));
        assertTrue(b.isOk(), b.message());
        var denied = login("Griefer", u, "198.51.100.1");
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, denied.getLoginResult());
        String screen = McFx.plain(denied.kickMessage());
        assertTrue(screen.contains("banned") && screen.contains("griefing"), screen);
        assertFalse(screen.contains("203.0.113.7"), "the screen never leaks IP addresses");
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, login("Innocent", java.util.UUID.randomUUID(), "198.51.100.2").getLoginResult());
        // an IP ban also stops a different account from the same address
        java.util.UUID u2 = java.util.UUID.randomUUID();
        fx.gui.resolver.recordJoin(u2, "Evader", "203.0.113.50", 0).get();
        assertTrue(fx.join(fx.plugin.punishments().issueQuick(Actors.of(owner), new PunishmentService.Request(new Target(u2, "Evader", false), PunishType.IPBAN,
                Durations.DAY, "IP ban for ban evasion.", List.of(dev.aura.punishments.support.Fx.ev())))).isOk());
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, login("EvaderAlt", java.util.UUID.randomUUID(), "203.0.113.50").getLoginResult());
    }

    @Test
    void banningAnOnlinePlayerDisconnectsThem() {
        PlayerMock owner = fx.staff("Owner", Perms.PUNISH_OTHERS, Perms.punish(PunishType.KICK), Perms.LEVEL_PREFIX + "100");
        PlayerMock steve = fx.server.addPlayer("Steve");
        Result<Punishment> k = fx.join(fx.plugin.punishments().issueQuick(Actors.of(owner), new PunishmentService.Request(
                new Target(steve.getUniqueId(), "Steve", true), PunishType.KICK, 0, "Disrupting the spawn area.", List.of())));
        assertTrue(k.isOk(), k.message());
        assertTrue(fx.await(() -> !steve.isOnline() || fx.server.getPlayer("Steve") == null));
    }

    @Test
    void missedWarningsAreDeliveredAtNextLogin() throws Exception {
        PlayerMock mod = fx.staff("Mod", Perms.PUNISH_OTHERS, Perms.punish(PunishType.WARN), Perms.LEVEL_PREFIX + "20");
        java.util.UUID u = java.util.UUID.randomUUID();
        fx.gui.resolver.recordJoin(u, "Away", "10.1.1.1", 0).get();
        var w = fx.join(fx.plugin.punishments().issueQuick(Actors.of(mod), new PunishmentService.Request(new Target(u, "Away", false), PunishType.WARN, 0,
                "Please stop spamming chat.", List.of())));
        assertTrue(w.isOk() && !w.value().delivered());
        PlayerMock away = new PlayerMock(fx.server, "Away", u);
        fx.server.addPlayer(away);
        assertTrue(fx.await(() -> fx.messages(away).stream().anyMatch(m -> m.contains("spamming"))), "warning shown when they return");
        assertTrue(fx.await(() -> {
            try {
                return fx.plugin.core().db.txSync(cn -> dev.aura.punishments.db.Repo.Punishments.undeliveredWarnings(cn, u)).isEmpty();
            } catch (Exception e) {
                return false;
            }
        }));
    }

    // ------------------------------------------------------------ /ap command & tab completion
    @Test
    void apHelpAndTabCompletionOnlyShowWhatTheSenderMayUse() {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        mod.performCommand("ap help");
        List<String> help = fx.messages(mod);
        assertTrue(help.stream().anyMatch(l -> l.contains("/ap mute")));
        assertTrue(help.stream().noneMatch(l -> l.contains("/ap ban")), "no ban permission, so not listed");
        assertTrue(help.stream().noneMatch(l -> l.contains("/ap reload")));
        var ap = fx.server.getPluginCommand("aurapunishments");
        List<String> subs = ap.tabComplete(mod, "ap", new String[]{""});
        assertTrue(subs.contains("mute") && !subs.contains("ban") && !subs.contains("reload"), subs.toString());
        PlayerMock none = fx.staff("None");
        assertTrue(ap.tabComplete(none, "ap", new String[]{""}).isEmpty());
    }

    @Test
    void playerNameSuggestionsRespectPermissionAndVanish() throws Exception {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        PlayerMock hidden = fx.server.addPlayer("Stealthy");
        mod.hidePlayer(fx.plugin, hidden);
        fx.server.addPlayer("Steve");
        fx.gui.resolver.recordJoin(java.util.UUID.randomUUID(), "StanOffline", "10.0.0.9", 0).get();
        var cmd = fx.server.getPluginCommand("punish");
        List<String> out = cmd.tabComplete(mod, "punish", new String[]{"St"});
        assertTrue(out.contains("Steve") && out.contains("StanOffline"), out.toString());
        assertFalse(out.contains("Stealthy"), "vanished players are not disclosed");
        PlayerMock noOthers = fx.staff("Limited", Perms.PUNISH);
        assertTrue(cmd.tabComplete(noOthers, "punish", new String[]{"St"}).isEmpty(), "no suggestions without punish.others");
    }

    @Test
    void commandLinePunishmentsUseTheSameAuthorisation() {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        PlayerMock steve = fx.server.addPlayer("Steve");
        mod.performCommand("ap mute Steve 30m Spamming the global chat channel");
        assertTrue(fx.await(() -> fx.plugin.punishments().muteFor(steve.getUniqueId()).isPresent()), "mute within limits works");
        mod.performCommand("ap ban Steve 1d Cheating with a hacked client -e https://example.com/p");
        fx.server.getScheduler().performTicks(10);
        assertTrue(fx.plugin.punishments().banFor(steve.getUniqueId(), null).isEmpty(), "no ban permission: refused");
        PlayerMock other = fx.server.addPlayer("Other");
        mod.performCommand("ap mute Other 7d Spamming the global chat channel");
        assertTrue(fx.await(() -> fx.messages(mod).stream().anyMatch(m -> m.contains("limit"))), "duration above the tier is refused");
        assertTrue(fx.plugin.punishments().muteFor(other.getUniqueId()).isEmpty());
    }

    @Test
    void reloadNeedsPermissionAndRejectsInvalidConfig() throws Exception {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        mod.performCommand("ap reload");
        assertTrue(fx.messages(mod).stream().anyMatch(m -> m.contains("permission")));
        PlayerMock admin = fx.staff("Admin", Perms.COMMAND, Perms.ADMIN_RELOAD);
        java.io.File cfg = new java.io.File(fx.plugin.getDataFolder(), "config.yml");
        String orig = java.nio.file.Files.readString(cfg.toPath());
        java.nio.file.Files.writeString(cfg.toPath(), orig.replace("review-deadline: 48h", "review-deadline: nonsense"));
        admin.performCommand("ap reload");
        List<String> msgs = fx.messages(admin);
        assertTrue(msgs.stream().anyMatch(m -> m.contains("Reload failed")), msgs.toString());
        assertEquals(48 * Durations.HOUR, fx.plugin.core().settings.custody.reviewDeadline(), "old settings stay active");
        java.nio.file.Files.writeString(cfg.toPath(), orig);
        admin.performCommand("ap reload");
        assertTrue(fx.messages(admin).stream().anyMatch(m -> m.contains("reloaded")));
    }
}
