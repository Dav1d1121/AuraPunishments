package dev.aura.punishments.gui;

import dev.aura.punishments.model.Records.Appeal;
import dev.aura.punishments.service.CaseService.Page;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Open appeals against punishments, oldest first. Click one to open its punishment. */
final class AppealListMenu extends Menu {
    private static final int PER = Views.LIST_SLOTS.length;

    private Page<Appeal> data;
    private int page;

    private AppealListMenu(Gui gui, Player viewer, Menu parent, Page<Appeal> data, int page) {
        super(gui, viewer, parent);
        this.data = data;
        this.page = page;
    }

    static void open(Gui gui, Player p, Menu parent) {
        load(gui, p, parent, 0);
    }

    private static void load(Gui gui, Player p, Menu parent, int page) {
        gui.call(p, gui.appeals.open(gui.actor(p), page, PER), d -> new AppealListMenu(gui, p, parent, d, page).open());
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple>Open appeals <gray>(<n>)", "n", String.valueOf(data.total()));
    }

    @Override
    protected void render() {
        navigation();
        int i = 0;
        for (Appeal a : data.items()) {
            if (i >= PER) break;
            List<Component> lore = new ArrayList<>();
            lore.add(Items.t("<gray>Punishment: <white>#<v>", "v", String.valueOf(a.punishmentId())));
            lore.add(Items.t("<gray>Filed by <white><v> <gray>at <white><t>", "v", a.filedByName(), "t", Gui.time(a.filedAt())));
            lore.add(Items.t("<gray>Status: <white><v>", "v", a.status().name().toLowerCase().replace('_', ' ')));
            lore.add(Items.t("<gray>Ticket: <white><v>", "v", a.ticket() == null ? "none yet" : a.ticket()));
            if (a.assignedName() != null) lore.add(Items.t("<gray>Reviewer: <white><v>", "v", a.assignedName()));
            lore.add(Items.t("<dark_gray><v>", "v", dev.aura.punishments.util.Text.truncate(a.reason(), 50)));
            lore.add(Component.empty());
            lore.add(Items.t("<yellow>Click to open the punishment"));
            set(Views.LIST_SLOTS[i++], Items.item(a.status().name().equals("UNDER_REVIEW") ? Material.LECTERN : Material.WRITABLE_BOOK,
                    Items.t("<white><b>Appeal #<id>", "id", String.valueOf(a.id())), lore), (p, t) -> PunishmentMenu.open(gui, p, this, a.punishmentId()));
        }
        if (data.items().isEmpty()) set(22, Items.item(Material.LIME_STAINED_GLASS_PANE, "<green>No open appeals"));
        if (page > 0) set(48, Items.item(Material.SPECTRAL_ARROW, "<yellow>Previous page"), (p, t) -> load(gui, p, parent, page - 1));
        if ((long) (page + 1) * PER < data.total()) set(50, Items.item(Material.SPECTRAL_ARROW, "<yellow>Next page"), (p, t) -> load(gui, p, parent, page + 1));
    }
}
