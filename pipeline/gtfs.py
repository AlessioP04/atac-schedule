"""Lettura del GTFS statico di Roma Mobilità: funzioni condivise dagli script della pipeline."""

import csv
import io
import shutil
import sys
import urllib.request
import zipfile
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import date, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_GTFS = ROOT / "data" / "rome_static_gtfs.zip"
GTFS_URL = "https://romamobilita.it/sites/default/files/rome_static_gtfs.zip"
MINUTES_PER_DAY = 24 * 60

type Columns = dict[str, int]
type Rows = Iterator[list[str]]


@contextmanager
def open_csv(gtfs: zipfile.ZipFile, name: str) -> Iterator[tuple[Columns, Rows]]:
    """Apre un CSV dentro lo zip senza estrarlo. Restituisce (indice delle colonne, righe come liste).

    csv.reader è circa 2 volte più veloce di DictReader: con 5 milioni di righe fa la differenza.
    """
    with gtfs.open(name) as raw, io.TextIOWrapper(raw, encoding="utf-8-sig", newline="") as text:
        reader = csv.reader(text)
        header = next(reader)
        yield {col: i for i, col in enumerate(header)}, reader


def parse_time(hms: str) -> int:
    """'25:10:44' -> minuti dalla mezzanotte del giorno di servizio (1510). I secondi si scartano.

    Il GTFS ammette orari oltre le 24: indicano corse che passano dopo la mezzanotte.
    """
    h, m, _ = hms.split(":")
    return int(h) * 60 + int(m)


def parse_date(yyyymmdd: str) -> date:
    return datetime.strptime(yyyymmdd, "%Y%m%d").date()


def utf8_stdout() -> None:
    """La console di Windows usa cp1252, che non ha caratteri come "→": forziamo UTF-8."""
    if isinstance(sys.stdout, io.TextIOWrapper):
        sys.stdout.reconfigure(encoding="utf-8")


def download(dest: Path = DEFAULT_GTFS, url: str = GTFS_URL) -> Path:
    """Scarica il feed. Scrive su un file temporaneo e lo sostituisce solo se lo zip è integro."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(".part")
    request = urllib.request.Request(url, headers={"User-Agent": "atac-schedule-pipeline"})
    with urllib.request.urlopen(request, timeout=300) as response, tmp.open("wb") as f:
        shutil.copyfileobj(response, f)
    with zipfile.ZipFile(tmp) as z:
        if (bad := z.testzip()) is not None:
            raise ValueError(f"Feed scaricato corrotto: {bad}")
    tmp.replace(dest)
    return dest
