package dev.aura.punishments.db;

import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.*;
import dev.aura.punishments.model.Simple.*;
import dev.aura.punishments.db.Sql.RowMapper;
import dev.aura.punishments.db.Dialect;

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
            update(c, Dialect.of(c).upsert("profiles(uuid,name,name_lower,first_seen,last_seen,last_ip,level) VALUES(?,?,?,?,?,?,?)",
                    List.of("uuid"), List.of("name={new:name}", "name_lower={new:name_lower}", "last_seen={new:last_seen}",
                            "last_ip=COALESCE({new:last_ip}, profiles.last_ip)", "level={new:level}")),
                    id, name, name.toLowerCase(Locale.ROOT), now, now, ip, level);
        }

        /**
         * Serialise everything that decides "may this player get another punishment" across servers: make sure the player has a row and
         * lock it until the transaction ends. A single server needs nothing (its own thread already takes turns).
         */
        public static void lockPlayer(Connection c, UUID id, String name, long now) throws SQLException {
            Dialect d = Dialect.of(c);
            if (!d.shared()) return;
            update(c, d.insertIgnore("profiles(uuid,name,name_lower,first_seen,last_seen,level) VALUES(?,?,?,?,?,0)"), id, name, name.toLowerCase(Locale.ROOT), now, now);
            one(c, "SELECT uuid FROM profiles WHERE uuid=? FOR UPDATE", rs -> rs.getString(1), id);
        }

        /** How many different accounts last connected from this address since {@code since}. */
        public static long countByIp(Connection c, String ip, long since) throws SQLException {
            return count(c, "SELECT COUNT(*) FROM profiles WHERE last_ip=? AND last_seen>?", ip, since);
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
                longOrNull(rs, "closed_at"), rs.getLong("version"), rs.getString("appeal_ticket"),
                rs.getString("uid"), rs.getString("origin"));

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

        /** Emergency cases (1.2.x) that are still being worked. */
        public static long countOpenEmergency(Connection c) throws SQLException {
            return count(c, "SELECT COUNT(*) FROM cases WHERE kind='EMERGENCY' AND status NOT IN ('CLOSED','DISMISSED','NOT_GUILTY','SENTENCED','DIRECT_PUNISHMENT_ISSUED')");
        }

        public static long countOpenForAccused(Connection c, UUID accused) throws SQLException {
            return count(c, """
                    SELECT COUNT(*) FROM cases WHERE accused_uuid=? AND status IN
                    ('OPEN','UNDER_INVESTIGATION','AWAITING_TRIAL','IN_TRIAL','AWAITING_VERDICT','GUILTY','AWAITING_SENTENCING','UNDER_REVIEW','APPEALED')
                    """, accused);
        }

        public static boolean setAppealTicket(Connection c, long id, String ticket, long now) throws SQLException {
            return update(c, "UPDATE cases SET appeal_ticket=?, updated_at=?, version=version+1 WHERE id=?", ticket, now, id) == 1;
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

        static final RowMapper<Evidence> MAP = rs -> new Evidence(rs.getLong("id"), longOrNull(rs, "case_id"), longOrNull(rs, "punishment_id"),
                EvidenceType.valueOf(rs.getString("type")), rs.getString("reference"), rs.getString("description"),
                uuid(rs, "submitted_by"), rs.getString("submitted_name"), rs.getLong("submitted_at"),
                bool(rs, "voided"), rs.getString("void_reason"), rs.getString("voided_name"),
                longOrNull(rs, "voided_at"), longOrNull(rs, "corrects_id"), rs.getString("uid"), rs.getString("origin"));

        public static long add(Connection c, long caseId, EvidenceType type, String ref, String desc, UUID by,
                               String byName, long now, Long corrects) throws SQLException {
            return Sql.insert(c, """
                    INSERT INTO evidence(case_id,type,reference,description,submitted_by,submitted_name,submitted_at,corrects_id)
                    VALUES(?,?,?,?,?,?,?,?)""", caseId, type, ref, desc, by, byName, now, corrects);
        }

        /** Evidence attached straight to a punishment (the judicial system is off, or the punishment has no case). */
        public static long addToPunishment(Connection c, long punishmentId, EvidenceType type, String ref, String desc, UUID by,
                                           String byName, long now, Long corrects) throws SQLException {
            return Sql.insert(c, """
                    INSERT INTO evidence(punishment_id,type,reference,description,submitted_by,submitted_name,submitted_at,corrects_id)
                    VALUES(?,?,?,?,?,?,?,?)""", punishmentId, type, ref, desc, by, byName, now, corrects);
        }

        public static List<Evidence> forPunishment(Connection c, long punishmentId, boolean includeVoided) throws SQLException {
            return query(c, "SELECT * FROM evidence WHERE punishment_id=?" + (includeVoided ? "" : " AND voided=0") + " ORDER BY id ASC",
                    MAP, punishmentId);
        }

        public static long countActiveForPunishment(Connection c, long punishmentId) throws SQLException {
            return count(c, "SELECT COUNT(*) FROM evidence WHERE punishment_id=? AND voided=0", punishmentId);
        }

        /** Move punishment-level evidence onto a case (when a punishment is later brought under a case). */
        public static int moveToCase(Connection c, long punishmentId, long caseId) throws SQLException {
            return update(c, "UPDATE evidence SET case_id=?, punishment_id=NULL WHERE punishment_id=?", caseId, punishmentId);
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
                rs.getLong("created_at"), bool(rs, "sensitive"), bool(rs, "voided"), rs.getString("void_reason"),
                longOrNull(rs, "punishment_id"));

        public static long add(Connection c, Long caseId, UUID profile, UUID author, String authorName, String text,
                               boolean sensitive, long now) throws SQLException {
            return Sql.insert(c, "INSERT INTO notes(case_id,profile_uuid,author_uuid,author_name,text,created_at,sensitive) VALUES(?,?,?,?,?,?,?)",
                    caseId, profile, author, authorName, text, now, sensitive);
        }

        /** A staff note about one punishment (also kept on the player's profile for the history view). */
        public static long addForPunishment(Connection c, long punishmentId, UUID profile, UUID author, String authorName, String text,
                                            boolean sensitive, long now) throws SQLException {
            return Sql.insert(c, "INSERT INTO notes(punishment_id,profile_uuid,author_uuid,author_name,text,created_at,sensitive) VALUES(?,?,?,?,?,?,?)",
                    punishmentId, profile, author, authorName, text, now, sensitive);
        }

        public static List<Note> forPunishment(Connection c, long punishmentId) throws SQLException {
            return query(c, "SELECT * FROM notes WHERE punishment_id=? ORDER BY id ASC", MAP, punishmentId);
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
                bool(rs, "reviewed"), longOrNull(rs, "custody_id"), longOrNull(rs, "modified_at"), rs.getString("modified_by"),
                longOrNull(rs, "original_expires_at"), rs.getString("uid"), rs.getString("origin"), rs.getString("op_id"),
                rs.getString("jail_prisoner_id"), rs.getString("jail_name"), rs.getString("jail_state"), rs.getString("jail_error"), rs.getString("jail_origin"));

        public static long insert(Connection c, PunishType type, PunishStatus status, UUID target, String targetName, String ip,
                                  String reason, UUID issuer, String issuerName, long now, Long expiresAt, Long caseId,
                                  Source source, String offense, boolean delivered, Long reviewDeadline,
                                  Long custodyId) throws SQLException {
            return insert(c, type, status, target, targetName, ip, reason, issuer, issuerName, now, expiresAt, caseId, source,
                    offense, delivered, reviewDeadline, custodyId, null);
        }

        public static long insert(Connection c, PunishType type, PunishStatus status, UUID target, String targetName, String ip,
                                  String reason, UUID issuer, String issuerName, long now, Long expiresAt, Long caseId,
                                  Source source, String offense, boolean delivered, Long reviewDeadline,
                                  Long custodyId, String opId) throws SQLException {
            return Sql.insert(c, """
                    INSERT INTO punishments(type,status,target_uuid,target_name,ip,reason,issuer_uuid,issuer_name,issued_at,
                      expires_at,case_id,source,offense_key,delivered,review_deadline,custody_id,op_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    type, status, target, targetName, ip, reason, issuer, issuerName, now, expiresAt, caseId, source, offense,
                    delivered, reviewDeadline, custodyId, opId);
        }

        // ---- standalone jail (the punishment tracks its own JailReloaded prisoner record)
        public static void setJail(Connection c, long id, String state, String jailName, String prisonerId, String error) throws SQLException {
            update(c, "UPDATE punishments SET jail_state=?, jail_name=COALESCE(?, jail_name), jail_prisoner_id=COALESCE(?, jail_prisoner_id), jail_error=? WHERE id=?",
                    state, jailName, prisonerId, error, id);
        }

        /** Take the row's lock for the rest of the transaction (the shared engines; one server has nobody to wait for). */
        public static void lock(Connection c, long id) throws SQLException {
            if (Dialect.of(c).shared()) query(c, "SELECT id FROM punishments WHERE id=? FOR UPDATE", rs -> rs.getLong(1), id);
        }

        /** The proxy name of the server the prisoner was on when jailed (where a release sends them back to). */
        public static void setJailOrigin(Connection c, long id, String origin) throws SQLException {
            update(c, "UPDATE punishments SET jail_origin=? WHERE id=?", origin, id);
        }

        private static final String LIVE_JAIL = "jail_state IN ('PLACING','HELD','PENDING_PLACEMENT','RELEASING')";

        /**
         * Standalone jail punishments (no case, no custody row) whose JailReloaded record still exists or may exist: being
         * placed, held, waiting for the player to log in, or being released. A revoked punishment stays here until the
         * prisoner is physically out.
         */
        public static List<Punishment> liveStandaloneJail(Connection c) throws SQLException {
            return query(c, "SELECT * FROM punishments WHERE type='JAIL' AND custody_id IS NULL AND " + LIVE_JAIL + " ORDER BY id ASC", MAP);
        }

        public static Punishment liveStandaloneJailFor(Connection c, UUID target) throws SQLException {
            return one(c, "SELECT * FROM punishments WHERE type='JAIL' AND custody_id IS NULL AND " + LIVE_JAIL + " AND target_uuid=? ORDER BY id DESC LIMIT 1", MAP, target);
        }

        public static Punishment byJailPrisoner(Connection c, UUID target, String prisonerId) throws SQLException {
            return one(c, "SELECT * FROM punishments WHERE type='JAIL' AND target_uuid=? AND jail_prisoner_id=? ORDER BY id DESC LIMIT 1", MAP, target, prisonerId);
        }

        /** Active punishments that came out of the judicial system (they stay enforceable whatever the switch says). */
        public static long countJudicialInForce(Connection c) throws SQLException {
            return count(c, "SELECT COUNT(*) FROM punishments WHERE source='JUDICIAL' AND status='ACTIVE'");
        }

        public static boolean setOp(Connection c, long id, String opId) throws SQLException {
            return update(c, "UPDATE punishments SET op_id=? WHERE id=? AND op_id IS NULL", opId, id) == 1;
        }

        public static Punishment get(Connection c, long id) throws SQLException {
            return one(c, "SELECT * FROM punishments WHERE id=?", MAP, id);
        }

        public static List<Punishment> active(Connection c) throws SQLException {
            return query(c, "SELECT * FROM punishments WHERE status='ACTIVE' ORDER BY id ASC", MAP);
        }

        /** The active IP ban for an address, if any. */
        public static Punishment activeIpBan(Connection c, String ip) throws SQLException {
            return one(c, "SELECT * FROM punishments WHERE type='IPBAN' AND status='ACTIVE' AND ip=? ORDER BY id DESC LIMIT 1", MAP, ip);
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

        /**
         * "Strikes" in the case workflow: punishments issued from a case (not kicks) that were not revoked, optionally for one offense
         * category, not counting the given case.
         */
        public static long countCasePunishments(Connection c, UUID target, String offenseKey, long excludeCase) throws SQLException {
            String sql = "SELECT COUNT(*) FROM punishments WHERE target_uuid=? AND case_id IS NOT NULL AND case_id<>? AND source='STANDARD' AND type<>'KICK' AND status<>'REVOKED'"
                    + (offenseKey == null ? "" : " AND offense_key=?");
            return offenseKey == null ? count(c, sql, target, excludeCase) : count(c, sql, target, excludeCase, offenseKey);
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

        /** Review modification: new expiry/reason; the first original expiry is preserved. */
        public static boolean modify(Connection c, long id, Long newExpires, String newReason, String by, long now) throws SQLException {
            // original_expires_at is assigned FIRST: MariaDB / MySQL evaluate the assignments of an UPDATE left to right, so a later
            // assignment would see the new expires_at (SQLite sees the old one in every position).
            return update(c, "UPDATE punishments SET original_expires_at=COALESCE(original_expires_at, expires_at), expires_at=?, reason=COALESCE(?,reason), modified_at=?, modified_by=? WHERE id=? AND status='ACTIVE'",
                    newExpires, newReason, now, by, id) == 1;
        }

        /** Warnings for a player, newest first. */
        public static List<Punishment> warningsFor(Connection c, UUID target, int limit) throws SQLException {
            return query(c, "SELECT * FROM punishments WHERE target_uuid=? AND type='WARN' AND status<>'REVOKED' ORDER BY issued_at DESC, id DESC LIMIT ?", MAP, target, limit);
        }

        public static long countWarningsSince(Connection c, UUID target, long since, String offenseKeyOrNull) throws SQLException {
            if (offenseKeyOrNull == null) {
                return count(c, "SELECT COUNT(*) FROM punishments WHERE target_uuid=? AND type='WARN' AND status<>'REVOKED' AND issued_at>=?", target, since);
            }
            return count(c, "SELECT COUNT(*) FROM punishments WHERE target_uuid=? AND type='WARN' AND status<>'REVOKED' AND issued_at>=? AND offense_key=?", target, since, offenseKeyOrNull);
        }

        /** Punishments (other than warnings) or cases opened after a given moment: "was further action taken?" */
        public static long followUpsAfter(Connection c, UUID target, long after) throws SQLException {
            return count(c, "SELECT (SELECT COUNT(*) FROM punishments WHERE target_uuid=? AND type<>'WARN' AND issued_at>?) + (SELECT COUNT(*) FROM cases WHERE accused_uuid=? AND created_at>? AND kind IN ('STANDARD','DIRECT','EMERGENCY'))",
                    target, after, target, after);
        }

        public static List<Punishment> expiredNotAnnounced(Connection c, long now) throws SQLException {
            return query(c, "SELECT * FROM punishments WHERE status='EXPIRED' AND ended_at>=? ORDER BY id ASC", MAP, now - 120_000);
        }

        public static void setCase(Connection c, long id, long caseId) throws SQLException {
            update(c, "UPDATE punishments SET case_id=? WHERE id=? AND case_id IS NULL", caseId, id);
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

        static final RowMapper<Appeal> MAP = rs -> new Appeal(rs.getLong("id"), longOrNull(rs, "case_id"), longOrNull(rs, "punishment_id"),
                rs.getString("filed_by_name"), rs.getLong("filed_at"), rs.getString("reason"),
                AppealStatus.valueOf(rs.getString("status")), rs.getString("decided_by_name"),
                rs.getString("decision_reason"), longOrNull(rs, "decided_at"), rs.getString("assigned_name"),
                rs.getString("ticket"), longOrNull(rs, "new_expires_at"), rs.getString("uid"), rs.getString("origin"));

        public static long add(Connection c, long caseId, String by, String reason, long now) throws SQLException {
            return Sql.insert(c, "INSERT INTO appeals(case_id,filed_by_name,filed_at,reason,status) VALUES(?,?,?,?,'PENDING')",
                    caseId, by, now, reason);
        }

        /** An appeal against one punishment (no case involved). */
        public static long addForPunishment(Connection c, long punishmentId, String by, String reason, String ticket, long now) throws SQLException {
            return Sql.insert(c, "INSERT INTO appeals(punishment_id,filed_by_name,filed_at,reason,status,ticket) VALUES(?,?,?,?,'PENDING',?)",
                    punishmentId, by, now, reason, ticket);
        }

        public static Appeal get(Connection c, long id) throws SQLException {
            return one(c, "SELECT * FROM appeals WHERE id=?", MAP, id);
        }

        public static List<Appeal> forCase(Connection c, long caseId) throws SQLException {
            return query(c, "SELECT * FROM appeals WHERE case_id=? ORDER BY id ASC", MAP, caseId);
        }

        public static List<Appeal> forPunishment(Connection c, long punishmentId) throws SQLException {
            return query(c, "SELECT * FROM appeals WHERE punishment_id=? ORDER BY id ASC", MAP, punishmentId);
        }

        public static Appeal pendingForCase(Connection c, long caseId) throws SQLException {
            return one(c, "SELECT * FROM appeals WHERE case_id=? AND status IN ('PENDING','UNDER_REVIEW') ORDER BY id DESC LIMIT 1", MAP, caseId);
        }

        public static Appeal pendingForPunishment(Connection c, long punishmentId) throws SQLException {
            return one(c, "SELECT * FROM appeals WHERE punishment_id=? AND status IN ('PENDING','UNDER_REVIEW') ORDER BY id DESC LIMIT 1", MAP, punishmentId);
        }

        /** Open appeals against punishments (the case-free queue), oldest first. */
        public static List<Appeal> openForPunishments(Connection c, int limit, int offset) throws SQLException {
            return query(c, "SELECT * FROM appeals WHERE punishment_id IS NOT NULL AND status IN ('PENDING','UNDER_REVIEW') ORDER BY id ASC LIMIT ? OFFSET ?", MAP, limit, offset);
        }

        public static void setStatus(Connection c, long id, AppealStatus st, String assigned) throws SQLException {
            update(c, "UPDATE appeals SET status=?, assigned_name=COALESCE(?, assigned_name) WHERE id=?", st, assigned, id);
        }

        public static void setTicket(Connection c, long id, String ticket) throws SQLException {
            update(c, "UPDATE appeals SET ticket=? WHERE id=?", ticket, id);
        }

        public static void decide(Connection c, long id, AppealStatus s, String by, String reason, long now) throws SQLException {
            update(c, "UPDATE appeals SET status=?, decided_by_name=?, decision_reason=?, decided_at=? WHERE id=?",
                    s, by, reason, now, id);
        }

        public static void decide(Connection c, long id, AppealStatus s, String by, String reason, long now, Long newExpiresAt) throws SQLException {
            update(c, "UPDATE appeals SET status=?, decided_by_name=?, decision_reason=?, decided_at=?, new_expires_at=? WHERE id=?",
                    s, by, reason, now, newExpiresAt, id);
        }

        public static long countPending(Connection c) throws SQLException {
            return count(c, "SELECT COUNT(*) FROM appeals WHERE status IN ('PENDING','UNDER_REVIEW')");
        }

        public static long countPendingForCases(Connection c) throws SQLException {
            return count(c, "SELECT COUNT(*) FROM appeals WHERE case_id IS NOT NULL AND status IN ('PENDING','UNDER_REVIEW')");
        }

        public static long countPendingForPunishments(Connection c) throws SQLException {
            return count(c, "SELECT COUNT(*) FROM appeals WHERE punishment_id IS NOT NULL AND status IN ('PENDING','UNDER_REVIEW')");
        }
    }

    // ------------------------------------------------------------------ pending operations
    public static final class Ops {
        private Ops() {}

        static final RowMapper<PendingOp> MAP = rs -> new PendingOp(rs.getLong("id"), rs.getString("kind"),
                OpState.valueOf(rs.getString("state")), longOrNull(rs, "case_id"), longOrNull(rs, "custody_id"),
                uuid(rs, "uuid"), rs.getString("payload"), rs.getInt("attempts"), rs.getLong("created_at"),
                rs.getLong("updated_at"), rs.getString("last_error"), longOrNull(rs, "punishment_id"));

        public static long addForPunishment(Connection c, String kind, long punishmentId, UUID uuid, String payload, long now) throws SQLException {
            return Sql.insert(c, "INSERT INTO pending_ops(kind,state,punishment_id,uuid,payload,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                    kind, OpState.PENDING, punishmentId, uuid, payload, now, now);
        }

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

    // ------------------------------------------------------------------ owner reviews
    public static final class Reviews {
        private Reviews() {}

        static final RowMapper<Review> MAP = rs -> new Review(rs.getLong("id"), longOrNull(rs, "punishment_id"), longOrNull(rs, "case_id"),
                uuid(rs, "target_uuid"), rs.getString("target_name"), uuid(rs, "issuer_uuid"), rs.getString("issuer_name"),
                PunishType.valueOf(rs.getString("ptype")), longOrNull(rs, "duration_ms"), rs.getString("reason"), rs.getLong("issued_at"),
                ReviewStatus.valueOf(rs.getString("status")), longOrNull(rs, "evidence_due"), longOrNull(rs, "review_due"),
                rs.getString("reviewer_name"), longOrNull(rs, "decided_at"), rs.getString("decision_note"), bool(rs, "migrated"),
                rs.getInt("evidence_reminders"), longOrNull(rs, "last_evidence_reminder"), rs.getInt("review_reminders"),
                longOrNull(rs, "last_review_reminder"), rs.getLong("version"), rs.getLong("updated_at"));

        public static long insert(Connection c, Long punishmentId, Long caseId, UUID target, String targetName, UUID issuer, String issuerName,
                                  PunishType type, Long durationMs, String reason, long issuedAt, ReviewStatus status, Long evidenceDue,
                                  Long reviewDue, long now) throws SQLException {
            return Sql.insert(c, """
                    INSERT INTO reviews(punishment_id,case_id,target_uuid,target_name,issuer_uuid,issuer_name,ptype,duration_ms,reason,issued_at,
                      status,evidence_due,review_due,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    punishmentId, caseId, target, targetName, issuer, issuerName, type, durationMs, reason, issuedAt, status, evidenceDue, reviewDue, now);
        }

        public static Review get(Connection c, long id) throws SQLException {
            return one(c, "SELECT * FROM reviews WHERE id=?", MAP, id);
        }

        public static Review byPunishment(Connection c, long punishmentId) throws SQLException {
            return one(c, "SELECT * FROM reviews WHERE punishment_id=?", MAP, punishmentId);
        }

        public static Review byCase(Connection c, long caseId) throws SQLException {
            return one(c, "SELECT * FROM reviews WHERE case_id=? ORDER BY id DESC LIMIT 1", MAP, caseId);
        }

        public static List<Review> byStatuses(Connection c, List<ReviewStatus> statuses, UUID issuerOrNull, int limit, int offset) throws SQLException {
            String in = String.join(",", java.util.Collections.nCopies(statuses.size(), "?"));
            List<Object> args = new java.util.ArrayList<>(statuses);
            String where = "status IN (" + in + ")";
            if (issuerOrNull != null) {
                where += " AND issuer_uuid=?";
                args.add(issuerOrNull);
            }
            args.add(limit);
            args.add(offset);
            return query(c, "SELECT * FROM reviews WHERE " + where + " ORDER BY COALESCE(review_due, issued_at) ASC, id ASC LIMIT ? OFFSET ?", MAP, args.toArray());
        }

        public static long countByStatuses(Connection c, List<ReviewStatus> statuses, UUID issuerOrNull) throws SQLException {
            String in = String.join(",", java.util.Collections.nCopies(statuses.size(), "?"));
            List<Object> args = new java.util.ArrayList<>(statuses);
            String where = "status IN (" + in + ")";
            if (issuerOrNull != null) {
                where += " AND issuer_uuid=?";
                args.add(issuerOrNull);
            }
            return count(c, "SELECT COUNT(*) FROM reviews WHERE " + where, args.toArray());
        }

        public static List<Review> openReviews(Connection c) throws SQLException {
            return query(c, "SELECT * FROM reviews WHERE status IN ('PENDING_REVIEW','UNDER_REVIEW','FURTHER_INVESTIGATION') ORDER BY id ASC", MAP);
        }

        public static List<Review> forTarget(Connection c, UUID target, int limit) throws SQLException {
            return query(c, "SELECT * FROM reviews WHERE target_uuid=? ORDER BY id DESC LIMIT ?", MAP, target, limit);
        }

        /** Optimistic status change. */
        public static boolean setStatus(Connection c, long id, long expectedVersion, ReviewStatus to, String reviewer, String note,
                                        boolean decided, long now) throws SQLException {
            return update(c, "UPDATE reviews SET status=?, reviewer_name=COALESCE(?,reviewer_name), decision_note=COALESCE(?,decision_note), decided_at=?, updated_at=?, version=version+1 WHERE id=? AND version=?",
                    to, reviewer, note, decided ? (Object) now : null, now, id, expectedVersion) == 1;
        }

        public static void setCase(Connection c, long id, long caseId, long now) throws SQLException {
            update(c, "UPDATE reviews SET case_id=?, updated_at=? WHERE id=?", caseId, now, id);
        }

        public static void setReason(Connection c, long id, Long durationMs, String reason, long now) throws SQLException {
            update(c, "UPDATE reviews SET duration_ms=?, reason=COALESCE(?,reason), updated_at=? WHERE id=?", durationMs, reason, now, id);
        }

        public static void setDeadlines(Connection c, long id, Long evidenceDue, Long reviewDue, long now) throws SQLException {
            update(c, "UPDATE reviews SET evidence_due=?, review_due=?, evidence_reminders=0, review_reminders=0, last_evidence_reminder=NULL, last_review_reminder=NULL, updated_at=?, version=version+1 WHERE id=?",
                    evidenceDue, reviewDue, now, id);
        }

        public static void noteEvidenceReminder(Connection c, long id, long now) throws SQLException {
            update(c, "UPDATE reviews SET evidence_reminders=evidence_reminders+1, last_evidence_reminder=?, updated_at=? WHERE id=?", now, now, id);
        }

        public static void noteReviewReminder(Connection c, long id, long now) throws SQLException {
            update(c, "UPDATE reviews SET review_reminders=review_reminders+1, last_review_reminder=?, updated_at=? WHERE id=?", now, now, id);
        }

        public static void addEvent(Connection c, long reviewId, long ts, String actor, String type, String detail) throws SQLException {
            update(c, "INSERT INTO review_events(review_id,ts,actor_name,type,detail) VALUES(?,?,?,?,?)", reviewId, ts, actor, type, detail);
        }

        public static List<ReviewEvent> events(Connection c, long reviewId) throws SQLException {
            return query(c, "SELECT * FROM review_events WHERE review_id=? ORDER BY id ASC", rs -> new ReviewEvent(rs.getLong("id"), rs.getLong("review_id"),
                    rs.getLong("ts"), rs.getString("actor_name"), rs.getString("type"), rs.getString("detail")), reviewId);
        }
    }

    // ------------------------------------------------------------------ player notifications
    public static final class Notifications {
        private Notifications() {}

        static final RowMapper<Notification> MAP = rs -> new Notification(rs.getLong("id"), rs.getString("dedupe_key"), uuid(rs, "uuid"),
                rs.getString("kind"), rs.getString("ref_type"), longOrNull(rs, "ref_id"), rs.getString("payload"),
                NotificationState.valueOf(rs.getString("state")), rs.getLong("created_at"), longOrNull(rs, "delivered_at"), rs.getInt("attempts"));

        /** @return true when newly queued, false when this notification was already queued (duplicate prevention). */
        public static boolean enqueue(Connection c, String dedupeKey, UUID uuid, String kind, String refType, Long refId, String payload, long now) throws SQLException {
            return update(c, Dialect.of(c).insertIgnore("player_notifications(dedupe_key,uuid,kind,ref_type,ref_id,payload,state,created_at) VALUES(?,?,?,?,?,?,'PENDING',?)"),
                    dedupeKey, uuid, kind, refType, refId, payload, now) == 1;
        }

        /** Take this notice for {@code leaseMs}: true for exactly one caller at a time (a crashed server's claim lapses). */
        public static boolean claim(Connection c, long id, String owner, long now, long leaseMs) throws SQLException {
            return update(c, "UPDATE player_notifications SET claimed_by=?, claimed_at=? WHERE id=? AND state='PENDING' AND (claimed_by IS NULL OR claimed_at<?)",
                    owner, now, id, now - leaseMs) == 1;
        }

        /** Give a claimed notice back (the player went offline before it could be shown). */
        public static void release(Connection c, long id, String owner) throws SQLException {
            update(c, "UPDATE player_notifications SET claimed_by=NULL, claimed_at=NULL WHERE id=? AND claimed_by=? AND state='PENDING'", id, owner);
        }

        public static List<Notification> pendingFor(Connection c, UUID uuid) throws SQLException {
            return query(c, "SELECT * FROM player_notifications WHERE uuid=? AND state='PENDING' ORDER BY id ASC", MAP, uuid);
        }

        public static boolean markDelivered(Connection c, long id, long now) throws SQLException {
            return update(c, "UPDATE player_notifications SET state='DELIVERED', delivered_at=?, attempts=attempts+1 WHERE id=? AND state='PENDING'", now, id) == 1;
        }

        public static void expire(Connection c, long id) throws SQLException {
            update(c, "UPDATE player_notifications SET state='EXPIRED' WHERE id=? AND state='PENDING'", id);
        }

        public static void noteAttempt(Connection c, long id) throws SQLException {
            update(c, "UPDATE player_notifications SET attempts=attempts+1 WHERE id=?", id);
        }

        public static long countPending(Connection c) throws SQLException {
            return count(c, "SELECT COUNT(*) FROM player_notifications WHERE state='PENDING'");
        }

        public static Notification byKey(Connection c, String key) throws SQLException {
            return one(c, "SELECT * FROM player_notifications WHERE dedupe_key=?", MAP, key);
        }

        /** Very old undelivered notifications are expired so a player returning after months is not flooded. */
        public static int pruneDelivered(Connection c, long cutoff) throws SQLException {
            return update(c, "DELETE FROM player_notifications WHERE state IN ('DELIVERED','EXPIRED') AND created_at<?", cutoff);
        }

        public static int expireOlderThan(Connection c, long cutoff) throws SQLException {
            return update(c, "UPDATE player_notifications SET state='EXPIRED' WHERE state='PENDING' AND created_at<? AND kind NOT IN ('WARNING')", cutoff);
        }
    }

    // ------------------------------------------------------------------ Discord outbox
    public static final class Outbox {
        private Outbox() {}

        static final RowMapper<OutboxRow> MAP = rs -> new OutboxRow(rs.getLong("id"), rs.getString("dedupe_key"), rs.getString("category"),
                rs.getString("event"), rs.getString("dest"), rs.getString("body"), OutboxState.valueOf(rs.getString("state")),
                rs.getInt("attempts"), rs.getLong("next_attempt"), rs.getLong("created_at"), longOrNull(rs, "sent_at"), rs.getString("last_error"));

        public static boolean enqueue(Connection c, String dedupeKey, String category, String event, String dest, String body, long now) throws SQLException {
            return update(c, Dialect.of(c).insertIgnore("discord_outbox(dedupe_key,category,event,dest,body,state,next_attempt,created_at) VALUES(?,?,?,?,?,'PENDING',?,?)"),
                    dedupeKey, category, event, dest, body, now, now) == 1;
        }

        public static List<OutboxRow> due(Connection c, long now, int limit) throws SQLException {
            return query(c, "SELECT * FROM discord_outbox WHERE state='PENDING' AND next_attempt<=? ORDER BY id ASC LIMIT ?", MAP, now, limit);
        }

        /**
         * Shared database: take up to {@code limit} due rows for {@code owner} for {@code leaseMs}. A row another server holds a lease on is left
         * alone; the lease running out (that server died mid-delivery) makes it due again. The claim is one atomic UPDATE per row.
         */
        public static List<OutboxRow> claim(Connection c, String owner, long now, int limit, long leaseMs) throws SQLException {
            List<OutboxRow> out = new java.util.ArrayList<>();
            for (OutboxRow r : query(c, "SELECT * FROM discord_outbox WHERE state='PENDING' AND next_attempt<=? AND (claimed_until IS NULL OR claimed_until<?) ORDER BY id ASC LIMIT ?",
                    MAP, now, now, limit)) {
                if (update(c, "UPDATE discord_outbox SET claimed_by=?, claimed_until=? WHERE id=? AND state='PENDING' AND (claimed_until IS NULL OR claimed_until<?)",
                        owner, now + leaseMs, r.id(), now) == 1) out.add(r);
            }
            return out;
        }

        public static void markSent(Connection c, long id, long now) throws SQLException {
            update(c, "UPDATE discord_outbox SET state='SENT', sent_at=?, attempts=attempts+1, last_error=NULL, claimed_by=NULL, claimed_until=NULL WHERE id=?", now, id);
        }

        public static void retryLater(Connection c, long id, long next, String error) throws SQLException {
            update(c, "UPDATE discord_outbox SET attempts=attempts+1, next_attempt=?, last_error=?, claimed_by=NULL, claimed_until=NULL WHERE id=?", next, error, id);
        }

        public static void markDead(Connection c, long id, String error) throws SQLException {
            update(c, "UPDATE discord_outbox SET state='DEAD', attempts=attempts+1, last_error=?, claimed_by=NULL, claimed_until=NULL WHERE id=?", error, id);
        }

        public static long count(Connection c, OutboxState st) throws SQLException {
            return Sql.count(c, "SELECT COUNT(*) FROM discord_outbox WHERE state=?", st.name());
        }

        public static int prune(Connection c, long olderThan) throws SQLException {
            return update(c, "DELETE FROM discord_outbox WHERE state IN ('SENT','DEAD') AND created_at<?", olderThan);
        }

        public static int requeueDead(Connection c, long now) throws SQLException {
            return update(c, "UPDATE discord_outbox SET state='PENDING', next_attempt=?, attempts=0, claimed_by=NULL, claimed_until=NULL WHERE state='DEAD'", now);
        }

        public static List<OutboxRow> recentDead(Connection c, int limit) throws SQLException {
            return query(c, "SELECT * FROM discord_outbox WHERE state='DEAD' ORDER BY id DESC LIMIT ?", MAP, limit);
        }
    }

    // ------------------------------------------------------------------ audit (hash-chained, append-only)
    public static final class Audits {
        private Audits() {}

        public static final String GENESIS = "GENESIS";

        static final RowMapper<Audit> MAP = rs -> new Audit(rs.getLong("id"), rs.getLong("ts"), uuid(rs, "actor_uuid"),
                rs.getString("actor_name"), rs.getString("action"), uuid(rs, "target_uuid"), longOrNull(rs, "case_id"),
                rs.getString("detail"), bool(rs, "elevated"), rs.getString("prev_hash"), rs.getString("hash"),
                rs.getString("origin"), rs.getString("op_id"));

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
            append(c, ts, actor, actorName, action, target, caseId, detail, elevated, null);
        }

        /** @param opId the operation (one service call = one transaction) this entry belongs to; not part of the hash chain. */
        public static void append(Connection c, long ts, UUID actor, String actorName, String action, UUID target,
                                  Long caseId, String detail, boolean elevated, String opId) throws SQLException {
            // Several servers append to one hash chain: take the chain's lock first (held until this transaction ends), so each entry
            // is chained to the one before it, never to a sibling written at the same moment.
            if (Dialect.of(c).shared()) one(c, "SELECT v FROM meta WHERE k='audit_lock' FOR UPDATE", rs -> rs.getString(1));
            String prev = lastHash(c);
            String hash = digest(prev, ts, actor, actorName, action, target, caseId, detail, elevated);
            update(c, "INSERT INTO audit_log(ts,actor_uuid,actor_name,action,target_uuid,case_id,detail,elevated,prev_hash,hash,op_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    ts, actor, actorName, action, target, caseId, detail, elevated, prev, hash, opId);
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
            update(c, Dialect.of(c).upsert("audit_anchor(id,hash,seq) VALUES(1,?,?)", List.of("id"), List.of("hash={new:hash}", "seq={new:seq}")),
                    lastOld.hash(), lastOld.id());
            return update(c, "DELETE FROM audit_log WHERE id<=?", lastOld.id());
        }
    }

    // ------------------------------------------------------------------ 1.4.0: the network
    /** What a network of servers sharing this database needs: change events, a server registry, presence, background-job leases. */
    public static final class Net {
        private Net() {}

        public record Event(long id, long ts, String origin, String kind, UUID subject, Long ref, String detail) {}

        public record Server(String name, String instanceId, String version, int schema, long startedAt, long seenAt, boolean jailBackend, String proxyName) {
            /** The name the proxy knows this backend by (defaults to the server name). */
            public String proxy() {
                return proxyName == null || proxyName.isBlank() ? name : proxyName;
            }
        }

        public record Presence(UUID uuid, String name, String server, long joinedAt, long seenAt) {}

        private static final RowMapper<Event> EVENT = rs -> new Event(rs.getLong("id"), rs.getLong("ts"), rs.getString("origin"), rs.getString("kind"),
                uuid(rs, "subject_uuid"), longOrNull(rs, "ref_id"), rs.getString("detail"));
        private static final RowMapper<Server> SERVER = rs -> new Server(rs.getString("name"), rs.getString("instance_id"), rs.getString("plugin_version"),
                rs.getInt("schema_version"), rs.getLong("started_at"), rs.getLong("seen_at"), bool(rs, "jail_backend"), rs.getString("proxy_name"));

        // ---- events
        public static long lastEventId(Connection c) throws SQLException {
            return count(c, "SELECT COALESCE(MAX(id),0) FROM network_events");
        }

        public static List<Event> eventsAfter(Connection c, long cursor, int limit) throws SQLException {
            return query(c, "SELECT * FROM network_events WHERE id>? ORDER BY id ASC LIMIT ?", EVENT, cursor, limit);
        }

        public static int purgeEvents(Connection c, long olderThan) throws SQLException {
            return update(c, "DELETE FROM network_events WHERE ts<?", olderThan);
        }

        // ---- registry
        public static Server server(Connection c, String name) throws SQLException {
            return one(c, "SELECT * FROM network_servers WHERE name=?", SERVER, name);
        }

        public static List<Server> servers(Connection c) throws SQLException {
            return query(c, "SELECT * FROM network_servers ORDER BY name", SERVER);
        }

        public static void register(Connection c, String name, String instance, String version, int schema, long now, boolean jail, String proxyName) throws SQLException {
            update(c, Dialect.of(c).upsert("network_servers(name,instance_id,plugin_version,schema_version,started_at,seen_at,jail_backend,proxy_name) VALUES(?,?,?,?,?,?,?,?)",
                    List.of("name"), List.of("instance_id={new:instance_id}", "plugin_version={new:plugin_version}", "schema_version={new:schema_version}",
                            "started_at={new:started_at}", "seen_at={new:seen_at}", "jail_backend={new:jail_backend}", "proxy_name={new:proxy_name}")),
                    name, instance, version, schema, now, now, jail ? 1 : 0, proxyName);
        }

        /** @return false when another instance has taken this name over (two servers configured with the same server-name) */
        public static boolean heartbeat(Connection c, String name, String instance, long now) throws SQLException {
            return update(c, "UPDATE network_servers SET seen_at=? WHERE name=? AND instance_id=?", now, name, instance) == 1;
        }

        public static void unregister(Connection c, String name, String instance) throws SQLException {
            update(c, "UPDATE network_servers SET seen_at=0 WHERE name=? AND instance_id=?", name, instance);
        }

        // ---- presence
        public static void join(Connection c, UUID id, String name, String server, long now) throws SQLException {
            update(c, Dialect.of(c).upsert("network_presence(uuid,name,server,joined_at,seen_at) VALUES(?,?,?,?,?)", List.of("uuid"),
                    List.of("name={new:name}", "server={new:server}", "joined_at={new:joined_at}", "seen_at={new:seen_at}")), id, name, server, now, now);
        }

        public static void leave(Connection c, UUID id, String server) throws SQLException {
            update(c, "DELETE FROM network_presence WHERE uuid=? AND server=?", id, server);
        }

        public static void touch(Connection c, String server, java.util.Collection<UUID> online, long now) throws SQLException {
            if (online.isEmpty()) return;
            StringBuilder in = new StringBuilder();
            List<Object> args = new java.util.ArrayList<>();
            args.add(now);
            args.add(server);
            for (UUID u : online) {
                if (in.length() > 0) in.append(',');
                in.append('?');
                args.add(u);
            }
            update(c, "UPDATE network_presence SET seen_at=? WHERE server=? AND uuid IN (" + in + ")", args.toArray());
        }

        /** Where the player is connected right now (a row nobody refreshed for {@code staleAfter} ms is a crashed server's leftover). */
        public static Presence presence(Connection c, UUID id, long now, long staleAfter) throws SQLException {
            return one(c, "SELECT * FROM network_presence WHERE uuid=? AND seen_at>?", rs -> new Presence(uuid(rs, "uuid"), rs.getString("name"), rs.getString("server"),
                    rs.getLong("joined_at"), rs.getLong("seen_at")), id, now - staleAfter);
        }

        public static int purgePresence(Connection c, long olderThan) throws SQLException {
            return update(c, "DELETE FROM network_presence WHERE seen_at<?", olderThan);
        }

        // ---- leases: exactly one server runs a background job at a time
        /** @return true when {@code owner} holds (or just took) the lease named {@code name} until now+ttl */
        public static boolean lease(Connection c, String name, String owner, long now, long ttl) throws SQLException {
            Dialect d = Dialect.of(c);
            int taken = update(c, "UPDATE network_locks SET owner=?, lease_until=? WHERE name=? AND (owner=? OR lease_until<?)", owner, now + ttl, name, owner, now);
            if (taken == 1) return true;
            update(c, d.insertIgnore("network_locks(name,owner,lease_until) VALUES(?,?,?)"), name, owner, now + ttl);
            String holder = one(c, "SELECT owner FROM network_locks WHERE name=?", rs -> rs.getString(1), name);
            return owner.equals(holder);
        }

        /** Who holds the lease right now (null when nobody does). */
        public static String leaseHolder(Connection c, String name, long now) throws SQLException {
            return one(c, "SELECT owner FROM network_locks WHERE name=? AND lease_until>?", rs -> rs.getString(1), name, now);
        }

        public static void releaseLease(Connection c, String name, String owner) throws SQLException {
            update(c, "UPDATE network_locks SET lease_until=0 WHERE name=? AND owner=?", name, owner);
        }
    }
}
