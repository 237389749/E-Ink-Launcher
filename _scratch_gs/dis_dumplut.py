# -*- coding: utf-8 -*-
"""反汇编 dump_lut_list (0x534400 附近) 找 lut 索引计算"""
import sys
sys.stdout.reconfigure(encoding='utf-8')
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
P = r"C:\Users\root\Documents\eink\kernel_extracted.img"
data = open(P, 'rb').read()
md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
md.skipdata = True

START, END = 0x534380, 0x534600
print(f"=== dump_lut_list region 0x{START:x}~0x{END:x} ===")
for ins in md.disasm(data[START:END], START):
    m = ins.mnemonic; op = ins.op_str
    mark = ""
    if m in ('bl',): mark = "   <== CALL"
    if m == 'adrp' or (m=='add' and 'x2' in op): mark = "   <== str ref"
    print(f"  {ins.address:08x}: {m:8s} {op}{mark}")
