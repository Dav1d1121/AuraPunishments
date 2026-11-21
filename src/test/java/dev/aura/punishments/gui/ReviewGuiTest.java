package dev.aura.punishments.gui;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Review;
import dev.aura.punishments.model.Simple.ReviewStatus;
import dev.aura.punishments.service.Actors;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.service.PunishmentService;
import dev.aura.punishments.util.Durations;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.permissions.Permission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The 1.1.0 screens, driven with real clicks and chat prompts. */
class ReviewGuiTest {
    McFx fx;
    PlayerMock owner;
    PlayerMock mod;
    Target steve;

    @BeforeEach
    void setUp() {
        fx = new McFx();
        owner = fx.server.addPlayer("TheOwner");
        for (Permission perm : fx.server.getPluginManager().getPermissions()) {
            if (perm.getName().startsWith("aurapunishments.")) owner.addAttachment(fx.plugin, perm.getName(), true);
        }
        owner.addAttachment(fx.plugin, Perms.LEVEL_PREFIX + "100", true);
        mod = fx.staff("JrMod", McFx.basicStaff());
        steve = new Target(java.util.UUID.nameUUIDFromBytes("steve".getBytes()), "Steve", false);
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private void click(PlayerMock p, int slot) throws Exception {
        Thread.sleep(fx.gui.settings().gui.clickCooldownMs() + 30L);
        fx.click(p, slot, ClickType.LEFT);
    }

    private Review issueMute() throws Exception {
        var res = fx.join(fx.plugin.punishments().issueQuick(Actors.of(mod), new PunishmentService.Request(steve, PunishType.MUTE, Durations.HOUR,
                "Spamming the global chat channel.", List.of())));
        assertTrue(res.isOk(), res.message());
        return fx.plugin.core().db.txSync(c -> Repo.Reviews.byPunishment(c, res.value().id()));
    }

    private Review reload(Review r) throws Exception {
        return fx.plugin.core().db.txSync(c -> Repo.Reviews.get(c, r.id()));
    }

    @Test
    void ownerWalksTheQueueOpensAReviewAndUpholdsIt() throws Exception {
        Review r = issueMute();
        owner.performCommand("ap review");
        assertTrue(fx.awaitMenu(owner, ReviewQueueMenu.class), "the queue opens");
        assertTrue(fx.title(owner).contains("Owner review"));
        click(owner, 10);
        assertTrue(fx.awaitMenu(owner, ReviewMenu.class), "clicking an entry opens the review");
        click(owner, 28); // Uphold
        assertTrue(fx.await(() -> fx.gui.prompts().has(owner)));
        owner.chat("The evidence supports the mute.");
        assertTrue(fx.await(() -> { try { return reload(r).status() == ReviewStatus.UPHELD; } catch (Exception e) { return false; } }));
        assertEquals("The evidence supports the mute.", reload(r).decisionNote());
        assertTrue(fx.plugin.punishments().cache().mute(steve.uuid(), System.currentTimeMillis()).isPresent(), "upheld = still in force");
    }

    @Test
    void reversingThroughTheGuiNeedsConfirmationAndLiftsThePunishment() throws Exception {
        Review r = issueMute();
        owner.performCommand("ap review " + r.id());
        assertTrue(fx.awaitMenu(owner, ReviewMenu.class));
        click(owner, 30); // Reverse
        assertTrue(fx.await(() -> fx.gui.prompts().has(owner)));
        owner.chat("Applied to the wrong player by mistake.");
        assertTrue(fx.awaitMenu(owner, ConfirmMenu.class), "reversal asks for explicit confirmation");
        assertEquals(ReviewStatus.PENDING_REVIEW, reload(r).status(), "nothing changed before confirming");
        click(owner, 11); // CONFIRM
        assertTrue(fx.await(() -> { try { return reload(r).status() == ReviewStatus.REVERSED; } catch (Exception e) { return false; } }));
        assertTrue(fx.plugin.punishments().cache().mute(steve.uuid(), System.currentTimeMillis()).isEmpty());
    }

    @Test
    void aReviewerWhoLosesThePermissionCannotDecideAndNothingChanges() throws Exception {
        Review r = issueMute();
        PlayerMock reviewer = fx.staff("Reviewer", Perms.COMMAND, Perms.REVIEW_VIEW, Perms.REVIEW_REVIEW);
        reviewer.performCommand("ap review " + r.id());
        assertTrue(fx.awaitMenu(reviewer, ReviewMenu.class));
        // permission revoked while the menu is open (as a LuckPerms change would do)
        reviewer.addAttachment(fx.plugin, Perms.REVIEW_REVIEW, false);
        assertFalse(reviewer.hasPermission(Perms.REVIEW_REVIEW), "the revocation took effect");
        click(reviewer, 28);
        assertTrue(fx.await(() -> fx.gui.prompts().has(reviewer)), "the stale button still asks for the note");
        reviewer.chat("Trying to uphold without permission.");
        assertTrue(fx.await(() -> fx.messages(reviewer).stream().anyMatch(m -> m.contains("do not have permission"))), "the service refuses and says why");
        assertEquals(ReviewStatus.PENDING_REVIEW, reload(r).status(), "nothing changed");
    }

    @Test
    void doubleClickingADecisionOnlyDecidesOnce() throws Exception {
        Review r = issueMute();
        owner.performCommand("ap review " + r.id());
        assertTrue(fx.awaitMenu(owner, ReviewMenu.class));
        click(owner, 28);
        assertTrue(fx.await(() -> fx.gui.prompts().has(owner)));
        owner.chat("The evidence supports the mute.");
        assertTrue(fx.await(() -> { try { return reload(r).status() == ReviewStatus.UPHELD; } catch (Exception e) { return false; } }));
        long events = fx.plugin.core().db.<Integer>txSync(c -> (int) Repo.Reviews.events(c, r.id()).stream().filter(e -> e.type().equals("DECISION")).count());
        assertEquals(1, events);
        // the menu was refreshed after the decision: the Uphold button is gone, a stale second click does nothing
        fx.await(() -> fx.menuOf(owner) instanceof ReviewMenu);
        click(owner, 28);
        Thread.sleep(200);
        assertEquals(1, fx.plugin.core().db.<Integer>txSync(c -> (int) Repo.Reviews.events(c, r.id()).stream().filter(e -> e.type().equals("DECISION")).count()));
    }

    @Test
    void issuersSeeOnlyTheirOwnPunishmentsAndStaffWithoutReviewViewCannotOpenTheQueue() throws Exception {
        Review r = issueMute();
        PlayerMock other = fx.staff("OtherMod", McFx.basicStaff());
        other.performCommand("ap review");
        assertTrue(fx.awaitMenu(other, ReviewQueueMenu.class), "falls back to 'my punishments'");
        assertTrue(fx.title(other).contains("My punishments"));
        assertEquals(null, fx.top(other).getItem(10), "someone else's review is not listed");
        mod.performCommand("ap review");
        assertTrue(fx.awaitMenu(mod, ReviewQueueMenu.class));
        assertNotNull(fx.top(mod).getItem(10), "the issuer sees their own");
        fx.click(other, 10); // nothing there
        other.performCommand("ap review " + r.id());
        Thread.sleep(300);
        fx.server.getScheduler().performOneTick();
        assertFalse(fx.menuOf(other) instanceof ReviewMenu, "cannot open a review that is neither yours nor viewable");
    }

    @Test
    void workQueueAndWarningHistoryOpen() throws Exception {
        issueMute();
        owner.performCommand("ap queue");
        assertTrue(fx.awaitMenu(owner, WorkQueueMenu.class));
        assertNotNull(fx.top(owner).getItem(10), "the pending review is listed");
        fx.server.addPlayer("Steve"); // a known player (profile recorded on join)
        assertTrue(fx.await(() -> { try { return !fx.join(fx.plugin.gui().resolver.resolve("Steve")).isOk() == false; } catch (Throwable t) { return false; } }));
        owner.performCommand("ap warnings Steve");
        assertTrue(fx.awaitMenu(owner, WarningHistoryMenu.class));
        assertTrue(fx.title(owner).contains("Warnings"));
    }
}
