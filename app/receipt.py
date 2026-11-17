"""Draw a receipt as a PNG image (Hebrew, right-to-left)."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from bidi.algorithm import get_display
from PIL import Image, ImageDraw, ImageFont

FONTS_DIR = Path(__file__).parent / "fonts"

WIDTH, HEIGHT = 1080, 1350
MARGIN = 90
BG = (255, 255, 255)
INK = (34, 34, 34)
MUTED = (120, 120, 120)
ACCENT = (176, 141, 87)      # soft gold
LINE = (226, 220, 210)
PANEL = (250, 246, 240)


@dataclass
class ReceiptData:
    number: int
    date: str
    customer_name: str
    amount: str
    method: str
    description: str
    business_name: str
    business_id_label: str = ""
    business_id: str = ""
    business_phone: str = ""
    business_address: str = ""
    business_email: str = ""
    logo_path: str = ""


def _font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    name = "ReceiptSans-Bold.ttf" if bold else "ReceiptSans-Regular.ttf"
    # BASIC layout + python-bidi works everywhere (no libraqm needed).
    return ImageFont.truetype(str(FONTS_DIR / name), size, layout_engine=ImageFont.Layout.BASIC)


def _vis(text: str) -> str:
    return get_display(text, base_dir="R")


def _text_w(draw: ImageDraw.ImageDraw, text: str, font) -> int:
    return int(draw.textlength(_vis(text), font=font))


def _right(draw, x_right: int, y: int, text: str, font, fill=INK):
    draw.text((x_right - _text_w(draw, text, font), y), _vis(text), font=font, fill=fill)


def _left(draw, x_left: int, y: int, text: str, font, fill=INK):
    draw.text((x_left, y), _vis(text), font=font, fill=fill)


def _center(draw, y: int, text: str, font, fill=INK):
    draw.text(((WIDTH - _text_w(draw, text, font)) // 2, y), _vis(text), font=font, fill=fill)


def _fit(draw, text: str, max_w: int, size: int, bold=False):
    while size > 20 and _text_w(draw, text, _font(size, bold)) > max_w:
        size -= 2
    return _font(size, bold)


def render_receipt(data: ReceiptData, out_path: Path) -> Path:
    img = Image.new("RGB", (WIDTH, HEIGHT), BG)
    d = ImageDraw.Draw(img)
    right = WIDTH - MARGIN
    y = 70

    # Header: optional logo + business details
    if data.logo_path and Path(data.logo_path).is_file():
        logo = Image.open(data.logo_path).convert("RGBA")
        logo.thumbnail((260, 160))
        img.paste(logo, ((WIDTH - logo.width) // 2, y), logo)
        y += logo.height + 25

    _center(d, y, data.business_name, _fit(d, data.business_name, WIDTH - 2 * MARGIN, 56, bold=True), INK)
    y += 80
    details = []
    if data.business_id:
        details.append(f"{data.business_id_label} {data.business_id}".strip())
    if data.business_phone:
        details.append(f"טל׳ {data.business_phone}")
    if details:
        _center(d, y, "  |  ".join(details), _font(28), MUTED)
        y += 42
    extra = "  |  ".join(x for x in (data.business_address, data.business_email) if x)
    if extra:
        _center(d, y, extra, _fit(d, extra, WIDTH - 2 * MARGIN, 28), MUTED)
        y += 42

    y += 25
    d.line((MARGIN, y, right, y), fill=ACCENT, width=3)
    y += 45

    # Title row
    _right(d, right, y, f"קבלה מס׳ {data.number}", _font(52, bold=True))
    _left(d, MARGIN, y + 14, data.date, _font(32), MUTED)
    y += 70
    _right(d, right, y, "מקור", _font(26), ACCENT)
    y += 70

    # Details
    rows = [
        ("התקבל מאת", data.customer_name),
        ("עבור", data.description),
        ("אמצעי תשלום", data.method),
    ]
    label_font, value_font = _font(32), _font(38, bold=True)
    for label, value in rows:
        _right(d, right, y, label, label_font, MUTED)
        _left(d, MARGIN, y - 4, value, _fit(d, value, 560, 38, bold=True) if value else value_font)
        y += 62
        d.line((MARGIN, y, right, y), fill=LINE, width=2)
        y += 30

    # Total panel
    y += 20
    panel_h = 160
    d.rounded_rectangle((MARGIN, y, right, y + panel_h), radius=24, fill=PANEL, outline=LINE, width=2)
    _right(d, right - 40, y + 58, "סה״כ שולם", _font(40))
    # The amount is drawn left-to-right as-is so it reads "2,350 ₪".
    d.text((MARGIN + 40, y + 38), data.amount, font=_font(72, bold=True), fill=INK)
    y += panel_h + 60

    _center(d, y, "תודה רבה ומזל טוב!", _font(40, bold=True), ACCENT)

    # Footer, then trim the unused space at the bottom
    y += 110
    _center(d, y, "מסמך ממוחשב", _font(24), MUTED)
    img = img.crop((0, 0, WIDTH, min(HEIGHT, y + 70)))

    out_path.parent.mkdir(parents=True, exist_ok=True)
    img.save(out_path, "PNG", optimize=True)
    return out_path
