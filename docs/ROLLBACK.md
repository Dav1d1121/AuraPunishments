# AuraPunishments 1.3.0 - Rollback

1.3.0 migrates the database from schema v2 to **v3**. The migration adds columns, indexes and triggers, and rebuilds the `evidence` and `appeals` tables with every row copied; no record is deleted. Older plugin versions refuse to start on a v3 database (so data is never damaged by a mismatch).

| Situation | What to do |
|---|---|
| Problem **before** anything was issued under 1.3.0 | Stop; put the previous jar back (`AuraPunishments-1.2.1.jar`); restore the newest `plugins/AuraPunishments/backups/punishments.db.pre-v<N>-<timestamp>` over `punishments.db` (N is 3 when you upgraded from 1.1.x/1.2.x; it is 2 when you upgraded straight from 1.0.0, because the copy is named after the first migration step it precedes) (delete `punishments.db-wal` / `-shm`); start. |
| Problem **after** 1.3.0 was used | Rolling back loses what 1.3.0 recorded after the backup: punishments (including jail punishments and case-free evidence/notes/appeals) issued since, and review/Discord queue entries. First export what you need (`SELECT * FROM punishments WHERE issued_at > <migration time>`), restore the backup with the old jar, and re-issue. **Do not roll back while players are jailed through jail punishments** - 1.2.x does not know those records: release them first (`/ap unjail`), or free them in JailReloaded after the rollback. |
| Only the judicial switch | No rollback needed: set `judicial.enabled: true` and `/ap reload`. Nothing was deleted by switching it off. |
| Configuration | `config.yml`, `messages.yml`, `offenses.yml` were never modified by the upgrade. Delete `config-1.3.0-reference.yml` if you do not want it. 1.2.x ignores unknown config keys. |
| JailReloaded | Untouched by the upgrade. Prisoners held for cases stay jailed under either version. |

Always copy `plugins/AuraPunishments/` before upgrading (see [INSTALLATION.md](INSTALLATION.md)).
