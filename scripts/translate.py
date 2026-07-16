#!/usr/bin/env python3
"""
PhtonView 多语言翻译脚本（迭代 #15）。

读 de/es/fr/ja/ko/ru 缺失的 strings key，调用翻译服务补全。
支持 3 个 provider：deep（DeepL Free）、google（Google Translate public endpoint）、
                  libre（LibreTranslate 公共实例）。

使用示例：
  # 用 Google Translate（无需 API key，但不稳定）
  python scripts/translate.py --provider google --langs de,es,fr,ja,ko,ru

  # 用 DeepL（需环境变量 DEEPL_API_KEY）
  DEEPL_API_KEY=xxx python scripts/translate.py --provider deepl --langs de

  # 用 LibreTranslate（公共实例，无需 key）
  python scripts/translate.py --provider libre --langs de --libre-url https://libretranslate.de

依赖：仅 Python 3.7+ 标准库 + urllib。
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

PROJECT_ROOT = Path(__file__).parent.parent
RES_DIR = PROJECT_ROOT / "app" / "src" / "main" / "res"
EN_DIR = RES_DIR / "values"
TARGET_LANGS = ["de", "es", "fr", "ja", "ko", "ru"]

PROVIDER_GOOGLE = "google"
PROVIDER_DEEPL = "deepl"
PROVIDER_LIBRE = "libre"


def load_strings(lang_dir: Path) -> dict:
    """解析 res/values-XX/strings.xml，返回 {name: value} 字典。"""
    path = lang_dir / "strings.xml"
    if not path.exists():
        return {}
    tree = ET.parse(path)
    root = tree.getroot()
    result = {}
    for s in root.findall("string"):
        name = s.get("name")
        if name:
            result[name] = s.text or ""
    return result


def save_strings(lang_dir: Path, strings: dict) -> None:
    """写回 res/values-XX/strings.xml，保留格式。"""
    path = lang_dir / "strings.xml"
    tree = ET.parse(path)
    root = tree.getroot()
    existing_names = {s.get("name") for s in root.findall("string")}
    added = 0
    for name, value in strings.items():
        if name in existing_names:
            continue
        elem = ET.SubElement(root, "string")
        elem.set("name", name)
        elem.text = value
        added += 1
    # 用 lxml 风格写入，但 ET 默认会转义 & < >
    ET.indent(tree, space="    ")
    tree.write(path, encoding="utf-8", xml_declaration=True)
    print(f"  [ok] {path.relative_to(PROJECT_ROOT)} - added {added} new keys")


def translate_google(text: str, target_lang: str) -> str:
    """Google Translate public endpoint（无需 API key，不保证可用）。"""
    url = "https://translate.googleapis.com/translate_a/single"
    params = {
        "client": "gtx",
        "sl": "en",
        "tl": target_lang,
        "dt": "t",
        "q": text,
    }
    full_url = f"{url}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(full_url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    # data[0] 是 [segment, translated_text, ...]
    parts = []
    for chunk in data[0]:
        if chunk and len(chunk) > 0:
            parts.append(chunk[0])
    return "".join(parts)


def translate_deepl(text: str, target_lang: str, api_key: str) -> str:
    """DeepL Free API。需要 DEEPL_API_KEY 环境变量。"""
    url = "https://api-free.deepl.com/v2/translate"
    params = {
        "text": text,
        "source_lang": "EN",
        "target_lang": target_lang.upper(),
    }
    data = urllib.parse.urlencode(params).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Authorization": f"DeepL-Auth-Key {api_key}",
            "User-Agent": "PhtonViewTranslate/1.0",
        },
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data["translations"][0]["text"]


def translate_libre(text: str, target_lang: str, base_url: str) -> str:
    """LibreTranslate 公共实例。可自部署或用 libretranslate.de。"""
    url = f"{base_url.rstrip('/')}/translate"
    payload = {
        "q": text,
        "source": "en",
        "target": target_lang,
        "format": "text",
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json", "User-Agent": "PhtonView/1.0"},
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        result = json.loads(resp.read().decode("utf-8"))
    return result["translatedText"]


def translate_batch(texts: dict, target_lang: str, provider: str, **kwargs) -> dict:
    """批量翻译；返回 {name: translated}，失败的 key 跳过。"""
    result = {}
    failures = []
    for i, (name, value) in enumerate(texts.items(), 1):
        if not value or not value.strip():
            result[name] = value
            continue
        # 跳过已含未翻译占位的（fallback 是 en 文本，应该翻译）
        try:
            if provider == PROVIDER_GOOGLE:
                translated = translate_google(value, target_lang)
            elif provider == PROVIDER_DEEPL:
                translated = translate_deepl(value, target_lang, kwargs["api_key"])
            elif provider == PROVIDER_LIBRE:
                translated = translate_libre(value, target_lang, kwargs["libre_url"])
            else:
                raise ValueError(f"unknown provider: {provider}")
            result[name] = translated
            if i % 20 == 0:
                print(f"    {i}/{len(texts)} translated")
        except Exception as e:
            failures.append((name, value, str(e)))
            result[name] = value  # fallback
        time.sleep(0.2)  # rate limit
    if failures:
        print(f"  [warn] {len(failures)} translation failures; see fallback")
    return result


def main():
    ap = argparse.ArgumentParser(description="PhtonView 多语言翻译补全")
    ap.add_argument("--provider", choices=[PROVIDER_GOOGLE, PROVIDER_DEEPL, PROVIDER_LIBRE],
                    default=PROVIDER_GOOGLE)
    ap.add_argument("--langs", default="de,es,fr,ja,ko,ru",
                    help="comma-separated target language codes (de/es/fr/ja/ko/ru)")
    ap.add_argument("--libre-url", default="https://libretranslate.de",
                    help="LibreTranslate base URL (for --provider libre)")
    ap.add_argument("--dry-run", action="store_true",
                    help="don't write changes, just show what would be translated")
    ap.add_argument("--rewrite-fallback", action="store_true",
                    help="re-translate TODO: translate fallback entries (replace en text with real translation)")
    args = ap.parse_args()

    en_strings = load_strings(EN_DIR)
    print(f"[info] {len(en_strings)} en keys loaded")

    if args.provider == PROVIDER_DEEPL:
        api_key = os.environ.get("DEEPL_API_KEY")
        if not api_key:
            print("[error] DEEPL_API_KEY env var required for --provider deepl")
            sys.exit(1)
        kwargs = {"api_key": api_key}
    elif args.provider == PROVIDER_LIBRE:
        kwargs = {"libre_url": args.libre_url}
    else:
        kwargs = {}

    target_langs = [x.strip() for x in args.langs.split(",") if x.strip()]
    for lang in target_langs:
        if lang not in TARGET_LANGS:
            print(f"[warn] {lang} not in known list {TARGET_LANGS}, proceeding anyway")
        print(f"\n=== Translating to {lang} ===")
        lang_dir = RES_DIR / f"values-{lang}"
        existing = load_strings(lang_dir)
        if args.rewrite_fallback:
            # 重写模式：找出 en fallback（value == en value）的 key 重新翻译
            missing = {n: en_strings[n] for n in en_strings
                       if n in existing and existing[n] == en_strings[n]}
            print(f"  {len(missing)} fallback keys to re-translate")
        else:
            missing = {n: v for n, v in en_strings.items() if n not in existing}
        if not missing:
            print(f"  [skip] {lang} has no work to do")
            continue
        if args.dry_run:
            for n, v in list(missing.items())[:5]:
                print(f"    sample: {n} = {v!r}")
            print(f"    ... and {len(missing) - 5} more")
            continue
        translated = translate_batch(missing, lang, args.provider, **kwargs)
        if args.rewrite_fallback:
            # 重写模式：直接 update existing
            merged = {**existing, **translated}
        else:
            merged = {**existing, **translated}
        save_strings(lang_dir, merged)
    print("\n[done]")


if __name__ == "__main__":
    main()
