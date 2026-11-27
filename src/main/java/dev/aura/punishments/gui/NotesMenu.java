package dev.aura.punishments.gui;

import dev.aura.punishments.model.Records.Note;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Staff notes on a case or on a player. Notes are voided with a reason, never deleted. */
final class NotesMenu extends Menu {
    private final Long caseId;
    private final Long punishmentId;
    private final Target profile;
    private final List<Note> notes;

    private NotesMenu(Gui gui, Player viewer, Menu parent, Long caseId, Long punishmentId, Target profile, List<Note> notes) {
        super(gui, viewer, parent);
        this.caseId = caseId;
        this.punishmentId = punishmentId;
        this.profile = profile;
        this.notes = notes;
    }

    /** Notes attached to one punishment. */
    static void openForPunishment(Gui gui, Player p, Menu parent, long punishmentId) {
        CompletableFuture<Result<List<Note>>> f = gui.records.detail(gui.actor(p), punishmentId)
                .thenApply(r -> r.isOk() ? Result.ok(r.value().notes()) : r.<List<Note>>cast());
        gui.call(p, f, list -> new NotesMenu(gui, p, parent, null, punishmentId, null, list).open());
    }

    static void open(Gui gui, Player p, Menu parent, Long caseId, Target profile) {
        CompletableFuture<Result<List<Note>>> f = caseId != null
                ? gui.cases.detail(gui.actor(p), caseId).thenApply(r -> r.isOk() ? Result.ok(r.value().notes()) : r.<List<Note>>cast())
                : gui.cases.profileNotes(gui.actor(p), profile.uuid());
        gui.call(p, f, list -> new NotesMenu(gui, p, parent, caseId, null, profile, list).open());
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple>Staff notes");
    }

    @Override
    protected void render() {
        navigation();
        int i = 0;
        for (Note n : notes) {
            if (n.voided() || i >= Views.LIST_SLOTS.length) continue;
            List<Component> lore = new ArrayList<>();
            lore.add(Items.t("<gray>By <white><a> <gray>at <white><t>", "a", n.authorName(), "t", Gui.time(n.createdAt())));
            for (String line : wrap(n.text(), 38)) lore.add(Items.t("<white><v>", "v", line));
            if (n.sensitive()) lore.add(Items.t("<red>Sensitive"));
            if (viewer.hasPermission(Perms.NOTE_VOID)) lore.add(Items.t("<yellow>Right-click to void"));
            set(Views.LIST_SLOTS[i++], Items.item(n.sensitive() ? Material.RED_DYE : Material.OAK_SIGN, Items.t("<white>Note #<id>", "id", String.valueOf(n.id())), lore),
                    (p, t) -> {
                        if (t == ClickType.RIGHT && p.hasPermission(Perms.NOTE_VOID)) {
                            gui.prompts().ask(p, "Why is note #" + n.id() + " being voided?", gui.settings().cases.minReason(), gui.settings().cases.maxReason(),
                                    reason -> gui.call(p, gui.cases.voidNote(gui.actor(p), n.id(), reason), v -> {
                                        gui.success(p, "Note voided.");
                                        reopen(p);
                                    }, this::open), this::open);
                        }
                    });
        }
        if (i == 0) set(22, Items.item(Material.STRUCTURE_VOID, "<gray>No notes"));
        if (viewer.hasPermission(Perms.NOTE_ADD)) {
            set(46, Items.item(Material.WRITABLE_BOOK, "<green><b>Add note", "<gray>Click: normal note",
                    viewer.hasPermission(Perms.SENSITIVE_NOTES) ? "<gray>Shift-click: sensitive note (restricted)" : "<dark_gray>Sensitive notes need extra permission"), (p, t) -> {
                boolean sensitive = t.isShiftClick();
                gui.prompts().ask(p, "Type the note.", 3, gui.settings().cases.maxNote(), text ->
                        gui.call(p, punishmentId != null ? gui.records.addNote(gui.actor(p), punishmentId, text, sensitive)
                                : gui.cases.addNote(gui.actor(p), caseId, caseId == null ? profile.uuid() : null, text, sensitive), n -> {
                            gui.success(p, "Note added.");
                            reopen(p);
                        }, this::open), this::open);
            });
        }
    }

    private void reopen(Player p) {
        if (punishmentId != null) openForPunishment(gui, p, parent, punishmentId);
        else open(gui, p, parent, caseId, profile);
    }

    private static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        String rest = Text.clean(s);
        while (rest.length() > width) {
            int cut = rest.lastIndexOf(' ', width);
            if (cut < 10) cut = width;
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }
}
