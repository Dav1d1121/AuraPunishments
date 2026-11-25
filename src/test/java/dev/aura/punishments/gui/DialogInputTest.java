package dev.aura.punishments.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Text entry through a native dialog window (since 1.2.0): nothing is typed into chat any more. */
class DialogInputTest {
    McFx fx;
    PlayerMock mod;

    @BeforeEach
    void setUp() {
        fx = new McFx();
        mod = fx.staff("Mod", McFx.basicStaff());
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void askOpensADialogAndASubmittedAnswerIsDeliveredExactlyOnce() {
        List<String> got = new CopyOnWriteArrayList<>();
        fx.gui.prompts().ask(mod, "Why?", 3, 80, got::add, () -> { });
        var req = fx.dialogs.get(mod.getUniqueId());
        assertNotNull(req, "a dialog is shown");
        assertEquals("Why?", req.question());
        assertEquals(80, req.max());
        fx.answer(mod, "the reason for this");
        assertTrue(fx.await(() -> got.size() == 1));
        assertEquals("the reason for this", got.get(0));
        assertFalse(fx.gui.prompts().has(mod));
        // a stale second submit of the same dialog does nothing
        req.onSubmit().accept("again");
        fx.server.getScheduler().performTicks(5);
        assertEquals(1, got.size());
    }

    @Test
    void tooShortOrTooLongAnswersReopenTheDialogWithTheErrorAndTheTypedText() {
        List<String> got = new CopyOnWriteArrayList<>();
        fx.gui.prompts().ask(mod, "Why?", 5, 10, got::add, () -> { });
        fx.answer(mod, "ab");
        fx.server.getScheduler().performTicks(3);
        var again = fx.dialogs.get(mod.getUniqueId());
        assertTrue(fx.gui.prompts().has(mod), "the session stays");
        assertNotNull(again.error(), "the problem is shown inside the dialog");
        assertEquals("ab", again.initial(), "what was typed is kept");
        fx.answer(mod, "this is far too long");
        fx.server.getScheduler().performTicks(3);
        assertNotNull(fx.dialogs.get(mod.getUniqueId()).error());
        assertTrue(got.isEmpty());
        fx.answer(mod, "just fine");
        assertTrue(fx.await(() -> got.size() == 1));
    }

    @Test
    void cancelEndsTheSessionTellsThePlayerAndRunsTheReturnAction() {
        boolean[] cancelled = {false};
        fx.gui.prompts().ask(mod, "Why?", 3, 80, t -> fail("must not be called"), () -> cancelled[0] = true);
        fx.messages(mod);
        fx.pressCancel(mod);
        assertTrue(fx.await(() -> cancelled[0]));
        assertFalse(fx.gui.prompts().has(mod));
        assertTrue(fx.messages(mod).stream().anyMatch(m -> m.toLowerCase().contains("cancel")));
    }

    @Test
    void anUnansweredDialogTimesOutClosesAndReturns() {
        boolean[] cancelled = {false};
        fx.gui.prompts().ask(mod, "Why?", 3, 80, t -> fail("must not be called"), () -> cancelled[0] = true);
        fx.messages(mod);
        fx.server.getScheduler().performTicks(20L * 100);
        assertFalse(fx.gui.prompts().has(mod));
        assertTrue(cancelled[0]);
        assertNull(fx.dialogs.get(mod.getUniqueId()), "the dialog was closed");
        assertTrue(fx.messages(mod).stream().anyMatch(m -> m.toLowerCase().contains("timed out")));
    }

    @Test
    void aNewQuestionReplacesTheOldOneAndTheOldDialogCanNoLongerAnswer() {
        List<String> a = new CopyOnWriteArrayList<>();
        List<String> b = new CopyOnWriteArrayList<>();
        fx.gui.prompts().ask(mod, "first", 3, 80, a::add, () -> { });
        var old = fx.dialogs.get(mod.getUniqueId());
        fx.gui.prompts().ask(mod, "second", 3, 80, b::add, () -> { });
        old.onSubmit().accept("answer to the stale dialog");
        fx.server.getScheduler().performTicks(5);
        assertTrue(a.isEmpty() && b.isEmpty(), "the stale dialog is ignored");
        fx.answer(mod, "answer to the second");
        assertTrue(fx.await(() -> b.size() == 1));
        assertTrue(a.isEmpty());
    }

    @Test
    void staffSessionsAreIndependent() {
        PlayerMock other = fx.staff("ModB", McFx.basicStaff());
        String[] a = {null};
        String[] b = {null};
        fx.gui.prompts().ask(mod, "A", 3, 80, t -> a[0] = t, () -> { });
        fx.gui.prompts().ask(other, "B", 3, 80, t -> b[0] = t, () -> { });
        fx.answer(other, "answer from B");
        fx.answer(mod, "answer from A");
        assertTrue(fx.await(() -> a[0] != null && b[0] != null));
        assertEquals("answer from A", a[0]);
        assertEquals("answer from B", b[0]);
    }

    @Test
    void disconnectingAndShutdownCleanUp() {
        fx.gui.prompts().ask(mod, "Why?", 3, 80, t -> { }, () -> { });
        mod.disconnect();
        PlayerMock again = fx.server.addPlayer("Mod");
        assertFalse(fx.gui.prompts().has(again));
        PlayerMock other = fx.staff("ModC", McFx.basicStaff());
        fx.gui.prompts().ask(other, "Why?", 3, 80, t -> { }, () -> { });
        fx.messages(other);
        fx.gui.prompts().cancelAll();
        assertFalse(fx.gui.prompts().has(other));
        assertNull(fx.dialogs.get(other.getUniqueId()));
        assertTrue(fx.messages(other).stream().anyMatch(m -> m.toLowerCase().contains("cancel")), "told that nothing was changed");
    }

    @Test
    void thereIsNoChatListenerInTheInputSystemAnyMore() {
        AtomicInteger chatHandlers = new AtomicInteger();
        for (Method m : PromptManager.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(org.bukkit.event.EventHandler.class)) {
                String param = m.getParameterTypes()[0].getSimpleName();
                if (param.contains("Chat")) chatHandlers.incrementAndGet();
            }
        }
        assertEquals(0, chatHandlers.get(), "the prompt manager must not listen to chat events");
    }
}
