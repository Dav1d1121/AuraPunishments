package dev.aura.punishments.gui;

import dev.aura.punishments.model.Records.Evidence;
import dev.aura.punishments.service.Actor;
import dev.aura.punishments.service.CaseService.Detail;
import dev.aura.punishments.service.EvidenceValidator;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.util.Result;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Evidence for one case or one punishment: view, submit, void (never delete) and correct. */
final class EvidenceMenu extends Menu {
    /** What the evidence belongs to. */
    interface Subject {
        String heading();

        boolean hidden();

        List<Evidence> list();

        boolean canAdd();

        String noun();

        CompletableFuture<Result<Evidence>> add(Actor a, EvidenceValidator.Input in);

        CompletableFuture<Result<Evidence>> voidIt(Actor a, long id, String reason, EvidenceValidator.Input replacement);

        /** Load the subject again and open a fresh menu. */
        void reopen(Gui gui, Player p, Menu parent);
    }

    private final Subject subject;
    private int page;

    private EvidenceMenu(Gui gui, Player viewer, Menu parent, Subject subject) {
        super(gui, viewer, parent);
        this.subject = subject;
    }

    private static Subject ofCase(Gui gui, Player viewer, long caseId, Detail detail) {
        return new Subject() {
            public String heading() { return detail.c().ref(); }
            public boolean hidden() { return detail.evidenceHidden(); }
            public List<Evidence> list() { return detail.evidence(); }
            public boolean canAdd() { return viewer.hasPermission(Perms.EVIDENCE_ADD) && detail.c().status().acceptsEvidence(); }
            public String noun() { return "case"; }
            public CompletableFuture<Result<Evidence>> add(Actor a, EvidenceValidator.Input in) { return gui.cases.addEvidence(a, caseId, in); }
            public CompletableFuture<Result<Evidence>> voidIt(Actor a, long id, String reason, EvidenceValidator.Input rep) { return gui.cases.voidEvidence(a, id, reason, rep); }
            public void reopen(Gui g, Player p, Menu parent) { EvidenceMenu.open(g, p, parent, caseId); }
        };
    }

    private static Subject ofPunishment(Gui gui, long punishmentId, dev.aura.punishments.service.PunishmentRecords.Detail d) {
        return new Subject() {
            public String heading() { return "#" + punishmentId + " " + d.punishment().type().display(); }
            public boolean hidden() { return d.evidenceHidden(); }
            public List<Evidence> list() { return d.evidence(); }
            public boolean canAdd() { return d.mayAddEvidence(); }
            public String noun() { return "punishment"; }
            public CompletableFuture<Result<Evidence>> add(Actor a, EvidenceValidator.Input in) { return gui.records.addEvidence(a, punishmentId, in); }
            public CompletableFuture<Result<Evidence>> voidIt(Actor a, long id, String reason, EvidenceValidator.Input rep) { return gui.records.voidEvidence(a, id, reason, rep); }
            public void reopen(Gui g, Player p, Menu parent) { EvidenceMenu.openForPunishment(g, p, parent, punishmentId); }
        };
    }

    static void open(Gui gui, Player p, Menu parent, long caseId) {
        gui.call(p, gui.cases.detail(gui.actor(p), caseId), d -> new EvidenceMenu(gui, p, parent, ofCase(gui, p, caseId, d)).open());
    }

    static void openForPunishment(Gui gui, Player p, Menu parent, long punishmentId) {
        gui.call(p, gui.records.detail(gui.actor(p), punishmentId), d -> new EvidenceMenu(gui, p, parent, ofPunishment(gui, punishmentId, d)).open());
    }

    /** Add-evidence flow starting from anywhere: type -> reference -> submit. */
    static void promptAdd(Gui gui, Player p, Menu parent, long caseId) {
        Pickers.addEvidence(gui, p, parent, in -> gui.call(p, gui.cases.addEvidence(gui.actor(p), caseId, in), ev -> {
            gui.success(p, "Evidence #" + ev.id() + " added to the case.");
            open(gui, p, parent, caseId);
        }, parent::open));
    }

    static void promptAddForPunishment(Gui gui, Player p, Menu parent, long punishmentId) {
        Pickers.addEvidence(gui, p, parent, in -> gui.call(p, gui.records.addEvidence(gui.actor(p), punishmentId, in), ev -> {
            gui.success(p, "Evidence #" + ev.id() + " added to the punishment.");
            openForPunishment(gui, p, parent, punishmentId);
        }, parent::open));
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple>Evidence - <v>", "v", subject.heading());
    }

