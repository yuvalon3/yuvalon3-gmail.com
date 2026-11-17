"""Keep tests isolated from a real .env file (e.g. a live Hyp API key).

load_dotenv() never overrides variables that are already set, so blanking them here,
before the app is imported, means tests can never call the real Hyp or Instagram APIs.
"""
import os

for _name in ("HYP_API_KEY", "HYP_DEVELOPER_EMAIL", "IG_ACCESS_TOKEN", "META_APP_SECRET",
              "ALLOWED_SENDER_IDS", "WHATSAPP_TEMPLATE"):
    os.environ[_name] = ""
