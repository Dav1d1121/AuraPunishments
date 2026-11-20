package dev.aura.punishments.gui;

import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.*;
import dev.aura.punishments.model.Simple.*;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Shared item renderings for cases, punishments, evidence and custody. */
final class Views {
    private Views() {}

    static final int[] LIST_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    static Material statusIcon(CaseStatus s) {
        return switch (s) {
            case OPEN -> Material.BOOK;
            case UNDER_INVESTIGATION -> Material.SPYGLASS;
            case AWAITING_TRIAL -> Material.CLOCK;
            case IN_TRIAL -> Material.BELL;
            case AWAITING_VERDICT -> Material.COMPASS;
            case GUILTY -> Material.RED_CONCRETE;
            case AWAITING_SENTENCING -> Material.ORANGE_CONCRETE;
            case NOT_GUILTY -> Material.LIME_CONCRETE;
            case DISMISSED -> Material.LIGHT_GRAY_CONCRETE;
            case SENTENCED -> Material.IRON_AXE;
            case DIRECT_PUNISHMENT_ISSUED -> Material.NETHERITE_SWORD;
            case UNDER_REVIEW -> Material.LECTERN;
            case APPEALED -> Material.WRITABLE_BOOK;
            case CLOSED -> Material.GRAY_CONCRETE;
        };
    }

    static String statusColor(CaseStatus s) {
        return switch (s) {
            case GUILTY, SENTENCED, DIRECT_PUNISHMENT_ISSUED -> "<red>";
            case NOT_GUILTY -> "<green>";
            case AWAITING_TRIAL, IN_TRIAL, AWAITING_VERDICT, AWAITING_SENTENCING -> "<gold>";
            case DISMISSED, CLOSED -> "<gray>";
            default -> "<aqua>";
        };
    }

    static Material punishIcon(PunishType t) {
        return switch (t) {
            case WARN -> Material.PAPER;
            case KICK -> Material.LEATHER_BOOTS;
            case MUTE -> Material.SKELETON_SKULL;
            case BAN -> Material.BARRIER;
            case IPBAN -> Material.TNT;
            case JAIL -> Material.IRON_BARS;
        };
    }

    static String duration(long ms) {
        return ms == Durations.PERMANENT ? "permanent" : Durations.format(ms);
    }

    static ItemStack caseItem(Case c) {
        List<Component> l = new ArrayList<>();
        l.add(Items.t("<gray>Accused: <white><v>", "v", c.accusedName()));
        l.add(Items.t("<gray>Offense: <white><v> <dark_gray>(<s>)", "v", c.offenseKey(), "s", c.severity().name()));
        l.add(Items.t("<gray>Status: " + statusColor(c.status()) + "<v>", "v", c.status().display()));
        l.add(Items.t("<gray>Prosecutor: <white><v>", "v", c.prosecutorName() == null ? "-" : c.prosecutorName()));
        if (c.judgeName() != null) l.add(Items.t("<gray>Judge: <white><v>", "v", c.judgeName()));
        if (c.trialAt() != null) l.add(Items.t("<gray>Trial: <white><v>", "v", Gui.time(c.trialAt())));
        if (c.verdict() != null) l.add(Items.t("<gray>Verdict: <white><v>", "v", c.verdict().display() + (c.overturned() ? " (OVERTURNED)" : "")));
        l.add(Items.t("<gray>Opened: <white><v>", "v", Gui.time(c.createdAt())));
        l.add(Items.t("<dark_gray><v>", "v", Text.truncate(c.description(), 48)));
        l.add(Component.empty());
        l.add(Items.t("<yellow>Click to open"));
        return Items.item(statusIcon(c.status()), Items.t("<white><b><v></b> <dark_gray>[<k>]", "v", c.ref(), "k", c.kind().name()), l);
    }

