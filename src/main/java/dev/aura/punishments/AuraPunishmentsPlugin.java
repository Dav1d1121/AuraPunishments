package dev.aura.punishments;

import dev.aura.punishments.command.ApCommand;
import dev.aura.punishments.command.PunishCommand;
import dev.aura.punishments.command.PunishmentsCommand;
import dev.aura.punishments.config.ConfigException;
import dev.aura.punishments.config.Messages;
import dev.aura.punishments.config.OffenseCatalog;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Database;
import dev.aura.punishments.gui.Gui;
import dev.aura.punishments.integration.BukkitEnforcement;
import dev.aura.punishments.integration.BukkitLevelResolver;
import dev.aura.punishments.integration.BukkitNotifier;
import dev.aura.punishments.integration.CoreProtectBridge;
import dev.aura.punishments.integration.DiscordService;
import dev.aura.punishments.integration.JailBootstrap;
import dev.aura.punishments.integration.JailEventListener;
import dev.aura.punishments.integration.JailPort;
import dev.aura.punishments.integration.LuckPermsBackend;
import dev.aura.punishments.integration.UnavailableJailPort;
import dev.aura.punishments.listener.ChatListener;
import dev.aura.punishments.listener.CommandGuardListener;
import dev.aura.punishments.listener.ConnectionListener;
import dev.aura.punishments.service.*;
import dev.aura.punishments.util.Clock;
import dev.aura.punishments.util.Durations;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * AuraPunishments: GUI-driven moderation, judicial case management and punishment enforcement for Aurehaven.
 * Jailing is delegated to JailReloaded; this plugin never implements a jail of its own.
 */
public class AuraPunishmentsPlugin extends JavaPlugin implements Gui.AdminHooks, org.bukkit.event.Listener {
    private Core core;
    private Database db;
    private DiscordService discord;
    private ReviewService reviews;
    private WorkQueueService workQueue;
    private ExportService exports;
    private CoreProtectService coreProtect;
    private Gui gui;
    private LuckPermsBackend luckPerms;
    private PunishmentService punishments;
    private CustodyService custody;
    private JailService jails;
    private PunishmentRecords records;
    private PunishmentAppeals appeals;
    private CaseService cases;
    private JudicialService judicial;
    private DirectService direct;
    private AdminService admin;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private volatile AdminService.Counts lastCounts;
    private volatile boolean enabledOk;
    private JailEventListener jailListener;

