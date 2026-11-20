package dev.aura.punishments.gui;

import dev.aura.punishments.model.Simple.EvidenceType;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

final class EvidenceTypeMenu extends Menu {
    private final Consumer<EvidenceType> onPick;

    EvidenceTypeMenu(Gui gui, Player viewer, Menu parent, Consumer<EvidenceType> onPick) {
        super(gui, viewer, parent);
        this.onPick = onPick;
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple>Evidence type");
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected void render() {
        fill(0, 26);
        int slot = 9;
        for (EvidenceType t : EvidenceType.values()) {
            set(slot++, Items.item(Views.evidenceIcon(t), Items.t("<white><v>", "v", t.name().toLowerCase().replace('_', ' ')),
                    java.util.List.of(Items.t("<gray>Click to choose"))), (p, c) -> onPick.accept(t));
        }
        if (parent != null) set(22, Items.item(org.bukkit.Material.ARROW, "<yellow>Back"), (p, t) -> parent.open());
    }
}
