#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""EAC 残留审计 —— 判定「EInk Center 切刷新档会不会点了没反应」。

背景（HANDOFF §14.23/§14.24，2026-11-07 实机闭环）：
  面板点「净清/标准/极速」走 SystemUI 的 SelectRefreshModeAction。该类的
  `isRefreshModeIndexChanged()` 在 `loadRealRefreshModeIndex()` 之前执行，
  比较的是「当前 theme 的 idx」vs `RefreshModeIndex.toEnum(null)` = NONE。
  ⇒ 当前 idx 只要是 NONE，filter 恒 false，整个动作被跳过：不写盘、不 apply。
  这个 NONE 是 E-Ink-Launcher 历史 `reset/set` 写进 onyxconfig 的残留。

本脚本只读，用来在改机前后做同一判据：
  1) MMKV 健康度（容量/墓碑/crc）
  2) 每个已安装 app 的 active themeType + 该 type 的 idx → 可切 / 被 NONE 拦截
  3) 「优化引擎」总开关 eac_extra_config_enable（0 = app 侧不读 per-app 配置，切了也不生效）
  4) 与健康机 donor 快照对比（可选）

用法：
  python tools/refresh/eac_audit.py --pull                      # 自动从 adb 拉（需 root）
  python tools/refresh/eac_audit.py --pull --serial 6C7F0E64
  python tools/refresh/eac_audit.py --mmkv x.img --crc x.crc --installed pm.txt
  python tools/refresh/eac_audit.py --mmkv x.img --donor .diag/ORIG_mmkv_onyx_config

退出码：0 = 全部可切；1 = 存在被 NONE 拦截的已安装 app；2 = 引擎总开关为 0；3 = 读取失败
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'einkimg'))
import mmkv  # noqa: E402

P_IDX = re.compile(r'"refreshModeIndex"\s*:\s*"([^"]*)"')
P_UM = re.compile(r'"updateMode"\s*:\s*(-?\d+)')
P_TT = re.compile(r'"themeType"\s*:\s*(\d+)')
ACTIVE_PREFIX = 'eac_active_theme_'


def walk(path):
    buf, actual, recs, _ = mmkv.walk(path)
    return buf, actual, recs, mmkv.alive_keys(recs)


def value_str(rec):
    return rec[2].decode('utf-8', 'replace')


def rc_of(rec):
    """返回 (refreshModeIndex, updateMode)。"""
    s = value_str(rec)
    mi, mu = P_IDX.search(s), P_UM.search(s)
    return (mi.group(1) if mi else '?', mu.group(1) if mu else '?')


