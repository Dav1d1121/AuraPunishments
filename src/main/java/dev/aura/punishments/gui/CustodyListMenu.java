package dev.aura.punishments.gui;

import dev.aura.punishments.model.Records.Custody;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Everyone currently held, soonest review deadline first. */
final class CustodyListMenu extends Menu {
    private final List<Custody> list;

    private CustodyListMenu(Gui gui, Player viewer, Menu parent, List<Custody> list) {
        super(gui, viewer, parent);
        this.list = list;
    }

    static void open(Gui gui, Player p, Menu parent) {
        gui.call(p, gui.custody.live(gui.actor(p)), l -> {
            List<Custody> sorted = new ArrayList<>(l);
            sorted.sort(java.util.Comparator.comparing((Custody c) -> c.deadline() == null ? Long.MAX_VALUE : c.deadline()));
            new CustodyListMenu(gui, p, parent, sorted).open();
        });
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple>Custody");
    }

    @Override
    protected void render() {
        navigation();
        long now = gui.core().clock.now();
        int i = 0;
        for (Custody cu : list) {
            if (i >= Views.LIST_SLOTS.length) break;
            List<Component> lore = new ArrayList<>(Views.custodyLines(cu, now));
            lore.add(Component.empty());
            lore.add(Items.t("<yellow>Click to open the case"));
            set(Views.LIST_SLOTS[i++], Items.head(cu.uuid(), cu.name(), Items.t((cu.overdue(now) ? "<red>" : "<white>") + "<b><v>", "v", cu.name()), lore),
                    (p, t) -> CaseMenu.open(gui, p, this, cu.caseId()));
        }
        if (list.isEmpty()) set(22, Items.item(Material.LIME_STAINED_GLASS_PANE, "<green>Nobody is in custody"));
    }
}
