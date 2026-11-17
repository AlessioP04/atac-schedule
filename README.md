# orariAutobus

App Android senza pubblicità per consultare all'istante gli orari settimanali delle proprie fermate preferite (Roma, ATAC e linee Roma TPL), offline.

## Architettura

```
GTFS Roma Mobilità (.zip)  ──►  pipeline/ (Python)  ──►  timetable.db (SQLite)  ──►  android/ (Kotlin + Compose)
                                     ▲                          │
                                     └── GitHub Actions ────────┘  (ogni notte, pubblicato come Release)
```

- Fonte dati: https://romamobilita.it/sites/default/files/rome_static_gtfs.zip
- `pipeline/` legge il GTFS direttamente dallo zip e produce un database compatto, già pronto per l'app.
- `android/` legge il database, gestisce i preferiti e scarica gli aggiornamenti.

## Pipeline

Solo libreria standard, Python ≥ 3.12. Dalla radice del progetto:

```
python pipeline/build_db.py --download          # scarica il feed e crea out/timetable.db(.gz) + manifest.json
python pipeline/query_db.py 74362 --linea 706   # orari di oggi di una fermata, letti dal db
python pipeline/query_db.py --cerca marzabotto  # ricerca fermate
python pipeline/orari_fermata.py 74362          # stessi orari calcolati direttamente dal GTFS (lento, per verifica)
```

| File | Ruolo |
|---|---|
| `pipeline/gtfs.py` | lettura dei CSV dallo zip, parsing di date/orari, download |
| `pipeline/schema.sql` | **contratto** tra pipeline e app: tabelle e query di esempio |
| `pipeline/build_db.py` | GTFS → `timetable.db` deduplicato |
| `pipeline/query_db.py` | interroga il db come farà l'app |

Regole applicate: orari oltre le 24:00 spostati al giorno reale, nessun passaggio al capolinea d'arrivo
(lì si scende soltanto), `pickup_type=1` escluso, secondi scartati, date passate escluse.

## Roadmap

- [x] **Fase 0 – Setup**: repo, struttura, esplorazione del GTFS
- [x] **Fase 1 – Pipeline**: `gtfs.zip → timetable.db`
  - [x] 1.1 Calendario: servizi attivi per data
  - [x] 1.2 Orari per fermata/linea/direzione in un giorno
  - [x] 1.3 Raggruppamento giorni con orari identici (deduplicazione in `day_sets`)
  - [x] 1.4 Scrittura del db compatto + verifica incrociata con `orari_fermata.py`
- [ ] **Fase 2 – App MVP**: ricerca fermata, preferiti, tabella settimanale, APK installabile
- [ ] **Fase 3 – Aggiornamento automatico**: GitHub Actions + download in-app del db
- [ ] **Fase 4 – Rapidità**: widget, scorciatoie, fermate vicine
- [ ] **Fase 5 – (opzionale)**: tempo reale GTFS-RT

## Note sul feed (esplorato il 16/11/2026)

| File | Righe | Note |
|---|---|---|
| `stops.txt` | 8.325 | `stop_code` == `stop_id` == numero sulla palina |
| `routes.txt` | 438 | 429 bus, 5 tram, 4 metro; agenzie ATAC (`OP1`) + altri operatori |
| `trips.txt` | 171.202 | `direction_id` e `trip_headsign` presenti |
| `stop_times.txt` | 5.170.922 | 241 MB non compresso; ~337k orari oltre `24:00:00`; orari con secondi (interpolati dove `timepoint=0`) |
| `calendar_dates.txt` | 4.111 | **nessun `calendar.txt`**: ogni servizio è elencato data per data (solo `exception_type=1`), finestra di ~2 mesi |
