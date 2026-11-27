package dev.aura.punishments.service;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Records.Custody;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Records.Review;
import dev.aura.punishments.model.Simple.CustodyStatus;
import dev.aura.punishments.model.Simple.ReviewStatus;
import dev.aura.punishments.util.Result;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The judicial work queue: one prioritised list of everything that is waiting for a human, plus the counts shown on the
 * dashboard. The sort order comes from {@code dashboard.priority.*}; items are only included when the viewer holds the
 * permission needed to open them, so the queue never reveals records a viewer could not otherwise see.
 */
public final class WorkQueueService extends Svc {
    public enum Kind {
        CUSTODY_OVERDUE("custody-overdue", "Custody review OVERDUE"),
        CUSTODY_IMMINENT("custody-imminent", "Custody review due soon"),
        EMERGENCY_OVERDUE("emergency-overdue", "Emergency review OVERDUE"),
        EMERGENCY_PENDING("emergency-pending", "Emergency action awaiting review"),
        QUICK_REVIEW_OVERDUE("quick-review-overdue", "Quick-punishment review OVERDUE"),
        QUICK_REVIEW_PENDING("quick-review-pending", "Quick punishment awaiting review"),
        APPEAL("appeal", "Appeal pending"),
        AWAITING_SENTENCING("awaiting-sentencing", "Awaiting sentencing"),
        AWAITING_VERDICT("awaiting-verdict", "Awaiting verdict"),
        FURTHER_INVESTIGATION("further-investigation", "Further investigation required"),
        AWAITING_TRIAL("awaiting-trial", "Awaiting trial");

        public final String key;
        public final String label;

