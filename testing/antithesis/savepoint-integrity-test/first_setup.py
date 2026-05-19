#!/usr/bin/env -S python3 -u

# Concurrent SAVEPOINT / ROLLBACK TO scenario targeting the autoindex leaf
# cells that the observed corruption signatures (`sqlite_autoindex_*_1`)
# name. Every seed table has both a PRIMARY KEY rowid alias AND at least
# one UNIQUE constraint so every parallel write moves an autoindex cell.

import turso

# `multiprocess_wal` is required: Antithesis runs sibling OS processes
# concurrently and the default opener takes an fcntl lock that rejects them.
try:
    con = turso.connect("savepoint_test.db", experimental_features="multiprocess_wal")
except Exception as e:
    print(f"Error connecting to database: {e}")
    exit(0)

cur = con.cursor()

TABLE_DDL = [
    """CREATE TABLE IF NOT EXISTS t0 (
            id INTEGER PRIMARY KEY,
            k  TEXT NOT NULL UNIQUE,
            v  INTEGER)""",
    """CREATE TABLE IF NOT EXISTS t1 (
            id INTEGER PRIMARY KEY,
            k  INTEGER NOT NULL UNIQUE,
            v  TEXT)""",
    """CREATE TABLE IF NOT EXISTS t2 (
            id INTEGER PRIMARY KEY,
            k1 TEXT NOT NULL,
            k2 INTEGER NOT NULL,
            UNIQUE (k1, k2))""",
    """CREATE TABLE IF NOT EXISTS t3 (
            id INTEGER PRIMARY KEY,
            k  BLOB NOT NULL UNIQUE,
            v  REAL)""",
]
for ddl in TABLE_DDL:
    cur.execute(ddl)

# Seed each table with rows so workloads have something to update.
for i in range(8):
    cur.execute("INSERT OR IGNORE INTO t0 (k, v) VALUES (?, ?)", (f"k{i}", i))
    cur.execute("INSERT OR IGNORE INTO t1 (k, v) VALUES (?, ?)", (i, f"v{i}"))
    cur.execute("INSERT OR IGNORE INTO t2 (k1, k2) VALUES (?, ?)", (f"a{i}", i))
    cur.execute("INSERT OR IGNORE INTO t3 (k, v) VALUES (?, ?)", (bytes([i]), float(i)))

con.commit()
