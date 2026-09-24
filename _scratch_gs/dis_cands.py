# -*- coding: utf-8 -*-
import sys
sys.stdout.reconfigure(encoding='utf-8')
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
P = r"C:\Users\root\Documents\eink\kernel_extracted.img"
data = open(P, 'rb').read()
md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
md.skipdata = True
md.detail = True

def show(lo, hi, title):
    print(f"\n{'='*95}\n{title}  0x{lo:x}~0x{hi:x}\n{'='*95}")
    for ins in md.disasm(data[lo:hi], lo):
        m = ''
        if ins.mnemonic == 'bl':
            try: m = f'   ; -> 0x{ins.operands[0].imm:x}'
            except Exception: m = '   ; call'
        if '#0x30]' in ins.op_str or '#0x2c]' in ins.op_str: m += '   <== DESC FIELD'
        if ins.mnemonic == 'ret': m += '   <<< RET'
        print(f"  {ins.address:08x}: {ins.mnemonic:8s} {ins.op_str}{m}")

show(0x533150, 0x5331e0, 'epdc 0x5331a4 (stp [x0,#0x30])')
show(0x532c80, 0x532d10, 'epdc 0x532cd4 (str [x0,#0x30])')
