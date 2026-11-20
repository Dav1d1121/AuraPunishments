package dev.aura.punishments.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Confirmation for high-impact actions. The confirm callback only runs on a deliberate click of the green
 * button; services still re-check permissions when it runs. Optionally the staff member must type the
 * player's name (used for bans) so a misclick cannot ban the wrong person.
 */
final class ConfirmMenu extends Menu {
    private final String heading;
    private final List<String> lines;
    private final Runnable onConfirm;
    private final String typeToConfirm; // null = no typed confirmation

    ConfirmMenu(Gui gui, Player viewer, Menu parent, String heading, List<String> lines, String typeToConfirm, Runnable onConfirm) {
        super(gui, viewer, parent);
        this.heading = heading;
        this.lines = lines;
        this.typeToConfirm = typeToConfirm;
        this.onConfirm = onConfirm;
    }

    @Override
    protected Component title() {
        return Items.t("<dark_red>Confirm: <v>", "v", heading);
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected void render() {
        fill(0, 26);
        List<Component> lore = new ArrayList<>();
        for (String l : lines) lore.add(Items.t("<gray><v>", "v", l));
        set(13, Items.item(Material.PAPER, Items.t("<white><b><v>", "v", heading), lore));
        String hint = typeToConfirm == null ? "<gray>Click to confirm." : "<gray>You will be asked to type <white>" + typeToConfirm + "<gray> to confirm.";
        set(11, Items.item(Material.LIME_CONCRETE, Items.t("<green><b>CONFIRM"), List.of(Items.t(hint))), (p, t) -> {
            if (typeToConfirm == null) {
                onConfirm.run();
            } else {
                gui.prompts().ask(p, "Type the player's name exactly (" + typeToConfirm + ") to confirm, or cancel.", 1, 40, text -> {
                    if (text.equalsIgnoreCase(typeToConfirm)) onConfirm.run();
                    else {
                        gui.messages().sendError(p, "The name did not match. Nothing was changed.");
                        open();
                    }
                }, this::open);
            }
        });
        set(15, Items.item(Material.RED_CONCRETE, "<red><b>CANCEL", "<gray>Go back without changing anything"), (p, t) -> back());
    }
}
