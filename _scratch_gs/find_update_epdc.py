# -*- coding: utf-8 -*-
"""聚焦: 在 epdc 区域找 LUT 描述符 update(+0x30)/waveform(+0x2c) 写入"""
import struct, sys
sys.stdout.reconfigure(encoding='utf-8')
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
P = r"C:\Users\root\Documents\eink\kernel_extracted.img"
data = open(P, 'rb').read()
md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
md.skipdata = True

# epdc 相关函数集中区
for LO, HI in [(0x530000, 0x536000), (0x536000, 0x544000)]:
    print(f"\n{'='*90}\n区域 0x{LO:x}~0x{HI:x}\n{'='*90}")
    insns = list(md.disasm(data[LO:HI], LO))
    for idx, ins in enumerate(insns):
        o = ins.op_str
        if ins.mnemonic in ('str','strb','stp') and ('#0x2c]' in o or '#0x30]' in o):
            print(f"\n--- 0x{ins.address:x}: {ins.mnemonic} {o} ---")
            # 前 18 条上下文
            for j in range(max(0, idx-18), idx+1):
                pre = "  >" if j == idx else "   "
                print(f"{pre} 0x{insns[j].address:x}: {insns[j].mnemonic:8s} {insns[j].op_str}")
