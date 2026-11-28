package dev.aura.punishments.gui;

import dev.aura.punishments.config.Offense;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.service.CaseService;
import dev.aura.punishments.service.EvidenceValidator;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Case creation wizard: offense, severity, description, context and evidence on one screen. The draft lives
 * in this menu only; nothing is saved until Submit, and the service validates everything again.
 */
final class CaseCreateMenu extends Menu {
    private final Target target;
    private Offense offense;
    private Severity severity;
    private String description = "";
    private String context = "";
    private final List<EvidenceValidator.Input> evidence = new ArrayList<>();

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
                (p, t) -> new OffenseSelectMenu(gui, p, this, off -> true, "", off -> {
                    offense = off;
                    if (severity == null) severity = off.severity();
                    open();
                }).open());

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
        ev.add(Items.t("<gray>Items: <white><n><gray> / required: <white><r>", "n", String.valueOf(evidence.size()), "r", String.valueOf(need)));
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

        // submit
        boolean ready = offense != null && severity != null && description.length() >= gui.settings().cases.minDescription() && evidence.size() >= need;
        List<Component> sub = new ArrayList<>();
        sub.add(Items.t(ready ? "<green>Ready to open the case" : "<red>Complete the required steps first"));
        sub.add(Items.t(gui.core().mode.casework() ? "<gray>You can punish from the case afterwards." : "<gray>You become the default prosecutor."));
        set(31, Items.item(ready ? Material.LIME_CONCRETE : Material.RED_CONCRETE, Items.t(ready ? "<green><b>SUBMIT CASE" : "<red><b>Incomplete"), sub), (p, t) -> {
            if (!ready) {
                gui.messages().sendError(p, "Choose the offense, write a description and add the required evidence first.");
                return;
            }
            gui.call(p, gui.cases.create(gui.actor(p), new CaseService.CreateRequest(target, offense.key(), severity, description,
                    context.isBlank() ? null : context, List.copyOf(evidence))), c -> {
                gui.success(p, "Case " + c.ref() + " opened against " + target.name() + ".");
                CaseMenu.open(gui, p, parent, c.id());
            }, this::open);
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
