package dev.aura.livetest;

import net.minecraft.network.Connection;
import net.minecraft.network.HashedStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.inventory.ClickType;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A real Minecraft protocol client: it opens a TCP connection to the server, performs the real handshake, login and
 * configuration phases and then plays, sending genuine serverbound chat / command / container-click packets that the server
 * decodes from the wire exactly as it would from the official client. (It uses the server's own protocol classes for the
 * codecs, so it cannot render anything; every clientbound packet it receives is recorded.)
 */
public final class McClient {
    public final String name;
    public final UUID uuid;
    public final List<String> packets = Collections.synchronizedList(new ArrayList<>());
    public volatile int containerId;
    public volatile int stateId;
    public volatile boolean playing;
    /** How many times a proxy moved this client to another backend (it re-enters the configuration phase each time). */
    public volatile int switches;
    public volatile String disconnected;
    /** The text dialog the server last opened (null when none is open). */
    private volatile Dialog dialog;
    private Connection conn;

    private record Dialog(net.minecraft.resources.Identifier yesId, net.minecraft.nbt.CompoundTag yesAdd, net.minecraft.resources.Identifier noId, net.minecraft.nbt.CompoundTag noAdd, String text) {}

    private final CountDownLatch inGame = new CountDownLatch(1);

    private McClient(String name, UUID uuid) {
        this.name = name;
        this.uuid = uuid;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.BiConsumer<String, Object[]> onCall) {
        InvocationHandler h = (p, m, a) -> {
            try {
                return dispatch(type, onCall, p, m, a);
            } catch (Throwable t) {
                Bukkit.getLogger().severe("[McClient] " + m.getName() + " failed: " + t);
                t.printStackTrace();
                throw t;
            }
        };
        return (T) Proxy.newProxyInstance(McClient.class.getClassLoader(), new Class<?>[]{type}, h);
    }

    private static Object dispatch(Class<?> type, java.util.function.BiConsumer<String, Object[]> onCall, Object p, java.lang.reflect.Method m, Object[] a) throws Throwable {
        {
            String n = m.getName();
            if (n.startsWith("handle") && false) Bukkit.getLogger().info("[McClient] call " + n + " " + (a != null && a.length > 0 ? a[0].getClass().getSimpleName() : ""));
            if (n.equals("isAcceptingMessages") || n.equals("shouldHandleMessage")) return true;
            if (n.equals("onDisconnect") && a != null && a.length > 0) {
                onCall.accept("onDisconnect", a);
                return null;
            }
            if (a != null && a.length == 1 && a[0] instanceof Packet<?> && n.startsWith("handle")) {
                onCall.accept(n, a);
                return null;
            }
            if (m.isDefault()) return InvocationHandler.invokeDefault(p, m, a);
            Class<?> r = m.getReturnType();
            if (r == boolean.class) return false;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            return null;
        }
    }

    /** Connect and log in; returns once the client is in the play phase (or throws). */
    public static McClient connect(String name, UUID uuid, int port) throws Exception {
        return connect(name, uuid, "127.0.0.1", port);
    }

