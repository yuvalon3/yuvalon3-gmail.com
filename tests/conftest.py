"""Keep tests isolated from a real .env file (e.g. a live Hyp API key).

load_dotenv() never overrides variables that are already set, so blanking them here,
before the app is imported, means tests can never call the real Hyp or Telegram APIs.
"""
import os

for _name in ("HYP_API_KEY", "HYP_DEVELOPER_EMAIL", "WHATSAPP_TEMPLATE",
              "TELEGRAM_BOT_TOKEN", "TELEGRAM_WEBHOOK_SECRET", "TELEGRAM_ALLOWED_USER_IDS"):
    os.environ[_name] = ""
