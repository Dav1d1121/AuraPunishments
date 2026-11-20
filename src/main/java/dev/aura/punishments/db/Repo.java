package dev.aura.punishments.db;

import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.*;
import dev.aura.punishments.model.Simple.*;
import dev.aura.punishments.db.Sql.RowMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static dev.aura.punishments.db.Sql.*;

/**
 * Data access. Every method takes the caller's {@link Connection}, so services compose several
 * of them inside one transaction (record + audit entry commit or roll back together).
 */
public final class Repo {
    private Repo() {}

    // ------------------------------------------------------------------ profiles
    public static final class Profiles {
        private Profiles() {}

        static final RowMapper<Profile> MAP = rs -> new Profile(uuid(rs, "uuid"), rs.getString("name"),
                rs.getLong("first_seen"), rs.getLong("last_seen"), rs.getString("last_ip"), rs.getInt("level"));

        public static void upsert(Connection c, UUID id, String name, String ip, int level, long now) throws SQLException {
            update(c, """
                    INSERT INTO profiles(uuid,name,name_lower,first_seen,last_seen,last_ip,level) VALUES(?,?,?,?,?,?,?)
                    ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, name_lower=excluded.name_lower,
                      last_seen=excluded.last_seen, last_ip=COALESCE(excluded.last_ip, profiles.last_ip), level=excluded.level
                    """, id, name, name.toLowerCase(Locale.ROOT), now, now, ip, level);
        }

        public static void updateLevel(Connection c, UUID id, int level, long now) throws SQLException {
            update(c, "UPDATE profiles SET level=?, last_seen=? WHERE uuid=?", level, now, id);
        }

        public static Profile get(Connection c, UUID id) throws SQLException {
            return one(c, "SELECT * FROM profiles WHERE uuid=?", MAP, id);
        }

        public static List<Profile> byName(Connection c, String name) throws SQLException {
            return query(c, "SELECT * FROM profiles WHERE name_lower=? ORDER BY last_seen DESC", MAP,
                    name.toLowerCase(Locale.ROOT));
        }

        public static List<Profile> byPrefix(Connection c, String prefix, int limit) throws SQLException {
            return query(c, "SELECT * FROM profiles WHERE name_lower LIKE ? ESCAPE '\\' ORDER BY last_seen DESC LIMIT ?", MAP,
                    prefix.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", limit);
        }
    }

    // ------------------------------------------------------------------ cases
    public static final class Cases {
        private Cases() {}

        static final RowMapper<Case> MAP = rs -> new Case(rs.getLong("id"), CaseStatus.parse(rs.getString("status")),
                CaseKind.valueOf(rs.getString("kind")), uuid(rs, "accused_uuid"), rs.getString("accused_name"),
                rs.getString("offense_key"), Severity.parse(rs.getString("severity")), rs.getString("description"),
                rs.getString("context"), uuid(rs, "prosecutor_uuid"), rs.getString("prosecutor_name"),
                uuid(rs, "judge_uuid"), rs.getString("judge_name"), longOrNull(rs, "trial_at"), rs.getString("hearing_ref"),
                rs.getString("verdict") == null ? null : Verdict.valueOf(rs.getString("verdict")),
                rs.getString("verdict_reason"), rs.getString("verdict_by_name"), longOrNull(rs, "verdict_at"),
                bool(rs, "overturned"), rs.getLong("created_at"), rs.getLong("updated_at"),
                longOrNull(rs, "closed_at"), rs.getLong("version"));

        public static long insert(Connection c, CaseStatus status, CaseKind kind, UUID accused, String accusedName,
                                  String offense, Severity sev, String description, String context,
                                  UUID prosecutor, String prosecutorName, long now) throws SQLException {
            return Sql.insert(c, """
                    INSERT INTO cases(status,kind,accused_uuid,accused_name,offense_key,severity,description,context,
                      prosecutor_uuid,prosecutor_name,created_at,updated_at,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,0)
                    """, status, kind, accused, accusedName, offense, sev, description, context, prosecutor, prosecutorName, now, now);
        }

