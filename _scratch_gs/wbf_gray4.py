# -*- coding: utf-8 -*-
"""灰阶能力实测: 固定 from, 统计 16 个 to 产生的【不同驱动序列】数
   这是"该波形能区分多少个目标灰阶"的直接度量
"""
import sys
sys.stdout.reconfigure(encoding='utf-8')
P = r"C:\Users\root\Documents\eink\eink_waveform.wbf"
d = open(P, 'rb').read()
def u24(o): return d[o] | (d[o+1]<<8) | (d[o+2]<<16)
NMODE = d[0x25]+1; NTEMP = d[0x26]+1
XWIA = u24(0x1c); MT = XWIA + 1 + d[XWIA] + 1
mode_base = [u24(MT + 4*i) for i in range(NMODE)]
ptrs=set()
for mi in range(NMODE):
    for ti in range(NTEMP):
        v=u24(mode_base[mi]+4*ti)
        if v>0: ptrs.add(v)
flat=sorted(ptrs|{len(d)})
def seglen(a):
    for j,v in enumerate(flat):
        if v==a: return flat[j+1]-a
    return 0
def dec(a,mb):
    out=[]; i=0; fc=False
    while i<mb:
        b=d[a+i]
        if b==0xfc: fc=not fc; i+=1; continue
        c=1 if fc else (d[a+i+1]+1)
        i+=1 if fc else 2
        out.extend([b]*c)
    return out

NAMES={0:'INIT',1:'DU',2:'GC16',3:'GC16_FAST',4:'A2(名)',5:'GL16',6:'GL16_FAST',7:'DU4'}
L1BPP={0:'—',1:1,2:4,3:4,4:'—',5:4,6:4,7:2}
print("=== 每个 from 值下, 16 个 to 能区分出多少种不同驱动序列 ===")
print(f"{'mode':>4} {'名':<11} {'bpp':>4} " + " ".join(f"f{f:<2}" for f in range(16)) + "  最大")
result={}
for mi in range(NMODE):
    a=u24(mode_base[mi]+4*8); ln=seglen(a)
    allv=dec(a,ln-2); ph=len(allv)//256
    st=allv[:ph*256]
    per=[]
    for fr in range(16):
        seqs=set()
        for to in range(16):
            s=(fr<<4)|to
            vals=[]
            for bb in st[s*ph:(s+1)*ph]:
                vals.extend([(bb>>k)&3 for k in (0,2,4,6)])
            # 用"净黑脉冲数"表征终点 (粒子位置)
            nb=sum(1 for v in vals if v==1); nw=sum(1 for v in vals if v==2)
            seqs.add(nb-nw)
        per.append(len(seqs))
    result[mi]=per
    print(f"{mi:>4} {NAMES[mi]:<11} {str(L1BPP[mi]):>4} " + " ".join(f"{x:>3}" for x in per) + f"  {max(per):>4}")

print("\n=== 汇总: 实测可达灰阶 vs 规格 ===")
print(f"{'mode':>4} {'标准名':<12} {'bpp规格':>7} {'规格级数':>8} {'实测最大可区分':>14}")
lvl={'—':'?',1:2,2:4,4:16}
for mi in range(NMODE):
    b=L1BPP[mi]
    print(f"{mi:>4} {NAMES[mi]:<12} {str(b):>7} {str(lvl.get(b,'?')):>8} {max(result[mi]):>14}")
