#!/usr/bin/env python3
"""
Generates synthetic, UNHIGHLIGHTED "scanned" documents for the OcrMatcher demo.

For each case it writes (into ../examples):
  <name>.png         the page image (what a scan would look like; no highlighting)
  <name>.html        fake OCR output: one absolutely-positioned <span class="ocrw"> per word,
                     laid over the image, in the (possibly scrambled) order an OCR engine emitted them
  <name>.query.txt   the phrase an LLM would have returned as the supporting quote

The three cases:
  echo_two_column       Figure 1 of the abstract: two-column echo report whose OCR text is
                        interleaved line-by-line across the columns
  lab_table             Figure 2: lab table whose OCR spans are in column-by-column order, so the
                        label "Creatinine" is far from its value "1.29" in span order

All patient-style content is invented. No real data.
"""
import html
import os
from PIL import Image, ImageDraw, ImageFont

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "examples")
os.makedirs(OUT, exist_ok=True)

SANS = "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"
SANS_B = "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"


class Page:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.img = Image.new("RGB", (w, h), "white")
        self.d = ImageDraw.Draw(self.img)
        self.words = []  # dicts: text, x, y, w, h, order_key

    def text(self, x, y, s, size=26, bold=False, tag=None):
        """Draw a string word by word; record a box per word. Returns end x."""
        font = ImageFont.truetype(SANS_B if bold else SANS, size)
        space = self.d.textlength(" ", font=font)
        cx = x
        for word in s.split(" "):
            if not word:
                continue
            wlen = self.d.textlength(word, font=font)
            self.d.text((cx, y), word, font=font, fill=(20, 20, 20))
            self.words.append(dict(text=word, x=round(cx), y=y, w=round(wlen),
                                   h=round(size * 1.25), tag=tag))
            cx += wlen + space
        return cx

    def rule(self, y, x0=10, x1=None):
        x1 = x1 or self.w - 10
        for x in range(x0, x1, 4):  # dotted separator like the lab report
            self.d.point((x, y), fill=(120, 120, 120))
            self.d.point((x + 1, y), fill=(120, 120, 120))

    def save(self, name, order, ocr_text_fixes=None, query="", expected=""):
        ocr_text_fixes = ocr_text_fixes or {}
        self.img.save(os.path.join(OUT, name + ".png"))
        spans = []
        for i in order:
            wd = self.words[i]
            txt = ocr_text_fixes.get(i, wd["text"])
            spans.append(
                f'<span class="ocrw" style="left:{wd["x"]}px;top:{wd["y"]}px;'
                f'width:{wd["w"]}px;height:{wd["h"]}px">{html.escape(txt)}</span>')
        page = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>{name}</title>
