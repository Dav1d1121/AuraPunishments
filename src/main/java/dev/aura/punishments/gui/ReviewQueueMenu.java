package dev.aura.punishments.gui;

import dev.aura.punishments.model.Records.Review;
import dev.aura.punishments.model.Simple.ReviewStatus;
import dev.aura.punishments.service.CaseService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** The owner-review queue (and, for issuers, "my punishments under review"). */
final class ReviewQueueMenu extends Menu {
    private enum Filter {
        OPEN("Open (needs a decision)", List.of(ReviewStatus.PENDING_REVIEW, ReviewStatus.UNDER_REVIEW, ReviewStatus.FURTHER_INVESTIGATION)),
        PENDING("Pending review", List.of(ReviewStatus.PENDING_REVIEW)),
        UNDER("Under review", List.of(ReviewStatus.UNDER_REVIEW)),
        FURTHER("Further investigation", List.of(ReviewStatus.FURTHER_INVESTIGATION)),
        DECIDED("Decided", List.of(ReviewStatus.UPHELD, ReviewStatus.MODIFIED, ReviewStatus.REVERSED)),
        LEGACY("Legacy (pre-1.1)", List.of(ReviewStatus.LEGACY));

        final String label;
        final List<ReviewStatus> statuses;

        Filter(String label, List<ReviewStatus> statuses) {
            this.label = label;
            this.statuses = statuses;
        }
    }

    private final boolean mine;
    private Filter filter = Filter.OPEN;
    private int page;
    private CaseService.Page<Review> data = new CaseService.Page<>(List.of(), 0, 0, 28);

    private ReviewQueueMenu(Gui gui, Player viewer, Menu parent, boolean mine) {
        super(gui, viewer, parent);
        this.mine = mine;
    }

    static void open(Gui gui, Player p, Menu parent, boolean mine) {
        ReviewQueueMenu m = new ReviewQueueMenu(gui, p, parent, mine);
        m.load(true);
    }

    private void load(boolean first) {
        var f = mine ? gui.reviews.mine(gui.actor(viewer), page, 28) : gui.reviews.queue(gui.actor(viewer), filter.statuses, page, 28);
        gui.call(viewer, f, d -> {
            data = d;
            if (first) open();
            else refresh();
        });
    }

    @Override
    protected Component title() {
        return Items.t(mine ? "<aqua>My punishments under review" : "<aqua>Owner review: <v>", "v", filter.label);
    }

    @Override
    protected void render() {
        navigation();
        long now = gui.core().clock.now();
        for (int i = 0; i < data.items().size() && i < Views.LIST_SLOTS.length; i++) {
            Review r = data.items().get(i);
            boolean overdue = r.status().open() && r.reviewDue() != null && now > r.reviewDue();
            List<Component> lore = new ArrayList<>();
            lore.add(Items.t("<gray>Player: <white><v>", "v", r.targetName()));
            lore.add(Items.t("<gray>Issued by: <white><v>", "v", r.issuerName()));
            lore.add(Items.t("<gray>Punishment: <white><v>", "v", r.ptype().display() + (r.durationMs() == null ? " (permanent)" : " (" + Views.duration(r.durationMs()) + ")")));
            lore.add(Items.t("<gray>Status: <white><v>", "v", r.status().display()));
            if (r.status().open() && r.reviewDue() != null) lore.add(Items.t(overdue ? "<red>Review OVERDUE since <v>" : "<gray>Review due: <white><v>", "v", Gui.time(r.reviewDue())));
            lore.add(Items.t("<gray>Reason: <white><v>", "v", dev.aura.punishments.util.Text.truncate(r.reason(), 60)));
            lore.add(Items.t("<yellow>Click to open"));
            Material m = overdue ? Material.RED_CONCRETE : switch (r.status()) {
                case PENDING_REVIEW -> Material.YELLOW_CONCRETE;
                case UNDER_REVIEW -> Material.BLUE_CONCRETE;
                case FURTHER_INVESTIGATION -> Material.ORANGE_CONCRETE;
                case UPHELD -> Material.LIME_CONCRETE;
                case MODIFIED -> Material.CYAN_CONCRETE;
                case REVERSED -> Material.GRAY_CONCRETE;
                case LEGACY -> Material.LIGHT_GRAY_CONCRETE;
            };
            set(Views.LIST_SLOTS[i], Items.item(m, Items.t("<white><b><v> <gray>- <v2>", "v", r.ref(), "v2", r.targetName()), lore), (p, t) -> ReviewMenu.open(gui, p, this, r.id()));
        }
        if (data.items().isEmpty()) set(22, Items.item(Material.LIME_STAINED_GLASS_PANE, "<green>Nothing here"));
        if (!mine) {
            set(4, Items.item(Material.HOPPER, Items.t("<white><b>Filter: <v>", "v", filter.label), List.of(Items.t("<gray>Total: <white><v>", "v", String.valueOf(data.total())), Items.t("<yellow>Click to change"))), (p, t) -> {
                filter = Filter.values()[(filter.ordinal() + 1) % Filter.values().length];
                page = 0;
                load(false);
            });
        }
        long pages = Math.max(1, (data.total() + 27) / 28);
        set(47, Items.item(Material.PAPER, Items.t("<gray>Page <v>/<n>", "v", String.valueOf(page + 1), "n", String.valueOf(pages)), List.of()));
        if (page > 0) set(48, Items.item(Material.ARROW, "<yellow>Previous page"), (p, t) -> {
            page--;
            load(false);
        });
        if (page + 1 < pages) set(50, Items.item(Material.ARROW, "<yellow>Next page"), (p, t) -> {
            page++;
            load(false);
        });
    }
}
