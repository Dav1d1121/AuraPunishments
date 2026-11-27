package dev.aura.punishments.gui;

import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.WorkQueueService.Item;
import dev.aura.punishments.service.WorkQueueService.Kind;
import dev.aura.punishments.service.WorkQueueService.Queue;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** One prioritised list of everything that is waiting for a human (custody, reviews, emergencies, trials, appeals). */
final class WorkQueueMenu extends Menu {
    private final Queue q;
    private Kind filter; // null = everything
    private int page;

    private WorkQueueMenu(Gui gui, Player viewer, Menu parent, Queue q) {
        super(gui, viewer, parent);
        this.q = q;
    }

    static void open(Gui gui, Player p, Menu parent) {
        gui.call(p, gui.workQueue.queue(gui.actor(p)), q -> new WorkQueueMenu(gui, p, parent, q).open());
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple>Work queue" + (filter == null ? "" : " - " + filter.label));
    }

    private List<Item> visible() {
        return q.items().stream().filter(i -> filter == null || i.kind() == filter).toList();
    }

    @Override
    protected void render() {
        navigation();
        List<Item> list = visible();
        int per = Views.LIST_SLOTS.length;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        page = Math.min(page, pages - 1);
        long now = gui.core().clock.now();
        for (int i = 0; i < per && page * per + i < list.size(); i++) {
            Item it = list.get(page * per + i);
            List<Component> lore = new ArrayList<>();
            lore.add(Items.t("<gray>Player: <white><v>", "v", it.player()));
            lore.add(Items.t("<gray><v>", "v", it.label()));
            if (it.dueAt() != null) {
                lore.add(Items.t(it.overdue() ? "<red>Was due: <v>" : "<gray>Due / scheduled: <white><v>", "v", Gui.time(it.dueAt())));
            }
            lore.add(Items.t("<dark_gray>Priority <v>", "v", String.valueOf(it.priority())));
            lore.add(Items.t("<yellow>Click to open"));
            Material m = it.overdue() ? Material.RED_CONCRETE : switch (it.kind()) {
                case CUSTODY_IMMINENT -> Material.ORANGE_CONCRETE;
                case QUICK_REVIEW_PENDING, QUICK_REVIEW_OVERDUE, FURTHER_INVESTIGATION -> Material.BLUE_CONCRETE;
                case EMERGENCY_PENDING -> Material.ORANGE_CONCRETE;
                case APPEAL -> Material.CYAN_CONCRETE;
                default -> Material.PURPLE_CONCRETE;
            };
            set(Views.LIST_SLOTS[i], Items.item(m, Items.t("<white><b><v>", "v", it.player()), lore), (p, t) -> go(p, it));
        }
        if (list.isEmpty()) set(22, Items.item(Material.LIME_STAINED_GLASS_PANE, "<green>Nothing is waiting for you"));
        // summary / filter cycle
        List<Component> counts = new ArrayList<>();
        for (Kind k : Kind.values()) if (q.count(k) > 0) counts.add(Items.t("<gray><k>: <white><v>", "k", k.label, "v", String.valueOf(q.count(k))));
        counts.add(Items.t("<yellow>Click to filter by type"));
        if (q.truncated()) counts.add(Items.t("<red>Only the highest-priority items are shown"));
        set(4, Items.item(Material.HOPPER, Items.t("<white><b>Filter: <v>", "v", filter == null ? "everything" : filter.label), counts), (p, t) -> {
            Kind[] ks = Kind.values();
            int next = filter == null ? 0 : filter.ordinal() + 1;
            while (next < ks.length && q.count(ks[next]) == 0) next++;
            filter = next >= ks.length ? null : ks[next];
            page = 0;
            refresh();
        });
        if (page > 0) set(48, Items.item(Material.ARROW, "<yellow>Previous page"), (p, t) -> {
            page--;
            refresh();
        });
        set(47, Items.item(Material.PAPER, Items.t("<gray>Page <v>/<n>", "v", String.valueOf(page + 1), "n", String.valueOf(pages)), List.of()));
        if (page + 1 < pages) set(50, Items.item(Material.ARROW, "<yellow>Next page"), (p, t) -> {
            page++;
            refresh();
        });
        set(52, Items.item(Material.SUNFLOWER, "<aqua>Refresh"), (p, t) -> open(gui, p, parent));
    }

    private void go(Player p, Item it) {
        if (it.reviewId() != null && gui.can(p, Perms.REVIEW_VIEW)) ReviewMenu.open(gui, p, this, it.reviewId());
        else if (it.caseId() != null && gui.core().mode.judicialVisible()) CaseMenu.open(gui, p, this, it.caseId());
        else if (it.punishmentId() != null && gui.records != null && gui.can(p, Perms.HISTORY_VIEW)) PunishmentMenu.open(gui, p, this, it.punishmentId());
        else gui.messages().sendError(p, "There is nothing you may open for that item.");
    }
}
