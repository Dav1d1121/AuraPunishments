package dev.aura.punishments.gui;

import dev.aura.punishments.config.Offense;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.service.CaseService;
import dev.aura.punishments.service.EvidenceValidator;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.service.PunishmentService;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Case creation wizard: offense, severity, description, context and evidence on one screen. The draft lives
 * in this menu only; nothing is saved until Submit, and the service validates everything again.
 *
 * <p>In the case workflow (judicial system off) there is also a PUNISHMENT button. It opens the same punishment screens used everywhere
 * else (choose the type, then duration, reason and note) but keeps the choice on this draft; Submit then opens the case and issues the
 * punishment in one step. The punishment is final and immediate: there is no court and no owner approval.
 */
final class CaseCreateMenu extends Menu {
    private final Target target;
    private Offense offense;
    private Severity severity;
    private String description = "";
    private String context = "";
    private final List<EvidenceValidator.Input> evidence = new ArrayList<>();

    // case workflow: the punishment chosen for this case (null = open the case only)
    private QuickPunishMenu.Draft punishment;
    private Offense.Recommendation recommended;
    private long strikes;

    private CaseCreateMenu(Gui gui, Player viewer, Menu parent, Target target) {
        super(gui, viewer, parent);
        this.target = target;
    }

    static void open(Gui gui, Player p, Menu parent, Target t) {
        new CaseCreateMenu(gui, p, parent, t).open();
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple>New case: <white><v>", "v", target.name());
    }

