package dev.aura.punishments.gui;

import dev.aura.punishments.config.Offense;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.*;
import dev.aura.punishments.model.Simple.*;
import dev.aura.punishments.service.CaseService.Detail;
import dev.aura.punishments.service.DirectService;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Everything about one case: details, evidence, custody, trial preparation, verdict, sentence, appeals. */
final class CaseMenu extends Menu {
    private static final DateTimeFormatter TRIAL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Detail d;

    private CaseMenu(Gui gui, Player viewer, Menu parent, Detail d) {
        super(gui, viewer, parent);
        this.d = d;
    }

    static void open(Gui gui, Player p, Menu parent, long caseId) {
        gui.call(p, gui.cases.detail(gui.actor(p), caseId), det -> new CaseMenu(gui, p, parent, det).open());
    }

    /** Reload after a change (or a version conflict) and redraw. */
    private void reload() {
        gui.call(viewer, gui.cases.detail(gui.actor(viewer), d.c().id()), det -> {
            this.d = det;
            open();
        });
    }

    private boolean can(String node) {
        return viewer.hasPermission(node);
    }

    private void reason(String question, int min, Consumer<String> then) {
        gui.prompts().ask(viewer, question, min, gui.settings().cases.maxReason(), then, this::open);
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple>Case <white><v>", "v", d.c().ref());
    }

