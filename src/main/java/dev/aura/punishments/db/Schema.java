package dev.aura.punishments.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Versioned schema. Add a new {@link Migration} to the end of {@link #MIGRATIONS}; never edit a
 * released one. The SQL is deliberately plain and runs through the {@link Dialect}, so MySQL/MariaDB
 * can be added behind the same interface in the network release. A migration step is either one SQL
 * statement or a small piece of Java (used where a data fix-up cannot be written portably).
 */
public final class Schema {
    private Schema() {}

    /** Everything a Java migration step may need. */
    public record Context(Dialect dialect, String origin, java.util.logging.Logger log) {}

    @FunctionalInterface
    public interface Step {
        void run(Connection c, Context ctx) throws SQLException;
    }

    public record Migration(int version, String description, List<Step> steps, List<String> statements) {
        public Migration(int version, String description, List<Step> steps) {
            this(version, description, steps, List.of());
        }

        /** A migration made only of SQL statements. */
        public static Migration sql(int version, String description, List<String> statements) {
            List<Step> steps = new ArrayList<>();
            for (String stmt : statements) steps.add((c, ctx) -> {
                try (Statement st = c.createStatement()) {
                    st.execute(ctx.dialect().translate(stmt));
                }
            });
            return new Migration(version, description, steps, List.copyOf(statements));
        }
    }

    private static Step sql(String statement) {
        return (c, ctx) -> {
            try (Statement st = c.createStatement()) {
                st.execute(ctx.dialect().translate(statement));
            }
        };
    }

    private static final List<String> UID_TABLES = List.of("punishments", "cases", "evidence", "notes", "appeals", "reviews", "custody");

    public static final List<Migration> MIGRATIONS = List.of(
            Migration.sql(1, "Initial schema", List.of(
                    """
                    CREATE TABLE profiles (
                      uuid TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      name_lower TEXT NOT NULL,
                      first_seen INTEGER NOT NULL,
                      last_seen INTEGER NOT NULL,
                      last_ip TEXT,
                      level INTEGER NOT NULL DEFAULT 0
                    )""",
                    "CREATE INDEX idx_profiles_name ON profiles(name_lower)",
                    """
                    CREATE TABLE cases (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      status TEXT NOT NULL,
                      kind TEXT NOT NULL DEFAULT 'STANDARD',
                      accused_uuid TEXT NOT NULL,
                      accused_name TEXT NOT NULL,
                      offense_key TEXT NOT NULL,
                      severity TEXT NOT NULL,
                      description TEXT NOT NULL,
                      context TEXT,
                      prosecutor_uuid TEXT,
                      prosecutor_name TEXT,
                      judge_uuid TEXT,
                      judge_name TEXT,
                      trial_at INTEGER,
                      hearing_ref TEXT,
                      verdict TEXT,
                      verdict_reason TEXT,
                      verdict_by_name TEXT,
                      verdict_at INTEGER,
                      overturned INTEGER NOT NULL DEFAULT 0,
                      created_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL,
                      closed_at INTEGER,
                      version INTEGER NOT NULL DEFAULT 0
                    )""",
                    "CREATE INDEX idx_cases_accused ON cases(accused_uuid)",
                    "CREATE INDEX idx_cases_status ON cases(status)",
                    """
                    CREATE TABLE case_events (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      case_id INTEGER NOT NULL REFERENCES cases(id),
                      ts INTEGER NOT NULL,
                      actor_uuid TEXT,
                      actor_name TEXT NOT NULL,
                      type TEXT NOT NULL,
                      from_status TEXT,
                      to_status TEXT,
                      detail TEXT
                    )""",
                    "CREATE INDEX idx_events_case ON case_events(case_id)",
                    """
                    CREATE TABLE evidence (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      case_id INTEGER NOT NULL REFERENCES cases(id),
                      type TEXT NOT NULL,
                      reference TEXT NOT NULL,
                      description TEXT,
                      submitted_by TEXT,
                      submitted_name TEXT NOT NULL,
                      submitted_at INTEGER NOT NULL,
                      voided INTEGER NOT NULL DEFAULT 0,
                      void_reason TEXT,
                      voided_by TEXT,
                      voided_name TEXT,
                      voided_at INTEGER,
                      corrects_id INTEGER
                    )""",
                    "CREATE INDEX idx_evidence_case ON evidence(case_id)",
                    """
                    CREATE TABLE notes (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      case_id INTEGER,
                      profile_uuid TEXT,
                      author_uuid TEXT,
                      author_name TEXT NOT NULL,
                      text TEXT NOT NULL,
                      created_at INTEGER NOT NULL,
                      sensitive INTEGER NOT NULL DEFAULT 0,
                      voided INTEGER NOT NULL DEFAULT 0,
                      void_reason TEXT,
                      voided_name TEXT,
                      voided_at INTEGER
                    )""",
                    "CREATE INDEX idx_notes_case ON notes(case_id)",
                    "CREATE INDEX idx_notes_profile ON notes(profile_uuid)",
                    """
                    CREATE TABLE custody (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      case_id INTEGER NOT NULL REFERENCES cases(id),
                      uuid TEXT NOT NULL,
                      name TEXT NOT NULL,
                      status TEXT NOT NULL,
                      requested_by TEXT,
                      requested_name TEXT NOT NULL,
                      requested_at INTEGER NOT NULL,
                      started_at INTEGER,
                      deadline INTEGER,
                      extensions INTEGER NOT NULL DEFAULT 0,
                      jail_name TEXT,
                      jail_prisoner_id TEXT,
                      ended_at INTEGER,
                      ended_by TEXT,
                      end_reason TEXT,
                      alerts_sent TEXT NOT NULL DEFAULT '',
                      last_error TEXT,
                      updated_at INTEGER NOT NULL
                    )""",
                    "CREATE INDEX idx_custody_case ON custody(case_id)",
                    "CREATE INDEX idx_custody_uuid ON custody(uuid)",
                    """
                    CREATE TABLE punishments (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      type TEXT NOT NULL,
                      status TEXT NOT NULL,
                      target_uuid TEXT NOT NULL,
                      target_name TEXT NOT NULL,
                      ip TEXT,
                      reason TEXT NOT NULL,
                      issuer_uuid TEXT,
                      issuer_name TEXT NOT NULL,
                      issued_at INTEGER NOT NULL,
                      expires_at INTEGER,
                      ended_at INTEGER,
                      ended_by TEXT,
                      end_reason TEXT,
                      case_id INTEGER,
                      source TEXT NOT NULL,
                      offense_key TEXT,
                      delivered INTEGER NOT NULL DEFAULT 1,
                      review_deadline INTEGER,
                      reviewed INTEGER NOT NULL DEFAULT 0,
                      custody_id INTEGER
                    )""",
                    "CREATE INDEX idx_pun_target ON punishments(target_uuid)",
                    "CREATE INDEX idx_pun_status ON punishments(status)",
                    "CREATE INDEX idx_pun_case ON punishments(case_id)",
                    """
                    CREATE TABLE direct_actions (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      case_id INTEGER NOT NULL REFERENCES cases(id),
                      punishment_id INTEGER,
                      offense_key TEXT NOT NULL,
                      justification TEXT NOT NULL,
                      actor_name TEXT NOT NULL,
                      created_at INTEGER NOT NULL
                    )""",
                    """
                    CREATE TABLE appeals (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      case_id INTEGER NOT NULL REFERENCES cases(id),
                      filed_by_name TEXT NOT NULL,
                      filed_at INTEGER NOT NULL,
                      reason TEXT NOT NULL,
                      status TEXT NOT NULL,
                      decided_by_name TEXT,
                      decision_reason TEXT,
                      decided_at INTEGER
                    )""",
                    """
                    CREATE TABLE audit_log (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      ts INTEGER NOT NULL,
                      actor_uuid TEXT,
                      actor_name TEXT NOT NULL,
                      action TEXT NOT NULL,
                      target_uuid TEXT,
                      case_id INTEGER,
                      detail TEXT,
                      elevated INTEGER NOT NULL DEFAULT 0,
                      prev_hash TEXT NOT NULL,
                      hash TEXT NOT NULL
                    )""",
                    "CREATE INDEX idx_audit_case ON audit_log(case_id)",
                    "CREATE INDEX idx_audit_target ON audit_log(target_uuid)",
                    """
                    CREATE TABLE audit_anchor (
                      id INTEGER PRIMARY KEY CHECK (id = 1),
                      hash TEXT NOT NULL,
                      seq INTEGER NOT NULL
                    )""",
                    """
                    CREATE TABLE pending_ops (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      kind TEXT NOT NULL,
                      state TEXT NOT NULL,
                      case_id INTEGER,
                      custody_id INTEGER,
                      uuid TEXT,
                      payload TEXT,
                      attempts INTEGER NOT NULL DEFAULT 0,
                      created_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL,
                      last_error TEXT
                    )""",
                    "CREATE INDEX idx_ops_state ON pending_ops(state)"
            )),
            Migration.sql(2, "1.1.0: owner reviews, player notifications, Discord outbox, appeal tickets", List.of(
                    "ALTER TABLE cases ADD COLUMN appeal_ticket TEXT",
                    "ALTER TABLE appeals ADD COLUMN assigned_name TEXT",
                    "ALTER TABLE punishments ADD COLUMN modified_at INTEGER",
                    "ALTER TABLE punishments ADD COLUMN modified_by TEXT",
                    "ALTER TABLE punishments ADD COLUMN original_expires_at INTEGER",
                    """
                    CREATE TABLE reviews (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      punishment_id INTEGER,
                      case_id INTEGER,
                      target_uuid TEXT NOT NULL,
                      target_name TEXT NOT NULL,
                      issuer_uuid TEXT,
                      issuer_name TEXT NOT NULL,
                      ptype TEXT NOT NULL,
                      duration_ms INTEGER,
                      reason TEXT NOT NULL,
                      issued_at INTEGER NOT NULL,
                      status TEXT NOT NULL,
                      evidence_due INTEGER,
                      review_due INTEGER,
                      reviewer_name TEXT,
                      decided_at INTEGER,
                      decision_note TEXT,
                      migrated INTEGER NOT NULL DEFAULT 0,
                      evidence_reminders INTEGER NOT NULL DEFAULT 0,
                      last_evidence_reminder INTEGER,
                      review_reminders INTEGER NOT NULL DEFAULT 0,
                      last_review_reminder INTEGER,
                      version INTEGER NOT NULL DEFAULT 0,
                      updated_at INTEGER NOT NULL
                    )""",
                    "CREATE UNIQUE INDEX idx_reviews_punishment ON reviews(punishment_id) WHERE punishment_id IS NOT NULL",
                    "CREATE INDEX idx_reviews_status ON reviews(status)",
                    "CREATE INDEX idx_reviews_target ON reviews(target_uuid)",
                    """
                    CREATE TABLE review_events (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      review_id INTEGER NOT NULL REFERENCES reviews(id),
                      ts INTEGER NOT NULL,
                      actor_name TEXT NOT NULL,
                      type TEXT NOT NULL,
                      detail TEXT
                    )""",
                    "CREATE INDEX idx_review_events ON review_events(review_id)",
                    """
                    CREATE TABLE player_notifications (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      dedupe_key TEXT NOT NULL UNIQUE,
                      uuid TEXT NOT NULL,
                      kind TEXT NOT NULL,
                      ref_type TEXT,
                      ref_id INTEGER,
                      payload TEXT NOT NULL,
                      state TEXT NOT NULL,
                      created_at INTEGER NOT NULL,
                      delivered_at INTEGER,
                      attempts INTEGER NOT NULL DEFAULT 0
                    )""",
                    "CREATE INDEX idx_notif_uuid_state ON player_notifications(uuid, state)",
                    """
                    CREATE TABLE discord_outbox (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      dedupe_key TEXT NOT NULL UNIQUE,
                      category TEXT NOT NULL,
                      event TEXT NOT NULL,
                      dest TEXT NOT NULL,
                      body TEXT NOT NULL,
                      state TEXT NOT NULL,
                      attempts INTEGER NOT NULL DEFAULT 0,
                      next_attempt INTEGER NOT NULL,
                      created_at INTEGER NOT NULL,
                      sent_at INTEGER,
                      last_error TEXT
                    )""",
                    "CREATE INDEX idx_outbox_state ON discord_outbox(state, next_attempt)",
                    // Warnings that were never shown to the player become queued notifications (original timestamps kept).
                    """
                    INSERT INTO player_notifications(dedupe_key, uuid, kind, ref_type, ref_id, payload, state, created_at)
                    SELECT 'warn:' || id, target_uuid, 'WARNING', 'punishment', id,
                           json_object('reason', reason, 'issuer', issuer_name, 'id', CAST(id AS TEXT)), 'PENDING', issued_at
                    FROM punishments WHERE type='WARN' AND delivered=0 AND status<>'REVOKED'""",
                    // 1.0.0 Quick Punishments that would now need review are imported as LEGACY (never in the pending queue, never new).
                    """
                    INSERT INTO reviews(punishment_id, case_id, target_uuid, target_name, issuer_uuid, issuer_name, ptype, duration_ms,
                                        reason, issued_at, status, migrated, updated_at)
                    SELECT id, case_id, target_uuid, target_name, issuer_uuid, issuer_name, type,
                           CASE WHEN expires_at IS NULL THEN NULL ELSE expires_at - issued_at END,
                           reason, issued_at, 'LEGACY', 1, issued_at
                    FROM punishments WHERE source='QUICK' AND type IN ('MUTE','BAN','IPBAN')""",
                    """
                    INSERT INTO review_events(review_id, ts, actor_name, type, detail)
                    SELECT id, issued_at, 'Migration', 'MIGRATED', 'Imported from AuraPunishments 1.0.0; issued before reviews existed'
                    FROM reviews WHERE migrated=1"""
            )),
            migration3(),
            migration4()
    );

    // ------------------------------------------------------------------ 1.3.0

    /**
     * 1.3.0: optional judicial system, case-free evidence/notes/appeals, standalone jail, and the identifiers the
     * network release needs. Existing rows are preserved with their ids; nothing is deleted. The two tables whose
     * case reference becomes optional (evidence, appeals) are rebuilt (copy, drop, rename) inside the migration's
     * transaction, so a failure leaves the database exactly as it was.
     */
    private static Migration migration3() {
        List<Step> steps = new ArrayList<>();
        // identifiers: globally unique id + the server that created the record (backfilled below)
        for (String t : UID_TABLES) {
            if (t.equals("evidence") || t.equals("appeals")) continue; // rebuilt below with the new columns
            steps.add(sql("ALTER TABLE " + t + " ADD COLUMN uid TEXT"));
            steps.add(sql("ALTER TABLE " + t + " ADD COLUMN origin TEXT"));
        }
        steps.add(sql("ALTER TABLE punishments ADD COLUMN op_id TEXT"));
        // standalone jail: the punishment itself tracks its JailReloaded prisoner record
        steps.add(sql("ALTER TABLE punishments ADD COLUMN jail_prisoner_id TEXT"));
        steps.add(sql("ALTER TABLE punishments ADD COLUMN jail_name TEXT"));
        steps.add(sql("ALTER TABLE punishments ADD COLUMN jail_state TEXT"));
        steps.add(sql("ALTER TABLE punishments ADD COLUMN jail_error TEXT"));
        steps.add(sql("ALTER TABLE notes ADD COLUMN punishment_id INTEGER"));
        steps.add(sql("CREATE INDEX idx_notes_punishment ON notes(punishment_id)"));
        steps.add(sql("ALTER TABLE pending_ops ADD COLUMN punishment_id INTEGER"));
        steps.add(sql("ALTER TABLE audit_log ADD COLUMN origin TEXT"));
        steps.add(sql("ALTER TABLE audit_log ADD COLUMN op_id TEXT"));

        // evidence: belongs to a case OR directly to a punishment
        steps.add(sql("""
                CREATE TABLE evidence_v3 (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  case_id INTEGER REFERENCES cases(id),
                  punishment_id INTEGER REFERENCES punishments(id),
                  type TEXT NOT NULL,
                  reference TEXT NOT NULL,
                  description TEXT,
                  submitted_by TEXT,
                  submitted_name TEXT NOT NULL,
                  submitted_at INTEGER NOT NULL,
                  voided INTEGER NOT NULL DEFAULT 0,
                  void_reason TEXT,
                  voided_by TEXT,
                  voided_name TEXT,
                  voided_at INTEGER,
                  corrects_id INTEGER,
                  uid TEXT,
                  origin TEXT,
                  CHECK (case_id IS NOT NULL OR punishment_id IS NOT NULL)
                )"""));
        steps.add(sql("""
                INSERT INTO evidence_v3(id,case_id,type,reference,description,submitted_by,submitted_name,submitted_at,
                                        voided,void_reason,voided_by,voided_name,voided_at,corrects_id)
                SELECT id,case_id,type,reference,description,submitted_by,submitted_name,submitted_at,
                       voided,void_reason,voided_by,voided_name,voided_at,corrects_id FROM evidence"""));
        steps.add(sql("DROP TABLE evidence"));
        steps.add(sql("ALTER TABLE evidence_v3 RENAME TO evidence"));
        steps.add(sql("CREATE INDEX idx_evidence_case ON evidence(case_id)"));
        steps.add(sql("CREATE INDEX idx_evidence_punishment ON evidence(punishment_id)"));

        // appeals: against a case OR directly against a punishment; carries the Discord Ticket Tool reference
        steps.add(sql("""
                CREATE TABLE appeals_v3 (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  case_id INTEGER REFERENCES cases(id),
                  punishment_id INTEGER REFERENCES punishments(id),
                  filed_by_name TEXT NOT NULL,
                  filed_at INTEGER NOT NULL,
                  reason TEXT NOT NULL,
                  status TEXT NOT NULL,
                  decided_by_name TEXT,
                  decision_reason TEXT,
                  decided_at INTEGER,
                  assigned_name TEXT,
                  ticket TEXT,
                  new_expires_at INTEGER,
                  uid TEXT,
                  origin TEXT,
                  CHECK (case_id IS NOT NULL OR punishment_id IS NOT NULL)
                )"""));
        steps.add(sql("""
                INSERT INTO appeals_v3(id,case_id,filed_by_name,filed_at,reason,status,decided_by_name,decision_reason,decided_at,assigned_name,ticket)
                SELECT a.id,a.case_id,a.filed_by_name,a.filed_at,a.reason,a.status,a.decided_by_name,a.decision_reason,a.decided_at,a.assigned_name,
                       (SELECT cs.appeal_ticket FROM cases cs WHERE cs.id=a.case_id)
                FROM appeals a"""));
        steps.add(sql("DROP TABLE appeals"));
        steps.add(sql("ALTER TABLE appeals_v3 RENAME TO appeals"));
        steps.add(sql("CREATE INDEX idx_appeals_case ON appeals(case_id)"));
        steps.add(sql("CREATE INDEX idx_appeals_punishment ON appeals(punishment_id)"));
        steps.add(sql("CREATE INDEX idx_appeals_status ON appeals(status)"));

        // give every existing record its globally unique id and the server it came from
        steps.add((c, ctx) -> {
            for (String t : UID_TABLES) {
                try (var ps = c.prepareStatement("UPDATE " + t + " SET uid=" + ctx.dialect().randomId() + ", origin=? WHERE uid IS NULL")) {
                    ps.setString(1, ctx.origin());
                    ps.executeUpdate();
                }
            }
            try (var ps = c.prepareStatement("UPDATE audit_log SET origin=? WHERE origin IS NULL")) {
                ps.setString(1, ctx.origin());
                ps.executeUpdate();
            }
        });
        for (String t : UID_TABLES) steps.add(sql("CREATE UNIQUE INDEX idx_" + t + "_uid ON " + t + "(uid)"));
        // ... and make that hold for every record created from now on, whichever code path inserts it
        for (String t : UID_TABLES) steps.add((c, ctx) -> {
            try (Statement st = c.createStatement()) {
                st.execute(ctx.dialect().identityTrigger(t));
            }
        });
        steps.add((c, ctx) -> {
            try (Statement st = c.createStatement()) {
                st.execute(ctx.dialect().originTrigger("audit_log"));
            }
        });
        steps.add(sql("CREATE INDEX idx_pun_jail ON punishments(type, status, jail_prisoner_id)"));
        return new Migration(3, "1.3.0: optional judicial system, case-free evidence/notes/appeals, standalone jail, network identifiers", steps);
    }

    // ------------------------------------------------------------------ 1.4.0

    /**
     * 1.4.0: what a network of servers sharing one database needs. Harmless (and unused) on a standalone SQLite server.
     * {@code network_events} says what changed (written by triggers on the shared engines), {@code network_servers} is the registry
     * every server keeps a heartbeat in, {@code network_presence} tells where a player is connected, and {@code network_locks} lets
     * exactly one server run a background job at a time.
     */
    private static Migration migration4() {
        List<Step> steps = new ArrayList<>();
        steps.add(sql("""
                CREATE TABLE network_events (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  ts INTEGER NOT NULL,
                  origin TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  subject_uuid TEXT,
                  ref_id INTEGER,
                  detail TEXT
                )"""));
        steps.add(sql("CREATE INDEX idx_netev_ts ON network_events(ts)"));
        steps.add(sql("""
                CREATE TABLE network_servers (
                  name TEXT PRIMARY KEY,
                  instance_id TEXT NOT NULL,
                  plugin_version TEXT NOT NULL,
                  schema_version INTEGER NOT NULL,
                  started_at INTEGER NOT NULL,
                  seen_at INTEGER NOT NULL,
                  jail_backend INTEGER NOT NULL DEFAULT 0
                )"""));
        steps.add(sql("""
                CREATE TABLE network_presence (
                  uuid TEXT PRIMARY KEY,
                  name TEXT NOT NULL,
                  server TEXT NOT NULL,
                  joined_at INTEGER NOT NULL,
                  seen_at INTEGER NOT NULL
                )"""));
        steps.add(sql("CREATE INDEX idx_presence_server ON network_presence(server)"));
        steps.add(sql("""
                CREATE TABLE network_locks (
                  name TEXT PRIMARY KEY,
                  owner TEXT NOT NULL,
                  lease_until INTEGER NOT NULL
                )"""));
        // a queued player notice (a warning for someone who was offline) is CLAIMED by one server for a short lease before it is shown,
        // so a player who connects to two servers in quick succession is not shown it twice
        steps.add(sql("ALTER TABLE player_notifications ADD COLUMN claimed_by TEXT"));
        steps.add(sql("ALTER TABLE player_notifications ADD COLUMN claimed_at INTEGER"));
        // the row every audit append locks, so that servers writing the hash chain at the same moment take turns
        steps.add((c, ctx) -> {
            try (Statement st = c.createStatement()) {
                st.execute(ctx.dialect().insertIgnore("meta(k,v) VALUES('audit_lock','1')"));
            }
        });
        steps.add((c, ctx) -> {
            for (String trigger : ctx.dialect().networkTriggers()) {
                try (Statement st = c.createStatement()) {
                    st.execute(trigger);
                }
            }
        });
        return new Migration(4, "1.4.0: network events, server registry, presence and leader locks", steps);
    }

    public static int latest() {
        return MIGRATIONS.get(MIGRATIONS.size() - 1).version();
    }
}
