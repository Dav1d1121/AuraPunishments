package dev.aura.punishments.gui;

import dev.aura.punishments.config.Offense;
import dev.aura.punishments.model.PunishType;
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
 * <p>In the case workflow (judicial system off) the same screen also has the PUNISHMENT: choose what happens (or "no punishment yet"),
 * for how long, and the reason the player sees. The offense's recommendation for this player's history is pre-selected. Submit then opens
 * the case and issues the punishment in one step.
 */
final class CaseCreateMenu extends Menu {
    private final Target target;
    private Offense offense;
    private Severity severity;
    private String description = "";
    private String context = "";
    private final List<EvidenceValidator.Input> evidence = new ArrayList<>();

    // case workflow: the punishment chosen on this screen (null = open the case only)
    private PunishType ptype;
    private long pduration;
    private String preason = "";
    private boolean punishChosenByHand;
    private Offense.Recommendation recommended;
    private long strikes = -1;

    /** Default duration choices when the offense lists none for a type. */
    private static final long[] DEFAULT_PRESETS = {Durations.HOUR, 6 * Durations.HOUR, Durations.DAY, 3 * Durations.DAY, 7 * Durations.DAY, 14 * Durations.DAY, 30 * Durations.DAY};

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
        if (casework && ptype != null && gui.settings().quick.requireEvidence().contains(ptype)) need = Math.max(need, 1);
        final int needEvidence = need;
        ev.add(Items.t("<gray>Items: <white><n><gray> / required: <white><r>", "n", String.valueOf(evidence.size()), "r", String.valueOf(needEvidence)));
        if (casework && ptype != null && gui.settings().quick.requireEvidence().contains(ptype)) ev.add(Items.t("<yellow>A <t> needs evidence", "t", ptype.display()));
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

        if (casework) renderPunishment();

