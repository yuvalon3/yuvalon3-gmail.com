from urllib.parse import urlparse

import httpx
from fastapi.testclient import TestClient


def _setup(tmp_path, monkeypatch, hyp_response=None):
    from app import main
    from app.service import ReceiptService

    s = main.settings
    monkeypatch.setattr(s, "data_dir", tmp_path)
    monkeypatch.setattr(main, "TG_SECRET", "tgsecret")
    monkeypatch.setattr(s, "telegram_allowed_user_ids", {"111"})
    if hyp_response is not None:
        monkeypatch.setattr(s, "hyp_api_key", "KEY")
        monkeypatch.setattr("app.hyp.httpx.post", lambda url, json, timeout: httpx.Response(200, json=hyp_response))
    monkeypatch.setattr(main, "service", ReceiptService(s))
    calls = []

    async def fake_call(method, data=None, files=None):
        calls.append((method, data, files))
        return {"ok": True}

    monkeypatch.setattr(main.tg, "_call", fake_call)
    return main, TestClient(main.app), calls


def _update(update_id, user_id, text):
    return {"update_id": update_id, "message": {"message_id": update_id, "from": {"id": user_id},
                                                "chat": {"id": user_id}, "text": text}}


H = {"X-Telegram-Bot-Api-Secret-Token": "tgsecret"}


def test_local_receipt(tmp_path, monkeypatch):
    main, client, calls = _setup(tmp_path, monkeypatch)
    assert client.post("/telegram/webhook", json=_update(1, 111, "דנה כהן 1500 ביט 052-1234567"), headers=H).status_code == 200
    assert client.post("/telegram/webhook", json=_update(1, 111, "דנה כהן 1500 ביט"), headers=H).status_code == 200  # retry
    assert [c[0] for c in calls] == ["sendPhoto", "sendMessage"]
    assert "photo" in calls[0][2]
    text_call = calls[1][1]
    assert "1001" in text_call["text"] and "דנה כהן" in text_call["text"]
    wa = text_call["reply_markup"]["inline_keyboard"][0][0]["url"]
    assert urlparse(wa).netloc == "wa.me" and urlparse(wa).path == "/972521234567"


def test_hyp_receipt(tmp_path, monkeypatch):
    main, client, calls = _setup(tmp_path, monkeypatch,
                                 {"success": True, "doc_number": 77, "pdf_link": "https://hyp.example/77.pdf"})
    client.post("/telegram/webhook", json=_update(5, 111, "נועה 300 מזומן"), headers=H)
    assert [c[0] for c in calls] == ["sendDocument", "sendMessage"]
    assert calls[0][1]["document"] == "https://hyp.example/77.pdf"
    rows = calls[1][1]["reply_markup"]["inline_keyboard"]
    assert rows[1][0]["url"] == "https://hyp.example/77.pdf"


def test_security(tmp_path, monkeypatch):
    main, client, calls = _setup(tmp_path, monkeypatch)
    assert client.post("/telegram/webhook", json=_update(9, 111, "דנה 10 ביט"),
                       headers={"X-Telegram-Bot-Api-Secret-Token": "wrong"}).status_code == 403
    client.post("/telegram/webhook", json=_update(10, 222, "דנה 10 ביט"), headers=H)  # stranger
    assert calls == []


def test_parse_error_reply(tmp_path, monkeypatch):
    main, client, calls = _setup(tmp_path, monkeypatch)
    client.post("/telegram/webhook", json=_update(11, 111, "דנה כהן ביט"), headers=H)
    assert calls[0][0] == "sendMessage" and "סכום" in calls[0][1]["text"]


def test_webhook_secret():
    from app.telegram import webhook_secret
    assert webhook_secret("abc_DEF-1", "t") == "abc_DEF-1"
    derived = webhook_secret("", "123:ABC")
    assert derived == webhook_secret("", "123:ABC") and len(derived) == 64
    assert webhook_secret("bad+/=", "t").isalnum()
    assert webhook_secret("", "") == ""
