package dev.aura.punishments.gui;

import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** A player's full disciplinary history: cases (findings, acquittals) and punishments (incl. reversals). */
final class HistoryMenu extends Menu {
    private static final int PER = Views.LIST_SLOTS.length;

    private final Target target;
    private boolean showPunishments;
    private int page;
    private List<Case> cases = List.of();
    private long totalCases;
    private List<Punishment> puns = List.of();
    private boolean morePuns;

    private HistoryMenu(Gui gui, Player viewer, Menu parent, Target target) {
        super(gui, viewer, parent);
        this.target = target;
    }

    static void open(Gui gui, Player p, Menu parent, Target t) {
        HistoryMenu m = new HistoryMenu(gui, p, parent, t);
        m.load(false, 0);
    }

    private void load(boolean punishments, int pg) {
        if (punishments) {
            gui.call(viewer, gui.punishments.history(gui.actor(viewer), target.uuid(), PER + 1, pg * PER), list -> {
                this.showPunishments = true;
                this.page = pg;
                this.morePuns = list.size() > PER;
                this.puns = list.size() > PER ? list.subList(0, PER) : list;
                open();
            });
        } else {
            gui.call(viewer, gui.cases.historyFor(gui.actor(viewer), target.uuid(), pg, PER), pgRes -> {
                this.showPunishments = false;
                this.page = pg;
                this.cases = pgRes.items();
                this.totalCases = pgRes.total();
                open();
            });
        }
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple>History: <white><v>", "v", target.name());
    }

    @Override
    protected void render() {
        navigation();
        set(2, Items.item(showPunishments ? Material.BOOK : Material.ENCHANTED_BOOK, showPunishments ? "<gray>Cases" : "<green><b>Cases"),
                (p, t) -> load(false, 0));
        set(6, Items.item(showPunishments ? Material.ENCHANTED_BOOK : Material.BOOK, showPunishments ? "<green><b>Punishments" : "<gray>Punishments"),
                (p, t) -> load(true, 0));
        set(4, Items.head(target.uuid(), target.name(), Items.t("<white><b><v>", "v", target.name()), List.of(
                Items.t(showPunishments ? "<gray>Showing punishments" : "<gray>Showing cases <dark_gray>(<n> total)", "n", String.valueOf(totalCases)))));
        long now = gui.core().clock.now();
        boolean ip = viewer.hasPermission(Perms.SENSITIVE_IP);
        if (!showPunishments) {
            int i = 0;
            for (Case c : cases) {
                set(Views.LIST_SLOTS[i++], Views.caseItem(c), (p, t) -> CaseMenu.open(gui, p, this, c.id()));
            }
            if (cases.isEmpty()) set(22, Items.item(Material.LIME_STAINED_GLASS_PANE, "<green>No cases on record"));
            if (page > 0) set(48, Items.item(Material.SPECTRAL_ARROW, "<yellow>Previous page"), (p, t) -> load(false, page - 1));
            if ((long) (page + 1) * PER < totalCases) set(50, Items.item(Material.SPECTRAL_ARROW, "<yellow>Next page"), (p, t) -> load(false, page + 1));
        } else {
            int i = 0;
            for (Punishment pun : puns) {
                boolean canRevoke = (pun.status() == PunishStatus.ACTIVE || pun.status() == PunishStatus.COMPLETED)
                        && (viewer.hasPermission(Perms.REVOKE_ANY) || viewer.hasPermission(Perms.REVOKE));
                var item = Views.punishmentItem(pun, ip, now);
                if (canRevoke) {
                    var meta = item.getItemMeta();
                    var lore = new java.util.ArrayList<>(meta.lore());
                    lore.add(Component.empty());
                    lore.add(Items.t("<yellow>Click to revoke"));
                    meta.lore(lore);
                    item.setItemMeta(meta);
                }
                set(Views.LIST_SLOTS[i++], item, canRevoke ? (p, t) -> Pickers.revoke(gui, p, this, pun, () -> load(true, page)) : null);
            }
            if (puns.isEmpty()) set(22, Items.item(Material.LIME_STAINED_GLASS_PANE, "<green>No punishments on record"));
            if (page > 0) set(48, Items.item(Material.SPECTRAL_ARROW, "<yellow>Previous page"), (p, t) -> load(true, page - 1));
            if (morePuns) set(50, Items.item(Material.SPECTRAL_ARROW, "<yellow>Next page"), (p, t) -> load(true, page + 1));
        }
    }
}