    @Override
    protected void render() {
        fill(0, 53);
        Case c = d.c();
        long now = gui.core().clock.now();
        Optional<Offense> off = gui.core().offenses.get(c.offenseKey());
        navigation();
        set(53, Items.item(Material.SUNFLOWER, "<yellow>Refresh"), (p, t) -> reload());

        // ---- header
        List<Component> h = new ArrayList<>();
        h.add(Items.t("<gray>Status: " + Views.statusColor(c.status()) + "<v>", "v", c.status().display()));
        h.add(Items.t("<gray>Accused: <white><v>", "v", c.accusedName()));
        h.add(Items.t("<gray>Offense: <white><v> <dark_gray>(<s>)", "v", off.map(Offense::display).orElse(c.offenseKey()), "s", c.severity().name()));
        h.add(Items.t("<gray>Prosecutor: <white><v>", "v", c.prosecutorName() == null ? "-" : c.prosecutorName()));
        h.add(Items.t("<gray>Judge: <white><v>", "v", c.judgeName() == null ? "not assigned" : c.judgeName()));
        h.add(Items.t("<gray>Trial: <white><v>", "v", c.trialAt() == null ? "not scheduled" : Gui.time(c.trialAt())));
        h.add(Items.t("<gray>Hearing: <white><v>", "v", c.hearingRef() == null ? "-" : Text.truncate(c.hearingRef(), 40)));
        if (c.verdict() != null) {
            h.add(Items.t("<gray>Verdict: <white><v><gray> by <white><b>", "v", c.verdict().display() + (c.overturned() ? " (OVERTURNED)" : ""), "b", String.valueOf(c.verdictByName())));
            h.add(Items.t("<gray>Reasoning: <white><v>", "v", Text.truncate(String.valueOf(c.verdictReason()), 50)));
        }
        h.add(Items.t("<gray>Prior guilty findings: <white><v>", "v", String.valueOf(d.priorFindings())));
        h.add(Component.empty());
        for (String line : NotesWrap.wrap(c.description(), 40)) h.add(Items.t("<gray><v>", "v", line));
        set(4, Items.item(Views.statusIcon(c.status()), Items.t("<white><b><v> <dark_gray>[<k>]", "v", c.ref(), "k", c.kind().name()), h));
        set(2, Items.head(c.accusedUuid(), c.accusedName(), Items.t("<white><b><v>", "v", c.accusedName()),
                List.of(Items.t("<yellow>Click: open this player's punishment screen"))), (p, t) ->
                gui.resolve(p, c.accusedUuid().toString(), tg -> TargetMenu.open(gui, p, this, tg)));

        // ---- information row
        long activeEv = d.evidence().stream().filter(e -> !e.voided()).count();
        action(10, can(Perms.EVIDENCE_VIEW) || can(Perms.EVIDENCE_ADD), Material.SPYGLASS, "<yellow><b>Evidence",
                List.of(d.evidenceHidden() ? "<red>Not visible to you" : "<gray>Active items: <white>" + activeEv, "<gray>View, submit and correct"),
                (p, t) -> EvidenceMenu.open(gui, p, this, c.id()));
        action(11, can(Perms.NOTE_VIEW) || can(Perms.NOTE_ADD), Material.OAK_SIGN, "<yellow><b>Notes",
                List.of(d.notesHidden() ? "<red>Not visible to you" : "<gray>Notes: <white>" + d.notes().stream().filter(n -> !n.voided()).count(), "<gray>Investigation notes"),
                (p, t) -> NotesMenu.open(gui, p, this, c.id(), null));
        List<String> tl = new ArrayList<>();
        int from = Math.max(0, d.events().size() - 8);
        for (CaseEvent e : d.events().subList(from, d.events().size())) {
            tl.add("<dark_gray><t> <gray><a> <white><k>".replace("<t>", Gui.time(e.ts()).substring(5, 16)).replace("<a>", Text.truncate(esc(e.actorName()), 12)).replace("<k>", esc(e.type().toLowerCase())));
        }
        set(12, Items.item(Material.CLOCK, Items.t("<aqua><b>Timeline <gray>(latest)"), tl.stream().map(s -> Items.t(s)).toList()));
        Custody cu = d.custody();
        boolean live = cu != null && cu.status().isLive();
        List<Component> cl = new ArrayList<>();
        if (cu == null) cl.add(Items.t("<gray>No custody for this case"));
        else cl.addAll(Views.custodyLines(cu, now));
        if (live && cu.status() == CustodyStatus.ACTIVE) {
            if (can(Perms.CUSTODY_RELEASE) || can(Perms.JUDICIAL_RELEASE)) cl.add(Items.t("<yellow>Click: release"));
            if (can(Perms.JUDICIAL_MANAGE)) cl.add(Items.t("<yellow>Right-click: extend deadline"));
        } else if (!live && c.status().custodyAllowed() && can(Perms.CUSTODY_INITIATE) && gui.settings().custody.enabled()) {
            cl.add(Items.t("<yellow>Click: initiate pretrial detention"));
        }
        set(13, Items.item(live ? Material.IRON_BARS : Material.OAK_DOOR, Items.t("<gold><b>Custody"), cl), (p, t) -> custodyClick(p, t, cu, live));
        List<Component> pl = new ArrayList<>();
        for (Punishment pun : d.punishments()) pl.add(Items.t("<red>#<id> <white><t> <gray>(<s>)", "id", String.valueOf(pun.id()), "t", pun.type().display(), "s", pun.status().name().toLowerCase()));
        if (pl.isEmpty()) pl.add(Items.t("<gray>No punishments attached"));
        set(14, Items.item(Material.IRON_AXE, Items.t("<red><b>Punishments"), pl), (p, t) ->
                PunishmentListMenu.openList(gui, p, this, "Punishments: " + c.ref(), d.punishments(), this::reload));
        List<Component> ap = new ArrayList<>();
        if (d.directJustification() != null) {
            ap.add(Items.t("<gray>Direct punishment justification:"));
            for (String line : NotesWrap.wrap(d.directJustification(), 40)) ap.add(Items.t("<white><v>", "v", line));
        }
        for (Appeal a : d.appeals()) ap.add(Items.t("<gray>Appeal by <white><b> <gray>(<s>)", "b", a.filedByName(), "s", a.status().name().toLowerCase()));
        if (ap.isEmpty()) ap.add(Items.t("<gray>No appeals or direct decisions"));
        set(15, Items.item(Material.WRITABLE_BOOK, Items.t("<light_purple><b>Appeals / Direct decision"), ap));

        // ---- management row
        boolean manage = can(Perms.CASE_MANAGE) || can(Perms.JUDICIAL_MANAGE) || can(Perms.CASE_CLOSE);
        action(19, manage, Material.COMPARATOR, "<aqua><b>Change status",
                List.of("<gray>Current: <white>" + c.status().display(), "<gray>Investigate, schedule, start trial, close"), (p, t) -> statusMenu());
        action(20, can(Perms.JUDICIAL_MANAGE), Material.LECTERN, "<light_purple><b>Assign judge",
                List.of("<gray>Judge: <white>" + esc(c.judgeName() == null ? "none" : c.judgeName()), "<yellow>Click: assign yourself", "<yellow>Right-click: assign another player"), (p, t) -> judgeClick(p, t));
        action(21, can(Perms.JUDICIAL_MANAGE) || can(Perms.CASE_MANAGE), Material.CLOCK, "<light_purple><b>Schedule trial",
                List.of("<gray>Trial: <white>" + (c.trialAt() == null ? "not scheduled" : Gui.time(c.trialAt())), "<gray>Enter yyyy-MM-dd HH:mm or e.g. 2d"), (p, t) -> trialClick(p));
        action(22, can(Perms.JUDICIAL_MANAGE) || can(Perms.CASE_MANAGE), Material.NAME_TAG, "<light_purple><b>Hearing reference",
                List.of("<gray>Discord channel or link: <white>" + esc(c.hearingRef() == null ? "-" : Text.truncate(c.hearingRef(), 30))), (p, t) -> hearingClick(p));
        action(23, can(Perms.CASE_MANAGE), Material.ANVIL, "<yellow><b>Edit details",
                List.of("<gray>Change description / severity", "<gray>(before the trial begins)"), (p, t) -> editMenu());
        action(24, can(Perms.CASE_MANAGE), Material.PLAYER_HEAD, "<yellow><b>Reassign prosecutor",
                List.of("<gray>Prosecutor: <white>" + esc(String.valueOf(c.prosecutorName()))), (p, t) -> prosecutorClick(p));

        // ---- judicial row
        boolean awaitingVerdict = c.status() == CaseStatus.IN_TRIAL || c.status() == CaseStatus.AWAITING_VERDICT;
        action(28, can(Perms.JUDICIAL_VERDICT) && awaitingVerdict, Material.BELL, "<gold><b>Enter verdict",
                List.of("<gray>Guilty, not guilty, dismissed", "<gray>or continued / pending review"), (p, t) -> verdictMenu());
        boolean awaitingSentence = c.status() == CaseStatus.GUILTY || c.status() == CaseStatus.AWAITING_SENTENCING;
        action(29, can(Perms.JUDICIAL_SENTENCE) && awaitingSentence, Material.NETHERITE_AXE, "<red><b>Impose sentence",
                List.of("<gray>Warning, mute, ban, jail sentence", "<gray>with time-served credit"), (p, t) -> SentenceMenu.open(gui, p, this, d));
        action(31, can(Perms.APPEAL_FILE) && (c.status() == CaseStatus.SENTENCED || c.status() == CaseStatus.DIRECT_PUNISHMENT_ISSUED), Material.WRITABLE_BOOK, "<aqua><b>File appeal",
                List.of("<gray>Record an appeal against the outcome"), (p, t) -> reason("State the grounds for the appeal.", gui.settings().cases.minReason(), r ->
                        gui.call(p, gui.judicial.fileAppeal(gui.actor(p), c.id(), r), x -> {
                            gui.success(p, "Appeal filed.");
                            reload();
                        }, this::open)));
        action(32, can(Perms.JUDICIAL_OVERTURN) && c.status() == CaseStatus.APPEALED, Material.LECTERN, "<light_purple><b>Decide appeal",
                List.of("<gray>Uphold, overturn or modify"), (p, t) -> appealMenu());
        boolean canOverturn = c.status() == CaseStatus.SENTENCED || c.status() == CaseStatus.DIRECT_PUNISHMENT_ISSUED
                || c.status() == CaseStatus.UNDER_REVIEW || c.status() == CaseStatus.APPEALED;
        action(33, can(Perms.JUDICIAL_OVERTURN) && canOverturn, Material.TNT, "<dark_red><b>Overturn findings",
                List.of("<gray>Revokes all punishments of this case,", "<gray>releases custody and excludes it from", "<gray>future escalation. Recorded permanently."), (p, t) ->
                        reason("Why are the findings being overturned?", gui.settings().judicial.minVerdictReason(), r ->
                                new ConfirmMenu(gui, p, this, "Overturn " + c.ref(), List.of("All punishments of the case are revoked.", "Reason: " + r), null, () ->
                                        gui.call(p, gui.judicial.overturn(gui.actor(p), c.id(), r), x -> {
                                            gui.success(p, "Findings overturned.");
                                            reload();
                                        }, this::open)).open()));
        action(35, can(Perms.EXPORT) && gui.exports != null, Material.MAP, "<aqua><b>Export summary",
                List.of("<gray>Saves a Markdown summary to the", "<gray>plugin's exports folder", "<dark_gray>No IPs or private notes"), (p, t) ->
                        gui.call(p, gui.exports.export(gui.actor(p), c.id(), dev.aura.punishments.service.ExportService.Format.MARKDOWN, true), ex ->
                                gui.success(p, "Saved " + ex.fileName() + " in the exports folder.")));
        action(36, can(Perms.COREPROTECT) && can(Perms.EVIDENCE_ADD) && gui.coreProtect != null && gui.coreProtect.available(), Material.COMPASS, "<green><b>CoreProtect lookup",
                List.of("<gray>Read-only lookup of the accused's", "<gray>blocks, chat, commands or sessions", "<gray>Optionally saved as evidence"), (p, t) -> CoreProtectFlow.start(gui, p, this, c));
        action(37, can(Perms.APPEAL_TICKET) || can(Perms.JUDICIAL_OVERTURN), Material.NAME_TAG, "<aqua><b>Appeal ticket",
                List.of("<gray>Ticket Tool ticket: <white>" + (c.appealTicket() == null ? "none" : c.appealTicket()), "<gray>Click to link / replace"), (p, t) ->
                        gui.prompts().ask(p, "Type the Ticket Tool ticket number (e.g. 0042) or the ticket channel link.", 1, 200, text ->
                                gui.call(p, gui.judicial.linkAppealTicket(gui.actor(p), c.id(), text), x -> {
                                    gui.success(p, "Ticket linked.");
                                    reload();
                                }), this::open));
        if (c.kind() == CaseKind.EMERGENCY) {
            action(34, can(Perms.EMERGENCY_REVIEW), Material.REDSTONE_TORCH, "<red><b>Review emergency action",
                    List.of("<gray>Escalate to trial or revoke"), (p, t) -> emergencyReview());
        }
    }

