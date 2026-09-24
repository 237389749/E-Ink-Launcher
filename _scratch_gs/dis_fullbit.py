# -*- coding: utf-8 -*-
import sys
sys.stdout.reconfigure(encoding='utf-8')
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
P = r"C:\Users\root\Documents\eink\kernel_extracted.img"
d = open(P, 'rb').read()
md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN); md.skipdata = True; md.detail = True

print("=== 0x5503c0~0x550460 (第一处 orr #0x20) ===")
for ins in md.disasm(d[0x5503c0:0x550460], 0x5503c0):
    m = ''
    if ins.mnemonic == 'bl':
        try: m = f'   ; -> 0x{ins.operands[0].imm:x}'
        except Exception: m = '   ; call'
    if ins.mnemonic == 'orr' and '#0x20' in ins.op_str: m += '   <<<< FULL 位!'
    if 'cmp' in ins.mnemonic or ins.mnemonic in ('cbz','cbnz','tbz','tbnz','csel'): m += '   <== 判定'
    print(f"  {ins.address:08x}: {ins.mnemonic:8s} {ins.op_str}{m}")

print("\n=== 0x550540~0x5505c0 (第二处) ===")
for ins in md.disasm(d[0x550540:0x5505c0], 0x550540):
    m = ''
    if ins.mnemonic == 'orr' and '#0x20' in ins.op_str: m = '   <<<< FULL 位!'
    if 'cmp' in ins.mnemonic or ins.mnemonic in ('cbz','cbnz','tbz','tbnz','csel'): m += '   <== 判定'
    print(f"  {ins.address:08x}: {ins.mnemonic:8s} {ins.op_str}{m}")
