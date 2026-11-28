package dev.aura.punishments.gui;

import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.service.CaseService.Filter;
import dev.aura.punishments.service.CaseService.Overview;
import dev.aura.punishments.service.DirectService;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.util.Durations;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The main screen opened by /punish &lt;player&gt;. It shows the player's disciplinary picture and only the
 * actions the staff member holds permission for (hidden or locked, per config). Showing an action never
 * grants it: every service call re-checks authority at execution.
 */
final class TargetMenu extends Menu {
    private final Target target;
    private Overview ov;

    private TargetMenu(Gui gui, Player viewer, Menu parent, Target target, Overview ov) {
        super(gui, viewer, parent);
        this.target = target;
        this.ov = ov;
    }

    static void open(Gui gui, Player p, Menu parent, Target t) {
        gui.call(p, gui.cases.overview(gui.actor(p), t), ov -> new TargetMenu(gui, p, parent, t, ov).open());
    }

    private void reload() {
        gui.call(viewer, gui.cases.overview(gui.actor(viewer), target), o -> {
            this.ov = o;
            open();
        });
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple>Punish: <white><v>", "v", target.name());
    }

    private boolean can(String node) {
        return viewer.hasPermission(node);
    }

    @Override
    protected void render() {
        fill(0, 53);
        long now = gui.core().clock.now();
        // ---- header
        List<Component> head = new ArrayList<>();
        head.add(Items.t(ov.online() ? "<green>● Online" : "<red>● Offline"));
        head.add(Items.t("<gray>UUID: <dark_gray><v>", "v", target.uuid().toString()));
        if (ov.profile() != null) {
            head.add(Items.t("<gray>First seen: <white><v>", "v", Gui.time(ov.profile().firstSeen())));
            head.add(Items.t("<gray>Last seen: <white><v>", "v", ov.online() ? "now" : Gui.time(ov.profile().lastSeen())));
            if (can(Perms.SENSITIVE_IP) && ov.profile().lastIp() != null) head.add(Items.t("<gray>Last IP: <red><v>", "v", ov.profile().lastIp()));
        } else {
            head.add(Items.t("<yellow>No AuraPunishments profile yet"));
        }
        head.add(Component.empty());
        if (ov.active().isEmpty()) head.add(Items.t("<green>No active punishments"));
        for (Punishment p : ov.active()) {
            head.add(Items.t("<red>#<id> <t> <gray>- <e>", "id", String.valueOf(p.id()), "t", p.type().display(),
                    "e", p.expiresAt() == null ? (p.type().canBeTimed() ? "permanent" : "") : Durations.format(Math.max(0, p.expiresAt() - now)) + " left"));
        }
        if (ov.custody() != null) {
            head.add(Items.t("<gold>In custody <gray>(case AP-<c>)", "c", String.format("%04d", ov.custody().caseId())));
        }
        if (can(Perms.HISTORY_VIEW)) {
            head.add(Component.empty());
            head.add(Items.t("<gray>Cases: <white><c> <gray>| Punishments: <white><p> <gray>| Prior guilty: <white><g>",
                    "c", String.valueOf(ov.totalCases()), "p", String.valueOf(ov.totalPunishments()), "g", String.valueOf(ov.priorFindings())));
        }
        set(4, Items.head(target.uuid(), target.name(), Items.t("<white><b><v>", "v", target.name()), head));

        // ---- case & custody row (the judicial system: on = everything, wind-down = only what is still open, off = none of it)
        var mode = gui.core().mode;
        boolean judicialOn = mode.enabled();
        boolean judicialShown = mode.judicialVisible();
        boolean casework = mode.casework();
        boolean casesAvailable = mode.casesAvailable(); // judicial system, or the case workflow
        boolean casesShown = mode.casesVisible();
        boolean canCreate = can(Perms.CASE_CREATE) || can(Perms.REPORT);
        if (casesAvailable) {
            action(10, canCreate, Material.WRITABLE_BOOK, "<green><b>Create Case",
                    casework ? List.of("<gray>Open a case: offense, evidence and notes,", "<gray>then punish from it. <dark_gray>(no court)")
                            : List.of("<gray>Open a disciplinary case", "<gray>(the standard judicial process)"), (p, t) -> CaseCreateMenu.open(gui, p, this, target));
        }
        boolean viewCases = can(Perms.CASE_VIEW_ALL) || can(Perms.CASE_VIEW_OWN);
        if (casesShown) {
            action(11, viewCases, Material.BOOK, "<aqua><b>View Cases",
                    List.of("<gray>Active cases: <white>" + ov.openCases().size() + (ov.openCases().size() >= 5 ? "+" : ""), "<gray>Click to browse all cases"),
                    (p, t) -> CaseListMenu.open(gui, p, this, "Cases: " + target.name(),
                            n -> gui.cases.historyForCases(gui.actor(p), target.uuid(), n, 28), null));
        }
        if (casesAvailable) {
            action(12, can(Perms.EVIDENCE_ADD), Material.SPYGLASS, "<yellow><b>Submit Evidence",
                    List.of("<gray>Attach evidence to one of", "<gray>this player's open cases"), (p, t) -> pickCaseFor("Add evidence to which case?", c -> EvidenceMenu.promptAdd(gui, p, this, c.id())));
        }
        boolean custodyOn = gui.settings().custody.enabled();
        if (!judicialShown) {
            // nothing to do here: custody belongs to the judicial system
        } else if (ov.custody() != null) {
            action(13, viewCases || can(Perms.CUSTODY_VIEW), Material.IRON_BARS, "<gold><b>In Custody",
                    List.of("<gray>Held for case AP-" + String.format("%04d", ov.custody().caseId()), "<gray>Click to open the case"),
                    (p, t) -> CaseMenu.open(gui, p, this, ov.custody().caseId()));
        } else if (judicialOn) {
            action(13, custodyOn && can(Perms.CUSTODY_INITIATE), Material.IRON_BARS, "<gold><b>Pretrial Detention",
                    List.of("<gray>Hold this player in jail (via JailReloaded)", "<gray>until a trial can be held.", "<dark_gray>Review deadline: " + Durations.format(gui.settings().custody.reviewDeadline())),
                    (p, t) -> pickCaseFor("Detain for which case?", c -> new ConfirmMenu(gui, p, this, "Detain " + target.name(),
                            List.of("Case " + c.ref() + " (" + c.offenseKey() + ")", "Held by JailReloaded until the hearing.",
                                    "Auto-review in " + Durations.format(gui.settings().custody.reviewDeadline()), "This is NOT a punishment or a verdict."),
                            null, () -> gui.call(p, gui.custody.initiate(gui.actor(p), c.id()), cu -> {
                        gui.success(p, "Detained " + target.name() + " (" + c.ref() + "). Review by " + Gui.time(cu.deadline()) + ".");
                        reload();
                    }, this::open)).open()));
        }
        Punishment servingJail = ov.active().stream().filter(x -> x.type() == PunishType.JAIL && x.custodyId() == null && x.jailState() != null).findFirst().orElse(null);
        action(14, can(Perms.CUSTODY_VIEW) || can(Perms.JUDICIAL_VIEW) || can(Perms.HISTORY_VIEW) || ov.custody() != null && viewCases, Material.CLOCK, "<gold><b>Jail Status",
                jailStatusLore(now, servingJail), (p, t) -> {
                    if (ov.custody() != null) CaseMenu.open(gui, p, this, ov.custody().caseId());
                    else if (servingJail != null && gui.records != null && can(Perms.HISTORY_VIEW)) PunishmentMenu.open(gui, p, this, servingJail.id());
                });
        action(15, can(Perms.HISTORY_VIEW), Material.CHEST, "<aqua><b>Punishment History",
                List.of("<gray>Cases, findings, acquittals and", "<gray>every punishment, incl. reversals"), (p, t) -> HistoryMenu.open(gui, p, this, target));
        action(16, can(Perms.NOTE_VIEW), Material.OAK_SIGN, "<yellow><b>Staff Notes",
                List.of("<gray>Notes about this player: <white>" + ov.noteCount()), (p, t) -> NotesMenu.open(gui, p, this, null, target));

        action(17, can(Perms.HISTORY_VIEW), Material.YELLOW_BANNER, "<gold><b>Warnings",
                List.of("<gray>Warning history, repeat indicator and", "<gray>whether later action was taken"), (p, t) -> WarningHistoryMenu.open(gui, p, this, target));

        // ---- punishment row
        action(19, gui.direct != null && can(Perms.punish(PunishType.WARN)), Views.punishIcon(PunishType.WARN), "<yellow><b>Issue Warning",
                List.of("<gray>Official warning on record"), (p, t) -> QuickPunishMenu.open(gui, p, this, target, PunishType.WARN));
        action(20, can(Perms.punish(PunishType.KICK)), Views.punishIcon(PunishType.KICK), "<gold><b>Issue Kick",
                List.of("<gray>Disconnect the player", ov.online() ? "<green>Player is online" : "<red>Player is offline"), (p, t) -> QuickPunishMenu.open(gui, p, this, target, PunishType.KICK));
        action(21, can(Perms.punish(PunishType.MUTE)), Views.punishIcon(PunishType.MUTE), "<gold><b>Issue Mute",
                List.of("<gray>Chat, private messages and", "<gray>channels are blocked"), (p, t) -> QuickPunishMenu.open(gui, p, this, target, PunishType.MUTE));
        action(22, can(Perms.punish(PunishType.BAN)), Views.punishIcon(PunishType.BAN), "<red><b>Issue Ban",
                List.of("<gray>Blocks login", "<dark_gray>Evidence required by default"), (p, t) -> QuickPunishMenu.open(gui, p, this, target, PunishType.BAN));
        action(23, can(Perms.punish(PunishType.IPBAN)), Views.punishIcon(PunishType.IPBAN), "<dark_red><b>Issue IP Ban",
                List.of("<gray>Blocks the account and its", "<gray>last known connection address", "<dark_gray>Evidence required by default"), (p, t) -> QuickPunishMenu.open(gui, p, this, target, PunishType.IPBAN));
        if (casework) {
            // the quick buttons stay, but the case is the main path: say so on the player's screen
            set(18, Items.item(Material.PAPER, Items.t("<gray>Quick punishments"), List.of(Items.t("<gray>No case: one click, recorded on the"), Items.t("<gray>punishment itself."),
                    Items.t("<yellow>For anything needing evidence, use <green>Create Case"))));
        }
        boolean directOk = gui.settings().direct.enabled() && can(Perms.DIRECT) && DirectService.DIRECT_TYPES.stream().anyMatch(t -> can(Perms.direct(t)));
        if (judicialOn) {
            action(24, directOk, Material.NETHERITE_SWORD, "<dark_red><b>Direct Punishment",
                    List.of("<red>MAJOR OFFENSES ONLY", "<gray>Skips the hearing. Requires evidence,", "<gray>a justification and confirmation.", "<dark_gray>Not the normal workflow."),
                    (p, t) -> DirectMenu.open(gui, p, this, target));
        } else {
            // judicial system off: jail is an ordinary punishment (JailReloaded does the imprisonment)
            action(24, can(Perms.punish(PunishType.JAIL)), Views.punishIcon(PunishType.JAIL), "<gold><b>Issue Jail",
                    List.of("<gray>Jail the player for a set time", "<gray>(released automatically at the end)", gui.core().jail.available() ? "<green>JailReloaded ready" : "<red>JailReloaded unavailable"),
                    (p, t) -> QuickPunishMenu.open(gui, p, this, target, PunishType.JAIL));
        }
        action(25, gui.settings().emergency.enabled() && can(Perms.EMERGENCY_USE), Material.REDSTONE_TORCH, "<red><b>Emergency Action",
                List.of("<gray>Short protective mute/ban for an", "<gray>urgent threat. Auto-expires and", "<gray>must be reviewed by senior staff."), (p, t) -> EmergencyMenu.open(gui, p, this, target));

        // ---- judicial row (appeals replace it when the judicial system is off)
        if (judicialShown) {
            action(28, can(Perms.JUDICIAL_VIEW), Material.ANVIL, "<light_purple><b>Judicial Review",
                    List.of("<gray>Trials, verdicts and sentences", "<gray>for this player's cases"), (p, t) -> CaseListMenu.open(gui, p, this, "Judicial: " + target.name(),
                            n -> gui.cases.historyForCases(gui.actor(p), target.uuid(), n, 28), null));
        } else if (gui.appeals != null) {
            boolean seeAppeals = can(Perms.APPEAL_DECIDE) || can(Perms.APPEAL_FILE) || can(Perms.APPEAL_TICKET);
            action(28, seeAppeals, Material.LECTERN, "<light_purple><b>Appeals",
                    List.of("<gray>Open appeals against punishments", "<gray>(handled through Discord Ticket Tool)"), (p, t) -> AppealListMenu.open(gui, p, this));
        }
        boolean canRevoke = (can(Perms.REVOKE) || can(Perms.REVOKE_ANY));
        action(30, canRevoke, Material.ENDER_EYE, "<green><b>Release / Revoke Punishment",
                List.of("<gray>Active punishments: <white>" + ov.active().size(), "<gray>Lift a punishment with a recorded reason"),
                (p, t) -> PunishmentListMenu.open(gui, p, this, target));

        // ---- open cases (or, with no cases in use, the punishments in force)
        int slot = 38;
        if (casesShown) {
            for (Case c : ov.openCases()) {
                if (slot > 42) break;
                ItemStack it = Views.caseItem(c);
                set(slot++, it, (p, t) -> CaseMenu.open(gui, p, this, c.id()));
            }
            if (ov.openCases().isEmpty()) {
                set(40, Items.item(Material.LIME_STAINED_GLASS_PANE, "<green>No open cases"));
            }
        } else {
            for (Punishment pun : ov.active()) {
                if (slot > 42) break;
                ItemStack it = Views.punishmentItem(pun, can(Perms.SENSITIVE_IP), now);
                set(slot++, it, gui.records != null && can(Perms.HISTORY_VIEW) ? (p, t) -> PunishmentMenu.open(gui, p, this, pun.id()) : null);
            }
            if (ov.active().isEmpty()) set(40, Items.item(Material.LIME_STAINED_GLASS_PANE, "<green>No punishments in force"));
        }
        set(45, Items.item(Material.BARRIER, "<red>Close"), (p, t) -> p.closeInventory());
        set(53, Items.item(Material.SUNFLOWER, "<yellow>Refresh"), (p, t) -> reload());
    }

    private List<String> jailStatusLore(long now, Punishment serving) {
        List<String> l = new ArrayList<>();
        if (ov.custody() == null && serving != null) {
            l.add("<gold>Serving jail punishment #" + serving.id());
            l.add("<gray>" + dev.aura.punishments.service.JailService.describe(serving).replace("<", "").replace(">", ""));
            l.add("<gray>Click for the punishment's details");
        } else if (ov.custody() == null) {
            l.add("<green>Not in custody");
            l.add("<gray>JailReloaded: <white>" + gui.core().jail.status());
        } else {
            for (Component c : Views.custodyLines(ov.custody(), now)) l.add(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .escapeTags(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c)));
        }
        return l;
    }

    private void pickCaseFor(String heading, java.util.function.Consumer<Case> then) {
        CaseListMenu.open(gui, viewer, this, heading, n -> gui.cases.openCasesFor(gui.actor(viewer), target.uuid(), n, 28), c -> then.accept(c));
    }
}
