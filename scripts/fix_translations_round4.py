#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Round 4: 修复 fr 中未转义的 `'` 导致 aapt 失败

Android XML 资源中字符串包含 ' 时有两种处理：
1. 用双引号包字符串 "l'x"  (l'x 需不需要转义)
2. 用单引号转义 \'  (l\'x)
我们采用 2 (Android 文档推荐)

同时其它语言里如有 `'` 也加转义。
"""
import re
from pathlib import Path

ROOT = Path(r"e:\PhtonView\app\src\main\res")
LANGS = ["de", "es", "fr", "ja", "ko", "ru"]


def fix_apostrophes(lang: str):
    src = ROOT / f"values-{lang}" / "strings.xml"
    text = src.read_text(encoding="utf-8")
    out = []
    i = 0
    fixed = 0
    while i < len(text):
        m = re.match(r'<string\s+name="([^"]+)"\s*>', text[i:])
        if m:
            name = m.group(1)
            j = i + m.end()
            end = text.find("</string>", j)
            content = text[j:end]
            new_content = content.replace("'", "\\'")
            if new_content != content:
                fixed += content.count("'")
            out.append(f'<string name="{name}">{new_content}</string>')
            i = end + len("</string>")
            continue
        out.append(text[i])
        i += 1
    src.write_text("".join(out), encoding="utf-8")
    print(f"  {lang}: apostrophes fixed = {fixed}")


def main():
    print("=== Round 4: escape apostrophes in all lang files ===")
    for lang in LANGS:
        fix_apostrophes(lang)


if __name__ == "__main__":
    main()
