#!/usr/bin/env bash
# Rebuild → deploy to EMULATOR ONLY (never the phone) → launch repro → screenshot → felt-masked diff
# vs the reference. Fast iteration loop for HUD pixel-parity. Usage: bash deploy_diff.sh [tag]
set -euo pipefail
export PATH="$HOME/.nix-profile/bin:$PATH"
TAG="${1:-r}"
RB=/home/jluker/balatro-cryptid-mobile/rebuild
EMU=emulator-5560
REF=/tmp/bref_3.png
OUT=/tmp/${TAG}.png

cd "$RB"
./gradlew :app:assembleDebug -q 2>&1 | tail -5
adb -s "$EMU" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null 2>&1
# Force the display to bref_3's resolution (portrait 2160x3840 → landscape 3840x2160). The room
# layout is width-constrained + u-based, so px positions are density-independent; this makes the
# diff apples-to-apples on ANY emulator (a rebuilt AVD may default to a different panel size).
adb -s "$EMU" shell wm size 2160x3840 >/dev/null 2>&1
# Suppress the one-time "Viewing full screen" immersive-mode dialog: on a fresh emulator it obscures
# the frame AND keeps the app out of true full-screen (insets shift the whole layout) → false ~44% diff.
adb -s "$EMU" shell settings put secure immersive_mode_confirmations confirmed >/dev/null 2>&1
adb -s "$EMU" shell am force-stop systems.balatro.rebuild
adb -s "$EMU" shell am start -n systems.balatro.rebuild/systems.balatro.ui.MainActivity --es screen repro >/dev/null 2>&1
sleep 4
adb -s "$EMU" shell screencap -p /sdcard/${TAG}.png
adb -s "$EMU" pull /sdcard/${TAG}.png "$OUT" >/dev/null 2>&1

/tmp/imgvenv/bin/python - "$OUT" "$REF" "$TAG" <<'PY'
import sys
from PIL import Image; import numpy as np
mp,rp,tag=sys.argv[1],sys.argv[2],sys.argv[3]
m=np.asarray(Image.open(mp).convert('RGB')).astype(int)
r=np.asarray(Image.open(rp).convert('RGB')).astype(int)
H,W,_=m.shape
d=np.abs(m-r).sum(2)
def felt(a):
    R,G,B=a[...,0],a[...,1],a[...,2]; return (G>R+8)&(G>B+4)&(G>45)&(G<185)
fb=felt(m)&felt(r); mask=(d>60)&~fb
print(f"[{tag}] FULL {mask.mean()*100:.1f}% differ | HUD(left26%) {mask[:,:int(W*0.26)].mean()*100:.1f}%")
# side-by-side + heat of the HUD region
x1=int(W*0.30)
heat=(r*0.25).astype('uint8'); heat[mask]=[255,0,255]
def sc(im,h=560): return im.resize((int(im.width*h/im.height),h))
mine=sc(Image.fromarray(m[:,:x1].astype('uint8'))); ref=sc(Image.fromarray(r[:,:x1].astype('uint8'))); hc=sc(Image.fromarray(heat[:,:x1]))
combo=Image.new('RGB',(mine.width+ref.width+hc.width+20,560),(20,20,20))
combo.paste(mine,(0,0)); combo.paste(ref,(mine.width+10,0)); combo.paste(hc,(mine.width+ref.width+20,0))
combo.save(f'/tmp/{tag}_compare.png')
print(f"  -> /tmp/{tag}_compare.png  (MINE | REF | HEAT)")
PY