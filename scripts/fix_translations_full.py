#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
修复 de/es/fr/ja/ko/ru 翻译质量问题：

1) 修复 strings.xml EN 参考：把 CJK 短标签替换为拉丁短标签，
   把 flash_manual_popup_hint 由中文改为英文
2) 替换全角冒号 (：) → 半角冒号 (:)（对 de/es/fr/ru）
3) 翻译所有未翻译条目（about_role_*, wifi_hint_*, metering_*_short, ...）
4) 保留语言名（CJK / cyrillic）是正常的
5) 保留 credits_summary 中的 CJK 设计师名（故意）

直接重写所有 values-{lang}/strings.xml，结构按 values/strings.xml 顺序
输出（保留相同 key 集合）。
"""
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(r"e:\PhtonView\app\src\main\res")
EN_FILE = ROOT / "values" / "strings.xml"
LANGS = ["de", "es", "fr", "ja", "ko", "ru"]

# === 1. EN 参考修复：把 CJK 短标签和 zh 提示改成英文 ===
EN_FIXES = {
    "metering_matrix_short": "All",
    "metering_center_short": "Ctr",
    "metering_spot_short": "Spot",
    "flash_manual_popup_hint": (
        "This model requires manually popping up the flash via the side button; "
        "otherwise the flash function will not take effect"
    ),
}

# === 2. 全角冒号替换为半角（仅 de/es/fr/ru）===
FW_COLON_LANGS = {"de", "es", "fr", "ru"}
FW_COLON = "："
HW_COLON = ": "

def fix_colons(text: str, lang: str) -> str:
    if lang not in FW_COLON_LANGS:
        return text
    # 替换 "Foo：bar" -> "Foo: bar"；多个都换
    return text.replace(FW_COLON, HW_COLON)

# === 3. 完整翻译字典（key 为 en 字符串 value） ===
# 每个值是 {lang: 翻译} 字典
TR = {
    # Connection states
    "Connected: %1$s": {
        "de": "Verbunden: %1$s",
        "es": "Conectado: %1$s",
        "fr": "Connecté: %1$s",
        "ja": "接続済み: %1$s",
        "ko": "연결됨: %1$s",
        "ru": "Подключено: %1$s",
    },
    "Error: %1$s": {
        "de": "Fehler: %1$s",
        "es": "Error: %1$s",
        "fr": "Erreur: %1$s",
        "ja": "エラー: %1$s",
        "ko": "오류: %1$s",
        "ru": "Ошибка: %1$s",
    },
    # Camera info format
    "EV %1$+.1f  %2$s  %3$s  ISO %4$d": {
        "de": "EV %1$+.1f  Blende %2$s  Verschluss %3$s  ISO %4$d",
        "es": "EV %1$+.1f  Apertura %2$s  Obturador %3$s  ISO %4$d",
        "fr": "EV %1$+.1f  Ouverture %2$s  Obturateur %3$s  ISO %4$d",
        "ja": "露出 %1$+.1f  絞り %2$s  シャッター %3$s  ISO %4$d",
        "ko": "노출 %1$+.1f  조리개 %2$s  셔터 %3$s  ISO %4$d",
        "ru": "Эксп %1$+.1f  Диафрагма %2$s  Выдержка %3$s  ISO %4$d",
    },
    # Mag format
    "Mag %1$.0fx": {
        "de": "Vergrößerung %1$.0fx",
        "es": "Ampliación %1$.0fx",
        "fr": "Grossissement %1$.0fx",
        "ja": "拡大 %1$.0fx",
        "ko": "확대 %1$.0fx",
        "ru": "Увеличение %1$.0fx",
    },
    # Metering short labels (3-5 chars)
    "All": {
        "de": "Glob", "es": "Gen", "fr": "Gen", "ja": "全局", "ko": "전역", "ru": "Общ"
    },
    "Ctr": {
        "de": "Mit", "es": "Cen", "fr": "Cen", "ja": "中央", "ko": "중앙", "ru": "Цен"
    },
    "Spot": {
        "de": "Spot", "es": "Spot", "fr": "Spot", "ja": "点", "ko": "점", "ru": "Тчк"
    },
    # Long metering
    "Matrix": {
        "de": "Matrix", "es": "Matricial", "fr": "Matricielle",
        "ja": "マルチパターン", "ko": "다중 패턴", "ru": "Матрица"
    },
    "Center": {
        "de": "Mitte", "es": "Central", "fr": "Centrale",
        "ja": "中央部", "ko": "중앙부", "ru": "Центр"
    },
    # Bulb duration
    "Bulb Duration: %d s": {
        "de": "Bulb-Dauer: %d s",
        "es": "Duración bulb: %d s",
        "fr": "Pose B: %d s",
        "ja": "バルブ時間: %d 秒",
        "ko": "벌브 시간: %d 초",
        "ru": "Выдержка Bulb: %d с",
    },
    # Timer delay
    "Self-Timer: %d s": {
        "de": "Selbstauslöser: %d s",
        "es": "Autodisparador: %d s",
        "fr": "Retardateur: %d s",
        "ja": "セルフタイマー: %d 秒",
        "ko": "셀프 타이머: %d 초",
        "ru": "Автоспуск: %d с",
    },
    # Intervalometer
    "Interval: %1$d s, Shots: %2$d": {
        "de": "Intervall: %1$d s, Aufnahmen: %2$d",
        "es": "Intervalo: %1$d s, Tomas: %2$d",
        "fr": "Intervalle: %1$d s, Photos: %2$d",
        "ja": "インターバル: %1$d 秒、枚数: %2$d",
        "ko": "간격: %1$d 초, 장수: %2$d",
        "ru": "Интервал: %1$d с, Кадров: %2$d",
    },
    # AEB
    "AEB: %1$d shots, Step: %2$.1f EV": {
        "de": "Belichtungsreihe: %1$d Aufn., Schritt: %2$.1f EV",
        "es": "Bracketing: %1$d fotos, Paso: %2$.1f EV",
        "fr": "Bracketing: %1$d vues, Palier: %2$.1f IL",
        "ja": "ブラケット: %1$d 枚、ステップ: %2$.1f EV",
        "ko": "브래킷: %1$d 장, 단계: %2$.1f EV",
        "ru": "Брекетинг: %1$d кадров, Шаг: %2$.1f EV",
    },
    # EV comp format
    "EV %+,.1f": {
        "de": "EV %+,.1f",
        "es": "EV %+,.1f",
        "fr": "IL %+,.1f",
        "ja": "露出補正 %+,.1f",
        "ko": "노출 보정 %+,.1f",
        "ru": "Эксп %+,.1f",
    },
    "EV Compensation": {
        "de": "Belichtungskorrektur", "es": "Compensación EV",
        "fr": "Correction d'exposition", "ja": "露出補正",
        "ko": "노출 보정", "ru": "Компенсация экспозиции"
    },
    "Exposure Compensation: %1$+.1f EV": {
        "de": "Belichtungskorrektur: %1$+.1f EV",
        "es": "Compensación de exposición: %1$+.1f EV",
        "fr": "Correction d'exposition: %1$+.1f IL",
        "ja": "露出補正: %1$+.1f EV",
        "ko": "노출 보정: %1$+.1f EV",
        "ru": "Компенсация экспозиции: %1$+.1f EV",
    },
    # EV format used in pre-translated files
    "Belichtungskorrektur: %1$+.1f EV": {
        "de": "Belichtungskorrektur: %1$+.1f EV",
        "es": "Compensación exp.: %1$+.1f EV",
        "fr": "Correction d'exposition: %1$+.1f IL",
        "ja": "露出補正: %1$+.1f EV",
        "ko": "노출 보정: %1$+.1f EV",
        "ru": "Компенсация экспозиции: %1$+.1f EV",
    },
    "Compensación exp.: %1$+.1f EV": {
        "de": "Belichtungskorrektur: %1$+.1f EV",
        "es": "Compensación exp.: %1$+.1f EV",
        "fr": "Correction d'exposition: %1$+.1f IL",
        "ja": "露出補正: %1$+.1f EV",
        "ko": "노출 보정: %1$+.1f EV",
        "ru": "Компенсация экспозиции: %1$+.1f EV",
    },
    "노출 보정: %1$+.1f EV": {
        "de": "Belichtungskorrektur: %1$+.1f EV",
        "es": "Compensación exp.: %1$+.1f EV",
        "fr": "Correction d'exposition: %1$+.1f IL",
        "ja": "露出補正: %1$+.1f EV",
        "ko": "노출 보정: %1$+.1f EV",
        "ru": "Компенсация экспозиции: %1$+.1f EV",
    },
    "Компенсация эксп.: %1$+.1f EV": {
        "de": "Belichtungskorrektur: %1$+.1f EV",
        "es": "Compensación exp.: %1$+.1f EV",
        "fr": "Correction d'exposition: %1$+.1f IL",
        "ja": "露出補正: %1$+.1f EV",
        "ko": "노출 보정: %1$+.1f EV",
        "ru": "Компенсация экспозиции: %1$+.1f EV",
    },
    # AF Mode group
    "AF Mode": {
        "de": "AF-Modus", "es": "Modo AF", "fr": "Mode AF",
        "ja": "AF モード", "ko": "AF 모드", "ru": "Режим АФ"
    },
    "AF Area": {
        "de": "AF-Bereich", "es": "Área AF", "fr": "Zone AF",
        "ja": "AF エリア", "ko": "AF 영역", "ru": "Зона АФ"
    },
    "Single": {
        "de": "Einzel", "es": "Único", "fr": "Point",
        "ja": "シングル", "ko": "단일", "ru": "Точечный"
    },
    "Zone": {
        "de": "Zone", "es": "Zona", "fr": "Zone",
        "ja": "ゾーン", "ko": "영역", "ru": "Зона"
    },
    "Tracking": {
        "de": "Tracking", "es": "Seguimiento", "fr": "Suivi",
        "ja": "追尾", "ko": "추적", "ru": "Слежение"
    },
    "Face": {
        "de": "Gesicht", "es": "Cara", "fr": "Visage",
        "ja": "顔", "ko": "얼굴", "ru": "Лицо"
    },
    "Focus": {
        "de": "Fokus", "es": "Enfoque", "fr": "Mise au point",
        "ja": "フォーカス", "ko": "포커스", "ru": "Фокус"
    },
    "Focus Mode": {
        "de": "Fokusmodus", "es": "Modo de enfoque",
        "fr": "Mode de mise au point",
        "ja": "フォーカスモード", "ko": "포커스 모드", "ru": "Режим фокуса"
    },
    # USB / WiFi connection messages
    "USB camera detected: %1$s": {
        "de": "USB-Kamera erkannt: %1$s",
        "es": "Cámara USB detectada: %1$s",
        "fr": "Caméra USB détectée: %1$s",
        "ja": "USB カメラを検出: %1$s",
        "ko": "USB 카메라 감지: %1$s",
        "ru": "Обнаружена USB камера: %1$s",
    },
    "Enter camera IP address and port. Example: 192.168.1.1:15740": {
        "de": "IP-Adresse und Port der Kamera eingeben. Beispiel: 192.168.1.1:15740",
        "es": "Introduzca IP y puerto de la cámara. Ejemplo: 192.168.1.1:15740",
        "fr": "Saisir l'adresse IP et le port de la caméra. Exemple: 192.168.1.1:15740",
        "ja": "カメラの IP アドレスとポートを入力。例: 192.168.1.1:15740",
        "ko": "카메라 IP 주소와 포트 입력. 예: 192.168.1.1:15740",
        "ru": "Введите IP-адрес и порт камеры. Например: 192.168.1.1:15740",
    },
    # wifi hints
    "1. Enable Wi-Fi on the camera.\\n2. Connect your phone to the camera hotspot (e.g. NIKON_Z6_xxx).\\n3. Default address is usually 192.168.1.1:15740.": {
        "de": "1. Aktivieren Sie WLAN an der Kamera.\\n2. Verbinden Sie das Telefon mit dem Kamera-Hotspot (z.B. NIKON_Z6_xxx).\\n3. Standardadresse ist normalerweise 192.168.1.1:15740.",
        "es": "1. Active Wi-Fi en la cámara.\\n2. Conecte el móvil al hotspot de la cámara (p.ej. NIKON_Z6_xxx).\\n3. Dirección por defecto suele ser 192.168.1.1:15740.",
        "fr": "1. Activez le Wi-Fi sur l'appareil.\\n2. Connectez le téléphone au point d'accès de l'appareil (ex. NIKON_Z6_xxx).\\n3. L'adresse par défaut est généralement 192.168.1.1:15740.",
        "ja": "1. カメラの Wi-Fi を有効化。\\n2. カメラの中継局（例: NIKON_Z6_xxx）に接続。\\n3. デフォルトアドレスは通常 192.168.1.1:15740。",
        "ko": "1. 카메라의 Wi-Fi를 켭니다.\\n2. 폰을 카메라 핫스팟에 연결 (예: NIKON_Z6_xxx).\\n3. 기본 주소는 보통 192.168.1.1:15740.",
        "ru": "1. Включите Wi-Fi на камере.\\n2. Подключите телефон к точке доступа камеры (напр. NIKON_Z6_xxx).\\n3. Адрес по умолчанию обычно 192.168.1.1:15740."
    },
    "1. Insert the WU-1a/1b adapter and enable Wi-Fi on the camera.\\n2. Connect your phone to the adapter hotspot (e.g. NIKON_WU_xxx).\\n3. Default address is usually 192.168.1.100:15740.": {
        "de": "1. Stecken Sie den WU-1a/1b-Adapter ein und aktivieren Sie WLAN.\\n2. Verbinden Sie das Telefon mit dem Adapter-Hotspot (z.B. NIKON_WU_xxx).\\n3. Standardadresse ist normalerweise 192.168.1.100:15740.",
        "es": "1. Inserte el adaptador WU-1a/1b y active Wi-Fi.\\n2. Conecte el móvil al hotspot del adaptador (p.ej. NIKON_WU_xxx).\\n3. Dirección por defecto suele ser 192.168.1.100:15740.",
        "fr": "1. Insérez l'adaptateur WU-1a/1b et activez le Wi-Fi.\\n2. Connectez le téléphone au point d'accès de l'adaptateur (ex. NIKON_WU_xxx).\\n3. L'adresse par défaut est généralement 192.168.1.100:15740.",
        "ja": "1. WU-1a/1b アダプタを装着して Wi-Fi を有効化。\\n2. アダプタの中継局（例: NIKON_WU_xxx）に接続。\\n3. デフォルトアドレスは通常 192.168.1.100:15740。",
        "ko": "1. WU-1a/1b 어댑터를 끼우고 Wi-Fi를 켭니다.\\n2. 폰을 어댑터 핫스팟에 연결 (예: NIKON_WU_xxx).\\n3. 기본 주소는 보통 192.168.1.100:15740.",
        "ru": "1. Установите адаптер WU-1a/1b и включите Wi-Fi.\\n2. Подключите телефон к точке доступа адаптера (напр. NIKON_WU_xxx).\\n3. Адрес по умолчанию обычно 192.168.1.100:15740."
    },
    "1. Enable Wi-Fi on the camera and start EOS Utility mode.\\n2. Connect your phone to the camera hotspot.\\n3. Default address is usually 192.168.1.1:15740.": {
        "de": "1. Aktivieren Sie WLAN und starten Sie den EOS Utility-Modus.\\n2. Verbinden Sie das Telefon mit dem Kamera-Hotspot.\\n3. Standardadresse ist normalerweise 192.168.1.1:15740.",
        "es": "1. Active Wi-Fi y el modo EOS Utility.\\n2. Conecte el móvil al hotspot de la cámara.\\n3. Dirección por defecto suele ser 192.168.1.1:15740.",
        "fr": "1. Activez le Wi-Fi et le mode EOS Utility.\\n2. Connectez le téléphone au point d'accès.\\n3. L'adresse par défaut est généralement 192.168.1.1:15740.",
        "ja": "1. Wi-Fi を有効にして EOS Utility モードを開始。\\n2. カメラの中継局に接続。\\n3. デフォルトアドレスは通常 192.168.1.1:15740。",
        "ko": "1. Wi-Fi를 켜고 EOS Utility 모드 시작.\\n2. 카메라 핫스팟에 연결.\\n3. 기본 주소는 보통 192.168.1.1:15740.",
        "ru": "1. Включите Wi-Fi и режим EOS Utility.\\n2. Подключите телефон к точке доступа камеры.\\n3. Адрес по умолчанию обычно 192.168.1.1:15740."
    },
    "1. Enable PC Remote on the camera.\\n2. Connect your phone to the camera hotspot (e.g. DIRECT-xxxx).\\n3. Enter the camera IP shown on screen.": {
        "de": "1. Aktivieren Sie PC Remote an der Kamera.\\n2. Verbinden Sie das Telefon mit dem Kamera-Hotspot (z.B. DIRECT-xxxx).\\n3. Geben Sie die auf dem Bildschirm angezeigte IP ein.",
        "es": "1. Active PC Remote en la cámara.\\n2. Conecte el móvil al hotspot (p.ej. DIRECT-xxxx).\\n3. Introduzca la IP mostrada en pantalla.",
        "fr": "1. Activez PC Remote sur l'appareil.\\n2. Connectez le téléphone au point d'accès (ex. DIRECT-xxxx).\\n3. Saisissez l'IP affichée à l'écran.",
        "ja": "1. カメラで PC Remote を有効化。\\n2. カメラの中継局（例: DIRECT-xxxx）に接続。\\n3. 画面に表示された IP を入力。",
        "ko": "1. 카메라에서 PC Remote 활성화.\\n2. 카메라 핫스팟에 연결 (예: DIRECT-xxxx).\\n3. 화면에 표시된 IP 입력.",
        "ru": "1. Включите PC Remote на камере.\\n2. Подключите телефон к точке доступа (напр. DIRECT-xxxx).\\n3. Введите IP-адрес с экрана камеры."
    },
    "1. Enable tether shooting on the camera.\\n2. Connect your phone to the camera hotspot.\\n3. Enter the camera IP shown on screen.": {
        "de": "1. Aktivieren Sie Tether-Aufnahme an der Kamera.\\n2. Verbinden Sie das Telefon mit dem Kamera-Hotspot.\\n3. Geben Sie die auf dem Bildschirm angezeigte IP ein.",
        "es": "1. Active disparo tethered en la cámara.\\n2. Conecte el móvil al hotspot.\\n3. Introduzca la IP mostrada en pantalla.",
        "fr": "1. Activez la prise de vue tethered.\\n2. Connectez le téléphone au point d'accès.\\n3. Saisissez l'IP affichée à l'écran.",
        "ja": "1. カメラでテザー撮影を有効化。\\n2. カメラの中継局に接続。\\n3. 画面に表示された IP を入力。",
        "ko": "1. 카메라에서 테더 촬영 활성화.\\n2. 카메라 핫스팟에 연결.\\n3. 화면에 표시된 IP 입력.",
        "ru": "1. Включите съёмку по tethering.\\n2. Подключите телефон к точке доступа.\\n3. Введите IP-адрес с экрана камеры."
    },
    "1. Enable tethering on the camera.\\n2. Connect your phone to the camera hotspot.\\n3. Enter the camera IP shown on screen.": {
        "de": "1. Aktivieren Sie Tethering an der Kamera.\\n2. Verbinden Sie das Telefon mit dem Kamera-Hotspot.\\n3. Geben Sie die auf dem Bildschirm angezeigte IP ein.",
        "es": "1. Active tethering en la cámara.\\n2. Conecte el móvil al hotspot.\\n3. Introduzca la IP mostrada en pantalla.",
        "fr": "1. Activez le tethering.\\n2. Connectez le téléphone au point d'accès.\\n3. Saisissez l'IP affichée à l'écran.",
        "ja": "1. カメラでテザリングを有効化。\\n2. カメラの中継局に接続。\\n3. 画面に表示された IP を入力。",
        "ko": "1. 카메라에서 테더링 활성화.\\n2. 카메라 핫스팟에 연결.\\n3. 화면에 표시된 IP 입력.",
        "ru": "1. Включите tethering на камере.\\n2. Подключите телефон к точке доступа.\\n3. Введите IP-адрес с экрана камеры."
    },
    "1. Enable PC mode on the camera.\\n2. Connect your phone to the camera hotspot.\\n3. Enter the camera IP shown on screen.": {
        "de": "1. Aktivieren Sie den PC-Modus an der Kamera.\\n2. Verbinden Sie das Telefon mit dem Kamera-Hotspot.\\n3. Geben Sie die auf dem Bildschirm angezeigte IP ein.",
        "es": "1. Active el modo PC en la cámara.\\n2. Conecte el móvil al hotspot.\\n3. Introduzca la IP mostrada en pantalla.",
        "fr": "1. Activez le mode PC sur l'appareil.\\n2. Connectez le téléphone au point d'accès.\\n3. Saisissez l'IP affichée à l'écran.",
        "ja": "1. カメラで PC モードを有効化。\\n2. カメラの中継局に接続。\\n3. 画面に表示された IP を入力。",
        "ko": "1. 카메라에서 PC 모드 활성화.\\n2. 카메라 핫스팟에 연결.\\n3. 화면에 표시된 IP 입력.",
        "ru": "1. Включите режим PC на камере.\\n2. Подключите телефон к точке доступа.\\n3. Введите IP-адрес с экрана камеры."
    },
    "Enter the camera IP and port shown on screen or in the manual.": {
        "de": "Geben Sie die auf dem Bildschirm oder im Handbuch angezeigte IP und den Port ein.",
        "es": "Introduzca la IP y el puerto mostrados en pantalla o en el manual.",
        "fr": "Saisissez l'IP et le port indiqués à l'écran ou dans le manuel.",
        "ja": "画面またはマニュアルに表示されている IP とポートを入力。",
        "ko": "화면이나 설명서에 표시된 IP와 포트를 입력하세요.",
        "ru": "Введите IP-адрес и порт с экрана камеры или из инструкции."
    },
    "Saved: %1$s": {
        "de": "Gespeichert: %1$s",
        "es": "Guardado: %1$s",
        "fr": "Enregistré: %1$s",
        "ja": "保存済み: %1$s",
        "ko": "저장됨: %1$s",
        "ru": "Сохранено: %1$s",
    },
    "Version %1$s": {
        "de": "Version %1$s",
        "es": "Versión %1$s",
        "fr": "Version %1$s",
        "ja": "バージョン %1$s",
        "ko": "버전 %1$s",
        "ru": "Версия %1$s",
    },
    "Select %1$s": {
        "de": "%1$s wählen",
        "es": "Seleccionar %1$s",
        "fr": "Sélectionner %1$s",
        "ja": "%1$s を選択",
        "ko": "%1$s 선택",
        "ru": "Выбрать %1$s",
    },
    "No photos found on camera": {
        "de": "Keine Fotos auf der Kamera",
        "es": "No hay fotos en la cámara",
        "fr": "Aucune photo sur l'appareil",
        "ja": "カメラに写真がありません",
        "ko": "카메라에 사진이 없습니다",
        "ru": "На камере нет фотографий",
    },
    "Shutter Speed": {
        "de": "Verschlusszeit", "es": "Velocidad de obturación",
        "fr": "Vitesse d'obturation",
        "ja": "シャッタースピード", "ko": "셔터 속도", "ru": "Выдержка"
    },
    "Tap value to input": {
        "de": "Zum Eingeben tippen",
        "es": "Toca para introducir",
        "fr": "Tapez pour saisir",
        "ja": "タップで入力",
        "ko": "탭하여 입력",
        "ru": "Нажмите для ввода",
    },
    "Download & Preview": {
        "de": "Herunterladen & Vorschau",
        "es": "Descargar y previsualizar",
        "fr": "Télécharger et prévisualiser",
        "ja": "ダウンロードとプレビュー",
        "ko": "다운로드 및 미리보기",
        "ru": "Скачать и просмотреть",
    },
    "Gallery": {
        "de": "Galerie", "es": "Galería", "fr": "Galerie",
        "ja": "ギャラリー", "ko": "갤러리", "ru": "Галерея"
    },
    "Search settings": {
        "de": "Einstellungen suchen",
        "es": "Buscar ajustes",
        "fr": "Rechercher réglages",
        "ja": "設定を検索",
        "ko": "설정 검색",
        "ru": "Поиск настроек",
    },
    "Type to filter settings": {
        "de": "Tippen zum Filtern",
        "es": "Escribe para filtrar",
        "fr": "Tapez pour filtrer",
        "ja": "入力で絞り込み",
        "ko": "입력하여 필터",
        "ru": "Введите для фильтра",
    },
    "No matching settings": {
        "de": "Keine passenden Einstellungen",
        "es": "Sin ajustes",
        "fr": "Aucun réglage",
        "ja": "該当する設定なし",
        "ko": "일치하는 설정 없음",
        "ru": "Нет совпадений",
    },
    # Flash popup hint
    "Manual flash will fire next time you open the flash": {
        "de": "Manueller Blitz wird beim nächsten Öffnen ausgelöst",
        "es": "El flash manual se disparará la próxima vez que lo abras",
        "fr": "Le flash manuel se déclenchera à la prochaine ouverture",
        "ja": "手動フラッシュは次回ポップアップ時に発光します",
        "ko": "수동 플래시는 다음에 열릴 때 발광합니다",
        "ru": "Ручная вспышка сработает при следующем открытии",
    },
    # Reset to defaults
    "Reset to Defaults": {
        "de": "Auf Standard zurücksetzen",
        "es": "Restablecer valores",
        "fr": "Réinitialiser",
        "ja": "デフォルトに戻す",
        "ko": "기본값으로 재설정",
        "ru": "Сброс к стандартным",
    },
    "Capture multiple frames": {
        "de": "Mehrere Bilder aufnehmen",
        "es": "Capturar varios fotogramas",
        "fr": "Capturer plusieurs images",
        "ja": "連続撮影",
        "ko": "연속 프레임 촬영",
        "ru": "Серия снимков",
    },
    "Long exposure timer": {
        "de": "Langzeitbelichtung",
        "es": "Exposición prolongada",
        "fr": "Pose longue",
        "ja": "長時間露光タイマー",
        "ko": "장시간 노출 타이머",
        "ru": "Таймер длительной выдержки",
    },
    "Delayed single shot": {
        "de": "Verzögerte Einzelaufnahme",
        "es": "Disparo único retardado",
        "fr": "Déclenchement retardé",
        "ja": "セルフタイマー",
        "ko": "지연 단일 촬영",
        "ru": "Отложенный одиночный снимок",
    },
    "Time-lapse sequence": {
        "de": "Zeitraffer-Sequenz",
        "es": "Secuencia time-lapse",
        "fr": "Séquence time-lapse",
        "ja": "タイムラプス",
        "ko": "타임랩스",
        "ru": "Таймлапс",
    },
    "Auto exposure bracketing": {
        "de": "Belichtungsreihe",
        "es": "Bracketing de exposición",
        "fr": "Bracketing d'exposition",
        "ja": "自動露出ブラケット",
        "ko": "자동 노출 브래킷",
        "ru": "Брекетинг экспозиции",
    },
    # USB guide
    "How to connect via USB": {
        "de": "USB-Verbindung herstellen",
        "es": "Cómo conectar por USB",
        "fr": "Connexion USB",
        "ja": "USB 接続方法",
        "ko": "USB 연결 방법",
        "ru": "Подключение по USB",
    },
    "1. Use a USB OTG adapter/cable to connect the camera to your phone.": {
        "de": "1. USB-OTG-Adapter/Kabel verwenden, um die Kamera mit dem Telefon zu verbinden.",
        "es": "1. Usa un adaptador/cable USB OTG para conectar la cámara al móvil.",
        "fr": "1. Utilisez un adaptateur/câble USB OTG pour relier l'appareil au téléphone.",
        "ja": "1. USB OTG アダプタ／ケーブルでカメラとスマホを接続。",
        "ko": "1. USB OTG 어댑터/케이블로 카메라와 폰을 연결하세요.",
        "ru": "1. Подключите камеру к телефону через адаптер/кабель USB OTG."
    },
    "2. Turn the camera on and set it to PC / Remote / MTP mode.": {
        "de": "2. Kamera einschalten und in den PC-/Remote-/MTP-Modus setzen.",
        "es": "2. Enciende la cámara y ponla en modo PC / Remoto / MTP.",
        "fr": "2. Allumez l'appareil et passez en mode PC / Télécommande / MTP.",
        "ja": "2. カメラの電源を入れ、PC/リモート/MTP モードに設定。",
        "ko": "2. 카메라 전원을 켜고 PC / 원격 / MTP 모드로 설정.",
        "ru": "2. Включите камеру и переведите её в режим PC / Remote / MTP."
    },
    "3. Allow USB permission when the system dialog pops up.": {
        "de": "3. USB-Berechtigung im Systemdialog erteilen.",
        "es": "3. Permite el acceso USB cuando aparezca el diálogo.",
        "fr": "3. Autorisez l'accès USB dans la boîte de dialogue système.",
        "ja": "3. システムダイアログで USB アクセスを許可。",
        "ko": "3. 시스템 대화상자에서 USB 접근을 허용하세요.",
        "ru": "3. Разрешите доступ по USB в системном диалоге."
    },
    '4. The app will detect the camera and you can tap "Connect USB".': {
        "de": '4. Die App erkennt die Kamera und Sie können auf „USB verbinden" tippen.',
        "es": '4. La app detectará la cámara y podrás tocar "Conectar USB".',
        "fr": '4. L\'application détectera l\'appareil et vous pourrez appuyer sur « Connecter USB ».',
        "ja": "4. アプリを検知し、「USB 接続」をタップ。",
        "ko": '4. 앱이 카메라를 감지하면 "USB 연결"을 탭하세요.',
        "ru": '4. Приложение обнаружит камеру — нажмите «Подключить USB».'
    },
    "Note: Some phones require enabling OTG in system settings.": {
        "de": "Hinweis: Bei einigen Telefonen muss OTG in den Systemeinstellungen aktiviert werden.",
        "es": "Nota: algunos móviles requieren activar OTG en los ajustes del sistema.",
        "fr": "Remarque: certains téléphones exigent d'activer OTG dans les paramètres système.",
        "ja": "注意: 一部の端末ではシステム設定で OTG を有効化する必要があります。",
        "ko": "참고: 일부 기기는 시스템 설정에서 OTG를 켜야 합니다.",
        "ru": "Примечание: на некоторых телефонах нужно включить OTG в настройках системы."
    },
    "USB Connection Guide": {
        "de": "USB-Verbindungsanleitung",
        "es": "Guía de conexión USB",
        "fr": "Guide de connexion USB",
        "ja": "USB 接続ガイド",
        "ko": "USB 연결 안내",
        "ru": "Руководство по USB",
    },
    # Onboarding
    "Welcome to PhtonView": {
        "de": "Willkommen bei PhtonView",
        "es": "Bienvenido a PhtonView",
        "fr": "Bienvenue dans PhtonView",
        "ja": "PhtonView へようこそ",
        "ko": "PhtonView 에 오신 것을 환영합니다",
        "ru": "Добро пожаловать в PhtonView",
    },
    "Professional remote control for your camera via USB or WiFi.": {
        "de": "Professionelle Fernsteuerung Ihrer Kamera per USB oder WLAN.",
        "es": "Control remoto profesional de tu cámara por USB o WiFi.",
        "fr": "Pilotage professionnel de votre appareil par USB ou Wi-Fi.",
        "ja": "USB または WiFi でカメラをプロフェッショナルにリモート制御。",
        "ko": "USB 또는 WiFi 로 카메라를 전문적으로 원격 제어하세요.",
        "ru": "Профессиональное удалённое управление камерой через USB или Wi-Fi."
    },
    "USB Permission": {
        "de": "USB-Berechtigung",
        "es": "Permiso USB",
        "fr": "Autorisation USB",
        "ja": "USB 権限",
        "ko": "USB 권한",
        "ru": "Разрешение USB",
    },
    "PhtonView uses USB OTG to communicate with your camera. Please allow USB access when prompted.": {
        "de": "PhtonView nutzt USB OTG, um mit der Kamera zu kommunizieren. Bitte USB-Zugriff erlauben.",
        "es": "PhtonView usa USB OTG para comunicarse con la cámara. Permite el acceso USB cuando se pida.",
        "fr": "PhtonView utilise USB OTG pour communiquer avec l'appareil. Autorisez l'accès USB.",
        "ja": "PhtonView は USB OTG でカメラと通信します。求められたら USB アクセスを許可してください。",
        "ko": "PhtonView 는 USB OTG 로 카메라와 통신합니다. 메시지가 표시되면 USB 접근을 허용하세요.",
        "ru": "PhtonView использует USB OTG для связи с камерой. Разрешите доступ при запросе."
    },
    "Choose Theme": {
        "de": "Thema wählen",
        "es": "Elegir tema",
        "fr": "Choisir le thème",
        "ja": "テーマを選択",
        "ko": "테마 선택",
        "ru": "Выбор темы",
    },
    "Select a comfortable appearance for your workspace.": {
        "de": "Wählen Sie eine angenehme Darstellung für Ihren Arbeitsbereich.",
        "es": "Seleccione una apariencia cómoda para su espacio.",
        "fr": "Choisissez une apparence confortable pour votre espace.",
        "ja": "作業環境に合った外観を選択してください。",
        "ko": "작업 환경에 맞는 편안한 외관을 선택하세요.",
        "ru": "Выберите комфортное оформление."
    },
    "Camera Setup": {
        "de": "Kameraeinrichtung",
        "es": "Configuración de cámara",
        "fr": "Configuration de l'appareil",
        "ja": "カメラ設定",
        "ko": "카메라 설정",
        "ru": "Настройка камеры",
    },
    "Set your preferred interface mode.": {
        "de": "Wählen Sie Ihren bevorzugten Oberflächenmodus.",
        "es": "Configure su modo de interfaz preferido.",
        "fr": "Définissez votre mode d'interface préféré.",
        "ja": "お好みのインターフェースモードを設定。",
        "ko": "선호하는 인터페이스 모드를 설정하세요.",
        "ru": "Выберите предпочтительный режим интерфейса."
    },
    # Settings
    "Settings": {
        "de": "Einstellungen",
        "es": "Ajustes",
        "fr": "Réglages",
        "ja": "設定",
        "ko": "설정",
        "ru": "Настройки",
    },
    "About": {
        "de": "Über",
        "es": "Acerca de",
        "fr": "À propos",
        "ja": "アプリについて",
        "ko": "정보",
        "ru": "О приложении",
    },
    "Appearance": {
        "de": "Erscheinungsbild",
        "es": "Apariencia",
        "fr": "Apparence",
        "ja": "外観",
        "ko": "외관",
        "ru": "Внешний вид",
    },
    "General": {
        "de": "Allgemein",
        "es": "General",
        "fr": "Général",
        "ja": "一般",
        "ko": "일반",
        "ru": "Общие",
    },
    "Language": {
        "de": "Sprache",
        "es": "Idioma",
        "fr": "Langue",
        "ja": "言語",
        "ko": "언어",
        "ru": "Язык",
    },
    "Theme": {
        "de": "Thema",
        "es": "Tema",
        "fr": "Thème",
        "ja": "テーマ",
        "ko": "테마",
        "ru": "Тема",
    },
    "Connection": {
        "de": "Verbindung",
        "es": "Conexión",
        "fr": "Connexion",
        "ja": "接続",
        "ko": "연결",
        "ru": "Подключение",
    },
    "Status": {
        "de": "Status",
        "es": "Estado",
        "fr": "État",
        "ja": "ステータス",
        "ko": "상태",
        "ru": "Статус",
    },
    "Connect": {
        "de": "Verbinden",
        "es": "Conectar",
        "fr": "Connecter",
        "ja": "接続",
        "ko": "연결",
        "ru": "Подключить",
    },
    "Disconnect": {
        "de": "Trennen",
        "es": "Desconectar",
        "fr": "Déconnecter",
        "ja": "切断",
        "ko": "연결 해제",
        "ru": "Отключить",
    },
    "Camera Brand": {
        "de": "Kameramarke",
        "es": "Marca de cámara",
        "fr": "Marque de l'appareil",
        "ja": "カメラブランド",
        "ko": "카메라 브랜드",
        "ru": "Марка камеры",
    },
    "WiFi Pairing": {
        "de": "WLAN-Kopplung",
        "es": "Emparejamiento WiFi",
        "fr": "Appairage Wi-Fi",
        "ja": "Wi-Fi ペアリング",
        "ko": "Wi-Fi 페어링",
        "ru": "Сопряжение Wi-Fi",
    },
    "WiFi Pair": {
        "de": "WLAN koppeln",
        "es": "Emparejar WiFi",
        "fr": "Appairer Wi-Fi",
        "ja": "Wi-Fi ペアリング",
        "ko": "Wi-Fi 페어링",
        "ru": "Сопряжение Wi-Fi",
    },
    # Camera
    "Camera": {
        "de": "Kamera",
        "es": "Cámara",
        "fr": "Appareil",
        "ja": "カメラ",
        "ko": "카메라",
        "ru": "Камера",
    },
    "Burst": {
        "de": "Serie",
        "es": "Ráfaga",
        "fr": "Rafale",
        "ja": "連写",
        "ko": "연사",
        "ru": "Серия",
    },
    "Burst Count": {
        "de": "Serienbildanzahl",
        "es": "Cantidad de ráfaga",
        "fr": "Nombre de vues",
        "ja": "連写枚数",
        "ko": "연사 매수",
        "ru": "Кол-во кадров",
    },
    "Capture": {
        "de": "Auslösen",
        "es": "Capturar",
        "fr": "Déclencher",
        "ja": "撮影",
        "ko": "촬영",
        "ru": "Съёмка",
    },
    "Capture Settings": {
        "de": "Aufnahmeeinstellungen",
        "es": "Ajustes de captura",
        "fr": "Réglages de capture",
        "ja": "撮影設定",
        "ko": "촬영 설정",
        "ru": "Настройки съёмки",
    },
    "Advanced Capture": {
        "de": "Erweiterte Aufnahme",
        "es": "Captura avanzada",
        "fr": "Capture avancée",
        "ja": "高度な撮影",
        "ko": "고급 촬영",
        "ru": "Расширенная съёмка",
    },
    "White Balance": {
        "de": "Weißabgleich",
        "es": "Balance de blancos",
        "fr": "Balance des blancs",
        "ja": "ホワイトバランス",
        "ko": "화이트밸런스",
        "ru": "Баланс белого",
    },
    "Flash Mode": {
        "de": "Blitzmodus",
        "es": "Modo flash",
        "fr": "Mode flash",
        "ja": "フラッシュモード",
        "ko": "플래시 모드",
        "ru": "Режим вспышки",
    },
    "Flash Compensation": {
        "de": "Blitzkorrektur",
        "es": "Compensación flash",
        "fr": "Correction du flash",
        "ja": "フラッシュ補正",
        "ko": "플래시 보정",
        "ru": "Компенсация вспышки",
    },
    "Storage Target": {
        "de": "Speicherziel",
        "es": "Destino de almacenamiento",
        "fr": "Destination de stockage",
        "ja": "保存先",
        "ko": "저장 위치",
        "ru": "Место сохранения",
    },
    # About
    "App Information": {
        "de": "App-Informationen",
        "es": "Información de la app",
        "fr": "Informations sur l'app",
        "ja": "アプリ情報",
        "ko": "앱 정보",
        "ru": "О приложении",
    },
    "Package name": {
        "de": "Paketname",
        "es": "Nombre del paquete",
        "fr": "Nom du paquet",
        "ja": "パッケージ名",
        "ko": "패키지명",
        "ru": "Имя пакета",
    },
    "Version code": {
        "de": "Versionscode",
        "es": "Código de versión",
        "fr": "Code de version",
        "ja": "バージョンコード",
        "ko": "버전 코드",
        "ru": "Код версии",
    },
    "Target SDK": {
        "de": "Ziel-SDK",
        "es": "SDK destino",
        "fr": "SDK cible",
        "ja": "ターゲット SDK",
        "ko": "대상 SDK",
        "ru": "Целевой SDK",
    },
    "Min SDK": {
        "de": "Min. SDK",
        "es": "SDK mínimo",
        "fr": "SDK min.",
        "ja": "最小 SDK",
        "ko": "최소 SDK",
        "ru": "Мин. SDK",
    },
    "Android API": {
        "de": "Android API",
        "es": "API Android",
        "fr": "API Android",
        "ja": "Android API",
        "ko": "Android API",
        "ru": "Android API",
    },
    "Android version": {
        "de": "Android-Version",
        "es": "Versión Android",
        "fr": "Version Android",
        "ja": "Android バージョン",
        "ko": "Android 버전",
        "ru": "Версия Android",
    },
    "Libraries / SDKs": {
        "de": "Bibliotheken / SDKs",
        "es": "Librerías / SDKs",
        "fr": "Bibliothèques / SDKs",
        "ja": "ライブラリ / SDK",
        "ko": "라이브러리 / SDK",
        "ru": "Библиотеки / SDK",
    },
    # Diagnostics
    "Connection diagnostics": {
        "de": "Verbindungsdiagnose",
        "es": "Diagnóstico de conexión",
        "fr": "Diagnostic de connexion",
        "ja": "接続診断",
        "ko": "연결 진단",
        "ru": "Диагностика подключения",
    },
    "USB / WiFi connection state, camera status, settings snapshot": {
        "de": "USB-/WLAN-Verbindungsstatus, Kamerastatus, Einstellungs-Snapshot",
        "es": "Estado USB/WiFi, estado de cámara, instantánea de ajustes",
        "fr": "État USB/Wi-Fi, état de l'appareil, instantané des réglages",
        "ja": "USB/Wi-Fi 接続状態、カメラ状態、設定スナップショット",
        "ko": "USB/Wi-Fi 연결 상태, 카메라 상태, 설정 스냅샷",
        "ru": "Состояние USB/Wi-Fi, статус камеры, снимок настроек"
    },
    # Content descriptions
    "Zoom in": {
        "de": "Vergrößern",
        "es": "Acercar",
        "fr": "Zoomer",
        "ja": "拡大",
        "ko": "확대",
        "ru": "Увеличить",
    },
    "Zoom out": {
        "de": " Verkleinern",
        "es": "Alejar",
        "fr": "Dézoomer",
        "ja": "縮小",
        "ko": "축소",
        "ru": "Уменьшить",
    },
    "Reset zoom": {
        "de": "Zoom zurücksetzen",
        "es": "Restablecer zoom",
        "fr": "Réinitialiser le zoom",
        "ja": "ズームをリセット",
        "ko": "확대/축소 초기화",
        "ru": "Сброс масштаба",
    },
    "Toggle focus peaking": {
        "de": "Fokus-Peaking umschalten",
        "es": "Alternar peaking",
        "fr": "Activer le peaking",
        "ja": "ピーキング切替",
        "ko": "피킹 토글",
        "ru": "Переключить пикинг",
    },
    "Close": {
        "de": "Schließen",
        "es": "Cerrar",
        "fr": "Fermer",
        "ja": "閉じる",
        "ko": "닫기",
        "ru": "Закрыть",
    },
    "Back": {
        "de": "Zurück",
        "es": "Atrás",
        "fr": "Retour",
        "ja": "戻る",
        "ko": "뒤로",
        "ru": "Назад",
    },
    # Privacy / License / Issue
    "Privacy Policy": {
        "de": "Datenschutzerklärung",
        "es": "Política de privacidad",
        "fr": "Politique de confidentialité",
        "ja": "プライバシーポリシー",
        "ko": "개인정보 처리방침",
        "ru": "Политика конфиденциальности",
    },
    "How we collect and use your data": {
        "de": "Wie wir Ihre Daten erfassen und nutzen",
        "es": "Cómo recopilamos y usamos tus datos",
        "fr": "Comment nous collectons et utilisons vos données",
        "ja": "データの収集と利用について",
        "ko": "데이터 수집 및 사용 방법",
        "ru": "Как мы собираем и используем ваши данные"
    },
    "License": {
        "de": "Lizenz",
        "es": "Licencia",
        "fr": "Licence",
        "ja": "ライセンス",
        "ko": "라이선스",
        "ru": "Лицензия",
    },
    "View LICENSE and COPYING": {
        "de": "LICENSE und COPYING ansehen",
        "es": "Ver LICENSE y COPYING",
        "fr": "Voir LICENSE et COPYING",
        "ja": "LICENSE と COPYING を表示",
        "ko": "LICENSE 및 COPYING 보기",
        "ru": "Просмотр LICENSE и COPYING"
    },
    "Report Issue": {
        "de": "Problem melden",
        "es": "Reportar problema",
        "fr": "Signaler un problème",
        "ja": "問題を報告",
        "ko": "문제 보고",
        "ru": "Сообщить о проблеме",
    },
    "Manually upload current logs to GitHub issue": {
        "de": "Aktuelle Logs manuell in ein GitHub-Issue hochladen",
        "es": "Subir logs actuales manualmente a un issue de GitHub",
        "fr": "Téléverser manuellement les logs dans un ticket GitHub",
        "ja": "現在のログを手動で GitHub issue にアップロード",
        "ko": "현재 로그를 수동으로 GitHub issue 에 업로드",
        "ru": "Вручную загрузить текущие логи в issue на GitHub"
    },
    "Issue submitted": {
        "de": "Problem gemeldet",
        "es": "Problema enviado",
        "fr": "Problème envoyé",
        "ja": "問題を送信しました",
        "ko": "문제 전송됨",
        "ru": "Вопрос отправлен",
    },
    "Failed to submit issue": {
        "de": "Senden fehlgeschlagen",
        "es": "Error al enviar",
        "fr": "Échec de l'envoi",
        "ja": "送信に失敗しました",
        "ko": "전송 실패",
        "ru": "Не удалось отправить",
    },
    # UX
    "User Experience Improvement Program": {
        "de": "Programm zur Nutzererfahrungs-Verbesserung",
        "es": "Programa de mejora de experiencia",
        "fr": "Programme d'amélioration de l'expérience",
        "ja": "UX 改善プログラム",
        "ko": "UX 개선 프로그램",
        "ru": "Программа улучшения UX"
    },
    "Help us improve PhtonView by automatically sending anonymous logs from app start to close. Logs are submitted as GitHub issues and may include device model and app version.": {
        "de": "Helfen Sie uns, PhtonView zu verbessern, indem anonyme Logs automatisch übermittelt werden. Enthalten können Gerätemodell und App-Version sein.",
        "es": "Ayúdanos a mejorar PhtonView enviando logs anónimos automáticamente. Pueden incluir modelo y versión.",
        "fr": "Aidez-nous à améliorer PhtonView en envoyant automatiquement des logs anonymes. Peuvent inclure modèle et version.",
        "ja": "匿名のログを自動送信して PhtonView の改善にご協力ください。端末モデルとアプリ版が含まれる場合があります。",
        "ko": "익명 로그를 자동 전송하여 PhtonView 개선에 도움을 주세요. 기기 모델과 앱 버전이 포함될 수 있습니다.",
        "ru": "Помогите улучшить PhtonView, автоматически отправляя анонимные логи. Могут содержать модель и версию."
    },
    "Automatically submit anonymous session logs": {
        "de": "Anonyme Sitzungs-Logs automatisch senden",
        "es": "Enviar logs anónimos automáticamente",
        "fr": "Envoyer automatiquement des logs anonymes",
        "ja": "匿名のセッションログを自動送信",
        "ko": "익명 세션 로그 자동 전송",
        "ru": "Автоматически отправлять анонимные логи"
    },
    "Agree": {
        "de": "Zustimmen",
        "es": "Aceptar",
        "fr": "Accepter",
        "ja": "同意",
        "ko": "동의",
        "ru": "Согласен",
    },
    "Decline": {
        "de": "Ablehnen",
        "es": "Rechazar",
        "fr": "Refuser",
        "ja": "同意しない",
        "ko": "거절",
        "ru": "Отклонить",
    },
    # Off/On
    "Off": {
        "de": "Aus",
        "es": "Apagado",
        "fr": "Désactivé",
        "ja": "オフ",
        "ko": "끔",
        "ru": "Выкл",
    },
    "On": {
        "de": "Ein",
        "es": "Encendido",
        "fr": "Activé",
        "ja": "オン",
        "ko": "켬",
        "ru": "Вкл",
    },
    # Capture / Preset
    "Portrait": {
        "de": "Porträt",
        "es": "Retrato",
        "fr": "Portrait",
        "ja": "ポートレート",
        "ko": "인물",
        "ru": "Портрет",
    },
    "Landscape": {
        "de": "Landschaft",
        "es": "Paisaje",
        "fr": "Paysage",
        "ja": "風景",
        "ko": "풍경",
        "ru": "Пейзаж",
    },
    "Sports": {
        "de": "Sport",
        "es": "Deportes",
        "fr": "Sport",
        "ja": "スポーツ",
        "ko": "스포츠",
        "ru": "Спорт",
    },
    "Night": {
        "de": "Nacht",
        "es": "Noche",
        "fr": "Nuit",
        "ja": "夜景",
        "ko": "야경",
        "ru": "Ночь",
    },
    "Macro": {
        "de": "Makro",
        "es": "Macro",
        "fr": "Macro",
        "ja": "マクロ",
        "ko": "매크로",
        "ru": "Макро",
    },
    "Studio": {
        "de": "Studio",
        "es": "Estudio",
        "fr": "Studio",
        "ja": "スタジオ",
        "ko": "스튜디오",
        "ru": "Студия",
    },
    "User 1": {
        "de": "Benutzer 1",
        "es": "Usuario 1",
        "fr": "Utilisateur 1",
        "ja": "ユーザー 1",
        "ko": "사용자 1",
        "ru": "Пользователь 1",
    },
    "User 2": {
        "de": "Benutzer 2",
        "es": "Usuario 2",
        "fr": "Utilisateur 2",
        "ja": "ユーザー 2",
        "ko": "사용자 2",
        "ru": "Пользователь 2",
    },
    "Slow Sync": {
        "de": "Langzeitsynchronisation",
        "es": "Sincronización lenta",
        "fr": "Synchro lente",
        "ja": "スローシンクロ",
        "ko": "슬로우 싱크",
        "ru": "Медленная синхронизация",
    },
    "Rear Sync": {
        "de": "Synchronisation auf 2. Vorhang",
        "es": "Sincronización trasera",
        "fr": "Synchro 2e rideau",
        "ja": "リアシンクロ",
        "ko": "리어 싱크",
        "ru": "Синхр. по задней шторке",
    },
    "Red-Eye": {
        "de": "Rote-Augen-Reduktion",
        "es": "Ojos rojos",
        "fr": "Anti-yeux rouges",
        "ja": "赤目軽減",
        "ko": "적목 감소",
        "ru": "Подавление красных глаз",
    },
    # WB
    "Tungsten": {
        "de": "Wolfram",
        "es": "Tungsteno",
        "fr": "Tungstène",
        "ja": "タングステン",
        "ko": "텅스텐",
        "ru": "Лампа накаливания",
    },
    "Kelvin": {
        "de": "Kelvin",
        "es": "Kelvin",
        "fr": "Kelvin",
        "ja": "ケルビン",
        "ko": "켈빈",
        "ru": "Кельвин",
    },
    "Cloudy": {
        "de": "Bewölkt",
        "es": "Nublado",
        "fr": "Nuageux",
        "ja": "曇天",
        "ko": "흐림",
        "ru": "Облачно",
    },
    "Shade": {
        "de": "Schatten",
        "es": "Sombra",
        "fr": "Ombre",
        "ja": "日陰",
        "ko": "그늘",
        "ru": "Тень",
    },
    "Daylight": {
        "de": "Tageslicht",
        "es": "Luz diurna",
        "fr": "Lumière du jour",
        "ja": "日中光",
        "ko": "일광",
        "ru": "Дневной свет",
    },
    "Fluorescent": {
        "de": "Leuchtstoff",
        "es": "Fluorescente",
        "fr": "Fluorescent",
        "ja": "蛍光灯",
        "ko": "형광등",
        "ru": "Люминесцентный",
    },
    "Custom": {
        "de": "Benutzerdefiniert",
        "es": "Personalizado",
        "fr": "Personnalisé",
        "ja": "カスタム",
        "ko": "사용자 지정",
        "ru": "Пользовательский",
    },
    # Capture / Focus
    "Bulb": {
        "de": "Bulb",
        "es": "Bulb",
        "fr": "Pose B",
        "ja": "バルブ",
        "ko": "벌브",
        "ru": "Bulb",
    },
    "AEB": {
        "de": "AEB",
        "es": "AEB",
        "fr": "AEB",
        "ja": "AEB",
        "ko": "AEB",
        "ru": "AEB",
    },
    "Interval": {
        "de": "Intervall",
        "es": "Intervalo",
        "fr": "Intervalle",
        "ja": "インターバル",
        "ko": "인터벌",
        "ru": "Интервал",
    },
    "Timer": {
        "de": "Timer",
        "es": "Temporizador",
        "fr": "Retardateur",
        "ja": "タイマー",
        "ko": "타이머",
        "ru": "Таймер",
    },
    "Camera connected": {
        "de": "Kamera verbunden",
        "es": "Cámara conectada",
        "fr": "Appareil connecté",
        "ja": "カメラ接続済み",
        "ko": "카메라 연결됨",
        "ru": "Камера подключена",
    },
    "Waiting for camera…": {
        "de": "Warte auf Kamera…",
        "es": "Esperando cámara…",
        "fr": "En attente de l'appareil…",
        "ja": "カメラを待機中…",
        "ko": "카메라 대기 중…",
        "ru": "Ожидание камеры…",
    },
    "Preparing camera interface…": {
        "de": "Kameraoberfläche wird vorbereitet…",
        "es": "Preparando interfaz…",
        "fr": "Préparation de l'interface…",
        "ja": "カメラインターフェースを準備中…",
        "ko": "카메라 인터페이스 준비 중…",
        "ru": "Подготовка интерфейса…",
    },
    "Professional Camera Remote": {
        "de": "Professionelle Kamerasteuerung",
        "es": "Control remoto profesional",
        "fr": "Télécommande professionnelle",
        "ja": "プロフェッショナルカメラリモート",
        "ko": "전문 카메라 리모컨",
        "ru": "Профессиональный пульт камеры",
    },
    "Live View": {
        "de": "Live-View",
        "es": "Vista en vivo",
        "fr": "Visée en direct",
        "ja": "ライブビュー",
        "ko": "라이브 뷰",
        "ru": "Видоискатель",
    },
    "Peaking": {
        "de": "Peaking",
        "es": "Peaking",
        "fr": "Peaking",
        "ja": "ピーキング",
        "ko": "피킹",
        "ru": "Пикинг",
    },
    "Error": {
        "de": "Fehler",
        "es": "Error",
        "fr": "Erreur",
        "ja": "エラー",
        "ko": "오류",
        "ru": "Ошибка",
    },
    "Disconnected": {
        "de": "Getrennt",
        "es": "Desconectado",
        "fr": "Déconnecté",
        "ja": "未接続",
        "ko": "연결 해제됨",
        "ru": "Отключено",
    },
    "Connecting…": {
        "de": "Verbinde…",
        "es": "Conectando…",
        "fr": "Connexion…",
        "ja": "接続中…",
        "ko": "연결 중…",
        "ru": "Подключение…",
    },
    # WB / Flash
    "Flash": {
        "de": "Blitz",
        "es": "Flash",
        "fr": "Flash",
        "ja": "フラッシュ",
        "ko": "플래시",
        "ru": "Вспышка",
    },
    "Auto": {
        "de": "Auto",
        "es": "Auto",
        "fr": "Auto",
        "ja": "オート",
        "ko": "자동",
        "ru": "Авто",
    },
    "AF-S": {
        "de": "AF-S",
        "es": "AF-S",
        "fr": "AF-S",
        "ja": "AF-S",
        "ko": "AF-S",
        "ru": "AF-S",
    },
    "AF-C": {
        "de": "AF-C",
        "es": "AF-C",
        "fr": "AF-C",
        "ja": "AF-C",
        "ko": "AF-C",
        "ru": "AF-C",
    },
    "AF-F": {
        "de": "AF-F",
        "es": "AF-F",
        "fr": "AF-F",
        "ja": "AF-F",
        "ko": "AF-F",
        "ru": "AF-F",
    },
    "AF": {
        "de": "AF",
        "es": "AF",
        "fr": "AF",
        "ja": "AF",
        "ko": "AF",
        "ru": "AF",
    },
    "MF": {
        "de": "MF",
        "es": "MF",
        "fr": "MF",
        "ja": "MF",
        "ko": "MF",
        "ru": "MF",
    },
    "ISO": {
        "de": "ISO",
        "es": "ISO",
        "fr": "ISO",
        "ja": "ISO",
        "ko": "ISO",
        "ru": "ISO",
    },
    "Aperture": {
        "de": "Blende",
        "es": "Apertura",
        "fr": "Ouverture",
        "ja": "絞り",
        "ko": "조리개",
        "ru": "Диафрагма",
    },
    "Shutter": {
        "de": "Verschluss",
        "es": "Obturador",
        "fr": "Obturateur",
        "ja": "シャッター",
        "ko": "셔터",
        "ru": "Затвор",
    },
    "Image Format": {
        "de": "Bildformat",
        "es": "Formato de imagen",
        "fr": "Format d'image",
        "ja": "画像形式",
        "ko": "이미지 포맷",
        "ru": "Формат изображения",
    },
    "Image Size": {
        "de": "Bildgröße",
        "es": "Tamaño de imagen",
        "fr": "Taille d'image",
        "ja": "画像サイズ",
        "ko": "이미지 크기",
        "ru": "Размер изображения",
    },
    "Burst Speed": {
        "de": "Serienbildrate",
        "es": "Velocidad de ráfaga",
        "fr": "Cadence rafale",
        "ja": "連写速度",
        "ko": "연사 속도",
        "ru": "Скорость серии",
    },
    # shooting / captures
    "Shooting Mode": {
        "de": "Aufnahmemodus",
        "es": "Modo de disparo",
        "fr": "Mode de prise de vue",
        "ja": "撮影モード",
        "ko": "촬영 모드",
        "ru": "Режим съёмки",
    },
    "Metering": {
        "de": "Messung",
        "es": "Medición",
        "fr": "Mesure",
        "ja": "測光",
        "ko": "측광",
        "ru": "Замер",
    },
    "Metering Mode": {
        "de": "Messmodus",
        "es": "Modo medición",
        "fr": "Mode de mesure",
        "ja": "測光モード",
        "ko": "측광 모드",
        "ru": "Режим замера",
    },
    # status
    "Tap to connect via USB": {
        "de": "Tippen für USB-Verbindung",
        "es": "Toca para conectar por USB",
        "fr": "Tapez pour connecter en USB",
        "ja": "タップして USB 接続",
        "ko": "탭하여 USB 연결",
        "ru": "Нажмите для USB-подключения",
    },
    "Use USB": {
        "de": "USB verwenden",
        "es": "Usar USB",
        "fr": "Utiliser USB",
        "ja": "USB を使用",
        "ko": "USB 사용",
        "ru": "Использовать USB",
    },
    "Use WiFi": {
        "de": "WLAN verwenden",
        "es": "Usar WiFi",
        "fr": "Utiliser Wi-Fi",
        "ja": "Wi-Fi を使用",
        "ko": "Wi-Fi 사용",
        "ru": "Использовать Wi-Fi",
    },
    "Select brand and enter camera IP": {
        "de": "Marke wählen und IP eingeben",
        "es": "Elige marca e introduce IP",
        "fr": "Choisir la marque et saisir l'IP",
        "ja": "ブランドを選び IP を入力",
        "ko": "브랜드 선택 후 IP 입력",
        "ru": "Выберите бренд и введите IP",
    },
    "Camera IP:Port": {
        "de": "Kamera-IP:Port",
        "es": "IP:Puerto de cámara",
        "fr": "IP:Port de l'appareil",
        "ja": "カメラ IP:ポート",
        "ko": "카메라 IP:포트",
        "ru": "IP:Порт камеры",
    },
    "WiFi Connection (Experimental)": {
        "de": "WLAN-Verbindung (experimentell)",
        "es": "Conexión WiFi (experimental)",
        "fr": "Connexion Wi-Fi (expérimentale)",
        "ja": "Wi-Fi 接続（実験的）",
        "ko": "Wi-Fi 연결(실험적)",
        "ru": "Wi-Fi подключение (экспериментально)",
    },
    "Enable experimental WiFi camera connection": {
        "de": "Experimentelle WLAN-Kameraverbindung aktivieren",
        "es": "Habilitar conexión WiFi experimental",
        "fr": "Activer la connexion Wi-Fi expérimentale",
        "ja": "実験的な Wi-Fi カメラ接続を有効化",
        "ko": "실험적 Wi-Fi 카메라 연결 활성화",
        "ru": "Включить экспериментальное Wi-Fi подключение"
    },
    "Debug Mode": {
        "de": "Debug-Modus",
        "es": "Modo depuración",
        "fr": "Mode débogage",
        "ja": "デバッグモード",
        "ko": "디버그 모드",
        "ru": "Режим отладки",
    },
    "Output detailed USB/PTP logs to logcat": {
        "de": "Ausführliche USB/PTP-Logs in logcat ausgeben",
        "es": "Volcar logs detallados de USB/PTP a logcat",
        "fr": "Sortie détaillée des logs USB/PTP vers logcat",
        "ja": "USB/PTP の詳細ログを logcat に出力",
        "ko": "자세한 USB/PTP 로그를 logcat 에 출력",
        "ru": "Вывод подробных логов USB/PTP в logcat"
    },
    # presets
    "Shooting Presets": {
        "de": "Aufnahmeprogramme",
        "es": "Preajustes de disparo",
        "fr": "Préréglages de prise de vue",
        "ja": "撮影プリセット",
        "ko": "촬영 프리셋",
        "ru": "Пресеты съёмки",
    },
    "Camera Maintenance": {
        "de": "Kamerawartung",
        "es": "Mantenimiento",
        "fr": "Maintenance de l'appareil",
        "ja": "カメラメンテナンス",
        "ko": "카메라 유지보수",
        "ru": "Обслуживание камеры",
    },
    "Fetch Status": {
        "de": "Status abrufen",
        "es": "Obtener estado",
        "fr": "Obtenir l'état",
        "ja": "状態を取得",
        "ko": "상태 가져오기",
        "ru": "Получить статус",
    },
    "Sync Date/Time": {
        "de": "Datum/Uhrzeit synchronisieren",
        "es": "Sincronizar fecha/hora",
        "fr": "Synchroniser date/heure",
        "ja": "日時を同期",
        "ko": "날짜/시간 동기화",
        "ru": "Синхронизировать дату/время"
    },
    "Up to date": {
        "de": "Auf dem neuesten Stand",
        "es": "Estás al día",
        "fr": "À jour",
        "ja": "最新です",
        "ko": "최신 버전입니다",
        "ru": "Актуальная версия",
    },
    "Check for Updates": {
        "de": "Nach Updates suchen",
        "es": "Buscar actualizaciones",
        "fr": "Rechercher des mises à jour",
        "ja": "更新を確認",
        "ko": "업데이트 확인",
        "ru": "Проверить обновления",
    },
    # System default / language list
    "System default": {
        "de": "Systemstandard",
        "es": "Predeterminado del sistema",
        "fr": "Par défaut du système",
        "ja": "システム既定",
        "ko": "시스템 기본값",
        "ru": "Как в системе",
    },
    "Interface Mode": {
        "de": "Oberflächenmodus",
        "es": "Modo de interfaz",
        "fr": "Mode d'interface",
        "ja": "インターフェースモード",
        "ko": "인터페이스 모드",
        "ru": "Режим интерфейса",
    },
    "Connection Type": {
        "de": "Verbindungsart",
        "es": "Tipo de conexión",
        "fr": "Type de connexion",
        "ja": "接続方式",
        "ko": "연결 방식",
        "ru": "Способ подключения",
    },
    "Multi-Brand": {
        "de": "Mehrere Marken",
        "es": "Multi-marca",
        "fr": "Multi-marques",
        "ja": "マルチブランド",
        "ko": "멀티 브랜드",
        "ru": "Несколько брендов",
    },
    "Simple": {
        "de": "Einfach",
        "es": "Simple",
        "fr": "Simple",
        "ja": "シンプル",
        "ko": "심플",
        "ru": "Простой",
    },
    "Essential controls only": {
        "de": "Nur wesentliche Bedienelemente",
        "es": "Solo controles esenciales",
        "fr": "Contrôles essentiels uniquement",
        "ja": "基本操作のみ",
        "ko": "필수 항목만",
        "ru": "Только основные элементы",
    },
    "Professional": {
        "de": "Professionell",
        "es": "Profesional",
        "fr": "Professionnel",
        "ja": "プロフェッショナル",
        "ko": "전문가",
        "ru": "Профессиональный",
    },
    "Full control panel": {
        "de": "Vollständige Steuerung",
        "es": "Panel de control completo",
        "fr": "Panneau de contrôle complet",
        "ja": "フルコントロールパネル",
        "ko": "전체 제어 패널",
        "ru": "Полная панель управления",
    },
    "Get Started": {
        "de": "Loslegen",
        "es": "Empezar",
        "fr": "Commencer",
        "ja": "始める",
        "ko": "시작하기",
        "ru": "Начать",
    },
    "Next": {
        "de": "Weiter",
        "es": "Siguiente",
        "fr": "Suivant",
        "ja": "次へ",
        "ko": "다음",
        "ru": "Далее",
    },
    "Skip": {
        "de": "Überspringen",
        "es": "Omitir",
        "fr": "Passer",
        "ja": "スキップ",
        "ko": "건너뛰기",
        "ru": "Пропустить",
    },
    # Brand labels
    "Nikon": {
        "de": "Nikon",
        "es": "Nikon",
        "fr": "Nikon",
        "ja": "ニコン",
        "ko": "니콘",
        "ru": "Nikon",
    },
    "Nikon WU-1a/b": {
        "de": "Nikon WU-1a/b",
        "es": "Nikon WU-1a/b",
        "fr": "Nikon WU-1a/b",
        "ja": "Nikon WU-1a/b",
        "ko": "Nikon WU-1a/b",
        "ru": "Nikon WU-1a/b",
    },
    "Canon": {
        "de": "Canon",
        "es": "Canon",
        "fr": "Canon",
        "ja": "キヤノン",
        "ko": "캐논",
        "ru": "Canon",
    },
    "Sony": {
        "de": "Sony",
        "es": "Sony",
        "fr": "Sony",
        "ja": "ソニー",
        "ko": "소니",
        "ru": "Sony",
    },
    "Fujifilm": {
        "de": "Fujifilm",
        "es": "Fujifilm",
        "fr": "Fujifilm",
        "ja": "富士フイルム",
        "ko": "후지필름",
        "ru": "Fujifilm",
    },
    "Panasonic": {
        "de": "Panasonic",
        "es": "Panasonic",
        "fr": "Panasonic",
        "ja": "パナソニック",
        "ko": "파나소닉",
        "ru": "Panasonic",
    },
    "Olympus": {
        "de": "Olympus",
        "es": "Olympus",
        "fr": "Olympus",
        "ja": "オリンパス",
        "ko": "올림푸스",
        "ru": "Olympus",
    },
    # Credits
    "Credits": {
        "de": "Mitwirkende",
        "es": "Créditos",
        "fr": "Crédits",
        "ja": "クレジット",
        "ko": "크레딧",
        "ru": "Авторы",
    },
    "Program by LanChe · UI design by 安信一・プロス (One Bucket)": {
        "de": "Programmierung: LanChe · UI-Design: 安信一・プロス (One Bucket)",
        "es": "Programación: LanChe · Diseño UI: 安信一・プロス (One Bucket)",
        "fr": "Programme: LanChe · Design UI: 安信一・プロス (One Bucket)",
        "ja": "Program by LanChe · UI design by 安信一・プロス (One Bucket)",
        "ko": "Program by LanChe · UI design by 安信一・プロス (One Bucket)",
        "ru": "Программа: LanChe · UI-дизайн: 安信一・プロス (One Bucket)",
    },
    "Lanche-Labs": {
        "de": "Lanche-Labs",
        "es": "Lanche-Labs",
        "fr": "Lanche-Labs",
        "ja": "Lanche-Labs",
        "ko": "Lanche-Labs",
        "ru": "Lanche-Labs",
    },
    "PhtonView": {
        "de": "PhtonView",
        "es": "PhtonView",
        "fr": "PhtonView",
        "ja": "PhtonView",
        "ko": "PhtonView",
        "ru": "PhtonView",
    },
    # About role (technical - keep mostly the same but translate the
    # descriptive part where possible; we keep as is for tech terms
    # because they are project/SDK proper nouns)
    # These can simply mirror the English.
    "Open-source camera driver framework — supports >2000 cameras": {
        "de": "Open-Source-Kameratreiber-Framework — unterstützt >2000 Kameras",
        "es": "Framework de driver de cámara open-source — soporta >2000 cámaras",
        "fr": "Framework open-source de pilotes d'appareils — supporte >2000 appareils",
        "ja": "オープンソースのカメラドライバフレームワーク — 2000 以上のカメラ対応",
        "ko": "오픈소스 카메라 드라이버 프레임워크 — 2000 종 이상 지원",
        "ru": "Открытый фреймворк драйверов камер — поддержка >2000 камер"
    },
    "PhtonView native bridge — glues gphoto2 to Android USB": {
        "de": "PhtonView Native-Bridge — verbindet gphoto2 mit Android-USB",
        "es": "Bridge nativo de PhtonView — conecta gphoto2 con USB de Android",
        "fr": "Bridge natif PhtonView — relie gphoto2 à l'USB Android",
        "ja": "PhtonView ネイティブブリッジ — gphoto2 を Android USB に接着",
        "ko": "PhtonView 네이티브 브리지 — gphoto2 를 Android USB 에 연결",
        "ru": "Нативный мост PhtonView — связывает gphoto2 с USB Android"
    },
    "Kotlin stdlib — coroutines & language runtime": {
        "de": "Kotlin stdlib — Coroutinen & Sprachlaufzeit",
        "es": "Kotlin stdlib — corrutinas y runtime",
        "fr": "Kotlin stdlib — coroutines & runtime",
        "ja": "Kotlin stdlib — コルーチンとランタイム",
        "ko": "Kotlin stdlib — 코루틴 및 런타임",
        "ru": "Kotlin stdlib — корутины и среда исполнения"
    },
    "Jetpack Compose BOM — UI framework": {
        "de": "Jetpack Compose BOM — UI-Framework",
        "es": "Jetpack Compose BOM — framework UI",
        "fr": "Jetpack Compose BOM — framework UI",
        "ja": "Jetpack Compose BOM — UI フレームワーク",
        "ko": "Jetpack Compose BOM — UI 프레임워크",
        "ru": "Jetpack Compose BOM — UI-фреймворк"
    },
    "AndroidX Core KTX — Kotlin extensions": {
        "de": "AndroidX Core KTX — Kotlin-Erweiterungen",
        "es": "AndroidX Core KTX — extensiones de Kotlin",
        "fr": "AndroidX Core KTX — extensions Kotlin",
        "ja": "AndroidX Core KTX — Kotlin 拡張",
        "ko": "AndroidX Core KTX — Kotlin 확장",
        "ru": "AndroidX Core KTX — расширения Kotlin"
    },
    "AndroidX Lifecycle — ViewModel & runtime": {
        "de": "AndroidX Lifecycle — ViewModel & Laufzeit",
        "es": "AndroidX Lifecycle — ViewModel & runtime",
        "fr": "AndroidX Lifecycle — ViewModel & runtime",
        "ja": "AndroidX Lifecycle — ViewModel & ランタイム",
        "ko": "AndroidX Lifecycle — ViewModel & 런타임",
        "ru": "AndroidX Lifecycle — ViewModel и среда исполнения"
    },
    "Hilt — dependency injection": {
        "de": "Hilt — Dependency Injection",
        "es": "Hilt — inyección de dependencias",
        "fr": "Hilt — injection de dépendances",
        "ja": "Hilt — 依存性注入",
        "ko": "Hilt — 의존성 주입",
        "ru": "Hilt — внедрение зависимостей"
    },
    "kotlinx.coroutines — async streaming & backpressure": {
        "de": "kotlinx.coroutines — asynchrones Streaming & Backpressure",
        "es": "kotlinx.coroutines — streaming asíncrono y backpressure",
        "fr": "kotlinx.coroutines — streaming asynchrone & backpressure",
        "ja": "kotlinx.coroutines — 非同期ストリームとバックプレッシャー",
        "ko": "kotlinx.coroutines — 비동기 스트리밍 및 백프레셔",
        "ru": "kotlinx.coroutines — асинхронные потоки и backpressure"
    },
    "Material 3 — design system": {
        "de": "Material 3 — Designsystem",
        "es": "Material 3 — sistema de diseño",
        "fr": "Material 3 — système de design",
        "ja": "Material 3 — デザインシステム",
        "ko": "Material 3 — 디자인 시스템",
        "ru": "Material 3 — дизайн-система"
    },
    "Material Icons Extended — extended icon set": {
        "de": "Material Icons Extended — erweiterte Icon-Sammlung",
        "es": "Material Icons Extended — set de iconos extendido",
        "fr": "Material Icons Extended — set d'icônes étendu",
        "ja": "Material Icons Extended — 拡張アイコンセット",
        "ko": "Material Icons Extended — 확장 아이콘 세트",
        "ru": "Material Icons Extended — расширенный набор иконок"
    },
    "EXIF Interface — photo metadata reader": {
        "de": "EXIF Interface — Foto-Metadaten-Leser",
        "es": "EXIF Interface — lector de metadatos de foto",
        "fr": "EXIF Interface — lecteur de métadonnées photo",
        "ja": "EXIF Interface — 写真のメタデータリーダー",
        "ko": "EXIF Interface — 사진 메타데이터 리더",
        "ru": "EXIF Interface — чтение метаданных фото"
    },
    "USB bulk & interrupt transfer": {
        "de": "USB Bulk- & Interrupt-Transfer",
        "es": "Transferencia bulk e interrupt USB",
        "fr": "Transferts USB bulk & interrupt",
        "ja": "USB バルク/インタラプト転送",
        "ko": "USB 벌크/인터럽트 전송",
        "ru": "USB bulk и interrupt передача"
    },
    "Dynamic library loader (gphoto2 dependencies)": {
        "de": "Dynamischer Bibliothekslader (gphoto2-Abhängigkeiten)",
        "es": "Cargador dinámico de bibliotecas (dependencias gphoto2)",
        "fr": "Chargeur dynamique de bibliothèques (dépendances gphoto2)",
        "ja": "動的ライブラリローダー（gphoto2 依存関係）",
        "ko": "동적 라이브러리 로더 (gphoto2 종속성)",
        "ru": "Динамический загрузчик библиотек (зависимости gphoto2)"
    },
    "Camera control protocol (Picture Transfer Protocol)": {
        "de": "Kamerasteuerungsprotokoll (Picture Transfer Protocol)",
        "es": "Protocolo de control de cámara (Picture Transfer Protocol)",
        "fr": "Protocole de contrôle d'appareil (Picture Transfer Protocol)",
        "ja": "カメラ制御プロトコル (Picture Transfer Protocol)",
        "ko": "카메라 제어 프로토콜 (Picture Transfer Protocol)",
        "ru": "Протокол управления камерой (Picture Transfer Protocol)"
    },
    # About group
    "Protocol": {
        "de": "Protokoll",
        "es": "Protocolo",
        "fr": "Protocole",
        "ja": "プロトコル",
        "ko": "프로토콜",
        "ru": "Протокол",
    },
    "Camera control stack": {
        "de": "Kamerasteuerungs-Stack",
        "es": "Pila de control de cámara",
        "fr": "Pile de contrôle de l'appareil",
        "ja": "カメラ制御スタック",
        "ko": "카메라 제어 스택",
        "ru": "Стек управления камерой"
    },
    "Android & app stack": {
        "de": "Android- & App-Stack",
        "es": "Pila Android y app",
        "fr": "Pile Android & app",
        "ja": "Android / アプリスタック",
        "ko": "Android 및 앱 스택",
        "ru": "Android и стек приложения"
    },
    # Misc remaining
    "Magnification %1$.0fx": {
        "de": "Vergrößerung %1$.0fx",
        "es": "Ampliación %1$.0fx",
        "fr": "Grossissement %1$.0fx",
        "ja": "拡大 %1$.0fx",
        "ko": "확대 %1$.0fx",
        "ru": "Увеличение %1$.0fx",
    },
    # Pre-translated phrases that have full-width colons
    "Conectado: %1$s": {
        "de": "Verbunden: %1$s",
        "es": "Conectado: %1$s",
        "fr": "Connecté: %1$s",
        "ja": "接続済み: %1$s",
        "ko": "연결됨: %1$s",
        "ru": "Подключено: %1$s",
    },
    "Подключено: %1$s": {
        "de": "Verbunden: %1$s",
        "es": "Conectado: %1$s",
        "fr": "Connecté: %1$s",
        "ja": "接続済み: %1$s",
        "ko": "연결됨: %1$s",
        "ru": "Подключено: %1$s",
    },
    "연결됨: %1$s": {
        "de": "Verbunden: %1$s",
        "es": "Conectado: %1$s",
        "fr": "Connecté: %1$s",
        "ja": "接続済み: %1$s",
        "ko": "연결됨: %1$s",
        "ru": "Подключено: %1$s",
    },
}


def xml_escape(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def fix_en():
    """Fix English reference: replace CJK short labels and zh hint with proper English"""
    print("\n=== Fixing values/strings.xml (English reference) ===")
    text = EN_FILE.read_text(encoding="utf-8")
    out = []
    in_string = False
    i = 0
    while i < len(text):
        # Detect <string name="..."> 
        m = re.match(r'<string\s+name="([^"]+)"\s*>', text[i:])
        if m:
            name = m.group(1)
            j = i + m.end()
            end = text.find("</string>", j)
            content = text[j:end]
            # Apply EN fix if exists
            new_content = EN_FIXES.get(name, content)
            if new_content != content:
                print(f"  EN[{name}]: {content!r} -> {new_content!r}")
            out.append(f'<string name="{name}">{new_content}</string>')
            i = end + len("</string>")
            continue
        out.append(text[i])
        i += 1
    EN_FILE.write_text("".join(out), encoding="utf-8")
    print(f"  Wrote: {EN_FILE}")


def rewrite_lang(lang: str):
    """Rewrite values-{lang}/strings.xml with proper translations"""
    src = ROOT / f"values-{lang}" / "strings.xml"
    if not src.exists():
        print(f"  ! {src} not found")
        return
    # Parse with regex (don't rely on ET for namespace handling, but ET should work)
    text = src.read_text(encoding="utf-8")
    out = []
    i = 0
    fixed_untrans = 0
    fixed_colon = 0
    while i < len(text):
        m = re.match(r'<string\s+name="([^"]+)"\s*>', text[i:])
        if m:
            name = m.group(1)
            j = i + m.end()
            end = text.find("</string>", j)
            content = text[j:end]
            # Skip empty content (translatable="false")
            # Try to find translation
            tr = TR.get(content, {}).get(lang)
            if tr and tr != content:
                content = tr
                fixed_untrans += 1
            # Fix colons
            new_content = fix_colons(content, lang)
            if new_content != content:
                fixed_colon += 1
            # XML escape inside string
            new_content = xml_escape(new_content)
            out.append(f'<string name="{name}">{new_content}</string>')
            i = end + len("</string>")
            continue
        out.append(text[i])
        i += 1
    src.write_text("".join(out), encoding="utf-8")
    print(f"  {lang}: untrans={fixed_untrans}  colons={fixed_colon}  -> {src}")


def main():
    fix_en()
    print("\n=== Rewriting language files ===")
    for lang in LANGS:
        rewrite_lang(lang)


if __name__ == "__main__":
    main()
