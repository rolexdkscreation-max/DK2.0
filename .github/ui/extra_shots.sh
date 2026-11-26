# 26 नवंबर 2026: होम कैरेक्टर (Full-Bleed Environment + Universal Animation) - हर थीम x हर तस्वीर-आकार, पहले / बाद.
# shots.sh के run() के अंदर source होता है ($tag, $PKG, $OUT, go, shot, revive मौजूद).
XA=/tmp/xa
if [ ! -f $XA/index.json ]; then
  mkdir -p $XA
  python3 -c "import PIL" 2>/dev/null || pip install -q pillow >/dev/null 2>&1
  python3 - <<'PY'
from PIL import Image, ImageDraw
import json
A='avatars/'; X='/tmp/xa/'
bun=Image.open(A+'dk_art_glasses_bun_hq.png').convert('RGB')
bun.crop((256,0,1792,2048)).resize((768,1024)).save(X+'ex_portrait.png')
h=Image.open(A+'dk_art_hearts_hq.png').convert('RGB')
h.crop((0,448,2048,1600)).resize((1024,576)).save(X+'ex_landscape.png')
o=Image.open(A+'dk_art_headphone_orb_hq.png').convert('RGB').resize((940,940))
c=Image.new('RGB',(1024,1024),(255,255,255)); m=Image.new('L',(940,940),0)
ImageDraw.Draw(m).ellipse((0,0,939,939),fill=255); c.paste(o,(42,42),m); c.save(X+'ex_circle.png')
r=Image.new('RGBA',(800,1000),(0,0,0,0)); d=ImageDraw.Draw(r)
d.rounded_rectangle((150,560,650,1060),radius=120,fill=(90,104,128,255))
d.rounded_rectangle((220,120,580,520),radius=110,fill=(200,208,222,255))
d.rounded_rectangle((260,240,540,380),radius=60,fill=(11,18,32,255))
d.ellipse((310,280,370,340),fill=(34,211,238,255)); d.ellipse((430,280,490,340),fill=(34,211,238,255))
d.rectangle((392,90,408,130),fill=(142,155,176,255)); d.ellipse((374,54,426,106),fill=(245,158,11,255))
r.save(X+'ex_robot.png')
json.dump({"version":2,"avatars":[{"id":i,"file":i+".png","name":i,"gender":"girl"} for i in ["ex_portrait","ex_landscape","ex_circle","ex_robot"]]},open(X+'index.json','w'))
PY
  cp avatars/dk_art_hearts_hq.png $XA/ 2>/dev/null || true
  ls -la $XA
fi

xa_push() {
  revive
  local D=/data/data/$PKG/files/avatars
  adb shell "mkdir -p $D"
  for f in $XA/*; do adb push "$f" /data/local/tmp/ >/dev/null; adb shell "cp /data/local/tmp/$(basename $f) $D/"; done
  local U; U=$(adb shell stat -c %u /data/data/$PKG | tr -d '\r')
  adb shell "chown -R $U:$U /data/data/$PKG/files; restorecon -R /data/data/$PKG/files" || true
}

# dk_prefs.xml में key=value (string) बदलो / जोड़ो
xa_prefs() {
  revive
  adb shell am force-stop $PKG
  local P=/data/data/$PKG/shared_prefs/dk_prefs.xml
  adb shell "cat $P" > /tmp/xa_p.xml 2>/dev/null || true
  python3 - "$@" <<'PY'
import sys, re, html
p='/tmp/xa_p.xml'
try: s=open(p).read()
except Exception: s=''
if '<map' not in s: s="<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
s=s.replace('<map />','<map>\n</map>')
for kv in sys.argv[1:]:
    k,v=kv.split('=',1)
    s=re.sub(r'\s*<string name="%s">[^<]*</string>' % re.escape(k), '', s)
    s=s.replace('</map>','    <string name="%s">%s</string>\n</map>' % (k, html.escape(v)))
if 'name="onboarded"' not in s: s=s.replace('</map>','    <boolean name="onboarded" value="true" />\n</map>')
open(p,'w').write(s)
PY
  adb push /tmp/xa_p.xml /data/local/tmp/xa_p.xml >/dev/null
  adb shell "cp /data/local/tmp/xa_p.xml $P"
  local U; U=$(adb shell stat -c %u /data/data/$PKG | tr -d '\r')
  adb shell "chown $U:$U $P; chmod 660 $P; restorecon $P" || true
}

xa_home() { revive; timeout 90 adb shell am start -S -W -n $PKG/.MainActivity >/dev/null; shot "$1" "${2:-9}"; }

xa_push
# HQ वाला कैरेक्टर (चौकोर 2048x2048) - हर थीम
for th in clay_day midnight ember gehra_navy nocturne; do
  xa_prefs clay_theme=$th home_center=avatar:dk_art_hearts dkai_orb=fluid
  xa_home ${tag}_x_square_$th 10
done
# अलग आकार - हल्की और गहरी थीम
for th in clay_day midnight; do
  for s in ex_portrait ex_landscape ex_circle ex_robot; do
    xa_prefs clay_theme=$th home_center=avatar:$s dkai_orb=fluid
    xa_home ${tag}_x_${s#ex_}_$th 8
  done
  # रोशनी वाला कैरेक्टर (spirit)
  xa_prefs clay_theme=$th home_center=orb dkai_orb=spirit_female
  xa_home ${tag}_x_spirit_$th 9
done
# वापस डिफ़ॉल्ट (बाकी screenshots पहले जैसे)
xa_prefs clay_theme=midnight home_center=avatar:orb_art_dk_wave dkai_orb=fluid
