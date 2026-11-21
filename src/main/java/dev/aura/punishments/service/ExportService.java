package dev.aura.punishments.service;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Records.CaseEvent;
import dev.aura.punishments.model.Records.Evidence;
import dev.aura.punishments.model.Records.Note;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Records.Review;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import dev.aura.punishments.util.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;

/**
 * Case summary export (Markdown or plain text), e.g. to attach to a Discord hearing or an appeal ticket.
 *
 * <p>An export contains exactly what the exporting staff member is allowed to see in the GUI (evidence only with
 * evidence.view, notes only with note.view, private notes only with sensitive.notes) and never contains IP addresses
 * or private review notes. Files are written to a fixed folder inside the plugin folder under a generated name, so a
 * caller can never choose a path.
 */
public final class ExportService extends Svc {
    public enum Format {
        MARKDOWN("md"), TEXT("txt");

        public final String ext;

        Format(String ext) {
            this.ext = ext;
        }

        public static Format parse(String s) {
            return s != null && (s.equalsIgnoreCase("txt") || s.equalsIgnoreCase("text")) ? TEXT : MARKDOWN;
        }
    }

    public record Export(String fileName, String content, Path path) {}

    private final CaseService cases;
    private final Path folder;

    public ExportService(Core core, CaseService cases, Path pluginFolder) {
        super(core);
        this.cases = cases;
        this.folder = pluginFolder == null ? null : pluginFolder.resolve(core.settings.exports.folder());
    }

