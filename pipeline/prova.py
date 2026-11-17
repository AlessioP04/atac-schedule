# Algoritmo non ottimizzato ma funzionante
# Esempio con 706, fermata Marzabotto (74362), direzione Eur
import zipfile, csv, io

days = ["20261116", "20261117", "20261118", "20261119", "20261120"]
services = []
trips = []
results = []
route_id = "706"
stop_id = "74362"
z = zipfile.ZipFile("data/rome_static_gtfs.zip")
dates_reader = csv.DictReader(io.TextIOWrapper(z.open("calendar_dates.txt"), encoding="utf-8-sig"))
stop_times_reader = csv.DictReader(io.TextIOWrapper(z.open("stop_times.txt"), encoding="utf-8-sig"))
trips_reader = csv.DictReader(io.TextIOWrapper(z.open("trips.txt"), encoding="utf-8-sig"))

print(dates_reader.fieldnames)
for row in dates_reader:
    if row["date"] in days:
        services.append(row["service_id"])

print(trips_reader.fieldnames)
for row in trips_reader:
    if row["route_id"] == route_id:
        if row["service_id"] in services:
            trips.append(row["trip_id"])

print(stop_times_reader.fieldnames)
for row in stop_times_reader:
    if row["stop_id"] == stop_id:
        if row["trip_id"] in trips:
            results.append(row["arrival_time"])
results.sort()
print(results)