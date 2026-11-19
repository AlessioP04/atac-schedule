"""Percorsi delle linee (shapes.txt): lettura, semplificazione e codifica compatta per la mappa dell'app."""

import math
import zipfile
from collections import defaultdict

from gtfs import open_csv

type Point = tuple[float, float]  # (lat, lon)

# Tolleranza della semplificazione: a 5 m di errore il percorso sulla mappa è indistinguibile dall'originale.
TOLERANCE_M = 5.0


def load_shapes(gtfs: zipfile.ZipFile, wanted: set[str]) -> dict[str, list[Point]]:
    """shape_id -> punti nell'ordine del percorso (solo i percorsi in `wanted`)."""
    if "shapes.txt" not in gtfs.namelist():
        return {}
    raw: dict[str, list[tuple[int, float, float]]] = defaultdict(list)
    with open_csv(gtfs, "shapes.txt") as (col, rows):
        i_id, i_lat, i_lon, i_seq = col["shape_id"], col["shape_pt_lat"], col["shape_pt_lon"], col["shape_pt_sequence"]
        for row in rows:
            if row[i_id] in wanted:
                raw[row[i_id]].append((int(row[i_seq]), float(row[i_lat]), float(row[i_lon])))
    return {sid: [(lat, lon) for _, lat, lon in sorted(pts)] for sid, pts in raw.items()}


def simplify(points: list[Point], tolerance_m: float = TOLERANCE_M) -> list[Point]:
    """Douglas-Peucker (iterativo, niente ricorsione) in coordinate locali in metri."""
    if len(points) < 3:
        return points
    lat0 = math.radians(points[0][0])
    kx, ky = 111_320.0 * math.cos(lat0), 110_540.0
    xy = [(lon * kx, lat * ky) for lat, lon in points]

    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        (ax, ay), (bx, by) = xy[a], xy[b]
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy)
        worst, worst_i = 0.0, -1
        for i in range(a + 1, b):
            px, py = xy[i]
            # distanza dal segmento a-b (o dal punto a se coincidono)
            d = abs(dy * (px - ax) - dx * (py - ay)) / length if length else math.hypot(px - ax, py - ay)
            if d > worst:
                worst, worst_i = d, i
        if worst > tolerance_m:
            keep[worst_i] = True
            stack += [(a, worst_i), (worst_i, b)]
    return [p for p, k in zip(points, keep) if k]


def encode_polyline(points: list[Point]) -> str:
    """Encoded Polyline Algorithm di Google (precisione 1e-5): ~3-4 byte per punto, decodifica banale nell'app."""
    out: list[str] = []
    prev_lat = prev_lon = 0
    for lat, lon in points:
        ilat, ilon = round(lat * 1e5), round(lon * 1e5)
        for delta in (ilat - prev_lat, ilon - prev_lon):
            v = ~(delta << 1) if delta < 0 else delta << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        prev_lat, prev_lon = ilat, ilon
    return "".join(out)


def decode_polyline(text: str) -> list[Point]:
    """Inverso di encode_polyline (usato nei test e per verifica)."""
    points: list[Point] = []
    i = lat = lon = 0
    while i < len(text):
        deltas = []
        for _ in range(2):
            shift = result = 0
            while True:
                b = ord(text[i]) - 63
                i += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            deltas.append(~(result >> 1) if result & 1 else result >> 1)
        lat += deltas[0]
        lon += deltas[1]
        points.append((lat / 1e5, lon / 1e5))
    return points
