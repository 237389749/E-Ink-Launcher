# -*- coding: utf-8 -*-
"""独立校验文档中的关键数值"""
import sys, hashlib, struct
sys.stdout.reconfigure(encoding='utf-8')
P = r"C:\Users\root\Documents\eink\eink_waveform.wbf"
d = open(P, 'rb').read()
def u24(o): return d[o] | (d[o+1]<<8) | (d[o+2]<<16)

print("=== CHECK 1: 文件 md5 ===")
m = hashlib.md5(d).hexdigest()
print(f"  实际 {m}")
print(f"  文档 f463661b158b9dc394f2a8e78d9322f8  {'✓' if m=='f463661b158b9dc394f2a8e78d9322f8' else '✗'}")

print("\n=== CHECK 2: mc/trc/温度点 ===")
print(f"  mc@0x25 = {d[0x25]} -> NMODE = {d[0x25]+1}  (文档: 8)  {'✓' if d[0x25]+1==8 else '✗'}")
print(f"  trc@0x26 = {d[0x26]} -> NTEMP = {d[0x26]+1}  (文档: 14)  {'✓' if d[0x26]+1==14 else '✗'}")
t = list(d[0x30:0x30+14])
print(f"  温度点 = {t}")
print(f"  文档 [0,3,...,38,43]  {'✓' if t==[0,3,6,9,12,15,18,21,24,27,30,33,38,43] else '✗'}")

print("\n=== CHECK 3: mode3/4/5 指针相同 ===")
NMODE=8; NTEMP=14
XWIA=u24(0x1c); MT=XWIA+1+d[XWIA]+1
mb=[u24(MT+4*i) for i in range(NMODE)]
p3=[u24(mb[3]+4*ti) for ti in range(NTEMP)]
p4=[u24(mb[4]+4*ti) for ti in range(NTEMP)]
p5=[u24(mb[5]+4*ti) for ti in range(NTEMP)]
print(f"  mode3==mode4: {'✓' if p3==p4 else '✗'}   mode4==mode5: {'✓' if p4==p5 else '✗'}")

print("\n=== CHECK 4: 24C 段帧数 (文档: 0:113 1:22 2:39 3:39 4:39 5:39 6:10 7:24) ===")
doc = {0:113,1:22,2:39,3:39,4:39,5:39,6:10,7:24}
def seglen_mode(mi, ti):
    p0=u24(mb[mi]+4*ti)
    for k in range(ti+1,NTEMP):
        p1=u24(mb[mi]+4*k)
        if p1>p0: return p1-p0
    for m2 in range(mi+1,NMODE):
        for k in range(NTEMP):
            p1=u24(mb[m2]+4*k)
            if p1>p0: return p1-p0
    return len(d)-p0
def dec(a,mb_):
    out=[]; i=0; fc=False
    while i<mb_:
        b=d[a+i]
        if b==0xfc: fc=not fc; i+=1; continue
        c=1 if fc else (d[a+i+1]+1)
        i+=1 if fc else 2
        out.extend([b]*c)
    return out
allok=True
for mi in range(8):
    a=u24(mb[mi]+4*8); ln=seglen_mode(mi,8)
    out=dec(a,ln-2); ph=len(out)//256
    ok = ph==doc[mi]
    allok &= ok
    print(f"  mode{mi}: 实测 {ph}  文档 {doc[mi]}  {'✓' if ok else '✗'}")
print(f"  → {'全部一致' if allok else '有偏差'}")

print("\n=== CHECK 5: mode8-B 是否存在 REAGL 段 ===")
print(f"  NMODE={NMODE} => 有效 mode 0..{NMODE-1}")
print(f"  文档称 mode8..modeB 缺失  {'✓' if NMODE==8 else '✗'}")
