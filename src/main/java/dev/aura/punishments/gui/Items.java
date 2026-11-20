package dev.aura.punishments.gui;

import dev.aura.punishments.config.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Item and text construction for menus. User-supplied values are always inserted as plain text. */
public final class Items {
    private Items() {}

    /** Parse MiniMessage with unparsed placeholders: kv = name, value, name, value... */
    public static Component t(String mini, String... kv) {
        TagResolver.Builder b = TagResolver.builder();
        for (int i = 0; i + 1 < kv.length; i += 2) b.resolver(Placeholder.unparsed(kv[i], kv[i + 1] == null ? "" : kv[i + 1]));
        return Messages.mini().deserialize(mini, b.build()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static ItemStack item(Material m, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null && !lore.isEmpty()) meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            it.setItemMeta(meta);
        }
        return it;
    }

    public static ItemStack item(Material m, String name, String... lore) {
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(t(s));
        return item(m, t(name), l);
    }

    public static ItemStack head(UUID uuid, String name, Component display, List<Component> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof SkullMeta sm) {
            try {
                OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                sm.setOwningPlayer(op);
            } catch (RuntimeException ignored) {
                // texture is cosmetic only
            }
            sm.displayName(display);
            if (lore != null) sm.lore(lore);
            sm.addItemFlags(ItemFlag.values());
            it.setItemMeta(sm);
        }
        return it;
    }

    public static Material material(String name, Material fallback) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m == null || !m.isItem() ? fallback : m;
    }

    public static ItemStack filler() {
        return item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
    }
}
