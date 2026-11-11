#!/usr/bin/env bash
# DK runner को पीछे चालू करो (codespace हर बार चालू होने पर). पहले से चल रहा हो तो दोबारा नहीं.
cd "$(dirname "$0")/.." || exit 0
if pgrep -f "dk-runner.sh" >/dev/null 2>&1; then exit 0; fi
nohup bash .devcontainer/dk-runner.sh >/tmp/dk-runner.log 2>&1 &
disown || true
exit 0