    static ItemStack punishmentItem(Punishment p, boolean showIp, long now) {
        List<Component> l = new ArrayList<>();
        l.add(Items.t("<gray>Target: <white><v>", "v", p.targetName()));
        l.add(Items.t("<gray>Status: <white><v>", "v", p.status().name().toLowerCase()));
        l.add(Items.t("<gray>Issued by: <white><v> <dark_gray>(<s>)", "v", p.issuerName(), "s", p.source().name().toLowerCase()));
        l.add(Items.t("<gray>Issued: <white><v>", "v", Gui.time(p.issuedAt())));
        if (p.type().canBeTimed()) {
            l.add(Items.t("<gray>Expires: <white><v>", "v", p.expiresAt() == null ? "never" : Gui.time(p.expiresAt())
                    + (p.status() == PunishStatus.ACTIVE ? " (" + Durations.format(Math.max(0, p.expiresAt() - now)) + " left)" : "")));
        }
        l.add(Items.t("<gray>Reason: <white><v>", "v", Text.truncate(p.reason(), 60)));
        if (p.caseId() != null) l.add(Items.t("<gray>Case: <white>AP-<v>", "v", String.format("%04d", p.caseId())));
        if (showIp && p.ip() != null) l.add(Items.t("<gray>IP: <red><v>", "v", p.ip()));
        if (p.endedAt() != null) {
            l.add(Items.t("<gray>Ended: <white><v> <dark_gray>by <who>", "v", Gui.time(p.endedAt()), "who", p.endedBy() == null ? "-" : p.endedBy()));
            if (p.endReason() != null) l.add(Items.t("<gray>End reason: <white><v>", "v", Text.truncate(p.endReason(), 60)));
        }
        String color = p.status() == PunishStatus.ACTIVE ? "<red>" : (p.status() == PunishStatus.REVOKED ? "<green>" : "<gray>");
        return Items.item(punishIcon(p.type()), Items.t(color + "<b>#<id> <t></b>", "id", String.valueOf(p.id()), "t", p.type().display()), l);
    }

    static ItemStack evidenceItem(Evidence e) {
        List<Component> l = new ArrayList<>();
        l.add(Items.t("<gray>Type: <white><v>", "v", e.type().name().toLowerCase().replace('_', ' ')));
        l.add(Items.t("<gray>Reference: <aqua><v>", "v", Text.truncate(e.reference(), 70)));
        if (e.description() != null && !e.description().isBlank()) l.add(Items.t("<gray>Note: <white><v>", "v", Text.truncate(e.description(), 60)));
        l.add(Items.t("<gray>Submitted by <white><v> <gray>at <white><t>", "v", e.submittedName(), "t", Gui.time(e.submittedAt())));
        if (e.voided()) {
            l.add(Items.t("<red>VOIDED by <v>: <w>", "v", String.valueOf(e.voidedName()), "w", Text.truncate(String.valueOf(e.voidReason()), 50)));
        }
        if (e.correctsId() != null) l.add(Items.t("<yellow>Corrects evidence #<v>", "v", String.valueOf(e.correctsId())));
        return Items.item(e.voided() ? Material.BARRIER : evidenceIcon(e.type()), Items.t((e.voided() ? "<red><st>" : "<white>") + "Evidence #<id>", "id", String.valueOf(e.id())), l);
    }

    static Material evidenceIcon(EvidenceType t) {
        return switch (t) {
            case SCREENSHOT -> Material.PAINTING;
            case VIDEO -> Material.ENDER_EYE;
            case CHAT_LOG -> Material.WRITABLE_BOOK;
            case COREPROTECT -> Material.COMPASS;
            case SERVER_LOG -> Material.MAP;
            case INCIDENT_REPORT -> Material.BOOK;
            case ANTICHEAT -> Material.SHIELD;
            case EXTERNAL_LINK -> Material.NAME_TAG;
            case OTHER -> Material.PAPER;
        };
    }

    static List<Component> custodyLines(Custody cu, long now) {
        List<Component> l = new ArrayList<>();
        l.add(Items.t("<gray>Status: <white><v>", "v", cu.status().name()));
        l.add(Items.t("<gray>Case: <white>AP-<v>", "v", String.format("%04d", cu.caseId())));
        if (cu.jailName() != null) l.add(Items.t("<gray>Jail: <white><v>", "v", cu.jailName()));
        l.add(Items.t("<gray>Detained by: <white><v>", "v", cu.requestedName()));
        if (cu.startedAt() != null) l.add(Items.t("<gray>Since: <white><v> <dark_gray>(<d>)", "v", Gui.time(cu.startedAt()),
                "d", Durations.format(Math.max(0, now - cu.startedAt())) + " ago"));
        if (cu.deadline() != null) {
            long left = cu.deadline() - now;
            l.add(Items.t(left < 0 ? "<red>Review deadline: <v> (OVERDUE)" : "<gray>Review deadline: <white><v> <dark_gray>(<d> left)",
                    "v", Gui.time(cu.deadline()), "d", Durations.format(Math.max(0, left))));
            l.add(Items.t("<gray>Extensions used: <white><v>", "v", String.valueOf(cu.extensions())));
        }
        if (cu.lastError() != null) l.add(Items.t("<red>Last problem: <v>", "v", Text.truncate(cu.lastError(), 60)));
        return l;
    }

    static Material severityIcon(Severity s) {
        return switch (s) {
            case MINOR -> Material.LIME_DYE;
            case MODERATE -> Material.YELLOW_DYE;
            case MAJOR -> Material.ORANGE_DYE;
            case SEVERE -> Material.RED_DYE;
        };
    }
}
