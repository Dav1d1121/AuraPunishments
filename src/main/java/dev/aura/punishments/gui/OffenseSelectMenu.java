package dev.aura.punishments.gui;

import dev.aura.punishments.config.Offense;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Choose an offense category. */
final class OffenseSelectMenu extends Menu {
    private final Predicate<Offense> filter;
    private final Consumer<Offense> onPick;
    private final String hint;

    OffenseSelectMenu(Gui gui, Player viewer, Menu parent, Predicate<Offense> filter, String hint, Consumer<Offense> onPick) {
        super(gui, viewer, parent);
        this.filter = filter;
        this.hint = hint;
        this.onPick = onPick;
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple>Choose the offense");
    }

    @Override
    protected void render() {
        navigation();
        int i = 0;
        for (Offense o : gui.core().offenses.all()) {
            if (!filter.test(o) || i >= Views.LIST_SLOTS.length) continue;
            if (!o.permission().isBlank() && !viewer.hasPermission(o.permission())) continue;
            List<Component> lore = new ArrayList<>();
            lore.add(Items.t("<gray><v>", "v", o.description()));
            lore.add(Items.t("<gray>Default severity: <white><v>", "v", o.severity().name()));
            lore.add(Items.t(o.directEligible() ? "<red>Major offense: direct punishment possible" : "<green>Judicial process"));
            lore.add(Items.t("<gray>Possible outcomes: <white><v>", "v", o.allowed().stream().map(t -> t.display()).reduce((a, b) -> a + ", " + b).orElse("-")));
            if (!hint.isBlank()) lore.add(Items.t("<dark_gray><v>", "v", hint));
            lore.add(Items.t("<yellow>Click to choose"));
            set(Views.LIST_SLOTS[i++], Items.item(Items.material(o.icon(), Material.PAPER), Items.t("<white><b><v>", "v", o.display()), lore), (p, t) -> onPick.accept(o));
        }
    }
}
