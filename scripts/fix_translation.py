"""
PhtonView 翻译文件回滚（针对 #15 翻译补全失败）。

Google Translate public endpoint 翻译结果有质量问题：
- fr 文件中含中文字符（"Spot" → "点"）
- de/es 中部分翻译为 en fallback
- ja/ko/ru 中翻译错误

策略：把 6 个语言文件中"含非本地字符"（中文/日文/韩文/俄文/西文/德文等异常字符）
的 string value 改回 en 文本（fallback）。

中文字符的判断：\u4e00-\u9fff（CJK 统一表意）
日文字符：\u3040-\u30ff（假名）
韩文字符：\uac00-\ud7ff（Hangul）
俄文字符：\u0400-\u04ff（西里尔）
"""
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path("e:/PhtonView/app/src/main/res")
EN = ROOT / "values"
TARGETS = ["de", "es", "fr", "ja", "ko", "ru"]
# 每个语言允许的字符范围（拉丁扩展 + 本地 + 数字 + 标点）
PATTERNS_BAD = {
    "de": re.compile(r"[\u4e00-\u9fff]"),  # 中文不该在 de
    "es": re.compile(r"[\u4e00-\u9fff]"),
    "fr": re.compile(r"[\u4e00-\u9fff]"),
    "ja": re.compile(r"[\u0400-\u04ff]"),  # 俄文不该在 ja
    "ko": re.compile(r"[\u0400-\u04ff]"),
    "ru": re.compile(r"[\u3040-\u30ff]|[\uac00-\ud7af]"),  # 日文/韩文不该在 ru
}

# 加载 en
en_tree = ET.parse(EN / "strings.xml")
en_map = {s.get("name"): (s.text or "") for s in en_tree.getroot().findall("string")}

for lang in TARGETS:
    target = ROOT / f"values-{lang}" / "strings.xml"
    tree = ET.parse(target)
    root = tree.getroot()
    bad_pat = PATTERNS_BAD[lang]
    fixed_bad = 0
    fixed_q = 0
    for s in root.findall("string"):
        name = s.get("name")
        value = s.text or ""
        en_value = en_map.get(name, "")
        # 1) 错语言字符 → en
        if bad_pat.search(value):
            s.text = en_value
            fixed_bad += 1
            continue
        # 2) Google Translate 退化：值含 ? 但 en 不含 ? → 可能是重音字符被替换
        if "?" in value and "?" not in en_value and value != en_value:
            # 但保留翻译中合理使用 ? 的情况（如 "Bulb?"）
            # 启发式：如果 ? 出现在翻译文本中且 en 没有，且翻译长度 >= en 长度 80%
            if len(value) >= len(en_value) * 0.7:
                s.text = en_value
                fixed_q += 1
    ET.indent(tree, space="    ")
    tree.write(target, encoding="utf-8", xml_declaration=True)
    print(f"[{lang}] fixed {fixed_bad} bad-lang + {fixed_q} question-mark fallback")
