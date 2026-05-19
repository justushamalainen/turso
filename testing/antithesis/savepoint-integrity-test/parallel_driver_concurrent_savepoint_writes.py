#!/usr/bin/env -S python3 -u

# 30% of transactions wrap their DML in SAVEPOINT/ROLLBACK TO; the four DML
# shapes (INSERT OR ROLLBACK / UPDATE-of-UNIQUE / INSERT OR IGNORE+DELETE /
# INSERT OR REPLACE) all move autoindex leaf cells, matching the
# `sqlite_autoindex_*_1` corruption signatures.

import turso
from antithesis.random import get_random

# `multiprocess_wal` is required: Antithesis runs sibling OS processes
# concurrently and the default opener takes an fcntl lock that rejects them.
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
