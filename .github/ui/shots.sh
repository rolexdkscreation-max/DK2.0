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

# emulator कभी-कभी बीच में बंद हो जाता है (adb: device not found) - तब उसे दोबारा चालू करके आगे बढ़ो
EMU="${ANDROID_SDK_ROOT:-$ANDROID_HOME}/emulator/emulator"
AVD=$("$EMU" -list-avds 2>/dev/null | head -1)
revive() {
  adb get-state 2>/dev/null | grep -q device && return 0
  echo "=== emulator बंद मिला ($(date -u +%T)) - दोबारा चालू ===" | tee -a "$OUT/emu_revive.txt"
  { free -m; sudo dmesg 2>/dev/null | tail -25; } >> "$OUT/emu_revive.txt" 2>&1
  pkill -9 -f qemu-system 2>/dev/null; sleep 3
  adb kill-server; adb start-server
  nohup "$EMU" -avd "$AVD" -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim > /tmp/emu2.log 2>&1 &
  timeout 300 adb wait-for-device || return 1
  for i in $(seq 1 80); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 3; done
  adb root; sleep 4; adb wait-for-device
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $k 0; done
  adb shell svc power stayon true; adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard || true
  echo "=== फिर चालू ($(date -u +%T)) ===" >> "$OUT/emu_revive.txt"
}
shot() { sleep "${2:-3}"; revive; timeout 40 adb exec-out screencap -p > "$OUT/$1.png"; }
# कोई स्क्रीन खोलो (emulator बंद हो तो पहले चालू)
go() { revive; timeout 90 adb shell am start -S -W -n "$PKG/$1" "${@:2}"; }

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
  go .MainActivity;                                                       shot ${tag}_01_home 12
  shot ${tag}_01b_home_late 14
  # v4: पहली बार का परिचय (onboarding) छोड़कर असली होम, फिर ऊपर-बाएँ मेनू
  { tap "Skip" || tap "छोड़ें"; } ; sleep 3
  revive; timeout 90 adb shell am start -W -n $PKG/.MainActivity;                             shot ${tag}_01d_home_real 8
  { tap "मेनू खोलें" || tap "Open menu"; } && shot ${tag}_01c_home_menu 2
  adb shell input keyevent KEYCODE_BACK; sleep 1
  # v4: emulator कुछ मिनट में अटक जाता है - इसलिए नई स्क्रीनें पहले (चैट, आवाज़, मेमोरी)
  go .ChatActivity;                          shot ${tag}_05a_chat_first 5
  go .VoiceActivity;                         shot ${tag}_07a_voice_first 5
  go .MemoryActivity;                        shot ${tag}_08a_memory_first 4
  # v4: दिखावट (UI कस्टमाइज़ेशन)
  go .SettingsActivity --es section ui;      shot ${tag}_04c_settings_ui 5
  adb shell input swipe 540 1700 540 600 400;                             shot ${tag}_04d_settings_ui_scroll 2
  adb shell input swipe 540 1700 540 600 400;                             shot ${tag}_04e_settings_ui_scroll2 2
  # v4: रंग बदलकर देखो (मैजेंटा + AMOLED) - Settings और होम दोनों पर तुरंत
  go .SettingsActivity --es section ui; sleep 4
  { tap "AMOLED" ; } ; sleep 2
  adb shell input swipe 540 1500 540 700 400; sleep 1
  { tap "मैजेंटा" || tap "Magenta"; } ; sleep 2;                          shot ${tag}_12_ui_magenta 1
  revive; timeout 90 adb shell am start -W -n $PKG/.MainActivity;                             shot ${tag}_13_home_magenta 8
  adb shell input keyevent KEYCODE_BACK; sleep 1
  adb shell am start -S -W -n $PKG/.SettingsActivity;                      shot ${tag}_02_settings 4
  # dabaane ki haalat (pressed): pehle card ko 3 sec dabaye rakho aur beech me screenshot
  (adb shell input swipe 540 560 540 560 3000 &) ; sleep 1.2;               shot ${tag}_02b_pressed 0
  sleep 3; adb shell input keyevent KEYCODE_BACK; sleep 1
  adb shell am start -S -W -n $PKG/.SettingsActivity --es section voice;   shot ${tag}_03_settings_voice 4
  # v3.69: खुलने-बंद होने वाला कॉलम - टैप = खुले, दोबारा टैप = बंद
  tap "बोलने वाला इंजन" && shot ${tag}_03b_voice_engine_open 2
  adb shell input swipe 540 1700 540 700 300; shot ${tag}_03c_voice_engine_scroll 2
  adb shell input swipe 540 1700 540 700 300; shot ${tag}_03d_voice_engine_scroll2 2
  adb shell am start -S -W -n $PKG/.SettingsActivity --es section voice; sleep 3
  tap "बोलने वाला इंजन" && sleep 1 && tap "बोलने वाला इंजन" && shot ${tag}_03e_voice_engine_closed 2
  adb shell am start -S -W -n $PKG/.SettingsActivity --es section profile; shot ${tag}_04_settings_profile 4
  tap "Access key" && shot ${tag}_04b_profile_accesskey_open 2
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

# नया पहले - emulator बीच में अटके तो भी इस branch की तस्वीरें मिलें
run after  /tmp/after.apk
run before /tmp/before.apk
ls -la $OUT
exit 0
