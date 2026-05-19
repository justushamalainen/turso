#!/usr/bin/env -S python3 -u

# Single invariant for the schema-leak scenario: at every observation point,
# `PRAGMA integrity_check` returns exactly one row with the value "ok".
# Page leaks under concurrent DDL+checkpoint show up here as "Page N never
# used" diagnostic rows; cell-level corruption shows up as "Cell N in page M
# is out of range" etc. Either failure mode trips the `always` assertion.

import turso
from antithesis.assertions import always

try:
    con = turso.connect("schema_leak_test.db")
except Exception as e:
    print(f"Error connecting to database: {e}")
    exit(0)

cur = con.cursor()
rows = cur.execute("PRAGMA integrity_check").fetchall()
clean = len(rows) == 1 and rows[0][0] == "ok"

always(
    clean,
    "[Anytime] integrity_check after concurrent CREATE TABLE + checkpoint",
    {
        "row_count": len(rows),
        "first_row": rows[0][0] if rows else None,
        # Cap the log payload — a corrupted DB can produce hundreds of rows.
        "rows": [r[0] for r in rows[:10]],
    },
)
