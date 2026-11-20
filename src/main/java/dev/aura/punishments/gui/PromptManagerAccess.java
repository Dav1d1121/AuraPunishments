package dev.aura.punishments.gui;

import org.bukkit.entity.Player;

/** Narrow view of the prompt manager for listeners outside the gui package. */
public interface PromptManagerAccess {
    boolean has(Player p);

    void cancel(Player p, boolean notify);
}
