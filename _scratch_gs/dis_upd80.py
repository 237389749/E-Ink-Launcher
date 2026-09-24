# -*- coding: utf-8 -*-
import sys
sys.stdout.reconfigure(encoding='utf-8')
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
P = r"C:\Users\root\Documents\eink\kernel_extracted.img"
data = open(P, 'rb').read()
md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
md.skipdata = True
md.detail = True

print("=== 0x532d58~0x532e10 (update 生产处) ===")
for ins in md.disasm(data[0x532d58:0x532e10], 0x532d58):
    m = ''
    if ins.mnemonic == 'bl':
        try: m = f'   ; -> 0x{ins.operands[0].imm:x}'
        except Exception: m = '   ; call'
    if '#0x80]' in ins.op_str: m += '   <== x80 (update src)'
    if '#0x30]' in ins.op_str: m += '   <== desc update'
    print(f"  {ins.address:08x}: {ins.mnemonic:8s} {ins.op_str}{m}")

print("\n=== 0x532500~0x532b60 概览: 写 [x19,#0x80] 的上下文 ===")
prev = []
for ins in md.disasm(data[0x532500:0x532b60], 0x532500):
    if '#0x80]' in ins.op_str:
        print(f"  --- 0x{ins.address:x}: {ins.mnemonic} {ins.op_str} ---")
        for p in prev[-12:]:
            print(f"      {p.address:08x}: {p.mnemonic:8s} {p.op_str}")
    prev.append(ins)
