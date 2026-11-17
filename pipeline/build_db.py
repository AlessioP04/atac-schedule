"""Costruisce il database degli orari letto dall'app, a partire dal GTFS di Roma Mobilità.

Esempi (dalla radice del progetto):
    python pipeline/build_db.py               # usa data/rome_static_gtfs.zip
    python pipeline/build_db.py --download    # scarica prima il feed più recente

Produce in out/:
    timetable.db      database SQLite (schema e query di esempio in pipeline/schema.sql)
    timetable.db.gz   lo stesso, compresso: è il file che scarica l'app
    manifest.json     versione, validità e hash: l'app lo legge per sapere se aggiornarsi
"""

import argparse
import gzip
import hashlib
import json
import shutil
import sqlite3
import sys
import time
import zipfile
from array import array
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

from gtfs import DEFAULT_GTFS, MINUTES_PER_DAY, ROOT, download, open_csv, parse_date, parse_time, utf8_stdout

SCHEMA_VERSION = 1
SCHEMA_FILE = Path(__file__).with_name("schema.sql")
OUT_DIR = ROOT / "out"
DB_NAME = "timetable.db"


class Interner[T]:
    """Assegna un id progressivo (da 1) a ogni valore distinto: è il cuore della deduplicazione."""

    def __init__(self) -> None:
        self.ids: dict[T, int] = {}

    def __call__(self, value: T) -> int:
        return self.ids.setdefault(value, len(self.ids) + 1)

    def __len__(self) -> int:
        return len(self.ids)


@dataclass(frozen=True, slots=True)
class Trip:
    route_id: str
    direction: int
    headsign: str
    service_id: str


type LineKey = tuple[str, str, int, str]  # (stop_id, route_id, direction, headsign)


@dataclass
class Feed:
    """Tutto ciò che finisce nel database, già deduplicato."""

    valid_dates: frozenset[date]
    stops: dict[str, tuple[str, float, float]]
    routes: dict[str, tuple[str, int, str]]
    lines: Interner[LineKey] = field(default_factory=Interner)
    timetables: Interner[bytes] = field(default_factory=Interner)
    day_sets: Interner[frozenset[date]] = field(default_factory=Interner)
    schedules: list[tuple[int, int, int]] = field(default_factory=list)  # (line, day_set, timetable)


# --------------------------------------------------------------------------- #
# 1. Calendario: in quali date è attivo ogni servizio
# --------------------------------------------------------------------------- #

def load_calendar(gtfs: zipfile.ZipFile, start: date) -> dict[str, set[date]]:
    """service_id -> date attive da `start` in poi.

    Roma usa solo calendar_dates.txt, ma gestiamo anche calendar.txt (regole settimanali):
    se un giorno Roma Mobilità cambia formato, la pipeline continua a funzionare.
    """
    services: dict[str, set[date]] = defaultdict(set)

    if "calendar.txt" in gtfs.namelist():
        weekdays = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
        with open_csv(gtfs, "calendar.txt") as (col, rows):
            for row in rows:
                active = [row[col[w]] == "1" for w in weekdays]
                d = max(parse_date(row[col["start_date"]]), start)
                end = parse_date(row[col["end_date"]])
                while d <= end:
                    if active[d.weekday()]:
                        services[row[col["service_id"]]].add(d)
                    d += timedelta(days=1)

    with open_csv(gtfs, "calendar_dates.txt") as (col, rows):
        for row in rows:
            d = parse_date(row[col["date"]])
            if d < start:
                continue
            if row[col["exception_type"]] == "1":    # servizio aggiunto
                services[row[col["service_id"]]].add(d)
            else:                                    # "2": servizio soppresso
                services[row[col["service_id"]]].discard(d)

    return {sid: dates for sid, dates in services.items() if dates}


# --------------------------------------------------------------------------- #
# 2. Anagrafiche: corse, fermate, linee
# --------------------------------------------------------------------------- #

def load_trips(gtfs: zipfile.ZipFile, services: dict[str, set[date]]) -> dict[str, Trip]:
    """Solo le corse con almeno una data attiva."""
    trips = {}
    with open_csv(gtfs, "trips.txt") as (col, rows):
        for row in rows:
            if row[col["service_id"]] in services:
                trips[row[col["trip_id"]]] = Trip(
                    route_id=row[col["route_id"]],
                    direction=int(row[col["direction_id"]] or 0),
                    headsign=row[col["trip_headsign"]].strip(),
                    service_id=row[col["service_id"]],
                )
    return trips


def load_stops(gtfs: zipfile.ZipFile) -> dict[str, tuple[str, float, float]]:
    with open_csv(gtfs, "stops.txt") as (col, rows):
        return {
            row[col["stop_id"]]: (row[col["stop_name"]].strip(), float(row[col["stop_lat"]]), float(row[col["stop_lon"]]))
            for row in rows
        }


