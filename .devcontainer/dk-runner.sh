#!/usr/bin/env bash
# DK sandbox runner (नवंबर 2026).
# माँग:  refs/heads/dk-run/<sha12>  (DK फ़ोन से बनाता है - नया commit नहीं, सिर्फ़ ref)
# काम:   उसी commit पर  gradle :app:testDebugUnitTest  (compile + सारे unit tests). कोई और command कभी नहीं.
# नतीजा: refs/heads/dk-result/<sha12>  -> dk-result.txt  (पहली लाइन "DK-RESULT OK|FAIL", फिर secs=, फिर log)
set -u
SRC="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$HOME/dk-run"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
TOOLS_ZIP="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
POLL=6

log() { echo "[$(date +%H:%M:%S)] $*"; }

setup_sdk() {
  if [ -d "$SDK/platforms/android-34" ] && [ -d "$SDK/build-tools" ]; then return 0; fi
  log "Android SDK लगा रहा हूँ (सिर्फ़ पहली बार)"
  mkdir -p "$SDK/cmdline-tools"
  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    command -v unzip >/dev/null || sudo apt-get install -y -qq unzip >/dev/null 2>&1
    curl -fsSL -o /tmp/clt.zip "$TOOLS_ZIP" || return 1
    rm -rf /tmp/clt && mkdir -p /tmp/clt && unzip -q /tmp/clt.zip -d /tmp/clt || return 1
    rm -rf "$SDK/cmdline-tools/latest" && mv /tmp/clt/cmdline-tools "$SDK/cmdline-tools/latest"
  fi
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-34" "build-tools;34.0.0" "platform-tools" >/dev/null || return 1
  log "SDK तैयार"
}

setup_work() {
  if [ -d "$WORK/.git" ]; then return 0; fi
  git clone -q "$(git -C "$SRC" remote get-url origin)" "$WORK" || return 1
  echo "sdk.dir=$SDK" > "$WORK/local.properties"
}

gradle_bin() { command -v gradle || ls /usr/local/sdkman/candidates/gradle/current/bin/gradle 2>/dev/null; }

result_push() { # $1=key $2=file
  local blob tree c
  blob=$(git -C "$WORK" hash-object -w "$2") || return 1
  tree=$(printf "100644 blob %s\tdk-result.txt\n" "$blob" | git -C "$WORK" mktree) || return 1
  c=$(git -C "$WORK" -c user.name=dk-runner -c user.email=dk-runner@users.noreply.github.com commit-tree "$tree" -m "dk-runner result $1") || return 1
  git -C "$WORK" push -q -f origin "$c:refs/heads/dk-result/$1"
}

run_one() { # $1=sha $2=key
  local sha="$1" key="$2" out="/tmp/dk-out-$2.txt" res="/tmp/dk-res-$2.txt" t0 rc secs
  log "माँग $key"
  t0=$(date +%s)
  if ! git -C "$WORK" fetch -q origin "+refs/heads/dk-run/$key:refs/remotes/dk-run/$key" 2>"$out"; then
    rc=99
  else
    git -C "$WORK" checkout -q -f "$sha" && git -C "$WORK" clean -q -fd -e local.properties
    ( cd "$WORK" && timeout 25m "$(gradle_bin)" :app:testDebugUnitTest --console=plain ) >"$out" 2>&1
    rc=$?
  fi
  secs=$(( $(date +%s) - t0 ))
  {
    if [ "$rc" -eq 0 ]; then echo "DK-RESULT OK"; else echo "DK-RESULT FAIL"; fi
    echo "secs=$secs"
    echo "sha=$sha"
    # पहले असली गलतियाँ (compile की e: लाइनें, fail हुए tests), फिर आख़िर का हिस्सा
    grep -E "^e: |FAILED|AssertionError|Exception:|What went wrong|^> " "$out" | head -80
    echo "-----"
    tail -n 60 "$out"
  } > "$res"
  result_push "$key" "$res" && log "नतीजा $key: rc=$rc ${secs}s" || log "नतीजा push नहीं हुआ $key"
  git -C "$WORK" push -q origin ":refs/heads/dk-run/$key" 2>/dev/null || true
}

log "DK runner शुरू"
setup_sdk || log "SDK setup fail - फिर भी माँग देखता रहूँगा (अगली बार फिर कोशिश)"
setup_work || { log "clone fail"; exit 1; }
while true; do
  setup_sdk >/dev/null 2>&1 || true
  git ls-remote origin 'refs/heads/dk-run/*' 2>/dev/null | while read -r sha ref; do
    key="${ref#refs/heads/dk-run/}"
    case "$key" in *[!0-9a-f]*|"") continue ;; esac   # सिर्फ़ sha वाले नाम - कोई और कभी नहीं
    [ "${sha:0:12}" = "$key" ] || continue
    run_one "$sha" "$key"
  done
  sleep "$POLL"
done
