package dev.aura.punishments.gui;

import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Active punishments of one player, to revoke with a recorded reason. */
final class PunishmentListMenu extends Menu {
    private final String heading;
    private final List<Punishment> list;
    private final Runnable reload;

    private PunishmentListMenu(Gui gui, Player viewer, Menu parent, String heading, List<Punishment> list, Runnable reload) {
        super(gui, viewer, parent);
        this.heading = heading;
        this.list = list;
        this.reload = reload;
    }

    static void open(Gui gui, Player p, Menu parent, Target t) {
        gui.call(p, gui.punishments.activeFor(t.uuid()), l -> new PunishmentListMenu(gui, p, parent, "Active punishments: " + t.name(), l,
                () -> open(gui, p, parent, t)).open());
    }

    /** A fixed list (for example every punishment attached to one case). */
    static void openList(Gui gui, Player p, Menu parent, String heading, List<Punishment> list, Runnable reload) {
        new PunishmentListMenu(gui, p, parent, heading, list, reload).open();
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple><v>", "v", heading);
    }

    @Override
    protected void render() {
        navigation();
        long now = gui.core().clock.now();
        boolean ip = viewer.hasPermission(Perms.SENSITIVE_IP);
        int i = 0;
        for (Punishment pun : list) {
            if (i >= Views.LIST_SLOTS.length) break;
            var item = Views.punishmentItem(pun, ip, now);
            boolean live = pun.status() == dev.aura.punishments.model.Simple.PunishStatus.ACTIVE || pun.status() == dev.aura.punishments.model.Simple.PunishStatus.COMPLETED;
            boolean may = live && (viewer.hasPermission(Perms.REVOKE_ANY) || viewer.hasPermission(Perms.REVOKE));
            if (may) {
                var meta = item.getItemMeta();
                var lore = new java.util.ArrayList<>(meta.lore());
                lore.add(Component.empty());
                lore.add(Items.t("<yellow>Click to revoke"));
                meta.lore(lore);
                item.setItemMeta(meta);
            }
            set(Views.LIST_SLOTS[i++], item, may ? (p, t) -> Pickers.revoke(gui, p, this, pun, reload) : null);
        }
        if (list.isEmpty()) set(22, Items.item(Material.LIME_STAINED_GLASS_PANE, "<green>Nothing to show"));
    }
}
