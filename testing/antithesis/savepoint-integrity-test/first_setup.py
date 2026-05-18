#!/usr/bin/env -S python3 -u

# Scenario: regression coverage for the concurrent SAVEPOINT / ROLLBACK TO
# page-corruption surface described in the upstream bug report
# "page-cell corruption under concurrent writes (overlapping cells, missing
# index entries)".
#
# Local reproduction via `turso_whopper --mode ragnarok --max-connections 8`
# hits the failure at ~14% of runs but cannot replay a specific schedule (no
# `--seed`). Antithesis can. The bug-report-author observed that the
# corruption signatures specifically reference `sqlite_autoindex_*_1` rows,
# i.e. the auto-indexes created for UNIQUE columns; the seed schema below
# defines four tables that each include both a PRIMARY KEY rowid alias *and*
# at least one UNIQUE constraint so every parallel write moves an
# auto-index leaf cell.

import turso

try:
    con = turso.connect("savepoint_test.db")
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
