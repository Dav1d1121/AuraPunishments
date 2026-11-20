package dev.aura.punishments.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** A small menu of mutually exclusive choices (verdicts, appeal decisions, status changes...). */
final class ChoiceMenu extends Menu {
    record Choice(Material icon, String name, List<String> lore, boolean enabled, Runnable run) {}

    private final String heading;
    private final ItemStack header;
    private final List<Choice> choices;

    ChoiceMenu(Gui gui, Player viewer, Menu parent, String heading, ItemStack header, List<Choice> choices) {
        super(gui, viewer, parent);
        this.heading = heading;
        this.header = header;
        this.choices = choices;
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple><v>", "v", heading);
    }

    @Override
    protected int rows() {
        return 4;
    }

    @Override
    protected void render() {
        fill(0, 35);
        if (header != null) set(4, header);
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < choices.size() && i < slots.length; i++) {
            Choice c = choices.get(i);
            List<Component> lore = new ArrayList<>();
            for (String l : c.lore()) lore.add(Items.t(l));
            if (c.enabled()) set(slots[i], Items.item(c.icon(), Items.t(c.name()), lore), (p, t) -> c.run().run());
            else set(slots[i], Items.item(Material.GRAY_DYE, Items.t("<dark_gray><st>" + c.name().replaceAll("<[^>]*>", "")), lore),
                    (p, t) -> gui.messages().sendError(p, "That is not available right now."));
        }
        if (parent != null) set(31, Items.item(Material.ARROW, "<yellow>Back"), (p, t) -> parent.open());
    }
}