def pull(serial, adb_path='adb'):
    adb = [adb_path]
    if serial:
        adb += ['-s', serial]
    tmp = tempfile.mkdtemp(prefix='eac_audit_')
    mm = os.path.join(tmp, 'onyx_config')
    crc = os.path.join(tmp, 'onyx_config.crc')
    pm = os.path.join(tmp, 'pm.txt')
    sh = ("su -c 'cp /onyxconfig/mmkv/onyx_config /data/local/tmp/onyx_config_audit;"
          " cp /onyxconfig/mmkv/onyx_config.crc /data/local/tmp/onyx_config_audit.crc;"
          " chmod 644 /data/local/tmp/onyx_config_audit /data/local/tmp/onyx_config_audit.crc'")
    subprocess.run(adb + ['shell', sh], check=False, stdout=subprocess.DEVNULL)
    subprocess.run(adb + ['pull', '/data/local/tmp/onyx_config_audit', mm],
                   check=True, stdout=subprocess.DEVNULL)
    subprocess.run(adb + ['pull', '/data/local/tmp/onyx_config_audit.crc', crc],
                   check=True, stdout=subprocess.DEVNULL)
    with open(pm, 'w', encoding='utf-8') as f:
        out = subprocess.run(adb + ['shell', 'pm', 'list', 'packages'],
                             check=True, capture_output=True)
        for line in out.stdout.decode('utf-8', 'replace').splitlines():
            line = line.strip()
            if line.startswith('package:'):
                f.write(line[len('package:'):] + '\n')
    return mm, crc, pm


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--pull', action='store_true', help='从 adb 拉取（需 root）')
    ap.add_argument('--adb', default=os.environ.get('ADB', 'adb'),
                    help='adb 可执行文件路径（默认取环境变量 ADB 或 PATH 里的 adb）')
    ap.add_argument('--serial', default='', help='adb 序列号')
    ap.add_argument('--mmkv', default='', help='onyx_config 文件路径')
    ap.add_argument('--crc', default='', help='onyx_config.crc 路径')
    ap.add_argument('--installed', default='', help='pm list packages 结果（每行一个包名）')
    ap.add_argument('--donor', default='', help='健康机 onyx_config（可选，做对照）')
    args = ap.parse_args()

    if args.pull:
        mmkv_path, crc_path, pm_path = pull(args.serial, args.adb)
    else:
        if not args.mmkv:
            ap.error('需要 --pull 或 --mmkv')
        mmkv_path, crc_path, pm_path = args.mmkv, args.crc, args.installed

    try:
        buf, actual, recs, alive = walk(mmkv_path)
    except Exception as e:  # noqa: BLE001
        print('读取失败:', e)
        return 3

    print('=' * 72)
    print('EAC 残留审计  mmkv=%s' % mmkv_path)
    print('=' * 72)

    # ---------- 1. MMKV 健康度 ----------
    print('\n[1] MMKV 健康度')
    print('    file      = %d' % len(buf))
    print('    actual    = %d   剩余 = %d' % (actual, len(buf) - 4 - actual))
    print('    记录数    = %d   alive = %d   墓碑 = %d'
          % (len(recs), len(alive), len([1 for k, v, _ in recs if v == 0])))
    if crc_path and os.path.exists(crc_path):
        crc = open(crc_path, 'rb').read()
        ok, want, got = mmkv.verify_crc(crc, buf)
        print('    crc       = %s' % ('OK' if ok else 'BAD (want=%08x got=%08x)' % (want, got)))

    # ---------- 2. 引擎开关 ----------
    print('\n[2] 优化引擎总开关（eac_extra_config_enable）')
    eng = alive.get('eac_extra_config_enable')
    eng_on = eng is not None and eng[2][:1] != b'\x00'
    print('    eac_extra_config_enable = %s  (%s)'
          % (eng[2].hex() if eng else '缺失', 'ON' if eng_on else 'OFF'))
    gc = alive.get('eac_extra_gc_after_scrolling')
    print('    eac_extra_gc_after_scrolling = %s'
          % (gc[2].hex() if gc else '缺失'))

    # ---------- 3. theme idx 分布 ----------
    print('\n[3] eac_theme_* 的 refreshModeIndex 分布')
    dist = {}
    none_keys = []
    for k, r in alive.items():
        if not k.startswith('eac_theme_'):
            continue
        idx, _um = rc_of(r)
        dist[idx] = dist.get(idx, 0) + 1
        if idx == 'NONE':
            none_keys.append(k)
    for kk in sorted(dist, key=lambda x: -dist[x]):
        print('    %-16s %d' % (kk, dist[kk]))
    print('    NONE 键合计 = %d' % len(none_keys))

    # ---------- 4. 已安装 app 判定 ----------
    installed = []
    if pm_path and os.path.exists(pm_path):
        for line in open(pm_path, encoding='utf-8'):
            p = line.strip()
            if not p or p.startswith('#'):
                continue
            if p.startswith('package:'):
                p = p[len('package:'):].strip()
            installed.append(p)

    print('\n[4] 已安装 app 判定（active themeType 的 idx == NONE ⇒ 面板点了没反应）')
    if not installed:
        print('    (未提供包列表，跳过)')
    blocked = []
    configured = 0
    unconfigured = 0
    for pkg in sorted(installed):
        act = alive.get(ACTIVE_PREFIX + pkg)
        active_type = int.from_bytes(act[2][:1], 'little') if act else 3
        theme_key = 'eac_theme_%s@theme_type_%d' % (pkg, active_type)
        th = alive.get(theme_key)
        app = alive.get('eac_app_' + pkg)
        has_cfg = th is not None or app is not None
        if th is None:
            unconfigured += 1
            if has_cfg:
                print('    %-45s active=%d  theme 未落盘  -> 可切（默认）' % (pkg, active_type))
            continue
        configured += 1
        idx, um = rc_of(th)
        if idx == 'NONE':
            app_idx = rc_of(app)[0] if app else '-'
            blocked.append(pkg)
            print('    %-45s active=%d  idx=NONE um=%s  eac_app=%s  -> **BLOCKED**'
                  % (pkg, active_type, um, app_idx))
        else:
            print('    %-45s active=%d  idx=%s um=%s  -> 可切' % (pkg, active_type, idx, um))
    print('    小计：已配置 %d 个，未配置(继承默认) %d 个' % (configured, unconfigured))

    # ---------- 5. 与 donor 对照 ----------
    if args.donor:
        print('\n[5] 与健康机 donor 对照：%s' % args.donor)
        _b, _a, _r, dalive = walk(args.donor)
        d_eng = dalive.get('eac_extra_config_enable')
        d_dist = {}
        for k, r in dalive.items():
            if k.startswith('eac_theme_'):
                d_dist[rc_of(r)[0]] = d_dist.get(rc_of(r)[0], 0) + 1
        print('    donor enable = %s   idx 分布 = %s' % (d_eng[2].hex() if d_eng else '?', d_dist))
        print('    本机  enable = %s   idx 分布 = %s' % (eng[2].hex() if eng else '?', dist))
        print('    donor 是否含 NONE: %s' % ('是' if d_dist.get('NONE') else '否'))

    # ---------- 6. 结论 ----------
    print('\n[6] 结论')
    if not eng_on:
        print('    ✗ 引擎总开关 OFF：app 侧不读 per-app 配置，切档不会生效。')
        print('      修复：CLASSPATH=/data/local/tmp/fixeac2.dex app_process /system/bin io.onyx.FixEac enable 1')
    if blocked:
        print('    ✗ %d 个已安装 app 的 active theme idx = NONE ⇒ SelectRefreshModeAction 静默空转：' % len(blocked))
        for p in blocked:
            print('        - %s' % p)
        print('      修复：FixEac rmnone（全量清）或 FixEac rm <pkgCsv>（按包清）')
    if eng_on and not blocked:
        print('    ✓ 未发现 NONE 拦截，引擎已开。')
    print('\n    手动复现（A/B）：')
    print('      1. 开一个「可切」的 app 到前台 → 控制中心「优化引擎」→ 点「标准」')
    print('         → 预期 onyxconfig 多出 eac_theme_<pkg>@theme_type_3 = refresh_mode_4')
    print('      2. 对一个 **BLOCKED** 的 app 重复同样操作 → onyxconfig 不变（本次闭环的核心判据）')
    print('      3. 读回：FixEac read <pkg>  看 theme[2] 的 idx')

    if blocked:
        return 1
    if not eng_on:
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
