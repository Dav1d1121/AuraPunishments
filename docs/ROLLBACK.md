# AuraPunishments 1.1.0 - Rollback

1.1.0 migrates the database from schema v1 to v2 (new tables and columns only; no 1.0.0 data is changed or removed).

| Situation | What to do |
|---|---|
| Problem **before** anything was issued under 1.1.0 | Stop; put `AuraPunishments-1.0.0.jar` back; restore `plugins/AuraPunishments/backups/punishments.db.pre-v2-<timestamp>` over `punishments.db` (delete `punishments.db-wal` / `-shm`); start. 1.0.0 refuses to start on a v2 database, so this restore is required. |
| Problem **after** 1.1.0 was used | Rolling back loses what 1.1.0 recorded after the backup (reviews, notification queue, outbox, appeal tickets and any punishments issued since). If you must, first export what you need (`SELECT * FROM punishments WHERE issued_at > <migration time>`), then restore the backup and re-issue. |
| Configuration only | Your `config.yml` was never modified by the upgrade. Delete `config-1.1.0-reference.yml` if you do not want it. |
| JailReloaded | Untouched by 1.1.0. Prisoners held for open cases stay jailed under either version. |

Always copy `plugins/AuraPunishments/` before upgrading (see [INSTALLATION.md](INSTALLATION.md)).
