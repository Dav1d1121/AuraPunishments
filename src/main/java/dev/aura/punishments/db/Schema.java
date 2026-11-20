package dev.aura.punishments.db;

import java.util.List;

/**
 * Versioned schema. Add a new {@link Migration} to the end of {@link #MIGRATIONS}; never edit a
 * released one. The SQL is deliberately plain so a MySQL/MariaDB dialect can be added later
 * (the only SQLite-specific parts are AUTOINCREMENT and the pragmas in {@link Database}).
 */
public final class Schema {
    private Schema() {}

    public record Migration(int version, String description, List<String> statements) {}

    public static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "Initial schema", List.of(
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
            ))
    );

    public static int latest() {
        return MIGRATIONS.get(MIGRATIONS.size() - 1).version();
    }
}
