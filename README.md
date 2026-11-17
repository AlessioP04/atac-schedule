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

## App Android

Kotlin + Jetpack Compose, nessuna dipendenza oltre ad AndroidX. Package `io.github.alessiop04.atacschedule`.

```
python pipeline/build_db.py              # il db in out/ viene incluso nell'APK (facoltativo)
cd android && ./gradlew assembleDebug    # APK in android/app/build/outputs/apk/debug/
./gradlew testDebugUnitTest              # test della logica orari
```

- **Home**: le linee salvate, ognuna con un nome breve "da → a" (es. *Spinaceto → Eur*) e i prossimi passaggi.
- **Aggiungi**: cerca la fermata per nome o numero di palina, spunta le linee, dai un nome e salva.
- **Orari**: di base i feriali (5 giorni da oggi), poi Sabato, Domenica o un giorno qualsiasi.
  Gli orari dei feriali sono uniti in un'unica tabella: le corse che non ci sono tutti i giorni
  sono in colore con il giorno accanto (`lun` = solo lunedì, `no ven` = tutti tranne venerdì).
  I giorni con un orario completamente diverso (es. festivi infrasettimanali) si aprono a parte.
- **Aggiornamenti**: una volta al giorno (WorkManager) e all'avvio l'app legge `manifest.json`
  dalla release `db` e scarica il nuovo database solo se il feed è cambiato.

| Cartella | Ruolo |
|---|---|
| `domain/` | logica pura (testata): raggruppamento dei feriali, giorni speciali, formattazione |
| `data/` | lettura di `timetable.db`, preferiti (`favorites.json`), aggiornamento dalla release |
| `ui/` | schermate Compose |

Con una settimana normale il 55% delle linee ha orari identici dal lunedì al venerdì e per le altre la
differenza mediana è di 0 corse (90° percentile: 5, soprattutto corse notturne del lunedì): per questo la vista
unica dei feriali con le eccezioni segnate resta compatta.

## Automazione (GitHub Actions)

- `update-db.yml`: ogni notte ricostruisce il db e, se il feed è cambiato, lo pubblica nella release
  [`db`](https://github.com/AlessioP04/atac-schedule/releases/tag/db) (`timetable.db.gz` + `manifest.json`).
- `android.yml`: test + APK di debug (come artifact) a ogni modifica di `android/`.

## Roadmap

- [x] **Fase 0 – Setup**: repo, struttura, esplorazione del GTFS
- [x] **Fase 1 – Pipeline**: `gtfs.zip → timetable.db`
  - [x] 1.1 Calendario: servizi attivi per data
  - [x] 1.2 Orari per fermata/linea/direzione in un giorno
  - [x] 1.3 Raggruppamento giorni con orari identici (deduplicazione in `day_sets`)
  - [x] 1.4 Scrittura del db compatto + verifica incrociata con `orari_fermata.py`
- [x] **Fase 2 – App MVP**: ricerca fermata, preferiti, tabella settimanale, APK installabile
- [x] **Fase 3 – Aggiornamento automatico**: GitHub Actions + download in-app del db
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