    // ------------------------------------------------------------------ lifecycle
    @Override
    public void onEnable() {
        long t0 = System.currentTimeMillis();
        for (String f : List.of("config.yml", "offenses.yml", "messages.yml")) {
            if (!new File(getDataFolder(), f).exists()) saveResource(f, false);
        }

        writeUpgradeReference();
        Settings settings;
        OffenseCatalog offenses;
        Messages messages;
        try {
            settings = Settings.parse(load("config.yml"), secrets(), System::getenv);
            offenses = OffenseCatalog.parse(load("offenses.yml"));
            messages = Messages.parse(load("messages.yml"), defaults("messages.yml"));
        } catch (ConfigException e) {
            getLogger().severe("AuraPunishments cannot start: its configuration is invalid.");
            for (String p : e.problems()) getLogger().severe("  - " + p);
            getLogger().severe("Fix the problems above (or delete the file to regenerate it) and restart.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        db = settings.storage.shared()
                ? Database.shared(settings.storage, getLogger(), settings.network.serverName())
                : new Database(new File(getDataFolder(), settings.dbFile).toPath(), getLogger(), settings.network.serverName());
        try {
            db.open();
        } catch (java.sql.SQLException e) {
            getLogger().severe("AuraPunishments cannot open its database (" + settings.storage + "): " + e.getMessage());
            getLogger().severe("No punishment can be enforced without it, so the plugin is disabling itself. Existing data is untouched.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        MainThread main = new MainThread() {
            @Override
            public CompletableFuture<Void> later(long ticks) {
                CompletableFuture<Void> f = new CompletableFuture<>();
                try {
                    Bukkit.getScheduler().runTaskLater(AuraPunishmentsPlugin.this, () -> f.complete(null), Math.max(1, ticks));
                } catch (RuntimeException e) { // plugin disabled
                    f.completeExceptionally(e);
                }
                return f;
            }

            @Override
            public <T> CompletableFuture<T> call(Supplier<T> work) {
                if (Bukkit.isPrimaryThread()) {
                    try {
                        return CompletableFuture.completedFuture(work.get());
                    } catch (Throwable t) {
                        return CompletableFuture.failedFuture(t);
                    }
                }
                CompletableFuture<T> f = new CompletableFuture<>();
                try {
                    Bukkit.getScheduler().runTask(AuraPunishmentsPlugin.this, () -> {
                        try {
                            f.complete(work.get());
                        } catch (Throwable t) {
                            f.completeExceptionally(t);
                        }
                    });
                } catch (RuntimeException e) { // plugin disabled
                    f.completeExceptionally(e);
                }
                return f;
            }
        };

        // (MainThread.later is added below via the wrapper)
        JailPort port = JailBootstrap.connect(getServer(), getLogger(), settings.jailEnabled);
        core = new Core(db, Clock.SYSTEM, getLogger(), main, settings, offenses, port, null, e -> { });
        core.messages = messages;
        discord = new DiscordService(() -> core.settings, getLogger(), db);
        discord.onAdminAlert(msg -> Bukkit.getScheduler().runTask(this, () -> {
            for (org.bukkit.entity.Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.hasPermission(Perms.ADMIN)) pl.sendMessage(core.messages.get("info", "message", msg));
            }
        }));
        core.events = new BukkitNotifier(this, core, discord);
        BukkitLevelResolver levels = new BukkitLevelResolver(core);
        core.levels = levels;
        BukkitEnforcement enforcement = new BukkitEnforcement(this, core);
        core.enforcement = enforcement;
        try {
            if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
                luckPerms = LuckPermsBackend.create();
                if (luckPerms != null) levels.backend(luckPerms);
            }
        } catch (LinkageError | RuntimeException e) {
            getLogger().warning("LuckPerms was found but its API could not be used (" + e + "). Offline staff protection falls back to cached levels.");
            luckPerms = null;
        }

        punishments = new PunishmentService(core);
        custody = new CustodyService(core, punishments);
        jails = new JailService(core, punishments);
        punishments.bind(jails);
        reviews = new ReviewService(core, punishments);
        cases = new CaseService(core, custody);
        punishments.bind(cases);
        records = new PunishmentRecords(core, cases);
        appeals = new PunishmentAppeals(core, punishments);
        judicial = new JudicialService(core, custody, punishments);
        direct = new DirectService(core, punishments);
        admin = new AdminService(core, punishments);
        workQueue = new WorkQueueService(core);
        exports = new ExportService(core, cases, getDataFolder().toPath());
        coreProtect = new CoreProtectService(core, cases);
        try {
            CoreProtectPort cp = getServer().getPluginManager().getPlugin("CoreProtect") == null ? null : CoreProtectBridge.create();
            if (cp != null) coreProtect.port(cp);
        } catch (LinkageError | RuntimeException e) {
            getLogger().warning("CoreProtect was found but its API could not be used (" + e + "). CoreProtect evidence lookups are unavailable.");
        }
        PlayerResolver resolver = new PlayerResolver(core);

        // Network mode: join the registry and take the change-feed cursor BEFORE the in-memory index is loaded, so a change made in
        // between is read from the feed instead of being lost.
        if (db.shared()) {
            NetworkService network = new NetworkService(core, punishments);
            try {
                var joined = network.start(getPluginMeta().getVersion(), false, settings.network.maxClockSkewSeconds() * 1000L, settings.network.allowDuplicateName()).get(30, TimeUnit.SECONDS);
                if (!joined.isOk()) {
                    getLogger().severe("AuraPunishments cannot join the network: " + joined.message());
                    Bukkit.getPluginManager().disablePlugin(this);
                    return;
                }
            } catch (Exception e) {
                getLogger().severe("AuraPunishments cannot join the network: " + e.getMessage());
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
            core.network = network;
        }

        try {
            var loaded = punishments.loadCache().get(30, TimeUnit.SECONDS);
            if (!loaded.isOk()) throw new IllegalStateException(loaded.message());
            resolver.loadKnownNames().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            getLogger().severe("Could not load active punishments: " + e.getMessage() + ". Disabling so bans and mutes are never silently skipped.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        gui = new Gui(this, core, resolver, punishments, custody, cases, judicial, direct, admin, this);
        gui.attach(reviews, workQueue, exports, coreProtect);
        gui.attach(records, appeals, jails);
        var pm = getServer().getPluginManager();
        enforcement.promptCheck(u -> { org.bukkit.entity.Player pp = Bukkit.getPlayer(u); return pp != null && gui.prompts().has(pp); });
        pm.registerEvents(gui.prompts(), this);   // prompts first so a typed answer is never treated as chat
        pm.registerEvents(gui.manager(), this);
        pm.registerEvents(new ConnectionListener(core, punishments, resolver, (BukkitEnforcement) core.enforcement), this);
        pm.registerEvents(new ChatListener(this, core, punishments), this);
        pm.registerEvents(new CommandGuardListener(core, punishments, custody, jails, gui.prompts()), this);
        registerJailListener();
        pm.registerEvents(this, this); // administrator warning while judicial work is still open

        var punish = new PunishCommand(gui);
        getCommand("punish").setExecutor(punish);
        getCommand("punish").setTabCompleter(punish);
        getCommand("punishments").setExecutor(new PunishmentsCommand(gui));
        var ap = new ApCommand(gui);
        getCommand("aurapunishments").setExecutor(ap);
        getCommand("aurapunishments").setTabCompleter(ap);

        if (luckPerms != null && settings.closeGuisOnPermissionChange) {
            luckPerms.onPermissionChange(this, p -> {
                if (gui.manager().hasOpenMenu(p) && !p.hasPermission(Perms.PUNISH) && !p.hasPermission(Perms.DASHBOARD)) {
                    gui.manager().close(p);
                    p.sendMessage(core.messages.get("command.no-permission"));
                }
            });
        }

        startTasks();
        // Recover anything interrupted by a crash or restart.
        custody.reconcileOnStartup().thenCompose(r -> judicial.resumeOps()).thenCompose(v -> custody.refreshIndex())
                .thenCompose(v -> jails.reconcileOnStartup()).thenCompose(v -> core.mode.refresh()).thenAccept(r -> warnWindDown(null)).exceptionally(t -> {
            getLogger().warning("Startup recovery failed: " + t);
            return null;
        });
        enabledOk = true;
        logStartup(System.currentTimeMillis() - t0);
    }

    @Override
    public void onDisable() {
        enabledOk = false;
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        if (gui != null) {
            gui.prompts().cancelAll();
            gui.manager().closeAll();
        }
        if (luckPerms != null) luckPerms.shutdown();
        if (discord != null) discord.shutdown();
        if (coreProtect != null) coreProtect.shutdown();
        if (core != null && core.network != null) core.network.stop();
        if (db != null) db.close();
    }

    private void registerJailListener() {
        if (jailListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(jailListener);
            jailListener = null;
        }
        if (core.jail.available()) {
            jailListener = new JailEventListener(custody, jails);
            getServer().getPluginManager().registerEvents(jailListener, this);
        }
    }

    /** Console (and optionally one player) warning while the judicial system is off but work is still open. Never changes anything. */
    private void warnWindDown(CommandSender to) {
        if (!windDownWarning()) return;
        var report = core.mode.report();
        boolean casework = core.mode.casework();
        List<String> lines = new ArrayList<>();
        lines.add("The judicial system is switched OFF but court work is still open" + (casework ? " (the case workflow does not touch it)" : "")
                + ". NOTHING has been released, closed or deleted automatically:");
        if (!report.known()) lines.add("  (the open items could not be counted yet)");
        for (String l : casework ? report.courtLines() : report.lines()) lines.add("  - " + l);
        lines.add("Finish them with the normal tools (/ap judge, /ap cases open). Details and the resolution steps: docs/JUDICIAL-MODE.md. "
                + "Switching judicial.enabled back to true at any time restores everything.");
        if (to == null) {
            lines.forEach(getLogger()::warning);
        } else {
            for (String l : lines) to.sendMessage(core.messages.get("error", "message", l));
        }
    }

    /** True while court work from before is still open: judicial.enabled false and either nothing replaces it (wind-down) or leftovers remain next to the case workflow. */
    private boolean windDownWarning() {
        var state = core.mode.state();
        return state == JudicialMode.State.WIND_DOWN || (state == JudicialMode.State.CASEWORK && core.mode.report().known() && core.mode.report().courtLeftover() > 0);
    }

    /** Tell administrators who join while judicial work is still open after the system was switched off. */
    @org.bukkit.event.EventHandler
    public void onAdminJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (core == null || !p.hasPermission(Perms.ADMIN) || !windDownWarning()) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) warnWindDown(p);
        }, 60L);
    }

