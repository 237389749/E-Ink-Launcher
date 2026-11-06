"""在分区/整盘镜像里暴力扫描 ext4 文件系统（绕开 LP metadata / GPT）。

用途：EDL 回读的 super.img 有时 LP metadata 被混淆（geometry magic 后字段是随机值），
解不出分区表。此时直接扫 ext4 超级块最可靠。

判定条件（严格，避免随机数据误报）：
  · sb+56 处 2 字节 == 0xEF53
  · 0 < s_log_block_size <= 6
  · 0 < s_inodes_count < 5e7
  · 0 < s_blocks_count < 0x8000_0000
  · 512 <= s_blocks_per_group <= 1<<20
  · 16 <= s_inodes_per_group <= 1<<20
  · s_inode_size in {128,256,512,1024}
  · s_state in {0,1,2,3}

用法：
    python find_ext4.py <img> [--start 0] [--end N]
"""
import sys
import os
import struct

CHUNK = 16 * 1024 * 1024


def check_sb(buf, rel):
    if rel < 0 or rel + 1024 > len(buf):
        return None
    sb = buf[rel:rel + 1024]
    if sb[56:58] != b'\x53\xef':
        return None
    inodes = struct.unpack_from('<I', sb, 0)[0]
    blocks = struct.unpack_from('<I', sb, 4)[0]
    logbs = struct.unpack_from('<I', sb, 24)[0]
    bpg = struct.unpack_from('<I', sb, 32)[0]
    ipg = struct.unpack_from('<I', sb, 40)[0]
    state = struct.unpack_from('<H', sb, 58)[0]
    isz = struct.unpack_from('<H', sb, 88)[0]
    if not 0 < logbs <= 6:
        return None
    if not 0 < inodes < 50_000_000:
        return None
    if not 0 < blocks < 0x8000_0000:
        return None
    if not 512 <= bpg <= (1 << 20):
        return None
    if not 16 <= ipg <= (1 << 20):
        return None
    if isz not in (128, 256, 512, 1024):
        return None
    if state not in (0, 1, 2, 3):
        return None
    blk = 1024 << logbs
    return dict(blocks=blocks, blk=blk, inodes=inodes, isz=isz, state=state,
                vol=sb[120:136].split(b'\x00')[0].decode('utf-8', 'replace'),
                size=blocks * blk)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    img = sys.argv[1]
    start = 0
    end = os.path.getsize(img)
    args = sys.argv[2:]
    for i, a in enumerate(args):
        if a == '--start':
            start = int(args[i + 1], 0)
        elif a == '--end':
            end = int(args[i + 1], 0)

    f = open(img, 'rb')
    pos = start
    found = []
    while pos < end:
        f.seek(pos)
        data = f.read(min(CHUNK, end - pos))
        if not data:
            break
        for i in range(0, len(data) - 2048, 4096):
            info = check_sb(data, i + 1024)
            if info:
                found.append((pos + i, info))
        pos += len(data)
    f.close()

    # 去重
    seen = set()
    uniq = []
    for off, info in found:
        if off in seen:
            continue
        seen.add(off)
        uniq.append((off, info))

    print('%s  (%d B)' % (img, end))
    print('found %d ext4 filesystem(s):' % len(uniq))
    for off, i in uniq:
        print('  part_start=0x%09X (%9.1f MB)  fs=%9.1f MB  blk=%5d inodes=%8d isz=%d vol=%r'
              % (off, off / 1e6, i['size'] / 1e6, i['blk'], i['inodes'], i['isz'], i['vol']))
    return 0


if __name__ == '__main__':
    sys.exit(main())
