package dev.aura.punishments.gui;

import dev.aura.punishments.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Secure free-text entry (reasons, notes, evidence, durations) without an anvil GUI or packets: the GUI
 * closes, the next chat line from that player is captured and CANCELLED (so it is never broadcast, logged to
 * chat plugins or seen by other players), validated and handed to the caller. Typing the cancel word, running
 * a command, disconnecting or timing out aborts safely with nothing changed.
 */
public final class PromptManager implements Listener, PromptManagerAccess {
    private record Session(long token, int min, int max, Consumer<String> ok, Runnable cancelled, BukkitTask timeout) {}

    private final Gui gui;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private long tokens;

    public PromptManager(Gui gui) {
        this.gui = gui;
    }

    @Override
    public boolean has(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    /** Ask for text. Must be called on the main thread. */
    public void ask(Player p, String question, int min, int max, Consumer<String> ok, Runnable cancelled) {
        cancel(p, false);
        p.closeInventory();
        int seconds = gui.settings().gui.promptTimeoutSeconds();
        long token = ++tokens;
        BukkitTask timeout = gui.plugin().getServer().getScheduler().runTaskLater(gui.plugin(), () -> {
            Session s = sessions.get(p.getUniqueId());
            if (s != null && s.token() == token) {
                sessions.remove(p.getUniqueId());
                gui.messages().send(p, "prompt.timeout");
                s.cancelled().run();
            }
        }, seconds * 20L);
        sessions.put(p.getUniqueId(), new Session(token, min, max, ok, cancelled, timeout));
        p.sendMessage(gui.messages().get("info", "message", question));
        gui.messages().send(p, "prompt.header", "cancel", gui.settings().gui.cancelWord(), "seconds", String.valueOf(seconds));
        p.showTitle(Title.title(gui.messages().get("prompt.title"),
                gui.messages().get("prompt.subtitle", "cancel", gui.settings().gui.cancelWord()),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(Math.min(seconds, 30)), Duration.ofMillis(500))));
    }

    /** Abort a running prompt. @param notify tell the player. */
    @Override
    public void cancel(Player p, boolean notify) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        s.timeout().cancel();
        p.clearTitle();
        if (notify) gui.messages().send(p, "prompt.cancelled");
        s.cancelled().run();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        String raw = PlainTextComponentSerializer.plainText().serialize(e.message());
        gui.plugin().getServer().getScheduler().runTask(gui.plugin(), () -> handle(p, s, raw));
    }

    private void handle(Player p, Session s, String raw) {
        if (sessions.get(p.getUniqueId()) != s) return; // replaced or finished meanwhile
        String text = Text.clean(raw);
        if (text.equalsIgnoreCase(gui.settings().gui.cancelWord())) {
            cancel(p, true);
            return;
        }
        if (text.length() < s.min()) {
            gui.messages().send(p, "prompt.too-short", "min", String.valueOf(s.min()));
            return;
        }
        if (text.length() > s.max()) {
            gui.messages().send(p, "prompt.too-long", "max", String.valueOf(s.max()));
            return;
        }
        sessions.remove(p.getUniqueId());
        s.timeout().cancel();
        p.clearTitle();
        try {
            s.ok().accept(text);
        } catch (RuntimeException ex) {
            gui.plugin().getLogger().severe("Prompt handler failed: " + ex);
            gui.messages().sendError(p, "That input could not be processed. Nothing was changed.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.remove(e.getPlayer().getUniqueId());
        if (s != null) s.timeout().cancel();
    }

    public void cancelAll() {
        for (UUID id : sessions.keySet()) {
            Session s = sessions.remove(id);
            if (s != null) s.timeout().cancel();
        }
    }
}
