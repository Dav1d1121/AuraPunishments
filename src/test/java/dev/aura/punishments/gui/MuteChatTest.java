package dev.aura.punishments.gui;

import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.service.Actor;
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mute enforcement on BOTH chat events. Paper fires the legacy AsyncPlayerChatEvent first and the modern AsyncChatEvent second
 * whenever any plugin listens to the legacy one, so a muted line must be stopped at the first event, before a Discord relay,
 * channel plugin or chat logger can act on it. (Text entry no longer uses chat at all since 1.2.0; see DialogInputTest.)
 */
class MuteChatTest {
    McFx fx;
    final List<String> seenByRelays = new CopyOnWriteArrayList<>();

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
        fx.server.getPluginManager().registerEvents(new Relay(), fx.plugin);
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private Object[] line(Player p, String text) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            Set<Player> recipients = new HashSet<>(fx.server.getOnlinePlayers());
            AsyncPlayerChatEvent legacy = new AsyncPlayerChatEvent(true, p, text, recipients);
            fx.server.getPluginManager().callEvent(legacy);
            Set<Audience> viewers = new HashSet<>(fx.server.getOnlinePlayers());
            AsyncChatEvent modern = new AsyncChatEvent(true, p, viewers, ChatRenderer.defaultRenderer(), Component.text(text), Component.text(text), null);
            modern.setCancelled(legacy.isCancelled());
            fx.server.getPluginManager().callEvent(modern);
            return new Object[]{legacy, modern};
        }).join();
    }

    private PlayerMock muted(String name) {
        PlayerMock victim = fx.server.addPlayer(name);
        var res = fx.join(fx.plugin.punishments().issueQuick(Actor.console(), new PunishmentService.Request(new Target(victim.getUniqueId(), name, false), PunishType.MUTE,
                Durations.HOUR, "Spamming the global chat channel.", List.of())));
        assertTrue(res.isOk(), res.message());
        fx.messages(victim);
        return victim;
    }

    @Test
    void aMutedPlayersLineIsStoppedAtTheFirstEventAndNoticedOnce() {
        PlayerMock victim = muted("Victim");
        Object[] ev = line(victim, "muted words");
        assertTrue(((AsyncPlayerChatEvent) ev[0]).isCancelled());
        assertTrue(((AsyncPlayerChatEvent) ev[0]).getRecipients().isEmpty());
        assertTrue(((AsyncChatEvent) ev[1]).isCancelled());
        assertTrue(seenByRelays.stream().noneMatch(s -> s.contains("muted words")), "a relay/logger never sees a muted line: " + seenByRelays);
        fx.server.getScheduler().performTicks(3);
        assertEquals(1, fx.messages(victim).stream().filter(m -> m.toLowerCase().contains("muted")).count(), "one notice, not one per event");
    }

    @Test
    void ordinaryChatIsUntouched() {
        PlayerMock other = fx.server.addPlayer("Other");
        Object[] ev = line(other, "hello everyone");
        assertFalse(((AsyncPlayerChatEvent) ev[0]).isCancelled());
        assertFalse(((AsyncChatEvent) ev[1]).isCancelled());
        assertTrue(seenByRelays.contains("legacy:hello everyone") && seenByRelays.contains("modern:hello everyone"));
    }

    @Test
    void anUnmutedLineAfterTheMuteIsRevokedGoesThrough() {
        PlayerMock victim = muted("Victim2");
        var mute = fx.plugin.punishments().muteFor(victim.getUniqueId()).orElseThrow();
        assertTrue(fx.join(fx.plugin.punishments().revoke(Actor.console(), mute.id(), "Mute lifted by test.")).isOk());
        Object[] ev = line(victim, "free again");
        assertFalse(((AsyncPlayerChatEvent) ev[0]).isCancelled());
    }

    @Test
    void aStaffMemberWithAnOpenDialogChatsNormally() {
        PlayerMock mod = fx.staff("Mod", McFx.basicStaff());
        fx.gui.prompts().ask(mod, "Reason?", 3, 80, t -> { }, () -> { });
        Object[] ev = line(mod, "a normal chat line while a dialog is open");
        assertFalse(((AsyncPlayerChatEvent) ev[0]).isCancelled(), "chat is no longer an input channel, so it is never captured");
        assertTrue(fx.gui.prompts().has(mod), "and the dialog session is unaffected");
    }

    @Test
    void theDeclaredLoadOrderPutsAuraPunishmentsBeforeTheChatPlugins() {
        var meta = fx.plugin.getPluginMeta();
        assertTrue(meta.getLoadBeforePlugins().containsAll(List.of("TownyChat", "Essentials", "EssentialsChat", "DiscordSRV")));
        assertFalse(meta.getPluginSoftDependencies().contains("TownyChat") || meta.getPluginSoftDependencies().contains("DiscordSRV"));
    }
}