    @Override
    protected void render() {
        navigation();
        if (subject.hidden()) {
            set(22, Items.item(Material.BARRIER, "<red>You may not view evidence on this " + subject.noun()));
            return;
        }
        List<Evidence> all = subject.list();
        int per = Views.LIST_SLOTS.length;
        int pages = Math.max(1, (all.size() + per - 1) / per);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < per; i++) {
            int idx = page * per + i;
            if (idx >= all.size()) break;
            Evidence e = all.get(idx);
            set(Views.LIST_SLOTS[i], withHints(Views.evidenceItem(e), e), (p, t) -> click(p, t, e));
        }
        if (all.isEmpty()) set(22, Items.item(Material.STRUCTURE_VOID, "<gray>No evidence yet"));
        set(4, Items.item(Material.SPYGLASS, Items.t("<white><b>Evidence <gray>(<n>)", "n", String.valueOf(all.stream().filter(x -> !x.voided()).count())),
                List.of(Items.t("<gray>Evidence is never deleted:"), Items.t("<gray>it is voided with a reason."))));
        if (subject.canAdd()) {
            set(46, Items.item(Material.EMERALD, "<green><b>Submit evidence", "<gray>Screenshot, video, chat log, CoreProtect,", "<gray>server log, report or link"), (p, t) ->
                    Pickers.addEvidence(gui, p, this, in -> gui.call(p, subject.add(gui.actor(p), in), ev -> {
                        gui.success(p, "Evidence #" + ev.id() + " added to the " + subject.noun() + ".");
                        subject.reopen(gui, p, parent);
                    }, this::open)));
        }
        if (page > 0) set(48, Items.item(Material.SPECTRAL_ARROW, "<yellow>Previous page"), (p, t) -> {
            page--;
            refresh();
        });
        if (page + 1 < pages) set(50, Items.item(Material.SPECTRAL_ARROW, "<yellow>Next page"), (p, t) -> {
            page++;
            refresh();
        });
    }

    private org.bukkit.inventory.ItemStack withHints(org.bukkit.inventory.ItemStack it, Evidence e) {
        var meta = it.getItemMeta();
        var lore = meta.lore() == null ? new java.util.ArrayList<Component>() : new java.util.ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(Items.t("<yellow>Left-click: show reference in chat"));
        if (!e.voided() && viewer.hasPermission(Perms.EVIDENCE_VOID)) {
            lore.add(Items.t("<yellow>Right-click: void  <dark_gray>| <yellow>Shift+Right: correct"));
        }
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private void click(Player p, ClickType t, Evidence e) {
        if (t.isLeftClick()) {
            Component line = Items.t("<gray>Evidence #<id> (<type>): ", "id", String.valueOf(e.id()), "type", e.type().name().toLowerCase());
            Component ref = Component.text(e.reference()).color(NamedTextColor.AQUA);
            if (dev.aura.punishments.util.Text.isHttpUrl(e.reference())) ref = ref.clickEvent(ClickEvent.openUrl(e.reference()));
            else ref = ref.clickEvent(ClickEvent.copyToClipboard(e.reference()));
            p.closeInventory();
            p.sendMessage(line.append(ref));
            return;
        }
        if (e.voided() || !p.hasPermission(Perms.EVIDENCE_VOID)) return;
        boolean correct = t.isShiftClick();
        gui.prompts().ask(p, "Why is evidence #" + e.id() + " being voided? (reason is recorded)", gui.settings().cases.minReason(),
                gui.settings().cases.maxReason(), reason -> {
                    if (correct) {
                        Pickers.addEvidence(gui, p, this, in -> gui.call(p, subject.voidIt(gui.actor(p), e.id(), reason, in), r -> {
                            gui.success(p, "Evidence #" + e.id() + " voided and replaced.");
                            subject.reopen(gui, p, parent);
                        }, this::open));
                    } else {
                        gui.call(p, subject.voidIt(gui.actor(p), e.id(), reason, null), r -> {
                            gui.success(p, "Evidence #" + e.id() + " voided.");
                            subject.reopen(gui, p, parent);
                        }, this::open);
                    }
                }, this::open);
    }
}