        public static Case get(Connection c, long id) throws SQLException {
            return one(c, "SELECT * FROM cases WHERE id=?", MAP, id);
        }

        /** Optimistic status change. Returns false when the case changed since the caller read it. */
        public static boolean setStatus(Connection c, long id, long expectedVersion, CaseStatus expectedStatus,
                                        CaseStatus to, long now) throws SQLException {
            return update(c, "UPDATE cases SET status=?, updated_at=?, version=version+1, closed_at=? WHERE id=? AND version=? AND status=?",
                    to, now, (to == CaseStatus.CLOSED ? (Object) now : null), id, expectedVersion, expectedStatus) == 1;
        }

        /** Optimistic field update; {@code setSql} is a fixed fragment chosen by code, never user text. */
        public static boolean updateFields(Connection c, long id, long expectedVersion, String setSql, long now,
                                           Object... args) throws SQLException {
            Object[] all = new Object[args.length + 3];
            System.arraycopy(args, 0, all, 0, args.length);
            all[args.length] = now;
            all[args.length + 1] = id;
            all[args.length + 2] = expectedVersion;
            return update(c, "UPDATE cases SET " + setSql + ", updated_at=?, version=version+1 WHERE id=? AND version=?", all) == 1;
        }

        public static boolean recordVerdict(Connection c, long id, long expectedVersion, CaseStatus from, CaseStatus to,
                                            Verdict v, String reason, String byName, long now) throws SQLException {
            return update(c, """
                    UPDATE cases SET status=?, verdict=?, verdict_reason=?, verdict_by_name=?, verdict_at=?,
                      updated_at=?, version=version+1 WHERE id=? AND version=? AND status=?
                    """, to, v, reason, byName, now, now, id, expectedVersion, from) == 1;
        }

        public static List<Case> forAccused(Connection c, UUID accused, int limit, int offset) throws SQLException {
            return query(c, "SELECT * FROM cases WHERE accused_uuid=? ORDER BY id DESC LIMIT ? OFFSET ?", MAP, accused, limit, offset);
        }

        public static long countForAccused(Connection c, UUID accused) throws SQLException {
            return count(c, "SELECT COUNT(*) FROM cases WHERE accused_uuid=?", accused);
        }

        public static List<Case> byStatuses(Connection c, List<CaseStatus> statuses, int limit, int offset) throws SQLException {
            if (statuses.isEmpty()) return List.of();
            String in = "?,".repeat(statuses.size());
            in = in.substring(0, in.length() - 1);
            Object[] args = new Object[statuses.size() + 2];
            for (int i = 0; i < statuses.size(); i++) args[i] = statuses.get(i);
            args[statuses.size()] = limit;
            args[statuses.size() + 1] = offset;
            return query(c, "SELECT * FROM cases WHERE status IN (" + in + ") ORDER BY id DESC LIMIT ? OFFSET ?", MAP, args);
        }

        public static long countByStatuses(Connection c, List<CaseStatus> statuses) throws SQLException {
            if (statuses.isEmpty()) return 0;
            String in = "?,".repeat(statuses.size());
            in = in.substring(0, in.length() - 1);
            return count(c, "SELECT COUNT(*) FROM cases WHERE status IN (" + in + ")", statuses.toArray());
        }

        public static List<Case> involving(Connection c, UUID staff, int limit, int offset) throws SQLException {
            return query(c, "SELECT * FROM cases WHERE prosecutor_uuid=? OR judge_uuid=? ORDER BY id DESC LIMIT ? OFFSET ?",
                    MAP, staff, staff, limit, offset);
        }

        public static List<Case> all(Connection c, int limit, int offset) throws SQLException {
            return query(c, "SELECT * FROM cases ORDER BY id DESC LIMIT ? OFFSET ?", MAP, limit, offset);
        }

        public static long countAll(Connection c) throws SQLException {
            return count(c, "SELECT COUNT(*) FROM cases");
        }

