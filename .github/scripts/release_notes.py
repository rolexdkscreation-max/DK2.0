#!/usr/bin/env python3
"""v3.6 (owner): अपडेट में सिर्फ़ असली बदलाव.
हर merged PR की body से '## अपडेट में क्या नया' (या कोडिंग एजेंट PR का '## क्या बदला') के bullets;
न मिले तो PR title; कोई PR नहीं तो साधारण commits. Merge/टेस्ट/बैकअप/फ़ाइल नाम हटते हैं।
उपयोग: release_notes.py <RANGE>
"""
import json, re, subprocess, sys

RANGE = sys.argv[1] if len(sys.argv) > 1 else "HEAD~1..HEAD"
HEADS = [r"अपडेट में क्या नया", r"क्या नया", r"what'?s new", r"क्या बदला"]
DROP_START = ("merge pull request", "merge branch", "merge remote", 'revert "merge', "co-authored-by", "signed-off-by",
              "tests:", "test:", "ci:", "chore", "bump", "backup", "wip")
DROP_HAS = ("backup/", ".kt", ".xml", ".yml", ".gradle", "unit test", "testdebug", "assembledebug", "github/workflows",
            "agents.md", "```", "http://", "https://", "pull/", "feature/", "रिपो", "branch")


def sh(*a):
    try:
        return subprocess.run(a, capture_output=True, text=True, timeout=60).stdout
    except Exception:
        return ""


def line(raw):
    s = raw.strip().lstrip("•-*+ \t").strip()
    if len(s) < 4 or s.startswith("#") or s == "---":
        return None
    s = re.sub(r"^\[(software|security)\]\s*", "", s, flags=re.I)
    s = re.sub(r"\s*\(#\d+\)$", "", s)
    s = re.sub(r"\s*[+&]\s*tests?\s*$", "", s, flags=re.I).replace("**", "").replace("`", "").strip()
    low = s.lower()
    if low.startswith(DROP_START) or any(h in low for h in DROP_HAS):
        return None
    if re.match(r"^(type|version):", s, re.I) or re.search(r"[{};]|\(\)|->|==|:=|\w\(\w", s):
        return None
    if len(s) > 140:
        t = s[:140]
        cuts = [i for i in (t.find("।"), t.find(". "), t.find(" — "), t.find("; ")) if i > 20]
        s = (t[:min(cuts)] if cuts else t.rsplit(" ", 1)[0]).strip().rstrip("।.,") + "…"
    return s


def section(body):
    lines = (body or "").replace("\r", "").split("\n")
    for head in HEADS:
        for i, l in enumerate(lines):
            if re.match(r"^#{2,3}\s*" + head, l.strip(), re.I):
                out = []
                for m in lines[i + 1:]:
                    if m.strip().startswith("#"):
                        break
                    out.append(m)
                got = [x for x in map(line, out) if x]
                if got:
                    return got
    return []


notes = []
prs = re.findall(r"Merge pull request #(\d+)", sh("git", "log", "--first-parent", "--pretty=%s", RANGE))
for n in prs:
    try:
        pr = json.loads(sh("gh", "pr", "view", n, "--json", "title,body") or "{}")
    except Exception:
        pr = {}
    got = section(pr.get("body", ""))
    if not got and pr.get("title"):
        t = line(pr["title"])
        got = [t] if t else []
    notes += got
if not prs:
    notes = [x for x in map(line, sh("git", "log", "--no-merges", "--pretty=%s", RANGE).split("\n")) if x]

seen, final = set(), []
for x in notes:
    k = re.sub(r"\W", "", x.lower())
    if k and k not in seen:
        seen.add(k)
        final.append("• " + x)
print("\n".join(final[:15]) or "• छोटे सुधार और बेहतर स्थिरता")
