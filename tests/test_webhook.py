import hashlib
import hmac
import json

from fastapi.testclient import TestClient


def test_webhook_flow(tmp_path, monkeypatch):
    from app import main
    from app.service import ReceiptService

    s = main.settings
    monkeypatch.setattr(s, "data_dir", tmp_path)
    monkeypatch.setattr(s, "meta_app_secret", "sec")
    monkeypatch.setattr(s, "ig_verify_token", "vt")
    monkeypatch.setattr(s, "allowed_sender_ids", {"OWNER"})
    monkeypatch.setattr(main, "service", ReceiptService(s))
    sent = []

    async def fake_send(recipient, message):
        sent.append((recipient, message))

    monkeypatch.setattr(main.ig, "_send", fake_send)
    client = TestClient(main.app)

    r = client.get("/webhook", params={"hub.mode": "subscribe", "hub.verify_token": "vt", "hub.challenge": "42"})
    assert r.text == "42"

    def post(sender, text, mid):
        body = json.dumps({"object": "instagram", "entry": [{"messaging": [
            {"sender": {"id": sender}, "recipient": {"id": "BOT"}, "message": {"mid": mid, "text": text}}]}]}).encode()
        sig = "sha256=" + hmac.new(b"sec", body, hashlib.sha256).hexdigest()
        return client.post("/webhook", content=body, headers={"X-Hub-Signature-256": sig})

    assert post("OWNER", "דנה כהן 1500 ביט", "m1").status_code == 200
    assert post("OWNER", "דנה כהן 1500 ביט", "m1").status_code == 200  # retry: ignored
    assert post("STRANGER", "דנה 100 ביט", "m2").status_code == 200    # not allowed: ignored
    assert len(sent) == 2
    image_url = sent[0][1]["attachment"]["payload"]["url"]
    assert "wa.me" in sent[1][1]["text"]
    path = image_url.split("/r/")[1]
    img = client.get(f"/r/{path}")
    assert img.status_code == 200 and img.headers["content-type"] == "image/png"
    assert client.get("/r/../../etc.png").status_code == 404

    bad = client.post("/webhook", content=b"{}", headers={"X-Hub-Signature-256": "sha256=x"})
    assert bad.status_code == 403
