# -*- coding: utf-8 -*-
"""dump 波形模式映射表 (0x1f69000+0x240 区), 看 mode 号 → update 类型 的映射"""
import struct, sys
sys.stdout.reconfigure(encoding='utf-8')
P = r"C:\Users\root\AppData\Local\eink\kernel_extracted.img"
try:
    data = open(P, 'rb').read()
except FileNotFoundError:
    P = r"C:\Users\root\Documents\eink\kernel_extracted.img"
    data = open(P, 'rb').read()
print(f"kernel size = {len(data)} (0x{len(data):x})")

# 表虚拟地址 0x1f69000 + 0x240 = 0x1f69240
# 但映像只用 0x0 ~ 0x2130000 左右? 检查
TBL_VA = 0x1f69240
print(f"表 VA = 0x{TBL_VA:x}, 映像大小 0x{len(data):x} -> {'在范围内' if TBL_VA < len(data) else '★ 超出文件范围!'}")

# 若超出, 说明 VA != file_off, 需要算映射偏移
# 已知 dump_lut_list 串 file_off 0x1991522 被 adrp #0x1991000 引用 -> VA == file_off
# 故 TBL_VA 应等于 file_off, 但 0x1f69240 > 0x2130000? 实际 kernel 34MB = 0x212f000
# 0x1f69240 < 0x212f000 -> 在范围内
if TBL_VA < len(data):
    REC = 0x4C
    print(f"\n=== 温度段 0: 表 0x{TBL_VA:x}, 记录大小 0x{REC:x} ===")
    print("  mode 0-15 的 [offset+8] 值 (= get_waveform_mode_index 返回值):")
    for ti in range(4):
        base = TBL_VA + ti * REC
        vals = []
        for m in range(16):
            v = struct.unpack_from('<i', data, base + m*4 + 8)[0]
            vals.append(v)
        print(f"    温度段[{ti}] base=0x{base:x}: {vals}")
    # 整条记录
    print(f"\n=== 温度段 0 记录全 76 字节 ===")
    base = TBL_VA
    for o in range(0, REC, 4):
        v = struct.unpack_from('<i', data, base + o)[0]
        print(f"    +0x{o:02x}: {v:12d}  (0x{v & 0xffffffff:08x})")
