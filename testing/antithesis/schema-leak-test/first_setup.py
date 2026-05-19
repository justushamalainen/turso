#!/usr/bin/env -S python3 -u

# Concurrent CREATE TABLE + INSERT + wal_checkpoint scenario targeting the
# checkpoint-vs-writer interleaving inside `pager.btree_create`. No fixture
# rows; the parallel drivers race CREATE TABLE collisions themselves.

import turso

try:
    con = turso.connect("schema_leak_test.db")
except Exception as e:
    print(f"Error connecting to database: {e}")
    exit(0)

cur = con.cursor()

# Turso's Python binding only steps a row-returning statement when its rows
# are fetched, so `PRAGMA journal_mode` must be fetched or it silently no-ops.
result = cur.execute("PRAGMA journal_mode = wal")
row = result.fetchone()
print(f"journal_mode after setup: {row}")

con.commit()