<style>
  body {{ margin:0; background:#eee; }}
  .page {{ position:relative; width:{self.w}px; height:{self.h}px; background:url('{name}.png') no-repeat; }}
  /* OCR text is invisible; it sits over the scan so highlights line up with the pixels */
  .ocrw {{ position:absolute; color:transparent; white-space:nowrap; font:20px sans-serif; }}
  .ocrw mark {{ display:block; width:100%; height:100%; mix-blend-mode:multiply; }}
</style></head>
<body><div class="page">
{chr(10).join(spans)}
</div></body></html>
"""
        with open(os.path.join(OUT, name + ".html"), "w") as f:
            f.write(page)
        with open(os.path.join(OUT, name + ".query.txt"), "w") as f:
            f.write(query + "\n")


# ---------------------------------------------------------------- case 1: two-column echo report
def echo_case():
    p = Page(1000, 430)
    LX, RX = 40, 520
    rows = [  # (left text, right text), y
        ("LEFT VENTRICLE:", None, True),
        ("The left ventricle is normal in size.", "TRICUSPID VALVE:", False),
        ("There is normal left ventricular wall", "Structurally normal tricuspid valve.", False),
        ("thickness. Left ventricular systolic", "There is no tricuspid stenosis. There", False),
        ("function is normal. The LV ejection", "is mild tricuspid regurgitation. There", False),
        ("fraction was determined utilizing", "was insufficient tricuspid", False),
    ]
    y = 30
    idx_by_row = []
    for left, right, header in rows:
        start = len(p.words)
        p.text(LX, y, left, size=28, bold=header)
        mid = len(p.words)
        if right:
            p.text(RX, y, right, size=28, bold=right.endswith(":"))
        idx_by_row.append(list(range(start, len(p.words))))
        y += 62
    # OCR emitted line by line across both columns (this is what scrambles the sentences),
    # and misread "Left" as "Leit" once, as in the abstract.
    order = [i for row in idx_by_row for i in row]
    fixes = {}
    for i, wd in enumerate(p.words):
        if wd["text"] == "Left" and wd["y"] == 30 + 62 * 3:
            fixes[i] = "Leit"
    p.save("echo_two_column", order, fixes,
           query="Structurally normal tricuspid valve. There is no tricuspid stenosis. "
                 "There is mild tricuspid regurgitation.",
           expected="Structurally normal tricuspid valve. There is no tricuspid stenosis. "
                    "There is mild tricuspid regurgitation.")


# ---------------------------------------------------------------- cases 2+3: lab table
def lab_case():
    p = Page(1260, 470)
    C_NAME, C_VAL, C_RANGE, C_FLAG = 18, 670, 895, 1210
    S = 30
    y = 20
    rows = [
        ("BUN", "53", "7 - 25 mg/dL", "H"),
        ("Creatinine", "1.29", "0.70 - 1.30 mg/dL", "-"),
        ("eGFR", "56", ">=60 mL/min/1.73m2", "L"),
    ]
    row_idx = []
    for name, val, rng, flag in rows:
        s = len(p.words)
        p.text(C_NAME, y, name, S)
        p.text(C_VAL, y, val, S)
        p.text(C_RANGE, y, rng, S)
        p.text(C_FLAG, y, flag, S, bold=True)
        row_idx.append(list(range(s, len(p.words))))
        y += 52
        p.rule(y - 6)
    # comment block (indented, two lines)
    s = len(p.words)
    p.text(45, y + 4, "Comment:", 27)
    p.text(45, y + 42,
           "Calculation based on the Chronic Kidney Disease Epidemiology Collaboration (CKD-EPI) equation,", 27)
    p.text(45, y + 80, "2021 refit without adjustment for race.", 27)
    comment_idx = list(range(s, len(p.words)))
    y += 125
    p.rule(y - 6)
    s = len(p.words)
    p.text(C_NAME, y, "BUN/Creatinine Ratio", S)
    p.text(C_VAL, y, "41.1", S)
    p.text(C_RANGE, y, "-", S)
    p.text(C_FLAG, y, "-", S)
    ratio_idx = list(range(s, len(p.words)))
    y += 52
    p.rule(y - 6)
    s = len(p.words)
    p.text(C_NAME, y, "Calcium", S)
    p.text(C_VAL, y, "9.5", S)
    p.text(C_RANGE, y, "8.6 - 10.3 mg/dL", S)
    p.text(C_FLAG, y, "-", S)
    ca_idx = list(range(s, len(p.words)))

    # The quote the LLM returned, as in the abstract's Figure 2 screenshot (the highlighted words there
    # are the creatinine value, its reference range, and the flag).
    query = "Creatinine 1.29 0.70 - 1.30 mg/dL H"

    # OCR span order: the engine emitted the full-width comment paragraph first, then the table
    # one COLUMN at a time (labels, values, reference ranges, flags). In that span order the label
    # "BUN/Creatinine" ends up nearer "1.29" than the label "Creatinine" does, which reproduces the
    # Figure 2 failure with the unmodified matcher.
    def col(x0, x1):
        return [i for i, w in enumerate(p.words) if x0 <= w["x"] < x1 and i not in comment_idx]
    order = comment_idx + col(0, 600) + col(600, 880) + col(880, 1150) + col(1150, 9999)
    p.save("lab_table", order, query=query, expected="")


echo_case()
lab_case()
print("wrote examples to", os.path.abspath(OUT))
