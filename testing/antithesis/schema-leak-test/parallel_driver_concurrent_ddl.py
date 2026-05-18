#!/usr/bin/env -S python3 -u

# Workload from `~/runs/turso-bugs/04-concurrent-create-table-page-leak.md`,
# "Proposed Antithesis test case" section.
#
# Each invocation:
#  - picks a name from a small pool so CREATE TABLE collisions are the point
#  - races CREATE TABLE IF NOT EXISTS against other parallel drivers
#  - if the table was actually created (or already existed), immediately
#    INSERTs a row and forces a checkpoint frame via PRAGMA wal_checkpoint
#
# The bug surface is the checkpoint-vs-writer interleaving, not the CREATE
# TABLE conflict itself: under MVCC the lossing CREATE TABLE attempts do not
# allocate pages, so the leak we observed must come from the checkpoint path
# that *does* allocate (CheckpointStateMachine::SpecialWrite::BTreeCreate).

import turso
from antithesis.random import get_random

try:
    con = turso.connect("schema_leak_test.db")
except Exception as e:
    print(f"Error connecting to database: {e}")
    exit(0)
cur = con.cursor()

# The column count is deterministic per name (1..4) so that all parallel
# actors targeting the same table agree on its arity. Without this, an
# actor that loses the CREATE TABLE race would try to INSERT a row with a
# different column count than the table that actually exists, and the
# resulting "table tN has X columns but Y values were supplied" parse error
# would mask the bug surface we are actually trying to exercise (the
# checkpoint-vs-writer interleaving inside `PRAGMA wal_checkpoint`).
table_idx = get_random() % 8
name = f"t{table_idx}"
cols = (table_idx % 4) + 1
defs = ", ".join(f"c{i} INTEGER" for i in range(cols))
try:
    cur.execute(f"CREATE TABLE IF NOT EXISTS {name} ({defs})")
    cur.execute(f"INSERT INTO {name} VALUES ({', '.join('0' for _ in range(cols))})")
    cur.execute("PRAGMA wal_checkpoint")
    cur.execute("COMMIT")
except Exception as e:
    try:
        cur.execute("ROLLBACK")
    except Exception:
        pass
    # Swallow on purpose: the invariant we care about is integrity_check,
    # not whether each individual statement succeeded.
    print(f"caught: {e}")