    /** Neutralise MiniMessage tags in free text that must be placed inside a lore template. */
    private static String esc(String s) {
        return s == null ? "" : net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(s);
    }

    // ------------------------------------------------------------------ actions
    private void custodyClick(Player p, org.bukkit.event.inventory.ClickType t, Custody cu, boolean live) {
        Case c = d.c();
        if (live && cu.status() == CustodyStatus.ACTIVE) {
            if (t.isRightClick() && can(Perms.JUDICIAL_MANAGE)) {
                reason("Why is the custody deadline being extended?", gui.settings().cases.minReason(), r ->
                        gui.call(p, gui.custody.extend(gui.actor(p), c.id(), r), x -> {
                            gui.success(p, "Custody deadline extended to " + Gui.time(x.deadline()) + ".");
                            reload();
                        }, this::open));
            } else if (can(Perms.CUSTODY_RELEASE) || can(Perms.JUDICIAL_RELEASE)) {
                reason("Why is " + c.accusedName() + " being released from custody?", gui.settings().cases.minReason(), r ->
                        new ConfirmMenu(gui, p, this, "Release " + c.accusedName(), List.of("Released through JailReloaded.", "Reason: " + r), null, () ->
                                gui.call(p, gui.custody.release(gui.actor(p), c.id(), r), x -> {
                                    gui.success(p, c.accusedName() + (x.status() == CustodyStatus.RELEASING ? " release accepted (completes at next login)." : " released."));
                                    reload();
                                }, this::open)).open());
            }
        } else if (!live && c.status().custodyAllowed() && can(Perms.CUSTODY_INITIATE) && gui.settings().custody.enabled()) {
            new ConfirmMenu(gui, p, this, "Detain " + c.accusedName(), List.of("Case " + c.ref(), "Held by JailReloaded until the hearing.",
                    "Auto-review in " + Durations.format(gui.settings().custody.reviewDeadline()), "This is NOT a punishment or a verdict."), null, () ->
                    gui.call(p, gui.custody.initiate(gui.actor(p), c.id()), x -> {
                        gui.success(p, c.accusedName() + " detained. Review by " + Gui.time(x.deadline()) + ".");
                        reload();
                    }, this::open)).open();
        }
    }

