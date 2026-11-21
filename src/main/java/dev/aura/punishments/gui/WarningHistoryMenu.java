package dev.aura.punishments.gui;

import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.service.PunishmentService.WarningEntry;
import dev.aura.punishments.service.PunishmentService.WarningHistory;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** A player's warnings, the repeat-warning indicator, and whether any later action was taken. Informational only. */
final class WarningHistoryMenu extends Menu {
    private final Target target;
    private final WarningHistory h;

    private WarningHistoryMenu(Gui gui, Player viewer, Menu parent, Target target, WarningHistory h) {
        super(gui, viewer, parent);
        this.target = target;
        this.h = h;
    }

    static void open(Gui gui, Player p, Menu parent, Target t) {
        gui.call(p, gui.punishments.warningHistory(gui.actor(p), t.uuid(), 28), h -> new WarningHistoryMenu(gui, p, parent, t, h).open());
    }

    @Override
    protected Component title() {
        return Items.t("<gold>Warnings: <v>", "v", target.name());
    }

    @Override
    protected void render() {
        navigation();
        List<Component> head = new ArrayList<>();
        head.add(Items.t("<gray>Warnings in the last <v>: <white><n>", "v", Views.duration(h.windowMs()), "n", String.valueOf(h.recentCount())));
        head.add(Items.t(h.repeatOffender() ? "<red><b>REPEAT WARNINGS <gray>(threshold " + h.threshold() + ")" : "<green>Below the repeat threshold (" + h.threshold() + ")"));
        head.add(Items.t("<dark_gray>This is an indicator only: warnings never cause a jail, mute or ban automatically."));
        set(4, Items.item(h.repeatOffender() ? Material.RED_BANNER : Material.YELLOW_BANNER, Items.t("<gold><b>Warning record"), head));
        for (int i = 0; i < h.entries().size() && i < Views.LIST_SLOTS.length; i++) {
            WarningEntry e = h.entries().get(i);
            Punishment w = e.warning();
            List<Component> lore = new ArrayList<>();
            lore.add(Items.t("<gray>Issued by: <white><v>", "v", w.issuerName()));
            lore.add(Items.t("<gray>Date: <white><v>", "v", Gui.time(w.issuedAt())));
            lore.add(Items.t("<gray>Reason: <white><v>", "v", dev.aura.punishments.util.Text.truncate(w.reason(), 70)));
            lore.add(Items.t(w.delivered() ? "<green>Shown to the player" : "<yellow>Not yet shown (player has not been online)"));
            lore.add(Items.t(e.followUps() > 0 ? "<red>Later action taken: <white><n> <gray>(punishment/case after this warning)" : "<green>No later action taken", "n", String.valueOf(e.followUps())));
            set(Views.LIST_SLOTS[i], Items.item(Material.PAPER, Items.t("<gold>Warning #<v>", "v", String.valueOf(w.id())), lore));
        }
        if (h.entries().isEmpty()) set(22, Items.item(Material.LIME_STAINED_GLASS_PANE, "<green>No warnings on record"));
    }
}
