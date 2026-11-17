"""The core flow: sentence -> stored receipt -> PNG -> WhatsApp link."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from .config import Settings
from .parser import ReceiptRequest, format_amount, parse_message
from .receipt import ReceiptData, render_receipt
from .storage import Storage
from .whatsapp import whatsapp_link


@dataclass
class IssuedReceipt:
    request: ReceiptRequest
    number: int
    image_path: Path
    image_url: str
    whatsapp_url: str
    whatsapp_text: str


class ReceiptService:
    def __init__(self, settings: Settings):
        self.s = settings
        self.storage = Storage(settings.db_path, settings.receipt_start_number)

    def image_path(self, token: str) -> Path:
        return self.s.receipts_dir / f"{token}.png"

    def issue(self, text: str) -> IssuedReceipt:
        req = parse_message(text)  # raises ParseError
        now = datetime.now(ZoneInfo(self.s.timezone))
        amount_str = format_amount(req.amount)
        description = f"{req.kind} - {self.s.default_description}" if req.kind else self.s.default_description

        stored = self.storage.create_receipt(req.name, str(req.amount), req.method, req.phone, req.kind, now)
        path = render_receipt(
            ReceiptData(
                number=stored.number,
                date=now.strftime("%d/%m/%Y"),
                customer_name=req.name,
                amount=amount_str,
                method=req.method,
                description=description,
                business_name=self.s.business_name,
                business_id_label=self.s.business_id_label,
                business_id=self.s.business_id,
                business_phone=self.s.business_phone,
                business_address=self.s.business_address,
                business_email=self.s.business_email,
                logo_path=self.s.logo_path,
            ),
            self.image_path(stored.token),
        )
        image_url = f"{self.s.public_base_url}/r/{stored.token}.png"
        wa_text = self.s.whatsapp_template.format(
            name=req.name,
            first_name=req.first_name,
            amount=amount_str,
            method=req.method,
            number=stored.number,
            receipt_url=image_url,
            business_name=self.s.business_name,
            description=description,
        )
        return IssuedReceipt(req, stored.number, path, image_url, whatsapp_link(wa_text, req.phone), wa_text)


HELP_TEXT = (
    "שלחו לי משפט אחד עם שם הכלה, הסכום וצורת התשלום, למשל:\n"
    "• דנה כהן 1500 ביט\n"
    "• נועה לוי 2,300 ש\"ח העברה בנקאית 052-1234567\n"
    "• קבלה ללאה אברהם 800 מזומן מקדמה\n\n"
    "אם תוסיפו מספר טלפון – הוואטסאפ ייפתח ישר בצ'אט של הכלה."
)


def owner_reply(r: IssuedReceipt) -> str:
    to = "לצ'אט של הכלה" if r.request.phone else "(בחרו את הכלה מרשימת אנשי הקשר)"
    kind = f" · {r.request.kind}" if r.request.kind else ""
    return (
        f"✅ קבלה מס' {r.number} הופקה\n"
        f"{r.request.name} · {format_amount(r.request.amount)} · {r.request.method}{kind}\n\n"
        f"📲 לשליחה בוואטסאפ {to}:\n{r.whatsapp_url}"
    )
