#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
扫描所有非英文翻译文件，定位：
  1. 与英文完全一致（未翻译）
  2. 含有 CJK 字符（混入中文）
  3. 含有其他语言乱码（mojibake，例如 Ã©, ä¸­）
  4. 含有问号 ? 表明翻译引擎挂了
  5. 缺失条目

输出每个语言每个 string 的状态。生成的报告写到 /tmp/translate_report.txt
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(r"e:\PhtonView\app\src\main\res")
EN_FILE = ROOT / "values" / "strings.xml"
LANGS = ["de", "es", "fr", "ja", "ko", "ru"]
DIRS = {l: ROOT / f"values-{l}" / "strings.xml" for l in LANGS}

CJK_RANGE = re.compile(r"[\u3000-\u303f\u4e00-\u9fff\uff00-\uffef]")
LATIN = re.compile(r"^[A-Za-z0-9 _\-+/.,:;()%'\"&!?*]*$")
# 不同语言"看起来像"该语言的最小字符集启发式
NATIVE_HINTS = {
    "de": re.compile(r"[äöüßÄÖÜ]"),
    "es": re.compile(r"[ñ¿¡áéíóúÁÉÍÓÚñÑ]"),
    "fr": re.compile(r"[àâçéèêëîïôûùüÿœæÀÂÇÉÈÊËÎÏÔÛÙÜŸŒÆ]"),
    "ja": re.compile(r"[\u3040-\u309f\u30a0-\u30ff]"),
    "ko": re.compile(r"[\uac00-\ud7af]"),
    "ru": re.compile(r"[\u0400-\u04ff]"),
}


def load(path: Path):
    tree = ET.parse(path)
    root = tree.getroot()
    return {s.get("name"): (s.text or "") for s in root.findall("string")}


def main():
    en = load(EN_FILE)
    print(f"[EN] {len(en)} keys")
    report_lines = []
    for lang, path in DIRS.items():
        print(f"\n=== {lang} ({path}) ===")
        tr = load(path)
        report_lines.append(f"\n=== {lang} ===")
        same, cjk, mojibake, qmark, missing = 0, 0, 0, 0, 0
        for k, en_v in en.items():
            tv = tr.get(k)
            if tv is None:
                missing += 1
                report_lines.append(f"  [MISSING] {k}: {en_v!r}")
                continue
            if tv == en_v and en_v.strip() and not LATIN.match(en_v):
                same += 1
                report_lines.append(f"  [UNTRANS] {k}: {tv!r}")
            if CJK_RANGE.search(tv):
                # ja 允许部分 CJK
                if lang != "ja":
                    cjk += 1
                    report_lines.append(f"  [CJK] {k}: {tv!r}")
            if "?" in tv and "?" not in en_v and len(tv) >= 5:
                qmark += 1
                report_lines.append(f"  [QMARK] {k}: {tv!r}")
            # mojibake: 连续 2+ 个非 ASCII 但不在原生字符集
            has_native = bool(NATIVE_HINTS[lang].search(tv))
            weird = 0
            for ch in tv:
                if ord(ch) > 127 and not has_native and lang != "ja" and lang != "ko" and lang != "ru":
                    if not LATIN.match(ch) and ch not in " '\"&!?*-+/.,:;()%":
                        weird += 1
            if weird >= 2 and not has_native:
                mojibake += 1
                report_lines.append(f"  [MOJI] {k}: {tv!r}")
        print(f"  UNTRANS={same}  CJK={cjk}  QMARK={qmark}  MOJI={mojibake}  MISSING={missing}")
        report_lines.append(f"  UNTRANS={same}  CJK={cjk}  QMARK={qmark}  MOJI={mojibake}  MISSING={missing}")
    out = Path("e:/PhtonView/scripts/translate_report.txt")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(report_lines), encoding="utf-8")
    print(f"\n报告写入: {out}")


if __name__ == "__main__":
    main()
