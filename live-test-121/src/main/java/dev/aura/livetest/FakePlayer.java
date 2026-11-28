package dev.aura.livetest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A genuinely connected player inside the running Paper server (full join sequence, PlayerJoinEvent, inventories, chat
 * events, packet delivery) whose network channel is an in-memory EmbeddedChannel that records every clientbound packet.
 * It exists only so the live tests can exercise online-player behaviour; it is not part of the plugin.
 */
public final class FakePlayer {
    public final String name;
    public final UUID uuid;
    public ServerPlayer handle;
    public EmbeddedChannel channel;
    /** One entry per clientbound packet: "SimpleName|detail". */
    public final List<String> packets = Collections.synchronizedList(new ArrayList<>());

    private FakePlayer(String name, UUID uuid) {
        this.name = name;
        this.uuid = uuid;
    }

    public static FakePlayer join(String name, UUID uuid) {
        FakePlayer f = new FakePlayer(name, uuid);
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) Bukkit.getWorlds().get(0)).getHandle();
        GameProfile profile = new GameProfile(uuid, name);
        f.handle = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        ChannelOutboundHandlerAdapter recorder = new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                f.packets.add(describe(msg));
                super.write(ctx, msg, promise);
            }
        };
        f.channel = new EmbeddedChannel(recorder, connection); // handlers given here are activated, so Connection learns its channel
        server.getPlayerList().placeNewPlayer(connection, f.handle, CommonListenerCookie.createInitial(profile, false));
        return f;
    }

    /** Why the login was refused (null = joined). */
    public String denied;

    /**
     * Join through the real login events (AsyncPlayerPreLogin on the CALLING thread, which must NOT be the main thread, then
     * PlayerLogin on the main thread), so LuckPerms loads/injects the user and AuraPunishments' ban checks run.
     */
    public static FakePlayer loginAndJoin(String name, UUID uuid, String ip) throws Exception {
        java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
        var pre = new org.bukkit.event.player.AsyncPlayerPreLoginEvent(name, addr, uuid);
        Bukkit.getPluginManager().callEvent(pre);
        if (pre.getLoginResult() != org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            FakePlayer f = new FakePlayer(name, uuid);
            f.denied = "pre-login: " + PlainTextComponentSerializer.plainText().serialize(pre.kickMessage());
            return f;
        }
        return Bukkit.getScheduler().callSyncMethod(Bukkit.getPluginManager().getPlugin("APLiveTest"), () -> {
            FakePlayer f = new FakePlayer(name, uuid);
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
            ServerLevel level = ((CraftWorld) Bukkit.getWorlds().get(0)).getHandle();
            GameProfile profile = new GameProfile(uuid, name);
            f.handle = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
            var login = new org.bukkit.event.player.PlayerLoginEvent(f.handle.getBukkitEntity(), "localhost", addr);
            Bukkit.getPluginManager().callEvent(login);
            if (login.getResult() != org.bukkit.event.player.PlayerLoginEvent.Result.ALLOWED) {
                f.denied = "login: " + PlainTextComponentSerializer.plainText().serialize(login.kickMessage());
                return f;
            }
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            ChannelOutboundHandlerAdapter recorder = new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    f.packets.add(describe(msg));
                    super.write(ctx, msg, promise);
                }
            };
            f.channel = new EmbeddedChannel(recorder, connection);
            server.getPlayerList().placeNewPlayer(connection, f.handle, CommonListenerCookie.createInitial(profile, false));
            return f;
        }).get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    public Player bukkit() {
        return handle.getBukkitEntity();
    }

    public boolean online() {
        return Bukkit.getPlayer(uuid) != null;
    }

    public void leave() {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) handle.connection.onDisconnect(new net.minecraft.network.DisconnectionDetails(net.minecraft.network.chat.Component.literal("test leave")));
    }

    public void clear() {
        packets.clear();
    }

    public List<String> snapshot() {
        synchronized (packets) {
            return new ArrayList<>(packets);
        }
    }

    static String describe(Object msg) {
        String n = msg.getClass().getSimpleName();
        try {
            if (msg instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket p) {
                return n + "|" + PlainTextComponentSerializer.plainText().serialize(io.papermc.paper.adventure.PaperAdventure.asAdventure(p.content()));
            }
            if (msg instanceof net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket p) {
                return n + "|" + PlainTextComponentSerializer.plainText().serialize(io.papermc.paper.adventure.PaperAdventure.asAdventure(p.message()));
            }
            if (msg instanceof net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket p) {
                return n + "|" + PlainTextComponentSerializer.plainText().serialize(io.papermc.paper.adventure.PaperAdventure.asAdventure(p.text()));
            }
            if (msg instanceof net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket p) {
                return n + "|" + PlainTextComponentSerializer.plainText().serialize(io.papermc.paper.adventure.PaperAdventure.asAdventure(p.text()));
            }
            if (msg instanceof net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket p) {
                return n + "|" + p.getFadeIn() + "/" + p.getStay() + "/" + p.getFadeOut();
            }
            if (msg instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket p) {
                return n + "|" + p.getSound().getRegisteredName() + " vol=" + p.getVolume() + " pitch=" + p.getPitch();
            }
            if (msg instanceof net.minecraft.network.protocol.game.ClientboundSoundEntityPacket p) {
                return n + "|" + p.getSound().getRegisteredName() + " vol=" + p.getVolume() + " pitch=" + p.getPitch();
            }
        } catch (Throwable t) {
            return n + "|(undecodable: " + t.getClass().getSimpleName() + ")";
        }
        if (n.startsWith("ClientboundPlayerChat") || n.startsWith("ClientboundDisguisedChat")) return n + "|" + msg;
        return n;
    }
}
