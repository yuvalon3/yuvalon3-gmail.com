"""Issue an official receipt in Hyp (formerly EZcount) through its API.

Docs: https://www.ezcount.co.il/api  (POST {base}/api/createDoc, JSON body)
Use HYP_API_BASE=https://demo.ezcount.co.il with a demo key to test without creating real documents.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal

import httpx

log = logging.getLogger(__name__)

# Hyp payment_type codes: 1 cash, 2 check, 3 credit card, 4 bank transfer, 9 other.
PAYMENT_TYPE_CODES = {
    "מזומן": 1,
    "צ'ק": 2,
    "כרטיס אשראי": 3,
    "העברה בנקאית": 4,
}
OTHER_PAYMENT = 9  # ביט, פייבוקס, PayPal, Apple Pay, Google Pay


class HypError(RuntimeError):
    pass


@dataclass
class HypDocument:
    number: str
    pdf_url: str
    raw: dict


class HypClient:
    def __init__(self, api_key: str, developer_email: str, api_base: str = "https://api.ezcount.co.il",
                 doc_type: int = 400, send_email_copy: bool = False):
        self.api_key = api_key
        self.developer_email = developer_email
        self.url = f"{api_base.rstrip('/')}/api/createDoc"
        self.doc_type = doc_type
        self.send_email_copy = send_email_copy

    def build_payload(self, *, customer_name: str, amount: Decimal, method: str, description: str,
                      date: datetime, customer_phone: str | None = None,
                      transaction_id: str | None = None) -> dict:
        payment_type = PAYMENT_TYPE_CODES.get(method, OTHER_PAYMENT)
        payment = {
            "payment_type": payment_type,
            "payment_sum": float(amount),
            "date": date.strftime("%d/%m/%Y"),
        }
        if payment_type == OTHER_PAYMENT:
            payment["other_payment_type_name"] = method
            payment["comment"] = method
        payload = {
            "api_key": self.api_key,
            "developer_email": self.developer_email,
            "type": self.doc_type,
            "description": description,
            "customer_name": customer_name,
            "item": [{"details": description, "amount": 1, "price": float(amount), "vat_type": "INC"}],
            "payment": [payment],
            "price_total": float(amount),
            "comment": f"אמצעי תשלום: {method}",
            "lang": "he",
            "currency_code": "ILS",
            "send_copy": self.send_email_copy,
        }
        if customer_phone:
            payload["customer_phone"] = "0" + customer_phone[3:] if customer_phone.startswith("972") else customer_phone
        if transaction_id:
            payload["transaction_id"] = transaction_id  # protects against duplicates on retries
        return payload

    def create_receipt(self, **kwargs) -> HypDocument:
        payload = self.build_payload(**kwargs)
        try:
            r = httpx.post(self.url, json=payload, timeout=30)
            data = r.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise HypError(f"אין תקשורת עם Hyp: {exc}") from exc
        if not data.get("success"):
            msg = data.get("errMsg") or data.get("errorMsg") or data.get("message") or str(data)[:300]
            log.error("Hyp createDoc failed: %s", data)
            raise HypError(f"Hyp החזירה שגיאה: {msg}")
        pdf = data.get("pdf_link") or data.get("pdf_link_copy") or ""
        return HypDocument(number=str(data.get("doc_number", "")), pdf_url=pdf, raw=data)
