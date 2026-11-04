"""Recreate the views from schema.sql in an existing CorridorIQ history database.

Views hold no data, so this is safe to rerun: it drops every view defined in schema.sql and
creates it again, leaving the tables untouched. Use it after changing a view, or to bring a
database loaded from a bank's exports up to date without regenerating it.

    python3 code/scripts/apply_views.py                    # default database
    python3 code/scripts/apply_views.py --db path/to.db
"""
import argparse
import re
import sqlite3
from contextlib import closing
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parents[2] / "Data" / "corridoriq_history"
VIEW_NAME = re.compile(r"^\s*CREATE\s+VIEW\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)", re.I | re.M)


def view_statements(schema_sql: str):
    """(name, CREATE VIEW statement) for each view in schema order, which respects dependencies."""
    out, buf = [], []
    for line in schema_sql.splitlines(keepends=True):
        buf.append(line)
        stmt = "".join(buf)
        if sqlite3.complete_statement(stmt):
            m = VIEW_NAME.search(stmt)
            if m:
                out.append((m.group(1), stmt.strip()))
            buf = []
    return out


def apply_views(db: Path, schema: Path):
    views = view_statements(schema.read_text())
    with closing(sqlite3.connect(db)) as conn, conn:
        for name, _ in reversed(views):
            conn.execute(f"DROP VIEW IF EXISTS {name}")
        for _, stmt in views:
            conn.execute(stmt)
    return [name for name, _ in views]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", type=Path, default=DATA_DIR / "corridoriq_history.db")
    ap.add_argument("--schema", type=Path, default=DATA_DIR / "schema.sql")
    a = ap.parse_args()
    names = apply_views(a.db, a.schema)
    print(f"Recreated {len(names)} views in {a.db}: {', '.join(names)}")


if __name__ == "__main__":
    main()