    public CompletableFuture<Result<Export>> export(Actor actor, long caseId, Format format, boolean writeFile) {
        Result<Void> perm = core.auth.require(actor, Perms.EXPORT, "export case summaries");
        if (!perm.isOk()) return done(perm.cast());
        if (!core.settings.exports.enabled()) return done(Result.fail(Code.UNAVAILABLE, "Case exports are disabled in the configuration."));
        return cases.detail(actor, caseId).thenCompose(res -> {
            if (!res.isOk()) return done(res.<Export>cast());
            CaseService.Detail d = res.value();
            return db(c -> {
                Review rv = core.auth.has(actor, Perms.REVIEW_VIEW) ? Repo.Reviews.byCase(c, caseId) : null;
                String text = render(d, rv, format, actor);
                audit(c, actor, "case.export", d.c().accusedUuid(), caseId, "format=" + format.ext, false);
                return Result.ok(text);
            }).thenApply(tr -> {
                if (!tr.isOk()) return tr.<Export>cast();
                String name = d.c().ref() + "-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC)
                        .format(java.time.Instant.ofEpochMilli(core.clock.now())) + "." + format.ext;
                Path out = null;
                if (writeFile && folder != null) {
                    try {
                        Files.createDirectories(folder);
                        out = folder.resolve(name);
                        Files.writeString(out, tr.value(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    } catch (IOException e) {
                        return Result.<Export>fail(Code.EXTERNAL, "The summary could not be written to the exports folder (" + e.getClass().getSimpleName() + ").");
                    }
                }
                core.emit(DomainEvent.of(DomainEvent.Type.EXPORT).key("export:" + caseId + ":" + name).caseId(caseId).player(d.c().accusedName())
                        .actor(actor.name()).summary("Case summary exported (" + format.ext + ")").build());
                return Result.ok(new Export(name, tr.value(), out));
            });
        });
    }

    // ------------------------------------------------------------------ rendering
    private static final class W {
        final StringBuilder sb = new StringBuilder();
        final boolean md;

        W(boolean md) {
            this.md = md;
        }

        W h1(String s) {
            sb.append(md ? "# " + s + "\n\n" : s.toUpperCase() + "\n" + "=".repeat(Math.min(70, s.length())) + "\n\n");
            return this;
        }

        W h2(String s) {
            sb.append(md ? "## " + s + "\n\n" : "\n" + s + "\n" + "-".repeat(Math.min(70, s.length())) + "\n");
            return this;
        }

        W kv(String k, String v) {
            if (v == null || v.isBlank()) return this;
            sb.append(md ? "- **" + k + ":** " + esc(v) + "\n" : k + ": " + v + "\n");
            return this;
        }

        W li(String s) {
            sb.append(md ? "- " + esc(s) + "\n" : " * " + s + "\n");
            return this;
        }

        W p(String s) {
            sb.append(md ? esc(s) + "\n\n" : s + "\n\n");
            return this;
        }

        W nl() {
            sb.append("\n");
            return this;
        }

        private String esc(String s) {
            // keep staff-typed text from being interpreted as Markdown structure or mentions when pasted into Discord
            return s.replace("\r", "").replace("\n", " ").replace("@everyone", "@​everyone").replace("@here", "@​here");
        }
    }

    String render(CaseService.Detail d, Review rv, Format format, Actor actor) {
        Case c = d.c();
        W w = new W(format == Format.MARKDOWN);
        w.h1("Case summary " + c.ref());
        w.kv("Accused", c.accusedName()).kv("Offense", core.offenses.get(c.offenseKey()).map(o -> o.display()).orElse(c.offenseKey()))
                .kv("Severity", c.severity().name()).kv("Kind", c.kind().name()).kv("Status", c.status().display())
                .kv("Opened", Text.dateTime(c.createdAt())).kv("Prosecutor", c.prosecutorName()).kv("Judge", c.judgeName())
                .kv("Trial", c.trialAt() == null ? null : Text.dateTime(c.trialAt())).kv("Hearing", c.hearingRef())
                .kv("Appeal ticket", c.appealTicket()).kv("Prior findings counted", String.valueOf(d.priorFindings()));
        w.nl().h2("Allegation").p(c.description());
        if (c.context() != null && !c.context().isBlank()) w.h2("Context").p(c.context());
        if (c.verdict() != null) {
            w.h2("Verdict").kv("Verdict", c.verdict().display()).kv("By", c.verdictByName())
                    .kv("Date", c.verdictAt() == null ? null : Text.dateTime(c.verdictAt())).kv("Reasoning", c.verdictReason());
            if (c.overturned()) w.kv("Overturned", "yes");
        }
        if (d.directJustification() != null && !d.directJustification().isBlank()) w.h2("Justification for direct action").p(d.directJustification());
        w.h2("Evidence");
        if (d.evidenceHidden()) w.p("(You do not have permission to see evidence, so none is included.)");
        else if (d.evidence().isEmpty()) w.p("None recorded.");
        for (Evidence e : d.evidence()) {
            w.li((e.voided() ? "[VOIDED: " + e.voidReason() + "] " : "") + "#" + e.id() + " " + e.type() + " - " + e.reference()
                    + (e.description() == null || e.description().isBlank() ? "" : " - " + e.description()) + " (by " + e.submittedName() + ", " + Text.dateTime(e.submittedAt()) + ")");
        }
        w.nl();
        if (!d.notesHidden()) {
            w.h2("Staff notes");
            if (d.notes().isEmpty()) w.p("None.");
            for (Note n : d.notes()) {
                if (n.voided()) continue;
                w.li(n.authorName() + " (" + Text.dateTime(n.createdAt()) + ")" + (n.sensitive() ? " [private]" : "") + ": " + n.text());
            }
            w.nl();
        }
        w.h2("Punishments linked to this case");
        if (d.punishments().isEmpty()) w.p("None.");
        for (Punishment p : d.punishments()) {
            w.li("#" + p.id() + " " + p.classification() + " - " + p.type().display() + " [" + p.status() + "]"
                    + (p.type().canBeTimed() ? " " + (p.expiresAt() == null ? "permanent" : Durations.format(p.expiresAt() - p.issuedAt())) : "")
                    + " by " + p.issuerName() + " on " + Text.dateTime(p.issuedAt()) + ": " + p.reason());
        }
        w.nl();
        if (d.custody() != null) {
            var cu = d.custody();
            w.h2("Pretrial custody").kv("Status", cu.status().name()).kv("Requested by", cu.requestedName()).kv("Requested", Text.dateTime(cu.requestedAt()))
                    .kv("Confirmed started", cu.startedAt() == null ? "not confirmed" : Text.dateTime(cu.startedAt()))
                    .kv("Review deadline", cu.deadline() == null ? null : Text.dateTime(cu.deadline())).kv("Extensions", String.valueOf(cu.extensions()))
                    .kv("Ended", cu.endedAt() == null ? null : Text.dateTime(cu.endedAt())).kv("End reason", cu.endReason()).nl();
        }
        if (!d.appeals().isEmpty()) {
            w.h2("Appeals");
            for (var a : d.appeals()) {
                w.li("#" + a.id() + " " + a.status() + " filed by " + a.filedByName() + " (" + Text.dateTime(a.filedAt()) + "): " + a.reason()
                        + (a.decisionReason() == null ? "" : " | Decision by " + a.decidedByName() + ": " + a.decisionReason()));
            }
            w.nl();
        }
        if (rv != null) {
            w.h2("Owner review").kv("Review", rv.ref()).kv("Status", rv.status().display()).kv("Reviewer", rv.reviewerName())
                    .kv("Decision", rv.decisionNote()).nl();
        }
        if (core.settings.exports.includeTimeline()) {
            w.h2("Timeline");
            for (CaseEvent e : d.events()) {
                w.li(Text.dateTime(e.ts()) + " " + e.type() + (e.fromStatus() == null ? "" : " (" + e.fromStatus() + " -> " + e.toStatus() + ")")
                        + (e.actorName() == null ? "" : " by " + e.actorName()) + (e.detail() == null ? "" : ": " + e.detail().replaceAll("<t:(\\d+)(?::[A-Za-z])?>", "(time)")));
            }
            w.nl();
        }
        w.p((w.md ? "_" : "") + "Exported by " + actor.name() + " on " + Text.dateTime(core.clock.now()) + ". IP addresses and private review notes are never included."
                + (w.md ? "_" : ""));
        return w.sb.toString();
    }
}
