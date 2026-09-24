# -*- coding: utf-8 -*-
"""追 update 字段源头: 找写 [xN, #0x80] 的地方 (上游结构体)"""
import sys
sys.stdout.reconfigure(encoding='utf-8')
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
P = r"C:\Users\root\Documents\eink\kernel_extracted.img"
data = open(P, 'rb').read()
md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
md.skipdata = True
md.detail = True

print("=== epdc 区: 写 [reg, #0x80] 的指令 ===")
n = 0
for ins in md.disasm(data[0x530000:0x560000], 0x530000):
    o = ins.op_str
    if ins.mnemonic in ('str','strb','stp','stur') and '#0x80]' in o and 'sp' not in o:
        print(f"  0x{ins.address:x}: {ins.mnemonic:6s} {o}")
        n += 1
print(f"共 {n}")

# 找 ioctl 处理 SET_EBC_SEND_UPDATE 的地方 (它有 flags)
print("\n=== 找 SET_EBC_SEND_UPDATE 字符串引用 ===")
s = data.find(b'SET_EBC_SEND_UPDATE')
print(f"  串 file_off = 0x{s:x}")
# adrp+add 扫描
import struct
def adrp_target(w0, pc):
    immhi = (w0 >> 5) & 0x7FFFF; immlo = (w0 >> 29) & 3
    imm = (immhi << 2) | immlo
    if imm & (1 << 20): imm -= (1 << 21)
    return (pc & ~0xFFF) + (imm << 12)
tpage = s & ~0xFFF
for off in range(0, len(data)-12, 4):
    w0 = struct.unpack_from('<I', data, off)[0]
    if (w0 & 0x9F000000) != 0x90000000: continue
    if adrp_target(w0, off) != tpage: continue
    rd = w0 & 0x1F
    for k in (1,2,3):
        w1 = struct.unpack_from('<I', data, off+4*k)[0]
        if (w1 & 0xFF800000) != 0x91000000: continue
        rn = (w1>>5)&0x1F; imm12=(w1>>10)&0xFFF; sh=(w1>>22)&1
        if sh: imm12 <<= 12
        if rn == rd and tpage + imm12 == s:
            print(f"  引用 @ 0x{off:x}")
