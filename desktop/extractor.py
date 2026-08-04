# -*- coding: utf-8 -*-
"""
Merchant / Agent name extraction
=================================
Heuristic, multi-language extractor that scans the first pages of a PDF
for field labels such as:

    Arabic : اسم التاجر، اسم الوكيل، اسم المورد، المستورد، الشركة، ...
    English: Merchant, Agent, Importer, Supplier, Company, Name, ...

The detected name is used as the *title* of the generated Excel file.
"""

import re
from typing import Optional

import fitz  # PyMuPDF

from core import to_logical

# Field labels ordered by specificity (longer/more specific first).
LABEL_PATTERNS = [
    # Arabic
    r"اسم\s*التاجر", r"اسم\s*الوكيل", r"اسم\s*المورد", r"اسم\s*المستورد",
    r"اسم\s*الشركة", r"اسم\s*العميل", r"اسم\s*المتعامل", r"الاسم\s*التجاري",
    r"اسم\s*المؤسسة", r"اسم\s*المنشأة", r"اسم\s*المدير", r"اسم\s*صاحب",
    r"تاجر", r"وكيل", r"مورد", r"مستورد", r"المستورد", r"الشركة", r"الاسم",
    r"بيان\s*الشركة", r"جهة\s*التوريد",
    # English
    r"merchant\s*name", r"agent\s*name", r"trader\s*name", r"importer",
    r"supplier\s*name", r"company\s*name", r"trade\s*name", r"dealer\s*name",
    r"merchant", r"agent", r"supplier", r"consignee", r"buyer", r"customer",
    r"company", r"business\s*name",
]

_LABEL_RE = re.compile("|".join(LABEL_PATTERNS), re.IGNORECASE)


def _clean_name(raw: str) -> Optional[str]:
    """Trim a candidate name; reject labels / punctuation-only strings."""
    if raw is None:
        return None
    name = raw.strip()
    # strip a leading colon / dash (in case separator was captured)
    name = name.lstrip(":–—-\t ").strip()
    name = re.sub(r"\s+", " ", name)
    # remove trailing dangling separators
    name = name.rstrip(":–—-\t ").strip().strip(".")
    if not name:
        return None
    if len(name) > 80:                      # too long to be a name
        return None
    if re.fullmatch(r"[\W_]+", name):       # punctuation only
        return None
    # a name should contain at least one letter/digit
    if not re.search(r"[^\W_]", name, re.UNICODE):
        return None
    return name


def _value_after_label(rest: str) -> Optional[str]:
    """Extract the value that follows a label in the same line."""
    rest = rest.strip()
    rest = rest.lstrip(":–—-\t ").strip()
    # cut trailing label-ish noise (other field names on the same line)
    rest = re.split(r"\s{2,}|\t", rest)[0]
    return _clean_name(rest)


def extract_merchant_name(pdf_path: str, first_pages: int = 5) -> Optional[str]:
    """
    Best-effort extraction of the merchant / agent name from a PDF.
    Returns None when nothing convincing is found.
    """
    try:
        doc = fitz.open(pdf_path)
    except Exception:
        return None

    limit = min(first_pages, doc.page_count)
    for i in range(limit):
        page = doc.load_page(i)
        lines = [to_logical(l) for l in (page.get_text("text") or "").splitlines() if to_logical(l)]

        for idx, line in enumerate(lines):
            ln = line.strip()
            if not ln:
                continue
            m = _LABEL_RE.search(ln)
            if not m:
                continue
            start = m.start()
            # 1) value on the same line after the label
            after = ln[m.end():]
            value = _value_after_label(after)
            if value:
                doc.close()
                return value
            # 2) lookahead: value on the following line(s)
            for k in range(1, 4):
                if idx + k < len(lines):
                    nxt = lines[idx + k].strip()
                    if not nxt:
                        continue
                    # stop if the next line looks like another label
                    if _LABEL_RE.search(nxt) and len(nxt) < 40:
                        break
                    value = _clean_name(nxt)
                    if value and not _LABEL_RE.fullmatch(value):
                        doc.close()
                        return value

    # fallback 1: PDF document title metadata
    meta = (doc.metadata or {}).get("title") or ""
    if meta.strip():
        value = _clean_name(meta)
        if value:
            doc.close()
            return value

    # fallback 2: first meaningful line of page 1 (not a label, not a number)
    try:
        lines = [to_logical(l) for l in (doc.load_page(0).get_text("text") or "").splitlines()]
        for ln in lines:
            ln = ln.strip()
            if not ln or _LABEL_RE.search(ln):
                continue
            if re.fullmatch(r"[\d\s\W]+", ln):
                continue
            value = _clean_name(ln)
            if value and 3 <= len(value) <= 80:
                doc.close()
                return value
    except Exception:
        pass

    doc.close()
    return None


if __name__ == "__main__":
    import sys
    for f in sys.argv[1:]:
        print(f, "→", extract_merchant_name(f))
