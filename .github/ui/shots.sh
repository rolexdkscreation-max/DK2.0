#!/usr/bin/env bash
# Emulator par DK ki har screen ka screenshot: pehle purana (main), phir naya design. Crash ho to log bhi.
set -x
OUT=shots; mkdir -p $OUT
PKG=com.dk.assistant
adb root; sleep 4; adb wait-for-device
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true

shot() { sleep "${2:-3}"; timeout 40 adb exec-out screencap -p > "$OUT/$1.png"; }

# text/desc se view dhoondh kar tap (uiautomator dump)
tap() {
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1 || return 1
  local xy
  xy=$(python3 - "$1" <<'PY'
import sys,re,xml.etree.ElementTree as E
want=sys.argv[1]
for n in E.parse('/tmp/ui.xml').iter('node'):
    if want in (n.get('text') or '') or want == (n.get('content-desc') or ''):
        a=list(map(int,re.findall(r'\d+',n.get('bounds'))))
        print((a[0]+a[2])//2,(a[1]+a[3])//2); break
PY
)
  [ -n "$xy" ] && adb shell input tap $xy
}

run() {
  local tag=$1 apk=$2
  adb uninstall $PKG >/dev/null 2>&1 || true
  adb install -r -g "$apk"
  adb logcat -c
  adb shell am start -S -W -n $PKG/.MainActivity;                          shot ${tag}_01_home 12
  adb shell input keyevent KEYCODE_BACK; sleep 1
  adb shell am start -S -W -n $PKG/.SettingsActivity;                      shot ${tag}_02_settings 4
  adb shell am start -S -W -n $PKG/.SettingsActivity --es section voice;   shot ${tag}_03_settings_voice 4
  adb shell am start -S -W -n $PKG/.SettingsActivity --es section profile; shot ${tag}_04_settings_profile 4
  adb shell am force-stop $PKG
  adb shell am start -S -W -n $PKG/.ChatActivity; sleep 3
  adb shell input text "Namaste%sDK"; sleep 1
  tap "भेजें" || true;                                                   shot ${tag}_05_chat 6
  adb shell input keyevent KEYCODE_BACK; sleep 1;                        shot ${tag}_06_chat_nokb 2
  adb shell am force-stop $PKG
  adb shell am start -S -W -n $PKG/.VoiceActivity;                         shot ${tag}_07_voice 5
  adb shell am force-stop $PKG
  adb shell am start -S -W -n $PKG/.MemoryActivity; sleep 3
  tap "जैसे: मेरी बहन" && sleep 1 && adb shell input text "Meri%sgaadi%sMH15%s1234" && sleep 1 && tap "जोड़ें"
  adb shell input keyevent KEYCODE_BACK; sleep 1;                        shot ${tag}_08_memory 3
  tap "सारी यादें मिटाएँ" || { adb shell input swipe 540 1800 540 600 300; sleep 1; tap "सारी यादें मिटाएँ"; }
  shot ${tag}_09_dialog 3
  adb shell input keyevent KEYCODE_BACK
  adb shell am force-stop $PKG
  adb shell am start -S -W -n $PKG/.CodingActivity;                        shot ${tag}_10_coding 5
  adb shell input keyevent KEYCODE_BACK; sleep 1;                        shot ${tag}_11_coding_nokb 2
  timeout 20 adb logcat -d -b crash > "$OUT/${tag}_crash.txt" || true
  timeout 20 adb logcat -d | grep -E "FATAL|AndroidRuntime|LiquidGlass|InflateException" > "$OUT/${tag}_errors.txt" || true
  adb shell am force-stop $PKG
}

run before /tmp/before.apk
run after  /tmp/after.apk
ls -la $OUT
exit 0
