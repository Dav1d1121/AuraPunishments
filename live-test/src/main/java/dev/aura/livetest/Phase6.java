package dev.aura.livetest;

import com.graywolf336.jail.JailsAPI;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Simple.Verdict;
import dev.aura.punishments.service.*;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.util.Durations;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 1.1.0 live phase: behaviour that needs a CONNECTED player. Players are real server-side players created with
 * {@link FakePlayer} (full join sequence over an in-memory channel that records the packets sent to them).
 */
final class Phase6 {
    private final LiveTest t;

    Phase6(LiveTest t) {
        this.t = t;
    }

    private static boolean waitFor(BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return c.getAsBoolean();
    }

    private static long count(FakePlayer f, String prefix) {
        return f.snapshot().stream().filter(p -> p.startsWith(prefix)).count();
    }

    private static boolean saw(FakePlayer f, String text) {
        return f.snapshot().stream().anyMatch(p -> p.contains(text));
    }

    private interface Section {
        void run() throws Exception;
    }

    private void section(String name, Section s) {
        try {
            s.run();
        } catch (Throwable e) {
            t.check("[" + name + "] completed without an exception", false, e.toString());
            for (StackTraceElement el : e.getStackTrace()) {
                if (el.getClassName().startsWith("dev.aura")) {
                    t.results.add("     at " + el);
                    break;
                }
            }
        }
    }

    void run() throws Exception {
        t.createJail();
        section("join", this::join);
        section("warnings", this::warnings);
        section("custody", this::custody);
    }

    // ------------------------------------------------------------------ join
    private FakePlayer steve;

    private void join() throws Exception {
        UUID su = LiveTest.id("LiveSteve");
        t.ap.gui().resolver.recordJoin(su, "LiveSteve", "10.9.9.1", 0).get();
        steve = t.onMain(() -> FakePlayer.join("LiveSteve", su));
        t.check("a fake player is genuinely online in the server", t.onMain(() -> Bukkit.getPlayer(su) != null), "");
        t.check("the plugin's join handling ran (profile recorded)", waitFor(() -> {
            try {
                return t.ap.core().db.<Boolean>txSync(c -> Repo.Profiles.get(c, su) != null);
            } catch (Exception e) {
                return false;
            }
        }, 3000), "");
        t.check("the server sent packets to the player's channel", count(steve, "Clientbound") > 5, steve.snapshot().size() + " packets");
    }

    // ------------------------------------------------------------------ warnings (title / sound / chat, ordering, offline)
    private void warnings() throws Exception {
        UUID su = LiveTest.id("LiveSteve");
        Actor warner = LiveTest.limited("LiveWarner", 5, Perms.PUNISH_OTHERS, Perms.punish(PunishType.WARN));
        steve.clear();
        var w = LiveTest.r(t.ap.punishments().issueQuick(warner, new PunishmentService.Request(new Target(su, "LiveSteve", false), PunishType.WARN, 0,
                "Please stop spamming the chat.", List.of())));
        t.check("warning issued with only aurapunishments.punish.warn (+ target access)", w.isOk(), w.isOk() ? "" : w.message());
        t.check("title packet received", waitFor(() -> count(steve, "ClientboundSetTitleTextPacket") >= 1, 5000), steve.snapshot().toString());
        List<String> pk = steve.snapshot();
        t.check("title text is the configured headline", pk.stream().anyMatch(p -> p.startsWith("ClientboundSetTitleTextPacket|") && p.contains("OFFICIAL WARNING")), "");
        t.check("subtitle sent", count(steve, "ClientboundSetSubtitleTextPacket") == 1, "");
        t.check("title timing is fade 10 / stay 100 / fade 20 ticks", pk.contains("ClientboundSetTitlesAnimationPacket|10/100/20"), pk.toString());
        t.check("sound packet: pling, volume 0.8, pitch 1.0", pk.stream().anyMatch(p -> p.contains("block.note_block.pling") && p.contains("vol=0.8") && p.contains("pitch=1.0")), pk.toString());
        t.check("chat warning with the reason", saw(steve, "OFFICIAL WARNING") && saw(steve, "Please stop spamming the chat."), "");
        t.check("warning marked delivered only after it was shown", waitFor(() -> {
            try {
                return t.ap.core().db.<Boolean>txSync(c -> Repo.Punishments.get(c, w.value().id()).delivered());
            } catch (Exception e) {
                return false;
            }
        }, 3000), "");
        t.ap.punishments().deliverPending(su).get();
        Thread.sleep(500);
        t.check("no duplicate title after another delivery pass", count(steve, "ClientboundSetTitleTextPacket") == 1, "titles=" + count(steve, "ClientboundSetTitleTextPacket"));

        // offline player with three queued warnings: delivered in order, each once, only after login
        UUID ou = LiveTest.id("LiveOffWarn");
        t.ap.gui().resolver.recordJoin(ou, "LiveOffWarn", "10.9.9.2", 0).get();
        Target ot = new Target(ou, "LiveOffWarn", false);
        for (int i = 1; i <= 3; i++) {
            Thread.sleep(20);
            LiveTest.r(t.ap.punishments().issueQuick(warner, new PunishmentService.Request(ot, PunishType.WARN, 0, "Queued warning number " + i + " for spam.", List.of()))).orThrow();
        }
        t.check("three warnings queued for the offline player, none marked delivered", t.ap.core().db.<Integer>txSync(c -> Repo.Notifications.pendingFor(c, ou).size()) == 3, "");
        FakePlayer off = t.onMain(() -> FakePlayer.join("LiveOffWarn", ou));
        t.check("all three delivered after login", waitFor(() -> count(off, "ClientboundSetTitleTextPacket") == 3, 30000), "titles=" + count(off, "ClientboundSetTitleTextPacket"));
        List<String> chat = off.snapshot().stream().filter(p -> p.contains("Queued warning number")).toList();
        t.check("in order 1,2,3 and each exactly once", chat.size() == 3 && chat.get(0).contains("number 1") && chat.get(1).contains("number 2") && chat.get(2).contains("number 3"), chat.toString());
        t.check("none pending afterwards", waitFor(() -> {
            try {
                return t.ap.core().db.<Integer>txSync(c -> Repo.Notifications.pendingFor(c, ou).size()) == 0;
            } catch (Exception e) {
                return false;
            }
        }, 3000), "");
        off.leave();
    }

