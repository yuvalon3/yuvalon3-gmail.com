"""Minimal Telegram Bot API client (https://core.telegram.org/bots/api)."""
from __future__ import annotations

import hashlib
import hmac
import logging
import re
from pathlib import Path

import httpx

log = logging.getLogger(__name__)


def webhook_secret(configured: str, bot_token: str) -> str:
    """Telegram only accepts A-Z a-z 0-9 _ - (1-256 chars); otherwise derive a stable one from the token."""
    if configured and re.fullmatch(r"[A-Za-z0-9_-]{1,256}", configured):
        return configured
    seed = configured or bot_token
    return hashlib.sha256(f"receipt-bot:{seed}".encode()).hexdigest() if seed else ""


def verify_secret(expected: str, header: str | None) -> bool:
    """Telegram sends our secret back in X-Telegram-Bot-Api-Secret-Token on every webhook call."""
    if not expected:
        return True  # check disabled (not recommended in production)
    return bool(header) and hmac.compare_digest(expected, header)


def url_buttons(*rows: list[tuple[str, str]]) -> dict:
    """Inline keyboard of URL buttons: url_buttons([("text", "https://...")], ...)."""
    return {"inline_keyboard": [[{"text": t, "url": u} for t, u in row] for row in rows if row]}


class TelegramClient:
    def __init__(self, token: str, api_base: str = "https://api.telegram.org"):
        self.base = f"{api_base.rstrip('/')}/bot{token}"

    async def _call(self, method: str, data: dict | None = None, files: dict | None = None) -> dict:
        async with httpx.AsyncClient(timeout=30) as client:
            if files:
                r = await client.post(f"{self.base}/{method}", data=data, files=files)
            else:
                r = await client.post(f"{self.base}/{method}", json=data or {})
        body = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
        if r.status_code >= 400 or not body.get("ok"):
            log.error("Telegram %s failed %s: %s", method, r.status_code, r.text[:500])
            raise RuntimeError(f"Telegram {method} failed: {body.get('description') or r.status_code}")
        return body

    async def send_text(self, chat_id: int | str, text: str, reply_markup: dict | None = None) -> None:
        data = {"chat_id": chat_id, "text": text[:4096], "disable_web_page_preview": True}
        if reply_markup:
            data["reply_markup"] = reply_markup
        await self._call("sendMessage", data)

    async def send_photo_file(self, chat_id: int | str, path: Path, caption: str = "") -> None:
        with open(path, "rb") as f:
            await self._call("sendPhoto", {"chat_id": str(chat_id), "caption": caption[:1024]},
                             files={"photo": (path.name, f, "image/png")})

    async def send_document_url(self, chat_id: int | str, url: str, caption: str = "") -> None:
        await self._call("sendDocument", {"chat_id": chat_id, "document": url, "caption": caption[:1024]})

    async def set_webhook(self, url: str, secret: str) -> None:
        await self._call("setWebhook", {
            "url": url,
            "secret_token": secret,
            "allowed_updates": ["message"],
            "drop_pending_updates": False,
        })
