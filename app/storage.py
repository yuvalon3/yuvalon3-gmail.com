"""SQLite storage: running receipt numbers, receipt records, processed message IDs."""
from __future__ import annotations

import secrets
import sqlite3
import threading
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

_lock = threading.Lock()


@dataclass
class StoredReceipt:
    number: int
    token: str
    name: str
    amount: str
    method: str
    phone: str | None
    kind: str | None
    created_at: str


class Storage:
    def __init__(self, db_path: Path, start_number: int = 1001):
        db_path.parent.mkdir(parents=True, exist_ok=True)
        self.db_path = db_path
        self.start_number = start_number
        with self._conn() as c:
            c.execute(
                """CREATE TABLE IF NOT EXISTS receipts (
                    number INTEGER PRIMARY KEY,
                    token TEXT UNIQUE NOT NULL,
                    name TEXT NOT NULL,
                    amount TEXT NOT NULL,
                    method TEXT NOT NULL,
                    phone TEXT,
                    kind TEXT,
                    created_at TEXT NOT NULL
                )"""
            )
            c.execute("CREATE TABLE IF NOT EXISTS processed_messages (mid TEXT PRIMARY KEY)")

    def _conn(self) -> sqlite3.Connection:
        return sqlite3.connect(self.db_path)

    def create_receipt(self, name: str, amount: str, method: str, phone: str | None,
                       kind: str | None, created_at: datetime) -> StoredReceipt:
        token = secrets.token_urlsafe(12)
        with _lock, self._conn() as c:
            row = c.execute("SELECT MAX(number) FROM receipts").fetchone()
            number = max((row[0] or 0) + 1, self.start_number)
            c.execute(
                "INSERT INTO receipts VALUES (?,?,?,?,?,?,?,?)",
                (number, token, name, amount, method, phone, kind, created_at.isoformat()),
            )
        return StoredReceipt(number, token, name, amount, method, phone, kind, created_at.isoformat())

    def mark_processed(self, mid: str) -> bool:
        """Return True the first time a message id is seen (Meta may retry webhooks)."""
        with _lock, self._conn() as c:
            try:
                c.execute("INSERT INTO processed_messages VALUES (?)", (mid,))
                return True
            except sqlite3.IntegrityError:
                return False