    // ------------------------------------------------------------------ custody of an ONLINE player (real JailReloaded)
    private void custody() throws Exception {
        UUID su = LiveTest.id("LiveSteve");
        Actor owner = LiveTest.god("LiveOwner");
        Case c = LiveTest.r(t.ap.cases().create(owner, new CaseService.CreateRequest(new Target(su, "LiveSteve", false), "harassment", dev.aura.punishments.model.Simple.Severity.MODERATE,
                "Live test: repeated offensive behaviour towards other players.", null,
                List.of(new EvidenceValidator.Input(dev.aura.punishments.model.Simple.EvidenceType.EXTERNAL_LINK, "https://example.com/proof-steve", ""))))).orThrow();
        steve.clear();
        var cu = LiveTest.r(t.ap.custody().initiate(owner, c.id()));
        t.check("custody of the ONLINE player confirmed by JailReloaded", cu.isOk(), cu.isOk() ? "" : cu.message());
        t.check("JailReloaded holds the prisoner", t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(su)), "");
        t.check("the online player was moved into the jail cell area", t.onMain(() -> {
            Location l = Bukkit.getPlayer(su).getLocation();
            return Math.abs(l.getX()) <= 11 && Math.abs(l.getZ()) <= 11;
        }), "");
        t.check("defendant told: case number, offense, hearing instructions, not a guilt finding",
                waitFor(() -> saw(steve, "held pending a hearing"), 5000) && saw(steve, c.ref()) && saw(steve, "NOT a finding of guilt") && saw(steve, "Discord"), steve.snapshot().toString());
        t.check("detention message shown exactly once", steve.snapshot().stream().filter(p -> p.contains("held pending a hearing")).count() == 1, "");

        // disconnect and reconnect while in custody
        steve.leave();
        t.check("player offline", waitFor(() -> !steve.online(), 5000), "");
        t.check("still a prisoner while offline", t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(su)), "");
        steve = t.onMain(() -> FakePlayer.join("LiveSteve", su));
        t.check("reconnected, still in custody and AP still tracks it", t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(su)) && LiveTest.r(t.ap.custody().liveForPlayer(su)).isOk()
                && LiveTest.r(t.ap.custody().liveForPlayer(su)).value() != null, "");

        // trial -> not guilty releases the ONLINE prisoner
        Case tr = t.toTrial(owner, c);
        steve.clear();
        var v = LiveTest.r(t.ap.judicial().enterVerdict(owner, tr.id(), tr.version(), Verdict.NOT_GUILTY, "The evidence does not establish the allegation."));
        t.check("not-guilty verdict accepted", v.isOk(), v.isOk() ? "" : v.message());
        t.check("JailReloaded released the online player", waitFor(() -> {
            try {
                return !t.onMain(() -> JailsAPI.getJailManager().isPlayerJailed(su));
            } catch (Exception e) {
                return false;
            }
        }, 8000), "");
        t.check("no punishment recorded for an acquittal", t.ap.core().db.<Integer>txSync(cn -> Repo.Punishments.forCase(cn, c.id()).size()) == 0, "");
        t.check("defendant told about the acquittal", waitFor(() -> saw(steve, "in your favor") || saw(steve, "released from custody"), 8000), steve.snapshot().toString());
    }
}
