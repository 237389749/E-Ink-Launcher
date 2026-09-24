# -*- coding: utf-8 -*-
"""定位并反汇编 dump_lut_list 函数，找出 lut[N] / update[N] 字段来源"""
import struct, sys
sys.stdout.reconfigure(encoding='utf-8')
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from capstone.arm64 import ARM64_OP_IMM, ARM64_OP_REG, ARM64_OP_MEM

P = r"C:\Users\root\Documents\eink\kernel_extracted.img"
data = open(P, 'rb').read()
md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
md.detail = True

# 格式串 "lut[%d] waveform[%d] update[%d] frame_cur[%d] frame_total[%d]" 在 0x1991532 附近
# 计算其虚拟地址: 文件偏移 0x1991532 (内核未压缩, 通常 file_off == vaddr 对 -p 0x... 但需确认)
# 先找引用该串的 adrp/add 对。
STR_OFF = 0x1991532 - 12   # 串中 "magic[%d] " 起点附近; 精确定位
# 实际串起点
s = data.find(b'%s(): magic[%d] lut[%d] waveform[%d] update[%d] frame_cur[%d] frame_total[%d]!', 0)
print(f"格式串起点 file_off = 0x{s:x}")

# 内核基址: 已知 0x199501D 是 wait 串 → 之前 ref 记录 0x199501D 为真实起点
# 说明 file_off == vaddr (线性映射)
STR_VA = s
print(f"假设 vaddr = 0x{STR_VA:x}")

def find_adrp_refs(va, span=0x600000):
    """扫整个映像找 adrp+add 组合指向 va"""
    hits = []
    for off in range(0, len(data)-8, 4):
        w0 = struct.unpack_from('<I', data, off)[0]
        # adrp: 0x90000000 mask 0x9F000000
        if (w0 & 0x9F000000) != 0x90000000: continue
        immhi = (w0 >> 5) & 0x7FFFF
        immlo = (w0 >> 29) & 0x3
        imm = (immhi << 2) | immlo
        if imm & (1 << 20): imm -= (1 << 21)
        rd = w0 & 0x1F
        page_va = va & ~0xFFF
        base_over = page_va + (imm << 12)
        # 下一/下二条 add
        for k in (1, 2):
            if off + 4*k + 4 > len(data): continue
            w1 = struct.unpack_from('<I', data, off+4*k)[0]
            if (w1 & 0xFF800000) == 0x91000000:  # add xd, xn, #imm12
                imm12 = (w1 >> 10) & 0xFFF
                sh = (w1 >> 22) & 1
                if sh: imm12 <<= 12
                rn = (w1 >> 5) & 0x1F
                rd2 = w1 & 0x1F
                if rn == rd and base_over + imm12 == va:
                    hits.append((off, k, rd2))
    return hits

hits = find_adrp_refs(STR_VA)
print(f"\n引用该格式串的位置: {len(hits)}")
for off, k, rd in hits:
    print(f"  file_off 0x{off:x} (add at +{k*4})")
