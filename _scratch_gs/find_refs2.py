# -*- coding: utf-8 -*-
"""正确定位 adrp+add 引用: 找打印 update[%d] 的代码 (决定 update[0]/[1] 的地方)"""
import struct, sys
sys.stdout.reconfigure(encoding='utf-8')

P = r"C:\Users\root\Documents\eink\kernel_extracted.img"
data = open(P, 'rb').read()

STRINGS = {
    'dump_lut_fmt': b'%s(): magic[%d] lut[%d] waveform[%d] update[%d] frame_cur[%d] frame_total[%d]!',
    'add_lut_fmt' : b'add lut to lut_list! wb_update_cnt[%d]',
    'oneframe_fmt': b'error! lut[%d] waveform[%d] update[%d] frame_cur[%d] frame_total[%d]. will clean this lut',
    'funcname_dumplut': b'dump_lut_list\x00',
    'funcname_oneframe': b'onyx_get_waveform_one_frame_segment_16bit\x00',
}

def find_str(pat):
    i = data.find(pat)
    return i  # -1 if not found

VA = {}
for k, p in STRINGS.items():
    off = find_str(p)
    VA[k] = off
    print(f"{k:22s} file_off=0x{off:x}" if off >= 0 else f"{k:22s} NOT FOUND")

def adrp_target(w0, pc):
    """正确的 adrp 目标: pc_page + SignExtend(immhi:immlo:000000000000, 33)"""
    immhi = (w0 >> 5) & 0x7FFFF
    immlo = (w0 >> 29) & 0x3
    imm = (immhi << 2) | immlo
    if imm & (1 << 20):
        imm -= (1 << 21)
    return (pc & ~0xFFF) + (imm << 12)

def scan_refs(target_va):
    """扫全映像, 找 adrp+add xD, xN, #imm 指向 target_va"""
    hits = []
    tpage = target_va & ~0xFFF
    for off in range(0, len(data) - 12, 4):
        w0 = struct.unpack_from('<I', data, off)[0]
        if (w0 & 0x9F000000) != 0x90000000:
            continue
        if adrp_target(w0, off) != tpage:
            continue
        rd = w0 & 0x1F
        for k in (1, 2):          # add 紧随其后
            w1 = struct.unpack_from('<I', data, off + 4*k)[0]
            if (w1 & 0xFF800000) != 0x91000000:
                continue
            rn = (w1 >> 5) & 0x1F
            rd2 = w1 & 0x1F
            imm12 = (w1 >> 10) & 0xFFF
            sh = (w1 >> 22) & 1
            if sh: imm12 <<= 12
            if rn == rd and tpage + imm12 == target_va:
                hits.append((off, k, rd2))
    return hits

for k in ('dump_lut_fmt', 'add_lut_fmt', 'oneframe_fmt', 'funcname_oneframe'):
    off = VA.get(k, -1)
    if off < 0: continue
    h = scan_refs(off)
    print(f"\n=== {k} (va 0x{off:x}) 引用 {len(h)} 处 ===")
    for o, kk, rd in h:
        print(f"  code @ file_off 0x{o:x} (add +{kk*4}, x{rd})")
