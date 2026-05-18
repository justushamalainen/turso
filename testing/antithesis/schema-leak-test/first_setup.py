#!/usr/bin/env -S python3 -u

# Scenario: regression coverage for the concurrent CREATE TABLE page-leak bug.
#
# Background: a 30-minute `turso_stress --tx-mode concurrent` campaign hit a
# post-run `PRAGMA integrity_check` failure roughly once in 375 runs: pages
# allocated during the workload were never linked into any b-tree. The local
# stress runner cannot replay a specific schedule (no --seed); Antithesis can.
# The investigation note attached to that bug report ruled out the *failed*
# CREATE-TABLE codepath (MVCC short-circuits page allocation in that case)
# and pointed at the checkpoint-vs-writer interleaving instead. This scenario
# stresses CREATE TABLE + INSERT + wal_checkpoint from many parallel drivers
# and asserts integrity_check at every observation point.

import turso

try:
    con = turso.connect("schema_leak_test.db")
except Exception as e:
    print(f"Error connecting to database: {e}")
    exit(0)

cur = con.cursor()

# No fixture rows are needed; the parallel drivers will themselves race
# CREATE TABLE / INSERT / PRAGMA wal_checkpoint. We open the connection here
# so the database file exists before the parallel drivers race for it.
cur.execute("PRAGMA journal_mode = wal")

con.commit()