    private void statusMenu() {
        Case c = d.c();
        List<ChoiceMenu.Choice> choices = new ArrayList<>();
        for (CaseStatus s : new CaseStatus[]{CaseStatus.OPEN, CaseStatus.UNDER_INVESTIGATION, CaseStatus.AWAITING_TRIAL, CaseStatus.IN_TRIAL,
                CaseStatus.AWAITING_VERDICT, CaseStatus.UNDER_REVIEW, CaseStatus.CLOSED}) {
            boolean ok = c.status().canMoveTo(s);
            String why = ok ? "<gray>Click to move the case here" : "<red>Not allowed from " + c.status().display();
            if (s == CaseStatus.IN_TRIAL && c.judgeUuid() == null) {
                ok = false;
                why = "<red>Assign a judge first";
            }
            boolean needsReason = s == CaseStatus.CLOSED || s == CaseStatus.UNDER_REVIEW || s == CaseStatus.OPEN;
            choices.add(new ChoiceMenu.Choice(Views.statusIcon(s), Views.statusColor(s) + "<b>" + s.display(), List.of(why), ok, () -> {
                if (needsReason) {
                    reason("Why is the case moving to '" + s.display() + "'?", gui.settings().cases.minReason(), r -> move(s, r));
                } else {
                    move(s, null);
                }
            }));
        }
        new ChoiceMenu(gui, viewer, this, "Change status: " + c.ref(), Items.item(Material.COMPARATOR, Items.t("<white><b><v>", "v", c.status().display()),
                List.of(Items.t("<gray>Verdicts, sentences and appeals have"), Items.t("<gray>their own buttons on the case screen."))), choices).open();
    }

