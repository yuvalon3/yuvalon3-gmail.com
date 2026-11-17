from urllib.parse import parse_qs, urlparse

from app.config import Settings
from app.service import ReceiptService


def test_issue_receipt(tmp_path):
    s = Settings()
    s.data_dir = tmp_path
    s.public_base_url = "https://bot.example.com"
    s.business_name = "סטודיו לאיפור"
    svc = ReceiptService(s)

    r1 = svc.issue("דנה כהן 1500 ביט 052-1234567")
    r2 = svc.issue("נועה 300 מזומן")
    assert r1.source == "local" and r1.image_path.is_file() and r1.image_path.stat().st_size > 10_000
    assert int(r2.number) == int(r1.number) + 1 == s.receipt_start_number + 1
    assert r1.image_url.startswith("https://bot.example.com/r/")

    wa = urlparse(r1.whatsapp_url)
    assert wa.netloc == "wa.me" and wa.path == "/972521234567"
    text = parse_qs(wa.query)["text"][0]
    assert "דנה" in text and "1,500 ₪" in text and r1.image_url in text
    assert urlparse(r2.whatsapp_url).path == "/"

