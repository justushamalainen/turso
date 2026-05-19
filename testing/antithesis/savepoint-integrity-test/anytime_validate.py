#!/usr/bin/env -S python3 -u

# Single invariant for the savepoint-integrity scenario: at every observation
# point, `PRAGMA integrity_check` returns exactly one row with the value "ok".
# The corruption signatures the original campaign captured all show up here
# as multi-row diagnostics (e.g. "Cell N in page M is out of range", "row N
# missing from index sqlite_autoindex_*_1").

import turso
from antithesis.assertions import always

# IMPORTANT: enable the multiprocess WAL path. Antithesis launches multiple
# OS-level copies of `parallel_driver_*` concurrently and the default opener
# takes an fcntl lock that rejects a second process; see core test
# `database_open_without_experimental_multiprocess_wal_rejects_second_process`
# in core/multiprocess_tests.rs and the plumbing at sdk-kit/src/rsapi.rs
# (~lines 644-661). Without this, overlapping driver/validator processes hit
# a lock at open and silently exit through the `except` below, skipping the
# concurrent savepoint writes the scenario is meant to exercise.
try:
    con = turso.connect("savepoint_test.db", experimental_features="multiprocess_wal")
except Exception as e:
    print(f"Error connecting to database: {e}")
    exit(0)

cur = con.cursor()
rows = cur.execute("PRAGMA integrity_check").fetchall()
clean = len(rows) == 1 and rows[0][0] == "ok"

always(
    clean,
    "[Anytime] PRAGMA integrity_check returns 'ok'",
    {
        "row_count": len(rows),
        "first_row": rows[0][0] if rows else None,
        # Cap the log payload — a corrupted DB can produce hundreds of rows.
        "rows": [r[0] for r in rows[:10]],
    },
)