    private void move(CaseStatus s, String reason) {
        gui.call(viewer, gui.cases.transition(gui.actor(viewer), d.c().id(), d.c().version(), s, reason), c -> {
            gui.success(viewer, "Case moved to " + s.display() + ".");
            reload();
        }, this::reload);
    }

    private void judgeClick(Player p, org.bukkit.event.inventory.ClickType t) {
        Case c = d.c();
        if (t.isRightClick()) {
            gui.prompts().ask(p, "Type the name of the staff member to assign as judge.", 2, 32, name ->
                    gui.call(p, gui.resolver.resolve(name), tg -> assign(tg)), this::open);
        } else {
            assign(new Target(p.getUniqueId(), p.getName(), true));
        }
    }

    private void assign(Target judge) {
        gui.call(viewer, gui.cases.assignJudge(gui.actor(viewer), d.c().id(), d.c().version(), judge), c -> {
            gui.success(viewer, judge.name() + " assigned as judge.");
            reload();
        }, this::reload);
    }

    private void prosecutorClick(Player p) {
        gui.prompts().ask(p, "Type the name of the new prosecutor.", 2, 32, name -> gui.call(p, gui.resolver.resolve(name), tg ->
                gui.call(p, gui.cases.assignProsecutor(gui.actor(p), d.c().id(), d.c().version(), tg), c -> {
                    gui.success(p, tg.name() + " is now the prosecutor.");
                    reload();
                }, this::reload)), this::open);
    }

    private void trialClick(Player p) {
        gui.prompts().ask(p, "Enter the trial time: yyyy-MM-dd HH:mm (server time) or a delay such as 2d or 3h.", 2, 30, text -> {
            long when = parseTrialTime(text, gui.core().clock.now());
            if (when < 0) {
                gui.messages().sendError(p, "That is not a valid time. Use yyyy-MM-dd HH:mm or a delay like 2d.");
                open();
                return;
            }
            gui.call(p, gui.cases.scheduleTrial(gui.actor(p), d.c().id(), d.c().version(), when), c -> {
                gui.success(p, "Trial scheduled for " + Gui.time(when) + ".");
                reload();
            }, this::reload);
        }, this::open);
    }

