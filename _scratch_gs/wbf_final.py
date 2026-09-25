# -*- coding: utf-8 -*-
"""最终严谨版: 用全局排序指针算段长, 输出完整矩阵 + 物理规格"""
import sys, hashlib
sys.stdout.reconfigure(encoding='utf-8')
P = r"C:\Users\root\Documents\eink\eink_waveform.wbf"
d = open(P, 'rb').read()
print(f"md5={hashlib.md5(d).hexdigest()}  size={len(d)}")

def u24(o): return d[o] | (d[o+1]<<8) | (d[o+2]<<16)
NMODE = d[0x25]+1; NTEMP = d[0x26]+1
temps = list(d[0x30:0x30+NTEMP])
XWIA = u24(0x1c); MT = XWIA + 1 + d[XWIA] + 1
mode_base = [u24(MT + 4*i) for i in range(NMODE)]
print(f"NMODE={NMODE} NTEMP={NTEMP}\n温度点={temps}")

# 收集全部 (校验通过的) 指针
ptrs = set()
for mi in range(NMODE):
    for ti in range(NTEMP):
        v = u24(mode_base[mi] + 4*ti)
        if v > 0: ptrs.add(v)
flat = sorted(ptrs | {len(d)})
def seglen(a):
    for j, v in enumerate(flat):
        if v == a: return flat[j+1]-a
    return 0

def decode(a, ln):
    body = ln - 2
    out = []; i = 0; fc = False
    while i < body and a+i < len(d):
        b = d[a+i]
        if b == 0xfc: fc = not fc; i += 1; continue
        c = 1 if fc else (d[a+i+1]+1 if a+i+1 < len(d) else 1)
        i += 1 if fc else 2
        out.extend([b]*c)
    return out

mat = {}; spec = {}
for mi in range(NMODE):
    for ti in range(NTEMP):
        a = u24(mode_base[mi] + 4*ti)
        if a <= 0: continue
        ln = seglen(a)
        st = decode(a, ln)
        ph = len(st)//256
        mat[(mi,ti)] = ph
        if ph > 0 and len(st) == ph*256:
            drv = sd = 0
            for s in range(256):
                vals = []
                for bb in st[s*ph:(s+1)*ph]:
                    vals.extend([(bb>>k)&3 for k in (0,2,4,6)])
                if any(v in (1,2) for v in vals): drv += 1
                if s in (0,0x55,0xAA,0xFF) and any(v in (1,2) for v in vals): sd += 1
            spec[(mi,ti)] = (drv, sd, ln, len(st))

print("\n=== 完整帧数矩阵 (rows=温度, cols=mode0..%d) ===" % (NMODE-1))
print("  temp  " + "".join(f"{m:>6}" for m in range(NMODE)))
for ti in range(NTEMP):
    print(f"  {temps[ti]:>3}C  " + "".join(f"{mat.get((mi,ti),0):>6}" for mi in range(NMODE)))

print("\n=== 24°C(tr8) 物理规格 ===")
print(f"{'mode':>4} {'phases':>7} {'drv/256':>8} {'sameDrv/4':>10} {'segLen':>7} {'rawVals':>8} {'整除?':>6}")
for mi in range(NMODE):
    k = (mi, 8)
    if k in spec:
        drv, sd, ln, rv = spec[k]
        exact = "✓" if rv == mat[k]*256 else f"✗({rv})"
        print(f"{mi:>4} {mat[k]:>7} {drv:>8} {sd:>10} {ln:>7} {rv:>8} {exact:>6}")
    elif k in mat:
        print(f"{mi:>4} {mat[k]:>7}   (解码不整除, len={seglen(u24(mode_base[mi]+4*8))})")

print("\n=== mode 指针完全相同的组 (逐字节同数据) ===")
groups = {}
for mi in range(NMODE):
    key = tuple(u24(mode_base[mi]+4*ti) for ti in range(NTEMP))
    groups.setdefault(key, []).append(mi)
for key, ms in groups.items():
    if len(ms) > 1: print(f"  mode{ms} 共享同一批指针 (数据逐字节相同)")
