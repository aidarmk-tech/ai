# Local validation

- Python compileall: passed.
- Unit tests: 23/23 passed.
- Migration tested against a copy of `pumpradar.sqlite3 (5).gz`.
- SQLite `PRAGMA integrity_check`: `ok`.
- SQLite `PRAGMA foreign_key_check`: no violations.
- New snapshot horizons verified: 90, 150, 180 and 240 seconds.
- Existing configuration and SQLite data are preserved by the installer backup/rollback flow.
