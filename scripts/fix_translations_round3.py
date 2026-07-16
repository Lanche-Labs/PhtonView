#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Round 3: 修复 double-escape `&amp;amp;` 问题
之前 xml_escape 重复作用在已经转义过的 & 上导致三重转义。
正确做法：只在 NEW 内容（来自 TR dict）上调用 xml_escape，
对原有 content 不再 escape。
"""
import re
from pathlib import Path

ROOT = Path(r"e:\PhtonView\app\src\main\res")
LANGS = ["de", "es", "fr", "ja", "ko", "ru"]

# 只对来自 TR dict 的新内容 escape；不在原有内容上 escape
# 简单粗暴：把所有 &amp;amp; 还原为 &amp;
# 同样还原 &amp;amp;quot; 等（虽然没出现过）

def un_double_escape(text: str) -> str:
    """Reverse &amp;amp; -> &amp; (one level)"""
    # Multiple passes in case of 3x
    while "&amp;amp;" in text:
        text = text.replace("&amp;amp;", "&amp;")
    return text

# 还要修复一些明显问题：fr 中 `&apos;` 没出现，apostrophe 用了 raw `'`

def fix_file(lang: str):
    src = ROOT / f"values-{lang}" / "strings.xml"
    text = src.read_text(encoding="utf-8")
    new_text = un_double_escape(text)
    if new_text != text:
        src.write_text(new_text, encoding="utf-8")
        # Count
        cnt = text.count("&amp;amp;")
        print(f"  {lang}: fixed {cnt} double-escapes")
    else:
        print(f"  {lang}: clean")

def main():
    print("=== Round 3: fix double-escape ===")
    for lang in LANGS:
        fix_file(lang)

if __name__ == "__main__":
    main()
