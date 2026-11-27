package dev.aura.punishments.gui;

import dev.aura.punishments.model.Records.Punishment;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Emergency actions awaiting senior review. */
final class EmergencyReviewList extends Menu {
    private final List<Punishment> list;

    private EmergencyReviewList(Gui gui, Player viewer, Menu parent, List<Punishment> list) {
        super(gui, viewer, parent);
        this.list = list;
    }

    static void open(Gui gui, Player p, Menu parent) {
        gui.call(p, gui.admin2().pendingEmergencyReviews(gui.actor(p)), l -> new EmergencyReviewList(gui, p, parent, l).open());
    }

    @Override
    protected Component title() {
        return Items.t("<dark_red>Emergency reviews");
    }

    @Override
    protected void render() {
        navigation();
        long now = gui.core().clock.now();
        int i = 0;
        for (Punishment p : list) {
            if (i >= Views.LIST_SLOTS.length) break;
            var item = Views.punishmentItem(p, false, now);
            var meta = item.getItemMeta();
            var lore = new java.util.ArrayList<>(meta.lore());
            lore.add(Component.empty());
            lore.add(Items.t(p.reviewDeadline() != null && now > p.reviewDeadline() ? "<red>REVIEW OVERDUE" : "<gray>Review due: <white><v>", "v", p.reviewDeadline() == null ? "-" : Gui.time(p.reviewDeadline())));
            lore.add(Items.t(p.caseId() == null ? "<yellow>Click to open and review" : "<yellow>Click to open the case and review"));
            meta.lore(lore);
            item.setItemMeta(meta);
            set(Views.LIST_SLOTS[i++], item, (pl, t) -> {
                if (p.caseId() == null) PunishmentMenu.open(gui, pl, this, p.id());
                else CaseMenu.open(gui, pl, this, p.caseId());
            });
        }
        if (list.isEmpty()) set(22, Items.item(Material.LIME_STAINED_GLASS_PANE, "<green>No emergency actions await review"));
    }
}
