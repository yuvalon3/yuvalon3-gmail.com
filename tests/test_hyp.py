from datetime import datetime
from decimal import Decimal
from urllib.parse import parse_qs, urlparse

import httpx
import pytest

from app.config import Settings
from app.hyp import HypClient, HypError
from app.service import ReceiptService, owner_reply


def test_payload_codes():
    c = HypClient("KEY", "dev@example.com")
    d = datetime(2026, 11, 18)
    p = c.build_payload(customer_name="דנה", amount=Decimal("1500"), method="העברה בנקאית",
                        description="הדרכת כלה", date=d, customer_phone="972521234567", transaction_id="m1")
    assert p["type"] == 400 and p["api_key"] == "KEY" and p["developer_email"] == "dev@example.com"
    assert p["payment"] == [{"payment_type": 4, "payment_sum": 1500.0, "date": "18/11/2026",
                             "details": "הדרכת כלה", "comment": "הדרכת כלה"}]
    assert p["customer_phone"] == "0521234567" and p["transaction_id"] == "m1"
    assert p["item"][0]["vat_type"] == "NON"
    bit = c.build_payload(customer_name="דנה", amount=Decimal("10"), method="ביט", description="x", date=d)
    assert bit["payment"][0]["payment_type"] == 9 and bit["payment"][0]["other_payment_type_name"] == "ביט"


def _service(tmp_path, monkeypatch, response):
    s = Settings()
    s.data_dir = tmp_path
    s.hyp_api_key = "KEY"
    s.hyp_developer_email = "dev@example.com"
    sent = {}

    def fake_post(url, json, timeout):
        sent["url"], sent["json"] = url, json
        return httpx.Response(200, json=response)

    monkeypatch.setattr("app.hyp.httpx.post", fake_post)
    return ReceiptService(s), sent


def test_issue_via_hyp(tmp_path, monkeypatch):
    svc, sent = _service(tmp_path, monkeypatch,
                         {"success": True, "doc_number": 5012, "pdf_link": "https://hyp.example/doc.pdf"})
    r = svc.issue("נועה לוי 2300 מזומן 052-1234567", transaction_id="mid-1")
    assert sent["url"].endswith("/api/createDoc")
    assert sent["json"]["customer_name"] == "נועה לוי" and sent["json"]["payment"][0]["payment_type"] == 1
    assert r.source == "hyp" and r.number == "5012" and r.image_url is None
    text = parse_qs(urlparse(r.whatsapp_url).query)["text"][0]
    assert "https://hyp.example/doc.pdf" in text and "5012" in text
    assert "https://hyp.example/doc.pdf" in owner_reply(r)


def test_hyp_error(tmp_path, monkeypatch):
    svc, _ = _service(tmp_path, monkeypatch, {"success": False, "errMsg": "api key invalid"})
    with pytest.raises(HypError, match="api key invalid"):
        svc.issue("נועה 100 ביט")
