"""repair.py —— 生成修复后的 onyxconfig 分区镜像（Poke6 卡启动自救）。

设计原则（本项目踩坑总结，务必遵守）：
  1. **以本机镜像为底**，绝不拿别的机器的分区镜像整盘覆盖。
     onyxconfig 里至少有 5 处每机专属数据：
        DEVICE_SERIAL_NUMBER_KEY / mac_address / test_result_info /
        ONYX_SYSTEM_CONFIG_KEY / com.android.vcom(VCOM 电压校准)
     整盘覆盖会改掉序列号、蓝牙 WiFi MAC、产测记录、面板校准值。
  2. 只改 mmkv/onyx_config 与 mmkv/onyx_config.crc 两个文件，其余一律不动。
  3. 修复方式是 **append 新记录**（MMKV 原生语义），不重写、不删墓碑。
  4. crc 必须同步重算，否则 MMKV 判定校验失败。

用法：
    python repair.py --base <本机onyxconfig.img> --donor <健康机onyxconfig.img> \
        --out out/onyxconfig_fixed.img \
        --restore key1,key2,key3 [--dry-run]

  --restore 的每个 key 会从 donor 取"最终有效值"，append 到 base 的 MMKV 末尾。
  --dry-run 只打印计划，不写镜像。
"""
import os
import sys
import struct
import argparse
import hashlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ext4fs import Ext4FS, SubFile, S_IFDIR
import mmkv as mmkvlib


def read_all(fs, ino=2, prefix=''):
    res = {}
    try:
        entries = fs.listdir(ino)
    except Exception:
        return res
    for name, i2 in entries:
        full = prefix + name
        info = fs.inode_info(i2)
        if (info['mode'] & 0xF000) == S_IFDIR:
            res.update(read_all(fs, i2, full + '/'))
        else:
            try:
                data = fs.read_file(i2)
            except Exception:
                data = b''
            res[full] = (info['size'], hashlib.md5(data).hexdigest(), data)
    return res


