package dev.aura.punishments.command;

import dev.aura.punishments.gui.Gui;
import dev.aura.punishments.gui.Menus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.EvidenceType;
import dev.aura.punishments.service.Actor;
import dev.aura.punishments.service.Actors;
import dev.aura.punishments.service.CaseService.Filter;
import dev.aura.punishments.service.EvidenceValidator;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.service.PunishmentService;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * /ap (/aurapunishments): case browsing, history, judicial dashboard, reload, help and command-line
 * equivalents of the GUI punishments. Every subcommand goes through the same service-layer authorisation
 * as the GUI, so nothing here is a shortcut around permissions, limits, hierarchy or evidence rules.
 */
public final class ApCommand implements CommandExecutor, TabCompleter {
    private record Sub(String name, String usage, String description, Predicate<CommandSender> visible) {}

    private final Gui gui;
    private final List<Sub> subs;

    /** The server console is always trusted; everyone else needs the node. */
    private static boolean has(CommandSender s, String node) {
        return s instanceof org.bukkit.command.ConsoleCommandSender || s.hasPermission(node);
    }

    public ApCommand(Gui gui) {
        this.gui = gui;
        Predicate<CommandSender> viewCases = s -> has(s, Perms.CASE_VIEW_ALL) || has(s, Perms.CASE_VIEW_OWN);
        this.subs = List.of(
                new Sub("help", "/ap help", "Show the commands you can use", s -> true),
                new Sub("case", "/ap case <id>", "Open a case", s -> viewCases.test(s) && s instanceof Player),
                new Sub("cases", "/ap cases [all|open|trial|verdict|sentence|appeals|mine]", "Browse cases", s -> viewCases.test(s) && s instanceof Player),
                new Sub("history", "/ap history <player>", "View a player's disciplinary history", s -> has(s, Perms.HISTORY_VIEW)),
                new Sub("judge", "/ap judge", "Open the judicial dashboard", s -> has(s, Perms.JUDICIAL_VIEW) && s instanceof Player && gui.core().mode.judicialVisible()),
                new Sub("punishment", "/ap punishment <id>", "Open one punishment: evidence, notes, appeals, actions", s -> has(s, Perms.HISTORY_VIEW) && s instanceof Player),
                new Sub("appeals", "/ap appeals", "Open appeals against punishments", s -> (has(s, Perms.APPEAL_DECIDE) || has(s, Perms.APPEAL_FILE) || has(s, Perms.APPEAL_TICKET) || has(s, Perms.JUDICIAL_OVERTURN)) && s instanceof Player),
                new Sub("appeal", "/ap appeal <punishment id> [-t <ticket>] <grounds>", "Record an appeal (Discord ticket reference optional)", s -> has(s, Perms.APPEAL_FILE)),
                new Sub("warn", "/ap warn <player> <reason>", "Issue a warning", s -> has(s, Perms.punish(PunishType.WARN))),
                new Sub("kick", "/ap kick <player> <reason>", "Kick a player", s -> has(s, Perms.punish(PunishType.KICK))),
                new Sub("mute", "/ap mute <player> <duration> <reason> [-e <evidence>]", "Mute a player", s -> has(s, Perms.punish(PunishType.MUTE))),
                new Sub("ban", "/ap ban <player> <duration> <reason> [-e <evidence>]", "Ban a player", s -> has(s, Perms.punish(PunishType.BAN))),
                new Sub("ipban", "/ap ipban <player> <duration> <reason> [-e <evidence>]", "IP-ban a player", s -> has(s, Perms.punish(PunishType.IPBAN))),
                new Sub("jail", "/ap jail <player> <duration|permanent> <reason> [-e <evidence>]", "Jail a player (judicial system off)", s -> has(s, Perms.punish(PunishType.JAIL)) && !gui.core().mode.enabled()),
                new Sub("unjail", "/ap unjail <player> <reason>", "Release a jailed player early", s -> has(s, Perms.REVOKE) || has(s, Perms.REVOKE_ANY) || has(s, Perms.CUSTODY_RELEASE) || has(s, Perms.JUDICIAL_RELEASE)),
                new Sub("unmute", "/ap unmute <player> <reason>", "Revoke an active mute", s -> has(s, Perms.REVOKE) || has(s, Perms.REVOKE_ANY)),
                new Sub("unban", "/ap unban <player> <reason>", "Revoke an active ban", s -> has(s, Perms.REVOKE) || has(s, Perms.REVOKE_ANY)),
                new Sub("review", "/ap review [mine|<id>]", "Owner review queue / your punishments under review", s -> (has(s, Perms.REVIEW_VIEW) || has(s, Perms.PUNISH)) && s instanceof Player),
                new Sub("queue", "/ap queue", "Open the prioritised work queue", s -> (has(s, Perms.JUDICIAL_VIEW) || has(s, Perms.REVIEW_VIEW) || has(s, Perms.EMERGENCY_REVIEW)) && s instanceof Player),
                new Sub("export", "/ap export <case> [md|txt]", "Export a case summary to the exports folder", s -> has(s, Perms.EXPORT)),
                new Sub("warnings", "/ap warnings <player>", "A player's warnings and repeat indicator", s -> has(s, Perms.HISTORY_VIEW)),
                new Sub("discord", "/ap discord [status|retry]", "Discord delivery status / re-queue failed messages", s -> has(s, Perms.ADMIN)),
                new Sub("admin", "/ap admin", "Open the administration screen", s -> (has(s, Perms.ADMIN) || has(s, Perms.AUDIT_VIEW)) && s instanceof Player),
                new Sub("judicial", "/ap judicial [status|recount]", "Judicial system switch and any open work still to finish", s -> has(s, Perms.ADMIN)),
                new Sub("status", "/ap status", "Show system status and integrations", s -> has(s, Perms.ADMIN)),
                new Sub("audit", "/ap audit [verify]", "Verify the audit log integrity", s -> has(s, Perms.ADMIN)),
                new Sub("reload", "/ap reload", "Reload and validate the configuration", s -> has(s, Perms.ADMIN_RELOAD)));
    }

