"""静态自检：括号配平 + 数组长度一致性（替代本地编译；gradle 按项目规矩不跑）。"""
import re
import sys

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

FILES = [
    r'C:\Users\root\Documents\eink\E-Ink-Launcher\app\src\main\java\cn\modificator\launcher\RefreshModeHelper.java',
    r'C:\Users\root\Documents\eink\E-Ink-Launcher\app\src\main\java\cn\modificator\launcher\SettingFragment.java',
    r'C:\Users\root\Documents\eink\E-Ink-Launcher\app\src\main\java\cn\modificator\launcher\Launcher.java',
    r'C:\Users\root\Documents\eink\E-Ink-Launcher\app\src\main\java\cn\modificator\launcher\GlobalEacRefreshHelper.java',
]


def strip(src):
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            i = j + 1
        else:
            out.append(c)
            i += 1
    return ''.join(out)


ok = True
for f in FILES:
    src = open(f, encoding='utf8').read()
    code = strip(src)
    counts = {p: code.count(p[0]) - code.count(p[1]) for p in ('{}', '()', '[]')}
    print(f'{f.split(chr(92))[-1]}: ' + '  '.join(f'{p}={v:+d}' for p, v in counts.items()))
    bad = [p for p, v in counts.items() if v != 0]
    if bad:
        print(f'   !! 不配平: {bad}')
        ok = False
    if not code.rstrip().endswith('}'):
        print('   !! 文件未以 } 结尾')
        ok = False

# 数组长度一致性 + 残留引用检查
p = FILES[0]
src = open(p, encoding='utf8').read()
sv = re.search(r'SCOPE_VALUES\s*=\s*\{([^}]*)\}', src).group(1)
sv_n = len([x for x in sv.split(',') if x.strip()])
mn = re.search(r'MODE_NAMES\s*=\s*\{(.*?)\};', src, re.S).group(1)
mn_n = len(re.findall(r'"[^"]+"', mn))
lb = re.search(r'LABELS\s*=\s*\{(.*?)\};', src, re.S).group(1)
lb_n = len(re.findall(r'"[^"]+"', lb))
print(f'数组长度: SCOPE_VALUES={sv_n}  MODE_NAMES={mn_n}  LABELS={lb_n}  -> '
      f'{"一致 OK" if sv_n == mn_n == lb_n else "!! 不一致"}')
if not (sv_n == mn_n == lb_n):
    ok = False

print()
print('残留 2312/DU4 引用（应只剩注释里的说明）:')
for f in FILES:
    for i, line in enumerate(open(f, encoding='utf8'), 1):
        if '2312' in line or 'DU4' in line:
            kind = '注释' if line.strip().startswith(('*', '//', '/*')) else '★代码'
            print(f'  {kind} {f.split(chr(92))[-1]}:{i}: {line.strip()[:96]}')

print()
print('自检结论:', 'PASS' if ok else 'FAIL')
sys.exit(0 if ok else 1)
