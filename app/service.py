"""The core flow: sentence -> receipt (Hyp document or local PNG) -> WhatsApp link."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from .config import Settings
from .hyp import HypClient
from .parser import ReceiptRequest, format_amount, parse_message
from .receipt import ReceiptData, render_receipt
from .storage import Storage
from .whatsapp import whatsapp_link


@dataclass
class IssuedReceipt:
    request: ReceiptRequest
    number: str
    receipt_url: str               # link the bride gets (Hyp PDF or our PNG)
    whatsapp_url: str
    whatsapp_text: str
    image_path: Path | None = None  # only for locally drawn receipts
    image_url: str | None = None
    source: str = "local"           # "hyp" or "local"


class ReceiptService:
    def __init__(self, settings: Settings):
        self.s = settings
        self.storage = Storage(settings.db_path, settings.receipt_start_number)
        self.hyp = (
            HypClient(settings.hyp_api_key, settings.hyp_developer_email, settings.hyp_api_base,
                      settings.hyp_doc_type, settings.hyp_send_email_copy)
            if settings.hyp_api_key else None
        )

    def image_path(self, token: str) -> Path:
        return self.s.receipts_dir / f"{token}.png"

    def issue(self, text: str, transaction_id: str | None = None) -> IssuedReceipt:
        req = parse_message(text)  # raises ParseError
        now = datetime.now(ZoneInfo(self.s.timezone))
        amount_str = format_amount(req.amount)
        description = f"{req.kind} - {self.s.default_description}" if req.kind else self.s.default_description

        image_path = image_url = None
        if self.hyp:
            doc = self.hyp.create_receipt(  # raises HypError
                customer_name=req.name, amount=req.amount, method=req.method, description=description,
                date=now, customer_phone=req.phone, transaction_id=transaction_id,
            )
            number, receipt_url, source = doc.number, doc.pdf_url, "hyp"
        else:
            stored = self.storage.create_receipt(req.name, str(req.amount), req.method, req.phone, req.kind, now)
            image_path = render_receipt(
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
            number, receipt_url, source = str(stored.number), image_url, "local"

        wa_text = self.s.whatsapp_template.format(
            name=req.name,
            first_name=req.first_name,
            amount=amount_str,
            method=req.method,
            number=number,
            receipt_url=receipt_url,
            business_name=self.s.business_name,
            description=description,
        )
        return IssuedReceipt(req, number, receipt_url, whatsapp_link(wa_text, req.phone), wa_text,
                             image_path, image_url, source)


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
        f"{r.request.name} · {format_amount(r.request.amount)} · {r.request.method}{kind}\n"
        + (f"🧾 הקבלה ב-Hyp: {r.receipt_url}\n" if r.source == "hyp" else "")
        + "\n"
        + f"📲 לשליחה בוואטסאפ {to}:\n{r.whatsapp_url}"
    )