    // ------------------------------------------------------------------ helpers
    private <T> void reply(CommandSender s, CompletableFuture<Result<T>> f, Consumer<T> ok) {
        f.whenComplete((res, ex) -> Bukkit.getScheduler().runTask(gui.plugin(), () -> {
            if (ex != null || res == null) {
                gui.messages().sendError(s, "That action failed unexpectedly. Nothing was changed; see the console.");
                return;
            }
            if (!res.isOk()) {
                gui.messages().sendError(s, res.message());
                return;
            }
            ok.accept(res.value());
        }));
    }

    private Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        gui.messages().send(s, "command.players-only");
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!has(sender, Perms.COMMAND)) {
            gui.messages().send(sender, "command.no-permission");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        Sub sub = subs.stream().filter(x -> x.name().equals(name)).findFirst().orElse(null);
        if (sub == null) {
            gui.messages().send(sender, "command.unknown-subcommand");
            return true;
        }
        if (!sub.visible().test(sender) && !(sub.name().equals("history") || sub.name().equals("case") || sub.name().equals("cases"))) {
            gui.messages().send(sender, "command.no-permission");
            return true;
        }
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        switch (name) {
            case "reload" -> gui.admin.reload(sender instanceof Player p ? p : null);
            case "case" -> openCase(sender, rest);
            case "cases" -> openCases(sender, rest);
            case "history" -> history(sender, rest);
            case "judge" -> {
                Player p = player(sender);
                if (p != null) Menus.openJudicial(gui, p);
            }
            case "punishment" -> {
                Player p = player(sender);
                if (p == null) break;
                if (rest.length != 1 || !rest[0].matches("#?\\d{1,12}")) gui.messages().sendInfo(sender, "Usage: /ap punishment <id>");
                else Menus.openPunishment(gui, p, Long.parseLong(rest[0].replace("#", "")));
            }
            case "appeals" -> {
                Player p = player(sender);
                if (p != null) Menus.openAppeals(gui, p);
            }
            case "appeal" -> appeal(sender, rest);
            case "unjail" -> unjail(sender, rest);
            case "judicial" -> judicial(sender, rest);
            case "admin" -> {
                Player p = player(sender);
                if (p != null) Menus.openAdmin(gui, p);
            }
            case "status" -> gui.admin.diagnostics().forEach(l -> gui.messages().sendInfo(sender, l));
            case "audit" -> reply(sender, gui.admin2().verifyAudit(Actors.of(sender)), bad -> {
                if (bad < 0) gui.messages().send(sender, "success", "message", "Audit log is intact.");
                else gui.messages().sendError(sender, "AUDIT LOG DAMAGED: first inconsistent entry is #" + bad + ".");
            });
            case "review" -> {
                Player p = player(sender);
                if (p != null) Menus.openReviews(gui, p, rest.length > 0 && rest[0].equalsIgnoreCase("mine"), rest);
            }
            case "queue" -> {
                Player p = player(sender);
                if (p != null) Menus.openWorkQueue(gui, p);
            }
            case "export" -> export(sender, rest);
            case "warnings" -> warnings(sender, rest);
            case "discord" -> {
                if (rest.length > 0 && rest[0].equalsIgnoreCase("retry")) gui.messages().sendInfo(sender, "Re-queued " + gui.admin.discordRetry() + " undeliverable Discord message(s).");
                else gui.messages().sendInfo(sender, "Discord: " + gui.admin.discordStatus());
            }
            case "warn", "kick" -> simple(sender, PunishType.valueOf(name.toUpperCase(Locale.ROOT)), rest);
            case "mute", "ban", "ipban", "jail" -> timed(sender, PunishType.valueOf(name.toUpperCase(Locale.ROOT)), rest);
            case "unmute", "unban" -> unpunish(sender, name.equals("unmute"), rest);
            default -> gui.messages().send(sender, "command.unknown-subcommand");
        }
        return true;
    }

    private void help(CommandSender sender) {
        gui.messages().send(sender, "command.help-header");
        for (Sub s : subs) {
            if (s.visible().test(sender)) gui.messages().send(sender, "command.help-line", "usage", s.usage(), "description", s.description());
        }
        if (has(sender, Perms.PUNISH)) gui.messages().send(sender, "command.help-line", "usage", "/punish <player>", "description", "Open a player's punishment screen");
        if (has(sender, Perms.DASHBOARD)) gui.messages().send(sender, "command.help-line", "usage", "/punishments", "description", "Open the staff dashboard");
    }

    private void openCase(CommandSender sender, String[] a) {
        Player p = player(sender);
        if (p == null) return;
        if (a.length != 1) {
            gui.messages().send(p, "info", "message", "Usage: /ap case <id>  (for example /ap case 12 or /ap case AP-0012)");
            return;
        }
        try {
            long id = Long.parseLong(a[0].toUpperCase(Locale.ROOT).replace("AP-", ""));
            Menus.openCase(gui, p, id);
        } catch (NumberFormatException e) {
            gui.messages().sendError(p, "That is not a case id.");
        }
    }

    private void openCases(CommandSender sender, String[] a) {
        Player p = player(sender);
        if (p == null) return;
        Filter f = Filter.ALL;
        if (a.length > 0) {
            f = switch (a[0].toLowerCase(Locale.ROOT)) {
                case "open" -> Filter.OPEN;
                case "trial" -> Filter.PENDING_TRIAL;
                case "verdict" -> Filter.AWAITING_VERDICT;
                case "sentence" -> Filter.AWAITING_SENTENCE;
                case "appeals" -> Filter.APPEALS;
                case "mine" -> Filter.MINE;
                default -> Filter.ALL;
            };
        }
        Menus.openCases(gui, p, f);
    }

    private void judicial(CommandSender sender, String[] a) {
        var mode = gui.core().mode;
        Runnable show = () -> {
            gui.messages().sendInfo(sender, "Judicial system: " + mode.state() + " (judicial.enabled: " + mode.enabled() + ")");
            if (mode.state() == dev.aura.punishments.service.JudicialMode.State.DISABLED) {
                gui.messages().sendInfo(sender, "Nothing judicial is open. Punishments are final and immediately enforceable.");
            } else if (mode.state() == dev.aura.punishments.service.JudicialMode.State.WIND_DOWN) {
                gui.messages().sendError(sender, "Judicial work is still open. Nothing is released, closed or deleted automatically:");
                for (String l : mode.report().lines()) gui.messages().sendInfo(sender, " - " + l);
                gui.messages().sendInfo(sender, "Finish them with /ap judge and /ap cases open (see docs/JUDICIAL-MODE.md). Setting judicial.enabled: true restores everything.");
            }
        };
        if (a.length > 0 && a[0].equalsIgnoreCase("recount")) mode.refresh().thenRun(() -> Bukkit.getScheduler().runTask(gui.plugin(), show));
        else show.run();
    }

    private void unjail(CommandSender sender, String[] a) {
        if (a.length < 2) {
            gui.messages().sendInfo(sender, "Usage: /ap unjail <player> <reason>");
            return;
        }
        String reason = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length));
        resolve(sender, a[0], t -> reply(sender, gui.jails.unjail(Actors.of(sender), t.uuid(), reason), p ->
                gui.messages().send(sender, "success", "message", t.name() + " released from jail (punishment #" + p.id() + ").")));
    }

    private void appeal(CommandSender sender, String[] a) {
        if (a.length < 2 || !a[0].matches("#?\\d{1,12}")) {
            gui.messages().sendInfo(sender, "Usage: /ap appeal <punishment id> [-t <ticket>] <grounds>");
            return;
        }
        long id = Long.parseLong(a[0].replace("#", ""));
        List<String> rest = new ArrayList<>(List.of(a).subList(1, a.length));
        String ticket = null;
        for (int i = 0; i < rest.size() - 1; i++) {
            if (rest.get(i).equals("-t")) {
                ticket = rest.get(i + 1);
                rest.remove(i + 1);
                rest.remove(i);
                break;
            }
        }
        String grounds = String.join(" ", rest);
        reply(sender, gui.appeals.file(Actors.of(sender), id, grounds, ticket), ap ->
                gui.messages().send(sender, "success", "message", "Appeal #" + ap.id() + " recorded against punishment #" + id + ". The punishment stays in force until it is decided."));
    }

    private void history(CommandSender sender, String[] a) {
        if (!has(sender, Perms.HISTORY_VIEW)) {
            gui.messages().send(sender, "command.no-permission");
            return;
        }
        if (a.length != 1) {
            gui.messages().send(sender, "info", "message", "Usage: /ap history <player>");
            return;
        }
        resolve(sender, a[0], t -> {
            if (sender instanceof Player p) {
                Menus.openHistory(gui, p, t);
            } else {
                reply(sender, gui.punishments.history(Actor.console(), t.uuid(), 10, 0), list -> {
                    gui.messages().send(sender, "info", "message", "Last punishments of " + t.name() + ":");
                    for (Punishment pun : list) {
                        gui.messages().send(sender, "info", "message", "#" + pun.id() + " " + pun.type() + " " + pun.status() + " - " + pun.reason());
                    }
                });
            }
        });
    }

    private void export(CommandSender sender, String[] a) {
        if (a.length < 1 || a.length > 2) {
            gui.messages().sendInfo(sender, "Usage: /ap export <case> [md|txt]");
            return;
        }
        long id;
        try {
            id = Long.parseLong(a[0].toUpperCase(Locale.ROOT).replace("AP-", ""));
        } catch (NumberFormatException e) {
            gui.messages().sendError(sender, "That is not a case id.");
            return;
        }
        var fmt = dev.aura.punishments.service.ExportService.Format.parse(a.length > 1 ? a[1] : "md");
        reply(sender, gui.exports.export(Actors.of(sender), id, fmt, true), ex -> gui.messages().send(sender, "success", "message", "Saved " + ex.fileName() + " in the exports folder."));
    }

    private void warnings(CommandSender sender, String[] a) {
        if (a.length != 1) {
            gui.messages().sendInfo(sender, "Usage: /ap warnings <player>");
            return;
        }
        resolve(sender, a[0], t -> {
            if (sender instanceof Player p) {
                Menus.openWarnings(gui, p, t);
            } else {
                reply(sender, gui.punishments.warningHistory(Actor.console(), t.uuid(), 20), h -> {
                    gui.messages().sendInfo(sender, t.name() + ": " + h.recentCount() + " warning(s) in the repeat window" + (h.repeatOffender() ? " - REPEAT WARNINGS" : "") + " (indicator only).");
                    for (var e : h.entries()) gui.messages().sendInfo(sender, "#" + e.warning().id() + " " + e.warning().reason() + (e.followUps() > 0 ? " [later action taken]" : ""));
                });
            }
        });
    }

    private void resolve(CommandSender sender, String name, Consumer<Target> ok) {
        reply(sender, gui.resolver.resolve(name), ok);
    }

    // ------------------------------------------------------------------ command-line punishments
    private static List<EvidenceValidator.Input> extractEvidence(List<String> tokens) {
        List<EvidenceValidator.Input> out = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).equals("-e") && i + 1 < tokens.size()) {
                String ref = tokens.get(i + 1);
                out.add(new EvidenceValidator.Input(Text.isHttpUrl(ref) ? EvidenceType.EXTERNAL_LINK : EvidenceType.OTHER, ref, ""));
                tokens.remove(i + 1);
                tokens.remove(i);
                i--;
            }
        }
        return out;
    }

    private void simple(CommandSender sender, PunishType type, String[] a) {
        if (a.length < 2) {
            gui.messages().send(sender, "info", "message", "Usage: /ap " + type.node() + " <player> <reason>");
            return;
        }
        List<String> rest = new ArrayList<>(List.of(a).subList(1, a.length));
        List<EvidenceValidator.Input> ev = extractEvidence(rest);
        String reason = String.join(" ", rest);
        resolve(sender, a[0], t -> reply(sender, gui.punishments.issueQuick(Actors.of(sender),
                new PunishmentService.Request(t, type, 0, reason, ev)), pun ->
                gui.messages().send(sender, "success", "message", type.display() + " issued to " + t.name() + " (#" + pun.id() + ")")));
    }

    private void timed(CommandSender sender, PunishType type, String[] a) {
        if (a.length < 3) {
            gui.messages().send(sender, "info", "message", "Usage: /ap " + type.node() + " <player> <duration|permanent> <reason> [-e <evidence>]");
            return;
        }
        long dur = Durations.parse(a[1]);
        if (dur == -2) {
            gui.messages().sendError(sender, "'" + a[1] + "' is not a valid duration (examples: 30m, 2d, 1w, permanent).");
            return;
        }
        List<String> rest = new ArrayList<>(List.of(a).subList(2, a.length));
        List<EvidenceValidator.Input> ev = extractEvidence(rest);
        String reason = String.join(" ", rest);
        resolve(sender, a[0], t -> reply(sender, gui.punishments.issueQuick(Actors.of(sender),
                new PunishmentService.Request(t, type, dur, reason, ev)), pun ->
                gui.messages().send(sender, "success", "message", type.display() + " issued to " + t.name() + " (#" + pun.id() + ")")));
    }

    private void unpunish(CommandSender sender, boolean mute, String[] a) {
        if (a.length < 2) {
            gui.messages().send(sender, "info", "message", "Usage: /ap " + (mute ? "unmute" : "unban") + " <player> <reason>");
            return;
        }
        String reason = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length));
        resolve(sender, a[0], t -> reply(sender, gui.punishments.activeFor(t.uuid()), list -> {
            Punishment target = list.stream().filter(p -> mute ? p.type() == PunishType.MUTE : (p.type() == PunishType.BAN || p.type() == PunishType.IPBAN)).findFirst().orElse(null);
            if (target == null) {
                gui.messages().sendError(sender, t.name() + " has no active " + (mute ? "mute" : "ban") + ".");
                return;
            }
            reply(sender, gui.punishments.revoke(Actors.of(sender), target.id(), reason), p ->
                    gui.messages().send(sender, "success", "message", "Revoked #" + p.id() + " for " + t.name() + "."));
        }));
    }

    // ------------------------------------------------------------------ tab completion
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!has(sender, Perms.COMMAND)) return List.of();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            return subs.stream().filter(s -> s.visible().test(sender)).map(Sub::name).filter(n -> n.startsWith(p)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        Sub s = subs.stream().filter(x -> x.name().equals(sub)).findFirst().orElse(null);
        if (s == null || !s.visible().test(sender)) return List.of();
        boolean needsPlayer = List.of("history", "warn", "kick", "mute", "ban", "ipban", "jail", "unjail", "unmute", "unban").contains(sub);
        if (args.length == 2 && needsPlayer && has(sender, Perms.PUNISH_OTHERS)) return PlayerNames.suggest(gui, sender, args[1]);
        if (args.length == 2 && sub.equals("cases")) return List.of("all", "open", "trial", "verdict", "sentence", "appeals", "mine");
        if (args.length == 3 && List.of("mute", "ban", "ipban", "jail").contains(sub)) {
            PunishType t = PunishType.valueOf(sub.toUpperCase(Locale.ROOT));
            Actor actor = Actors.of(sender);
            List<String> out = new ArrayList<>();
            for (String d : List.of("15m", "1h", "6h", "1d", "3d", "7d", "14d", "30d", "90d", "permanent")) {
                if (!d.startsWith(args[2].toLowerCase(Locale.ROOT))) continue;
                if (gui.core().auth.checkDuration(actor, t, Durations.parse(d), false).isOk()) out.add(d);
            }
            return out;
        }
        if (args.length == 2 && sub.equals("audit") && has(sender, Perms.ADMIN)) return List.of("verify");
        if (args.length == 2 && sub.equals("judicial") && has(sender, Perms.ADMIN)) return List.of("status", "recount");
        return List.of();
    }
}
