package dev.aura.punishments.gui;

import dev.aura.punishments.config.Messages;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.service.*;
import dev.aura.punishments.util.Result;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Everything a menu needs: services, settings, messages, prompts, and the async-call helper. */
public final class Gui {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault());

    private final Plugin plugin;
    private final Core core;
    public final PlayerResolver resolver;
    public final PunishmentService punishments;
    public final CustodyService custody;
    public final CaseService cases;
    public final JudicialService judicial;
    public final DirectService direct;
    private final AdminService adminService;
    /** 1.1.0 services (attached after construction by the plugin / test fixture). */
    public ReviewService reviews;
    public WorkQueueService workQueue;
    public ExportService exports;
    public CoreProtectService coreProtect;
    public final AdminHooks admin;
    private final GuiManager manager;
    private final PromptManager prompts;

    /** Operations that live in the plugin itself (reload, diagnostics). */
    public interface AdminHooks {
        void reload(Player by);

        java.util.List<String> diagnostics();

        /** Discord outbox status line. */
        default String discordStatus() {
            return "n/a";
        }

        /** Re-queue undeliverable Discord messages; returns how many. */
        default int discordRetry() {
            return 0;
        }
    }

    public Gui(Plugin plugin, Core core, PlayerResolver resolver, PunishmentService punishments, CustodyService custody,
               CaseService cases, JudicialService judicial, DirectService direct, AdminService adminService, AdminHooks admin) {
        this.plugin = plugin;
        this.core = core;
        this.resolver = resolver;
        this.punishments = punishments;
        this.custody = custody;
        this.cases = cases;
        this.judicial = judicial;
        this.direct = direct;
        this.adminService = adminService;
        this.admin = admin;
        this.manager = new GuiManager(this);
        this.prompts = new PromptManager(this);
    }

    public void attach(ReviewService reviews, WorkQueueService workQueue, ExportService exports, CoreProtectService coreProtect) {
        this.reviews = reviews;
        this.workQueue = workQueue;
        this.exports = exports;
        this.coreProtect = coreProtect;
    }

    public AdminService admin2() {
        return adminService;
    }

    public Plugin plugin() {
        return plugin;
    }

    public Core core() {
        return core;
    }

    public Settings settings() {
        return core.settings;
    }

    public Messages messages() {
        return core.messages;
    }

    public GuiManager manager() {
        return manager;
    }

    public PromptManager prompts() {
        return prompts;
    }

    public Actor actor(Player p) {
        return Actors.of(p);
    }

    public boolean can(Player p, String node) {
        return p.hasPermission(node);
    }

    public void sound(Player p, String which) {
        String key = switch (which) {
            case "success" -> settings().gui.soundSuccess();
            case "error" -> settings().gui.soundError();
            default -> settings().gui.soundClick();
        };
        if (key == null || key.equalsIgnoreCase("none") || key.isBlank()) return;
        try {
            p.playSound(Sound.sound(Key.key(key), Sound.Source.MASTER, 0.6f, 1f));
        } catch (RuntimeException ignored) {
            // invalid sound key in config: feedback sounds are optional
        }
    }

    public void main(Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }

    public static String time(long millis) {
        return FMT.format(Instant.ofEpochMilli(millis));
    }

    /**
     * Run a service call with the GUI locked against repeated clicks. On success {@code ok} runs on the main
     * thread; on failure the reason is shown and the sound played. The lock is always released.
     */
    public <T> void call(Player p, CompletableFuture<Result<T>> future, Consumer<T> ok) {
        call(p, future, ok, null);
    }

    public <T> void call(Player p, CompletableFuture<Result<T>> future, Consumer<T> ok, Runnable onFail) {
        if (!manager.tryLock(p)) {
            messages().send(p, "command.busy");
            return;
        }
        future.whenComplete((res, ex) -> main(() -> {
            manager.unlock(p);
            if (!p.isOnline()) return;
            if (ex != null || res == null) {
                plugin.getLogger().severe("Service call failed: " + ex);
                messages().sendError(p, "That action failed unexpectedly. Nothing was changed; see the console.");
                sound(p, "error");
                if (onFail != null) onFail.run();
                return;
            }
            if (!res.isOk()) {
                messages().sendError(p, res.message());
                sound(p, "error");
                if (onFail != null) onFail.run();
                return;
            }
            try {
                ok.accept(res.value());
            } catch (RuntimeException e) {
                plugin.getLogger().severe("GUI follow-up failed: " + e);
                if (settings().debug) e.printStackTrace();
            }
        }));
    }

    /** Success feedback shown to the staff member. */
    public void success(Player p, String text) {
        messages().send(p, "success", "message", text);
        sound(p, "success");
    }

    /** Resolve a typed name and run the callback with the target (reports unknown/ambiguous names). */
    public void resolve(Player p, String name, Consumer<PlayerResolver.Target> ok) {
        call(p, resolver.resolve(name), ok);
    }
}
