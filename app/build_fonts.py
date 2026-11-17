"""Build the receipt fonts (Noto Sans Latin + Noto Sans Hebrew merged into one file).

Pillow can't fall back between fonts, and Noto Sans Hebrew has no digits, so we merge them.
Needs the Debian/Ubuntu package `fonts-noto-core` and `pip install fonttools`.

    python -m app.build_fonts [NOTO_DIR]
"""
from __future__ import annotations

import sys
import tempfile
from pathlib import Path

OUT_DIR = Path(__file__).parent / "fonts"
DEFAULT_NOTO = Path("/usr/share/fonts/truetype/noto")


def build(noto_dir: Path = DEFAULT_NOTO, out_dir: Path = OUT_DIR) -> None:
    from fontTools import subset
    from fontTools.merge import Merger, Options
    from fontTools.ttLib import TTFont

    out_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for weight in ("Regular", "Bold"):
            latin = TTFont(noto_dir / f"NotoSans-{weight}.ttf")
            sub = subset.Subsetter(subset.Options(layout_features=["*"]))
            sub.populate(unicodes=list(range(0x20, 0x7F)) + [0xA0, 0x2013, 0x2014, 0x2022, 0x2116])
            sub.subset(latin)
            latin_path = Path(tmp) / f"latin-{weight}.ttf"
            latin.save(latin_path)
            merged = Merger(Options(drop_tables=["vmtx", "vhea", "MATH"])).merge(
                [str(latin_path), str(noto_dir / f"NotoSansHebrew-{weight}.ttf")]
            )
            merged.save(out_dir / f"ReceiptSans-{weight}.ttf")
            print("built", out_dir / f"ReceiptSans-{weight}.ttf")


if __name__ == "__main__":
    build(Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_NOTO)
