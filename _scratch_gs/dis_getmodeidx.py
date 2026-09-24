# -*- coding: utf-8 -*-
"""反汇编 get_waveform_mode_index (0x550ab0) —— 决定 update[0]/[1] 的核心"""
import struct, sys
sys.stdout.reconfigure(encoding='utf-8')
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
P = r"C:\Users\root\Documents\eink\kernel_extracted.img"
data = open(P, 'rb').read()
md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
md.skipdata = True
md.detail = True

LO, HI = 0x550ab0, 0x550c80
print(f"=== get_waveform_mode_index 0x550ab0 ===")
for ins in md.disasm(data[LO:HI], LO):
    mark = ''
    if ins.mnemonic == 'bl':
        try: mark = f'   ; -> 0x{ins.operands[0].imm:x}'
        except Exception: mark = '   ; call'
    if ins.mnemonic in ('ret',): mark += '   <<< RET'
    if 'cmp' in ins.mnemonic or 'tbz' in ins.mnemonic or 'tbnz' in ins.mnemonic or 'csel' in ins.mnemonic:
        mark += '   <== 分支判定'
    print(f"  {ins.address:08x}: {ins.mnemonic:8s} {ins.op_str}{mark}")