        /**
         * Prior findings that may influence escalation. Acquittals, dismissals, overturned verdicts and
         * unproven allegations never count. Direct punishments count only when configured.
         */
        public static long priorFindings(Connection c, UUID accused, String offenseKeyOrNull, long excludeCase,
                                         boolean countDirect) throws SQLException {
            StringBuilder sql = new StringBuilder("""
                    SELECT COUNT(*) FROM cases WHERE accused_uuid=? AND id<>? AND overturned=0 AND (
                      (verdict='GUILTY' AND status IN ('GUILTY','AWAITING_SENTENCING','SENTENCED','APPEALED','UNDER_REVIEW','CLOSED'))
                    """);
            if (countDirect) sql.append(" OR (kind='DIRECT' AND status IN ('DIRECT_PUNISHMENT_ISSUED','APPEALED','UNDER_REVIEW','CLOSED'))");
            sql.append(")");
            if (offenseKeyOrNull != null) {
                sql.append(" AND offense_key=?");
                return count(c, sql.toString(), accused, excludeCase, offenseKeyOrNull);
            }
            return count(c, sql.toString(), accused, excludeCase);
        }

        public static long countOpenForAccused(Connection c, UUID accused) throws SQLException {
            return count(c, """
                    SELECT COUNT(*) FROM cases WHERE accused_uuid=? AND status IN
                    ('OPEN','UNDER_INVESTIGATION','AWAITING_TRIAL','IN_TRIAL','AWAITING_VERDICT','GUILTY','AWAITING_SENTENCING','UNDER_REVIEW','APPEALED')
                    """, accused);
        }

        public static boolean markOverturned(Connection c, long id, long now) throws SQLException {
            return update(c, "UPDATE cases SET overturned=1, updated_at=?, version=version+1 WHERE id=?", now, id) == 1;
        }
    }

    public static final class Events {
        private Events() {}

        static final RowMapper<CaseEvent> MAP = rs -> new CaseEvent(rs.getLong("id"), rs.getLong("case_id"), rs.getLong("ts"),
                rs.getString("actor_name"), rs.getString("type"), rs.getString("from_status"),
                rs.getString("to_status"), rs.getString("detail"));

        public static void add(Connection c, long caseId, long ts, UUID actor, String actorName, String type,
                               CaseStatus from, CaseStatus to, String detail) throws SQLException {
            update(c, "INSERT INTO case_events(case_id,ts,actor_uuid,actor_name,type,from_status,to_status,detail) VALUES(?,?,?,?,?,?,?,?)",
                    caseId, ts, actor, actorName, type, from, to, detail);
        }

        public static List<CaseEvent> forCase(Connection c, long caseId) throws SQLException {
            return query(c, "SELECT * FROM case_events WHERE case_id=? ORDER BY id ASC", MAP, caseId);
        }
    }

    // ------------------------------------------------------------------ evidence & notes
    public static final class Evidences {
        private Evidences() {}

        static final RowMapper<Evidence> MAP = rs -> new Evidence(rs.getLong("id"), rs.getLong("case_id"),
                EvidenceType.valueOf(rs.getString("type")), rs.getString("reference"), rs.getString("description"),
                uuid(rs, "submitted_by"), rs.getString("submitted_name"), rs.getLong("submitted_at"),
                bool(rs, "voided"), rs.getString("void_reason"), rs.getString("voided_name"),
                longOrNull(rs, "voided_at"), longOrNull(rs, "corrects_id"));

        public static long add(Connection c, long caseId, EvidenceType type, String ref, String desc, UUID by,
                               String byName, long now, Long corrects) throws SQLException {
            return Sql.insert(c, """
                    INSERT INTO evidence(case_id,type,reference,description,submitted_by,submitted_name,submitted_at,corrects_id)
                    VALUES(?,?,?,?,?,?,?,?)""", caseId, type, ref, desc, by, byName, now, corrects);
        }

        public static Evidence get(Connection c, long id) throws SQLException {
            return one(c, "SELECT * FROM evidence WHERE id=?", MAP, id);
        }

