package dev.aura.punishments.gui;

import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.EvidenceType;
import dev.aura.punishments.service.EvidenceValidator;
import dev.aura.punishments.util.Durations;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

/** Reusable input steps shared by the wizards: durations, evidence entry, revocation. */
final class Pickers {
    private Pickers() {}

    /**
     * Draw duration preset buttons from {@code startSlot}. Presets the actor may not use are shown locked.
     * Authority is still re-checked by the service when the action runs.
     */
    static void durationRow(Menu m, int startSlot, List<Long> presets, long current, LongPredicate allowed, LongConsumer pick) {
        int slot = startSlot;
        for (long d : presets) {
            boolean ok = allowed.test(d);
            boolean sel = d == current;
            String label = Views.duration(d);
            Material mat = !ok ? Material.GRAY_DYE : (sel ? Material.LIME_DYE : Material.CLOCK);
            m.set(slot++, Items.item(mat, Items.t((ok ? (sel ? "<green><b>" : "<white>") : "<dark_gray><st>") + "<v>", "v", label),
                    List.of(Items.t(ok ? (sel ? "<green>Selected" : "<gray>Click to select") : "<red>Above your limit"))),
                    ok ? (p, t) -> pick.accept(d) : (p, t) -> m.gui.messages().sendError(p, "That duration is above your limit."));
            if (slot >= startSlot + 9) break;
        }
    }

    /** Ask for a custom duration ("3d12h", "permanent"). */
    static void customDuration(Gui gui, Player p, boolean allowPermanent, LongConsumer pick, Runnable reopen) {
        gui.prompts().ask(p, "Type a duration such as 3d12h" + (allowPermanent ? " or 'permanent'" : "") + ".", 1, 20, text -> {
            long d = Durations.parse(text);
            if (d == -2 || (d == Durations.PERMANENT && !allowPermanent)) {
                gui.messages().sendError(p, "That is not a valid duration.");
                reopen.run();
                return;
            }
            pick.accept(d);
        }, reopen);
    }

    /** Pick an evidence type, then type the reference; produces an evidence input. */
    static void addEvidence(Gui gui, Player p, Menu returnTo, Consumer<EvidenceValidator.Input> done) {
        new EvidenceTypeMenu(gui, p, returnTo, type -> {
            String hint = switch (type) {
                case SCREENSHOT, VIDEO, EXTERNAL_LINK -> "Paste the link (https://...).";
                case CHAT_LOG -> "Paste the relevant chat log lines or a link to them.";
                case COREPROTECT -> "Paste the CoreProtect command/lookup, e.g. /co l u:Name t:1h r:20.";
                case SERVER_LOG -> "Paste the log excerpt or file reference.";
                case ANTICHEAT -> "Describe the anticheat alert (check, violation level, time).";
                case INCIDENT_REPORT -> "Write the incident report (at least " + gui.settings().cases.minWrittenReport() + " characters).";
                case OTHER -> "Describe or link the evidence.";
            };
            gui.prompts().ask(p, hint, 3, gui.settings().cases.maxEvidenceRef(), ref -> done.accept(new EvidenceValidator.Input(type, ref, "")), returnTo::open);
        }).open();
    }

    /** Revoke flow: reason prompt, confirmation, service call. */
    static void revoke(Gui gui, Player p, Menu parent, Punishment pun, Runnable after) {
        gui.prompts().ask(p, "Why is punishment #" + pun.id() + " (" + pun.type().display() + ") being revoked?",
                gui.settings().cases.minReason(), gui.settings().cases.maxReason(), reason ->
                        new ConfirmMenu(gui, p, parent, "Revoke #" + pun.id(),
                                List.of(pun.type().display() + " on " + pun.targetName(), "Reason: " + reason, "This is recorded in the audit log."), null,
                                () -> gui.call(p, gui.punishments.revoke(gui.actor(p), pun.id(), reason), r -> {
                                    gui.success(p, "Punishment #" + pun.id() + " revoked.");
                                    after.run();
                                }, parent::open)).open(), parent::open);
    }

    static Component none() {
        return Component.empty();
    }
}