    private void logStartup(long ms) {
        var log = getLogger();
        log.info("AuraPunishments " + getPluginMeta().getVersion() + " enabled in " + ms + "ms (schema v" + db.schemaVersion() + ", "
                + punishments.cache().size() + " active punishments).");
        log.info("JailReloaded: " + core.jail.status());
        log.info("Storage: " + (db.shared() ? "SHARED " + db.dialect().name() + " database " + db.storage() + " (network mode)" : "standalone SQLite file"));
        log.info("Server name (records are stamped with it): " + db.origin());
        log.info("Judicial system: " + (core.mode.enabled() ? "ENABLED (cases, pretrial detention, hearings, verdicts and sentencing are available)"
                : core.mode.casework() ? "OFF (judicial.enabled: false), CASE WORKFLOW ON (casework.enabled: true) - cases are opened, investigated, punished from and closed; no court, punishments are final immediately"
                : "DISABLED (judicial.enabled: false) - punishments are final and immediately enforceable"));
        if (!core.jail.available()) {
            log.warning("Pretrial custody and jail sentences are UNAVAILABLE until JailReloaded is present and compatible. Everything else works.");
        }
        var pm = getServer().getPluginManager();
        log.info("LuckPerms: " + (luckPerms != null ? "connected (offline staff protection active)" : "not found (offline staff protection uses cached levels)"));
        if (pm.getPlugin("AuraCommandFilter") != null) log.info("AuraCommandFilter detected: commands declare permissions, so they stay hidden from players who cannot use them.");
        if (pm.getPlugin("Essentials") != null) log.info("EssentialsX detected: legacy punishment commands are redirected (config legacy-commands). Remove its punishment permissions in LuckPerms; see docs/PERMISSIONS.md.");
        if (pm.getPlugin("TownyChat") != null) log.info("TownyChat detected: mutes cancel chat at LOWEST priority and block channel/private-message commands.");
        if (!core.settings.discord.enabled()) log.info("Discord webhooks are disabled (config discord.enabled).");
    }

