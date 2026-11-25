package dev.aura.punishments.gui;

import dev.aura.punishments.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Free-text entry (reasons, notes, evidence references, durations, player names) WITHOUT chat.
 *
 * <p>Since 1.2.0 nothing is ever typed into chat: the player gets a native Minecraft dialog window with a text box and
 * Submit / Cancel buttons. The text travels in the dialog response packet, so no chat event exists for any plugin to see,
 * relay or log. One session per staff member; answers are validated (length), invalid answers re-open the dialog with the
 * error and the typed text; timing out, cancelling, disconnecting or a plugin disable ends the session and changes nothing.
 */
public final class PromptManager implements Listener, PromptManagerAccess {
    /** What the dialog must show. */
    public record Request(String question, int min, int max, String initial, String error, int seconds,
                          Consumer<String> onSubmit, Runnable onCancel) {}

    /** Shows / closes the dialog. Production: {@link DialogPresenter}; tests substitute a recording fake. */
    public interface Presenter {
        void show(Player p, Request r);

        void close(Player p);
    }

    private record Session(long token, int min, int max, Consumer<String> ok, Runnable cancelled, BukkitTask timeout, String question) {}

    private final Gui gui;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile Presenter presenter;
    private long tokens;

    public PromptManager(Gui gui) {
        this.gui = gui;
        this.presenter = new DialogPresenter(gui);
    }

    public void presenter(Presenter p) {
        this.presenter = p;
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
                presenter.close(p);
                gui.messages().send(p, "prompt.timeout");
                s.cancelled().run();
            }
        }, seconds * 20L);
        sessions.put(p.getUniqueId(), new Session(token, min, max, ok, cancelled, timeout, question));
        show(p, token, question, min, max, "", null, seconds);
    }

    private void show(Player p, long token, String question, int min, int max, String initial, String error, int seconds) {
        presenter.show(p, new Request(question, min, max, initial, error, seconds,
                text -> gui.plugin().getServer().getScheduler().runTask(gui.plugin(), () -> submit(p, token, text)),
                () -> gui.plugin().getServer().getScheduler().runTask(gui.plugin(), () -> {
                    Session s = sessions.get(p.getUniqueId());
                    if (s != null && s.token() == token) cancel(p, true);
                })));
    }

    /** The player pressed Submit. Stale dialogs (replaced, timed out, cancelled) are ignored. */
    private void submit(Player p, long token, String raw) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.token() != token || !p.isOnline()) return;
        String text = Text.clean(raw == null ? "" : raw);
        String error = null;
        if (text.length() < s.min()) error = gui.messages().plain("prompt.too-short", "min", String.valueOf(s.min()));
        else if (text.length() > s.max()) error = gui.messages().plain("prompt.too-long", "max", String.valueOf(s.max()));
        if (error != null) { // keep the session, show the dialog again with the problem and what was typed
            show(p, token, s.question(), s.min(), s.max(), Text.truncate(text, s.max()), error, gui.settings().gui.promptTimeoutSeconds());
            return;
        }
        sessions.remove(p.getUniqueId());
        s.timeout().cancel();
        try {
            s.ok().accept(text);
        } catch (RuntimeException ex) {
            gui.plugin().getLogger().severe("Prompt handler failed: " + ex);
            gui.messages().sendError(p, "That input could not be processed. Nothing was changed.");
        }
    }

    /** Abort a running prompt. @param notify tell the player. */
    @Override
    public void cancel(Player p, boolean notify) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        s.timeout().cancel();
        presenter.close(p);
        if (notify) gui.messages().send(p, "prompt.cancelled");
        s.cancelled().run();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.remove(e.getPlayer().getUniqueId());
        if (s != null) s.timeout().cancel();
    }

    /** Plugin disable / reload of the GUI layer: end every session and tell the player nothing was changed. */
    public void cancelAll() {
        for (UUID id : sessions.keySet()) {
            Session s = sessions.remove(id);
            if (s == null) continue;
            s.timeout().cancel();
            Player p = gui.plugin().getServer().getPlayer(id);
            if (p != null) {
                presenter.close(p);
                p.sendMessage(gui.messages().get("prompt.cancelled"));
            }
        }
    }
}