def load_routes(gtfs: zipfile.ZipFile) -> dict[str, tuple[str, int, str]]:
    with open_csv(gtfs, "routes.txt") as (col, rows):
        return {
            row[col["route_id"]]: (row[col["route_short_name"]] or row[col["route_id"]], int(row[col["route_type"]]), row[col["agency_id"]])
            for row in rows
        }


# --------------------------------------------------------------------------- #
# 3. Passaggi: una sola lettura di stop_times.txt per tutte le fermate
# --------------------------------------------------------------------------- #

def collect_departures(
    gtfs: zipfile.ZipFile, feed: Feed, trips: dict[str, Trip], services: dict[str, set[date]]
) -> tuple[dict[int, dict[int, array]], list[frozenset[date]]]:
    """Raggruppa ogni passaggio per linea e per "insieme di date reali".

    Una corsa del servizio S alle 25:10 passa alle 01:10 del giorno DOPO ogni data di S:
    per questo l'insieme di date dipende da (servizio, giorni di scarto). Gli insiemi sono pochi
    e vengono calcolati una volta sola (cache `shifted`).

    Restituisce:
        departures[line_id][shifted_id] = minuti (array compatto di uint16)
        shifted_dates[shifted_id]       = date reali in cui valgono quei minuti
    """
    shifted: dict[tuple[str, int], int] = {}
    shifted_dates: list[frozenset[date]] = []
    departures: dict[int, dict[int, array]] = defaultdict(lambda: defaultdict(lambda: array("H")))

    def add_trip(trip: Trip, stops: list[tuple[int, str, int, str]]) -> None:
        stops.sort()  # per stop_sequence
        for _, stop_id, minutes, pickup in stops[:-1]:  # al capolinea d'arrivo si scende soltanto
            if pickup == "1":                            # 1 = salita non consentita
                continue
            day_offset, minute = divmod(minutes, MINUTES_PER_DAY)
            key = (trip.service_id, day_offset)
            if (sid := shifted.get(key)) is None:
                dates = frozenset(d + timedelta(days=day_offset) for d in services[trip.service_id])
                sid = shifted[key] = len(shifted_dates)
                shifted_dates.append(dates & feed.valid_dates)
            if shifted_dates[sid]:
                line_id = feed.lines((stop_id, trip.route_id, trip.direction, trip.headsign))
                departures[line_id][sid].append(minute)

    with open_csv(gtfs, "stop_times.txt") as (col, rows):
        i_trip, i_stop, i_seq, i_dep = col["trip_id"], col["stop_id"], col["stop_sequence"], col["departure_time"]
        i_pickup = col.get("pickup_type")
        current_trip: Trip | None = None
        current_id = ""
        buffer: list[tuple[int, str, int, str]] = []
        seen: set[str] = set()

        for row in rows:
            trip_id = row[i_trip]
            if trip_id != current_id:
                if current_trip is not None:
                    add_trip(current_trip, buffer)
                if trip_id in seen:
                    raise ValueError(f"stop_times.txt non è raggruppato per trip_id (rivisto {trip_id})")
                seen.add(trip_id)
                current_id, current_trip, buffer = trip_id, trips.get(trip_id), []
            if current_trip is not None and row[i_dep]:
                pickup = row[i_pickup] if i_pickup is not None else ""
                buffer.append((int(row[i_seq]), row[i_stop], parse_time(row[i_dep]), pickup))
        if current_trip is not None:
            add_trip(current_trip, buffer)

    return departures, shifted_dates


def encode_times(minutes: list[int]) -> bytes:
    times = array("H", minutes)
    if sys.byteorder == "big":
        times.byteswap()
    return times.tobytes()


def build_schedules(feed: Feed, departures: dict[int, dict[int, array]], shifted_dates: list[frozenset[date]]) -> None:
    """Per ogni linea: orari di ogni data, poi raggruppa le date con orari identici."""
    for line_id, by_dates in departures.items():
        per_date: dict[date, list[int]] = defaultdict(list)
        for sid, minutes in by_dates.items():
            for d in shifted_dates[sid]:
                per_date[d].extend(minutes)

        dates_by_timetable: dict[bytes, list[date]] = defaultdict(list)
        for d, minutes in per_date.items():
            dates_by_timetable[encode_times(sorted(minutes))].append(d)

        for blob, dates in dates_by_timetable.items():
            feed.schedules.append((line_id, feed.day_sets(frozenset(dates)), feed.timetables(blob)))


# --------------------------------------------------------------------------- #
# 4. Scrittura
# --------------------------------------------------------------------------- #

def yyyymmdd(d: date) -> int:
    return d.year * 10000 + d.month * 100 + d.day


