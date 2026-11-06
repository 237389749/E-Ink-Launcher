"""einkimg —— Poke6 onyxconfig 诊断与修复工具（纯 Python 3 标准库）。

子命令：
  ls       <img> [path]                     列出 ext4 镜像目录/文件
  cat      <img> <path> [out]               导出文件
  mmkv     <file> [list|keys|get <key>]     解析 MMKV：记录数/有效/墓碑/单键值
  diff     <imgA> <imgB>                    逐文件（含 MMKV 逐 key）对比两份镜像
  ota      <img>                            （占位）从 system 分区找 OTA APK

典型用法（本项目实测流程）：

  1) 看两份 onyxconfig 到底差在哪（含每机专属参数）
     python tools/einkimg/einkimg.py diff poke6_9008/images/onyxconfig.img \
                                       poke6_9008_new/images/onyxconfig.img

  2) 解析出 MMKV，看重删的 key
     python tools/einkimg/einkimg.py cat poke6_9008_new/images/onyxconfig.img \
            mmkv/onyx_config out/cur_onyx_config
     python tools/einkimg/einkimg.py mmkv out/cur_onyx_config list

  3) 修复（详见 repair.py）
     python tools/einkimg/repair.py --base <当前机img> --donor <健康机img> \
            --out out/onyxconfig_fixed.img \
            --restore eac_theme_com.onyx@theme_type_3,eac_app_com.onyx,eac_default_app_configcom.onyx
"""
import os
import sys
import struct
import hashlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ext4fs import Ext4FS, SubFile, S_IFDIR, S_IFREG, S_IFLNK
import mmkv as mmkvlib


def _open(img):
    return Ext4FS(open(img, 'rb') if False else SubFile(img, 0))


def cmd_ls(args):
    img = args[0]
    path = args[1] if len(args) > 1 else ''
    fs = _open(img)
    print('ext4 %s  blk=%d inodes=%d desc=%d 64bit=%s vol=%r'
          % (img, fs.block_size, fs.inodes_count, fs.desc_size, fs.is_64bit, fs.volname))
    ino = fs.resolve(path) if path else 2
    if ino is None:
        print('NOT FOUND:', path)
        return 1
    for name, i2 in fs.listdir(ino):
        info = fs.inode_info(i2)
        k = info['mode'] & 0xF000
        t = {S_IFDIR: '[D]', S_IFREG: '   ', S_IFLNK: '[L]'}.get(k, ' ? ')
        print('  %s %-44s ino=%-6d %10d' % (t, name, i2, info['size']))
    return 0


def cmd_cat(args):
    img, path = args[0], args[1]
    out = args[2] if len(args) > 2 else None
    fs = _open(img)
    ino = fs.resolve(path)
    if ino is None:
        print('NOT FOUND:', path)
        return 1
    data = fs.read_file(ino)
    if out:
        open(out, 'wb').write(data)
        print('extracted %d B -> %s' % (len(data), out))
    else:
        sys.stdout.buffer.write(data)
    return 0


def cmd_mmkv(args):
    f = args[0]
    sub = args[1] if len(args) > 1 else 'list'
    buf, actual, recs, consumed = mmkvlib.walk(f)
    final = mmkvlib.final_state(recs)
    alive = {k: r for k, r in final.items() if r[1] > 0}
    tomb = {k: r for k, r in final.items() if r[1] == 0}

    if sub == 'list':
        print('file      = %s (%d B)' % (f, len(buf)))
        print('actual    = %d   未用尾部 = %d' % (actual, len(buf) - 4 - actual))
        print('records   = %d  (有效 key %d / 墓碑 %d)' % (len(recs), len(alive), len(tomb)))
        print()
        print('--- 有效 key ---')
        for k in sorted(alive):
            print('  %-58s %6d' % (k, alive[k][1]))
        if tomb:
            print()
            print('--- 墓碑 key（已删除，vlen=0）---')
            for k in sorted(tomb):
                print('  %s' % k)
    elif sub == 'keys':
        for k in sorted(alive):
            print(k)
    elif sub == 'deleted':
        for k in sorted(tomb):
            print(k)
    elif sub == 'get':
        if len(args) < 3:
            print('usage: mmkv <file> get <key>')
            return 2
        key = args[2]
        r = final.get(key)
        if r is None:
            print('MISSING (从未写入)')
        elif r[1] == 0:
            print('DELETED (墓碑, vlen=0)')
        else:
            v = r[2]
            payload = v
            if len(v) > 1 and v[0] < 0x80:
                ln = v[0]
                payload = v[1:] if ln == 0x1f else v[1:1 + ln]
            print('vlen=%d' % r[1])
            try:
                print(payload.decode('utf-8'))
            except UnicodeDecodeError:
                print(payload.hex(' '))
    else:
        print('unknown subcommand:', sub)
        return 2
    return 0


