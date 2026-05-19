#!/usr/bin/env -S python3 -u

# Workload from `~/runs/turso-bugs/06-page-corruption-under-concurrent-writes.md`,
# "Proposed Antithesis test case" section.
#
# Each invocation:
#  - opens a connection and starts a transaction (BEGIN)
#  - with 30% probability, opens a SAVEPOINT sp1
#  - runs one of four DML shapes against a UNIQUE-indexed table:
#       op 0: INSERT OR ROLLBACK INTO t0 (forces conflict on autoindex)
#       op 1: UPDATE t1 with a randomized new value for its UNIQUE column
#       op 2: INSERT OR IGNORE INTO t2 followed by a DELETE
#       op 3: INSERT OR REPLACE INTO t3 (BLOB UNIQUE key)
#  - if a savepoint was opened: ROLLBACK TO sp1 (40%) or RELEASE sp1 (60%)
#  - COMMITs (or ROLLBACK on caught exception)
#
# The bug surface this targets is the autoindex-leaf-cell movement under
# concurrent SAVEPOINT/ROLLBACK TO + DML — the corruption signatures in
# the original report all name `sqlite_autoindex_*_1`.

import turso
from antithesis.random import get_random

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

op_kind = get_random() % 4
use_sp = (get_random() % 10) < 3  # 30% probability
rollback = (get_random() % 10) < 4  # 40% of savepoints end in ROLLBACK TO
key_id = get_random() % 32

try:
    cur.execute("BEGIN")
    if use_sp:
        cur.execute("SAVEPOINT sp1")

    if op_kind == 0:
        cur.execute(
            "INSERT OR ROLLBACK INTO t0 (k, v) VALUES (?, ?)",
            (f"k{key_id}", get_random() % 1000),
        )
    elif op_kind == 1:
        new_k = get_random() % 1000
        cur.execute(
            "UPDATE t1 SET k = ? WHERE id = (SELECT id FROM t1 LIMIT 1)",
            (new_k,),
        )
    elif op_kind == 2:
        cur.execute(
            "INSERT OR IGNORE INTO t2 (k1, k2) VALUES (?, ?)",
            (f"a{key_id}", get_random() % 1000),
        )
        cur.execute("DELETE FROM t2 WHERE id = (SELECT id FROM t2 LIMIT 1)")
    else:
        cur.execute(
            "INSERT OR REPLACE INTO t3 (k, v) VALUES (?, ?)",
            (bytes([key_id & 0xFF]), (get_random() % 100) / 7.0),
        )

    if use_sp and rollback:
        cur.execute("ROLLBACK TO sp1")
    elif use_sp:
        cur.execute("RELEASE sp1")

    cur.execute("COMMIT")
except Exception as e:
    try:
        cur.execute("ROLLBACK")
    except Exception:
        pass
    # Swallow on purpose: the invariant we care about is integrity_check,
    # not whether each specific statement succeeded.
    print(f"workload caught: {e}")