        public static List<Evidence> forCase(Connection c, long caseId, boolean includeVoided) throws SQLException {
            return query(c, "SELECT * FROM evidence WHERE case_id=?" + (includeVoided ? "" : " AND voided=0") + " ORDER BY id ASC",
                    MAP, caseId);
        }

        public static long countActive(Connection c, long caseId) throws SQLException {
            return count(c, "SELECT COUNT(*) FROM evidence WHERE case_id=? AND voided=0", caseId);
        }

        /** Evidence is never deleted; a correction voids it with a recorded reason. */
        public static boolean voidIt(Connection c, long id, UUID by, String byName, String reason, long now) throws SQLException {
            return update(c, "UPDATE evidence SET voided=1, void_reason=?, voided_by=?, voided_name=?, voided_at=? WHERE id=? AND voided=0",
                    reason, by, byName, now, id) == 1;
        }
    }

    public static final class Notes {
        private Notes() {}

        static final RowMapper<Note> MAP = rs -> new Note(rs.getLong("id"), longOrNull(rs, "case_id"),
                uuid(rs, "profile_uuid"), uuid(rs, "author_uuid"), rs.getString("author_name"), rs.getString("text"),
                rs.getLong("created_at"), bool(rs, "sensitive"), bool(rs, "voided"), rs.getString("void_reason"));

        public static long add(Connection c, Long caseId, UUID profile, UUID author, String authorName, String text,
                               boolean sensitive, long now) throws SQLException {
            return Sql.insert(c, "INSERT INTO notes(case_id,profile_uuid,author_uuid,author_name,text,created_at,sensitive) VALUES(?,?,?,?,?,?,?)",
                    caseId, profile, author, authorName, text, now, sensitive);
        }

        public static Note get(Connection c, long id) throws SQLException {
            return one(c, "SELECT * FROM notes WHERE id=?", MAP, id);
        }

        public static List<Note> forCase(Connection c, long caseId) throws SQLException {
            return query(c, "SELECT * FROM notes WHERE case_id=? ORDER BY id ASC", MAP, caseId);
        }

        public static List<Note> forProfile(Connection c, UUID profile) throws SQLException {
            return query(c, "SELECT * FROM notes WHERE profile_uuid=? ORDER BY id DESC LIMIT 200", MAP, profile);
        }

        public static boolean voidIt(Connection c, long id, String byName, String reason, long now) throws SQLException {
            return update(c, "UPDATE notes SET voided=1, void_reason=?, voided_name=?, voided_at=? WHERE id=? AND voided=0",
                    reason, byName, now, id) == 1;
        }
    }

    // ------------------------------------------------------------------ custody
    public static final class Custodies {
        private Custodies() {}

        static final RowMapper<Custody> MAP = rs -> new Custody(rs.getLong("id"), rs.getLong("case_id"), uuid(rs, "uuid"),
                rs.getString("name"), CustodyStatus.valueOf(rs.getString("status")), rs.getString("requested_name"),
                rs.getLong("requested_at"), longOrNull(rs, "started_at"), longOrNull(rs, "deadline"),
                rs.getInt("extensions"), rs.getString("jail_name"), rs.getString("jail_prisoner_id"),
                longOrNull(rs, "ended_at"), rs.getString("ended_by"), rs.getString("end_reason"),
                rs.getString("alerts_sent"), rs.getString("last_error"), rs.getLong("updated_at"));

        public static long insert(Connection c, long caseId, UUID uuid, String name, UUID by, String byName, long now) throws SQLException {
            return Sql.insert(c, """
                    INSERT INTO custody(case_id,uuid,name,status,requested_by,requested_name,requested_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?)""", caseId, uuid, name, CustodyStatus.REQUESTED, by, byName, now, now);
        }

        public static Custody get(Connection c, long id) throws SQLException {
            return one(c, "SELECT * FROM custody WHERE id=?", MAP, id);
        }

        public static List<Custody> forCase(Connection c, long caseId) throws SQLException {
            return query(c, "SELECT * FROM custody WHERE case_id=? ORDER BY id DESC", MAP, caseId);
        }

