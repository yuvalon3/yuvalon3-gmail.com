from decimal import Decimal

import pytest

from app.parser import ParseError, format_amount, parse_message


@pytest.mark.parametrize(
    "text,name,amount,method,phone,kind",
    [
        ("דנה כהן 1500 ביט", "דנה כהן", "1500", "ביט", None, None),
        ('נועה לוי, 2,300 ש"ח, העברה בנקאית', "נועה לוי", "2300", "העברה בנקאית", None, None),
        ("קבלה ללאה אברהם 800 במזומן 052-1234567", "לאה אברהם", "800", "מזומן", "972521234567", None),
        ("שירה 1200₪ אשראי מקדמה", "שירה", "1200", "כרטיס אשראי", None, "מקדמה"),
        ("רחל ביטון 500 ביט", "רחל ביטון", "500", "ביט", None, None),
        ("מיכל 350.50 שקל בפייבוקס", "מיכל", "350.50", "פייבוקס", None, None),
        ("הדס 2000 צ'ק יתרה +972541112222", "הדס", "2000", "צ'ק", "972541112222", "יתרה"),
        ("סופי מזרחי 900 בהעברה", "סופי מזרחי", "900", "העברה בנקאית", None, None),
        ("ביט 700 יעל", "יעל", "700", "ביט", None, None),
    ],
)
def test_parse(text, name, amount, method, phone, kind):
    r = parse_message(text)
    assert (r.name, r.amount, r.method, r.phone, r.kind) == (name, Decimal(amount), method, phone, kind)


@pytest.mark.parametrize("text", ["", "דנה כהן ביט", "דנה כהן 1500", "1500 ביט", "דנה 0 ביט"])
def test_parse_errors(text):
    with pytest.raises(ParseError):
        parse_message(text)


def test_format_amount():
    assert format_amount(Decimal("1500")) == "1,500 ₪"
    assert format_amount(Decimal("350.5")) == "350.50 ₪"
