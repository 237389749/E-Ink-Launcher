# -*- coding: utf-8 -*-
"""dump wbf 全部 mode 的 temp 指针原值, 搞清结构"""
import sys
sys.stdout.reconfigure(encoding='utf-8')
P = r"C:\Users\root\Documents\eink\eink_waveform.wbf"
d = open(P, 'rb').read()

def u24(o): return d[o] | (d[o+1]<<8) | (d[o+2]<<16)
mc = d[0x25]; NMODE = mc+1; NTEMP = d[0x26]+1
temps = list(d[0x30:0x30+NTEMP])
XWIA = u24(0x1c); MT = XWIA + 1 + d[XWIA] + 1
mode_base = [u24(MT + 4*i) for i in range(NMODE)]

print(f"NMODE={NMODE} NTEMP={NTEMP} temps={temps}")
print(f"mode_base = {['0x%x'%b for b in mode_base]}")
print(f"相邻 mode_base 差 = {[mode_base[i+1]-mode_base[i] for i in range(NMODE-1)]}")

print("\n=== 每个 mode 的 14 个 temp 指针 (u24) + 校验 ===")
allp = {}
for mi in range(NMODE):
    b = mode_base[mi]
    row = []
    for ti in range(NTEMP):
        o = b + 4*ti
        v = u24(o)
        ck = d[o+3]
        ok = ((v & 0xFF) + ((v>>8)&0xFF) + ((v>>16)&0xFF)) & 0xFF == ck
        row.append((v, ok))
    allp[mi] = row
    vals = [f"{v}{'' if ok else '!'}" for v, ok in row]
    print(f"  mode{mi} base=0x{b:x}: {vals}")

# 用"全体指针排序"算段长
flat = sorted(set(v for mi in range(NMODE) for v, ok in allp[mi] if ok) | {len(d)})
print(f"\n=== 排序后全部指针 (共 {len(flat)}) 前 20 ===")
print(f"  {['0x%x'%v for v in flat[:20]]}")
print(f"  ... 后 10: {['0x%x'%v for v in flat[-10:]]}")

def seglen(a):
    for j, v in enumerate(flat):
        if v == a: return (flat[j+1]-a) if j+1 < len(flat) else 0
    return -1

print("\n=== 24°C(tr8) 各 mode: 指针/段长/解码phases ===")
for mi in range(NMODE):
    a, ok = allp[mi][8]
    if not ok: print(f"  mode{mi}: 校验失败"); continue
    ln = seglen(a)
    body = ln-2
    out = []; i = 0; fc = False
    while i < body and a+i < len(d):
        b_ = d[a+i]
        if b_ == 0xfc: fc = not fc; i += 1; continue
        c = 1 if fc else (d[a+i+1]+1 if a+i+1 < len(d) else 1)
        i += 1 if fc else 2
        out.extend([b_]*c)
    ph = len(out)//256
    print(f"  mode{mi}: ptr=0x{a:x} len={ln} phases={ph} (rawvals={len(out)})")
