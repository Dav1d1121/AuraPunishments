package dev.aura.punishments.gui;

import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.service.Actors;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.service.PunishmentService;
import dev.aura.punishments.util.Durations;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.1.1 chat input. Paper fires the LEGACY AsyncPlayerChatEvent first and the modern AsyncChatEvent second whenever any plugin
 * listens to the legacy one. These tests drive both events in that order (exactly what the live server does) and check that
 * confidential answers are captured once, silenced for every later listener, and never confused between players.
 * (The real-client proof is the live phase 7 run; see docs/TEST_REPORT.md.)
 */
class ChatInputTest {
    McFx fx;
    PlayerMock mod;
    final List<String> seenByRelays = new CopyOnWriteArrayList<>();

    /** Stands in for DiscordSRV / a chat logger / CoreProtect: listens at MONITOR and ignores cancelled lines. */
    final class Relay implements Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void legacy(AsyncPlayerChatEvent e) {
            seenByRelays.add("legacy:" + e.getMessage());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void modern(AsyncChatEvent e) {
            seenByRelays.add("modern:" + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.message()));
        }
    }

    @BeforeEach
    void setUp() {
        fx = new McFx();
        mod = fx.staff("Mod", McFx.basicStaff());
        fx.server.getPluginManager().registerEvents(new Relay(), fx.plugin);
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    /** One chat line the way Paper delivers it with legacy listeners present. @return {legacy event, modern event} */
    private Object[] line(Player p, String text) {
        // chat events are asynchronous: like the real server, fire them from a non-main thread
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> fire(p, text)).join();
    }

    private Object[] fire(Player p, String text) {
        Set<Player> recipients = new HashSet<>(fx.server.getOnlinePlayers());
        AsyncPlayerChatEvent legacy = new AsyncPlayerChatEvent(true, p, text, recipients);
        fx.server.getPluginManager().callEvent(legacy);
        Set<Audience> viewers = new HashSet<>(fx.server.getOnlinePlayers());
        AsyncChatEvent modern = new AsyncChatEvent(true, p, viewers, ChatRenderer.defaultRenderer(), Component.text(text), Component.text(text), null);
        modern.setCancelled(legacy.isCancelled()); // Paper carries the legacy decision over
        fx.server.getPluginManager().callEvent(modern);
        return new Object[]{legacy, modern, recipients, viewers};
    }

    @Test
    void theLegacyEventIsCapturedFirstSilencedAndTheModernTwinIsNotProcessedAgain() {
        PlayerMock other = fx.server.addPlayer("Other");
        AtomicInteger got = new AtomicInteger();
        String[] answer = {null};
        fx.gui.prompts().ask(mod, "Type something", 3, 80, t -> {
            got.incrementAndGet();
            answer[0] = t;
        }, () -> { });
        Object[] ev = line(mod, "the secret reason for this punishment");
        AsyncPlayerChatEvent legacy = (AsyncPlayerChatEvent) ev[0];
        AsyncChatEvent modern = (AsyncChatEvent) ev[1];
        assertTrue(legacy.isCancelled(), "cancelled at the FIRST event, before any legacy listener could act");
        assertTrue(legacy.getRecipients().isEmpty(), "and nobody is left to receive it");
        assertTrue(modern.isCancelled());
        assertTrue(modern.viewers().stream().noneMatch(a -> a == other), "the twin has no audience either");
        assertTrue(fx.await(() -> got.get() == 1));
        fx.server.getScheduler().performTicks(5);
        assertEquals(1, got.get(), "legacy + modern of the same line are processed exactly once");
        assertEquals("the secret reason for this punishment", answer[0]);
        assertTrue(seenByRelays.stream().noneMatch(s -> s.contains("secret reason")), "a MONITOR relay/logger never sees the answer: " + seenByRelays);
    }

    @Test
    void ordinaryChatIsCompletelyUntouched() {
        PlayerMock other = fx.server.addPlayer("Other");
        Object[] ev = line(other, "hello everyone");
        assertFalse(((AsyncPlayerChatEvent) ev[0]).isCancelled());
        assertFalse(((AsyncChatEvent) ev[1]).isCancelled());
        assertFalse(((AsyncPlayerChatEvent) ev[0]).getRecipients().isEmpty());
        assertTrue(seenByRelays.contains("legacy:hello everyone") && seenByRelays.contains("modern:hello everyone"));
        // a staff member WITHOUT an active prompt is also ordinary chat
        Object[] ev2 = line(mod, "just chatting");
        assertFalse(((AsyncPlayerChatEvent) ev2[0]).isCancelled());
    }

    @Test
    void aSecondIdenticalAnswerAfterAValidationErrorIsStillCaptured() {
        List<String> got = new CopyOnWriteArrayList<>();
        fx.gui.prompts().ask(mod, "Type", 5, 80, got::add, () -> { });
        line(mod, "ok"); // too short: the prompt stays
        fx.server.getScheduler().performTicks(5);
        assertTrue(fx.gui.prompts().has(mod));
        line(mod, "ok"); // the same text again, within the twin window
        fx.server.getScheduler().performTicks(5);
        assertTrue(fx.gui.prompts().has(mod));
        line(mod, "now a long enough answer");
        assertTrue(fx.await(() -> got.size() == 1));
        assertEquals("now a long enough answer", got.get(0));
        assertTrue(seenByRelays.stream().noneMatch(s -> s.contains("ok") || s.contains("long enough")), seenByRelays.toString());
    }

    @Test
    void onlyTheOwnerOfTheSessionIsCapturedAndSessionsAreIndependent() {
        PlayerMock modB = fx.staff("ModB", McFx.basicStaff());
        PlayerMock bystander = fx.server.addPlayer("Bystander");
        String[] a = {null};
        String[] b = {null};
        fx.gui.prompts().ask(mod, "A", 3, 80, t -> a[0] = t, () -> { });
        fx.gui.prompts().ask(modB, "B", 3, 80, t -> b[0] = t, () -> { });
        line(bystander, "bystander normal chat");
        assertTrue(seenByRelays.contains("legacy:bystander normal chat"), "a player without a session is never captured");
        line(modB, "answer from B");
        line(mod, "answer from A");
        assertTrue(fx.await(() -> a[0] != null && b[0] != null));
        assertEquals("answer from A", a[0]);
        assertEquals("answer from B", b[0]);
        assertTrue(seenByRelays.stream().noneMatch(s -> s.contains("answer from")), seenByRelays.toString());
    }

    @Test
    void ifNoLegacyListenerIsInvolvedTheModernEventStillCaptures() {
        String[] got = {null};
        fx.gui.prompts().ask(mod, "Type", 3, 80, t -> got[0] = t, () -> { });
        Set<Audience> viewers = new HashSet<>(fx.server.getOnlinePlayers());
        AsyncChatEvent modern = new AsyncChatEvent(true, mod, viewers, ChatRenderer.defaultRenderer(), Component.text("modern only answer"), Component.text("modern only answer"), null);
        java.util.concurrent.CompletableFuture.runAsync(() -> fx.server.getPluginManager().callEvent(modern)).join();
        assertTrue(modern.isCancelled());
        assertTrue(modern.viewers().isEmpty());
        assertTrue(fx.await(() -> got[0] != null));
        assertEquals("modern only answer", got[0]);
    }

    @Test
    void aPluginThatUncancelsALaterEventStillCannotGiveTheLineAnAudience() {
        // a badly behaved chat plugin re-enables the twin at HIGH; our MONITOR guard cancels it again and removes the audience
        fx.server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.HIGH)
            public void naughty(AsyncChatEvent e) {
                e.setCancelled(false);
                e.viewers().addAll(fx.server.getOnlinePlayers());
            }
        }, fx.plugin);
        fx.gui.prompts().ask(mod, "Type", 3, 80, t -> { }, () -> { });
        Object[] ev = line(mod, "must stay private");
        assertTrue(((AsyncChatEvent) ev[1]).isCancelled(), "cancelled again at MONITOR");
        assertTrue(((AsyncChatEvent) ev[1]).viewers().isEmpty());
    }

    @Test
    void disconnectingCleansUpTheSessionAndAReturningPlayerChatsNormally() {
        fx.gui.prompts().ask(mod, "Type", 3, 80, t -> { }, () -> { });
        assertTrue(fx.gui.prompts().has(mod));
        mod.disconnect();
        PlayerMock again = fx.server.addPlayer("Mod");
        assertFalse(fx.gui.prompts().has(again));
        Object[] ev = line(again, "ordinary after reconnect");
        assertFalse(((AsyncPlayerChatEvent) ev[0]).isCancelled());
    }

    @Test
    void shutdownOrReloadEndsEverySessionAndTellsThePlayer() {
        boolean[] cancelled = {false};
        fx.gui.prompts().ask(mod, "Type", 3, 80, t -> { }, () -> cancelled[0] = true);
        fx.messages(mod);
        fx.gui.prompts().cancelAll();
        assertFalse(fx.gui.prompts().has(mod));
        assertTrue(fx.messages(mod).stream().anyMatch(m -> m.toLowerCase().contains("cancel")), "the player is told nothing was changed");
        Object[] ev = line(mod, "after shutdown");
        assertFalse(((AsyncPlayerChatEvent) ev[0]).isCancelled());
    }

    @Test
    void aMutedPlayersLineIsStoppedAtTheLegacyEventBeforeAnyLegacyListenerAndNoticedOnce() {
        Target t = new Target(mod.getUniqueId(), "Mod", false);
        PlayerMock victim = fx.server.addPlayer("Victim");
        var res = fx.join(fx.plugin.punishments().issueQuick(dev.aura.punishments.service.Actor.console(), new PunishmentService.Request(new Target(victim.getUniqueId(), "Victim", false), PunishType.MUTE,
                Durations.HOUR, "Spamming the global chat channel.", List.of())));
        assertTrue(res.isOk(), res.message());
        fx.messages(victim);
        Object[] ev = line(victim, "muted words");
        assertTrue(((AsyncPlayerChatEvent) ev[0]).isCancelled());
        assertTrue(((AsyncPlayerChatEvent) ev[0]).getRecipients().isEmpty());
        assertTrue(((AsyncChatEvent) ev[1]).isCancelled());
        assertTrue(seenByRelays.stream().noneMatch(s -> s.contains("muted words")), "a relay/logger never sees a muted line: " + seenByRelays);
        fx.server.getScheduler().performTicks(3);
        assertEquals(1, fx.messages(victim).stream().filter(m -> m.toLowerCase().contains("muted")).count(), "one notice, not one per event");
        assertNotNull(t);
    }

    @Test
    void aMutedStaffMembersPromptAnswerIsStillACapturedAnswerNotAMuteNotice() {
        var res = fx.join(fx.plugin.punishments().issueQuick(dev.aura.punishments.service.Actor.console(), new PunishmentService.Request(new Target(mod.getUniqueId(), "Mod", false), PunishType.MUTE,
                Durations.HOUR, "Spamming the global chat channel.", List.of())));
        assertTrue(res.isOk(), res.message());
        fx.messages(mod);
        String[] got = {null};
        fx.gui.prompts().ask(mod, "Type", 3, 80, t -> got[0] = t, () -> { });
        line(mod, "answer typed while muted");
        assertTrue(fx.await(() -> got[0] != null));
        assertEquals("answer typed while muted", got[0]);
        fx.server.getScheduler().performTicks(3);
        assertTrue(fx.messages(mod).stream().noneMatch(m -> m.toLowerCase().contains("you are muted")));
    }

    @Test
    void theDeclaredLoadOrderPutsAuraPunishmentsBeforeTheChatPlugins() {
        var meta = fx.plugin.getPluginMeta();
        List<String> before = new ArrayList<>(meta.getLoadBeforePlugins());
        assertTrue(before.containsAll(List.of("TownyChat", "Essentials", "EssentialsChat", "DiscordSRV")), "loadbefore: " + before);
        List<String> soft = new ArrayList<>(meta.getPluginSoftDependencies());
        assertFalse(soft.contains("TownyChat") || soft.contains("Essentials") || soft.contains("DiscordSRV"), "no chat plugin may be a soft dependency (it would force the wrong order): " + soft);
    }

    /**
     * Event delivery order and timing differ between servers (Paper: legacy then modern, same thread; MockBukkit: modern then legacy,
     * on pool threads, the twin of an old line may arrive after the next prompt began). A late twin of a CANCELLED line must never
     * cancel or answer the NEXT prompt. (Found by a flaky existing test; 14 of 80 runs failed before twins were matched in both orders.)
     */
    @org.junit.jupiter.api.RepeatedTest(30)
    void aLateTwinOfAnEarlierLineNeverTouchesTheNextPrompt() {
        PlayerMock p = fx.staff("Late" + System.nanoTime() % 1000, McFx.basicStaff());
        boolean[] cancelled = {false};
        fx.gui.prompts().ask(p, "first", 3, 50, t -> { }, () -> cancelled[0] = true);
        p.chat("cancel");
        assertTrue(fx.await(() -> cancelled[0]));
        List<String> got = new CopyOnWriteArrayList<>();
        fx.gui.prompts().ask(p, "second", 3, 50, got::add, () -> { });
        p.chat("ab");
        fx.server.getScheduler().performTicks(5);
        assertTrue(fx.gui.prompts().has(p), "the second prompt must still be waiting (the first line's twin was not treated as an answer)");
        assertTrue(got.isEmpty());
    }

    @Test
    void theModernEventFirstThenTheLegacyTwinIsAlsoProcessedOnce() {
        AtomicInteger got = new AtomicInteger();
        fx.gui.prompts().ask(mod, "Type", 3, 80, t -> got.incrementAndGet(), () -> { });
        Set<Audience> viewers = new HashSet<>(fx.server.getOnlinePlayers());
        AsyncChatEvent modern = new AsyncChatEvent(true, mod, viewers, ChatRenderer.defaultRenderer(), Component.text("modern first"), Component.text("modern first"), null);
        AsyncPlayerChatEvent legacy = new AsyncPlayerChatEvent(true, mod, "modern first", new HashSet<>(fx.server.getOnlinePlayers()));
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            fx.server.getPluginManager().callEvent(modern);
            fx.server.getPluginManager().callEvent(legacy);
        }).join();
        assertTrue(modern.isCancelled() && legacy.isCancelled());
        assertTrue(legacy.getRecipients().isEmpty());
        assertTrue(fx.await(() -> got.get() >= 1));
        fx.server.getScheduler().performTicks(5);
        assertEquals(1, got.get());
    }
}
