#!/usr/bin/env -S python3 -u

# Same invariant as anytime_validate.py, scheduled by Antithesis in the
# `finally` window — see testing/antithesis/bank-test/ for the same
# three-validator pattern.

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
    "[Finally] integrity_check after concurrent CREATE TABLE + checkpoint",
    {
        "row_count": len(rows),
        "first_row": rows[0][0] if rows else None,
        "rows": [r[0] for r in rows[:10]],
    },
)