    // ------------------------------------------------------------------ timers
    private void startTasks() {
        NetworkService network = core.network;
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
            punishments.expireDue();
            custody.refreshIndex();
            jails.refreshIndex();
        }, 100L, 200L));
        if (network != null) {
            // what the other servers changed, applied here (a login never waits for this: it asks the database directly)
            long syncTicks = Math.max(5L, core.settings.network.syncIntervalMs() / 50L);
            tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, network::poll, syncTicks, syncTicks));
            long beatTicks = core.settings.network.heartbeatSeconds() * 20L;
            tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
                List<java.util.UUID> online = new ArrayList<>();
                for (Player pl : Bukkit.getOnlinePlayers()) online.add(pl.getUniqueId());
                network.heartbeat(online).thenAccept(r -> {
                    if (!r.isOk()) getLogger().warning("Network heartbeat: " + r.message());
                });
            }, beatTicks, beatTicks));
        }
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
            core.mode.refresh();
            admin.counts(Actor.system()).thenAccept(r -> {
                if (r.isOk()) lastCounts = r.value();
            });
            jails.retryStuckReleases();
            // Jobs that must run once per network (not once per server) run on whichever server holds their lease.
            Runnable once = () -> {
                // Judicial housekeeping only runs while there is judicial work (switch on, or open items still being wound down).
                if (core.mode.judicialVisible()) {
                    custody.sweepDeadlines();
                    custody.retryStuckReleases();
                }
                direct.sweepReviews();
                reviews.remind();
                core.notifications.expireStale();
                if (network != null) network.purgeOldEvents(core.settings.network.eventRetentionDays() * Durations.DAY);
            };
            if (network == null) once.run();
            else network.lead("housekeeping", 90_000L).thenAccept(leader -> {
                if (leader) Bukkit.getScheduler().runTask(this, once);
            });
        }, 400L, 1200L));
        long reconcileTicks = Math.max(5, core.settings.custody.reconcileSeconds()) * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (core.mode.judicialVisible()) custody.reconcile();
            jails.reconcile();
        }, reconcileTicks, reconcileTicks));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
            int days = core.settings.auditRetentionDays;
            if (days > 0) admin.pruneAudit(Actor.system(), core.clock.now() - days * Durations.DAY);
        }, 20L * 600, 20L * 3600 * 24));
    }

    // ------------------------------------------------------------------ configuration
    /** Optional webhooks.yml: keeps Discord webhook URLs out of config.yml (null when absent). */
    private YamlConfiguration secrets() {
        File f = new File(getDataFolder(), "webhooks.yml");
        return f.isFile() ? YamlConfiguration.loadConfiguration(f) : null;
    }

    /**
     * An upgraded install keeps its config.yml untouched (new options simply use their built-in defaults). So that
     * administrators can see and adopt the new options, the shipped file is copied next to it once per version.
     */
    private void writeUpgradeReference() {
        try {
            YamlConfiguration current = load("config.yml");
            boolean has11 = current.isConfigurationSection("warnings") && current.isConfigurationSection("review");
            boolean has13 = current.contains("judicial.enabled");
            boolean has131 = current.contains("casework.enabled");
            if (has11 && has13 && has131) return;
            String name = "config-" + getPluginMeta().getVersion() + "-reference.yml";
            File ref = new File(getDataFolder(), name);
            if (ref.exists()) return;
            try (var in = getResource("config.yml")) {
                if (in != null) java.nio.file.Files.copy(in, ref.toPath());
            }
            if (!has11) {
                getLogger().info("Upgrade: your config.yml was NOT changed. New 1.1 options (warnings, review, dashboard, defendant, exports, "
                        + "discord channels...) use safe built-in defaults. See " + name + " for every new option and copy what you want.");
            }
            if (has13 && !has131) {
                getLogger().info("Upgrade to 1.3.1: new optional 'casework' section (the case workflow: with the judicial system off, staff still open a case and punish from it, "
                        + "with no court). Your config.yml was NOT changed and the workflow is OFF until you add 'casework:' / '  enabled: true' at the top level and run /ap reload - "
                        + "see docs/WORKFLOW_CASEWORK.md. " + name + " shows the new section.");
            }
            if (!has13) {
                getLogger().info("Upgrade to 1.3: your config.yml has no 'judicial.enabled' line, so the judicial system stays ON and everything behaves as before. "
                        + "To switch it off (punishments become final immediately), add 'judicial:' / '  enabled: false' and run /ap reload - read docs/JUDICIAL-MODE.md first. "
                        + "All other new options (network, appeals, jail, review rules, Discord punishment log) use safe defaults; " + name + " lists them.");
            }
        } catch (Exception e) {
            getLogger().warning("Could not write the upgrade reference config: " + e.getMessage());
        }
    }

    private YamlConfiguration load(String name) {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), name));
    }

    private YamlConfiguration defaults(String name) {
        var in = getResource(name);
        return in == null ? new YamlConfiguration() : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    @Override
    public void reload(Player by) {
        reloadFor(by);
    }

    /** Validate everything first; swap in the new configuration only if all of it is valid. */
    public void reloadFor(CommandSender sender) {
        CommandSender to = sender == null ? Bukkit.getConsoleSender() : sender;
        try {
            Settings s = Settings.parse(load("config.yml"), secrets(), System::getenv);
            OffenseCatalog o = OffenseCatalog.parse(load("offenses.yml"));
            Messages m = Messages.parse(load("messages.yml"), defaults("messages.yml"));
            boolean judicialBefore = core.settings.judicial.enabled();
            core.settings = s;
            core.offenses = o;
            core.messages = m;
            core.jail = JailBootstrap.connect(getServer(), getLogger(), s.jailEnabled);
            registerJailListener();
            Actor who = sender == null ? Actor.console() : Actors.of(sender);
            core.db.tx(c -> {
                dev.aura.punishments.db.Repo.Audits.append(c, core.clock.now(), who.dbUuid(), who.name(), "admin.reload", null, null, "configuration reloaded", true);
                return null;
            });
            core.emit(DomainEvent.of(DomainEvent.Type.ADMIN_ACTION).key("reload:" + core.clock.now()).actor(who.name()).summary("Configuration reloaded").build());
            to.sendMessage(core.messages.get("command.reload-ok"));
            if (judicialBefore != s.judicial.enabled()) {
                core.db.tx(c -> {
                    dev.aura.punishments.db.Repo.Audits.append(c, core.clock.now(), who.dbUuid(), who.name(), "admin.judicial-switch", null, null,
                            "judicial.enabled " + judicialBefore + " -> " + s.judicial.enabled(), true);
                    return null;
                });
                getLogger().warning("judicial.enabled changed from " + judicialBefore + " to " + s.judicial.enabled() + " by " + to.getName() + ".");
                core.mode.refresh().thenAccept(r -> Bukkit.getScheduler().runTask(this, () -> {
                    if (s.judicial.enabled()) {
                        to.sendMessage(core.messages.get("info", "message", "Judicial system switched ON: cases, pretrial detention, hearings, verdicts and sentencing are available again."));
                    } else {
                        to.sendMessage(core.messages.get("info", "message", "Judicial system switched OFF: new punishments are final and immediately enforceable."));
                        warnWindDown(to);
                        warnWindDown(null);
                    }
                }));
            }
            getLogger().info("Configuration reloaded by " + to.getName() + ". Settings marked [RESTART] in config.yml (database) were not changed.");
        } catch (ConfigException e) {
            to.sendMessage(core.messages.get("command.reload-failed"));
            for (String p : e.problems()) to.sendMessage(core.messages.get("command.reload-problem", "problem", p));
        }
    }

    // ------------------------------------------------------------------ diagnostics
    @Override
    public List<String> diagnostics() {
        List<String> l = new ArrayList<>();
        l.add("AuraPunishments " + getPluginMeta().getVersion() + (enabledOk ? " - running" : " - starting/stopping"));
        l.add("Database: " + (db.healthy() ? "healthy" : "PROBLEM: " + db.lastError()));
        l.add("Active bans/mutes cached: " + punishments.cache().size());
        l.add("JailReloaded: " + core.jail.status());
        l.add("Storage: " + (db.shared() ? "shared " + db.dialect().name() + " " + db.storage() : "standalone SQLite") + "; server name: " + db.origin()
                + ", database schema v" + db.schemaVersion());
        l.add("Judicial system: " + core.mode.state() + (core.mode.state() == JudicialMode.State.WIND_DOWN ? " - " + String.join("; ", core.mode.report().lines())
                : windDownWarning() ? " - court work left over: " + String.join("; ", core.mode.report().courtLines()) : ""));
        l.add("LuckPerms: " + (luckPerms != null ? "connected" : "not connected"));
        l.add("Discord: " + discord.status());
        l.add("CoreProtect: " + coreProtect.status());
        var pm = getServer().getPluginManager();
        l.add("Detected: " + (pm.getPlugin("AuraCommandFilter") != null ? "AuraCommandFilter " : "") + (pm.getPlugin("Essentials") != null ? "EssentialsX " : "")
                + (pm.getPlugin("TownyChat") != null ? "TownyChat " : "") + (pm.getPlugin("CoreProtect") != null ? "CoreProtect " : "")
                + (pm.getPlugin("DiscordSRV") != null ? "DiscordSRV " : ""));
        var c = lastCounts;
        if (c != null) {
            l.add("In custody: " + c.liveCustody() + " (overdue reviews: " + c.overdueCustody() + "), pending appeals: " + c.pendingAppeals()
                    + ", unreviewed emergencies: " + c.unreviewedEmergencies());
            l.add("Pending operations: " + c.unfinishedOps() + " (failed: " + c.failedOps() + "), audit entries: " + c.auditEntries());
        }
        return l;
    }

    // accessors for tests / live harness
    public Core core() {
        return core;
    }

    public CaseService cases() {
        return cases;
    }

    public CustodyService custody() {
        return custody;
    }

    public JudicialService judicial() {
        return judicial;
    }

    public PunishmentService punishments() {
        return punishments;
    }

    public JailService jails() {
        return jails;
    }

    public PunishmentAppeals appeals() {
        return appeals;
    }

    public PunishmentRecords records() {
        return records;
    }

    @Override
    public String discordStatus() {
        return discord.status();
    }

    @Override
    public int discordRetry() {
        return discord.requeueDead();
    }

    public CoreProtectService coreProtectService() {
        return coreProtect;
    }

    public ReviewService reviews() {
        return reviews;
    }

    public DirectService direct() {
        return direct;
    }

    public Gui gui() {
        return gui;
    }
}