def write_db(feed: Feed, path: Path, feed_sha256: str) -> None:
    path.unlink(missing_ok=True)
    used_stops = {stop_id for stop_id, *_ in feed.lines.ids}
    used_routes = {route_id for _, route_id, *_ in feed.lines.ids}
    meta = {
        "schema_version": str(SCHEMA_VERSION),
        "generated_at": datetime.now(UTC).isoformat(timespec="seconds"),
        "feed_sha256": feed_sha256,
        "valid_from": str(yyyymmdd(min(feed.valid_dates))),
        "valid_to": str(yyyymmdd(max(feed.valid_dates))),
    }

    con = sqlite3.connect(path)
    try:
        con.executescript(SCHEMA_FILE.read_text(encoding="utf-8"))
        with con:  # un'unica transazione: molto più veloce di un commit per riga
            con.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
            con.executemany("INSERT INTO meta VALUES (?, ?)", meta.items())
            con.executemany("INSERT INTO stops VALUES (?, ?, ?, ?)",
                            ((sid, *feed.stops[sid]) for sid in sorted(used_stops)))
            con.executemany("INSERT INTO routes VALUES (?, ?, ?, ?)",
                            ((rid, *feed.routes[rid]) for rid in sorted(used_routes)))
            con.executemany("INSERT INTO lines VALUES (?, ?, ?, ?, ?)",
                            ((lid, *key) for key, lid in feed.lines.ids.items()))
            con.executemany("INSERT INTO timetables VALUES (?, ?)",
                            ((tid, blob) for blob, tid in feed.timetables.ids.items()))
            con.executemany("INSERT INTO day_sets VALUES (?, ?)",
                            ((did, yyyymmdd(d)) for dates, did in feed.day_sets.ids.items() for d in dates))
            con.executemany("INSERT INTO schedules VALUES (?, ?, ?)", feed.schedules)
        con.execute("VACUUM")
    finally:
        con.close()


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def write_release_files(db_path: Path) -> dict:
    """Comprime il database e scrive il manifest che l'app controlla per gli aggiornamenti."""
    gz_path = db_path.with_name(db_path.name + ".gz")
    with db_path.open("rb") as src, gzip.open(gz_path, "wb", compresslevel=9) as dst:
        shutil.copyfileobj(src, dst)

    con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    meta = dict(con.execute("SELECT key, value FROM meta"))
    con.close()

    manifest = {
        "schema_version": SCHEMA_VERSION,
        "generated_at": meta["generated_at"],
        "feed_sha256": meta["feed_sha256"],  # l'app riscarica solo se il feed è cambiato
        "valid_from": meta["valid_from"],
        "valid_to": meta["valid_to"],
        "file": gz_path.name,
        "size": gz_path.stat().st_size,
        "sha256": sha256(gz_path),
    }
    (db_path.parent / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest


# --------------------------------------------------------------------------- #

def build(gtfs_path: Path, out_dir: Path) -> dict:
    t0 = time.perf_counter()

    def step(msg: str) -> None:
        print(f"[{time.perf_counter() - t0:5.1f}s] {msg}", flush=True)

    # Da ieri (in UTC): così l'orario di Roma, avanti di 1-2 ore, è sempre coperto.
    start = datetime.now(UTC).date() - timedelta(days=1)

    with zipfile.ZipFile(gtfs_path) as gtfs:
        # Si caricano anche i servizi del giorno prima di `start`: le loro corse dopo mezzanotte cadono in `start`.
        services = load_calendar(gtfs, start - timedelta(days=1))
        valid_dates = frozenset(d for dates in services.values() for d in dates if d >= start)
        if not valid_dates:
            raise SystemExit("Il feed non contiene date future: è scaduto?")
        step(f"calendario: {len(services)} servizi, {min(valid_dates)} → {max(valid_dates)}")

        feed = Feed(valid_dates=valid_dates, stops=load_stops(gtfs), routes=load_routes(gtfs))
        trips = load_trips(gtfs, services)
        step(f"anagrafiche: {len(feed.stops)} fermate, {len(feed.routes)} linee, {len(trips)} corse attive")

        departures, shifted_dates = collect_departures(gtfs, feed, trips, services)
        step(f"stop_times letto: {len(feed.lines)} linee-fermata")

    build_schedules(feed, departures, shifted_dates)
    step(f"deduplicazione: {len(feed.schedules)} orari, {len(feed.timetables)} tabelle distinte, "
         f"{len(feed.day_sets)} insiemi di date")

    out_dir.mkdir(parents=True, exist_ok=True)
    db_path = out_dir / DB_NAME
    write_db(feed, db_path, sha256(gtfs_path))
    manifest = write_release_files(db_path)
    step(f"scritto {db_path.name}: {db_path.stat().st_size / 1e6:.1f} MB "
         f"({manifest['file']}: {manifest['size'] / 1e6:.1f} MB)")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--gtfs", type=Path, default=DEFAULT_GTFS, help="percorso dello zip GTFS")
    parser.add_argument("--out", type=Path, default=OUT_DIR, help="cartella di output (default: out/)")
    parser.add_argument("--download", action="store_true", help="scarica prima il feed più recente")
    args = parser.parse_args()
    utf8_stdout()

    if args.download:
        print(f"Scarico il feed in {args.gtfs} ...", flush=True)
        download(args.gtfs)
    build(args.gtfs, args.out)


if __name__ == "__main__":
    main()
