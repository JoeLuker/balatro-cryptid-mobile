#!/usr/bin/env python3
"""Verify a faithful port of Balatro's UIBox layout (calculate_xywh + set_wh + set_alignments)
against the real engine's geometry dump (hud_geometry.full.txt). This is the EXACT logic the
Kotlin port (UILayout.kt) implements — if this matches the dump, the Kotlin will too.

Run: python3 verify_layout.py
"""
import json, re, sys

# glyph advance table (em = getWidth(char @ size N)/N), dumped from LÖVE — additivity verified, no kerning.
GLYPH_EM = {32:0.44,33:0.19,34:0.39,35:0.505,36:0.44,37:0.44,38:0.63,39:0.195,40:0.315,41:0.315,42:0.38,43:0.44,44:0.195,45:0.44,46:0.19,47:0.44,48:0.44,49:0.44,50:0.44,51:0.44,52:0.44,53:0.44,54:0.44,55:0.44,56:0.44,57:0.44,58:0.19,59:0.19,60:0.38,61:0.44,62:0.38,63:0.44,64:0.63,65:0.44,66:0.44,67:0.44,68:0.44,69:0.44,70:0.44,71:0.44,72:0.44,73:0.44,74:0.44,75:0.44,76:0.44,77:0.565,78:0.44,79:0.44,80:0.44,81:0.505,82:0.44,83:0.44,84:0.44,85:0.44,86:0.44,87:0.565,88:0.505,89:0.44,90:0.44,91:0.315,92:0.44,93:0.315,94:0.44,95:0.44,96:0.065,97:0.44,98:0.44,99:0.44,100:0.44,101:0.44,102:0.44,103:0.44,104:0.44,105:0.19,106:0.315,107:0.44,108:0.19,109:0.565,110:0.44,111:0.44,112:0.44,113:0.44,114:0.44,115:0.44,116:0.44,117:0.44,118:0.44,119:0.565,120:0.505,121:0.44,122:0.44,123:0.38,124:0.19,125:0.38,126:0.44}
TEXT_H = 0.83

# node types
T,B,C,R,O,ROOT = 1,2,3,4,5,7
TYPE = {"T":T,"B":B,"C":C,"R":R,"O":O,"ROOT":ROOT}

def text_w(s, scale):
    return sum(GLYPH_EM.get(ord(c), 0.44) for c in s) * scale

# resolve the text/obj a leaf carries, matching tools/uiref main.lua's stub G.GAME values (the
# state the dump was captured in): hands_left=4, discards_left=3, dollars=4, chips=300, ante=1, etc.
LOC = {"k_hud_hands":"Hands","k_hud_discards":"Discards","k_ante":"Ante","k_round":"Round",
       "k_lower_score":"score","b_run_info_1":"Run","b_run_info_2":"Info","b_options":"Options","$":"$"}
REFVAL = {"win_ante":"8","chips_text":"300","hand_level":""}
SEGVAL = {"hands_left":"4","discards_left":"3","dollars":"4","ante":"1","round":"1",
          "handname_text":"","chip_total_text":"","chip_text":"0","mult_text":"0"}

def node_text(cfg):
    t = cfg.get("text")
    if isinstance(t, dict) and t.get("$")=="loc": return LOC.get(t.get("key"),"")
    if isinstance(t, str): return t
    if "ref_value" in cfg: return REFVAL.get(cfg["ref_value"],"")
    return ""

def obj_size(cfg):
    """O node intrinsic size when config.w/h absent: object.T.w/h (dynatext self-measures)."""
    o = cfg.get("object") or {}
    kind = o.get("$")
    if kind == "dynatext":
        scale = float(o.get("scale",1))
        spacing = float(o.get("spacing",0))
        segs = o.get("segs",[])
        w = 0.0; h = 0.0
        for sg in segs:
            val = ""
            if "value" in sg: val = SEGVAL.get(sg["value"],"")
            elif "text" in sg: val = sg["text"]
            pre = sg.get("prefix")
            if isinstance(pre, dict): pre = LOC.get(pre.get("loc"),"")
            elif not isinstance(pre, str): pre = ""
            full = pre+val
            # per-letter: em*scale + 2.7*spacing*FONTSCALE/TILESIZE  (= em*scale + 0.0135*spacing) per text.lua:152
            w += text_w(full, scale) + len(full)*0.0135*spacing
            if full: h = max(h, scale*TEXT_H)   # empty seg contributes no height (loop doesn't run)
        return w, h
    if kind == "sprite":
        sc = float(o.get("scale",0.5)); return sc, sc
    return 0.0, 0.0   # moveable (flame): zero

class N:
    __slots__=("type","cfg","kids","text","x","y","w","h","cw","ch","label")
    def __init__(self, j, is_root=False):
        self.type = ROOT if is_root else TYPE[j["n"]]
        self.cfg = j.get("config",{})
        self.kids = [N(k) for k in j.get("nodes",[])]
        self.text = node_text(self.cfg) if self.type==T else ""
        self.x=self.y=self.w=self.h=self.cw=self.ch=0.0
        self.label = self.cfg.get("text") if isinstance(self.cfg.get("text"),str) else self.cfg.get("id","")
        if self.type==T and self.text: self.label=self.text

def cfgf(cfg, k, d=0.0):
    v = cfg.get(k, d)
    try: return float(v)
    except: return d

