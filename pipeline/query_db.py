"""Interroga timetable.db con le stesse query che userà l'app.

Esempi (dalla radice del progetto):
    python pipeline/query_db.py 74362                     # orari di oggi
    python pipeline/query_db.py 74362 --data 2026-11-22 --linea 706
    python pipeline/query_db.py --cerca marzabotto        # ricerca fermate per nome
"""

import argparse
import sqlite3
import sys
from array import array
from collections import defaultdict
from datetime import date
from pathlib import Path

from build_db import DB_NAME, OUT_DIR, SCHEMA_VERSION
from gtfs import utf8_stdout

STOP_TIMETABLE = """
    SELECT r.name, l.route_id, l.direction, l.headsign, t.times
    FROM lines l
    JOIN routes r     ON r.id = l.route_id
    JOIN schedules s  ON s.line_id = l.id
    JOIN day_sets d   ON d.id = s.day_set_id AND d.date = :date
    JOIN timetables t ON t.id = s.timetable_id
    WHERE l.stop_id = :stop AND (:route IS NULL OR l.route_id = :route)
"""

SEARCH_STOPS = "SELECT id, name FROM stops WHERE id = :q OR name LIKE '%' || :q || '%' ORDER BY name LIMIT 30"


def decode_times(blob: bytes) -> list[int]:
    times = array("H")
    times.frombytes(blob)
    if sys.byteorder == "big":
        times.byteswap()
    return times.tolist()


def connect(path: Path) -> sqlite3.Connection:
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    if (version := con.execute("PRAGMA user_version").fetchone()[0]) != SCHEMA_VERSION:
        raise SystemExit(f"Schema v{version}, atteso v{SCHEMA_VERSION}: ricostruisci il database")
    return con


def stop_timetable(con: sqlite3.Connection, stop_id: str, day: date, route_id: str | None = None) -> dict[tuple[str, str, int, str], list[int]]:
    """(nome linea, route_id, direzione, capolinea) -> minuti dalla mezzanotte."""
    params = {"stop": stop_id, "date": int(day.strftime("%Y%m%d")), "route": route_id}
    return {(name, rid, direction, headsign): decode_times(blob)
            for name, rid, direction, headsign, blob in con.execute(STOP_TIMETABLE, params)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("fermata", nargs="?", help="codice della palina, es. 74362")
    parser.add_argument("--data", type=date.fromisoformat, default=date.today(), help="AAAA-MM-GG (default: oggi)")
    parser.add_argument("--linea", help="route_id, es. 706")
    parser.add_argument("--cerca", help="cerca fermate per nome o codice")
    parser.add_argument("--db", type=Path, default=OUT_DIR / DB_NAME)
    args = parser.parse_args()
    utf8_stdout()

    con = connect(args.db)
    if args.cerca:
        for stop_id, name in con.execute(SEARCH_STOPS, {"q": args.cerca}):
            print(f"{stop_id:>6}  {name}")
        return
    if not args.fermata:
        parser.error("indica una fermata oppure --cerca")

    name = con.execute("SELECT name FROM stops WHERE id = ?", (args.fermata,)).fetchone()
    print(f"Fermata {args.fermata} – {name[0] if name else '?'}  ({args.data:%d/%m/%Y})")
    table = stop_timetable(con, args.fermata, args.data, args.linea)
    if not table:
        valid = dict(con.execute("SELECT key, value FROM meta WHERE key LIKE 'valid_%'"))
        print(f"  nessun passaggio (dati validi dal {valid['valid_from']} al {valid['valid_to']})")
    for (route_name, _, direction, headsign), times in sorted(table.items()):
        print(f"\n  {route_name} → {headsign}  (dir. {direction}, {len(times)} corse)")
        by_hour: dict[int, list[int]] = defaultdict(list)
        for t in times:
            by_hour[t // 60].append(t % 60)
        for hour, minutes in by_hour.items():
            print(f"    {hour:02d} | " + " ".join(f"{m:02d}" for m in minutes))


if __name__ == "__main__":
    main()
