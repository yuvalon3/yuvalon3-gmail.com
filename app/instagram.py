"""Minimal Instagram Messaging API client (send text / image to a user)."""
from __future__ import annotations

import hashlib
import hmac
import logging

import httpx

log = logging.getLogger(__name__)


def verify_signature(app_secret: str, body: bytes, header: str | None) -> bool:
    """Check Meta's X-Hub-Signature-256 header."""
    if not app_secret:
        return True  # signature check disabled (not recommended in production)
    if not header or not header.startswith("sha256="):
        return False
    expected = hmac.new(app_secret.encode(), body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, header.split("=", 1)[1])


class InstagramClient:
    def __init__(self, access_token: str, api_base: str):
        self.token = access_token
        self.url = f"{api_base}/me/messages"

    async def _send(self, recipient_id: str, message: dict) -> None:
        async with httpx.AsyncClient(timeout=20) as client:
            r = await client.post(
                self.url,
                headers={"Authorization": f"Bearer {self.token}"},
                json={"recipient": {"id": recipient_id}, "message": message},
            )
        if r.status_code >= 400:
            log.error("Instagram send failed %s: %s", r.status_code, r.text)
            r.raise_for_status()

    async def send_text(self, recipient_id: str, text: str) -> None:
        await self._send(recipient_id, {"text": text[:1000]})

    async def send_image(self, recipient_id: str, image_url: str) -> None:
        await self._send(recipient_id, {"attachment": {"type": "image", "payload": {"url": image_url}}})