        // submit
        boolean punishing = casework && ptype != null;
        boolean caseReady = offense != null && severity != null && description.length() >= gui.settings().cases.minDescription() && evidence.size() >= needEvidence;
        boolean punishReady = !punishing || (punishmentAllowed(ptype) && (!ptype.canBeTimed() || (pduration != 0 && durationAllowed(ptype, pduration))) && effectiveReason().length() >= gui.settings().cases.minReason());
        boolean ready = caseReady && punishReady;
        List<Component> sub = new ArrayList<>();
        if (ready) {
            sub.add(Items.t("<green>Ready to open the case"));
            if (punishing) {
                sub.add(Items.t("<gray>and issue: <white><v>", "v", describePunishment()));
                sub.add(Items.t("<gray>It takes effect immediately and is final."));
                sub.add(Items.t("<gray>The case stays open for notes and appeals."));
            } else {
                sub.add(Items.t(casework ? "<gray>No punishment yet: you can punish from the case afterwards." : "<gray>You become the default prosecutor."));
            }
        } else {
            sub.add(Items.t("<red>Complete the required steps first"));
            if (!caseReady) sub.add(Items.t("<gray>Case: offense, description" + (needEvidence > 0 ? ", evidence" : "")));
            if (punishing && !punishReady) sub.add(Items.t("<gray>Punishment: " + (ptype.canBeTimed() && pduration == 0 ? "choose a duration" : "check type, duration and reason")));
        }
        set(31, Items.item(ready ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                Items.t(ready ? (punishing ? "<green><b>SUBMIT CASE + " + ptype.display().toUpperCase(java.util.Locale.ROOT) : "<green><b>SUBMIT CASE") : "<red><b>Incomplete"), sub), (p, t) -> {
            if (!ready) {
                gui.messages().sendError(p, punishing && caseReady
                        ? "Finish the punishment first: choose its duration and check the reason."
                        : "Choose the offense, write a description and add the required evidence first.");
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
            String reason = effectiveReason();
            List<String> lines = new ArrayList<>();
            lines.add("Player: " + target.name());
            lines.add("Case: " + offense.display() + " (" + severity.name() + "), " + evidence.size() + " evidence item(s)");
            lines.add("Punishment: " + describePunishment());
            lines.add("Reason shown to the player: " + reason);
            if (ptype == PunishType.JAIL) lines.add("The player is jailed through JailReloaded and released automatically when the time is up.");
            lines.add("The case is opened and the punishment takes effect immediately and is final.");
            boolean heavy = gui.settings().quick.typeNameToConfirm()
                    && (ptype == PunishType.BAN || ptype == PunishType.IPBAN || (ptype.canBeTimed() && pduration == Durations.PERMANENT));
            PunishmentService.Request pr = new PunishmentService.Request(target, ptype, ptype.canBeTimed() ? pduration : 0, reason, List.of(), null);
            new ConfirmMenu(gui, p, this, ptype.display() + " " + target.name(), lines, heavy ? target.name() : null, () ->
                    gui.call(p, gui.punishments.openCaseAndPunish(gui.actor(p), cr, pr), both -> {
                        gui.success(p, "Case " + both.opened().ref() + " opened and " + ptype.display().toLowerCase() + " issued to " + target.name() + " (#" + both.punishment().id() + ")"
                                + (ptype == PunishType.WARN && !both.punishment().delivered() ? " - will be shown at next login." : "."));
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
        // the offense decides which punishments are possible and which one is recommended for this player's history
        gui.call(viewer, gui.cases.priors(target.uuid(), off.key(), 0L), n -> {
            strikes = n;
            recommended = off.recommend(n);
            if (ptype != null && !punishmentAllowed(ptype)) ptype = null; // the new offense does not allow what was chosen before
            if (ptype == null || !punishChosenByHand) applyRecommendation();
            open();
        }, this::open);
    }

    private void applyRecommendation() {
        Offense.Recommendation r = recommended;
        ptype = null;
        pduration = 0;
        if (r == null || !punishmentAllowed(r.type())) return;
        ptype = r.type();
        pduration = r.type().canBeTimed() && durationAllowed(r.type(), r.duration()) ? r.duration() : 0;
    }

    /** May this staff member issue this type, and does the chosen offense allow it (a kick is not a sentence, so it is always allowed)? */
    private boolean punishmentAllowed(PunishType t) {
        if (t == null) return true;
        if (!gui.core().auth.canQuick(gui.actor(viewer), t)) return false;
        if (t == PunishType.JAIL && !gui.core().jail.available()) return false;
        return t == PunishType.KICK || offense == null || offense.allows(t);
    }

    private boolean durationAllowed(PunishType t, long ms) {
        if (!t.canBeTimed()) return true;
        if (ms != Durations.PERMANENT && ms <= 0) return false;
        if (offense != null) {
            if (ms == Durations.PERMANENT ? offense.maxDuration() != Durations.PERMANENT : offense.exceedsCap(ms)) return false;
        }
        return gui.core().auth.checkDuration(gui.actor(viewer), t, ms, false).isOk();
    }

    private String describePunishment() {
        return ptype.display() + (ptype.canBeTimed() ? " " + Views.duration(pduration) : "");
    }

    /** The reason the player sees: what the staff member wrote, otherwise the offense's name. */
    private String effectiveReason() {
        if (!preason.isBlank()) return preason;
        String base = offense == null ? "" : offense.display();
        if (base.length() < gui.settings().cases.minReason() && !description.isBlank()) base = base + " - " + description;
        return Text.truncate(base, gui.settings().cases.maxReason());
    }

    private List<Long> durationChoices(PunishType t) {
        List<Long> out = new ArrayList<>();
        List<Long> own = offense == null ? null : offense.presets().get(t);
        if (own != null && !own.isEmpty()) out.addAll(own);
        else for (long d : DEFAULT_PRESETS) out.add(d);
        if (offense != null && offense.maxDuration() == Durations.PERMANENT && !out.contains(Durations.PERMANENT)) out.add(Durations.PERMANENT);
        out.removeIf(d -> offense != null && d != Durations.PERMANENT && offense.exceedsCap(d));
        return out.size() > 9 ? out.subList(0, 9) : out;
    }

    private void renderPunishment() {
        // 6. the punishment: label + recommendation, then one button per choice
        List<Component> head = new ArrayList<>();
        head.add(Items.t("<gray>What happens when you submit."));
        if (offense == null) {
            head.add(Items.t("<red>Choose the offense first"));
        } else if (recommended != null) {
            head.add(Items.t("<gray>Recommended for <white><n><gray> earlier punishment(s) for this: <white><v>", "n", String.valueOf(Math.max(0, strikes)), "v", recommended.describe()));
            head.add(Items.t("<yellow>Click to use the recommendation"));
        } else {
            head.add(Items.t("<gray>No recommendation for this offense. Choose below."));
        }
        set(18, Items.item(Material.GOLDEN_SWORD, Items.t("<gold><b>6. Punishment"), head), (p, t) -> {
            if (offense == null || recommended == null) return;
            punishChosenByHand = false;
            applyRecommendation();
            refresh();
        });

        // none + one button per type
        set(19, choice(Material.WHITE_BANNER, "No punishment yet", ptype == null, true,
                List.of("<gray>Open the case only. You can punish", "<gray>from the case at any time.")), (p, t) -> {
            punishChosenByHand = true;
            ptype = null;
            pduration = 0;
            refresh();
        });
        PunishType[] types = {PunishType.WARN, PunishType.KICK, PunishType.MUTE, PunishType.BAN, PunishType.IPBAN, PunishType.JAIL};
        for (int i = 0; i < types.length; i++) {
            final PunishType t = types[i];
            boolean can = punishmentAllowed(t) && offense != null;
            boolean selected = ptype == t;
            List<String> lore = new ArrayList<>();
            if (offense == null) lore.add("<red>Choose the offense first");
            else if (!gui.core().auth.canQuick(gui.actor(viewer), t)) lore.add("<red>You may not issue this");
            else if (t == PunishType.JAIL && !gui.core().jail.available()) lore.add("<red>JailReloaded is not available");
            else if (!punishmentAllowed(t)) lore.add("<red>" + offense.display() + " cannot result in this");
            else {
                lore.add(selected ? "<green>Selected" : "<gray>Click to select");
                if (recommended != null && recommended.type() == t) lore.add("<aqua>Recommended for this offense");
                if (gui.settings().quick.requireEvidence().contains(t)) lore.add("<dark_gray>Needs evidence");
            }
            set(20 + i, choice(Views.punishIcon(t), t.display(), selected, can, lore), (p, click) -> {
                if (!can) {
                    gui.messages().sendError(p, offense == null ? "Choose the offense first." : "You cannot choose that punishment for this case.");
                    return;
                }
                punishChosenByHand = true;
                ptype = t;
                pduration = 0;
                Offense.Recommendation r = recommended;
                if (t.canBeTimed() && r != null && r.type() == t && durationAllowed(t, r.duration())) pduration = r.duration();
                refresh();
            });
        }
        if (ptype == null) return;

        // duration (timed types) and the reason the player sees
        if (ptype.canBeTimed()) {
            Pickers.durationRow(this, 36, durationChoices(ptype), pduration, ms -> durationAllowed(ptype, ms), ms -> {
                punishChosenByHand = true;
                pduration = ms;
                refresh();
            });
            set(33, Items.item(Material.COMPASS, Items.t("<yellow><b>Custom duration"), List.of(
                    Items.t("<gray>Selected: <white><v>", "v", pduration == 0 ? "none yet" : Views.duration(pduration)),
                    Items.t("<gray>Longest for this offense: <white><v>", "v", offense.maxDuration() == Durations.PERMANENT ? "no limit" : Durations.format(offense.maxDuration())),
                    Items.t("<yellow>Click to type a duration"))), (p, t) -> Pickers.customDuration(gui, p, offense.maxDuration() == Durations.PERMANENT, ms -> {
                if (!durationAllowed(ptype, ms)) gui.messages().sendError(p, "That duration is above the offense's limit or your own.");
                else {
                    punishChosenByHand = true;
                    pduration = ms;
                }
                open();
            }, this::open));
        }
        List<Component> why = new ArrayList<>();
        why.add(Items.t("<gray>Shown to the player and kept on record."));
        for (String line : NotesWrap.wrap(effectiveReason(), 40)) why.add(Items.t("<white><v>", "v", line));
        why.add(Items.t(preason.isBlank() ? "<dark_gray>(the offense's name; click to write your own)" : "<yellow>Click to change"));
        set(29, Items.item(Material.WRITABLE_BOOK, Items.t("<gold><b>Reason for the player"), why), (p, t) ->
                gui.prompts().ask(p, "Type the reason the player is shown (at least " + gui.settings().cases.minReason() + " characters).", gui.settings().cases.minReason(),
                        gui.settings().cases.maxReason(), text -> {
                            preason = text;
                            open();
                        }, this::open));
    }

    /** A selectable choice button: shimmer and a green name when selected, grey when it cannot be chosen. */
    private static org.bukkit.inventory.ItemStack choice(Material icon, String name, boolean selected, boolean enabled, List<String> lore) {
        List<Component> l = new ArrayList<>();
        for (String line : lore) l.add(Items.t(line));
        if (!enabled) return Items.item(Material.GRAY_DYE, Items.t("<dark_gray><st><v>", "v", name), l);
        org.bukkit.inventory.ItemStack it = Items.item(icon, Items.t((selected ? "<green><b>" : "<white>") + "<v>", "v", name), l);
        return selected ? Items.glow(it) : it;
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
