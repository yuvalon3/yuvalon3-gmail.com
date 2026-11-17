"""Web server: Instagram webhook + public receipt images.

Run:  uvicorn app.main:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

import json
import logging
import re

from fastapi import BackgroundTasks, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, PlainTextResponse

from .config import settings
from .instagram import InstagramClient, verify_signature
from .parser import ParseError
from .service import HELP_TEXT, ReceiptService, owner_reply

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("receipt-bot")

app = FastAPI(title="Instagram receipt bot")
service = ReceiptService(settings)
ig = InstagramClient(settings.ig_access_token, settings.graph_api_base)

HELP_WORDS = {"עזרה", "help", "?", "היי", "שלום", "start"}
TOKEN_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")


@app.get("/")
def health():
    return {"ok": True}


@app.get("/webhook")
def verify_webhook(request: Request):
    """Meta calls this once when you register the webhook URL."""
    p = request.query_params
    if p.get("hub.mode") == "subscribe" and p.get("hub.verify_token") == settings.ig_verify_token:
        return PlainTextResponse(p.get("hub.challenge", ""))
    raise HTTPException(status_code=403, detail="verification failed")


@app.post("/webhook")
async def receive_webhook(request: Request, background: BackgroundTasks):
    body = await request.body()
    if not verify_signature(settings.meta_app_secret, body, request.headers.get("X-Hub-Signature-256")):
        raise HTTPException(status_code=403, detail="bad signature")
    payload = json.loads(body or b"{}")
    for entry in payload.get("entry", []):
        for event in entry.get("messaging", []):
            msg = event.get("message") or {}
            if msg.get("is_echo") or "text" not in msg:
                continue
            sender = event.get("sender", {}).get("id")
            mid = msg.get("mid")
            if sender and (not mid or service.storage.mark_processed(mid)):
                background.add_task(handle_message, sender, msg["text"])
    # Always answer 200 fast, otherwise Meta keeps retrying.
    return {"ok": True}


async def handle_message(sender_id: str, text: str) -> None:
    try:
        if sender_id not in settings.allowed_sender_ids:
            log.warning("Message from unknown sender %s ignored", sender_id)
            if not settings.allowed_sender_ids:
                # First-time setup helper: tell the owner which ID to put in ALLOWED_SENDER_IDS.
                await ig.send_text(sender_id, f"🔒 הבוט עדיין לא הוגדר.\nה-ID שלך: {sender_id}\n"
                                              f"יש להוסיף אותו למשתנה ALLOWED_SENDER_IDS ולהפעיל מחדש.")
            return

        if text.strip().lower() in HELP_WORDS:
            await ig.send_text(sender_id, HELP_TEXT)
            return

        try:
            result = service.issue(text)
        except ParseError as e:
            await ig.send_text(sender_id, f"⚠️ {e}\n\n{HELP_TEXT}")
            return

        await ig.send_image(sender_id, result.image_url)
        await ig.send_text(sender_id, owner_reply(result))
        log.info("Receipt %s issued for %s", result.number, result.request.name)
    except Exception:
        log.exception("Failed handling message")
        try:
            await ig.send_text(sender_id, "❌ משהו השתבש בהפקת הקבלה. נסו שוב בעוד רגע.")
        except Exception:
            pass


@app.get("/r/{token}.png")
def receipt_image(token: str):
    """Receipt image link (random, unguessable token) — this is what the bride opens."""
    if not TOKEN_RE.match(token):
        raise HTTPException(status_code=404)
    path = service.image_path(token)
    if not path.is_file():
        raise HTTPException(status_code=404)
    return FileResponse(path, media_type="image/png", headers={"Cache-Control": "private, max-age=86400"})
