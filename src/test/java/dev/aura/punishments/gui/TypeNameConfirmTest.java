package dev.aura.punishments.gui;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.permissions.Permission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 1.2.1: the "enter the player's name" step before heavy punishments is configurable and off by default. */
class TypeNameConfirmTest {
    McFx fx;
    PlayerMock owner;
    final UUID victim = UUID.nameUUIDFromBytes("victim".getBytes());

    @BeforeEach
    void setUp() {
        fx = new McFx();
        owner = fx.server.addPlayer("TheOwner");
        for (Permission perm : fx.server.getPluginManager().getPermissions()) {
            if (perm.getName().startsWith("aurapunishments.")) owner.addAttachment(fx.plugin, perm.getName(), true);
        }
        owner.addAttachment(fx.plugin, Perms.LEVEL_PREFIX + "100", true);
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private void click(int slot) throws Exception {
        Thread.sleep(fx.gui.settings().gui.clickCooldownMs() + 30L);
        fx.click(owner, slot, ClickType.LEFT);
    }

    /** Walk a permanent mute through the screen up to and including the CONFIRM click. */
    private void permanentMuteThroughTheGui() throws Exception {
        QuickPunishMenu.open(fx.gui, owner, null, new Target(victim, "Victim", false), PunishType.MUTE);
        assertTrue(fx.awaitMenu(owner, QuickPunishMenu.class));
        click(10);
        assertTrue(fx.await(() -> fx.dialogs.containsKey(owner.getUniqueId())));
        fx.answer(owner, "permanent");
        assertTrue(fx.awaitMenu(owner, QuickPunishMenu.class));
        click(12);
        assertTrue(fx.await(() -> fx.dialogs.containsKey(owner.getUniqueId())));
        fx.answer(owner, "Repeated spamming of the global chat");
        assertTrue(fx.awaitMenu(owner, QuickPunishMenu.class));
        click(40);
        assertTrue(fx.awaitMenu(owner, ConfirmMenu.class));
        click(11); // CONFIRM
    }

    private boolean muted() throws Exception {
        return fx.plugin.core().db.txSync(c -> !Repo.Punishments.forTarget(c, victim, 10, 0).isEmpty());
    }

    @Test
    void byDefaultOnlyConfirmCancelIsShownAndThePunishmentIsAppliedAtOnce() throws Exception {
        assertFalse(fx.gui.settings().quick.typeNameToConfirm());
        permanentMuteThroughTheGui();
        assertTrue(fx.await(() -> { try { return muted(); } catch (Exception e) { return false; } }), "applied after CONFIRM, no name entry");
        assertNull(fx.dialogs.get(owner.getUniqueId()), "no name dialog was opened");
    }

    @Test
    void whenEnabledTheNameIsAskedForAndAWrongNameChangesNothing() throws Exception {
        File cfg = new File(fx.plugin.getDataFolder(), "config.yml");
        String text = Files.readString(cfg.toPath());
        assertTrue(text.contains("type-name-to-confirm: false"));
        Files.writeString(cfg.toPath(), text.replaceFirst("(quick:[\\s\\S]*?)type-name-to-confirm: false", "$1type-name-to-confirm: true"));
        fx.plugin.reloadFor(fx.server.getConsoleSender());
        assertTrue(fx.gui.settings().quick.typeNameToConfirm());
        permanentMuteThroughTheGui();
        assertTrue(fx.await(() -> fx.dialogs.containsKey(owner.getUniqueId())), "the name dialog opens");
        fx.answer(owner, "SomeoneElse");
        Thread.sleep(300);
        fx.server.getScheduler().performTicks(5);
        assertFalse(muted(), "a wrong name applies nothing");
        // back at the confirmation screen: confirm again and give the right name
        assertTrue(fx.awaitMenu(owner, ConfirmMenu.class));
        click(11);
        assertTrue(fx.await(() -> fx.dialogs.containsKey(owner.getUniqueId())));
        fx.answer(owner, "Victim");
        assertTrue(fx.await(() -> { try { return muted(); } catch (Exception e) { return false; } }));
    }

    @Test
    void theShippedDirectDefaultIsConfirmCancelOnlyAndTheKeyStillWorks() throws Exception {
        assertTrue(fx.gui.settings().direct.typeNameToConfirm().isEmpty());
        var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(getClass().getResourceAsStream("/config.yml")));
        y.set("direct.type-name-to-confirm", java.util.List.of("BAN", "IPBAN"));
        var s = dev.aura.punishments.config.Settings.parse(y);
        assertEquals(java.util.Set.of(PunishType.BAN, PunishType.IPBAN), s.direct.typeNameToConfirm());
    }
}