        /** The one live custody of a case (REQUESTED / ACTIVE / RELEASING), if any. */
        public static Custody liveForCase(Connection c, long caseId) throws SQLException {
            return one(c, "SELECT * FROM custody WHERE case_id=? AND status IN ('REQUESTED','ACTIVE','RELEASING','SERVING') ORDER BY id DESC LIMIT 1",
                    MAP, caseId);
        }

        public static Custody liveForPlayer(Connection c, UUID uuid) throws SQLException {
            return one(c, "SELECT * FROM custody WHERE uuid=? AND status IN ('REQUESTED','ACTIVE','RELEASING','SERVING') ORDER BY id DESC LIMIT 1",
                    MAP, uuid);
        }

        public static List<Custody> allLive(Connection c) throws SQLException {
            return query(c, "SELECT * FROM custody WHERE status IN ('REQUESTED','ACTIVE','RELEASING','SERVING') ORDER BY id ASC", MAP);
        }

        public static void markActive(Connection c, long id, long start, long deadline, String jailName,
                                      String jailPrisonerId, long now) throws SQLException {
            update(c, "UPDATE custody SET status='ACTIVE', started_at=?, deadline=?, jail_name=?, jail_prisoner_id=?, last_error=NULL, updated_at=? WHERE id=?",
                    start, deadline, jailName, jailPrisonerId, now, id);
        }

        /** The held record now backs a final jail sentence (no review deadline). */
        public static void markServing(Connection c, long id, String jailName, String jailPrisonerId, long now, Long startedAt) throws SQLException {
            update(c, "UPDATE custody SET status='SERVING', deadline=NULL, jail_name=COALESCE(?,jail_name), jail_prisoner_id=COALESCE(?,jail_prisoner_id), started_at=COALESCE(started_at,?), last_error=NULL, updated_at=? WHERE id=?",
                    jailName, jailPrisonerId, startedAt == null ? now : startedAt, now, id);
        }

        public static void setStatus(Connection c, long id, CustodyStatus s, String error, long now) throws SQLException {
            update(c, "UPDATE custody SET status=?, last_error=?, updated_at=? WHERE id=?", s, error, now, id);
        }

        public static void end(Connection c, long id, CustodyStatus s, String by, String reason, long now) throws SQLException {
            update(c, "UPDATE custody SET status=?, ended_at=?, ended_by=?, end_reason=?, updated_at=? WHERE id=?",
                    s, now, by, reason, now, id);
        }

        public static void extend(Connection c, long id, long newDeadline, long now) throws SQLException {
            update(c, "UPDATE custody SET deadline=?, extensions=extensions+1, alerts_sent='', updated_at=? WHERE id=?",
                    newDeadline, now, id);
        }

        public static void setAlerts(Connection c, long id, String alerts, long now) throws SQLException {
            update(c, "UPDATE custody SET alerts_sent=?, updated_at=? WHERE id=?", alerts, now, id);
        }
    }

    // ------------------------------------------------------------------ punishments
    public static final class Punishments {
        private Punishments() {}

        static final RowMapper<Punishment> MAP = rs -> new Punishment(rs.getLong("id"), PunishType.valueOf(rs.getString("type")),
                PunishStatus.valueOf(rs.getString("status")), uuid(rs, "target_uuid"), rs.getString("target_name"),
                rs.getString("ip"), rs.getString("reason"), uuid(rs, "issuer_uuid"), rs.getString("issuer_name"),
                rs.getLong("issued_at"), longOrNull(rs, "expires_at"), longOrNull(rs, "ended_at"), rs.getString("ended_by"),
                rs.getString("end_reason"), longOrNull(rs, "case_id"), Source.valueOf(rs.getString("source")),
                rs.getString("offense_key"), bool(rs, "delivered"), longOrNull(rs, "review_deadline"),
                bool(rs, "reviewed"), longOrNull(rs, "custody_id"));

