"""Reading statistics store (statistics.json).

Schema:
  {"version": 1,
   "books": {"<book_id>": {"total_seconds": int, "sessions": int,
                           "pages_flipped": int, "last_read_at": "ISO",
                           "days": {"YYYY-MM-DD": {"seconds": int, "pages": int}}}},
   "global": {"total_seconds": int, "days": {...}}}
"""
import json
import threading
from datetime import date, timedelta
from pathlib import Path
from typing import Optional

from .storage import atomic_write_json, now_iso


def _local_today() -> str:
    return date.today().isoformat()


def _bump_day(days: dict, day: str, seconds: int, pages: int) -> None:
    entry = days.setdefault(day, {"seconds": 0, "pages": 0})
    entry["seconds"] += seconds
    entry["pages"] += pages


def _streak_days(days: dict) -> int:
    d = date.today()
    if days.get(d.isoformat()) is None:
        d -= timedelta(days=1)
    streak = 0
    while True:
        entry = days.get(d.isoformat())
        if not entry or int(entry.get("seconds", 0)) <= 0:
            break
        streak += 1
        d -= timedelta(days=1)
    return streak


def _enrich(rec: dict) -> dict:
    total = int(rec.get("total_seconds", 0))
    sessions = int(rec.get("sessions", 0))
    days = rec.get("days") or {}
    today = _local_today()
    week_start = (date.today() - timedelta(days=6)).isoformat()
    today_secs = int(days.get(today, {}).get("seconds", 0))
    today_pages = int(days.get(today, {}).get("pages", 0))
    week_secs = sum(
        int(v.get("seconds", 0))
        for k, v in days.items()
        if k >= week_start and k <= today
    )
    return {
        **rec,
        "days": dict(days),
        "total_seconds": total,
        "sessions": sessions,
        "pages_flipped": int(rec.get("pages_flipped", 0)),
        "last_read_at": rec.get("last_read_at", ""),
        "today_seconds": today_secs,
        "today_pages": today_pages,
        "week_seconds": week_secs,
        "avg_session_seconds": round(total / sessions) if sessions else 0,
        "streak_days": _streak_days(days),
    }


class StatsStore:
    def __init__(self, file: Path):
        self._file = file
        self._lock = threading.RLock()
        self._books: dict = {}
        self._global: dict = {}

    def load(self) -> None:
        try:
            with open(self._file, encoding="utf-8") as f:
                data = json.load(f)
            self._books = data.get("books", {})
            self._global = data.get("global", {})
        except (OSError, json.JSONDecodeError, AttributeError):
            self._books = {}
            self._global = {}

    def save(self) -> None:
        with self._lock:
            atomic_write_json(
                self._file,
                {"version": 1, "books": self._books, "global": self._global},
            )

    def record_reading(self, book_id: str, seconds: int, pages_flipped: int = 0) -> None:
        seconds = max(0, int(seconds))
        pages = max(0, int(pages_flipped))
        today = _local_today()
        with self._lock:
            b = self._books.setdefault(
                book_id,
                {
                    "total_seconds": 0,
                    "sessions": 0,
                    "pages_flipped": 0,
                    "last_read_at": "",
                    "days": {},
                },
            )
            b.setdefault("days", {})
            b["total_seconds"] += seconds
            if pages:
                b["pages_flipped"] += pages
            if seconds > 0:
                b["sessions"] += 1
                b["last_read_at"] = now_iso()
                _bump_day(b["days"], today, seconds, pages)
            g = self._global
            g.setdefault("days", {})
            g["total_seconds"] = g.get("total_seconds", 0) + seconds
            if seconds > 0:
                _bump_day(g["days"], today, seconds, pages)
            self.save()

    def get_book(self, book_id: str) -> dict:
        with self._lock:
            b = self._books.get(book_id)
            if not b:
                return {
                    "total_seconds": 0,
                    "sessions": 0,
                    "pages_flipped": 0,
                    "last_read_at": "",
                    "days": {},
                    "today_seconds": 0,
                    "today_pages": 0,
                    "week_seconds": 0,
                    "avg_session_seconds": 0,
                    "streak_days": 0,
                }
            return _enrich(b)

    def get_global(self) -> dict:
        with self._lock:
            return _enrich(self._global)

    def remove_book(self, book_id: str) -> None:
        with self._lock:
            if book_id in self._books:
                del self._books[book_id]
                self.save()
