package dev.aura.punishments;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.*;

class SmokeMockTest {
    @Test
    void pluginLoadsUnderMockBukkit() {
        ServerMock server = MockBukkit.mock();
        try {
            AuraPunishmentsPlugin plugin = MockBukkit.load(AuraPunishmentsPlugin.class);
            assertTrue(plugin.isEnabled());
            assertNotNull(server.getPluginCommand("punish"));
        } finally {
            MockBukkit.unmock();
        }
    }
}
