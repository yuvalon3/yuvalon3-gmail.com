"""Web server: Telegram webhook and public receipt images.

Run:  uvicorn app.main:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

import logging
import re
from contextlib import asynccontextmanager

from fastapi import BackgroundTasks, FastAPI, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse

from .config import settings
from .hyp import HypError
from .parser import ParseError
from .service import HELP_TEXT, ReceiptService, owner_summary
from .telegram import TelegramClient, url_buttons, verify_secret, webhook_secret

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("receipt-bot")

service = ReceiptService(settings)
tg = TelegramClient(settings.telegram_bot_token, settings.telegram_api_base)
TG_SECRET = webhook_secret(settings.telegram_webhook_secret, settings.telegram_bot_token)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    # Register the Telegram webhook automatically, so there is no manual setWebhook step.
    if settings.telegram_bot_token and settings.public_base_url.startswith("https://"):
        try:
            await tg.set_webhook(f"{settings.public_base_url}/telegram/webhook", TG_SECRET)
            log.info("Telegram webhook registered")
        except Exception:
            log.exception("Could not register the Telegram webhook")
    yield


app = FastAPI(title="Receipt bot", lifespan=lifespan)

HELP_WORDS = {"עזרה", "help", "?", "היי", "שלום", "start", "/start", "/help"}
TOKEN_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")


@app.get("/")
def health():
    return {"ok": True}


@app.post("/telegram/webhook")
async def telegram_webhook(request: Request, background: BackgroundTasks):
    if not verify_secret(TG_SECRET, request.headers.get("X-Telegram-Bot-Api-Secret-Token")):
        raise HTTPException(status_code=403, detail="bad secret")
    update = await request.json()
    msg = update.get("message") or {}
    text = msg.get("text")
    chat_id = (msg.get("chat") or {}).get("id")
    user_id = (msg.get("from") or {}).get("id")
    update_id = update.get("update_id")
    if text and chat_id and user_id and (update_id is None or service.storage.mark_processed(f"tg:{update_id}")):
        background.add_task(handle_telegram, chat_id, str(user_id), text, f"tg-{chat_id}-{msg.get('message_id')}")
    return {"ok": True}


async def handle_telegram(chat_id: int, user_id: str, text: str, transaction_id: str) -> None:
    try:
        if user_id not in settings.telegram_allowed_user_ids:
            log.warning("Telegram message from unknown user %s ignored", user_id)
            if not settings.telegram_allowed_user_ids:
                # First-time setup helper: tell the owner which ID to put in TELEGRAM_ALLOWED_USER_IDS.
                await tg.send_text(chat_id, f"🔒 הבוט עדיין לא הוגדר.\nה-ID שלך: {user_id}\n"
                                            f"יש להוסיף אותו למשתנה TELEGRAM_ALLOWED_USER_IDS ולהפעיל מחדש.")
            return

        if text.strip().lower() in HELP_WORDS:
            await tg.send_text(chat_id, HELP_TEXT)
            return

        try:
            result = await run_in_threadpool(service.issue, text, transaction_id)
        except ParseError as e:
            await tg.send_text(chat_id, f"⚠️ {e}\n\n{HELP_TEXT}")
            return
        except HypError as e:
            await tg.send_text(chat_id, f"❌ {e}")
            return

        # 1. The receipt itself
        try:
            if result.image_path:
                await tg.send_photo_file(chat_id, result.image_path, f"קבלה מס' {result.number}")
            elif result.receipt_url:
                await tg.send_document_url(chat_id, result.receipt_url, f"קבלה מס' {result.number}")
        except Exception:
            log.warning("Could not attach the receipt file; the link button is still sent", exc_info=True)

        # 2. Summary + buttons
        to = "📲 שליחה לכלה בוואטסאפ"
        buttons = url_buttons([(to, result.whatsapp_url)],
                              [("🧾 פתיחת הקבלה", result.receipt_url)] if result.receipt_url.startswith("https://") else [])
        await tg.send_text(chat_id, owner_summary(result), reply_markup=buttons)
        log.info("Receipt %s issued for %s (telegram)", result.number, result.request.name)
    except Exception:
        log.exception("Failed handling telegram message")
        try:
            await tg.send_text(chat_id, "❌ משהו השתבש בהפקת הקבלה. נסו שוב בעוד רגע.")
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
