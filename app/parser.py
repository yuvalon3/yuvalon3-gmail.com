"""Turn one free-text Hebrew sentence into receipt details.

Examples that work:
    דנה כהן 1500 ביט
    נועה לוי, 2,300 ש"ח, העברה בנקאית
    קבלה ללאה אברהם 800 במזומן 052-1234567
    שירה 1200₪ אשראי מקדמה
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation

# Display name -> aliases (lower-case). Longer aliases are tried first.
PAYMENT_METHODS: dict[str, list[str]] = {
    "העברה בנקאית": ["העברה בנקאית", "העברת בנקאית", "העברה לבנק", "העברה", "בנקאית", "bank transfer", "transfer"],
    "ביט": ["ביט", "bit"],
    "פייבוקס": ["פייבוקס", "פיי בוקס", "paybox"],
    "כרטיס אשראי": ["כרטיס אשראי", "אשראי", "כרטיס", "credit card", "credit", "visa", "ויזה"],
    "מזומן": ["מזומנים", "מזומן", "קאש", "cash"],
    "צ'ק": ["צ'קים", "צ׳קים", "צ'ק", "צ׳ק", "צק", "שיק", "המחאה", "check", "cheque"],
    "Apple Pay": ["אפל פיי", "apple pay", "applepay"],
    "Google Pay": ["גוגל פיי", "google pay", "googlepay"],
    "PayPal": ["פייפאל", "פייפל", "paypal"],
}

# Optional words describing what the payment is for.
PAYMENT_KINDS: dict[str, list[str]] = {
    "מקדמה": ["מקדמה", "מקדמת", "דמי רצינות"],
    "יתרה": ["יתרת תשלום", "יתרה", "השלמה", "תשלום סופי", "תשלום אחרון"],
    "תשלום מלא": ["תשלום מלא", "מלא"],
}

# Words that are not part of the bride's name.
FILLER_WORDS = {
    "קבלה", "קבלת", "הפק", "תפיק", "תוציא", "עבור", "תשלום", "שולם", "שילמה", "סכום", "סך", "של", "על",
    "ב", "ב-", "באמצעות", "דרך", "ש\"ח", "ש״ח", "שח", "שקל", "שקלים", "₪", "nis", "ils", "כלה", "הכלה",
    "לכלה", "טלפון", "טל", "טל'", "נייד", "מספר", "-", "–", "סה\"כ", "סה״כ", "שם", "ו",
}

PHONE_RE = re.compile(r"(?<!\d)(?:\+?972[- ]?|0)(5\d)[- ]?(\d{3})[- ]?(\d{4})(?!\d)")
AMOUNT_RE = re.compile(r"(?<![\d.])(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?(?![\d])")
CURRENCY_RE = re.compile(r"(ש[\"״]?ח|שקלים|שקל|₪|nis|ils)(?=\s|$|[,.;])", re.IGNORECASE)
NAME_PREFIX_RE = re.compile(r"^(?:קבלה|קבלת|הפק\s+קבלה|תפיק\s+קבלה)\s+(?:עבור\s+|ל[-־]?)?", re.IGNORECASE)
# Optional Hebrew prefix letters that can be glued to a word: ב (with), ו (and), ה (the).
PREFIX = r"(?:[בוה][-־]?)?"


class ParseError(ValueError):
    """Raised when the sentence is missing something we need."""


@dataclass
class ReceiptRequest:
    name: str
    amount: Decimal
    method: str
    phone: str | None = None  # international format, e.g. 972521234567
    kind: str | None = None   # מקדמה / יתרה / תשלום מלא

    @property
    def first_name(self) -> str:
        return self.name.split()[0] if self.name else ""


def _cut(text: str, span: tuple[int, int]) -> str:
    return (text[: span[0]] + " " + text[span[1]:]).strip()


def _find_keyword(text: str, table: dict[str, list[str]]) -> tuple[str | None, str]:
    """Find the first keyword from `table` as a whole word; return (display, text-without-it)."""
    aliases = sorted(((a, disp) for disp, al in table.items() for a in al), key=lambda x: -len(x[0]))
    lower = text.lower()
    for alias, display in aliases:
        pattern = r"(?<![\w'׳])" + PREFIX + re.escape(alias) + r"(?![\w'׳])"
        m = re.search(pattern, lower)
        if m:
            return display, _cut(text, m.span())
    return None, text


def parse_message(text: str) -> ReceiptRequest:
    original = text
    text = " ".join(text.replace("\n", " ").split())
    if not text:
        raise ParseError("ההודעה ריקה")

    # 1. Phone number (so its digits are not mistaken for the amount)
    phone = None
    m = PHONE_RE.search(text)
    if m:
        phone = "972" + m.group(1) + m.group(2) + m.group(3)
        text = _cut(text, m.span())

    # 2. Payment method and kind
    method, text = _find_keyword(text, PAYMENT_METHODS)
    kind, text = _find_keyword(text, PAYMENT_KINDS)

    # 3. Amount
    m = AMOUNT_RE.search(text)
    if not m:
        raise ParseError("לא מצאתי סכום")
    try:
        amount = Decimal(m.group(1).replace(",", "") + ("." + m.group(2) if m.group(2) else ""))
    except InvalidOperation as exc:  # pragma: no cover - regex guarantees digits
        raise ParseError("הסכום לא תקין") from exc
    if amount <= 0:
        raise ParseError("הסכום חייב להיות גדול מאפס")
    text = _cut(text, m.span())
    text = CURRENCY_RE.sub(" ", text)

    if not method:
        raise ParseError("לא מצאתי צורת תשלום (למשל: ביט, מזומן, העברה, אשראי, פייבוקס, צ'ק)")

    # 4. Whatever is left is the name
    text = re.sub(r"[,;:|/\\()\[\]{}!?]+", " ", text)
    text = NAME_PREFIX_RE.sub("", text.strip())
    words = [w.strip(".-–\"'״") for w in text.split()]
    words = [w for w in words if w and w.lower() not in FILLER_WORDS]
    name = " ".join(words)
    if not name:
        raise ParseError("לא מצאתי את שם הכלה")
    if re.search(r"\d", name):
        raise ParseError(f"לא הצלחתי להבין את ההודעה: \"{original.strip()}\"")

    return ReceiptRequest(name=name, amount=amount, method=method, phone=phone, kind=kind)


def format_amount(amount: Decimal) -> str:
    if amount == amount.to_integral_value():
        return f"{int(amount):,} ₪"
    return f"{amount:,.2f} ₪"
