# -*- coding: utf-8 -*-
"""按"驱动 subframe 数 / 总 subframe 数"重算, 验证 ref 定义"""
import sys
sys.stdout.reconfigure(encoding='utf-8')
P = r"C:\Users\root\Documents\eink\eink_waveform.wbf"
d = open(P, 'rb').read()
def u24(o): return d[o] | (d[o+1]<<8) | (d[o+2]<<16)
NMODE = d[0x25]+1; NTEMP = d[0x26]+1
XWIA = u24(0x1c); MT = XWIA + 1 + d[XWIA] + 1
mode_base = [u24(MT + 4*i) for i in range(NMODE)]
ptrs = set()
for mi in range(NMODE):
    for ti in range(NTEMP):
        v = u24(mode_base[mi]+4*ti)
        if v>0: ptrs.add(v)
flat = sorted(ptrs | {len(d)})
def seglen(a):
    for j,v in enumerate(flat):
        if v==a: return flat[j+1]-a
    return 0
def dec(a, mb):
    out=[]; i=0; fc=False
    while i < mb:
        b=d[a+i]
        if b==0xfc: fc=not fc; i+=1; continue
        c = 1 if fc else (d[a+i+1]+1)
        i += 1 if fc else 2
        out.extend([b]*c)
    return out

SAME = [(x<<4)|x for x in range(16)]
print("=== 定义B: 驱动 subframe 数 / 总 subframe 数 ===")
print(f"{'mode':>4} {'ph':>4} {'覆盖':>6} {'驱动率':>8} {'同色率':>8}")
res={}
for mi in range(NMODE):
    a=u24(mode_base[mi]+4*8); ln=seglen(a)
    allv=dec(a, ln-2); ph=len(allv)//256
    st=allv[:ph*256]
    tot=drvTot=cov=0; sTot=sDrv=0
    for s in range(256):
        vals=[]
        for bb in st[s*ph:(s+1)*ph]:
            vals.extend([(bb>>k)&3 for k in (0,2,4,6)])
        nDrv=sum(1 for v in vals if v in (1,2))
        tot += len(vals); drvTot += nDrv
        if nDrv: cov += 1
        if s in SAME:
            sTot += len(vals); sDrv += nDrv
    res[mi]=(ph,cov,drvTot/tot,sDrv/sTot)
    print(f"{mi:>4} {ph:>4} {cov:>4}/256 {drvTot/tot:>8.3f} {sDrv/sTot:>8.3f}")

print("\n=== 对照 ref (帧数/覆盖/驱动率/同色率) ===")
ref = {0:(113,193,0.743,0.750),1:(22,42,0.026,0.023),2:(39,243,0.219,0.229),
       3:(39,244,0.221,0.240),4:(39,244,0.221,0.240),5:(39,244,0.221,0.240),
       6:(10,18,0.004,0.009),7:(24,84,0.056,0.062)}
print(f"{'mode':>4} {'ref帧':>5}{'我':>4} {'ref覆盖':>7}{'我':>6} {'ref驱动':>7}{'我':>7} {'ref同色':>7}{'我':>7}  一致?")
for mi in range(NMODE):
    ph,cov,dr,sd = res[mi]
    rph,rcov,rdr,rsd = ref[mi]
    ok = "✓" if (abs(dr-rdr)<0.02 and abs(sd-rsd)<0.02 and cov==rcov) else "✗"
    print(f"{mi:>4} {rph:>5}{ph:>4} {rcov:>4}/256{cov:>4}/256 {rdr:>7.3f}{dr:>7.3f} {rsd:>7.3f}{sd:>7.3f}  {ok}")