def calc(n, tx, ty, fac=1.0):
    cfg = n.cfg
    padding = cfgf(cfg,"padding")
    scale = cfgf(cfg,"scale",1.0)*fac
    if n.type in (T,B,O):
        if n.type==T:
            w = text_w(n.text, scale); h = scale*TEXT_H
        else:  # B or O
            if "w" in cfg or "h" in cfg:
                w = cfgf(cfg,"w"); h = cfgf(cfg,"h")
            elif n.type==O:
                w,h = obj_size(cfg)
            else:
                w=h=0.0
        n.x,n.y,n.w,n.h = tx,ty,w,h
        return w,h
    # container (C,R,ROOT) — treated as column accumulation; advance axis by child type
    ctW=ctH=0.0
    for i in (1,2):
        exceeded = i==2 and ((cfgf(cfg,"maxw")>0 and ctW>cfgf(cfg,"maxw")) or (cfgf(cfg,"maxh")>0 and ctH>cfgf(cfg,"maxh")))
        if i==1 or exceeded:
            f = fac
            if i==2:
                maxw=cfgf(cfg,"maxw"); maxh=cfgf(cfg,"maxh")
                restriction = maxw if maxw>0 else maxh
                f = fac*restriction/(ctW if maxw>0 else ctH)
            n.x,n.y = tx,ty
            n.w = cfgf(cfg,"minw"); n.h = cfgf(cfg,"minh")
            if n.type==ROOT: n.x=0.0; n.y=0.0
            cx=n.x+padding; cy=n.y+padding; ctW=0.0; ctH=0.0
            for ch in n.kids:
                tw,th = calc(ch, cx, cy, f)
                emboss = cfgf(ch.cfg,"emboss")
                if ch.type==R:
                    ctH += th+padding; cy += th+padding
                    if tw+padding>ctW: ctW=tw+padding
                    if emboss>0: ctH+=emboss; cy+=emboss
                else:
                    ctW += tw+padding; cx += tw+padding
                    if th+padding>ctH: ctH=th+padding
                    if emboss>0: ctH+=emboss
    n.cw = ctW+padding; n.ch = ctH+padding
    n.w = max(ctW+padding, n.w); n.h = max(ctH+padding, n.h)
    return n.w,n.h

def set_wh(n):
    padding = cfgf(n.cfg,"padding")
    if not n.kids or n.cfg.get("no_fill"): return n.w,n.h
    mw=mh=0.0
    for c in n.kids:
        cw,ch = set_wh(c)
        if cw>mw: mw=cw
        if ch>mh: mh=ch
    for c in n.kids:
        if c.type==R: c.w=mw
        if c.type==C: c.h=mh
    return n.w,n.h

def align(n,dx,dy):
    n.x+=dx; n.y+=dy
    for c in n.kids: align(c,dx,dy)

def set_alignments(n):
    cfg=n.cfg; padding=cfgf(cfg,"padding"); a=cfg.get("align","")
    for c in n.kids:
        if a:
            if "c" in a:
                if c.type in (T,B,O): align(c,0,0.5*(n.h-2*padding-c.h))
                else: align(c,0,0.5*(n.h-n.ch))
            if "m" in a: align(c,0.5*(n.w-n.cw),0)
            if "b" in a: align(c,0,n.h-n.ch)
            if "r" in a: align(c,(n.w-n.cw),0)
        set_alignments(c)

def flat(n, out):
    out.append(n);
    for c in n.kids: flat(c,out)

def parse_dump(path):
    rows=[]
    for line in open(path):
        m = re.match(r'^((?:\| )*)(\d+)\s+x=([\-\d.]+) y=([\-\d.]+) w=([\-\d.]+) h=([\-\d.]+)\s*(.*)$', line.rstrip())
        if not m: continue
        depth=len(m.group(1))//2
        rows.append((depth,int(m.group(2)),float(m.group(3)),float(m.group(4)),float(m.group(5)),float(m.group(6)),m.group(7).strip()))
    return rows

def main():
    tree = json.load(open("hud_tree.json"))
    root = N(tree, is_root=True)
    calc(root,0,0); set_wh(root); set_alignments(root)
    mine=[]; flat(root,mine)
    ref = parse_dump("hud_geometry.full.txt")
    print(f"mine={len(mine)} nodes, ref={len(ref)} nodes")
    n = min(len(mine), len(ref))
    worst=0.0; bad=0
    for i in range(n):
        d,typ,rx,ry,rw,rh,lbl = ref[i]
        m = mine[i]
        ex = max(abs(m.x-rx),abs(m.y-ry),abs(m.w-rw),abs(m.h-rh))
        worst=max(worst,ex)
        flag = "" if ex<0.001 else "  <<<<<< DIFF"
        if ex>=0.001: bad+=1
        if ex>=0.001 or "-v" in sys.argv:
            print(f"  [{i}] t{m.type} mine(x={m.x:.4f} y={m.y:.4f} w={m.w:.4f} h={m.h:.4f}) ref(x={rx:.4f} y={ry:.4f} w={rw:.4f} h={rh:.4f}) {lbl}{flag}")
    print(f"worst abs error = {worst:.5f} units;  {bad}/{n} nodes differ (>0.001u)")
    if len(mine)!=len(ref): print(f"!! node count mismatch: mine {len(mine)} vs ref {len(ref)}")

main()