    @Override
    protected void render() {
        navigation();
        final boolean casework = gui.core().mode.casework();
        set(4, Items.head(target.uuid(), target.name(), Items.t("<white><b><v>", "v", target.name()),
                List.of(Items.t("<gray>Accused"), Items.t(gui.core().mode.casework() ? "<gray>Opened by (you): <white><v>" : "<gray>Prosecutor (you): <white><v>", "v", viewer.getName()))));

        // 1. offense
        List<Component> o = new ArrayList<>();
        if (offense == null) o.add(Items.t("<red>Required - click to choose"));
        else {
            o.add(Items.t("<white><v>", "v", offense.display()));
            o.add(Items.t("<gray><v>", "v", offense.description()));
            if (offense.minEvidenceCase() > 0) o.add(Items.t("<yellow>Needs at least <n> evidence item(s)", "n", String.valueOf(offense.minEvidenceCase())));
            o.add(Items.t("<yellow>Click to change"));
        }
        set(11, Items.item(offense == null ? Material.BOOKSHELF : Items.material(offense.icon(), Material.PAPER), Items.t("<gold><b>1. Offense"), o),
                (p, t) -> new OffenseSelectMenu(gui, p, this, off -> true, "", this::offensePicked).open());

        // 2. severity
        set(12, Items.item(severity == null ? Material.GRAY_DYE : Views.severityIcon(severity), Items.t("<gold><b>2. Severity"),
                List.of(Items.t("<white><v>", "v", severity == null ? "choose an offense first" : severity.name()), Items.t("<yellow>Click to cycle"))), (p, t) -> {
            Severity cur = severity == null ? Severity.MINOR : severity;
            severity = Severity.values()[(cur.ordinal() + 1) % Severity.values().length];
            refresh();
        });

        // 3. description
        set(13, Items.item(Material.WRITABLE_BOOK, Items.t("<gold><b>3. Incident description"), textLore(description, "<red>Required - what happened?")),
                (p, t) -> gui.prompts().ask(p, "Describe the incident (at least " + gui.settings().cases.minDescription() + " characters).",
                        gui.settings().cases.minDescription(), gui.settings().cases.maxDescription(), text -> {
                            description = text;
                            open();
                        }, this::open));

        // 4. context
        set(14, Items.item(Material.OAK_SIGN, Items.t("<gold><b>4. Contextual notes <gray>(optional)"), textLore(context, "<gray>Anything else the judge should know")),
                (p, t) -> gui.prompts().ask(p, "Type any contextual notes.", 3, gui.settings().cases.maxNote(), text -> {
                    context = text;
                    open();
                }, this::open));

        // 5. evidence
        List<Component> ev = new ArrayList<>();
        int need = Math.max(offense == null ? 0 : offense.minEvidenceCase(), gui.settings().cases.requireEvidenceOnCreate() ? 1 : 0);
        if (casework && punishment != null && gui.settings().quick.requireEvidence().contains(punishment.type())) need = Math.max(need, 1);
        final int needEvidence = need;
        ev.add(Items.t("<gray>Items: <white><n><gray> / required: <white><r>", "n", String.valueOf(evidence.size()), "r", String.valueOf(needEvidence)));
        if (casework && punishment != null && gui.settings().quick.requireEvidence().contains(punishment.type())) {
            ev.add(Items.t("<yellow>A <t> needs evidence", "t", punishment.type().display()));
        }
        for (EvidenceValidator.Input in : evidence) ev.add(Items.t("<dark_gray>- <white><t>: <aqua><v>", "t", in.type().name().toLowerCase(), "v", Text.truncate(in.reference(), 34)));
        ev.add(Items.t("<yellow>Click to add  <dark_gray>| <yellow>Shift+Click: remove last"));
        set(15, Items.item(Material.SPYGLASS, Items.t("<gold><b>5. Evidence"), ev), (p, t) -> {
            if (t.isShiftClick()) {
                if (!evidence.isEmpty()) evidence.remove(evidence.size() - 1);
                refresh();
                return;
            }
            Pickers.addEvidence(gui, p, this, in -> {
                evidence.add(in);
                open();
            });
        });

        // 6. the punishment (case workflow): the usual punishment screens, kept on this draft
        if (casework) renderPunishmentButton();

        // submit
        boolean punishing = casework && punishment != null;
        boolean ready = offense != null && severity != null && description.length() >= gui.settings().cases.minDescription() && evidence.size() >= needEvidence;
        List<Component> sub = new ArrayList<>();
        if (!ready) {
            sub.add(Items.t("<red>Complete the required steps first"));
        } else if (punishing) {
            sub.add(Items.t("<green>Ready to open the case"));
            sub.add(Items.t("<gray>and issue: <white><v>", "v", describePunishment()));
            sub.add(Items.t("<gray>It takes effect immediately and is final."));
            sub.add(Items.t("<gray>The case stays open for notes and appeals."));
        } else {
            sub.add(Items.t("<green>Ready to open the case"));
            sub.add(Items.t(casework ? "<gray>No punishment yet: you can punish from the case afterwards." : "<gray>You become the default prosecutor."));
        }
        set(31, Items.item(ready ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                Items.t(ready ? (punishing ? "<green><b>SUBMIT CASE + " + punishment.type().display().toUpperCase(java.util.Locale.ROOT) : "<green><b>SUBMIT CASE") : "<red><b>Incomplete"), sub), (p, t) -> {
            if (!ready) {
                gui.messages().sendError(p, "Choose the offense, write a description and add the required evidence first.");
                return;
            }
            CaseService.CreateRequest cr = new CaseService.CreateRequest(target, offense.key(), severity, description,
                    context.isBlank() ? null : context, List.copyOf(evidence));
            if (!punishing) {
                gui.call(p, gui.cases.create(gui.actor(p), cr), c -> {
                    gui.success(p, "Case " + c.ref() + " opened against " + target.name() + ".");
                    CaseMenu.open(gui, p, parent, c.id());
                }, this::open);
                return;
            }
            QuickPunishMenu.Draft d = punishment;
            List<String> lines = new ArrayList<>();
            lines.add("Player: " + target.name());
            lines.add("Case: " + offense.display() + " (" + severity.name() + "), " + evidence.size() + " evidence item(s)");
            lines.add("Punishment: " + describePunishment());
            lines.add("Reason: " + d.reason());
            if (d.note() != null && !d.note().isBlank()) lines.add("Staff note attached to the case");
            if (d.type() == dev.aura.punishments.model.PunishType.JAIL) lines.add("The player is jailed through JailReloaded and released automatically when the time is up.");
            lines.add("The case is opened and the punishment takes effect immediately and is final.");
            boolean heavy = gui.settings().quick.typeNameToConfirm() && (d.type() == dev.aura.punishments.model.PunishType.BAN || d.type() == dev.aura.punishments.model.PunishType.IPBAN
                    || (d.type().canBeTimed() && d.duration() == Durations.PERMANENT));
            PunishmentService.Request pr = new PunishmentService.Request(target, d.type(), d.type().canBeTimed() ? d.duration() : 0, d.reason(), List.of(),
                    d.note() == null || d.note().isBlank() ? null : d.note());
            new ConfirmMenu(gui, p, this, d.type().display() + " " + target.name(), lines, heavy ? target.name() : null, () ->
                    gui.call(p, gui.punishments.openCaseAndPunish(gui.actor(p), cr, pr), both -> {
                        gui.success(p, "Case " + both.opened().ref() + " opened and " + d.type().display().toLowerCase() + " issued to " + target.name() + " (#" + both.punishment().id() + ")"
                                + (d.type() == dev.aura.punishments.model.PunishType.WARN && !both.punishment().delivered() ? " - will be shown at next login." : "."));
                        CaseMenu.open(gui, p, parent, both.opened().id());
                    }, this::open)).open();
        });
    }

