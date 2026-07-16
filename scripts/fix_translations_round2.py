#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第二轮修复：
1) 按 key 名称强制翻译 metering_*_short 等关键条目
2) 对所有条目强制做全角冒号修复（仅 de/es/fr/ru）
3) 处理 ko/ru/ja 中残留的预翻译 CJK 短标签
4) 修复 ko 中仍残留的全角冒号

直接读取、修改、写回。保留所有已经正确的条目。
"""
import re
from pathlib import Path

ROOT = Path(r"e:\PhtonView\app\src\main\res")
LANGS = ["de", "es", "fr", "ja", "ko", "ru"]
FW_COLON_LANGS = {"de", "es", "fr", "ru"}
FW_COLON = "："
HW_COLON = ": "

# === 按 name 强制翻译（不论当前值） ===
# 来自 EN 参考的 key 列表
NAME_TR = {
    "metering_matrix_short": {
        "de": "Glob", "es": "Gen", "fr": "Gen", "ja": "全局", "ko": "전역", "ru": "Общ"
    },
    "metering_center_short": {
        "de": "Mit", "es": "Cen", "fr": "Cen", "ja": "中央", "ko": "중앙", "ru": "Цен"
    },
    "metering_spot_short": {
        "de": "Spot", "es": "Spot", "fr": "Spot", "ja": "点", "ko": "점", "ru": "Тчк"
    },
    "flash_manual_popup_hint": {
        "de": "Dieses Modell erfordert manuelles Aufklappen des Blitzes über die Seitentaste; andernfalls wird der Blitz nicht ausgelöst",
        "es": "Este modelo requiere abrir el flash manualmente con el botón lateral; de lo contrario el flash no se disparará",
        "fr": "Ce modèle nécessite d'ouvrir le flash manuellement via le bouton latéral ; sinon le flash ne se déclenchera pas",
        "ja": "このモデルでは、側面のボタンでフラッシュを手動で開く必要があります。そうしないとフラッシュは発光しません",
        "ko": "이 모델은 측면 버튼으로 플래시를 수동으로 열어야 합니다. 그렇지 않으면 플래시가 작동하지 않습니다",
        "ru": "В этой модели вспышку нужно поднимать вручную боковой кнопкой; иначе вспышка не сработает"
    },
    # credits_summary - keep designer name in CJK (intentional)
    "credits_summary": {
        "de": "Programmierung: LanChe · UI-Design: 安信一・プロス (One Bucket)",
        "es": "Programación: LanChe · Diseño UI: 安信一・プロス (One Bucket)",
        "fr": "Programme : LanChe · Design UI : 安信一・プロス (One Bucket)",
        "ja": "Program by LanChe · UI design by 安信一・プロス (One Bucket)",
        "ko": "Program by LanChe · UI design by 安信一・プロス (One Bucket)",
        "ru": "Программа: LanChe · UI-дизайн: 安信一・プロス (One Bucket)"
    },
    # 修正 ko 中含 '칩' 的错误翻译（应为 '카메라'）
    "waiting_for_camera": {
        "ko": "카메라 대기 중…"
    },
    "preparing_main": {
        "ko": "카메라 인터페이스 준비 중…"
    },
    "usb_device_detected": {
        "ko": "USB 카메라 감지: %1$s"
    },
    "wifi_pair_desc": {
        "ko": "카메라 IP 주소와 포트를 입력하세요. 예: 192.168.1.1:15740"
    },
    "camera_maintenance": {
        "ko": "카메라 유지보수"
    },
    "onboarding_desc": {
        "ko": "USB 또는 Wi-Fi 로 카메라를 전문적으로 원격 제어하세요."
    },
    "onboarding_permission_desc": {
        "ko": "PhtonView 는 USB OTG 로 카메라와 통신합니다. 메시지가 표시되면 USB 접근을 허용하세요."
    },
    "onboarding_connection_title": {
        "ko": "카메라 설정"
    },
    "splash_subtitle": {
        "ko": "전문가용 카메라 리모컨"
    },
    # ko 中专有 B(벌브) 等的细节
    "bulb": {
        "ko": "벌브"
    },
    "bulb_duration": {
        "ko": "벌브 시간: %d 초"
    },
}


def xml_escape(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def fix_colons(text: str, lang: str) -> str:
    if lang not in FW_COLON_LANGS:
        return text
    # 只在 latin/cyrillic 文本中替换
    return text.replace(FW_COLON, HW_COLON)


def rewrite_lang(lang: str):
    src = ROOT / f"values-{lang}" / "strings.xml"
    text = src.read_text(encoding="utf-8")
    out = []
    i = 0
    fixed_by_name = 0
    fixed_colon = 0
    while i < len(text):
        m = re.match(r'<string\s+name="([^"]+)"\s*>', text[i:])
        if m:
            name = m.group(1)
            j = i + m.end()
            end = text.find("</string>", j)
            content = text[j:end]
            new_content = content
            # 1) by-name translation
            tr = NAME_TR.get(name, {}).get(lang)
            if tr is not None and tr != content:
                new_content = tr
                fixed_by_name += 1
            # 2) always do colon fix
            after_colon = fix_colons(new_content, lang)
            if after_colon != new_content:
                fixed_colon += 1
                new_content = after_colon
            out.append(f'<string name="{name}">{xml_escape(new_content)}</string>')
            i = end + len("</string>")
            continue
        out.append(text[i])
        i += 1
    src.write_text("".join(out), encoding="utf-8")
    print(f"  {lang}: by_name={fixed_by_name}  colons={fixed_colon}")


def main():
    print("=== Round 2: by-name translation + colon fix ===")
    for lang in LANGS:
        rewrite_lang(lang)


if __name__ == "__main__":
    main()
