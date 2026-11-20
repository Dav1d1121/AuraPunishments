package dev.aura.punishments.gui;

import dev.aura.punishments.service.Perms;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Administration: system status, audit log, integrity check, reload. */
final class AdminMenu extends Menu {
    private AdminMenu(Gui gui, Player viewer, Menu parent) {
        super(gui, viewer, parent);
    }

    static void open(Gui gui, Player p, Menu parent) {
        new AdminMenu(gui, p, parent).open();
    }

    @Override
    protected Component title() {
        return Items.t("<gold>Administration");
    }

    @Override
    protected void render() {
        navigation();
        List<Component> diag = new ArrayList<>();
        for (String line : gui.admin.diagnostics()) diag.add(Items.t("<gray><v>", "v", line));
        action(10, gui.can(viewer, Perms.ADMIN), Material.REDSTONE_LAMP, "<aqua><b>System status", List.of("<gray>Click to print details to chat"), (p, t) -> {
            p.closeInventory();
            for (String l : gui.admin.diagnostics()) p.sendMessage(Items.t("<gray><v>", "v", l));
        });
        set(4, Items.item(Material.COMMAND_BLOCK, Items.t("<gold><b>System"), diag));
        action(12, gui.can(viewer, Perms.AUDIT_VIEW), Material.BOOKSHELF, "<yellow><b>Audit log", List.of("<gray>Recent administrative and elevated actions"), (p, t) -> AuditMenu.open(gui, p, this, 0));
        action(13, gui.can(viewer, Perms.ADMIN), Material.SHIELD, "<green><b>Verify audit integrity", List.of("<gray>Re-computes the tamper-evident hash chain"), (p, t) ->
                gui.call(p, gui.admin2().verifyAudit(gui.actor(p)), bad -> {
                    if (bad < 0) gui.success(p, "Audit log is intact.");
                    else gui.messages().sendError(p, "AUDIT LOG DAMAGED: first inconsistent entry is #" + bad + ". Preserve the database and investigate.");
                }));
        action(14, gui.can(viewer, Perms.ADMIN_RELOAD), Material.SUNFLOWER, "<yellow><b>Reload configuration", List.of("<gray>Validates first; keeps old settings on error"), (p, t) -> {
            gui.admin.reload(p);
            refresh();
        });
        action(16, gui.can(viewer, Perms.ADMIN), Material.CHEST, "<aqua><b>Jail custody list", List.of("<gray>Everyone currently held"), (p, t) -> CustodyListMenu.open(gui, p, this));
    }
}
