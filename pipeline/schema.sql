-- Schema di timetable.db: è il CONTRATTO tra la pipeline (Python) e l'app (Kotlin).
-- Ogni modifica incompatibile richiede di incrementare SCHEMA_VERSION in build_db.py
-- (salvato anche in PRAGMA user_version): l'app rifiuta i database di versione diversa.
--
-- Esempio: orari della fermata 74362 il 20/11/2026, come li chiede l'app
--   SELECT r.name, l.direction, l.headsign, t.times
--   FROM lines l
--   JOIN routes r     ON r.id = l.route_id
--   JOIN schedules s  ON s.line_id = l.id
--   JOIN day_sets d   ON d.id = s.day_set_id AND d.date = 20261120
--   JOIN timetables t ON t.id = s.timetable_id
--   WHERE l.stop_id = '74362';

CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
) WITHOUT ROWID;
-- chiavi: schema_version, generated_at (ISO 8601 UTC), feed_sha256,
--         valid_from, valid_to (AAAAMMGG: primo e ultimo giorno coperti)

CREATE TABLE stops (
    id   TEXT PRIMARY KEY,           -- codice della palina (a Roma stop_id == stop_code)
    name TEXT NOT NULL,
    lat  REAL NOT NULL,
    lon  REAL NOT NULL
) WITHOUT ROWID;

CREATE TABLE routes (
    id     TEXT PRIMARY KEY,         -- route_id GTFS
    name   TEXT NOT NULL,            -- numero come appare sulla palina (route_short_name)
    type   INTEGER NOT NULL,         -- route_type GTFS: 0 tram, 1 metro, 2 treno, 3 bus
    agency TEXT NOT NULL             -- agency_id: OP1 = ATAC, gli altri sono operatori Roma TPL
) WITHOUT ROWID;

-- Una linea, in una direzione, verso un capolinea, a una fermata.
-- Gli id cambiano a ogni build: l'app salva i preferiti per stop_id (+ route_id), mai per lines.id.
CREATE TABLE lines (
    id        INTEGER PRIMARY KEY,
    stop_id   TEXT NOT NULL REFERENCES stops(id),
    route_id  TEXT NOT NULL REFERENCES routes(id),
    direction INTEGER NOT NULL,      -- direction_id GTFS (0/1)
    headsign  TEXT NOT NULL          -- capolinea (trip_headsign)
);
CREATE INDEX lines_by_stop ON lines(stop_id);

-- Liste di orari deduplicate: molte linee/giorni condividono la stessa lista.
CREATE TABLE timetables (
    id    INTEGER PRIMARY KEY,
    times BLOB NOT NULL              -- uint16 little-endian, minuti dalla mezzanotte (0-1439), ordinati
);

-- Insiemi di date deduplicati (es. "tutti i feriali dal 23/11 tranne l'8/12").
CREATE TABLE day_sets (
    id   INTEGER NOT NULL,
    date INTEGER NOT NULL,           -- AAAAMMGG
    PRIMARY KEY (id, date)
) WITHOUT ROWID;
CREATE INDEX day_sets_by_date ON day_sets(date, id);

-- "La linea X, nei giorni dell'insieme Y, passa agli orari Z". Date di giorni diversi della
-- stessa linea non si sovrappongono mai: per una linea e una data esiste al massimo una riga.
CREATE TABLE schedules (
    line_id      INTEGER NOT NULL REFERENCES lines(id),
    day_set_id   INTEGER NOT NULL,
    timetable_id INTEGER NOT NULL REFERENCES timetables(id),
    PRIMARY KEY (line_id, day_set_id)
) WITHOUT ROWID;

-- Percorso "tipico" di ogni linea in una direzione verso un capolinea (la variante con più corse),
-- per la mappa dell'app. Aggiunta compatibile con lo schema 1: le app che non la conoscono la ignorano.
CREATE TABLE patterns (
    route_id  TEXT NOT NULL REFERENCES routes(id),
    direction INTEGER NOT NULL,
    headsign  TEXT NOT NULL,
    stops     TEXT NOT NULL,         -- stop_id in ordine di percorrenza, separati da virgole
    shape     TEXT NOT NULL,         -- tracciato: Encoded Polyline di Google (precisione 1e-5), semplificato a 5 m
    PRIMARY KEY (route_id, direction, headsign)
) WITHOUT ROWID;
