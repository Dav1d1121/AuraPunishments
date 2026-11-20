package dev.aura.punishments.gui;

import dev.aura.punishments.model.Records.Evidence;
import dev.aura.punishments.service.CaseService.Detail;
import dev.aura.punishments.service.Perms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;

/** Evidence for one case: view, submit, void (never delete) and correct. */
final class EvidenceMenu extends Menu {
    private final long caseId;
    private Detail detail;
    private int page;

    private EvidenceMenu(Gui gui, Player viewer, Menu parent, long caseId, Detail detail) {
        super(gui, viewer, parent);
        this.caseId = caseId;
        this.detail = detail;
    }

    static void open(Gui gui, Player p, Menu parent, long caseId) {
        gui.call(p, gui.cases.detail(gui.actor(p), caseId), d -> new EvidenceMenu(gui, p, parent, caseId, d).open());
    }

    /** Add-evidence flow starting from anywhere: type -> reference -> submit. */
    static void promptAdd(Gui gui, Player p, Menu parent, long caseId) {
        Pickers.addEvidence(gui, p, parent, in -> gui.call(p, gui.cases.addEvidence(gui.actor(p), caseId, in), ev -> {
            gui.success(p, "Evidence #" + ev.id() + " added to the case.");
            open(gui, p, parent, caseId);
        }, parent::open));
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple>Evidence - <v>", "v", detail.c().ref());
    }

    @Override
    protected void render() {
        navigation();
        if (detail.evidenceHidden()) {
            set(22, Items.item(Material.BARRIER, "<red>You may not view evidence on this case"));
            return;
        }
        List<Evidence> all = detail.evidence();
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
        if (viewer.hasPermission(Perms.EVIDENCE_ADD) && detail.c().status().acceptsEvidence()) {
            set(46, Items.item(Material.EMERALD, "<green><b>Submit evidence", "<gray>Screenshot, video, chat log, CoreProtect,", "<gray>server log, report or link"),
                    (p, t) -> promptAdd(gui, p, this, caseId));
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
                        Pickers.addEvidence(gui, p, this, in -> gui.call(p, gui.cases.voidEvidence(gui.actor(p), e.id(), reason, in), r -> {
                            gui.success(p, "Evidence #" + e.id() + " voided and replaced.");
                            open(gui, p, parent, caseId);
                        }, this::open));
                    } else {
                        gui.call(p, gui.cases.voidEvidence(gui.actor(p), e.id(), reason, null), r -> {
                            gui.success(p, "Evidence #" + e.id() + " voided.");
                            open(gui, p, parent, caseId);
                        }, this::open);
                    }
                }, this::open);
    }
}