        public static long insert(Connection c, PunishType type, PunishStatus status, UUID target, String targetName, String ip,
                                  String reason, UUID issuer, String issuerName, long now, Long expiresAt, Long caseId,
                                  Source source, String offense, boolean delivered, Long reviewDeadline,
                                  Long custodyId) throws SQLException {
            return Sql.insert(c, """
                    INSERT INTO punishments(type,status,target_uuid,target_name,ip,reason,issuer_uuid,issuer_name,issued_at,
                      expires_at,case_id,source,offense_key,delivered,review_deadline,custody_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    type, status, target, targetName, ip, reason, issuer, issuerName, now, expiresAt, caseId, source, offense,
                    delivered, reviewDeadline, custodyId);
        }

        public static Punishment get(Connection c, long id) throws SQLException {
            return one(c, "SELECT * FROM punishments WHERE id=?", MAP, id);
        }

        public static List<Punishment> active(Connection c) throws SQLException {
            return query(c, "SELECT * FROM punishments WHERE status='ACTIVE' ORDER BY id ASC", MAP);
        }

        public static List<Punishment> activeFor(Connection c, UUID target) throws SQLException {
            return query(c, "SELECT * FROM punishments WHERE target_uuid=? AND status='ACTIVE' ORDER BY id DESC", MAP, target);
        }

        public static List<Punishment> forTarget(Connection c, UUID target, int limit, int offset) throws SQLException {
            return query(c, "SELECT * FROM punishments WHERE target_uuid=? ORDER BY id DESC LIMIT ? OFFSET ?", MAP, target, limit, offset);
        }

        public static long countForTarget(Connection c, UUID target) throws SQLException {
            return count(c, "SELECT COUNT(*) FROM punishments WHERE target_uuid=?", target);
        }

        public static List<Punishment> forCase(Connection c, long caseId) throws SQLException {
            return query(c, "SELECT * FROM punishments WHERE case_id=? ORDER BY id ASC", MAP, caseId);
        }

        public static List<Punishment> dueForExpiry(Connection c, long now) throws SQLException {
            return query(c, "SELECT * FROM punishments WHERE status='ACTIVE' AND expires_at IS NOT NULL AND expires_at<=?", MAP, now);
        }

        public static List<Punishment> undeliveredWarnings(Connection c, UUID target) throws SQLException {
            return query(c, "SELECT * FROM punishments WHERE target_uuid=? AND type='WARN' AND delivered=0 AND status<>'REVOKED' ORDER BY id ASC", MAP, target);
        }

        public static void markDelivered(Connection c, long id) throws SQLException {
            update(c, "UPDATE punishments SET delivered=1 WHERE id=?", id);
        }

        public static List<Punishment> emergencyAwaitingReview(Connection c) throws SQLException {
            return query(c, "SELECT * FROM punishments WHERE source='EMERGENCY' AND reviewed=0 AND status='ACTIVE' ORDER BY id ASC", MAP);
        }

        public static boolean end(Connection c, long id, PunishStatus to, String by, String reason, long now) throws SQLException {
            return update(c, "UPDATE punishments SET status=?, ended_at=?, ended_by=?, end_reason=? WHERE id=? AND status IN ('ACTIVE','COMPLETED')",
                    to, now, by, reason, id) == 1;
        }

        public static List<Punishment> byCustody(Connection c, long custodyId) throws SQLException {
            return query(c, "SELECT * FROM punishments WHERE custody_id=? ORDER BY id ASC", MAP, custodyId);
        }

        /** A jail sentence ended in JailReloaded: mark the matching ACTIVE jail punishment served. */
        public static int serveByCustody(Connection c, long custodyId, long now) throws SQLException {
            return update(c, "UPDATE punishments SET status='SERVED', ended_at=?, ended_by='System', end_reason='Sentence served' WHERE custody_id=? AND type='JAIL' AND status='ACTIVE'",
                    now, custodyId);
        }

        public static void markReviewed(Connection c, long id) throws SQLException {
            update(c, "UPDATE punishments SET reviewed=1 WHERE id=?", id);
        }
    }

    public static final class Directs {
        private Directs() {}

        public static void add(Connection c, long caseId, Long punishmentId, String offense, String justification,
                               String actorName, long now) throws SQLException {
            update(c, "INSERT INTO direct_actions(case_id,punishment_id,offense_key,justification,actor_name,created_at) VALUES(?,?,?,?,?,?)",
                    caseId, punishmentId, offense, justification, actorName, now);
        }

        public static String justification(Connection c, long caseId) throws SQLException {
            return one(c, "SELECT justification FROM direct_actions WHERE case_id=? ORDER BY id DESC LIMIT 1", rs -> rs.getString(1), caseId);
        }
    }

    public static final class Appeals {
        private Appeals() {}

        static final RowMapper<Appeal> MAP = rs -> new Appeal(rs.getLong("id"), rs.getLong("case_id"),
                rs.getString("filed_by_name"), rs.getLong("filed_at"), rs.getString("reason"),
                AppealStatus.valueOf(rs.getString("status")), rs.getString("decided_by_name"),
                rs.getString("decision_reason"), longOrNull(rs, "decided_at"));

        public static long add(Connection c, long caseId, String by, String reason, long now) throws SQLException {
            return Sql.insert(c, "INSERT INTO appeals(case_id,filed_by_name,filed_at,reason,status) VALUES(?,?,?,?,'PENDING')",
                    caseId, by, now, reason);
        }

        public static List<Appeal> forCase(Connection c, long caseId) throws SQLException {
            return query(c, "SELECT * FROM appeals WHERE case_id=? ORDER BY id ASC", MAP, caseId);
        }

        public static Appeal pendingForCase(Connection c, long caseId) throws SQLException {
            return one(c, "SELECT * FROM appeals WHERE case_id=? AND status='PENDING' ORDER BY id DESC LIMIT 1", MAP, caseId);
        }

        public static void decide(Connection c, long id, AppealStatus s, String by, String reason, long now) throws SQLException {
            update(c, "UPDATE appeals SET status=?, decided_by_name=?, decision_reason=?, decided_at=? WHERE id=?",
                    s, by, reason, now, id);
        }

        public static long countPending(Connection c) throws SQLException {
            return count(c, "SELECT COUNT(*) FROM appeals WHERE status='PENDING'");
        }
    }

    // ------------------------------------------------------------------ pending operations
    public static final class Ops {
        private Ops() {}

        static final RowMapper<PendingOp> MAP = rs -> new PendingOp(rs.getLong("id"), rs.getString("kind"),
                OpState.valueOf(rs.getString("state")), longOrNull(rs, "case_id"), longOrNull(rs, "custody_id"),
                uuid(rs, "uuid"), rs.getString("payload"), rs.getInt("attempts"), rs.getLong("created_at"),
                rs.getLong("updated_at"), rs.getString("last_error"));

        public static long add(Connection c, String kind, Long caseId, Long custodyId, UUID uuid, String payload, long now) throws SQLException {
            return Sql.insert(c, "INSERT INTO pending_ops(kind,state,case_id,custody_id,uuid,payload,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                    kind, OpState.PENDING, caseId, custodyId, uuid, payload, now, now);
        }

        public static void setState(Connection c, long id, OpState s, String error, long now) throws SQLException {
            update(c, "UPDATE pending_ops SET state=?, last_error=?, attempts=attempts+1, updated_at=? WHERE id=?", s, error, now, id);
        }

        public static List<PendingOp> unfinished(Connection c) throws SQLException {
            return query(c, "SELECT * FROM pending_ops WHERE state IN ('PENDING','EXTERNAL_DONE') ORDER BY id ASC", MAP);
        }

        public static long countUnfinished(Connection c) throws SQLException {
            return count(c, "SELECT COUNT(*) FROM pending_ops WHERE state IN ('PENDING','EXTERNAL_DONE')");
        }

        public static long countFailed(Connection c) throws SQLException {
            return count(c, "SELECT COUNT(*) FROM pending_ops WHERE state='FAILED'");
        }
    }

    // ------------------------------------------------------------------ audit (hash-chained, append-only)
    public static final class Audits {
        private Audits() {}

        public static final String GENESIS = "GENESIS";

        static final RowMapper<Audit> MAP = rs -> new Audit(rs.getLong("id"), rs.getLong("ts"), uuid(rs, "actor_uuid"),
                rs.getString("actor_name"), rs.getString("action"), uuid(rs, "target_uuid"), longOrNull(rs, "case_id"),
                rs.getString("detail"), bool(rs, "elevated"), rs.getString("prev_hash"), rs.getString("hash"));

        static String digest(String prev, long ts, UUID actor, String actorName, String action, UUID target, Long caseId,
                             String detail, boolean elevated) {
            String joined = prev + "|" + ts + "|" + actor + "|" + actorName + "|" + action + "|" + target + "|" + caseId
                    + "|" + detail + "|" + elevated;
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(md.digest(joined.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        private static String lastHash(Connection c) throws SQLException {
            String h = one(c, "SELECT hash FROM audit_log ORDER BY id DESC LIMIT 1", rs -> rs.getString(1));
            if (h != null) return h;
            String a = one(c, "SELECT hash FROM audit_anchor WHERE id=1", rs -> rs.getString(1));
            return a == null ? GENESIS : a;
        }

        /** Append inside the caller's transaction, so the record and its audit entry are atomic. */
        public static void append(Connection c, long ts, UUID actor, String actorName, String action, UUID target,
                                  Long caseId, String detail, boolean elevated) throws SQLException {
            String prev = lastHash(c);
            String hash = digest(prev, ts, actor, actorName, action, target, caseId, detail, elevated);
            update(c, "INSERT INTO audit_log(ts,actor_uuid,actor_name,action,target_uuid,case_id,detail,elevated,prev_hash,hash) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    ts, actor, actorName, action, target, caseId, detail, elevated, prev, hash);
        }

        public static List<Audit> recent(Connection c, int limit, int offset) throws SQLException {
            return query(c, "SELECT * FROM audit_log ORDER BY id DESC LIMIT ? OFFSET ?", MAP, limit, offset);
        }

        public static List<Audit> forCase(Connection c, long caseId, int limit) throws SQLException {
            return query(c, "SELECT * FROM audit_log WHERE case_id=? ORDER BY id DESC LIMIT ?", MAP, caseId, limit);
        }

        public static long count(Connection c) throws SQLException {
            return Sql.count(c, "SELECT COUNT(*) FROM audit_log");
        }

        /** @return -1 when the chain is intact, otherwise the id of the first damaged entry. */
        public static long verify(Connection c) throws SQLException {
            String anchorHash = one(c, "SELECT hash FROM audit_anchor WHERE id=1", rs -> rs.getString(1));
            String prev = anchorHash == null ? GENESIS : anchorHash;
            List<Audit> all = query(c, "SELECT * FROM audit_log ORDER BY id ASC", MAP);
            for (Audit a : all) {
                if (!a.prevHash().equals(prev)) return a.id();
                String expect = digest(a.prevHash(), a.ts(), a.actorUuid(), a.actorName(), a.action(), a.targetUuid(),
                        a.caseId(), a.detail(), a.elevated());
                if (!expect.equals(a.hash())) return a.id();
                prev = a.hash();
            }
            return -1;
        }

        /** Delete entries older than the cutoff, re-anchoring the chain so verification still works. */
        public static int prune(Connection c, long olderThan) throws SQLException {
            Audit lastOld = one(c, "SELECT * FROM audit_log WHERE ts<? ORDER BY id DESC LIMIT 1", MAP, olderThan);
            if (lastOld == null) return 0;
            update(c, "INSERT INTO audit_anchor(id,hash,seq) VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET hash=excluded.hash, seq=excluded.seq",
                    lastOld.hash(), lastOld.id());
            return update(c, "DELETE FROM audit_log WHERE id<=?", lastOld.id());
        }
    }
}