def write_file_inplace(fs, path, data, fh):
    ino = fs.resolve(path)
    if ino is None:
        raise RuntimeError('镜像里找不到 ' + path)
    info = fs.inode_info(ino)
    blocks = fs.file_blocks(ino)
    cap = len(blocks) * fs.block_size
    if len(data) > cap:
        raise RuntimeError('%s: 新数据 %d 超过容量 %d' % (path, len(data), cap))
    buf = data + b'\x00' * (cap - len(data))
    for k, blk in enumerate(blocks):
        fh.seek(blk * fs.block_size)
        fh.write(buf[k * fs.block_size:(k + 1) * fs.block_size])
    # 更新 inode size（lo/hi）
    g = (ino - 1) // fs.inodes_per_group
    i = (ino - 1) % fs.inodes_per_group
    fh.seek(fs.gdt_off + g * fs.desc_size)
    d = fh.read(fs.desc_size)
    itab = struct.unpack_from('<I', d, 8)[0]
    inode_off = itab * fs.block_size + i * fs.inode_size
    fh.seek(inode_off + 4)
    fh.write(struct.pack('<I', len(data) & 0xFFFFFFFF))
    fh.seek(inode_off + 108)
    fh.write(struct.pack('<I', (len(data) >> 32) & 0xFFFFFFFF))
    return len(blocks), cap


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', required=True, help='本机 onyxconfig 分区镜像')
    ap.add_argument('--donor', required=True, help='健康机 onyxconfig 分区镜像（只取 key 值）')
    ap.add_argument('--out', required=True)
    ap.add_argument('--restore', default='', help='逗号分隔的 key 列表')
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()

    keys = [k.strip() for k in args.restore.split(',') if k.strip()]
    if not keys:
        print('!! --restore 为空，没有要做的事')
        return 2

    print('[1] 打开 donor（只读取值）')
    fd = Ext4FS(SubFile(args.donor, 0))
    md = read_all(fd)
    k_mmkv = 'mmkv/onyx_config'
    if k_mmkv not in md:
        print('!! donor 缺少', k_mmkv)
        return 1
    _, _, drecs, _ = mmkvlib.walk(md[k_mmkv][2])
    dflat = mmkvlib.alive_keys(drecs)
    plan = []
    for k in keys:
        r = dflat.get(k)
        if r is None:
            print('   !! donor 中没有有效值: %s' % k)
            return 1
        plan.append((k, r[2]))
        print('   %-50s vlen=%d' % (k, r[1]))

    print('\n[2] 打开 base（本机镜像，全部身份数据在此）')
    fb = Ext4FS(SubFile(args.base, 0))
    mb = read_all(fb)
    if k_mmkv not in mb:
        print('!! base 缺少', k_mmkv)
        return 1
    cur_mmkv = mb[k_mmkv][2]
    cur_crc = mb['mmkv/onyx_config.crc'][2]
    _, cur_actual, cur_recs, _ = mmkvlib.walk(cur_mmkv)
    cur_final = mmkvlib.final_state(cur_recs)
    print('   MMKV size=%d actual=%d records=%d'
          % (len(cur_mmkv), cur_actual, len(cur_recs)))
    for k, _ in plan:
        r = cur_final.get(k)
        st = 'MISSING' if r is None else ('TOMBSTONE(vlen=0)' if r[1] == 0 else 'ALIVE vlen=%d' % r[1])
        print('   当前 %-46s %s' % (k, st))

    print('\n[3] 追加恢复记录')
    new_mmkv, new_actual = mmkvlib.append_records(cur_mmkv, plan)
    print('   actual %d -> %d  (+%d)' % (cur_actual, new_actual, new_actual - cur_actual))
    new_crc = mmkvlib.fix_crc(cur_crc, new_mmkv)
    ok, want, got = mmkvlib.verify_crc(new_crc, new_mmkv)
    print('   crc  重算: 0x%08X  (自检 %s)' % (got, 'OK' if ok else 'FAIL'))
    if not ok:
        return 1

    # 回读校验
    _, _, nrecs, _ = mmkvlib.walk(new_mmkv)
    nfinal = mmkvlib.final_state(nrecs)
    for k, vb in plan:
        r = nfinal.get(k)
        good = r is not None and r[1] == len(vb) and r[2] == vb
        print('   校验 %-46s %s' % (k, 'OK' if good else '!! FAIL'))
        if not good:
            return 1

    if args.dry_run:
        print('\n[dry-run] 不写镜像')
        return 0

    print('\n[4] 写镜像')
    import shutil
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    shutil.copyfile(args.base, args.out)
    fh = open(args.out, 'r+b')
    try:
        n, cap = write_file_inplace(fb, 'mmkv/onyx_config', new_mmkv, fh)
        print('   mmkv/onyx_config     %d blocks  cap=%d' % (n, cap))
        n, cap = write_file_inplace(fb, 'mmkv/onyx_config.crc', new_crc, fh)
        print('   mmkv/onyx_config.crc %d blocks  cap=%d' % (n, cap))
    finally:
        fh.close()

    print('\n[5] 复核：除这 2 个文件外必须全部一致')
    fo = Ext4FS(SubFile(args.out, 0))
    mo = read_all(fo)
    changed = [n for n in sorted(set(mb) | set(mo))
               if not (n in mb and n in mo and mb[n][1] == mo[n][1])]
    print('   变化文件: %d 个' % len(changed))
    for n in changed:
        print('     ', n)
    unexpected = [n for n in changed if n not in ('mmkv/onyx_config', 'mmkv/onyx_config.crc')]
    if unexpected:
        print('   !! 出现了意外的变更:', unexpected)
        return 1

    print('\n完成: %s  %d B  md5=%s'
          % (args.out, os.path.getsize(args.out),
             hashlib.md5(open(args.out, 'rb').read()).hexdigest()))
    print('\n刷写（EDL，只写这一个分区）:')
    print('  edl --loader=<QCM6125 loader> --memory=ufs w onyxconfig %s' % args.out)
    print('  并建议同时清 misc: edl ... w misc out/misc_zero_1mb.img')
    return 0


if __name__ == '__main__':
    sys.exit(main())
