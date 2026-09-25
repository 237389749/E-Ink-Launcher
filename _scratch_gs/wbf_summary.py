# -*- coding: utf-8 -*-
"""汇总最终可信数据表, 供文档使用"""
import sys, hashlib
sys.stdout.reconfigure(encoding='utf-8')
P = r"C:\Users\root\Documents\eink\eink_waveform.wbf"
d = open(P, 'rb').read()
def u24(o): return d[o] | (d[o+1]<<8) | (d[o+2]<<16)
NMODE=d[0x25]+1; NTEMP=d[0x26]+1
temps=list(d[0x30:0x30+NTEMP])
XWIA=u24(0x1c); MT=XWIA+1+d[XWIA]+1
mode_base=[u24(MT+4*i) for i in range(NMODE)]

# 用"每 mode 内相邻指针"算段长 (比全局排序更准, 因为同 mode 指针单调)
def seglen_mode(mi, ti):
    p0 = u24(mode_base[mi]+4*ti)
    # 同 mode 下一个不同指针
    for k in range(ti+1, NTEMP):
        p1 = u24(mode_base[mi]+4*k)
        if p1 > p0: return p1-p0
    # 跨 mode
    for m2 in range(mi+1, NMODE):
        for k in range(NTEMP):
            p1 = u24(mode_base[m2]+4*k)
            if p1 > p0: return p1-p0
    return len(d)-p0

def dec(a,mb):
    out=[]; i=0; fc=False
    while i<mb:
        b=d[a+i]
        if b==0xfc: fc=not fc; i+=1; continue
        c=1 if fc else (d[a+i+1]+1)
        i+=1 if fc else 2
        out.extend([b]*c)
    return out

print(f"wbf md5={hashlib.md5(d).hexdigest()}")
print(f"NMODE={NMODE} NTEMP={NTEMP}")
print(f"温度点={temps}\n")

NAMES={0:'INIT',1:'DU',2:'GC16',3:'GC16_FAST',4:'A2(名)',5:'GL16',6:'GL16_FAST',7:'DU4'}
BPP={0:'—',1:'1bpp',2:'4bpp',3:'4bpp',4:'—',5:'4bpp',6:'4bpp',7:'2bpp'}

print("=== 帧数矩阵 (同 mode 相邻指针算法) ===")
print("  temp  " + "".join(f"{m:>6}" for m in range(NMODE)))
mat={}
for ti in range(NTEMP):
    row=[]
    for mi in range(NMODE):
        a=u24(mode_base[mi]+4*ti)
        if a==0: row.append(0); continue
        ln=seglen_mode(mi,ti)
        out=dec(a,ln-2); ph=len(out)//256
        mat[(mi,ti)]=ph; row.append(ph)
    print(f"  {temps[ti]:>3}C  " + "".join(f"{v:>6}" for v in row))

print("\n=== 物理规格 (22~24C 段实测) ===")
print(f"{'mode':>4} {'标准名':<12} {'bpp':>5} {'帧数':>5} {'state覆盖':>9} {'驱动率':>7} {'同色驱动':>8} {'可区分灰阶':>10}")
for mi in range(NMODE):
    # 选 24C (tr8)
    a=u24(mode_base[mi]+4*8)
    if a==0: continue
    ln=seglen_mode(mi,8); out=dec(a,ln-2); ph=len(out)//256
    st=out[:ph*256]
    cov=0; drvTot=0; tot=0; sDrv=0; sTot=0
    SAME=[(x<<4)|x for x in range(16)]
    for s in range(256):
        vals=[]
        for bb in st[s*ph:(s+1)*ph]:
            vals.extend([(bb>>k)&3 for k in (0,2,4,6)])
        nd=sum(1 for v in vals if v in (1,2))
        tot+=len(vals); drvTot+=nd
        if nd: cov+=1
        if s in SAME: sTot+=len(vals); sDrv+=nd
    # 可区分灰阶: 每个 from 下不同净驱动量的最大值
    maxk=0
    for fr in range(16):
        s_=set()
        for to in range(16):
            s2=(fr<<4)|to
            vals=[]
            for bb in st[s2*ph:(s2+1)*ph]:
                vals.extend([(bb>>k)&3 for k in (0,2,4,6)])
            s_.add(sum(1 for v in vals if v==1)-sum(1 for v in vals if v==2))
        maxk=max(maxk,len(s_))
    print(f"{mi:>4} {NAMES[mi]:<12} {BPP[mi]:>5} {ph:>5} {cov:>5}/256 {drvTot/tot:>7.3f} {sDrv/sTot:>8.3f} {maxk:>10}")

print("\n=== mode 指针共享 (逐字节同数据) ===")
g={}
for mi in range(NMODE):
    k=tuple(u24(mode_base[mi]+4*ti) for ti in range(NTEMP))
    g.setdefault(k,[]).append(mi)
for k,ms in g.items():
    if len(ms)>1: print(f"  mode{ms} 完全相同")