        Kind(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    public record Item(Kind kind, int priority, String player, String label, Long caseId, Long reviewId, Long punishmentId, Long dueAt, boolean overdue) {}

    public record Queue(Map<Kind, Long> counts, List<Item> items, boolean truncated) {
        public long count(Kind k) {
            return counts.getOrDefault(k, 0L);
        }
    }

    private static final int MAX_ITEMS = 200;

    public WorkQueueService(Core core) {
        super(core);
    }

    public CompletableFuture<Result<Queue>> queue(Actor actor) {
        Result<Void> p = core.auth.requireAny(actor, "view the work queue", Perms.DASHBOARD, Perms.JUDICIAL_VIEW, Perms.ADMIN, Perms.REVIEW_VIEW);
        if (!p.isOk()) return done(p.cast());
        // judicial work (cases, custody, trials) is only listed while the judicial system is on or still has open items
        boolean judicial = core.mode.judicialVisible();
        boolean seeCases = judicial && (core.auth.has(actor, Perms.JUDICIAL_VIEW) || core.auth.has(actor, Perms.CASE_VIEW_ALL) || core.auth.has(actor, Perms.ADMIN));
        boolean seeCustody = judicial && (seeCases || core.auth.has(actor, Perms.CUSTODY_VIEW));
        boolean seeAppeals = core.auth.has(actor, Perms.APPEAL_DECIDE) || core.auth.has(actor, Perms.JUDICIAL_OVERTURN) || core.auth.has(actor, Perms.ADMIN);
        boolean seeReviews = core.auth.has(actor, Perms.REVIEW_VIEW);
        boolean seeEmergency = core.auth.has(actor, Perms.EMERGENCY_REVIEW);
        return db(c -> {
            long now = core.clock.now();
            var cfg = core.settings.dashboard;
            Map<Kind, Long> counts = new LinkedHashMap<>();
            for (Kind k : Kind.values()) counts.put(k, 0L);
            List<Item> items = new ArrayList<>();

            if (seeCustody) {
                for (Custody cu : Repo.Custodies.allLive(c)) {
                    if (cu.status() != CustodyStatus.ACTIVE || cu.deadline() == null) continue;
                    boolean overdue = now > cu.deadline();
                    boolean soon = !overdue && cu.deadline() - now <= cfg.approachingCustody();
                    if (!overdue && !soon) continue;
                    Kind k = overdue ? Kind.CUSTODY_OVERDUE : Kind.CUSTODY_IMMINENT;
                    counts.merge(k, 1L, Long::sum);
                    items.add(new Item(k, cfg.priority().get(k.key), cu.name(), k.label, cu.caseId(), null, null, cu.deadline(), overdue));
                }
            }
            if (seeEmergency) {
                for (Punishment pu : Repo.Punishments.emergencyAwaitingReview(c)) {
                    boolean overdue = pu.reviewDeadline() != null && now > pu.reviewDeadline();
                    Kind k = overdue ? Kind.EMERGENCY_OVERDUE : Kind.EMERGENCY_PENDING;
                    counts.merge(k, 1L, Long::sum);
                    items.add(new Item(k, cfg.priority().get(k.key), pu.targetName(), k.label, pu.caseId(), null, pu.id(), pu.reviewDeadline(), overdue));
                }
            }
            if (seeReviews) {
                for (Review r : Repo.Reviews.openReviews(c)) {
                    if (r.migrated()) continue;
                    Kind k;
                    boolean overdue = r.reviewDue() != null && now > r.reviewDue();
                    if (r.status() == ReviewStatus.FURTHER_INVESTIGATION) k = Kind.FURTHER_INVESTIGATION;
                    else k = overdue ? Kind.QUICK_REVIEW_OVERDUE : Kind.QUICK_REVIEW_PENDING;
                    counts.merge(k, 1L, Long::sum);
                    items.add(new Item(k, cfg.priority().get(k.key), r.targetName(), r.ref() + " " + r.ptype().display() + " - " + k.label, r.caseId(), r.id(), r.punishmentId(),
                            r.reviewDue(), overdue));
                }
            }
            if (seeAppeals) {
                for (var a : Repo.Appeals.openForPunishments(c, 60, 0)) {
                    Punishment pu = Repo.Punishments.get(c, a.punishmentId());
                    if (pu == null) continue;
                    counts.merge(Kind.APPEAL, 1L, Long::sum);
                    items.add(new Item(Kind.APPEAL, cfg.priority().get("appeal"), pu.targetName(), "Appeal #" + a.id() + " against " + pu.type().display() + " #" + pu.id()
                            + (a.ticket() == null ? "" : " (ticket " + a.ticket() + ")"), null, null, pu.id(), null, false));
                }
            }
            if (seeCases) {
                counts.merge(Kind.APPEAL, Repo.Appeals.countPendingForCases(c), Long::sum);
                addCases(c, items, Kind.APPEAL, List.of(CaseStatus.APPEALED), cfg.priority().get("appeal"), counts, false);
                addCases(c, items, Kind.AWAITING_SENTENCING, List.of(CaseStatus.GUILTY, CaseStatus.AWAITING_SENTENCING), cfg.priority().get("awaiting-sentencing"), counts, true);
                addCases(c, items, Kind.AWAITING_VERDICT, List.of(CaseStatus.IN_TRIAL, CaseStatus.AWAITING_VERDICT), cfg.priority().get("awaiting-verdict"), counts, true);
                addCases(c, items, Kind.AWAITING_TRIAL, List.of(CaseStatus.AWAITING_TRIAL), cfg.priority().get("awaiting-trial"), counts, true);
            }
            items.sort(Comparator.comparingInt(Item::priority).reversed()
                    .thenComparing(i -> i.dueAt() == null ? Long.MAX_VALUE : i.dueAt()));
            boolean truncated = items.size() > MAX_ITEMS;
            return Result.ok(new Queue(counts, truncated ? List.copyOf(items.subList(0, MAX_ITEMS)) : List.copyOf(items), truncated));
        });
    }

    private void addCases(java.sql.Connection c, List<Item> items, Kind kind, List<CaseStatus> statuses, int priority, Map<Kind, Long> counts,
                          boolean count) throws java.sql.SQLException {
        if (count) counts.put(kind, Repo.Cases.countByStatuses(c, statuses));
        for (Case cs : Repo.Cases.byStatuses(c, statuses, 60, 0)) {
            items.add(new Item(kind, priority, cs.accusedName(), cs.ref() + " " + cs.status().display() + " - " + kind.label, cs.id(), null, null,
                    cs.trialAt(), false));
        }
    }
}
