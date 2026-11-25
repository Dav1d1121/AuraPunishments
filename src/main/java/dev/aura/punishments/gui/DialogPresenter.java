package dev.aura.punishments.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Builds the native Minecraft dialog window (text box + Submit / Cancel) used for every text entry. */
final class DialogPresenter implements PromptManager.Presenter {
    static final String KEY = "answer";
    private final Gui gui;

    DialogPresenter(Gui gui) {
        this.gui = gui;
    }

    @Override
    public void show(Player p, PromptManager.Request r) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(r.question()), 300));
        String limits = r.min() > 1 ? "At least " + r.min() + " and at most " + r.max() + " characters." : "At most " + r.max() + " characters.";
        body.add(DialogBody.plainMessage(Component.text(limits, NamedTextColor.GRAY), 300));
        if (r.error() != null) body.add(DialogBody.plainMessage(Component.text(r.error(), NamedTextColor.RED), 300));
        TextDialogInput.Builder in = DialogInput.text(KEY, Component.text("Your answer")).width(300).labelVisible(true)
                .initial(r.initial() == null ? "" : r.initial()).maxLength(Math.max(1, r.max()));
        if (r.max() > 120) in.multiline(TextDialogInput.MultilineOptions.create(6, 110));
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofSeconds(Math.max(30, r.seconds() + 30L))).build();
        ActionButton submit = ActionButton.builder(Component.text("Submit", NamedTextColor.GREEN, TextDecoration.BOLD)).width(140)
                .tooltip(Component.text("Use this answer"))
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player who && who.getUniqueId().equals(p.getUniqueId())) r.onSubmit().accept(view.getText(KEY));
                }, once)).build();
        ActionButton cancel = ActionButton.builder(Component.text("Cancel", NamedTextColor.RED)).width(140)
                .tooltip(Component.text("Close without changing anything"))
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player who && who.getUniqueId().equals(p.getUniqueId())) r.onCancel().run();
                }, once)).build();
        DialogBase base = DialogBase.builder(Component.text("AuraPunishments - your input is needed", NamedTextColor.GOLD))
                .canCloseWithEscape(false).pause(false).afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body).inputs(List.of(in.build())).build();
        Dialog dialog = Dialog.create(f -> f.empty().base(base).type(DialogType.confirmation(submit, cancel)));
        p.showDialog(dialog);
    }

    @Override
    public void close(Player p) {
        try {
            p.closeDialog();
        } catch (RuntimeException ignored) {
            // the player may already have left
        }
    }
}
