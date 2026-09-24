# -*- coding: utf-8 -*-
"""定位 kernel 里 dump_lut_list 的格式字符串 + lut/update 索引字段"""
import struct, re, sys
sys.stdout.reconfigure(encoding='utf-8')
P = r"C:\Users\root\Documents\eink\kernel_extracted.img"
d = open(P, 'rb').read()
print(f"kernel size = {len(d)}")

# 搜所有可能的格式串
pats = [b'lut[%d] waveform[%d]', b'lut_%d', b'lut[%d]', b'update[%d]',
        b'dump_lut_list', b'dump_pending_list', b'show_dump_list',
        b'wait all_lut_free', b'wait lut_free', b'all_lut_free']
for p in pats:
    idxs = []
    start = 0
    while True:
        i = d.find(p, start)
        if i < 0: break
        idxs.append(i)
        start = i + 1
    print(f"\n{p!r}: {len(idxs)} 处")
    for i in idxs[:6]:
        # 打印上下文
        s = max(0, i-60); e = min(len(d), i+80)
        ctx = d[s:e]
        printable = ''.join(chr(c) if 32 <= c < 127 else '.' for c in ctx)
        print(f"  0x{i:x}: {printable}")