    /** @param host what the handshake says the client connected to (a proxy routes on it: forced hosts) */
    public static McClient connect(String name, UUID uuid, String host, int port) throws Exception {
        McClient c = new McClient(name, uuid);
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        c.conn = Connection.connectToServer(new InetSocketAddress("127.0.0.1", port), EventLoopGroupHolder.remote(false), null);
        ClientLoginPacketListener login = proxy(ClientLoginPacketListener.class, (n, a) -> {
            Object pk = a[0];
            if (pk instanceof ClientboundLoginCompressionPacket p) {
                c.conn.setupCompression(p.getCompressionThreshold(), false);
            } else if (pk instanceof ClientboundLoginFinishedPacket) {
                ClientConfigurationPacketListener cfg = proxy(ClientConfigurationPacketListener.class, (n2, a2) -> c.config(server, a2[0]));
                c.conn.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND, cfg);
                c.conn.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
                c.conn.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
            } else if (n.equals("onDisconnect")) {
                c.disconnected = String.valueOf(pk);
            } else {
                if (pk.getClass().getSimpleName().equals("ClientboundLoginDisconnectPacket")) { c.disconnected = String.valueOf(pk); c.inGame.countDown(); } // the refusal text a banned player sees
                c.packets.add(FakePlayer.describe(pk));
            }
        });
        for (int i = 0; i < 100 && c.conn.channel == null; i++) Thread.sleep(50);
        c.conn.channel.pipeline().addLast("mcclient-debug", new io.netty.channel.ChannelInboundHandlerAdapter() {
            @Override public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) { Bukkit.getLogger().severe("[McClient] pipeline exception: " + cause); cause.printStackTrace(); ctx.fireExceptionCaught(cause); }
        });
        c.conn.initiateServerboundPlayConnection(host, port, login);
        c.conn.send(new ServerboundHelloPacket(name, uuid));
        if (!c.inGame.await(40, TimeUnit.SECONDS) || !c.playing) throw new IllegalStateException("login did not reach the play phase: " + c.disconnected + " " + c.packets);
        return c;
    }

    private void config(MinecraftServer server, Object pk) {
        String kind = pk.getClass().getSimpleName();
        if (kind.equals("ClientboundDisconnectPacket") || kind.contains("DisconnectionDetails")) {
            // refused while a proxy was still setting the connection up (a banned player sees this text): record it and stop waiting
            disconnected = FakePlayer.describe(pk) + " " + pk;
            inGame.countDown();
            return;
        }
        if (pk instanceof ClientboundSelectKnownPacks) {
            conn.send(new ServerboundSelectKnownPacks(List.of()));
        } else if (pk instanceof ClientboundKeepAlivePacket k) {
            conn.send(new ServerboundKeepAlivePacket(k.getId()));
        } else if (pk instanceof ClientboundPingPacket p) {
            conn.send(new ServerboundPongPacket(p.getId()));
        } else if (pk instanceof ClientboundFinishConfigurationPacket) {
            var access = server.registryAccess();
            ClientGamePacketListener game = proxy(ClientGamePacketListener.class, (n, a) -> play(a[0], n));
            conn.setupInboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access)), game);
            conn.send(ServerboundFinishConfigurationPacket.INSTANCE);
            conn.setupOutboundProtocol(GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access), new net.minecraft.network.protocol.game.GameProtocols.Context() {
                @Override public boolean hasInfiniteMaterials() { return false; }
            }));
            playing = true;
            inGame.countDown();
        }
    }

    private void play(Object pk, String method) {
        if (method.equals("onDisconnect")) {
            disconnected = String.valueOf(pk);
            return;
        }
        if (pk instanceof ClientboundStartConfigurationPacket) {
            // a proxy is moving this player to another backend: leave the play phase and go through configuration again, exactly as the vanilla client does
            switches++;
            playing = false;
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
            ClientConfigurationPacketListener cfg = proxy(ClientConfigurationPacketListener.class, (n2, a2) -> config(server, a2[0]));
            conn.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND, cfg);
            conn.send(ServerboundConfigurationAcknowledgedPacket.INSTANCE);
            conn.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
            return;
        }
        if (pk instanceof ClientboundKeepAlivePacket k) {
            conn.send(new ServerboundKeepAlivePacket(k.getId()));
            return;
        }
        if (pk instanceof ClientboundPingPacket p) {
            conn.send(new ServerboundPongPacket(p.getId()));
            return;
        }
        if (pk instanceof ClientboundPlayerPositionPacket p) {
            conn.send(new ServerboundAcceptTeleportationPacket(p.id()));
        }
        if (pk instanceof net.minecraft.network.protocol.common.ClientboundShowDialogPacket sd) {
            var d = (net.minecraft.server.dialog.ConfirmationDialog) sd.dialog().value();
            var yes = (net.minecraft.server.dialog.action.CustomAll) d.yesButton().action().orElseThrow();
            var no = (net.minecraft.server.dialog.action.CustomAll) d.noButton().action().orElseThrow();
            dialog = new Dialog(yes.id(), yes.additions().orElse(new net.minecraft.nbt.CompoundTag()), no.id(), no.additions().orElse(new net.minecraft.nbt.CompoundTag()), d.common().toString());
            packets.add("ClientboundShowDialogPacket|" + d.common());
            return;
        }
        if (pk instanceof net.minecraft.network.protocol.common.ClientboundClearDialogPacket) {
            dialog = null;
            packets.add("ClientboundClearDialogPacket");
            return;
        }
        if (pk instanceof ClientboundOpenScreenPacket o) containerId = o.getContainerId();
        if (pk instanceof ClientboundContainerSetContentPacket c) {
            containerId = c.containerId();
            stateId = c.stateId();
        }
        if (pk instanceof ClientboundContainerSetSlotPacket s) stateId = s.getStateId();
        if (pk.getClass().getSimpleName().equals("ClientboundDisconnectPacket")) disconnected = FakePlayer.describe(pk) + " " + pk;
        packets.add(FakePlayer.describe(pk));
    }

    // ------------------------------------------------------------------ what a player can do
    /** A real chat line, exactly as a client sends it. */
    public void chat(String text) {
        conn.send(new ServerboundChatPacket(text, Instant.now(), 0L, null, new LastSeenMessages.Update(0, new BitSet(), (byte) 0)));
    }

    /** Is a text dialog open on this client's screen? */
    public boolean dialogOpen() {
        return dialog != null;
    }

    /** Wait (up to 6 s) for a dialog to be on screen. */
    public boolean awaitDialog() throws InterruptedException {
        for (int i = 0; i < 120 && dialog == null; i++) Thread.sleep(50);
        return dialog != null;
    }

    /** Type into the dialog's text box and press Submit: a genuine custom-click-action packet carrying the field value. */
    public void submitDialog(String text) throws InterruptedException {
        if (!awaitDialog()) throw new IllegalStateException("no dialog is open on the client");
        Dialog d = dialog;
        dialog = null;
        var tag = d.yesAdd().copy();
        tag.put("answer", net.minecraft.nbt.StringTag.valueOf(text));
        conn.send(new net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket(d.yesId(), Optional.of(tag)));
    }

    /** Press the dialog's Cancel button. */
    public void cancelDialog() throws InterruptedException {
        if (!awaitDialog()) throw new IllegalStateException("no dialog is open on the client");
        Dialog d = dialog;
        dialog = null;
        conn.send(new net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket(d.noId(), Optional.of(d.noAdd().copy())));
    }

    /** A real slash command (without the slash). */
    public void command(String text) {
        conn.send(new ServerboundChatCommandPacket(text));
    }

    /** A real left click on a slot of the currently open container. */
    public void click(int slot) {
        conn.send(new ServerboundContainerClickPacket(containerId, stateId, (short) slot, (byte) 0, ClickType.PICKUP,
                it.unimi.dsi.fastutil.ints.Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
    }

    /** A real right click (button 1) on a slot. */
    public void rightClick(int slot) {
        conn.send(new ServerboundContainerClickPacket(containerId, stateId, (short) slot, (byte) 1, ClickType.PICKUP,
                it.unimi.dsi.fastutil.ints.Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
    }

    public void clear() {
        packets.clear();
    }

    public List<String> snapshot() {
        synchronized (packets) {
            return new ArrayList<>(packets);
        }
    }

    public boolean saw(String text) {
        return snapshot().stream().anyMatch(p -> p.contains(text));
    }

    public void close() {
        if (conn != null) conn.disconnect(net.minecraft.network.chat.Component.literal("test client closing"));
    }
}
