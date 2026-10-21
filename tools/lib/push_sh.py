"""把带 CRLF 的本地脚本转成 LF-only 后 push 到设备并可选执行。
用法:
  python push_sh.py <local.sh> [remote_name] [--run]
设备: 由环境变量 EINK_DEV 指定（默认故障机 6C7F0E64）。
  ⚠️ 本会话有两台 Poke6（故障机 6C7F0E64 / 正常机 6C1BF7D9），
     对正常机操作前务必 `$env:EINK_DEV='6C1BF7D9'`，否则会推到错的设备。
"""
import os, subprocess, sys

ADB = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'adb.exe')
ADB = os.path.abspath(ADB)
DEV = os.environ.get('EINK_DEV', '6C7F0E64')


def run(cmd):
    r = subprocess.run(cmd, capture_output=True)
    return (r.stdout or b'').decode('utf8', 'replace'), (r.stderr or b'').decode('utf8', 'replace')


def main():
    local = sys.argv[1]
    remote = sys.argv[2] if len(sys.argv) > 2 and not sys.argv[2].startswith('--') else os.path.basename(local)
    run_flag = '--run' in sys.argv
    data = open(local, 'rb').read().replace(b'\r\n', b'\n')
    tmp = os.path.join(os.environ.get('TEMP', '.'), 'lf_' + os.path.basename(local))
    open(tmp, 'wb').write(data)
    print('[dev %s]' % DEV)
    out, err = run([ADB, '-s', DEV, 'push', tmp, '/data/local/tmp/' + remote])
    print((out.strip() or err.strip()))
    if run_flag:
        out, err = run([ADB, '-s', DEV, 'shell', 'su -c "sh /data/local/tmp/%s"' % remote])
        print(out, end='')
        if err.strip():
            print('[stderr]', err.strip())


main()
