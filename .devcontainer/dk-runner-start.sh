#!/usr/bin/env bash
# DK runner को पीछे चालू करो (codespace हर बार चालू होने पर). पहले से चल रहा हो तो दोबारा नहीं.
cd "$(dirname "$0")/.." || exit 0
if pgrep -f "dk-runner.sh" >/dev/null 2>&1; then exit 0; fi
# double fork: runner तुरंत init का बच्चा बने, वरना devcontainer CLI postStart के बाद पूरा process-tree मार देता है
( setsid nohup bash .devcontainer/dk-runner.sh >>/tmp/dk-runner.log 2>&1 < /dev/null & )
sleep 2
exit 0