    // ------------------------------------------------------------------ the punishment step (case workflow)
    private void offensePicked(Offense off) {
        offense = off;
        if (severity == null) severity = off.severity();
        if (!gui.core().mode.casework()) {
            open();
            return;
        }
        // a punishment chosen earlier is dropped if the new offense does not allow it or its length
        if (punishment != null && !stillValid(punishment)) {
            gui.messages().sendError(viewer, off.display() + " does not allow the " + punishment.type().display().toLowerCase() + " you had chosen; choose the punishment again.");
            punishment = null;
        }
        // the offense's recommendation for this player's history is shown as a hint in the chooser
        gui.call(viewer, gui.cases.priors(target.uuid(), off.key(), 0L), n -> {
            strikes = n;
            recommended = off.recommend(n);
            open();
        }, this::open);
    }

    private boolean stillValid(QuickPunishMenu.Draft d) {
        if (PunishChooser.refusal(gui, viewer, d.type(), java.util.Optional.ofNullable(offense)) != null) return false;
        if (!d.type().canBeTimed() || offense == null) return true;
        return d.duration() == Durations.PERMANENT ? offense.maxDuration() == Durations.PERMANENT : !offense.exceedsCap(d.duration());
    }

    private String describePunishment() {
        return punishment.type().display() + (punishment.type().canBeTimed() ? " " + Views.duration(punishment.duration()) : "");
    }

    private void renderPunishmentButton() {
        List<Component> l = new ArrayList<>();
        if (punishment == null) {
            l.add(Items.t("<gray>Optional. Warning, kick, mute, ban, IP ban or jail."));
            l.add(Items.t("<gray>Issued when you submit; final at once."));
            l.add(Items.t("<gray>No owner approval, no court."));
            if (offense == null) l.add(Items.t("<red>Choose the offense first"));
            else {
                if (recommended != null) l.add(Items.t("<aqua>Recommended (<n> earlier): <white><v>", "n", String.valueOf(Math.max(0, strikes)), "v", recommended.describe()));
                l.add(Items.t("<yellow>Click to choose"));
            }
            set(22, Items.item(Material.GOLDEN_SWORD, Items.t("<gold><b>6. Punishment <gray>(optional)"), l), (p, t) -> choosePunishment(p));
            return;
        }
        l.add(Items.t("<white><v>", "v", describePunishment()));
        for (String line : NotesWrap.wrap(punishment.reason(), 40)) l.add(Items.t("<gray><v>", "v", line));
        if (punishment.note() != null && !punishment.note().isBlank()) l.add(Items.t("<dark_gray>+ staff note"));
        l.add(Items.t("<yellow>Click to change  <dark_gray>| <yellow>Shift+Click: remove"));
        set(22, Items.glow(Items.item(Views.punishIcon(punishment.type()), Items.t("<green><b>6. Punishment"), l)), (p, t) -> {
            if (t.isShiftClick()) {
                punishment = null;
                refresh();
            } else {
                choosePunishment(p);
            }
        });
    }

    /** The same type list as everywhere, then the usual screen for that type; its "Use this punishment" returns here. */
    private void choosePunishment(Player p) {
        if (offense == null) {
            gui.messages().sendError(p, "Choose the offense first: it decides which punishments are possible.");
            return;
        }
        PunishChooser.open(gui, p, this, "Punishment for the new case", target.name(), java.util.Optional.of(offense), recommended, type -> {
            long suggested = recommended != null && recommended.type() == type && type.canBeTimed() ? recommended.duration() : 0;
            QuickPunishMenu.openDraft(gui, p, this, target, type, offense, punishment, suggested, draft -> {
                punishment = draft;
                open();
            });
        });
    }

    private static List<Component> textLore(String text, String emptyHint) {
        List<Component> l = new ArrayList<>();
        if (text.isBlank()) l.add(Items.t(emptyHint));
        else {
            String rest = text;
            int n = 0;
            while (!rest.isEmpty() && n++ < 4) {
                int cut = Math.min(rest.length(), 40);
                l.add(Items.t("<white><v>", "v", rest.substring(0, cut)));
                rest = rest.substring(cut);
            }
        }
        l.add(Items.t("<yellow>Click to edit"));
        return l;
    }
}
