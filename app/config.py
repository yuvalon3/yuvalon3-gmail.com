"""Settings, read from environment variables (or a .env file)."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

try:
    from dotenv import load_dotenv

    load_dotenv()
except ImportError:  # python-dotenv is optional
    pass


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


DEFAULT_WHATSAPP_TEMPLATE = (
    "היי {first_name} 🤍\n"
    "תודה רבה! התשלום על סך {amount} ({method}) התקבל.\n"
    "מצורפת הקבלה שלך (מס' {number}):\n"
    "{receipt_url}\n\n"
    "{business_name}"
)


@dataclass
class Settings:
    # --- Business details printed on the receipt ---
    business_name: str = field(default_factory=lambda: _env("BUSINESS_NAME", "שם העסק"))
    business_id: str = field(default_factory=lambda: _env("BUSINESS_ID"))  # ע.מ / ע.פ
    business_id_label: str = field(default_factory=lambda: _env("BUSINESS_ID_LABEL", "עוסק פטור"))
    business_phone: str = field(default_factory=lambda: _env("BUSINESS_PHONE"))
    business_address: str = field(default_factory=lambda: _env("BUSINESS_ADDRESS"))
    business_email: str = field(default_factory=lambda: _env("BUSINESS_EMAIL"))
    default_description: str = field(default_factory=lambda: _env("DEFAULT_DESCRIPTION", "שירותי כלה"))
    logo_path: str = field(default_factory=lambda: _env("LOGO_PATH"))
    receipt_start_number: int = field(default_factory=lambda: int(_env("RECEIPT_START_NUMBER", "1001")))
    timezone: str = field(default_factory=lambda: _env("TIMEZONE", "Asia/Jerusalem"))

    # --- WhatsApp message sent to the bride ---
    whatsapp_template: str = field(
        default_factory=lambda: _env("WHATSAPP_TEMPLATE").replace("\\n", "\n") or DEFAULT_WHATSAPP_TEMPLATE
    )

    # --- Server ---
    public_base_url: str = field(default_factory=lambda: _env("PUBLIC_BASE_URL", "http://localhost:8000").rstrip("/"))
    data_dir: Path = field(default_factory=lambda: Path(_env("DATA_DIR", "data")))

    # --- Instagram / Meta ---
    ig_access_token: str = field(default_factory=lambda: _env("IG_ACCESS_TOKEN"))
    ig_verify_token: str = field(default_factory=lambda: _env("IG_VERIFY_TOKEN"))
    meta_app_secret: str = field(default_factory=lambda: _env("META_APP_SECRET"))
    graph_api_base: str = field(
        default_factory=lambda: _env("GRAPH_API_BASE", "https://graph.instagram.com/v21.0").rstrip("/")
    )
    # Comma-separated Instagram-scoped IDs allowed to create receipts (you).
    allowed_sender_ids: set[str] = field(
        default_factory=lambda: {s.strip() for s in _env("ALLOWED_SENDER_IDS").split(",") if s.strip()}
    )

    @property
    def receipts_dir(self) -> Path:
        return self.data_dir / "receipts"

    @property
    def db_path(self) -> Path:
        return self.data_dir / "receipts.db"


settings = Settings()
