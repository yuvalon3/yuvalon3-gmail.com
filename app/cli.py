"""Try the bot locally without Instagram:

    python -m app.cli "דנה כהן 1500 ביט 052-1234567"
"""
from __future__ import annotations

import sys

from .config import settings
from .hyp import HypError
from .parser import ParseError
from .service import HELP_TEXT, ReceiptService, owner_reply


def main() -> int:
    text = " ".join(sys.argv[1:]).strip()
    if not text:
        print(HELP_TEXT)
        return 1
    try:
        r = ReceiptService(settings).issue(text)
    except (ParseError, HypError) as e:
        print(f"⚠️ {e}")
        return 2
    print(owner_reply(r))
    if r.image_path:
        print(f"\nקובץ הקבלה: {r.image_path.resolve()}")
    print(f"\nהודעה לכלה:\n{r.whatsapp_text}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
