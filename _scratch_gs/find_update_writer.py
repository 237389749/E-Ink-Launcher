# -*- coding: utf-8 -*-
"""找 LUT 描述符 update 字段 (+0x30) 的写入者"""
import struct, sys
sys.stdout.reconfigure(encoding='utf-8')
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
P = r"C:\Users\root\Documents\eink\kernel_extracted.img"
data = open(P, 'rb').read()
md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
md.skipdata = True

print("=== 搜索写 [xN, #0x30] / [xN, #0x2c] 的 str/stp 指令 ===")
hits = []
for ins in md.disasm(data, 0):
    if ins.mnemonic in ('str', 'strb', 'stp') and ins.op_str.startswith('w'):
        if '#0x30]' in ins.op_str or '#0x2c]' in ins.op_str:
            hits.append((ins.address, ins.mnemonic, ins.op_str))
print(f"共 {len(hits)} 处 (前 40)")
for a, m, o in hits[:40]:
    print(f"  0x{a:x}: {m} {o}")

# 也搜 stp w,w,[xN,#0x2c]
print("\n=== 搜索 stp (双字写, 覆盖 0x2c+0x30) ===")
for ins in md.disasm(data, 0):
    if ins.mnemonic == 'stp' and '#0x2c]' in ins.op_str and ins.op_str.startswith('w'):
        print(f"  0x{ins.address:x}: {ins.mnemonic} {ins.op_str}")
