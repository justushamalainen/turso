#!/usr/bin/env -S python3 -u

# Race CREATE TABLE IF NOT EXISTS against parallel drivers (8-name pool so
# collisions are the point), then INSERT a row and force a checkpoint frame
# via PRAGMA wal_checkpoint. The checkpoint-vs-writer interleaving inside
# `CheckpointStateMachine::SpecialWrite::BTreeCreate` is what we're after.

import turso
from antithesis.random import get_random

try:
    con = turso.connect("schema_leak_test.db")
except Exception as e:
    print(f"Error connecting to database: {e}")
    exit(0)
cur = con.cursor()

# Column count is deterministic per name so every actor that targets `tN`
# agrees on its arity — otherwise INSERT-after-losing-the-CREATE-race fails
# with a parse error before reaching the checkpoint path under test.
table_idx = get_random() % 8
name = f"t{table_idx}"
cols = (table_idx % 4) + 1
defs = ", ".join(f"c{i} INTEGER" for i in range(cols))
try:
    cur.execute(f"CREATE TABLE IF NOT EXISTS {name} ({defs})")
    cur.execute(f"INSERT INTO {name} VALUES ({', '.join('0' for _ in range(cols))})")
    # Fetch the PRAGMA result so the binding actually steps it; without
    # the fetch, the next COMMIT finalizes the statement before it runs.
    result = cur.execute("PRAGMA wal_checkpoint")
    result.fetchone()
    cur.execute("COMMIT")
except Exception as e:
    try:
        cur.execute("ROLLBACK")
    except Exception:
        pass
    print(f"caught: {e}")
