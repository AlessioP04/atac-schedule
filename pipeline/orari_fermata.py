"""Orari settimanali di una fermata, letti direttamente dal GTFS di Roma Mobilità.

Esempi (dalla radice del progetto):
    python pipeline/orari_fermata.py 74362 --linea 706
    python pipeline/orari_fermata.py 74362 --dal 2026-11-21 --giorni 2
"""

import argparse
import zipfile
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path

from gtfs import DEFAULT_GTFS, MINUTES_PER_DAY as MINUTI_GIORNO, open_csv, parse_time, utf8_stdout

GIORNI = ["lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica"]


# --------------------------------------------------------------------------- #
# Lettura del GTFS
# --------------------------------------------------------------------------- #


@dataclass(frozen=True, slots=True)
class Trip:
    route_id: str
    service_id: str
    headsign: str
    direction_id: str


def stop_name(gtfs: zipfile.ZipFile, stop_id: str) -> str:
    with open_csv(gtfs, "stops.txt") as (col, rows):
        for row in rows:
            if row[col["stop_id"]] == stop_id:
                return row[col["stop_name"]]
    raise SystemExit(f"Fermata {stop_id} non trovata in stops.txt")


def route_names(gtfs: zipfile.ZipFile) -> dict[str, str]:
    """route_id -> numero della linea come appare sulla palina."""
    with open_csv(gtfs, "routes.txt") as (col, rows):
        return {row[col["route_id"]]: row[col["route_short_name"]] for row in rows}


def service_dates(gtfs: zipfile.ZipFile, window: set[date]) -> dict[str, set[date]]:
    """service_id -> date (dentro `window`) in cui il servizio è attivo."""
    result: dict[str, set[date]] = defaultdict(set)
    wanted = {d.strftime("%Y%m%d"): d for d in window}
    with open_csv(gtfs, "calendar_dates.txt") as (col, rows):
        for row in rows:
            d = wanted.get(row[col["date"]])
            if d is None:
                continue
            if row[col["exception_type"]] == "1":
                result[row[col["service_id"]]].add(d)
            else:  # "2" = servizio rimosso quel giorno (oggi Roma non lo usa, ma lo prevede lo standard)
                result[row[col["service_id"]]].discard(d)
    return result


def load_trips(gtfs: zipfile.ZipFile, services: dict[str, set[date]], route_id: str | None) -> dict[str, Trip]:
    """Corse attive nella finestra di date, eventualmente di una sola linea."""
    trips: dict[str, Trip] = {}
    with open_csv(gtfs, "trips.txt") as (col, rows):
        for row in rows:
            if route_id is not None and row[col["route_id"]] != route_id:
                continue
            if not services.get(row[col["service_id"]]):
                continue
            trips[row[col["trip_id"]]] = Trip(
                route_id=row[col["route_id"]],
                service_id=row[col["service_id"]],
                headsign=row[col["trip_headsign"]],
                direction_id=row[col["direction_id"]],
            )
    return trips


def stop_departures(gtfs: zipfile.ZipFile, stop_id: str, trips: dict[str, Trip]) -> list[tuple[str, int]]:
    """(trip_id, minuti) di ogni partenza dalla fermata. È l'unico passaggio lento: 5 milioni di righe."""
    found = []
    with open_csv(gtfs, "stop_times.txt") as (col, rows):
        i_stop, i_trip, i_dep = col["stop_id"], col["trip_id"], col["departure_time"]
        i_pickup = col.get("pickup_type")
        for row in rows:
            if row[i_stop] != stop_id or row[i_trip] not in trips:
                continue
            if i_pickup is not None and row[i_pickup] == "1":  # 1 = non si può salire
                continue
            found.append((row[i_trip], parse_time(row[i_dep])))
    return found


# --------------------------------------------------------------------------- #
# Tabella oraria
# --------------------------------------------------------------------------- #

type LineKey = tuple[str, str]           # (route_id, headsign)
type Timetable = dict[date, dict[LineKey, list[int]]]


def build_timetable(gtfs_path: Path, stop_id: str, route_id: str | None, start: date, days: int) -> tuple[str, Timetable]:
    window = [start + timedelta(days=i) for i in range(days)]
    # Serve anche il giorno prima: una corsa delle 24:30 di domenica passa lunedì alle 00:30.
    service_window = {start - timedelta(days=1), *window}

    with zipfile.ZipFile(gtfs_path) as gtfs:
        name = stop_name(gtfs, stop_id)
        services = service_dates(gtfs, service_window)
        trips = load_trips(gtfs, services, route_id)
        departures = stop_departures(gtfs, stop_id, trips)

    table: Timetable = {d: defaultdict(list) for d in window}
    for trip_id, minutes in departures:
        trip = trips[trip_id]
        for service_day in services[trip.service_id]:
            real_day = service_day + timedelta(days=minutes // MINUTI_GIORNO)
            if real_day in table:
                table[real_day][(trip.route_id, trip.headsign)].append(minutes % MINUTI_GIORNO)

    for by_line in table.values():
        for times in by_line.values():
            times.sort()
    return name, table


def print_timetable(stop_id: str, name: str, table: Timetable, routes: dict[str, str]) -> None:
    print(f"Fermata {stop_id} – {name}")
    for day, by_line in table.items():
        print(f"\n{GIORNI[day.weekday()]} {day:%d/%m}")
        if not by_line:
            print("  nessun passaggio")
        for (route_id, headsign), times in sorted(by_line.items()):
            print(f"  {routes.get(route_id, route_id)} → {headsign}  ({len(times)} corse)")
            by_hour: dict[int, list[int]] = defaultdict(list)
            for t in times:
                by_hour[t // 60].append(t % 60)
            for hour, minutes in by_hour.items():
                print(f"    {hour:02d} | " + " ".join(f"{m:02d}" for m in minutes))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("fermata", help="codice della palina (stop_id), es. 74362")
    parser.add_argument("--linea", help="route_id, es. 706 (default: tutte le linee)")
    parser.add_argument("--dal", type=date.fromisoformat, default=date.today(), help="data iniziale AAAA-MM-GG (default: oggi)")
    parser.add_argument("--giorni", type=int, default=7, help="quanti giorni mostrare (default: 7)")
    parser.add_argument("--gtfs", type=Path, default=DEFAULT_GTFS, help="percorso dello zip GTFS")
    args = parser.parse_args()
    utf8_stdout()

    name, table = build_timetable(args.gtfs, args.fermata, args.linea, args.dal, args.giorni)
    with zipfile.ZipFile(args.gtfs) as gtfs:
        routes = route_names(gtfs)
    print_timetable(args.fermata, name, table, routes)


if __name__ == "__main__":
    main()