    static long parseTrialTime(String text, long now) {
        String t = text.trim();
        long rel = Durations.parse(t.startsWith("in ") ? t.substring(3) : t);
        if (rel > 0 && rel != Durations.PERMANENT) return now + rel;
        try {
            return LocalDateTime.parse(t.replace('T', ' '), TRIAL_FMT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private void hearingClick(Player p) {
        gui.prompts().ask(p, "Enter the Discord voice channel name or invite link for the hearing.", 2, 200, ref ->
                gui.call(p, gui.cases.setHearingRef(gui.actor(p), d.c().id(), d.c().version(), ref), c -> {
                    gui.success(p, "Hearing reference saved.");
                    reload();
                }, this::reload), this::open);
    }

    private void editMenu() {
        Case c = d.c();
        List<ChoiceMenu.Choice> ch = new ArrayList<>();
        ch.add(new ChoiceMenu.Choice(Material.WRITABLE_BOOK, "<yellow><b>Description", List.of("<gray>Rewrite the incident description"), true, () ->
                gui.prompts().ask(viewer, "Type the new description.", gui.settings().cases.minDescription(), gui.settings().cases.maxDescription(), text ->
                        edit(new dev.aura.punishments.service.CaseService.Edit(text, null, null, null)), this::open)));
        ch.add(new ChoiceMenu.Choice(Material.OAK_SIGN, "<yellow><b>Context notes", List.of("<gray>Change the contextual notes"), true, () ->
                gui.prompts().ask(viewer, "Type the new contextual notes.", 3, gui.settings().cases.maxNote(), text ->
                        edit(new dev.aura.punishments.service.CaseService.Edit(null, null, null, text)), this::open)));
        ch.add(new ChoiceMenu.Choice(Views.severityIcon(c.severity()), "<yellow><b>Severity: " + c.severity(), List.of("<gray>Click to cycle"), true, () -> {
            Severity next = Severity.values()[(c.severity().ordinal() + 1) % Severity.values().length];
            edit(new dev.aura.punishments.service.CaseService.Edit(null, next, null, null));
        }));
        ch.add(new ChoiceMenu.Choice(Material.BOOKSHELF, "<yellow><b>Offense category", List.of("<gray>Current: " + c.offenseKey()), true, () ->
                new OffenseSelectMenu(gui, viewer, this, o -> true, "", o -> edit(new dev.aura.punishments.service.CaseService.Edit(null, null, o.key(), null))).open()));
        new ChoiceMenu(gui, viewer, this, "Edit " + c.ref(), null, ch).open();
    }

    private void edit(dev.aura.punishments.service.CaseService.Edit e) {
        gui.call(viewer, gui.cases.edit(gui.actor(viewer), d.c().id(), d.c().version(), e), c -> {
            gui.success(viewer, "Case updated.");
            reload();
        }, this::reload);
    }

    private void verdictMenu() {
        Case c = d.c();
        Optional<Offense> off = gui.core().offenses.get(c.offenseKey());
        List<String> hint = new ArrayList<>();
        off.ifPresent(o -> {
            var r = o.recommend(d.priorFindings());
            if (r != null) hint.add("<gray>If guilty, recommended: <white>" + r.describe() + " <dark_gray>(" + d.priorFindings() + " prior)");
        });
        List<ChoiceMenu.Choice> ch = new ArrayList<>();
        for (Verdict v : Verdict.values()) {
            Material m = switch (v) {
                case GUILTY -> Material.RED_CONCRETE;
                case NOT_GUILTY -> Material.LIME_CONCRETE;
                case DISMISSED -> Material.LIGHT_GRAY_CONCRETE;
                case CONTINUED -> Material.YELLOW_CONCRETE;
            };
            String color = switch (v) {
                case GUILTY -> "<red>";
                case NOT_GUILTY -> "<green>";
                case DISMISSED -> "<gray>";
                case CONTINUED -> "<yellow>";
            };
            List<String> lore = new ArrayList<>();
            lore.add(switch (v) {
                case GUILTY -> "<gray>Allows sentencing";
                case NOT_GUILTY -> "<gray>Closes the case; releases custody";
                case DISMISSED -> "<gray>Closes the case with a reason; releases custody";
                case CONTINUED -> "<gray>Case stays open for more proceedings";
            });
            ch.add(new ChoiceMenu.Choice(m, color + "<b>" + v.display(), lore, true, () ->
                    reason("Enter the reasoning for '" + v.display() + "'.", gui.settings().judicial.minVerdictReason(), r ->
                            new ConfirmMenu(gui, viewer, this, v.display() + ": " + c.ref(), List.of("Accused: " + c.accusedName(), "Reasoning: " + r,
                                    v == Verdict.NOT_GUILTY || v == Verdict.DISMISSED ? "Any pretrial custody is released via JailReloaded." : "Recorded in the audit log."), null, () ->
                                    gui.call(viewer, gui.judicial.enterVerdict(gui.actor(viewer), c.id(), c.version(), v, r), res -> {
                                        gui.success(viewer, "Verdict recorded: " + v.display() + ".");
                                        if (res.custodyNote() != null) gui.messages().sendInfo(viewer, res.custodyNote());
                                        reload();
                                    }, this::reload)).open())));
        }
        new ChoiceMenu(gui, viewer, this, "Verdict: " + c.ref(), Items.item(Material.BELL, Items.t("<white><b>Enter verdict"),
                hint.stream().map(s -> Items.t(s)).toList()), ch).open();
    }

    private void appealMenu() {
        Case c = d.c();
        List<ChoiceMenu.Choice> ch = new ArrayList<>();
        for (AppealStatus a : new AppealStatus[]{AppealStatus.UPHELD, AppealStatus.OVERTURNED, AppealStatus.MODIFIED}) {
            String lore = switch (a) {
                case UPHELD -> "<gray>The outcome stands";
                case OVERTURNED -> "<gray>Revokes all punishments; case becomes Not Guilty";
                default -> "<gray>Revokes the punishments; the sentence is re-imposed";
            };
            ch.add(new ChoiceMenu.Choice(a == AppealStatus.UPHELD ? Material.LIME_CONCRETE : a == AppealStatus.OVERTURNED ? Material.RED_CONCRETE : Material.YELLOW_CONCRETE,
                    "<white><b>" + a.name(), List.of(lore), true, () ->
                    reason("Enter the reasoning for the decision.", gui.settings().judicial.minVerdictReason(), r ->
                            new ConfirmMenu(gui, viewer, this, "Appeal " + a.name().toLowerCase(), List.of("Case " + c.ref(), "Reasoning: " + r), null, () ->
                                    gui.call(viewer, gui.judicial.decideAppeal(gui.actor(viewer), c.id(), a, r), x -> {
                                        gui.success(viewer, "Appeal " + a.name().toLowerCase() + ".");
                                        reload();
                                    }, this::reload)).open())));
        }
        new ChoiceMenu(gui, viewer, this, "Appeal: " + c.ref(), null, ch).open();
    }

    private void emergencyReview() {
        Optional<Punishment> em = d.punishments().stream().filter(p -> p.source() == Source.EMERGENCY && !p.reviewed()).findFirst();
        if (em.isEmpty()) {
            gui.messages().sendError(viewer, "There is no unreviewed emergency action on this case.");
            return;
        }
        Punishment p = em.get();
        List<ChoiceMenu.Choice> ch = new ArrayList<>();
        for (DirectService.ReviewDecision rd : DirectService.ReviewDecision.values()) {
            boolean esc = rd == DirectService.ReviewDecision.ESCALATE_TO_TRIAL;
            ch.add(new ChoiceMenu.Choice(esc ? Material.CLOCK : Material.LIME_CONCRETE, (esc ? "<gold><b>Escalate to trial" : "<green><b>Revoke and dismiss"),
                    List.of(esc ? "<gray>The case goes to trial; the action runs to its expiry" : "<gray>Lifts the action now and dismisses the case"), true, () ->
                    reason("Enter the review reasoning.", gui.settings().cases.minReason(), r ->
                            gui.call(viewer, gui.direct.reviewEmergency(gui.actor(viewer), p.id(), rd, r), x -> {
                                gui.success(viewer, "Emergency action reviewed.");
                                reload();
                            }, this::reload))));
        }
        new ChoiceMenu(gui, viewer, this, "Review emergency #" + p.id(), Items.item(Material.REDSTONE_TORCH, Items.t("<red><b>Emergency <t>", "t", p.type().display()),
                List.of(Items.t("<gray>Issued by <white><v>", "v", p.issuerName()))), ch).open();
    }
}
