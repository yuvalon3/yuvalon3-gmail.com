"""Build a WhatsApp click-to-chat link with the message to the bride pre-filled."""
from __future__ import annotations

from urllib.parse import quote


def whatsapp_link(text: str, phone: str | None = None) -> str:
    """https://wa.me/<phone>?text=...  — without a phone, WhatsApp asks which chat to send to."""
    base = f"https://wa.me/{phone}" if phone else "https://wa.me/"
    return f"{base}?text={quote(text, safe='')}"
