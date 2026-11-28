package dev.aura.punishments.gui;

import dev.aura.punishments.config.Offense;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * "Which punishment?" - the same list wherever a case is punished from (the case screen, the Create Case screen): Warning, Kick, Mute, Ban,
 * IP Ban, Jail. A type is greyed out when the staff member may not issue it, when the case's offense does not allow it (a kick is not a
 * sentence and is always allowed) or when JailReloaded is not available. Choosing one opens the usual punishment screen.
 */
final class PunishChooser {
    static final PunishType[] TYPES = {PunishType.WARN, PunishType.KICK, PunishType.MUTE, PunishType.BAN, PunishType.IPBAN, PunishType.JAIL};

    private PunishChooser() {}

    /** Why this type cannot be chosen, or null when it can. */
    static String refusal(Gui gui, Player p, PunishType t, Optional<Offense> offense) {
        if (!p.hasPermission(Perms.punish(t))) return "You may not issue this";
        if (t == PunishType.JAIL && !gui.core().jail.available()) return "JailReloaded is not available";
        if (t != PunishType.KICK && offense.isPresent() && !offense.get().allows(t)) return offense.get().display() + " cannot result in this (offenses.yml)";
        return null;
    }

    static void open(Gui gui, Player p, Menu parent, String title, String accusedName, Optional<Offense> offense, Offense.Recommendation recommended, Consumer<PunishType> chosen) {
        List<ChoiceMenu.Choice> choices = new ArrayList<>();
        for (PunishType t : TYPES) {
            String refused = refusal(gui, p, t, offense);
            List<String> lore = new ArrayList<>();
            lore.add(refused == null ? "<gray>Click to continue" : "<red>" + refused.replace("<", "").replace(">", ""));
            if (refused == null && recommended != null && recommended.type() == t) lore.add("<aqua>Recommended: " + recommended.describe());
            choices.add(new ChoiceMenu.Choice(Views.punishIcon(t), "<white><b>" + t.display(), lore, refused == null, () -> chosen.accept(t)));
        }
        new ChoiceMenu(gui, p, parent, title, Items.item(Material.NETHERITE_SWORD, Items.t("<white><b><v>", "v", accusedName),
                List.of(Items.t("<gray>Takes effect immediately and is final."), Items.t("<gray>No owner approval, no court."))), choices).open();
    }
}
