# DK ब्रांड

- `gen_brand.py` — एक ही spec से सारे Android vector बनाता है: `ic_launcher_background/foreground/monochrome` (adaptive icon),
  `ic_dk` (notification का सफ़ेद icon), `dk_splash_logo` (splash)। बदलाव यहीं करो, फिर `python3 branding/gen_brand.py --png`।
- `dk_icon.svg`, `dk_icon_512.png` — Play Store / प्रचार के लिए।
- रंग: नीला `#2EC5FF` → `#5B6CFF` → बैंगनी `#B14DFF`, पृष्ठभूमि `#05060D`।
- छोटा काम-वाला logo: `DKWorkingIndicator` (Canvas, यही 7 बार)। हालत: `DKWork` (`IDLE/LISTENING/THINKING/SPEAKING/WORKING`)।
  नया फ़ीचर बस `DKWork.begin("लेबल")` / `DKWork.end(id)` या `DKWork.track("लेबल") { … }` करे।