def _file_map(fs, ino=2, prefix=''):
    res = {}
    try:
        entries = fs.listdir(ino)
    except Exception:
        return res
    for name, i2 in entries:
        full = prefix + name
        info = fs.inode_info(i2)
        if (info['mode'] & 0xF000) == S_IFDIR:
            res.update(_file_map(fs, i2, full + '/'))
        else:
            try:
                data = fs.read_file(i2)
            except Exception:
                data = b''
            res[full] = (len(data), hashlib.md5(data).hexdigest(), data)
    return res


def cmd_diff(args):
    a, b = args[0], args[1]
    fa, fb = _open(a), _open(b)
    ma, mb = _file_map(fa), _file_map(fb)
    print('A = %s  (%d files)' % (a, len(ma)))
    print('B = %s  (%d files)' % (b, len(mb)))
    print()
    print('%-44s %-12s %-12s %s' % ('FILE', 'A(size)', 'B(size)', 'STATUS'))
    print('-' * 96)
    for n in sorted(set(ma) | set(mb)):
        x, y = ma.get(n), mb.get(n)
        if x and y:
            st = 'SAME' if x[1] == y[1] else 'DIFF'
        elif x:
            st = 'only-in-A'
        else:
            st = 'only-in-B'
        print('%-44s %-12s %-12s %s' % (n, x[0] if x else '-', y[0] if y else '-', st))

    # 若两边都有 mmkv/onyx_config，做 key 级 diff
    k = 'mmkv/onyx_config'
    if k in ma and k in mb:
        print()
        print('=== %s key 级对比 ===' % k)
        _, aa, ra, _ = mmkvlib.walk(ma[k][2])
        _, ab, rb, _ = mmkvlib.walk(mb[k][2])
        sa = mmkvlib.alive_keys(ra)
        sb = mmkvlib.alive_keys(rb)
        da = mmkvlib.deleted_keys(ra)
        db = mmkvlib.deleted_keys(rb)
        print('  A: 有效=%d 墓碑=%d   B: 有效=%d 墓碑=%d'
              % (len(sa), len(da), len(sb), len(db)))
        only_a = sorted(set(sa) - set(sb))
        only_b = sorted(set(sb) - set(sa))
        diffv = sorted(k2 for k2 in (set(sa) & set(sb)) if sa[k2][2] != sb[k2][2])
        print('  仅 A 有效: %d' % len(only_a))
        for x in only_a[:40]:
            print('    -', x)
        print('  仅 B 有效: %d' % len(only_b))
        for x in only_b[:40]:
            print('    +', x)
        print('  两边都有但值不同: %d' % len(diffv))
        for x in diffv[:40]:
            print('    ~', x)
        # B 中"墓碑且 A 中有效"= 被删掉的
        gone = sorted(set(sa) & set(db))
        print('  ★ 在 A 有效但 B 中已被删除: %d' % len(gone))
        for x in gone[:60]:
            print('    X', x)
        # pkg_set 内容（启动遍历依据）
        for tag, src in (('A', sa), ('B', sb)):
            r = src.get('eac_app_pkg_set')
            if r:
                v = r[2]
                payload = v[1:1 + v[0]] if len(v) > 1 and v[0] < 0x80 else v
                print('  %s eac_app_pkg_set (%d B): %r' % (tag, r[1], payload[:200]))
    return 0


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    cmd = sys.argv[1]
    rest = sys.argv[2:]
    table = {'ls': cmd_ls, 'cat': cmd_cat, 'mmkv': cmd_mmkv, 'diff': cmd_diff}
    fn = table.get(cmd)
    if not fn:
        print('unknown command:', cmd)
        print(__doc__)
        return 2
    try:
        return fn(rest)
    except IndexError:
        print('missing arguments for', cmd)
        return 2


if __name__ == '__main__':
    sys.exit(main())
