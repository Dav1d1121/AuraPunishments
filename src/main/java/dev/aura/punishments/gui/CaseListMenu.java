package dev.aura.punishments.gui;

import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.service.CaseService.Page;
import dev.aura.punishments.util.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Paginated list of cases. Used for browsing, per-player case lists, and "pick a case" steps. */
final class CaseListMenu extends Menu {
    private final String title;
    private final IntFunction<CompletableFuture<Result<Page<Case>>>> loader;
    private final Consumer<Case> onPick;   // null = open the case
    private Page<Case> page;

    private CaseListMenu(Gui gui, Player viewer, Menu parent, String title, IntFunction<CompletableFuture<Result<Page<Case>>>> loader,
                         Consumer<Case> onPick, Page<Case> page) {
        super(gui, viewer, parent);
        this.title = title;
        this.loader = loader;
        this.onPick = onPick;
        this.page = page;
    }

    static void open(Gui gui, Player p, Menu parent, String title, IntFunction<CompletableFuture<Result<Page<Case>>>> loader, Consumer<Case> onPick) {
        gui.call(p, loader.apply(0), pg -> new CaseListMenu(gui, p, parent, title, loader, onPick, pg).open());
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple><v>", "v", title);
    }

    @Override
    protected void render() {
        navigation();
        set(4, Items.item(Material.BOOKSHELF, Items.t("<white><b><v>", "v", title),
                java.util.List.of(Items.t("<gray>Page <white><p><gray>/<white><n> <dark_gray>(<t> cases)", "p", String.valueOf(page.page() + 1),
                        "n", String.valueOf(page.pages()), "t", String.valueOf(page.total())))));
        int i = 0;
        for (Case c : page.items()) {
            if (i >= Views.LIST_SLOTS.length) break;
            set(Views.LIST_SLOTS[i++], Views.caseItem(c), (p, t) -> {
                if (onPick != null) onPick.accept(c);
                else CaseMenu.open(gui, p, this, c.id());
            });
        }
        if (page.items().isEmpty()) {
            set(22, Items.item(Material.STRUCTURE_VOID, "<gray>No cases to show"));
        }
        if (page.page() > 0) set(48, Items.item(Material.SPECTRAL_ARROW, "<yellow>Previous page"), (p, t) -> go(page.page() - 1));
        if (page.page() + 1 < page.pages()) set(50, Items.item(Material.SPECTRAL_ARROW, "<yellow>Next page"), (p, t) -> go(page.page() + 1));
        set(53, Items.item(Material.SUNFLOWER, "<yellow>Refresh"), (p, t) -> go(page.page()));
    }

    private void go(int n) {
        gui.call(viewer, loader.apply(n), pg -> {
            this.page = pg;
            open();
        });
    }
}
