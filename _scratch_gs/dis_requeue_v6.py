# -*- coding: utf-8 -*-
"""反汇编 reset 重排队代码 (0x542a80~0x542c00 与 0x543140~0x543240)
   这是已知写 LUT 描述符的地方, 看它如何设置 update 字段 (+0x30)"""
import sys
sys.stdout.reconfigure(encoding='utf-8')
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
P = r"C:\Users\root\Documents\eink\kernel_extracted.img"
data = open(P, 'rb').read()
md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
md.skipdata = True
md.detail = True

for LO, HI, name in [(0x542a40, 0x542c40, 'requeue-A (0x542B0C)'), (0x543100, 0x543280, 'requeue-B (0x5431AC)')]:
    print(f"\n{'='*95}\n{name}  0x{LO:x}~0x{HI:x}\n{'='*95}")
    for ins in md.disasm(data[LO:HI], LO):
        mark = ''
        if ins.address in (0x542b0c, 0x5431ac): mark = '   <<<<<< PATCH POINT (movz w0,#4=A2)'
        if ins.mnemonic == 'bl':
            mark += f'   ; call 0x{ins.operands[0].imm:x}'
        if '#0x30]' in ins.op_str or '#0x2c]' in ins.op_str: mark += '   <== desc field!'
        print(f"  {ins.address:08x}: {ins.mnemonic:8s} {ins.op_str}{mark}")
