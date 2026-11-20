package dev.aura.punishments.gui;

import dev.aura.punishments.model.Records.Audit;
import dev.aura.punishments.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Read-only view of the tamper-evident audit log. IP addresses are never written to it. */
final class AuditMenu extends Menu {
    private static final int PER = Views.LIST_SLOTS.length;
    private final List<Audit> entries;
    private final int page;
    private final boolean more;

    private AuditMenu(Gui gui, Player viewer, Menu parent, List<Audit> entries, int page, boolean more) {
        super(gui, viewer, parent);
        this.entries = entries;
        this.page = page;
        this.more = more;
    }

    static void open(Gui gui, Player p, Menu parent, int page) {
        gui.call(p, gui.admin2().audit(gui.actor(p), PER + 1, page * PER), l ->
                new AuditMenu(gui, p, parent, l.size() > PER ? l.subList(0, PER) : l, page, l.size() > PER).open());
    }

    @Override
    protected Component title() {
        return Items.t("<gold>Audit log <gray>(page <v>)", "v", String.valueOf(page + 1));
    }

    @Override
    protected void render() {
        navigation();
        int i = 0;
        for (Audit a : entries) {
            List<Component> lore = new ArrayList<>();
            lore.add(Items.t("<gray>When: <white><v>", "v", Gui.time(a.ts())));
            lore.add(Items.t("<gray>By: <white><v>", "v", a.actorName()));
            if (a.caseId() != null) lore.add(Items.t("<gray>Case: <white>AP-<v>", "v", String.format("%04d", a.caseId())));
            for (String l : NotesWrap.wrap(a.detail() == null ? "" : a.detail(), 40)) lore.add(Items.t("<white><v>", "v", l));
            set(Views.LIST_SLOTS[i++], Items.item(a.elevated() ? Material.RED_DYE : Material.PAPER, Items.t((a.elevated() ? "<red>" : "<white>") + "#<id> <v>", "id", String.valueOf(a.id()),
                    "v", Text.truncate(a.action(), 28)), lore));
        }
        if (page > 0) set(48, Items.item(Material.SPECTRAL_ARROW, "<yellow>Newer"), (p, t) -> open(gui, p, parent, page - 1));
        if (more) set(50, Items.item(Material.SPECTRAL_ARROW, "<yellow>Older"), (p, t) -> open(gui, p, parent, page + 1));
    }
}
